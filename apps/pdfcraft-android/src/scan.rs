//! Scan (#287): one photo → one straightened, cleaned page image, through `pdfcraft-scan`.
//!
//! The functions only read and write inside the directory given to [`scan_set_root`] (the app's
//! cache directory): both paths are resolved (links followed) and checked against it.

use std::path::{Path, PathBuf};
use std::sync::RwLock;

use pdfcraft_scan::page::page_look_kind;
use pdfcraft_scan::page_kind::{PageKind, PresetChoice, preset_from_id};
use pdfcraft_scan::upright_pages::{PageTurn, followed, settle};
use pdfcraft_scan::{
    AspectSource, Finder, Format, Look, PageBase, PageRequest, Paper, Photo, Pyramid, Quad, QuadSource, RefineOptions, detect_frame, detect_with,
    page_base, process_page, refine,
};

use crate::MobileError;

/// A page's look.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ScanLook {
    Original,
    Colour,
    Greyscale,
    BlackWhite,
    /// Like a flatbed scan in one step (#298).
    Auto,
}

/// The format a page image was written in.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ScanFormat {
    Jpeg,
    Png,
}

/// How long one stage of a page took.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ScanTiming {
    pub stage: String,
    pub ms: u32,
}

/// One photo to turn into a page.
#[derive(Debug, Clone, uniffi::Record)]
pub struct ScanPageRequest {
    /// The camera file.
    pub photo_path: String,
    /// 8 shares (0–1) of the upright photo: x1, y1 … x4, y4 (top-left, top-right,
    /// bottom-right, bottom-left).
    pub corners: Vec<f32>,
    pub look: ScanLook,
    /// 0–100.
    pub strength: u8,
    /// Where the page image is written; `.jpg` or `.png` is added.
    pub output_path: String,
    /// The camera's focal length as a 35 mm equivalent, in millimetres, when the caller knows it
    /// (the in-app camera will). `None`: the photo's own EXIF block is used, else the corners.
    #[uniffi(default = None)]
    pub focal_35mm: Option<f32>,
    /// Match the page to standard paper sizes (A4, Letter, …) when its shape is within 3 %.
    #[uniffi(default = true)]
    pub snap_paper: bool,
    /// Flatten a curled, sagging or folded page with the flattening network (#302) instead of
    /// the perspective warp. Off unless asked for; see [`scan_can_flatten`].
    #[uniffi(default = false)]
    pub flatten: bool,
    /// With `flatten`: make the page straightened by its corners as well, and keep the flattened
    /// page only when its lines of text are at least 10 % straighter (#302's automatic mode).
    #[uniffi(default = false)]
    pub flatten_check: bool,
    /// Take the page's proportions from the lengths of its sides alone, as before #293. A testing
    /// choice (#288).
    #[uniffi(default = false)]
    pub side_proportions: bool,
    /// Turn the page the right way up when the orientation classifier is sure which way that is
    /// (#339). A whole photo in the Original look is never turned.
    #[uniffi(default = true)]
    pub upright: bool,
    /// The person's own quarter turns clockwise (the Rotate button, #339), added to the
    /// classifier's turn. A press of Rotate switches `upright` off for that page and puts its
    /// whole turn here.
    #[uniffi(default = 0)]
    pub turns: u8,
    /// How much the Auto look sharpens print, 0–100; 0 for none (#299).
    #[uniffi(default = 30)]
    pub sharpen: u8,
    /// Whether the Auto look smooths the grain of a page that has a lot of it (#299).
    #[uniffi(default = true)]
    pub denoise: bool,
    /// How the Auto look picks its settings by the kind of document (#299): "off" (the look's own
    /// strength and `sharpen`, as before), "auto" (worked out from the page), or the word of the
    /// kind the person chose ("receipt", "id_card", …). Anything else is "off".
    #[uniffi(default = "off")]
    pub preset: String,
    /// How many sides of the page its edge detection found curved (0–4), a book page's sign (#299).
    #[uniffi(default = 0)]
    pub curved_sides: u8,
    /// The strength was set by hand (a testing option): a document type does not change it.
    #[uniffi(default = false)]
    pub fixed_strength: bool,
}

/// The kind of document the Auto look took a page for, its settings, and what the page measured
/// as (#299). Numbers and short words only: it goes into the diagnostics log as it is.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ScanPageKind {
    /// "photo", "id_card", "receipt", "whiteboard", "book_page", "coloured_form", "photo_rich" or "document".
    pub preset: String,
    /// The person chose it; false when it was worked out from the page.
    pub chosen: bool,
    pub strength: u8,
    pub sharpen: u8,
    pub keep_paper: bool,
    pub suggest_flatten: bool,
    pub suggest_greyscale: bool,
    /// Longer side over shorter.
    pub ratio: f32,
    pub ink_share: f32,
    pub colour_ink_share: f32,
    pub photo_share: f32,
    /// Median stroke width in millimetres; -1 when there is no ink.
    pub stroke_mm: f32,
    pub glare_share: f32,
    pub gutter_band: bool,
    pub whole_photo: bool,
}

fn kind_of(kind: &PageKind) -> ScanPageKind {
    let f = &kind.features;
    ScanPageKind {
        preset: kind.preset.id().into(),
        chosen: kind.chosen,
        strength: kind.params.strength,
        sharpen: kind.params.sharpen,
        keep_paper: kind.params.keep_paper,
        suggest_flatten: kind.params.suggest_flatten,
        suggest_greyscale: kind.params.suggest_greyscale,
        ratio: f.ratio as f32,
        ink_share: f.ink_share as f32,
        colour_ink_share: f.colour_ink_share as f32,
        photo_share: f.photo_share as f32,
        stroke_mm: f.stroke_mm.map_or(-1.0, |v| v as f32),
        glare_share: f.glare_share as f32,
        gutter_band: f.gutter_band,
        whole_photo: kind.whole_photo,
    }
}

fn preset_choice(word: &str) -> PresetChoice {
    match word {
        "auto" => PresetChoice::Auto,
        other => preset_from_id(other).map_or(PresetChoice::Off, PresetChoice::Fixed),
    }
}

/// The standard paper a page was matched to.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ScanPaper {
    A4,
    A5,
    Letter,
    Legal,
    IdCard,
    Square,
    /// No standard size.
    Custom,
}

/// How a page's proportions were worked out (#293).
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ScanAspectSource {
    /// From the corners and the camera's focal length.
    KnownFocal,
    /// From the corners alone.
    EstimatedFocal,
    /// From the corners and a typical phone camera's focal length (the corners say little about
    /// it: the page is tilted one way only, or hardly at all).
    TypicalFocal,
    /// From the lengths of the sides (corners no rectangle gives).
    SideLengths,
}

/// The page written.
#[derive(Debug, Clone, uniffi::Record)]
pub struct ScanPageResult {
    pub path: String,
    pub width: u32,
    pub height: u32,
    pub format: ScanFormat,
    /// The resolution written into the file: the paper's real size for a standard paper, else
    /// the long side taken as 297 mm.
    pub dpi: f32,
    pub paper: ScanPaper,
    pub aspect_source: ScanAspectSource,
    /// The camera's focal length and the one the corners imply disagree.
    pub focal_mismatch: bool,
    /// The page was flattened by the network.
    pub flattened: bool,
    /// How far from flat the network found the page, as a share of its diagonal (0 when it was
    /// not asked).
    pub bend: f32,
    /// How the flattened and the straightened page compared, when that was asked (#302).
    pub flatten_check: Option<ScanFlattenCheck>,
    /// The quarter turns clockwise the page was given to stand upright (#339): 0 when it was not
    /// asked, already upright, or the classifier was not sure.
    pub quarter_turns: u8,
    /// What the orientation classifier made of the page; `None` when it was not asked or its
    /// model file is not installed.
    pub orientation: Option<ScanOrientation>,
    /// The quarter turns clockwise the page was given in all: the classifier's and the person's
    /// together, 0–3 (#339).
    pub turns: u8,
    /// The page's grain as the Auto look measured it (#299): the standard deviation of its noise
    /// on blank paper, in grey levels of 255. `None` for the other looks.
    pub noise: Option<f32>,
    /// The kind of document the Auto look took the page for (#299); `None` for the other looks or
    /// with document types off.
    pub kind: Option<ScanPageKind>,
    pub timings: Vec<ScanTiming>,
}

/// What the orientation classifier made of a page (#339).
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct ScanOrientation {
    /// The quarter turns clockwise that would put the page upright (0–3), acted on or not.
    pub quarter_turns: u8,
    /// The share of the vote that answer got (0–1; clean print gives about 0.85).
    pub confidence: f32,
    /// The share of the tiles looked at that gave that answer themselves.
    pub agreement: f32,
    /// How many tiles of the page were looked at (0 for an empty page).
    pub tiles: u8,
    /// Sure enough to act on.
    pub sure: bool,
}

/// A page's corners after a closer look at the photo (#291).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ScanQuad {
    /// x1, y1 … x4, y4 as shares (0–1) of the upright photo: top-left, top-right, bottom-right,
    /// bottom-left.
    pub corners: Vec<f32>,
    /// For the top, right, bottom and left sides: the side was moved onto an edge found in the
    /// photo (`false`: no convincing edge near it, it is where it was given).
    pub refined: Vec<bool>,
    /// For the same sides: the edge bends, so the page is not flat.
    pub curved: Vec<bool>,
    pub timings: Vec<ScanTiming>,
}

/// What found a page in a photo (#290).
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ScanQuadSource {
    /// The detector network.
    Model,
    /// The line detector (the network's model file is not installed).
    Lines,
    /// Nothing convincing was found.
    None,
}

/// The page found in a photo, its corners already moved onto the paper's edges (#290, #291).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ScanDocument {
    /// Whether a page was found. Without one, `corners` is the whole photo.
    pub found: bool,
    /// x1, y1 … x4, y4 as shares (0–1) of the upright photo: top-left, top-right, bottom-right,
    /// bottom-left.
    pub corners: Vec<f32>,
    /// 0–1, how sure the search was.
    pub confidence: f32,
    pub source: ScanQuadSource,
    /// The angle (degrees) the photo was shown to the network at for the answer it gave.
    pub angle: i16,
    /// How many times the network looked.
    pub passes: u8,
    /// For the top, right, bottom and left sides: moved onto an edge found in the full-size photo.
    pub refined: Vec<bool>,
    /// For the same sides: the edge bends, so the page is not flat.
    pub curved: Vec<bool>,
    pub timings: Vec<ScanTiming>,
}

/// The one directory scan functions read from and write to.
pub(crate) static SCAN_ROOT: RwLock<Option<PathBuf>> = RwLock::new(None);

/// Set the directory scan functions may use (the app's cache directory). Ignored unless it is an
/// existing absolute directory.
#[uniffi::export]
pub fn scan_set_root(root: String) {
    let path = Path::new(&root);
    if !path.is_absolute() {
        return;
    }
    if let Ok(canonical) = path.canonicalize()
        && canonical.is_dir()
    {
        *SCAN_ROOT.write().unwrap_or_else(std::sync::PoisonError::into_inner) = Some(canonical);
    }
}

/// Straighten the page in a photo, give it a look and write it once (see [`ScanPageRequest`]).
#[uniffi::export]
pub fn scan_process_page(request: ScanPageRequest) -> Result<ScanPageResult, MobileError> {
    let root = SCAN_ROOT.read().unwrap_or_else(std::sync::PoisonError::into_inner).clone();
    let root = root.ok_or_else(|| MobileError::Command("scanning is not ready yet".into()))?;
    process_in(&root, &request)
}

