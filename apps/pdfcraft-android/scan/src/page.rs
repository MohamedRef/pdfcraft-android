//! The whole page pipeline: decode → straighten → look → encode, each once, with a timing per
//! stage.

use std::time::Instant;

use crate::aspect::{AspectSource, Focal, Paper, dpi_without_paper, proportions, proportions_by_sides};
use crate::encode::{Format, JPEG_QUALITY, Raster, encode_jpeg, encode_png_bilevel, stamp_jpeg_density};
use crate::enhance::DEFAULT_SHARPEN;
use crate::enhance::enlargement;
use crate::flatten::{predict_grid, unwarp, unwarp_trimmed};
use crate::flatten_guard::{Kept, MIN_LINES, Trim, border_trim, guard_with_floor};
use crate::flatten_lines::{LineFit, fit_page};
use crate::look::{Applied, Look, LookParams, Pixels, apply_full};
use crate::page_kind::{PageKind, PresetChoice, page_kind};
use crate::straightness::{Straightness, line_straightness};
use crate::upright::{Upright, turned, upright};
use crate::upright_pages::total_turn;
use crate::warp::{Rgb, rectify_with_threads};
use crate::{Luma, Photo, Quad, ScanError};

/// The stage names timings are recorded under (fixed: diagnostics never hold document data).
pub const STAGE_DECODE: &str = "scan_decode";
/// The flattening network's run; only there when flattening was asked for.
pub const STAGE_FLATTEN: &str = "scan_flatten";
pub const STAGE_RECTIFY: &str = "scan_rectify";
/// The straightened page made as well, and both measured, to choose between them; only there when
/// flattening was checked (#302).
pub const STAGE_FLATTEN_CHECK: &str = "scan_flatten_check";
/// Looking for a strip of background along a flattened page's edges, and drawing the page again
/// without it (#302).
pub const STAGE_FLATTEN_TRIM: &str = "scan_flatten_trim";
/// The page flattened from its lines of text and checked (#303, part B); only there when that
/// fallback ran.
pub const STAGE_FLATTEN_LINES: &str = "scan_flatten_lines";
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
    /// With `flatten`: keep the flattened page only when its lines of text are at least 10 %
    /// straighter than on the page straightened by its corners alone, which is made as well
    /// (#302's automatic mode and quality guard). Without it a flattened page is always kept.
    pub flatten_check: bool,
    /// When a flattened page is kept: look for a thin strip of background left along its edges
    /// (2 % of a side at most) and draw the page again without it, at the same size (#302's
    /// border trim). Off: the flattened page as the network's grid gives it.
    pub flatten_trim: bool,
    /// With `flatten`: when the network is not installed or the guard did not keep its page, fit
    /// a curled-page model to the page's lines of text and keep that page when the guard finds it
    /// straighter (#303, part B). The guard is always applied to it.
    pub line_flatten: bool,
    /// The guard keeps a flattened page (the network's with `flatten_check`, or the text lines')
    /// only when at least this many lines of text were followed on both pages (#302); 0 for no
    /// floor, the guard as before.
    pub flatten_min_lines: u32,
    /// Turn the page the right way up when the orientation classifier is sure which way that is
    /// (#339). Without the model file, and on a page it is not sure of, the page stays as the
    /// corners gave it. A whole photo in the Original look is the camera's file and is never
    /// turned.
    pub upright: bool,
    /// The person's own quarter turns clockwise (the Rotate button, #339), added to the turn the
    /// classifier gave (`upright_pages::total_turn`). The app asks for the classifier's turn or the
    /// person's, not both: a press of Rotate switches `upright` off for that page and puts the
    /// page's whole turn here, so the classifier never fights the person.
    pub turns: u8,
    /// How much the Auto look sharpens print, 0–100; 0 for none (#299).
    pub sharpen: u8,
    /// Whether the Auto look smooths the grain of a page that has a lot of it (#299).
    pub denoise: bool,
    /// How the Auto look picks its settings by the kind of document (#299): off (the look's own
    /// strength and `sharpen`), worked out from the page, or the person's choice. Other looks
    /// ignore it.
    pub preset: PresetChoice,
    /// How many sides of the page its edge detection found curved (0–4): a book page's sign (#299).
    pub curved_sides: u8,
    /// The strength was set by hand (the page's slider or a testing option, #424): a document
    /// type does not change it.
    pub fixed_strength: bool,
    /// For a preview (#301): the page shrunk so its longer side is this many pixels at most,
    /// whatever its look. `None` for the page at the size it is saved at.
    pub max_side: Option<u32>,
    /// The paper's colour as the person chose it for Auto and Whiteboard (#301): `Some(true)`
    /// keeps the paper's own colour, `Some(false)` makes it white, `None` leaves it to the
    /// document type (white unless the type keeps it).
    pub keep_paper: Option<bool>,
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
            flatten_check: false,
            flatten_trim: false,
            line_flatten: false,
            flatten_min_lines: MIN_LINES,
            upright: false,
            turns: 0,
            sharpen: DEFAULT_SHARPEN,
            denoise: true,
            preset: PresetChoice::Off,
            curved_sides: 0,
            fixed_strength: false,
            max_side: None,
            keep_paper: None,
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
    /// The quarter turns clockwise the page was given in all: the classifier's (`quarter_turns`)
    /// and the person's (`PageRequest::turns`) together, 0–3 (#339).
    pub turns: u8,
    /// The comparison that chose between flattened and straightened (#302), when it was asked for.
    pub flatten_check: Option<FlattenCheck>,
    /// The strips of background trimmed from a flattened page's edges, in pixels of this page
    /// (#302); `None` when no trim was looked for (not asked, or the page was not flattened).
    pub trim: Option<Trim>,
    /// What the text-line fallback did (#303, part B), when it ran.
    pub line_flatten: Option<LineFlatten>,
    /// The page's grain as the Auto look measured it: the standard deviation of its noise on
    /// blank paper, in grey levels of 255 (`None` for the other looks).
    pub noise: Option<f32>,
    /// The kind of document the Auto look took the page for, and its settings (#299); `None` for
    /// the other looks or with document types off.
    pub kind: Option<PageKind>,
    /// (stage, milliseconds), in pipeline order.
    pub timings: Vec<(&'static str, u32)>,
}

