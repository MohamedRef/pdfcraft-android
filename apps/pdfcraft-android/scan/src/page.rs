//! The whole page pipeline: decode → straighten → look → encode, each once, with a timing per
//! stage.

use std::time::Instant;

use crate::aspect::{AspectSource, Focal, Paper, dpi_without_paper, proportions};
use crate::encode::{Format, JPEG_QUALITY, Raster, encode_jpeg, encode_png_grey, stamp_jpeg_density};
use crate::enhance::enlargement;
use crate::look::{Look, Pixels, apply_with_threads};
use crate::warp::rectify_with_threads;
use crate::{Photo, Quad, ScanError};

/// The stage names timings are recorded under (fixed: diagnostics never hold document data).
pub const STAGE_DECODE: &str = "scan_decode";
pub const STAGE_RECTIFY: &str = "scan_rectify";
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
}

impl<'a> PageRequest<'a> {
    /// A request with the defaults: the colour look at strength 50, one thread, the photo's own
    /// lens data, paper sizes matched.
    pub fn new(photo: &'a [u8], corners: [f32; 8]) -> Self {
        PageRequest { photo, corners, look: Look::default(), strength: 50, threads: 1, focal_35mm: None, snap_paper: true }
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
    /// (stage, milliseconds), in pipeline order.
    pub timings: Vec<(&'static str, u32)>,
}

/// The corners of a page that is the whole photo.
pub const WHOLE_PHOTO: [f32; 8] = [0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0];

/// A whole JPEG photo in the Original look stays the camera's own file: only its resolution is
/// written in (the long side as long as A4), so its PDF page has a sensible size beside the
/// other pages. `None` when the request asks for more than that.
fn untouched(request: &PageRequest<'_>) -> Option<Result<PageResult, ScanError>> {
    let whole = request.corners.iter().zip(WHOLE_PHOTO).all(|(a, b)| (a - b).abs() < 1e-6);
    if request.look != Look::Original || !whole {
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
    let shape = proportions(&quad, photo.width, photo.height, camera, request.snap_paper);
    // Auto enlarges a small page in this same resampling pass, before its tone curve (#298, step 0)
    let scale = if request.look == Look::Auto { enlargement(shape.width, shape.height) } else { 1.0 };
    let (w, h, dpi) = shape.output(scale)?;
    let page = rectify_with_threads(&photo, &quad, w, h, request.threads)?;
    drop(photo);
    lap(STAGE_RECTIFY, &mut timings);

    let pixels = apply_with_threads(&page, request.look, request.strength, request.threads).ok_or(ScanError::Encode)?;
    drop(page);
    lap(STAGE_LOOK, &mut timings);

    let raster = |pixels, grey| Raster { pixels, grey, width: w, height: h, dpi };
    let (bytes, format) = match &pixels {
        Pixels::Rgb(rgb) => (encode_jpeg(&raster(rgb, false), JPEG_QUALITY)?, Format::Jpeg),
        Pixels::Grey(grey) if request.look == Look::BlackWhite => (encode_png_grey(&raster(grey, true))?, Format::Png),
        Pixels::Grey(grey) => (encode_jpeg(&raster(grey, true), JPEG_QUALITY)?, Format::Jpeg),
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
        timings,
    })
}