/// The largest camera file read (a 64 MP PNG is under 200 MB).
const MAX_PHOTO_BYTES: u64 = 256 << 20;

/// The bytes of the photo at `path`, which must be inside `root`.
pub(crate) fn read_photo(root: &Path, path: &str) -> Result<Vec<u8>, MobileError> {
    let unreadable = || MobileError::Command("the photo could not be read".into());
    let photo_path = Path::new(path).canonicalize().map_err(|_| unreadable())?;
    if !photo_path.starts_with(root) {
        return Err(MobileError::Command("the scan's files must be in the app's own folder".into()));
    }
    if std::fs::metadata(&photo_path).map_err(|_| unreadable())?.len() > MAX_PHOTO_BYTES {
        return Err(MobileError::Command("the photo is too large".into()));
    }
    std::fs::read(&photo_path).map_err(|_| unreadable())
}

/// Move a page's corners onto the paper's edges as they are in the full-size photo (#291). A
/// detector sees a small copy of the photo, so its corners are several pixels off at full size.
/// `sides` says which of the top, right, bottom and left sides to look at again (all four when it
/// is not four values); `search_share` is how far from each side to look, as a share of the
/// photo's diagonal (0 for the usual 1.5 %). A side with no convincing edge near it stays.
#[uniffi::export]
pub fn scan_refine_quad(photo_path: String, corners: Vec<f32>, sides: Vec<bool>, search_share: f32) -> Result<ScanQuad, MobileError> {
    let root = SCAN_ROOT.read().unwrap_or_else(std::sync::PoisonError::into_inner).clone();
    let root = root.ok_or_else(|| MobileError::Command("scanning is not ready yet".into()))?;
    refine_in(&root, &photo_path, &corners, &sides, search_share)
}

/// Whether pages can be flattened (#302): the flattening network's model file is installed.
#[uniffi::export]
pub fn scan_can_flatten() -> bool {
    pdfcraft_scan::models::state(pdfcraft_scan::models::FLATTEN) == pdfcraft_scan::models::ModelState::Ready
}

/// The page in one camera analysis frame (#295).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ScanLiveQuad {
    /// x1, y1 … x4, y4 as shares (0–1) of the upright frame: top-left, top-right, bottom-right,
    /// bottom-left. The whole frame when nothing was found.
    pub points: Vec<f32>,
    /// 0–1, how sure the detector was.
    pub confidence: f32,
    /// The frame's mean brightness (BT.601 luma), 0–255.
    pub mean_luma: f32,
    pub found: bool,
    /// A corner lies within 1.5 % of the frame's edge (only when something was found).
    pub cut_off: bool,
    /// How long the call took, in milliseconds.
    pub ms: u32,
}

/// Find the page in one camera analysis frame, for the outline in the viewfinder (#295).
/// `rgba` holds `width × height` RGBA pixels, rows packed (`width × 4` bytes each);
/// `rotation_degrees` is the clockwise turn that makes the frame upright (CameraX's
/// `imageInfo.rotationDegrees`: 0, 90, 180 or 270), and the corners come back in shares of the
/// upright frame. The detector network looks once and the sides are refined lightly on the frame
/// itself; when the network finds nothing, `found` is false (the line detector is asked only when
/// the model file is not installed). A frame of no pixels, over 2 MP, with too few bytes or
/// another turn is refused.
#[uniffi::export]
pub fn scan_detect_frame(rgba: Vec<u8>, width: u32, height: u32, rotation_degrees: u32) -> Result<ScanLiveQuad, MobileError> {
    let clock = std::time::Instant::now();
    let found = detect_frame(&rgba, width, height, rotation_degrees).map_err(|e| MobileError::Command(e.to_string()))?;
    Ok(ScanLiveQuad {
        points: found.points.to_vec(),
        confidence: found.confidence,
        mean_luma: found.mean_luma,
        found: found.found,
        cut_off: found.cut_off,
        ms: u32::try_from(clock.elapsed().as_millis()).unwrap_or(u32::MAX),
    })
}

/// Which detector finds the page (#288). The scanner's own choice is `Auto`; the others are for
/// testing, so a page found badly can be pinned on the detector that found it.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ScanFinder {
    /// The network when its model file is there, else the line detector.
    Auto,
    /// The network alone (#290).
    Network,
    /// The line detector alone, as before the network.
    Lines,
    /// Nothing is looked for: the page is the whole photo.
    Off,
}

/// How a photo is searched for its page (#288). `ScanFindOptions::default()` is what
/// [`scan_find_document`] does.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct ScanFindOptions {
    pub finder: ScanFinder,
    /// Move the corners found onto the paper's edges at full size (#291).
    pub refine: bool,
}

impl Default for ScanFindOptions {
    fn default() -> Self {
        ScanFindOptions { finder: ScanFinder::Auto, refine: true }
    }
}

/// Find the page in a photo and move its corners onto the paper's edges: the photo is decoded
/// once, the detector looks at a small copy of it (#290), and each side is then looked at again
/// at full size (#291). When nothing convincing is found, `found` is false and the corners are
/// the whole photo.
#[uniffi::export]
pub fn scan_find_document(photo_path: String) -> Result<ScanDocument, MobileError> {
    scan_find_document_with(photo_path, ScanFindOptions::default())
}

/// [`scan_find_document`] with the detector named and the closer look at the edges on or off:
/// the scanner's testing options (#288).
#[uniffi::export]
pub fn scan_find_document_with(photo_path: String, options: ScanFindOptions) -> Result<ScanDocument, MobileError> {
    let root = SCAN_ROOT.read().unwrap_or_else(std::sync::PoisonError::into_inner).clone();
    let root = root.ok_or_else(|| MobileError::Command("scanning is not ready yet".into()))?;
    find_in(&root, &photo_path, options)
}

pub(crate) fn find_in(root: &Path, photo_path: &str, options: ScanFindOptions) -> Result<ScanDocument, MobileError> {
    let bytes = read_photo(root, photo_path)?;
    let mut timings = Vec::with_capacity(3);
    let mut clock = std::time::Instant::now();
    let mut lap = |stage: &str, timings: &mut Vec<ScanTiming>| {
        timings.push(ScanTiming { stage: stage.into(), ms: u32::try_from(clock.elapsed().as_millis()).unwrap_or(u32::MAX) });
        clock = std::time::Instant::now();
    };
    let photo = Photo::decode(&bytes).map_err(|e| MobileError::Command(e.to_string()))?;
    drop(bytes);
    lap("scan_decode", &mut timings);
    let found = detect_with(
        &photo,
        match options.finder {
            ScanFinder::Auto => Finder::Auto,
            ScanFinder::Network => Finder::Network,
            ScanFinder::Lines => Finder::Lines,
            ScanFinder::Off => Finder::Off,
        },
    );
    lap("scan_detect", &mut timings);
    let source = match found.source {
        QuadSource::Model => ScanQuadSource::Model,
        QuadSource::Lines => ScanQuadSource::Lines,
        QuadSource::None => ScanQuadSource::None,
    };
    let (width, height) = (photo.width, photo.height);
    let Some(quad) = found.quad else {
        let whole = vec![0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0];
        return Ok(ScanDocument {
            found: false,
            corners: whole,
            confidence: found.confidence,
            source,
            angle: found.angle,
            passes: found.passes,
            refined: vec![false; 4],
            curved: vec![false; 4],
            timings,
        });
    };
    if !options.refine {
        // the detector's own corners, as it saw them on its small copy of the photo
        return Ok(ScanDocument {
            found: true,
            corners: quad.to_shares(width, height).to_vec(),
            confidence: found.confidence,
            source,
            angle: found.angle,
            passes: found.passes,
            refined: vec![false; 4],
            curved: vec![false; 4],
            timings,
        });
    }
    let pyramid = Pyramid::new(photo.luma());
    drop(photo);
    let refined = refine(&pyramid, &quad, &RefineOptions::default());
    lap("scan_refine", &mut timings);
    Ok(ScanDocument {
        found: true,
        corners: refined.quad.to_shares(width, height).to_vec(),
        confidence: found.confidence,
        source,
        angle: found.angle,
        passes: found.passes,
        refined: refined.sides.iter().map(|s| s.refined).collect(),
        curved: refined.sides.iter().map(|s| s.curved).collect(),
        timings,
    })
}

pub(crate) fn refine_in(root: &Path, photo_path: &str, corners: &[f32], sides: &[bool], search_share: f32) -> Result<ScanQuad, MobileError> {
    let corners: [f32; 8] = corners.try_into().map_err(|_| MobileError::Command("a page needs four corners".into()))?;
    let bytes = read_photo(root, photo_path)?;
    let mut timings = Vec::with_capacity(2);
    let mut clock = std::time::Instant::now();
    let mut lap = |stage: &str, timings: &mut Vec<ScanTiming>| {
        timings.push(ScanTiming { stage: stage.into(), ms: u32::try_from(clock.elapsed().as_millis()).unwrap_or(u32::MAX) });
        clock = std::time::Instant::now();
    };
    let photo = Photo::decode(&bytes).map_err(|e| MobileError::Command(e.to_string()))?;
    drop(bytes);
    let (width, height) = (photo.width, photo.height);
    let quad = Quad::from_shares(&corners, width, height).map_err(|e| MobileError::Command(e.to_string()))?;
    let pyramid = Pyramid::new(photo.luma());
    drop(photo);
    lap("scan_decode", &mut timings);
    let diagonal = f64::from(width).hypot(f64::from(height));
    let search_px = (search_share.is_finite() && search_share > 0.0).then(|| (f64::from(search_share.min(0.2)) * diagonal) as f32);
    let options = RefineOptions { search_px, sides: sides.try_into().unwrap_or([true; 4]) };
    let found = refine(&pyramid, &quad, &options);
    lap("scan_refine", &mut timings);
    Ok(ScanQuad {
        corners: found.quad.to_shares(width, height).to_vec(),
        refined: found.sides.iter().map(|s| s.refined).collect(),
        curved: found.sides.iter().map(|s| s.curved).collect(),
        timings,
    })
}

/// The page a request asks for, as the scan crate takes it: the same for a saved page and for
/// its preview, so the preview shows what will be saved.
fn page_request<'a>(request: &ScanPageRequest, photo: &'a [u8], corners: [f32; 8], max_side: Option<u32>) -> PageRequest<'a> {
    let look = match request.look {
        ScanLook::Original => Look::Original,
        ScanLook::Colour => Look::Colour,
        ScanLook::Greyscale => Look::Greyscale,
        ScanLook::BlackWhite => Look::BlackWhite,
        ScanLook::Auto => Look::Auto,
    };
    PageRequest {
        look,
        strength: request.strength,
        threads: std::thread::available_parallelism().map_or(1, usize::from).min(pdfcraft_scan::warp::MAX_THREADS),
        focal_35mm: request.focal_35mm,
        snap_paper: request.snap_paper,
        flatten: request.flatten,
        flatten_check: request.flatten_check,
        side_proportions: request.side_proportions,
        upright: request.upright,
        turns: request.turns,
        sharpen: request.sharpen,
        denoise: request.denoise,
        preset: preset_choice(&request.preset),
        curved_sides: request.curved_sides.min(4),
        fixed_strength: request.fixed_strength,
        max_side,
        ..PageRequest::new(photo, corners)
    }
}