/// How the flattened page and the page straightened by its corners compared, when flattening was
/// checked (#302).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct FlattenCheck {
    /// The flattened page was kept.
    pub kept: bool,
    /// Why: `straighter`, `notbetter`, `unmeasured`, `straight` or `fewlines` (see [`crate::flatten_guard::Why`]).
    /// The floor on the lines followed that was in force (0 for none, #302).
    pub min_lines: u32,
    pub why: &'static str,
    /// The two pages' line measures, in pixels of this page; `None` when too few lines of text
    /// could be followed.
    pub straightened: Option<Straightness>,
    pub flattened: Option<Straightness>,
}

/// What the text-line fallback did with a page (#303, part B).
#[derive(Clone, Debug, PartialEq)]
pub struct LineFlatten {
    /// Why no surface was fitted (`few_lines`, `no_fit`, `folds`); `None` when one was.
    pub gave_up: Option<&'static str>,
    /// The fitted surface.
    pub fit: Option<LineFit>,
    /// The guard's comparison of the page flattened from the fit with the page straightened by its
    /// corners; `kept` says which one the page is.
    pub check: Option<FlattenCheck>,
}

/// A page straightened (and flattened and turned upright, when that was asked): everything before
/// its look. A preview keeps one of these and gives it each look in turn (#301).
#[derive(Clone, Debug, PartialEq)]
pub struct PageBase {
    pub page: Rgb,
    /// The page's resolution at this size.
    pub dpi: u16,
    pub paper: Paper,
    pub aspect: AspectSource,
    pub focal_mismatch: bool,
    pub flattened: bool,
    pub bend: f32,
    pub quarter_turns: u8,
    pub orientation: Option<Upright>,
    /// The classifier's turn and the person's together (#339).
    pub turns: u8,
    /// The comparison that chose between flattened and straightened (#302), when it was asked for
    /// and the network gave a page.
    pub flatten_check: Option<FlattenCheck>,
    /// The border trimmed from a flattened page (#302), when one was looked for.
    pub trim: Option<Trim>,
    /// What the text-line fallback did (#303, part B), when it ran.
    pub line_flatten: Option<LineFlatten>,
    /// (stage, milliseconds) of the stages so far.
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
    let turned_by_hand = !request.turns.is_multiple_of(4);
    if request.look != Look::Original || !whole || request.flatten || turned_by_hand || request.max_side.is_some() {
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
        turns: 0,
        flatten_check: None,
        trim: None,
        line_flatten: None,
        noise: None,
        kind: None,
        timings,
    }))
}

