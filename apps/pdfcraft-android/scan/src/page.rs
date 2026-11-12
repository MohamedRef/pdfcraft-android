//! The whole page pipeline: decode → straighten → look → encode, each once, with a timing per
//! stage.

use std::time::Instant;

use crate::aspect::{AspectSource, Focal, Paper, dpi_without_paper, proportions, proportions_by_sides};
use crate::encode::{Format, JPEG_QUALITY, Raster, encode_jpeg, encode_png_bilevel, stamp_jpeg_density};
use crate::enhance::DEFAULT_SHARPEN;
use crate::enhance::enlargement;
use crate::flatten::{predict_grid, unwarp};
use crate::look::{Look, LookParams, Pixels, apply_full};
use crate::upright::{Upright, turned, upright};
use crate::warp::rectify_with_threads;
use crate::{Photo, Quad, ScanError};

/// The stage names timings are recorded under (fixed: diagnostics never hold document data).
pub const STAGE_DECODE: &str = "scan_decode";
/// The flattening network's run; only there when flattening was asked for.
pub const STAGE_FLATTEN: &str = "scan_flatten";
pub const STAGE_RECTIFY: &str = "scan_rectify";
/// The orientation classifier's run and the turn; only there when turning upright was asked for.
pub const STAGE_UPRIGHT: &str = "scan_upright";
pub const STAGE_LOOK: &str = "scan_look";
pub const STAGE_ENCODE: &str = "scan_encode";

/// What to make of one photo.
#[derive(Clone, Debug)]
pub struct PageRequest<'a> {
    /// The camera file's bytes (JPEG or PNG).
    pub photo: &'a [u8],
    /// The page's corners as shares (0–1) of the upright photo: x1, y1 … x4, y4.
    pub corners: [f32; 8],
    pub look: Look,
    /// 0–100.
    pub strength: u8,
    /// Threads to straighten with (1–4); the bytes are the same for every value.
    pub threads: usize,
    /// The focal length the photo was taken at as a 35 mm equivalent, in millimetres and with
    /// any zoom included, when the caller knows it better than the photo's own EXIF block
    /// (`None`: use that block, else the corners alone).
    pub focal_35mm: Option<f32>,
    /// Match the page to standard paper sizes (A4, Letter, …) when its shape is within 3 %.
    pub snap_paper: bool,
    /// Take the page's proportions from the lengths of its sides alone, as before #293 (a testing
    /// choice, #288).
    pub side_proportions: bool,
    /// Flatten the page with the flattening network instead of the perspective warp (#302): for
    /// pages that curl, sag or were folded. Off by default: on a flat page the network's answer is
    /// a little less exact than the perspective warp, and it cannot tell which kind it is looking
    /// at. Without the model file the perspective warp is used.
    pub flatten: bool,
    /// Turn the page the right way up when the orientation classifier is sure which way that is
    /// (#339). Without the model file, and on a page it is not sure of, the page stays as the
    /// corners gave it. A whole photo in the Original look is the camera's file and is never
    /// turned.
    pub upright: bool,
    /// How much the Auto look sharpens print, 0–100; 0 for none (#299).
    pub sharpen: u8,
    /// Whether the Auto look smooths the grain of a page that has a lot of it (#299).
    pub denoise: bool,
}

impl<'a> PageRequest<'a> {
    /// A request with the defaults: the colour look at strength 50, one thread, the photo's own
    /// lens data, paper sizes matched.
    pub fn new(photo: &'a [u8], corners: [f32; 8]) -> Self {
        PageRequest {
            photo,
            corners,
            look: Look::default(),
            strength: 50,
            threads: 1,
            focal_35mm: None,
            snap_paper: true,
            side_proportions: false,
            flatten: false,
            upright: false,
            sharpen: DEFAULT_SHARPEN,
            denoise: true,
        }
    }
}