/// How the flattened page and the page straightened by its corners compared (#302).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ScanFlattenCheck {
    /// The flattened page was kept.
    pub kept: bool,
    /// Why, as a short word: `straighter`, `notbetter`, `unmeasured` or `straight`.
    pub why: String,
    /// How far the lines of text are from straight and level, in pixels of the page, on each;
    /// `None` when too few lines could be followed.
    pub straightened_px: Option<f32>,
    pub flattened_px: Option<f32>,
    /// The lines of text followed on each.
    pub straightened_lines: u32,
    pub flattened_lines: u32,
}

impl From<pdfcraft_scan::page::FlattenCheck> for ScanFlattenCheck {
    fn from(c: pdfcraft_scan::page::FlattenCheck) -> Self {
        ScanFlattenCheck {
            kept: c.kept,
            why: c.why.into(),
            straightened_px: c.straightened.map(|m| m.px as f32),
            flattened_px: c.flattened.map(|m| m.px as f32),
            straightened_lines: c.straightened.map_or(0, |m| m.lines),
            flattened_lines: c.flattened.map_or(0, |m| m.lines),
        }
    }
}

/// A page as it will be saved, small enough to show (#301).
#[derive(Debug, Clone, uniffi::Record)]
pub struct ScanPreview {
    pub width: u32,
    pub height: u32,
    /// `width` × `height` pixels, four bytes each: red, green, blue and 255.
    pub rgba: Vec<u8>,
    /// The quarter turns clockwise the page was given to stand upright (#339).
    pub quarter_turns: u8,
    /// What the orientation classifier made of the page (#339); `None` when it was not asked or
    /// its model file is not installed.
    pub orientation: Option<ScanOrientation>,
    /// The classifier's turn and the person's together, 0–3 (#339).
    pub turns: u8,
    /// The page was flattened by the network (#302).
    pub flattened: bool,
    /// The kind of document the Auto look took the page for (#299).
    pub kind: Option<ScanPageKind>,
    /// How the flattened and the straightened page compared, when that was asked (#302).
    pub flatten_check: Option<ScanFlattenCheck>,
    /// The straightened page was still at hand from an earlier preview: only its look was made.
    pub reused: bool,
    pub timings: Vec<ScanTiming>,
}

/// What a straightened preview page depends on: the photo as it is on disk, the corners, and
/// everything that changes the page before its look. The look and its strength are not part of it.
#[derive(Clone, Debug, PartialEq)]
struct PreviewKey {
    photo: PathBuf,
    bytes: u64,
    modified: Option<std::time::SystemTime>,
    corners: [u32; 8],
    focal: Option<u32>,
    snap_paper: bool,
    side_proportions: bool,
    flatten: bool,
    flatten_check: bool,
    upright: bool,
    turns: u8,
    max_side: u32,
}

/// The straightened pages of the last few previews, newest last. A page 1280 pixels long is
/// under 5 MB, so four of them are under 20 MB.
static PREVIEWS: std::sync::Mutex<Vec<(PreviewKey, std::sync::Arc<PageBase>)>> = std::sync::Mutex::new(Vec::new());
const PREVIEWS_KEPT: usize = 4;
/// Tests that empty the preview store (directly or through `release_memory`) hold this, so the
/// test that counts what the store keeps never sees it emptied by another test running beside it.
#[cfg(test)]
pub(crate) static PREVIEWS_TEST_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

/// Forget the straightened preview pages (when memory is short, or the scan is over).
#[uniffi::export]
pub fn scan_release_previews() {
    PREVIEWS.lock().unwrap_or_else(std::sync::PoisonError::into_inner).clear();
}

/// The page `request` describes, as it will be saved, shrunk so its longer side is `max_side`
/// pixels at most (64 to 4096): what the scan screen shows before saving (#301). The request's
/// `output_path` is not used; nothing is written.
///
/// The straightened page is kept for the next call, so changing only the look, its strength or
/// the sharpening costs one look on a small page and no decoding.
#[uniffi::export]
pub fn scan_preview_page(request: ScanPageRequest, max_side: u32) -> Result<ScanPreview, MobileError> {
    let root = SCAN_ROOT.read().unwrap_or_else(std::sync::PoisonError::into_inner).clone();
    let root = root.ok_or_else(|| MobileError::Command("scanning is not ready yet".into()))?;
    preview_in(&root, &request, max_side)
}

pub(crate) fn preview_in(root: &Path, request: &ScanPageRequest, max_side: u32) -> Result<ScanPreview, MobileError> {
    let unreadable = || MobileError::Command("the photo could not be read".into());
    let corners: [f32; 8] = request.corners.as_slice().try_into().map_err(|_| MobileError::Command("a page needs four corners".into()))?;
    let max_side = max_side.clamp(64, 4096);
    let photo = Path::new(&request.photo_path).canonicalize().map_err(|_| unreadable())?;
    if !photo.starts_with(root) {
        return Err(MobileError::Command("the scan's files must be in the app's own folder".into()));
    }
    let on_disk = std::fs::metadata(&photo).map_err(|_| unreadable())?;
    let key = PreviewKey {
        photo,
        bytes: on_disk.len(),
        modified: on_disk.modified().ok(),
        corners: corners.map(f32::to_bits),
        focal: request.focal_35mm.map(f32::to_bits),
        snap_paper: request.snap_paper,
        side_proportions: request.side_proportions,
        flatten: request.flatten,
        flatten_check: request.flatten_check,
        upright: request.upright,
        turns: request.turns % 4,
        max_side,
    };
    let kept = {
        let mut previews = PREVIEWS.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        // the one asked for becomes the newest
        previews.iter().position(|(k, _)| *k == key).map(|at| {
            let entry = previews.remove(at);
            let base = std::sync::Arc::clone(&entry.1);
            previews.push(entry);
            base
        })
    };
    let clock = std::time::Instant::now();
    let (base, reused) = match kept {
        Some(base) => (base, true),
        None => {
            let bytes = read_photo(root, &request.photo_path)?;
            let made = page_base(&page_request(request, &bytes, corners, Some(max_side))).map_err(|e| MobileError::Command(e.to_string()))?;
            let base = std::sync::Arc::new(made);
            let mut previews = PREVIEWS.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
            previews.push((key, std::sync::Arc::clone(&base)));
            let extra = previews.len().saturating_sub(PREVIEWS_KEPT);
            previews.drain(..extra);
            (base, false)
        }
    };
    let mut timings: Vec<ScanTiming> = match reused {
        true => Vec::new(),
        false => base.timings.iter().map(|&(stage, ms)| ScanTiming { stage: stage.into(), ms }).collect(),
    };
    let ms = |clock: std::time::Instant| u32::try_from(clock.elapsed().as_millis()).unwrap_or(u32::MAX);
    if !reused {
        // decoding, straightening and the rest, as one figure before its parts
        timings.insert(0, ScanTiming { stage: "scan_preview_base".into(), ms: ms(clock) });
    }
    let clock = std::time::Instant::now();
    let (applied, kind) =
        page_look_kind(&base, &page_request(request, &[], corners, Some(max_side))).map_err(|e| MobileError::Command(e.to_string()))?;
    let (width, height) = (base.page.width, base.page.height);
    let pixels = width as usize * height as usize;
    let mut rgba = Vec::with_capacity(pixels * 4);
    match &applied.pixels {
        pdfcraft_scan::look::Pixels::Rgb(rgb) => rgb.chunks_exact(3).for_each(|p| rgba.extend([p[0], p[1], p[2], 255])),
        pdfcraft_scan::look::Pixels::Grey(grey) => grey.iter().for_each(|&v| rgba.extend([v, v, v, 255])),
        pdfcraft_scan::look::Pixels::Bilevel(bits) => bits.to_grey().iter().for_each(|&v| rgba.extend([v, v, v, 255])),
    }
    if rgba.len() != pixels * 4 {
        return Err(MobileError::Command("the page could not be drawn".into()));
    }
    timings.push(ScanTiming { stage: "scan_preview_look".into(), ms: ms(clock) });
    Ok(ScanPreview {
        width,
        height,
        rgba,
        quarter_turns: base.quarter_turns,
        orientation: base.orientation.map(orientation_of),
        turns: base.turns,
        flattened: base.flattened,
        kind: kind.as_ref().map(kind_of),
        flatten_check: base.flatten_check.map(Into::into),
        reused,
        timings,
    })
}

fn orientation_of(found: pdfcraft_scan::Upright) -> ScanOrientation {
    ScanOrientation {
        quarter_turns: found.quarter_turns,
        confidence: found.confidence,
        agreement: found.agreement,
        tiles: found.tiles,
        sure: found.sure,
    }
}

/// The turn the multi-page rule gives one page of a scan (#339).
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct ScanSettledTurn {
    /// Quarter turns clockwise that stand the page upright (0–3).
    pub quarter_turns: u8,
    /// The page was not sure by itself and took the turn of its two neighbours.
    pub followed: bool,
}

/// The turn each page of a scan gets when the classifier's answers are read together (#339, the
/// multi-page rule): a page it was not sure of takes the turn of its two neighbours when both are
/// sure at 0.95 or more and agree; every other page keeps its own sure turn, or none. `None` in
/// `pages` is a page the classifier was not asked about (the person turned it, or turning is
/// off): it keeps no turn and is nobody's neighbour. Same length and order as `pages`.
#[uniffi::export]
pub fn scan_settle_turns(pages: Vec<Option<ScanOrientation>>) -> Vec<ScanSettledTurn> {
    let pages: Vec<Option<PageTurn>> =
        pages.into_iter().map(|found| found.map(|o| PageTurn { quarter_turns: o.quarter_turns, confidence: o.confidence, sure: o.sure })).collect();
    settle(&pages).into_iter().zip(followed(&pages)).map(|(quarter_turns, followed)| ScanSettledTurn { quarter_turns, followed }).collect()
}

pub(crate) fn process_in(root: &Path, request: &ScanPageRequest) -> Result<ScanPageResult, MobileError> {
    let outside = || MobileError::Command("the scan's files must be in the app's own folder".into());
    let corners: [f32; 8] = request.corners.as_slice().try_into().map_err(|_| MobileError::Command("a page needs four corners".into()))?;
    // The output's folder must exist inside the root; its name may not carry a folder.
    let out = Path::new(&request.output_path);
    let (Some(parent), Some(name)) = (out.parent(), out.file_name()) else { return Err(outside()) };
    let parent = parent.canonicalize().map_err(|_| outside())?;
    if !parent.starts_with(root) {
        return Err(outside());
    }
    let bytes = read_photo(root, &request.photo_path)?;
    let page = process_page(&page_request(request, &bytes, corners, None)).map_err(|e| MobileError::Command(e.to_string()))?;
    drop(bytes);
    let mut file_name = name.to_os_string();
    file_name.push(".");
    file_name.push(page.format.extension());
    let target = parent.join(file_name);
    std::fs::write(&target, &page.bytes).map_err(|_| MobileError::Command("the page could not be saved".into()))?;
    Ok(ScanPageResult {
        path: target.to_string_lossy().into_owned(),
        width: page.width,
        height: page.height,
        format: match page.format {
            Format::Jpeg => ScanFormat::Jpeg,
            Format::Png => ScanFormat::Png,
        },
        dpi: page.dpi,
        paper: match page.paper {
            Paper::A4 => ScanPaper::A4,
            Paper::A5 => ScanPaper::A5,
            Paper::Letter => ScanPaper::Letter,
            Paper::Legal => ScanPaper::Legal,
            Paper::IdCard => ScanPaper::IdCard,
            Paper::Square => ScanPaper::Square,
            Paper::Custom => ScanPaper::Custom,
        },
        aspect_source: match page.aspect {
            AspectSource::KnownFocal => ScanAspectSource::KnownFocal,
            AspectSource::EstimatedFocal => ScanAspectSource::EstimatedFocal,
            AspectSource::TypicalFocal => ScanAspectSource::TypicalFocal,
            AspectSource::SideLengths => ScanAspectSource::SideLengths,
        },
        focal_mismatch: page.focal_mismatch,
        flattened: page.flattened,
        bend: page.bend,
        flatten_check: page.flatten_check.map(Into::into),
        quarter_turns: page.quarter_turns,
        orientation: page.orientation.map(orientation_of),
        turns: page.turns,
        noise: page.noise,
        kind: page.kind.as_ref().map(kind_of),
        timings: page.timings.into_iter().map(|(stage, ms)| ScanTiming { stage: stage.into(), ms }).collect(),
    })
}