/// Run the pipeline on one photo.
pub fn process_page(request: &PageRequest<'_>) -> Result<PageResult, ScanError> {
    if let Some(page) = untouched(request) {
        return page;
    }
    let base = page_base(request)?;
    let mut clock = Instant::now();
    let mut lap = |stage: &'static str, timings: &mut Vec<(&'static str, u32)>| {
        let ms = u32::try_from(clock.elapsed().as_millis()).unwrap_or(u32::MAX);
        timings.push((stage, ms));
        clock = Instant::now();
    };
    let (applied, kind) = page_look_kind(&base, request)?;
    let (pixels, noise) = (applied.pixels, applied.noise);
    let PageBase {
        page,
        dpi,
        paper,
        aspect,
        focal_mismatch,
        flattened,
        bend,
        quarter_turns,
        orientation,
        turns,
        flatten_check,
        trim,
        line_flatten,
        mut timings,
    } = base;
    let (w, h) = (page.width, page.height);
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
        paper,
        aspect,
        focal_mismatch,
        flattened,
        bend,
        quarter_turns,
        orientation,
        turns,
        flatten_check,
        trim,
        line_flatten,
        noise,
        kind,
        timings,
    })
}

/// `base` with the look `request` asks for, at its strength, sharpening and smoothing (the
/// request's photo and corners are not looked at: `base` is what was made of them).
pub fn page_look(base: &PageBase, request: &PageRequest<'_>) -> Result<Applied, ScanError> {
    page_look_kind(base, request).map(|(applied, _)| applied)
}

/// The sharpening a page of a kind whose own is `kind` gets when the request asks for `asked`
/// (#424): the kind's scaled by `asked` over the default, so the default gives the kind's own and
/// 0 gives none. At most 100.
pub fn kind_sharpen(kind: u8, asked: u8) -> u8 {
    let scaled = u32::from(kind) * u32::from(asked) / u32::from(DEFAULT_SHARPEN);
    u8::try_from(scaled.min(100)).unwrap_or(100)
}

/// `page_look`, and the kind of document the Auto look took the page for (#299). With a kind,
/// its strength (unless the strength was set by hand: the slider or the testing option), its
/// sharpening (scaled by the request's, #424) and its paper colour replace the request's.
pub fn page_look_kind(base: &PageBase, request: &PageRequest<'_>) -> Result<(Applied, Option<PageKind>), ScanError> {
    let kind = match request.look {
        Look::Auto => page_kind(&base.page, base.dpi, base.paper, request.curved_sides, request.preset),
        _ => None,
    };
    let mut params = LookParams {
        strength: request.strength,
        threads: request.threads,
        dpi: f32::from(base.dpi),
        sharpen: request.sharpen,
        denoise: request.denoise,
        keep_paper: false,
    };
    if let Some(kind) = &kind {
        if !request.fixed_strength {
            params.strength = kind.params.strength;
        }
        // the request's sharpening scales the kind's (#424): the default (30) gives the kind's
        // own, 0 none, 60 twice the kind's, so each level of the testing option does something
        params.sharpen = kind_sharpen(kind.params.sharpen, request.sharpen);
        params.keep_paper = kind.params.keep_paper;
    }
    // the person's choice of paper wins over the document type's (#301)
    if let Some(keep) = request.keep_paper {
        params.keep_paper = keep;
    }
    let applied = apply_full(&base.page, request.look, &params).ok_or(ScanError::Encode)?;
    Ok((applied, kind))
}