/// One finished page.
#[derive(Clone, Debug, PartialEq)]
pub struct PageResult {
    /// The encoded page.
    pub bytes: Vec<u8>,
    pub format: Format,
    pub width: u32,
    pub height: u32,
    /// The resolution written into the file: the paper's real size for a standard paper, else
    /// the long side taken as 297 mm.
    pub dpi: f32,
    /// The standard paper the page was matched to, or `Custom`.
    pub paper: Paper,
    /// How the page's proportions were worked out.
    pub aspect: AspectSource,
    /// The camera's focal length and the one the corners imply disagree (a cropped photo, a page
    /// that is not flat, or wrong corners).
    pub focal_mismatch: bool,
    /// The page was flattened by the network (asked for, and the model was there).
    pub flattened: bool,
    /// How far the network found the page from flat, as a share of its diagonal (0 when it was
    /// not asked).
    pub bend: f32,
    /// The quarter turns clockwise the page was given to stand upright (0 when it was not asked,
    /// already upright, or the classifier was not sure).
    pub quarter_turns: u8,
    /// What the orientation classifier found (`None` when it was not asked or has no model file).
    pub orientation: Option<Upright>,
    /// The page's grain as the Auto look measured it: the standard deviation of its noise on
    /// blank paper, in grey levels of 255 (`None` for the other looks).
    pub noise: Option<f32>,
    /// (stage, milliseconds), in pipeline order.
    pub timings: Vec<(&'static str, u32)>,
}

/// A black-and-white page under this resolution is made at twice the size.
const BW_MIN_DPI: u16 = 200;

/// The corners of a page that is the whole photo.
pub const WHOLE_PHOTO: [f32; 8] = [0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0];

/// A whole JPEG photo in the Original look stays the camera's own file: only its resolution is
/// written in (the long side as long as A4), so its PDF page has a sensible size beside the
/// other pages. `None` when the request asks for more than that.
fn untouched(request: &PageRequest<'_>) -> Option<Result<PageResult, ScanError>> {
    let whole = request.corners.iter().zip(WHOLE_PHOTO).all(|(a, b)| (a - b).abs() < 1e-6);
    if request.look != Look::Original || !whole || request.flatten {
        return None;
    }
    let (width, height) = match Photo::upright_size(request.photo) {
        Ok((w, h, true)) => (w, h),
        Ok(_) => return None,
        Err(e) => return Some(Err(e)),
    };
    let dpi = dpi_without_paper(f64::from(width.max(height)));
    let bytes = stamp_jpeg_density(request.photo, dpi)?;
    let timings = [STAGE_DECODE, STAGE_RECTIFY, STAGE_LOOK, STAGE_ENCODE].map(|stage| (stage, 0)).to_vec();
    Some(Ok(PageResult {
        bytes,
        format: Format::Jpeg,
        width,
        height,
        dpi: f32::from(dpi),
        paper: Paper::Custom,
        aspect: AspectSource::SideLengths,
        focal_mismatch: false,
        flattened: false,
        bend: 0.0,
        quarter_turns: 0,
        orientation: None,
        noise: None,
        timings,
    }))
}

/// Run the pipeline on one photo.
pub fn process_page(request: &PageRequest<'_>) -> Result<PageResult, ScanError> {
    if let Some(page) = untouched(request) {
        return page;
    }
    let mut timings = Vec::with_capacity(4);
    let mut clock = Instant::now();
    let mut lap = |stage: &'static str, timings: &mut Vec<(&'static str, u32)>| {
        let ms = u32::try_from(clock.elapsed().as_millis()).unwrap_or(u32::MAX);
        timings.push((stage, ms));
        clock = Instant::now();
    };

    let (photo, lens) = Photo::decode_with_camera(request.photo)?;
    lap(STAGE_DECODE, &mut timings);

    let quad = Quad::from_shares(&request.corners, photo.width, photo.height)?;
    // the caller's focal length is the one in use (zoom included); the file's may or may not be
    let camera = match request.focal_35mm {
        Some(f) => Focal::from_35mm(Some(f), None, photo.width, photo.height),
        None => Focal::from_35mm(lens.focal_35mm, lens.digital_zoom, photo.width, photo.height),
    };
    let shape = match request.side_proportions {
        true => proportions_by_sides(&quad, photo.width, photo.height, request.snap_paper),
        false => proportions(&quad, photo.width, photo.height, camera, request.snap_paper),
    };
    let scale = match request.look {
        // Auto enlarges a small page in this same resampling pass, before its tone curve (#298, step 0)
        Look::Auto => enlargement(shape.width, shape.height),
        // two levels need more pixels than shades of grey to draw a thin stroke: a page under
        // 200 dpi is made at twice the size (#300)
        Look::BlackWhite if shape.output(1.0)?.2 < BW_MIN_DPI => 2.0,
        _ => 1.0,
    };
    let (w, h, dpi) = shape.output(scale)?;
    let grid = if request.flatten { predict_grid(&photo, &quad) } else { None };
    if request.flatten {
        lap(STAGE_FLATTEN, &mut timings);
    }
    // a grid that cannot be used leaves the page straightened by its corners, as without the switch
    let flat = grid.as_ref().and_then(|grid| unwarp(&photo, grid, w, h, request.threads).ok());
    let flattened = flat.is_some();
    let page = match flat {
        Some(page) => page,
        None => rectify_with_threads(&photo, &quad, w, h, request.threads)?,
    };
    drop(photo);
    lap(STAGE_RECTIFY, &mut timings);

    // the right way up, when the classifier is sure: the same pixels in new places, so the page
    // is still resampled once (#339)
    let orientation = if request.upright { upright(&page) } else { None };
    let quarter_turns = orientation.filter(|found| found.sure).map_or(0, |found| found.quarter_turns);
    let page = if quarter_turns == 0 {
        page
    } else {
        let upright = turned(&page, quarter_turns);
        drop(page);
        upright
    };
    let (w, h) = (page.width, page.height);
    if request.upright {
        lap(STAGE_UPRIGHT, &mut timings);
    }

    let params =
        LookParams { strength: request.strength, threads: request.threads, dpi: f32::from(dpi), sharpen: request.sharpen, denoise: request.denoise };
    let applied = apply_full(&page, request.look, &params).ok_or(ScanError::Encode)?;
    let (pixels, noise) = (applied.pixels, applied.noise);
    drop(page);
    lap(STAGE_LOOK, &mut timings);

    let raster = |pixels, grey| Raster { pixels, grey, width: w, height: h, dpi };
    let (bytes, format) = match &pixels {
        Pixels::Rgb(rgb) => (encode_jpeg(&raster(rgb, false), JPEG_QUALITY)?, Format::Jpeg),
        Pixels::Grey(grey) => (encode_jpeg(&raster(grey, true), JPEG_QUALITY)?, Format::Jpeg),
        Pixels::Bilevel(bits) => (encode_png_bilevel(bits, dpi)?, Format::Png),
    };
    lap(STAGE_ENCODE, &mut timings);

    Ok(PageResult {
        bytes,
        format,
        width: w,
        height: h,
        dpi: f32::from(dpi),
        paper: shape.paper,
        aspect: shape.source,
        focal_mismatch: shape.focal_mismatch,
        flattened,
        bend: grid.map_or(0.0, |g| g.bend() as f32),
        quarter_turns,
        orientation,
        noise,
        timings,
    })
}