/// How sharp the page in a photo is (#296). **Not a verdict to act on yet**: see
/// [`ScanSharpness::blurred_unverified`].
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ScanSharpness {
    /// Edge strength over contrast inside the page (sharp drawn pages 1.5–1.9; 0 when the page
    /// could not be measured). For logs and kept scans, and to compare attempts at one page.
    pub score: f32,
    /// The score is under [`ScanSharpness::threshold`] on a page with detail. **Unverified: log it
    /// and keep it, never ask for a retake from it.** The threshold was set on drawn pages, not
    /// on photos, and motion blur (a shaken phone, the commonest blur) is not told apart: drawn
    /// streaks of 7 and 13 pixels score across the sharp range (`docs/ANDROID_SCAN_BLOCKS.md`).
    pub blurred_unverified: bool,
    /// The score under which a page is called blurred (from drawn pages).
    pub threshold: f32,
    /// `scan_decode`, then `scan_sharpness`.
    pub timings: Vec<ScanTiming>,
}

/// Measure how sharp the page inside `corners` (8 shares, 0–1, of the upright photo, as every
/// scan call takes them) is in the photo at `photo_path` (#296). The photo is decoded with the
/// usual limits (64 megapixels, 256 MB) and measured on a half-size copy, inside the page's
/// outline shrunk by 5 %.
#[uniffi::export]
pub fn scan_sharpness(photo_path: String, corners: Vec<f32>) -> Result<ScanSharpness, MobileError> {
    let root = SCAN_ROOT.read().unwrap_or_else(std::sync::PoisonError::into_inner).clone();
    let root = root.ok_or_else(|| MobileError::Command("scanning is not ready yet".into()))?;
    sharpness_in(&root, &photo_path, &corners)
}

pub(crate) fn sharpness_in(root: &Path, photo_path: &str, corners: &[f32]) -> Result<ScanSharpness, MobileError> {
    let corners: [f32; 8] = corners.try_into().map_err(|_| MobileError::Command("a page needs four corners".into()))?;
    let bytes = read_photo(root, photo_path)?;
    let found = pdfcraft_scan::photo_sharpness(&bytes, corners).map_err(|e| MobileError::Command(e.to_string()))?;
    Ok(ScanSharpness {
        score: found.sharpness.score,
        blurred_unverified: found.sharpness.blurred,
        threshold: pdfcraft_scan::sharpness::BLURRED_BELOW,
        timings: found.timings.into_iter().map(|(stage, ms)| ScanTiming { stage: stage.into(), ms }).collect(),
    })
}

/// One photo of a burst (#297).
#[derive(Debug, Clone, uniffi::Record)]
pub struct ScanMergeFrame {
    /// The camera file.
    pub photo_path: String,
    /// 8 shares (0–1) of the upright photo: x1, y1 … x4, y4 (top-left, top-right,
    /// bottom-right, bottom-left), this photo's own corners.
    pub corners: Vec<f32>,
}

/// A burst of photos of one page to merge into one straightened page (#297).
#[derive(Debug, Clone, uniffi::Record)]
pub struct ScanMergeRequest {
    /// 2 to 4 photos of the same page, taken in a row.
    pub frames: Vec<ScanMergeFrame>,
    /// Where the merged page is written; `.jpg` or `.png` is added.
    pub output_path: String,
    /// JPEG (as a colour page is saved) or PNG (lossless).
    pub format: ScanFormat,
    /// The camera's focal length as a 35 mm equivalent, as in [`ScanPageRequest`].
    #[uniffi(default = None)]
    pub focal_35mm: Option<f32>,
    /// Match the page to standard paper sizes, as in [`ScanPageRequest`].
    #[uniffi(default = true)]
    pub snap_paper: bool,
}

/// The merged page written (#297).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ScanMergeResult {
    pub path: String,
    pub width: u32,
    pub height: u32,
    pub format: ScanFormat,
    /// The resolution written into the file.
    pub dpi: f32,
    /// Which frame (its index in the request) the others were lined up on: the sharpest.
    pub reference: u8,
    /// Every frame's sharpness score, in the request's order.
    pub scores: Vec<f32>,
    /// Frames averaged, the reference included.
    pub used: u8,
    /// Frames left out for scoring under 70 % of the reference.
    pub rejected_blur: u8,
    /// Frames left out because they could not be lined up (moved over 2.5 pixels, or too
    /// different after the move).
    pub rejected_align: u8,
    /// The theoretical noise gain for the samples averaged, 10·log10 of the mean count, in
    /// tenths of a dB (60 is the most four frames give). An estimate, not a measurement.
    pub psnr_gain_tenths_db: u16,
    /// `scan_decode`, `scan_sharpness`, `scan_rectify`, `scan_merge_align`, `scan_merge`,
    /// `scan_encode`, each summed over the frames.
    pub timings: Vec<ScanTiming>,
}

/// Merge a burst of photos of one page into one straightened page with less noise (#297), and
/// write it. The sharpest frame is the reference; frames under 70 % of its sharpness are left
/// out; the others are straightened to the reference's size (at most 12 megapixels), lined up
/// on it to a fraction of a pixel and averaged, a value at a time, leaving out what moved. The
/// page has no look: give it one with [`scan_process_page`] and the whole-photo corners.
#[uniffi::export]
pub fn scan_merge_frames(request: ScanMergeRequest) -> Result<ScanMergeResult, MobileError> {
    let root = SCAN_ROOT.read().unwrap_or_else(std::sync::PoisonError::into_inner).clone();
    let root = root.ok_or_else(|| MobileError::Command("scanning is not ready yet".into()))?;
    merge_in(&root, &request)
}