/// The page of `request` before its look: decoded, straightened to its proportions (or
/// flattened), and turned upright.
pub fn page_base(request: &PageRequest<'_>) -> Result<PageBase, ScanError> {
    let mut timings = Vec::with_capacity(6);
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
    let scale = match (request.max_side, request.look) {
        // a preview: shrunk to fit and never enlarged, whatever the look, so that one
        // straightened page serves every look (#301)
        (Some(side), _) => {
            let (w, h, _) = shape.output(1.0)?;
            (f64::from(side.max(16)) / f64::from(w.max(h))).min(1.0)
        }
        // Auto enlarges a small page in this same resampling pass, before its tone curve (#298, step 0)
        (None, Look::Auto | Look::Whiteboard) => enlargement(shape.width, shape.height),
        // two levels need more pixels than shades of grey to draw a thin stroke: a page under
        // 200 dpi is made at twice the size (#300)
        (None, Look::BlackWhite) if shape.output(1.0)?.2 < BW_MIN_DPI => 2.0,
        _ => 1.0,
    };
    let (w, h, dpi) = shape.output(scale)?;
    let grid = if request.flatten { predict_grid(&photo, &quad) } else { None };
    if request.flatten {
        lap(STAGE_FLATTEN, &mut timings);
    }
    // a grid that cannot be used leaves the page straightened by its corners, as without the switch
    let flat = grid.as_ref().and_then(|grid| unwarp(&photo, grid, w, h, request.threads).ok());
    let (page, flatten_check, flattened) = match flat {
        Some(flat) if request.flatten_check => {
            lap(STAGE_RECTIFY, &mut timings);
            let plain = rectify_with_threads(&photo, &quad, w, h, request.threads)?;
            let check = compare_flattened(&plain, &flat, request.flatten_min_lines);
            lap(STAGE_FLATTEN_CHECK, &mut timings);
            let kept = check.kept;
            (if kept { flat } else { plain }, Some(check), kept)
        }
        Some(flat) => {
            lap(STAGE_RECTIFY, &mut timings);
            (flat, None, true)
        }
        None => {
            let plain = rectify_with_threads(&photo, &quad, w, h, request.threads)?;
            lap(STAGE_RECTIFY, &mut timings);
            (plain, None, false)
        }
    };
    // a flattened page can keep a thin strip of table along an edge: drawn again without it (#302)
    let (page, trim) = match grid.as_ref() {
        Some(grid) if flattened && request.flatten_trim => {
            let (page, trim) = trim_flattened(&photo, grid, page, request.threads);
            lap(STAGE_FLATTEN_TRIM, &mut timings);
            (page, Some(trim))
        }
        _ => (page, None),
    };
    // the network was not there or not kept: the page's lines of text may still flatten it
    let (page, flattened, line_flatten) = match request.flatten && request.line_flatten && !flattened {
        true => {
            let (lined, outcome) = flatten_by_lines(&photo, &quad, &page, request.threads, request.flatten_min_lines);
            lap(STAGE_FLATTEN_LINES, &mut timings);
            match lined {
                Some(lined) => (lined, true, Some(outcome)),
                None => (page, false, Some(outcome)),
            }
        }
        false => (page, flattened, None),
    };
    drop(photo);

    // the right way up, when the classifier is sure: the same pixels in new places, so the page
    // is still resampled once (#339)
    let orientation = if request.upright { upright(&page) } else { None };
    let quarter_turns = orientation.filter(|found| found.sure).map_or(0, |found| found.quarter_turns);
    // and the person's own turn on top (the Rotate button): still the same pixels moved
    let turns = total_turn(quarter_turns, i32::from(request.turns));
    let page = if turns == 0 {
        page
    } else {
        let upright = turned(&page, turns);
        drop(page);
        upright
    };
    if request.upright {
        lap(STAGE_UPRIGHT, &mut timings);
    }
    Ok(PageBase {
        page,
        dpi,
        paper: shape.paper,
        aspect: shape.source,
        focal_mismatch: shape.focal_mismatch,
        flattened,
        bend: grid.map_or(0.0, |g| g.bend() as f32),
        quarter_turns,
        orientation,
        turns,
        flatten_check,
        trim,
        line_flatten,
        timings,
    })
}