pub(crate) fn merge_in(root: &Path, request: &ScanMergeRequest) -> Result<ScanMergeResult, MobileError> {
    let outside = || MobileError::Command("the scan's files must be in the app's own folder".into());
    let count = request.frames.len();
    if !(pdfcraft_scan::capture::MIN_MERGE_FRAMES..=pdfcraft_scan::capture::MAX_MERGE_FRAMES).contains(&count) {
        return Err(MobileError::Command(pdfcraft_scan::ScanError::FrameCount.to_string()));
    }
    let corners = request
        .frames
        .iter()
        .map(|f| <[f32; 8]>::try_from(f.corners.as_slice()))
        .collect::<Result<Vec<_>, _>>()
        .map_err(|_| MobileError::Command("a page needs four corners".into()))?;
    let out = Path::new(&request.output_path);
    let (Some(parent), Some(name)) = (out.parent(), out.file_name()) else { return Err(outside()) };
    let parent = parent.canonicalize().map_err(|_| outside())?;
    if !parent.starts_with(root) {
        return Err(outside());
    }
    // the camera files as they are (a few MB each); the photos are decoded one at a time
    let files = request.frames.iter().map(|f| read_photo(root, &f.photo_path)).collect::<Result<Vec<_>, _>>()?;
    let frames: Vec<_> = files.iter().zip(corners).map(|(photo, corners)| pdfcraft_scan::MergeFrame { photo, corners }).collect();
    let format = match request.format {
        ScanFormat::Jpeg => Format::Jpeg,
        ScanFormat::Png => Format::Png,
    };
    let merged = pdfcraft_scan::merge_frames(&pdfcraft_scan::MergeRequest {
        frames: &frames,
        format,
        threads: std::thread::available_parallelism().map_or(1, usize::from).min(pdfcraft_scan::warp::MAX_THREADS),
        focal_35mm: request.focal_35mm,
        snap_paper: request.snap_paper,
    })
    .map_err(|e| MobileError::Command(e.to_string()))?;
    drop(frames);
    drop(files);
    let mut file_name = name.to_os_string();
    file_name.push(".");
    file_name.push(merged.format.extension());
    let target = parent.join(file_name);
    std::fs::write(&target, &merged.bytes).map_err(|_| MobileError::Command("the page could not be saved".into()))?;
    Ok(ScanMergeResult {
        path: target.to_string_lossy().into_owned(),
        width: merged.width,
        height: merged.height,
        format: request.format,
        dpi: f32::from(merged.dpi),
        reference: merged.reference,
        scores: merged.scores,
        used: merged.used,
        rejected_blur: merged.rejected_blur,
        rejected_align: merged.rejected_align,
        psnr_gain_tenths_db: (merged.psnr_gain_estimate * 10.0).round().clamp(0.0, f32::from(u16::MAX)) as u16,
        timings: merged.timings.into_iter().map(|(stage, ms)| ScanTiming { stage: stage.into(), ms }).collect(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn photo(dir: &Path) -> PathBuf {
        let img = image::RgbImage::from_fn(64, 48, |x, y| image::Rgb([(x * 4) as u8, (y * 5) as u8, 200]));
        let p = dir.join("shot.jpg");
        img.save(&p).unwrap();
        p
    }

    fn request(photo: &Path, out: &Path) -> ScanPageRequest {
        ScanPageRequest {
            photo_path: photo.to_string_lossy().into_owned(),
            corners: vec![0.1, 0.1, 0.9, 0.1, 0.9, 0.9, 0.1, 0.9],
            look: ScanLook::Colour,
            strength: 50,
            output_path: out.to_string_lossy().into_owned(),
            focal_35mm: None,
            snap_paper: true,
            flatten: false,
            flatten_check: false,
            side_proportions: false,
            upright: false,
            turns: 0,
            sharpen: 30,
            denoise: true,
            preset: "off".into(),
            curved_sides: 0,
            fixed_strength: false,
        }
    }

    fn temp(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("pdfcraft-scan-{name}-{}", std::process::id()));
        std::fs::create_dir_all(&d).unwrap();
        d.canonicalize().unwrap()
    }

    #[test]
    fn a_page_is_written_beside_the_request_with_its_extension() {
        let root = temp("ok");
        let shot = photo(&root);
        let r = process_in(&root, &request(&shot, &root.join("page-0"))).unwrap();
        assert!(r.path.ends_with("page-0.jpg"), "{}", r.path);
        assert_eq!(r.format, ScanFormat::Jpeg);
        assert!(std::fs::metadata(&r.path).unwrap().len() > 0);
        let stages: Vec<_> = r.timings.iter().map(|t| t.stage.as_str()).collect();
        assert_eq!(stages, ["scan_decode", "scan_rectify", "scan_look", "scan_encode"]);
        let bw = process_in(&root, &ScanPageRequest { look: ScanLook::BlackWhite, ..request(&shot, &root.join("page-1")) }).unwrap();
        assert!(bw.path.ends_with("page-1.png"));
        std::fs::remove_dir_all(root).unwrap();
    }

    /// The sizes of the pages of a PDF made from `paths`, in points, each image at its own
    /// resolution (as Save does when every page carries one).
    fn pdf_page_sizes(paths: &[&str]) -> Vec<(f64, f64)> {
        let session = crate::MobileSession::new();
        let args = serde_json::json!({ "from": "images", "paths": paths, "name": "Scan" });
        let created: serde_json::Value = serde_json::from_str(&session.execute("doc_create".into(), args.to_string()).unwrap()).unwrap();
        let info: serde_json::Value =
            serde_json::from_str(&session.execute("doc_info".into(), serde_json::json!({ "doc": created["doc"] }).to_string()).unwrap()).unwrap();
        info["pages"].as_array().unwrap().iter().map(|p| (p["width"].as_f64().unwrap(), p["height"].as_f64().unwrap())).collect()
    }

    /// Pages of different papers in one PDF, each exactly its size (#293): an A4 page, a
    /// landscape Letter page and a whole photo left as the camera took it.
    #[test]
    fn each_page_of_the_pdf_has_its_papers_size() {
        let root = temp("sizes");
        let shot = root.join("wide.jpg");
        image::RgbImage::from_fn(1600, 1200, |x, y| image::Rgb([(x / 8) as u8, (y / 6) as u8, 180])).save(&shot).unwrap();
        // frontal pages: 700 x 990 photo pixels is A4, 1100 x 850 is Letter on its side
        let page = |w: f32, h: f32, look, out: &str| {
            let corners = vec![0.1, 0.1, 0.1 + w / 1600.0, 0.1, 0.1 + w / 1600.0, 0.1 + h / 1200.0, 0.1, 0.1 + h / 1200.0];
            process_in(&root, &ScanPageRequest { corners, look, ..request(&shot, &root.join(out)) }).unwrap()
        };
        let a4 = page(700.0, 990.0, ScanLook::Colour, "page-0");
        let letter = page(1100.0, 850.0, ScanLook::BlackWhite, "page-1");
        let whole = process_in(
            &root,
            &ScanPageRequest {
                corners: vec![0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0],
                look: ScanLook::Original,
                ..request(&shot, &root.join("page-2"))
            },
        )
        .unwrap();
        assert_eq!((a4.paper, letter.paper, whole.paper), (ScanPaper::A4, ScanPaper::Letter, ScanPaper::Custom));
        // the testing choices (#288): the side lengths alone, and no matching to a paper
        let plain = process_in(
            &root,
            &ScanPageRequest {
                corners: vec![0.1, 0.1, 0.5375, 0.1, 0.5375, 0.925, 0.1, 0.925],
                side_proportions: true,
                snap_paper: false,
                ..request(&shot, &root.join("page-3"))
            },
        )
        .unwrap();
        assert_eq!((plain.paper, plain.aspect_source, (plain.width, plain.height)), (ScanPaper::Custom, ScanAspectSource::SideLengths, (700, 990)));
        assert_ne!(a4.aspect_source, ScanAspectSource::SideLengths);
        assert_eq!((letter.format, whole.format), (ScanFormat::Png, ScanFormat::Jpeg));
        // the whole photo is the camera's file with a resolution, not a second encoding
        assert_eq!(std::fs::read(&whole.path).unwrap()[20..], std::fs::read(&shot).unwrap()[20..]);

        let sizes = pdf_page_sizes(&[&a4.path, &letter.path, &whole.path]);
        let near = |(w, h): (f64, f64), (tw, th): (f64, f64)| (w - tw).abs() <= 0.5 && (h - th).abs() <= 0.5;
        assert!(near(sizes[0], (595.28, 841.89)), "A4: {:?}", sizes[0]);
        assert!(near(sizes[1], (792.0, 612.0)), "Letter on its side: {:?}", sizes[1]);
        // 1600 pixels as long as A4: 137 dots per inch
        assert!(near(sizes[2], (1600.0 * 72.0 / 137.0, 1200.0 * 72.0 / 137.0)), "the whole photo: {:?}", sizes[2]);
        std::fs::remove_dir_all(root).unwrap();
    }

    /// A light sheet on a dark desk, found roughly: the corners come back on the sheet's edges.
    #[test]
    fn rough_corners_are_moved_onto_the_sheets_edges() {
        let root = temp("refine");
        let shot = root.join("desk.png");
        let (w, h) = (1600u32, 1200u32);
        image::GrayImage::from_fn(w, h, |x, y| image::Luma([if (300..1300).contains(&x) && (200..1000).contains(&y) { 230 } else { 50 }]))
            .save(&shot)
            .unwrap();
        let path = shot.to_string_lossy().into_owned();
        // each corner about 10 pixels off
        let rough =
            [290.0 / 1600.0, 212.0 / 1200.0, 1311.0 / 1600.0, 195.0 / 1200.0, 1292.0 / 1600.0, 1009.0 / 1200.0, 306.0 / 1600.0, 991.0 / 1200.0];
        let found = refine_in(&root, &path, &rough, &[], 0.0).unwrap();
        let truth = [300.0, 200.0, 1300.0, 200.0, 1300.0, 1000.0, 300.0, 1000.0];
        for (i, share) in found.corners.iter().enumerate() {
            let px = share * if i % 2 == 0 { w as f32 } else { h as f32 };
            assert!((px - truth[i]).abs() < 0.5, "corner coordinate {i}: {px} against {}", truth[i]);
        }
        assert_eq!((found.refined.as_slice(), found.curved.as_slice()), ([true; 4].as_slice(), [false; 4].as_slice()));
        assert_eq!(found.timings.iter().map(|t| t.stage.as_str()).collect::<Vec<_>>(), ["scan_decode", "scan_refine"]);
        // only two sides asked for: the corner between the other two stays where it was given
        let two = refine_in(&root, &path, &rough, &[true, true, false, false], 0.0).unwrap();
        assert_eq!(two.refined, [true, true, false, false]);
        assert!((two.corners[6] - rough[6]).abs() < 1e-6 && (two.corners[7] - rough[7]).abs() < 1e-6);
        // refused like the page function: outside the root, not four corners
        let other = temp("refine-other");
        assert!(refine_in(&other, &path, &rough, &[], 0.0).is_err());
        assert!(refine_in(&root, &path, &rough[..6], &[], 0.0).is_err());
        std::fs::remove_dir_all(root).unwrap();
        std::fs::remove_dir_all(other).unwrap();
    }

    /// A black-and-white page (#300) stays one bit per pixel all the way into the saved PDF.
    #[test]
    fn a_black_and_white_page_is_one_bit_in_the_saved_pdf() {
        let root = temp("bw");
        // an A4 page at 200 dpi with rows of small dark marks
        let shot = root.join("page.png");
        image::GrayImage::from_fn(1654, 2339, |x, y| {
            let mark = (200..2100).contains(&y)
                && (150..1500).contains(&x)
                && (y / 20) % 2 == 0
                && ((x / 7) * 7 + (y / 40) * 13) % 5 != 0
                && x % 7 < 4
                && y % 40 > 6;
            image::Luma([if mark { 30 } else { 232 }])
        })
        .save(&shot)
        .unwrap();
        let whole = vec![0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0];
        let page =
            process_in(&root, &ScanPageRequest { corners: whole, look: ScanLook::BlackWhite, strength: 55, ..request(&shot, &root.join("page-0")) })
                .unwrap();
        assert_eq!((page.format, page.paper, page.width, page.height, page.dpi), (ScanFormat::Png, ScanPaper::A4, 1654, 2339, 200.0));
        let png = std::fs::read(&page.path).unwrap();
        // the PNG header: bit depth 1, colour type 0 (grey)
        assert_eq!(&png[24..26], [1, 0]);
        let session = crate::MobileSession::new();
        let args = serde_json::json!({ "from": "images", "paths": [page.path], "name": "Scan" });
        let created: serde_json::Value = serde_json::from_str(&session.execute("doc_create".into(), args.to_string()).unwrap()).unwrap();
        let out = root.join("scan.pdf");
        session.execute("doc_save".into(), serde_json::json!({ "doc": created["doc"], "path": out.to_string_lossy() }).to_string()).unwrap();
        let pdf = std::fs::read(&out).unwrap();
        let text = String::from_utf8_lossy(&pdf);
        assert!(text.contains("/BitsPerComponent 1") && !text.contains("/BitsPerComponent 8"), "the page image is not 1 bit per pixel in the PDF");
        assert!(pdf.len() < 2 * png.len() + 4096, "PDF {} bytes for a PNG of {}", pdf.len(), png.len());
        std::fs::remove_dir_all(root).unwrap();
    }

    /// A camera frame held sideways (#295): the sheet comes back in shares of the upright frame,
    /// and a frame that cannot be looked at is refused.
    #[test]
    fn a_sheet_is_found_in_a_sideways_camera_frame() {
        // upright the frame is 480 x 640 with a light sheet of print in it, a little turned; the
        // sensor holds it a quarter turn counter-clockwise, so sensor pixel (x, y) shows upright
        // pixel (479 - y, x)
        let (sin, cos) = 3f64.to_radians().sin_cos();
        let mut rgba = Vec::with_capacity(640 * 480 * 4);
        for y in 0..480u32 {
            for x in 0..640u32 {
                let (ux, uy) = (f64::from(479 - y) + 0.5, f64::from(x) + 0.5);
                let (dx, dy) = (ux - 240.0, uy - 330.0);
                let (u, v) = (dx * cos + dy * sin, -dx * sin + dy * cos);
                let sheet = u.abs() < 150.0 && v.abs() < 210.0;
                let print = sheet && u.abs() < 125.0 && v.abs() < 185.0 && (v as i64).rem_euclid(12) < 4;
                let [r, g, b] = if print {
                    [45, 45, 50]
                } else if sheet {
                    [236, 234, 228]
                } else {
                    [62 + (x % 7) as u8, 50 + (y % 5) as u8, 44]
                };
                rgba.extend_from_slice(&[r, g, b, 255]);
            }
        }
        let found = scan_detect_frame(rgba.clone(), 640, 480, 90).unwrap();
        assert!(found.found && found.confidence >= 0.45 && !found.cut_off && found.points.len() == 8, "{found:?}");
        let truth = [(-150.0, -210.0), (150.0, -210.0), (150.0, 210.0), (-150.0, 210.0)]
            .map(|(u, v): (f64, f64)| [240.0 + u * cos - v * sin, 330.0 + u * sin + v * cos]);
        for (i, corner) in truth.iter().enumerate() {
            let (x, y) = (f64::from(found.points[2 * i]) * 480.0, f64::from(found.points[2 * i + 1]) * 640.0);
            assert!((x - corner[0]).hypot(y - corner[1]) < 6.0, "corner {i}: ({x:.1}, {y:.1}) against {corner:?}");
        }
        assert!(found.mean_luma > 60.0 && found.mean_luma < 200.0, "{found:?}");
        // refused, never a panic
        assert!(scan_detect_frame(rgba.clone(), 640, 480, 45).is_err());
        assert!(scan_detect_frame(rgba[..100].to_vec(), 640, 480, 0).is_err());
        assert!(scan_detect_frame(Vec::new(), 0, 0, 0).is_err());
        // a grey frame: nothing, and its brightness
        let grey = scan_detect_frame([120u8, 130, 140, 255].repeat(640 * 480), 640, 480, 270).unwrap();
        assert!(!grey.found && grey.mean_luma == 128.0 && grey.points == [0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0], "{grey:?}");
    }

    /// A sheet on a table: found by the network and put on its edges, in one call.
    #[test]
    fn a_sheet_is_found_and_put_on_its_edges() {
        let root = temp("find");
        let shot = root.join("desk.png");
        let (w, h) = (1200u32, 1600u32);
        // a light sheet with rows of print, a little turned, on a dark table
        let (sin, cos) = 4f64.to_radians().sin_cos();
        image::RgbImage::from_fn(w, h, |x, y| {
            let (dx, dy) = (f64::from(x) - 600.0, f64::from(y) - 800.0);
            let (u, v) = (dx * cos + dy * sin, -dx * sin + dy * cos);
            let sheet = u.abs() < 380.0 && v.abs() < 540.0;
            let print = sheet
                && u.abs() < 320.0
                && v.abs() < 480.0
                && (v as i64).rem_euclid(26) < 8
                && ((u as i64).div_euclid(15) + (v as i64).div_euclid(26)).rem_euclid(4) != 0;
            image::Rgb(if print {
                [45, 45, 50]
            } else if sheet {
                [236, 234, 228]
            } else {
                [62 + (x % 7) as u8, 50 + (y % 5) as u8, 44]
            })
        })
        .save(&shot)
        .unwrap();
        let found = find_in(&root, &shot.to_string_lossy(), ScanFindOptions::default()).unwrap();
        assert!(found.found && found.source == ScanQuadSource::Model && found.confidence >= 0.5, "{found:?}");
        let truth = [(-380.0, -540.0), (380.0, -540.0), (380.0, 540.0), (-380.0, 540.0)]
            .map(|(u, v): (f64, f64)| [600.0 + u * cos - v * sin, 800.0 + u * sin + v * cos]);
        for (i, corner) in truth.iter().enumerate() {
            let (x, y) = (f64::from(found.corners[2 * i]) * f64::from(w), f64::from(found.corners[2 * i + 1]) * f64::from(h));
            assert!((x - corner[0]).hypot(y - corner[1]) < 1.5, "corner {i}: ({x:.1}, {y:.1}) against {corner:?}");
        }
        assert_eq!(found.refined, [true; 4]);
        assert_eq!(found.timings.iter().map(|t| t.stage.as_str()).collect::<Vec<_>>(), ["scan_decode", "scan_detect", "scan_refine"]);

        // the testing options (#288): each detector alone, the closer look off, and nothing at all
        let with = |finder, refine| find_in(&root, &shot.to_string_lossy(), ScanFindOptions { finder, refine }).unwrap();
        assert_eq!(with(ScanFinder::Auto, true).corners, found.corners);
        assert_eq!(with(ScanFinder::Network, true).corners, found.corners);
        let lines = with(ScanFinder::Lines, true);
        assert!(lines.found && lines.source == ScanQuadSource::Lines, "{lines:?}");
        let coarse = with(ScanFinder::Network, false);
        assert!(coarse.found && coarse.refined == [false; 4] && coarse.corners != found.corners, "{coarse:?}");
        assert_eq!(coarse.timings.iter().map(|t| t.stage.as_str()).collect::<Vec<_>>(), ["scan_decode", "scan_detect"]);
        for (i, corner) in truth.iter().enumerate() {
            // the network's own corners: within a few percent of the photo, not on the edge
            let (x, y) = (f64::from(coarse.corners[2 * i]) * f64::from(w), f64::from(coarse.corners[2 * i + 1]) * f64::from(h));
            assert!((x - corner[0]).hypot(y - corner[1]) < 0.03 * 2000.0, "corner {i}: ({x:.1}, {y:.1}) against {corner:?}");
        }
        let off = with(ScanFinder::Off, true);
        assert!(
            !off.found && off.source == ScanQuadSource::None && off.passes == 0 && off.corners == [0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0],
            "{off:?}"
        );
        // nothing to find: the whole photo, and said so
        let blank = root.join("blank.png");
        image::RgbImage::from_fn(640, 480, |x, y| image::Rgb([90 + (x % 3) as u8, 90 + (y % 3) as u8, 90])).save(&blank).unwrap();
        let none = find_in(&root, &blank.to_string_lossy(), ScanFindOptions::default()).unwrap();
        assert!(!none.found && none.source == ScanQuadSource::None && none.corners == [0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0], "{none:?}");
        // outside the root: refused
        let other = temp("find-other");
        assert!(find_in(&other, &shot.to_string_lossy(), ScanFindOptions::default()).is_err());
        std::fs::remove_dir_all(root).unwrap();
        std::fs::remove_dir_all(other).unwrap();
    }

    /// The preview is the page that will be saved, only smaller (#301): for every look it is
    /// within a few grey levels of the saved page shrunk to its size (black and white, which has
    /// two levels and is saved at twice the size, is the roughest). The straightened page is kept
    /// between calls, so a second look costs no decoding.
    #[test]
    fn a_preview_shows_what_will_be_saved() {
        let _store = PREVIEWS_TEST_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        let root = temp("preview");
        let shot = root.join("page.png");
        // a page of print, photographed whole on a table a little larger than it
        let drawn = image::load_from_memory(include_bytes!("../scan/tests/data/upright-english.png")).unwrap().to_rgb8();
        let mut photo = image::RgbImage::from_pixel(1400, 1900, image::Rgb([70, 60, 52]));
        let large = image::imageops::resize(&drawn, 1260, 1680, image::imageops::FilterType::CatmullRom);
        image::imageops::overlay(&mut photo, &large, 70, 110);
        photo.save(&shot).unwrap();
        let corners =
            vec![70.0 / 1400.0, 110.0 / 1900.0, 1330.0 / 1400.0, 110.0 / 1900.0, 1330.0 / 1400.0, 1790.0 / 1900.0, 70.0 / 1400.0, 1790.0 / 1900.0];
        let ask = |look, strength| ScanPageRequest { corners: corners.clone(), look, strength, ..request(&shot, &root.join("saved")) };
        scan_release_previews();
        let mut first = true;
        for (look, strength, within) in [
            (ScanLook::Original, 0, 3.0),
            (ScanLook::Auto, 60, 3.0),
            (ScanLook::Colour, 50, 3.0),
            (ScanLook::Greyscale, 40, 3.0),
            (ScanLook::BlackWhite, 75, 8.0),
        ] {
            let preview = preview_in(&root, &ask(look, strength), 1280).unwrap();
            assert_eq!(preview.rgba.len(), (preview.width * preview.height * 4) as usize);
            assert_eq!((preview.width.max(preview.height), preview.quarter_turns, preview.flattened), (1280, 0, false), "{look:?}");
            // the first call straightens the page; every other look finds it at hand
            assert_eq!(preview.reused, !first, "{look:?}");
            let stages: Vec<_> = preview.timings.iter().map(|t| t.stage.as_str()).collect();
            match first {
                true => assert_eq!(stages, ["scan_preview_base", "scan_decode", "scan_rectify", "scan_preview_look"]),
                false => assert_eq!(stages, ["scan_preview_look"]),
            }
            first = false;
            // the saved page, shrunk to the preview's size
            let saved = process_in(&root, &ask(look, strength)).unwrap();
            let full = image::open(&saved.path).unwrap().to_rgb8();
            let small = image::imageops::resize(&full, preview.width, preview.height, image::imageops::FilterType::Triangle);
            let luma = |p: &[u8]| 0.299 * f64::from(p[0]) + 0.587 * f64::from(p[1]) + 0.114 * f64::from(p[2]);
            let apart: f64 = preview.rgba.chunks_exact(4).zip(small.as_raw().chunks_exact(3)).map(|(a, b)| (luma(a) - luma(b)).abs()).sum();
            let mean = apart / f64::from(preview.width * preview.height);
            println!(
                "{look:?}: the preview is {mean:.2} grey levels from the saved page ({} x {} against {} x {})",
                preview.width, preview.height, saved.width, saved.height
            );
            assert!(mean <= within, "{look:?}: {mean}");
            assert!(preview.rgba.chunks_exact(4).all(|p| p[3] == 255));
        }
        // what changes the page before its look asks for a new one: other corners, another size
        let moved = ScanPageRequest { corners: corners.iter().map(|v| v * 0.999 + 0.0005).collect(), ..ask(ScanLook::Auto, 60) };
        assert!(!preview_in(&root, &moved, 1280).unwrap().reused);
        assert!(!preview_in(&root, &ask(ScanLook::Auto, 60), 640).unwrap().reused);
        assert!(!preview_in(&root, &ScanPageRequest { snap_paper: false, ..ask(ScanLook::Auto, 60) }, 1280).unwrap().reused);
        // four are kept: the first of five is straightened again, the last is at hand
        assert!(preview_in(&root, &ScanPageRequest { side_proportions: true, ..ask(ScanLook::Auto, 60) }, 1280).is_ok());
        assert!(preview_in(&root, &ScanPageRequest { side_proportions: true, ..ask(ScanLook::Original, 0) }, 1280).unwrap().reused);
        assert!(!preview_in(&root, &ask(ScanLook::Auto, 60), 1280).unwrap().reused);
        // a photo that changed on disk is another photo
        photo.put_pixel(0, 0, image::Rgb([1, 2, 3]));
        photo.save(&shot).unwrap();
        assert!(!preview_in(&root, &ask(ScanLook::Auto, 60), 1280).unwrap().reused);
        // forgotten when memory is short
        scan_release_previews();
        assert!(!preview_in(&root, &ask(ScanLook::Auto, 60), 1280).unwrap().reused);
        // sizes out of range are brought into it; bad requests are refused
        assert_eq!(preview_in(&root, &ask(ScanLook::Original, 0), 3).unwrap().height, 64);
        assert!(preview_in(&root, &ScanPageRequest { corners: vec![0.0; 6], ..ask(ScanLook::Auto, 60) }, 1280).is_err());
        let other = temp("preview-other");
        assert!(preview_in(&other, &ask(ScanLook::Auto, 60), 1280).is_err());
        std::fs::remove_dir_all(root).unwrap();
        std::fs::remove_dir_all(other).unwrap();
    }

    /// A page photographed sideways comes out upright, and says so (#339).
    #[test]
    fn a_sideways_page_is_turned_upright() {
        let root = temp("upright");
        let shot = root.join("sideways.png");
        let drawn = image::load_from_memory(include_bytes!("../scan/tests/data/upright-arabic.png")).unwrap().to_rgb8();
        let drawn = pdfcraft_scan::Rgb { width: drawn.width(), height: drawn.height(), data: drawn.into_raw() };
        // lying on its right side: turned clockwise once
        let sideways = pdfcraft_scan::turned(&drawn, 1);
        image::RgbImage::from_raw(sideways.width, sideways.height, sideways.data).unwrap().save(&shot).unwrap();
        let whole = vec![0.01, 0.01, 0.99, 0.01, 0.99, 0.99, 0.01, 0.99];
        let ask = |upright, out: &str| {
            process_in(&root, &ScanPageRequest { corners: whole.clone(), snap_paper: false, upright, ..request(&shot, &root.join(out)) }).unwrap()
        };
        let (kept, turned) = (ask(false, "kept"), ask(true, "turned"));
        assert_eq!((kept.quarter_turns, kept.orientation), (0, None));
        assert!(kept.width > kept.height);
        let found = turned.orientation.expect("assets/models/scan-upright.onnx is missing or is not the pinned file");
        assert_eq!((turned.quarter_turns, found.quarter_turns, found.sure), (3, 3, true), "{found:?}");
        assert!(found.confidence >= 0.7 && found.agreement >= 0.8 && found.tiles >= 4, "{found:?}");
        assert_eq!((turned.width, turned.height), (kept.height, kept.width));
        let stages: Vec<_> = turned.timings.iter().map(|t| t.stage.as_str()).collect();
        assert_eq!(stages, ["scan_decode", "scan_rectify", "scan_upright", "scan_look", "scan_encode"]);
        assert_eq!(turned.turns, 3);
        // the person's turn adds to the classifier's: one more press undoes it (#339)
        let back = process_in(
            &root,
            &ScanPageRequest { corners: whole.clone(), snap_paper: false, upright: true, turns: 1, ..request(&shot, &root.join("back")) },
        )
        .unwrap();
        assert_eq!((back.quarter_turns, back.turns, back.width, back.height), (3, 0, kept.width, kept.height));
        std::fs::remove_dir_all(root).unwrap();
    }

    /// The Rotate button (#339): the person's turns move the page's pixels, on top of the
    /// classifier's turn or in place of it, and a preview with another turn is made afresh.
    #[test]
    fn a_page_is_turned_by_the_persons_turns() {
        let _store = PREVIEWS_TEST_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        let root = temp("turns");
        let shot = photo(&root);
        // the Original look, so only the turn differs (a look reads its page in blocks, which turn too)
        let ask = |turns| ScanPageRequest { turns, look: ScanLook::Original, ..request(&shot, &root.join("turned")) };
        let straight = preview_in(&root, &ask(0), 256).unwrap();
        let rgb = |rgba: &[u8]| rgba.as_chunks::<4>().0.iter().flat_map(|p| [p[0], p[1], p[2]]).collect::<Vec<u8>>();
        let straight = pdfcraft_scan::Rgb { width: straight.width, height: straight.height, data: rgb(&straight.rgba) };
        for turns in 1..4u8 {
            let made = preview_in(&root, &ask(turns), 256).unwrap();
            assert!(!made.reused, "a preview kept for another turn was shown for {turns}");
            assert_eq!((made.turns, made.quarter_turns, made.orientation), (turns, 0, None));
            let expected = pdfcraft_scan::turned(&straight, turns);
            assert_eq!((made.width, made.height), (expected.width, expected.height));
            assert!(rgb(&made.rgba) == expected.data, "{turns} quarter turns are not the page turned clockwise {turns} times");
        }
        // a full turn is no turn, and the saved page agrees with its preview
        assert_eq!(preview_in(&root, &ask(4), 256).unwrap().turns, 0);
        let saved = process_in(&root, &ask(1)).unwrap();
        let unturned = process_in(&root, &ask(0)).unwrap();
        assert_eq!((saved.turns, saved.width, saved.height), (1, unturned.height, unturned.width));
        std::fs::remove_dir_all(root).unwrap();
    }

    /// A whole photo in Original that the person turned is no longer the camera's own file (#339).
    #[test]
    fn a_whole_original_photo_turned_by_hand_is_turned() {
        let root = temp("turns-whole");
        let shot = photo(&root);
        let ask = |turns, out: &str| {
            let whole = vec![0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0];
            process_in(&root, &ScanPageRequest { corners: whole, look: ScanLook::Original, turns, ..request(&shot, &root.join(out)) }).unwrap()
        };
        let (kept, turned) = (ask(0, "kept"), ask(3, "turned"));
        assert_eq!((kept.width, kept.height, kept.turns), (64, 48, 0));
        assert_eq!((turned.width, turned.height, turned.turns), (48, 64, 3));
        std::fs::remove_dir_all(root).unwrap();
    }

    /// Document types through the app's calls (#299): the preview and the saved page say which
    /// kind the Clean scan look took the page for, and "off" or an unknown word is as before.
    #[test]
    fn the_clean_scan_look_says_which_kind_of_document_it_took_the_page_for() {
        let _store = PREVIEWS_TEST_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        let root = temp("kinds");
        // a slip three times as long as wide, with rows of print, filling the photo
        let shot = root.join("slip.png");
        image::RgbImage::from_fn(300, 900, |x, y| {
            let print = (y % 30) < 3 && (30..270).contains(&x) && (x / 5) % 3 != 0;
            if print { image::Rgb([90, 90, 90]) } else { image::Rgb([238, 238, 232]) }
        })
        .save(&shot)
        .unwrap();
        let whole = vec![0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0];
        let ask = |preset: &str| ScanPageRequest {
            corners: whole.clone(),
            look: ScanLook::Auto,
            snap_paper: false,
            preset: preset.into(),
            ..request(&shot, &root.join("slip-out"))
        };
        let saved = process_in(&root, &ask("auto")).unwrap().kind.expect("a kind for Clean scan");
        assert_eq!((saved.preset.as_str(), saved.chosen, saved.strength, saved.sharpen), ("receipt", false, 80, 35));
        assert!((saved.ratio - 3.0).abs() < 0.01 && saved.suggest_greyscale && !saved.keep_paper, "{saved:?}");
        let shown = preview_in(&root, &ask("auto"), 600).unwrap().kind.expect("a kind in the preview");
        assert_eq!(shown.preset, "receipt");
        let chosen = process_in(&root, &ask("coloured_form")).unwrap().kind.unwrap();
        assert_eq!((chosen.preset.as_str(), chosen.chosen, chosen.keep_paper), ("coloured_form", true, true));
        assert!(process_in(&root, &ask("off")).unwrap().kind.is_none());
        assert!(process_in(&root, &ask("magic")).unwrap().kind.is_none());
        // only Clean scan has kinds
        assert!(process_in(&root, &ScanPageRequest { look: ScanLook::Colour, ..ask("auto") }).unwrap().kind.is_none());
        std::fs::remove_dir_all(root).unwrap();
    }

    /// The multi-page rule through the app's call (#339).
    #[test]
    fn unsure_pages_follow_two_sure_neighbours() {
        let found = |quarter_turns, confidence, sure| Some(ScanOrientation { quarter_turns, confidence, agreement: 1.0, tiles: 9, sure });
        let settled = scan_settle_turns(vec![found(1, 0.97, true), found(0, 0.4, false), found(1, 0.99, true), None, found(2, 0.3, false)]);
        let turns: Vec<_> = settled.iter().map(|s| (s.quarter_turns, s.followed)).collect();
        assert_eq!(turns, [(1, false), (1, true), (1, false), (0, false), (0, false)]);
        // a neighbour under 0.95 is not followed
        let settled = scan_settle_turns(vec![found(1, 0.9, true), found(0, 0.4, false), found(1, 0.99, true)]);
        assert_eq!(settled[1], ScanSettledTurn { quarter_turns: 0, followed: false });
        assert!(scan_settle_turns(Vec::new()).is_empty());
    }

    /// A page asked to be flattened goes through the flattening network (#302).
    #[test]
    fn a_page_is_flattened_when_asked() {
        let root = temp("flatten");
        let shot = root.join("page.png");
        // a sheet whose top and bottom edges bow by 24 pixels, on a dark table
        image::RgbImage::from_fn(900, 1200, |x, y| {
            let bow = 24.0 * (std::f64::consts::PI * (f64::from(x) - 150.0) / 600.0).sin();
            let (level, sheet) = (f64::from(y) + bow, (150..750).contains(&x));
            let sheet = sheet && level > 180.0 && level < 1020.0;
            let print = sheet && (200..700).contains(&x) && level > 240.0 && level < 960.0 && (level as i64 - 240).rem_euclid(30) < 9;
            image::Rgb(if print {
                [40, 40, 46]
            } else if sheet {
                [236, 233, 226]
            } else {
                [66, 54, 46]
            })
        })
        .save(&shot)
        .unwrap();
        assert!(scan_can_flatten(), "assets/models/scan-flatten.onnx is missing or is not the pinned file");
        let corners =
            vec![150.0 / 900.0, 180.0 / 1200.0, 750.0 / 900.0, 180.0 / 1200.0, 750.0 / 900.0, 1020.0 / 1200.0, 150.0 / 900.0, 1020.0 / 1200.0];
        let plain = process_in(&root, &ScanPageRequest { corners: corners.clone(), look: ScanLook::Original, ..request(&shot, &root.join("plain")) })
            .unwrap();
        assert!(!plain.flattened && plain.bend == 0.0);
        let flat =
            process_in(&root, &ScanPageRequest { corners, look: ScanLook::Original, flatten: true, ..request(&shot, &root.join("flat")) }).unwrap();
        assert!(flat.flattened && flat.bend > 0.003, "{flat:?}");
        assert_eq!((flat.width, flat.height), (plain.width, plain.height));
        let stages: Vec<_> = flat.timings.iter().map(|t| t.stage.as_str()).collect();
        assert_eq!(stages, ["scan_decode", "scan_flatten", "scan_rectify", "scan_look", "scan_encode"]);
        // the rows of print are level in the flattened page and arched in the other: how much
        // the first row's top edge moves between the page's sides and its middle
        let arch = |path: &str| {
            let page = image::open(path).unwrap().to_luma8();
            let top = |x: u32| (page.height() / 30..page.height() / 4).find(|&y| page.get_pixel(x, y).0[0] < 110).map_or(0.0, f64::from);
            let (w, scale) = (page.width(), f64::from(page.height()) / 840.0);
            ((top(w / 2) - top(w / 6)).abs() + (top(w / 2) - top(w * 5 / 6)).abs()) / 2.0 / scale
        };
        let (was, now) = (arch(&plain.path), arch(&flat.path));
        assert!(was > 8.0 && now < was / 3.0, "arched by {was:.1} px before and {now:.1} px after");
        assert_eq!(flat.flatten_check, None, "checked only when asked");
        std::fs::remove_dir_all(root).unwrap();
    }

    /// Flattening checked (#302's automatic mode): a bowed page keeps the flattened version, a flat
    /// one keeps the page straightened by its corners, and each says why with both measures.
    #[test]
    fn a_checked_flattening_is_kept_only_when_the_lines_come_out_straighter() {
        let root = temp("flatten-check");
        assert!(scan_can_flatten(), "assets/models/scan-flatten.onnx is missing or is not the pinned file");
        let corners =
            vec![150.0 / 900.0, 180.0 / 1200.0, 750.0 / 900.0, 180.0 / 1200.0, 750.0 / 900.0, 1020.0 / 1200.0, 150.0 / 900.0, 1020.0 / 1200.0];
        // the same sheet as above, bowed by `bow` pixels, with lines of words on it
        let sheet = |name: &str, bow: f64| {
            let shot = root.join(name);
            image::RgbImage::from_fn(900, 1200, |x, y| {
                let bend = bow * (std::f64::consts::PI * (f64::from(x) - 150.0) / 600.0).sin();
                let (level, sheet) = (f64::from(y) + bend, (150..750).contains(&x));
                let sheet = sheet && level > 180.0 && level < 1020.0;
                let word = x.wrapping_sub(200) % 50 < 38;
                let print = sheet && word && (200..700).contains(&x) && level > 240.0 && level < 960.0 && (level as i64 - 240).rem_euclid(30) < 9;
                image::Rgb(if print {
                    [40, 40, 46]
                } else if sheet {
                    [236, 233, 226]
                } else {
                    [66, 54, 46]
                })
            })
            .save(&shot)
            .unwrap();
            shot
        };
        let ask = |shot: &Path, out: &str| ScanPageRequest {
            corners: corners.clone(),
            look: ScanLook::Original,
            flatten: true,
            flatten_check: true,
            ..request(shot, &root.join(out))
        };
        let bowed = process_in(&root, &ask(&sheet("bowed.png", 24.0), "bowed")).unwrap();
        let check = bowed.flatten_check.clone().expect("checked");
        let (s, f) = (check.straightened_px.unwrap(), check.flattened_px.unwrap());
        assert!(check.kept && bowed.flattened && check.why == "straighter", "{check:?}");
        assert!(f <= s * 0.9 && check.straightened_lines >= 3 && check.flattened_lines >= 3, "{check:?}");
        let stages: Vec<_> = bowed.timings.iter().map(|t| t.stage.as_str()).collect();
        assert_eq!(stages, ["scan_decode", "scan_flatten", "scan_rectify", "scan_flatten_check", "scan_look", "scan_encode"]);
        let level = process_in(&root, &ask(&sheet("level.png", 0.0), "level")).unwrap();
        let check = level.flatten_check.clone().expect("checked");
        assert!(!check.kept && !level.flattened && check.why != "straighter", "{check:?}");
        // the preview makes the same choice
        let preview = preview_in(&root, &ask(&root.join("bowed.png"), "unused"), 1280).unwrap();
        assert!(preview.flattened && preview.flatten_check.is_some_and(|c| c.kept));
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn paths_outside_the_root_are_refused() {
        let root = temp("root");
        let other = temp("other");
        let inside = photo(&root);
        let outside = photo(&other);
        assert!(process_in(&root, &request(&outside, &root.join("p"))).is_err(), "photo outside");
        assert!(process_in(&root, &request(&inside, &other.join("p"))).is_err(), "output outside");
        assert!(process_in(&root, &request(&inside, &root.join("..").join(other.file_name().unwrap()).join("p"))).is_err(), "output through ..");
        assert!(process_in(&root, &request(&inside, &root.join("missing").join("p"))).is_err(), "no such folder");
        assert!(process_in(&root, &ScanPageRequest { corners: vec![0.0; 6], ..request(&inside, &root.join("p")) }).is_err(), "six numbers");
        assert!(!other.join("p.jpg").exists());
        std::fs::remove_dir_all(root).unwrap();
        std::fs::remove_dir_all(other).unwrap();
    }

    /// A drawn sheet with rows of words on a table, 480 × 600, the sheet at 60..420 × 50..550,
    /// with Gaussian-like noise of about `sigma` (sum of uniforms) from `seed`, then blurred by a
    /// box of side 2·`blur` + 1 three times across and down (0: sharp).
    fn burst_photo(sigma: f64, seed: u64, blur: i64) -> image::RgbImage {
        let (w, h) = (480i64, 600i64);
        let mut grey: Vec<f64> = (0..w * h)
            .map(|i| {
                let (x, y) = (i % w, i / w);
                let sheet = (60..420).contains(&x) && (50..550).contains(&y);
                let row = (y - 80).rem_euclid(22) < 8 && (80..520).contains(&y);
                let word = (x * 7 + (y / 22) * 31).rem_euclid(53) > 12;
                match (sheet, sheet && row && word && (90..390).contains(&x)) {
                    (true, true) => 45.0,
                    (true, false) => 220.0,
                    _ => 70.0,
                }
            })
            .collect();
        for _ in 0..3 * usize::from(blur > 0) {
            for across in [true, false] {
                let src = grey.clone();
                for y in 0..h {
                    for x in 0..w {
                        let sum: f64 = (-blur..=blur)
                            .map(|k| {
                                if across { src[(y * w + (x + k).clamp(0, w - 1)) as usize] } else { src[((y + k).clamp(0, h - 1) * w + x) as usize] }
                            })
                            .sum();
                        grey[(y * w + x) as usize] = sum / (2 * blur + 1) as f64;
                    }
                }
            }
        }
        let mut state = seed.wrapping_mul(0x9E37_79B9_7F4A_7C15) | 1;
        let mut uniform = move || {
            state ^= state << 13;
            state ^= state >> 7;
            state ^= state << 17;
            (state >> 11) as f64 / (1u64 << 53) as f64
        };
        image::RgbImage::from_fn(w as u32, h as u32, |x, y| {
            let v = grey[(i64::from(y) * w + i64::from(x)) as usize];
            image::Rgb(std::array::from_fn(|_| {
                // twelve uniforms: a standard deviation of 1
                let n: f64 = (0..12).map(|_| uniform()).sum::<f64>() - 6.0;
                (v + sigma * n).round().clamp(0.0, 255.0) as u8
            }))
        })
    }

    /// The sheet's corners as shares, a little off by (`dx`, `dy`) pixels.
    fn sheet(dx: f32, dy: f32) -> Vec<f32> {
        let (l, t, r, b) = (60.0 + dx, 50.0 + dy, 420.0 + dx, 550.0 + dy);
        vec![l / 480.0, t / 600.0, r / 480.0, t / 600.0, r / 480.0, b / 600.0, l / 480.0, b / 600.0]
    }

    /// #297 through the files: three noisy photos of a drawn sheet, each with its corners a
    /// little off, merge into a page written beside the request that is closer to the clean sheet
    /// than the reference photo straightened alone.
    #[test]
    fn a_burst_on_disk_merges_into_a_cleaner_page() {
        let root = temp("merge");
        let offsets = [(0.0f32, 0.0f32), (0.9, 0.5), (-0.6, -0.8)];
        let paths: Vec<PathBuf> = (0..3)
            .map(|i| {
                let p = root.join(format!("burst-{i}.png"));
                burst_photo(10.0, 7 + i as u64, 1).save(&p).unwrap();
                p
            })
            .collect();
        let frames: Vec<ScanMergeFrame> = paths
            .iter()
            .zip(offsets)
            .map(|(p, (dx, dy))| ScanMergeFrame { photo_path: p.to_string_lossy().into_owned(), corners: sheet(dx, dy) })
            .collect();
        let ask = |frames: Vec<ScanMergeFrame>, out: &Path| ScanMergeRequest {
            frames,
            output_path: out.to_string_lossy().into_owned(),
            format: ScanFormat::Png,
            focal_35mm: None,
            snap_paper: false,
        };
        let merged = merge_in(&root, &ask(frames.clone(), &root.join("merged"))).unwrap();
        assert!(merged.path.ends_with("merged.png"), "{}", merged.path);
        assert_eq!((merged.used, merged.rejected_blur, merged.rejected_align, merged.width, merged.height), (3, 0, 0, 360, 500), "{merged:?}");
        assert!(merged.psnr_gain_tenths_db > 40, "{merged:?}");
        let stages: Vec<_> = merged.timings.iter().map(|t| t.stage.as_str()).collect();
        assert_eq!(stages, ["scan_decode", "scan_sharpness", "scan_rectify", "scan_merge_align", "scan_merge", "scan_encode"]);
        // against the clean sheet straightened from the reference's corners
        let r = usize::from(merged.reference);
        let straighten = |picture: image::RgbImage| {
            let photo = pdfcraft_scan::Photo { width: 480, height: 600, rgb: picture.into_raw() };
            let shares: [f32; 8] = sheet(offsets[r].0, offsets[r].1).try_into().unwrap();
            pdfcraft_scan::rectify(&photo, &Quad::from_shares(&shares, 480, 600).unwrap(), 360, 500).unwrap().data
        };
        let truth = straighten(burst_photo(0.0, 0, 1));
        let single = straighten(image::open(&paths[r]).unwrap().to_rgb8());
        let written = image::open(&merged.path).unwrap().to_rgb8().into_raw();
        let rmse = |a: &[u8]| (a.iter().zip(&truth).map(|(&x, &y)| (f64::from(x) - f64::from(y)).powi(2)).sum::<f64>() / a.len() as f64).sqrt();
        let (alone, together) = (rmse(&single), rmse(&written));
        println!("error against the clean sheet: {alone:.2} for the reference alone, {together:.2} merged; {merged:?}");
        assert!(together < 0.75 * alone, "{together} against {alone}");

        // refused: too few or too many photos, a missing photo, three corners, outside the root
        assert!(merge_in(&root, &ask(frames[..1].to_vec(), &root.join("one"))).is_err());
        let five: Vec<_> = frames.iter().cycle().take(5).cloned().collect();
        assert!(merge_in(&root, &ask(five, &root.join("five"))).is_err());
        let missing = ScanMergeFrame { photo_path: root.join("missing.png").to_string_lossy().into_owned(), corners: sheet(0.0, 0.0) };
        assert!(merge_in(&root, &ask(vec![frames[0].clone(), missing], &root.join("missing"))).is_err());
        let short = ScanMergeFrame { corners: vec![0.1; 6], ..frames[1].clone() };
        assert!(merge_in(&root, &ask(vec![frames[0].clone(), short], &root.join("short"))).is_err());
        let crossed = ScanMergeFrame { corners: vec![0.5; 8], ..frames[1].clone() };
        assert!(merge_in(&root, &ask(vec![frames[0].clone(), crossed], &root.join("crossed"))).is_err());
        let other = temp("merge-other");
        assert!(merge_in(&root, &ask(frames.clone(), &other.join("merged"))).is_err());
        assert!(merge_in(&other, &ask(frames, &other.join("merged"))).is_err());
        for name in ["one", "five", "missing", "short", "crossed"] {
            assert!(!root.join(format!("{name}.png")).exists(), "{name}");
        }
        std::fs::remove_dir_all(root).unwrap();
        std::fs::remove_dir_all(other).unwrap();
    }

    /// #296 through the files: a sharp drawn sheet in a JPEG scores above the threshold, the
    /// same sheet blurred below it; bad requests are refused.
    #[test]
    fn a_blurred_photo_scores_lower_than_a_sharp_one() {
        let root = temp("sharpness");
        let (sharp, soft) = (root.join("sharp.jpg"), root.join("soft.jpg"));
        burst_photo(0.0, 0, 0).save(&sharp).unwrap();
        burst_photo(0.0, 0, 3).save(&soft).unwrap();
        let measure = |p: &Path| sharpness_in(&root, &p.to_string_lossy(), &sheet(0.0, 0.0));
        let (a, b) = (measure(&sharp).unwrap(), measure(&soft).unwrap());
        println!("sharp {a:?}\nblurred {b:?}");
        assert!(a.score > 2.0 * b.score, "{a:?} {b:?}");
        assert!(!a.blurred_unverified && b.blurred_unverified && a.threshold == 1.0, "{a:?} {b:?}");
        assert_eq!(a.timings.iter().map(|t| t.stage.as_str()).collect::<Vec<_>>(), ["scan_decode", "scan_sharpness"]);
        assert!(sharpness_in(&root, &root.join("missing.jpg").to_string_lossy(), &sheet(0.0, 0.0)).is_err());
        assert!(sharpness_in(&root, &sharp.to_string_lossy(), &[0.1; 6]).is_err());
        assert!(sharpness_in(&root, &sharp.to_string_lossy(), &[f32::NAN; 8]).is_err());
        let other = temp("sharpness-other");
        assert!(sharpness_in(&other, &sharp.to_string_lossy(), &sheet(0.0, 0.0)).is_err());
        std::fs::remove_dir_all(root).unwrap();
        std::fs::remove_dir_all(other).unwrap();
    }
}