/// A flattened page without the strip of background along its edges, if it has one (#302): the
/// trim [`border_trim`] finds, and the page drawn again from the photo without it. The page as it
/// was, and no trim, when there is none or the second drawing fails.
fn trim_flattened(photo: &Photo, grid: &crate::flatten::FlattenGrid, page: Rgb, threads: usize) -> (Rgb, Trim) {
    let trim = border_trim(&page);
    if trim == Trim::default() {
        return (page, trim);
    }
    match unwarp_trimmed(photo, grid, page.width, page.height, trim, threads) {
        Ok(again) => (again, trim),
        Err(_) => (page, Trim::default()),
    }
}

/// The text-line fallback on `plain`, the page straightened by `quad` (#303, part B): the page
/// flattened from its lines when the guard keeps it, and what was found either way.
pub(crate) fn flatten_by_lines(photo: &Photo, quad: &Quad, plain: &Rgb, threads: usize, min_lines: u32) -> (Option<Rgb>, LineFlatten) {
    let fit = match fit_page(&grey(plain)) {
        Ok(fit) => fit,
        Err(why) => return (None, LineFlatten { gave_up: Some(why.id()), fit: None, check: None }),
    };
    let grid = match fit.grid(quad) {
        Ok(grid) => grid,
        Err(why) => return (None, LineFlatten { gave_up: Some(why.id()), fit: Some(fit), check: None }),
    };
    let Ok(lined) = unwarp(photo, &grid, plain.width, plain.height, threads) else {
        return (None, LineFlatten { gave_up: Some(crate::flatten_lines::GaveUp::NoFit.id()), fit: Some(fit), check: None });
    };
    let check = compare_flattened(plain, &lined, min_lines);
    let outcome = LineFlatten { gave_up: None, fit: Some(fit), check: Some(check) };
    (check.kept.then_some(lined), outcome)
}

/// Measures the lines of text on the page straightened by its corners and on the flattened one,
/// and keeps the flattened page only when it is clearly straighter (#302, the quality guard) and
/// at least `min_lines` lines were followed on both (0: no floor).
pub fn compare_flattened(straightened: &Rgb, flattened: &Rgb, min_lines: u32) -> FlattenCheck {
    let (s, f) = (line_straightness(&grey(straightened)), line_straightness(&grey(flattened)));
    let decision = guard_with_floor(s.map(|m| m.px), f.map(|m| m.px), (s.map(|m| m.lines), f.map(|m| m.lines)), min_lines);
    FlattenCheck { kept: decision.kept == Kept::Flattened, why: decision.why.id(), min_lines, straightened: s, flattened: f }
}

/// The page in shades of grey, for measuring.
fn grey(page: &Rgb) -> Luma {
    let data = page.data.chunks_exact(3).map(|p| crate::photo::luma([p[0], p[1], p[2]])).collect();
    Luma { width: page.width, height: page.height, data }
}
