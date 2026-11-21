//! The whole page pipeline: decode → straighten → look → encode, each once, with a timing per
//! stage.

use std::time::Instant;

use crate::aspect::{AspectSource, Focal, Paper, PaperMatch, dpi_without_paper, proportions_by_sides_matched, proportions_matched};
use crate::encode::{Format, stamp_jpeg_density};
use crate::enhance::DEFAULT_SHARPEN;
use crate::enhance::Shape;
use crate::enhance::enlargement;
use crate::flatten::{predict_grid, unwarp, unwarp_trimmed};
use crate::flatten_guard::{Kept, MIN_LINES, Trim, border_trim, guard_with_floor};
use crate::flatten_lines::{LineFit, fit_page};
use crate::look::{Applied, Look, LookParams, Pixels, apply_prepared};
use crate::page_kind::{Curve, Measured, PageKind, PresetChoice, Rules, kind_of, measure};
use crate::photo_mask::PhotoMask;
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
/// Measuring the lines of text on the page straightened by its corners, to skip flattening a page
/// that is already flat (#549); only there when that was asked.
pub const STAGE_FLATTEN_FLAT: &str = "scan_flatten_flat";
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
    /// With `snap_paper`: the paper most of the scan's other pages were matched to (#759). A page
    /// within 10 % of its shape takes it. `None` for none.
    pub paper_hint: Option<Paper>,
    /// With `snap_paper`: whether the page may be matched to ID card (#759). Off for an outline the
    /// detector was not sure of.
    pub card_paper: bool,
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
    /// Whether the Auto look draws a grainy page's paper white from a lower level (a photo taken
    /// in dim light, #466); false is the curve as it was (a testing choice).
    pub grain_white: bool,
    /// How the Auto look picks its settings by the kind of document (#299): off (the look's own
    /// strength and `sharpen`), worked out from the page, or the person's choice. Other looks
    /// ignore it.
    pub preset: PresetChoice,
    /// How many sides of the page its edge detection found curved (0–4): a book page's sign (#299).
    pub curved_sides: u8,
    /// Two opposite sides of the page were found curved (#442).
    pub curved_opposite: bool,
    /// The page is one half of a spread cut in book mode (#303, #442).
    pub book_mode: bool,
    /// A book page needs more than one curved side: the gutter, two opposite curved sides, or a
    /// curved side in book mode, and one curved side only suggests flattening (#442); false is
    /// the first rule (#299, any curved side), a testing choice.
    pub strict_book: bool,
    /// A dark band down one long side counts as a book's gutter only when it runs the whole
    /// length, stays narrow and fades inward (#427); false is the first rule (#299), a testing choice.
    pub strict_gutter: bool,
    /// A block of the page counts as a photograph only where little paper shows, so barcodes,
    /// stamps and soft print are not photographs (#427); false is the first rule (#299), a testing
    /// choice.
    pub strict_photo: bool,
    /// The strength was set by hand (the page's slider or a testing option, #424): a document
    /// type does not change it.
    pub fixed_strength: bool,
    /// A document type also changes the tone curve: its extra gamma (a receipt's faint print),
    /// its S-curve and colour factor (a whiteboard) (#299). Off: only strength, sharpening and
    /// paper colour, as #391 had it.
    pub kind_tone: bool,
    /// Sharpen the photographs the block photo mask finds (#299, section 4) as gently as the
    /// objects the Auto look finds itself (30 %). Off: only those objects.
    pub photo_sharpen: bool,
    /// For a preview (#301): the page shrunk so its longer side is this many pixels at most,
    /// whatever its look. `None` for the page at the size it is saved at.
    pub max_side: Option<u32>,
    /// The paper's colour as the person chose it for Auto and Whiteboard (#301): `Some(true)`
    /// keeps the paper's own colour, `Some(false)` makes it white, `None` leaves it to the
    /// document type (white unless the type keeps it).
    pub keep_paper: Option<bool>,
    /// Clean scan and Whiteboard take what they measure of a straightened page independently of
    /// the strength and the paper (the light estimate, the grain, the page smoothed and sharpened)
    /// from an earlier look of the same [`PageBase`] (#446). The pixels are the same either way;
    /// false measures it again for every look, as before, a testing choice.
    pub reuse_look: bool,
    /// The automatic look removes fingers at the page's edge (#466): after Clean scan at
    /// strength [`FINGER_MIN_STRENGTH`] or more, fingers are found on the look's own output and
    /// papered over. False leaves the page as the look made it, as before.
    pub remove_fingers: bool,
    /// Marks made by hand on the page (#495): drawn after the look and the finger step. `None` or
    /// empty for none.
    pub markup: Option<&'a crate::markup::Markup>,
    /// The person's size choice at Save (#505). Read only with `auto_encode`.
    pub size: crate::size::SizeChoice,
    /// Store the page as [`crate::size::store`] decides from what is on it (#505); false stores
    /// every page as before (a JPEG at quality 92 at full size, 1 bit for Black and white),
    /// whatever `size` says.
    pub auto_encode: bool,
    /// What the page's preview decided about flattening it (#549): with `flatten`, the page is
    /// flattened by the preview's grid, or straightened by its corners when the preview was,
    /// without running the network, the check or the text lines again. `None` decides afresh.
    pub flatten_known: Option<&'a FlattenDecision>,
    /// With `flatten_check` (the automatic mode): a page whose four sides were found straight
    /// (`curved_sides` 0) and whose lines of text are already straight on the page straightened
    /// by its corners is left so, without the network, its check or the text lines (#549). False
    /// flattens every such page as before.
    pub skip_flat: bool,
    /// Clean scan gives the photographs the block photo mask finds on a page of print the Photo
    /// document type's strength inside them (#784), so their shadows and highlights are kept
    /// while the print around them is cleaned as before. Needs a document type (`preset` not
    /// off), which is what finds the photographs. False: one curve for the whole page, as before.
    pub photo_tone: bool,
    /// With `flatten_check` (the automatic mode): the lines of text are measured on the page
    /// straightened by its corners before the network runs, and when that measure alone already
    /// says the check could keep no flattened page ([`crate::flatten_guard::foreknown`]: not
    /// measured, already straight, or too few lines), the network, its check and the text lines
    /// are not run (#785). The page is the same either way; the measure is also taken once
    /// instead of again for the text lines. False runs them and throws their page away, as before.
    pub flatten_foresee: bool,
}

/// The weakest Clean scan strength at which fingers are looked for (#466): below it the paper is
/// not white enough for the finger search's thresholds.
pub const FINGER_MIN_STRENGTH: u8 = 40;

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
            paper_hint: None,
            card_paper: true,
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
            grain_white: true,
            preset: PresetChoice::Off,
            curved_sides: 0,
            curved_opposite: false,
            book_mode: false,
            strict_book: true,
            strict_gutter: true,
            strict_photo: true,
            fixed_strength: false,
            kind_tone: false,
            photo_sharpen: false,
            max_side: None,
            keep_paper: None,
            reuse_look: true,
            remove_fingers: false,
            markup: None,
            size: crate::size::SizeChoice::Best,
            auto_encode: false,
            flatten_known: None,
            skip_flat: false,
            photo_tone: false,
            flatten_foresee: false,
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
    /// The flattening was the preview's decision, taken over instead of made again (#549).
    pub flatten_reused: bool,
    /// The page was found flat already and not flattened (#549).
    pub flatten_skipped_flat: bool,
    /// The check could keep no flattened page, known before the network ran, so it did not (#785).
    pub flatten_foreseen: bool,
    /// The flattening decision the page was made with, when flattening was asked for: kept by the
    /// app's bridge so the page made again with another look is not flattened again (#549).
    pub flatten_decision: Option<FlattenDecision>,
    /// The page's grain as the Auto look measured it: the standard deviation of its noise on
    /// blank paper, in grey levels of 255 (`None` for the other looks).
    pub noise: Option<f32>,
    /// How much the page's paper varies after its light is divided out, as a share of the
    /// paper's own brightness, and the level from which paper was drawn white (#466): about
    /// 0.005 to 0.02 and the strength's own white point on a clean page, more and lower on a photo
    /// taken in dim light. `None` for the other looks.
    pub paper_spread: Option<f32>,
    pub white_point: Option<f32>,
    /// The kind of document the Auto look took the page for, and its settings (#299); `None` for
    /// the other looks or with document types off.
    pub kind: Option<PageKind>,
    /// What the automatic look's finger step did (#466); `None` when it did not run.
    pub fingers: Option<crate::defects::FingerFill>,
    /// What drawing the page's hand-made marks did (#495); `None` when it had none.
    pub markup: Option<crate::markup::Applied>,
    /// What giving the page's photographs their own tone curve did (#784); `None` when it was not
    /// asked for or the look is not Clean scan.
    pub photo_tone: Option<crate::look::PhotoTone>,
    /// How the page was stored (#505); `None` for a camera file kept as it was.
    pub encoded: Option<crate::size::Encoded>,
    /// (stage, milliseconds), in pipeline order.
    pub timings: Vec<(&'static str, u32)>,
}

/// How the flattened page and the page straightened by its corners compared, when flattening was
/// checked (#302).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct FlattenCheck {
    /// The flattened page was kept.
    pub kept: bool,
    /// Why: `straighter`, `notbetter`, `unmeasured`, `straight`, `fewlines` or `muchbetter` (see [`crate::flatten_guard::Why`]).
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

/// What a page's making decided about flattening it (#549). The grid is in photo pixels, so a
/// decision taken on a preview holds for the same photo and corners at any size: the page made
/// at full size is drawn from it without the network, the check or the text lines.
#[derive(Clone, Debug, PartialEq)]
pub struct FlattenDecision {
    /// The grid the kept page was flattened by (the network's or its text lines'); `None`: the
    /// page was straightened by its corners.
    pub grid: Option<crate::flatten::FlattenGrid>,
    /// The grid is the network's: a page flattened by it has its border trimmed (when asked).
    pub network: bool,
    /// How far the network found the page from flat (0 when it did not run).
    pub bend: f32,
    pub check: Option<FlattenCheck>,
    pub line_flatten: Option<LineFlatten>,
    /// The page was found flat already, so nothing was run (#549).
    pub skipped_flat: bool,
    /// The network and the text lines were not run: no page of theirs could have been kept (#785).
    pub foreseen: bool,
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
    /// What this page's making decided about flattening it (#549), for the page made from the same
    /// photo and corners at another size; `None` when flattening was not asked for.
    pub flatten_decision: Option<FlattenDecision>,
    /// The flattening was [`PageRequest::flatten_known`]'s, not made again (#549).
    pub flatten_reused: bool,
    /// The page was found flat already and not flattened ([`PageRequest::skip_flat`], #549).
    pub flatten_skipped_flat: bool,
    /// No flattened page could have been kept, so none was made ([`PageRequest::flatten_foresee`], #785).
    pub flatten_foreseen: bool,
    /// (stage, milliseconds) of the stages so far.
    pub timings: Vec<(&'static str, u32)>,
    /// What the page measures as for the document types (#299), taken the first time a look asks
    /// and kept (#427): every later look of the same base (the preview's looks and strengths)
    /// reuses it. Empty until then; `None` inside when the page could not be measured.
    pub measured: std::sync::OnceLock<Option<Measured>>,
    /// What Clean scan and Whiteboard measure of the page independently of the strength and the
    /// paper (#446), taken the first time one of them is asked for and kept: a preview's next
    /// strength or paper choice costs the tone curve alone. Not part of what the page is.
    pub look_kept: crate::enhance::Prepared,
}

impl PageBase {
    /// The page's measurements for the document types, taken once (see `measured`).
    pub fn measured(&self) -> Option<&Measured> {
        self.measured.get_or_init(|| measure(&self.page, self.dpi, self.paper)).as_ref()
    }
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
    let marked = request.markup.is_some_and(|m| !m.is_empty());
    // a smaller size than Best stores the photo again (#505)
    let smaller = request.auto_encode && request.size != crate::size::SizeChoice::Best;
    if request.look != Look::Original || !whole || request.flatten || turned_by_hand || request.max_side.is_some() || marked || smaller {
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
        flatten_reused: false,
        flatten_skipped_flat: false,
        flatten_foreseen: false,
        flatten_decision: None,
        noise: None,
        paper_spread: None,
        white_point: None,
        kind: None,
        fingers: None,
        markup: None,
        photo_tone: None,
        encoded: None,
        timings,
    }))
}

/// Run the pipeline on one photo.
pub fn process_page(request: &PageRequest<'_>) -> Result<PageResult, ScanError> {
    process_page_stored(request, &[]).map(|(page, _)| page)
}

/// [`process_page`], and the same made page stored also at each of `also` (#528): the page is made
/// once and only stored again, so a page made ahead of Save is ready at whatever size is picked.
/// A page kept as the camera's file (a whole photo in Original at Best) is not made, so nothing
/// else is stored for it.
pub fn process_page_stored(request: &PageRequest<'_>, also: &[crate::size::SizeChoice]) -> Result<(PageResult, Vec<crate::size::Stored>), ScanError> {
    if let Some(page) = untouched(request) {
        return page.map(|p| (p, Vec::new()));
    }
    let base = page_base(request)?;
    let mut clock = Instant::now();
    let mut lap = |stage: &'static str, timings: &mut Vec<(&'static str, u32)>| {
        let ms = u32::try_from(clock.elapsed().as_millis()).unwrap_or(u32::MAX);
        timings.push((stage, ms));
        clock = Instant::now();
    };
    let (applied, kind) = page_look_kind(&base, request)?;
    let (paper_spread, white_point, photo_tone) = (applied.paper_spread, applied.white_point, applied.photo_tone);
    let (pixels, noise, look_stages, fingers, markup) = (applied.pixels, applied.noise, applied.stages, applied.fingers, applied.markup);
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
        flatten_decision,
        flatten_reused,
        flatten_skipped_flat,
        flatten_foreseen,
        mut timings,
        measured: _,
        look_kept: _,
    } = base;
    let (w, h) = (page.width, page.height);
    drop(page);
    lap(STAGE_LOOK, &mut timings);
    // the look's own parts (#446), after the whole, and what the whole holds besides them (#549)
    let rest = look_rest(timings.last().map_or(0, |t| t.1), &look_stages);
    timings.extend(look_stages.into_iter().map(|(stage, ms)| (scan_stage(stage), ms)));
    timings.extend(rest.map(|ms| (scan_stage(crate::look::STAGE_REST), ms)));

    let made = crate::size::Made { pixels: &pixels, width: w, height: h, dpi, look: request.look, kind: kind.as_ref() };
    let stored = crate::size::store(&made, request.size, request.auto_encode)?;
    let (bytes, format) = (stored.bytes, stored.format);
    let (w, h, dpi) = (stored.encoded.width, stored.encoded.height, stored.encoded.dpi);
    lap(STAGE_ENCODE, &mut timings);
    let others = also
        .iter()
        .filter(|c| **c != request.size)
        .map(|c| crate::size::store(&made, *c, request.auto_encode))
        .collect::<Result<Vec<_>, _>>()?;

    let page = PageResult {
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
        flatten_reused,
        flatten_skipped_flat,
        flatten_foreseen,
        flatten_decision,
        noise,
        paper_spread,
        white_point,
        kind,
        fingers,
        markup,
        photo_tone,
        encoded: Some(stored.encoded),
        timings,
    };
    Ok((page, others))
}

/// The page made once and stored at each size choice (#505), for the Save sheet's estimates and
/// its "as it will be saved" view: what [`process_page`] stores at each, with the automatic
/// encoding. A whole photo in Original, which Best keeps as the camera's file, is made like any
/// other page here.
pub fn page_sizes(request: &PageRequest<'_>) -> Result<Vec<crate::size::Stored>, ScanError> {
    let base = page_base(request)?;
    let (applied, kind) = page_look_kind(&base, request)?;
    let (w, h, dpi) = (base.page.width, base.page.height, base.dpi);
    drop(base);
    let made = crate::size::Made { pixels: &applied.pixels, width: w, height: h, dpi, look: request.look, kind: kind.as_ref() };
    crate::size::SizeChoice::ALL.into_iter().map(|choice| crate::size::store(&made, choice, true)).collect()
}

/// The part of a look of `total` milliseconds its named `parts` do not account for (#549): `None`
/// for a look with no named parts (Original, Colour, Greyscale, Black and white).
pub fn look_rest(total: u32, parts: &[(&'static str, u32)]) -> Option<u32> {
    (!parts.is_empty()).then(|| total.saturating_sub(parts.iter().map(|p| p.1).sum()))
}

/// The timing stage `scan_look_…` of a part of the look (#446).
fn scan_stage(stage: &'static str) -> &'static str {
    match stage {
        crate::look::STAGE_WHITEBOARD => "scan_look_whiteboard",
        crate::look::STAGE_KIND => "scan_look_kind",
        crate::look::STAGE_FINGERS => "scan_look_fingers",
        crate::look::STAGE_MARKUP => "scan_look_markup",
        crate::look::STAGE_REST => "scan_look_rest",
        crate::enhance::STAGE_LIGHT => "scan_look_light",
        crate::enhance::STAGE_NOISE => "scan_look_noise",
        crate::enhance::STAGE_DENOISE => "scan_look_denoise",
        crate::enhance::STAGE_SHARPEN => "scan_look_sharpen",
        crate::enhance::STAGE_TONE => "scan_look_tone",
        crate::enhance::STAGE_PHOTO_TONE => "scan_look_photo_tone",
        other => other,
    }
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
    let ms = |clock: Instant| u32::try_from(clock.elapsed().as_millis()).unwrap_or(u32::MAX);
    let clock = Instant::now();
    let asks_kind = matches!(request.look, Look::Auto) && request.preset != PresetChoice::Off;
    let kind = match request.look {
        Look::Auto if request.preset != PresetChoice::Off => {
            let rules = Rules { strict_gutter: request.strict_gutter, strict_photo: request.strict_photo, strict_book: request.strict_book };
            let curve = Curve { sides: request.curved_sides, opposite: request.curved_opposite, book_mode: request.book_mode };
            base.measured().and_then(|m| kind_of(m, curve, request.preset, rules))
        }
        _ => None,
    };
    // the document type's time (#549): measuring the page (once for a base) and choosing its kind
    let kind_ms = asks_kind.then(|| ms(clock));
    let mut params = LookParams {
        strength: request.strength,
        threads: request.threads,
        dpi: f32::from(base.dpi),
        sharpen: request.sharpen,
        denoise: request.denoise,
        keep_paper: false,
        shape: Shape::default(),
        grain_white: request.grain_white,
        photo_gentle: request.photo_sharpen,
    };
    if let Some(kind) = &kind {
        if !request.fixed_strength {
            params.strength = kind.params.strength;
        }
        // the request's sharpening scales the kind's (#424): the default (30) gives the kind's
        // own, 0 none, 60 twice the kind's, so each level of the testing option does something
        params.sharpen = kind_sharpen(kind.params.sharpen, request.sharpen);
        params.keep_paper = kind.params.keep_paper;
        if request.kind_tone {
            params.shape = kind_shape(&kind.params);
        }
    }
    // the person's choice of paper wins over the document type's (#301)
    if let Some(keep) = request.keep_paper {
        params.keep_paper = keep;
    }
    let photos = kind.as_ref().filter(|_| request.photo_sharpen || request.photo_tone).and_then(|k| k.photos.as_ref());
    let fresh;
    let kept = match request.reuse_look {
        true => &base.look_kept,
        false => {
            fresh = crate::enhance::Prepared::new();
            &fresh
        }
    };
    let mut applied = apply_prepared(&base.page, request.look, &params, photos, kept).ok_or(ScanError::Encode)?;
    if let Some(kind_ms) = kind_ms {
        applied.stages.insert(0, (crate::look::STAGE_KIND, kind_ms));
    }
    // the photographs get their own tone curve after the finger step (#784), which reads the
    // look's one curve as before; what that step fills is then known to be a finger
    let toned = photos.filter(|m| {
        request.photo_tone
            && request.look == Look::Auto
            && !m.whole_page
            && photo_tone_strength() < params.strength
            && m.weight.iter().any(|&k| k > 0.0)
    });
    let mut before_fingers: Option<Vec<u8>> = None;
    // fingers at the page's edge, found on the look's own output and papered over (#466)
    if request.remove_fingers && request.look == Look::Auto && params.strength >= FINGER_MIN_STRENGTH {
        if let Pixels::Rgb(data) = &mut applied.pixels {
            let clock = Instant::now();
            if toned.is_some() {
                before_fingers = Some(data.clone());
            }
            let mut page = Rgb { width: base.page.width, height: base.page.height, data: std::mem::take(data) };
            applied.fingers = Some(crate::defects::remove_fingers(&mut page));
            *data = page.data;
            applied.stages.push((crate::look::STAGE_FINGERS, ms(clock)));
        }
    }
    if let (Some(mask), Pixels::Rgb(data)) = (toned, &mut applied.pixels) {
        let clock = Instant::now();
        let filled = before_fingers.as_deref().filter(|_| applied.fingers.is_some_and(|f| f.filled > 0));
        let (weights, fingers_left_out) = photo_weights(mask, filled, data, base.page.width as usize, base.page.height as usize);
        let done = crate::look::photo_tone(&base.page, &params, mask, &weights, photo_tone_strength(), kept, data);
        let share = if done { weights.iter().filter(|&&k| k > 0.0).count() as f32 / weights.len().max(1) as f32 } else { 0.0 };
        applied.photo_tone = Some(crate::look::PhotoTone { share, fingers_left_out, ms: ms(clock) });
        applied.stages.push((crate::enhance::STAGE_PHOTO_TONE, ms(clock)));
    } else if request.photo_tone && request.look == Look::Auto {
        applied.photo_tone = Some(crate::look::PhotoTone::default());
    }
    drop(before_fingers);
    // the marks made by hand (#495), over the look and the finger step, turned with the page
    if let Some(markup) = request.markup.filter(|m| !m.is_empty()) {
        let clock = Instant::now();
        applied.markup = Some(crate::markup::apply(&mut applied.pixels, base.page.width, base.page.height, base.turns, markup));
        applied.stages.push((crate::look::STAGE_MARKUP, ms(clock)));
    }
    Ok((applied, kind))
}

/// The photo mask's weights for the photographs' own tone curve (#784): `mask`'s, without the
/// groups of blocks (touching, corners included) in which the finger step changed a pixel
/// between `before` and `after` (both the `w` × `h` page, RGB8). A finger is skin, which the
/// mask reads as a photograph; once it is papered over there is nothing there to tone, and the
/// gentler curve would only grey the paper put in its place. Returns the weights and how many
/// groups were left out.
pub(crate) fn photo_weights(mask: &PhotoMask, before: Option<&[u8]>, after: &[u8], w: usize, h: usize) -> (Vec<f32>, u32) {
    let (cols, rows) = (mask.cols, mask.rows);
    let mut weights = mask.weight.clone();
    let Some(before) = before.filter(|b| b.len() == after.len() && after.len() == w * h * 3 && cols > 0 && rows > 0 && weights.len() == cols * rows)
    else {
        return (weights, 0);
    };
    let changed: Vec<bool> = (0..cols * rows)
        .map(|i| {
            let (c, r) = (i % cols, i / cols);
            let (x0, x1, y0, y1) = (c * w / cols, (c + 1) * w / cols, r * h / rows, (r + 1) * h / rows);
            weights[i] > 0.0 && (y0..y1).any(|y| before[(y * w + x0) * 3..(y * w + x1) * 3] != after[(y * w + x0) * 3..(y * w + x1) * 3])
        })
        .collect();
    let mut left_out = 0u32;
    let mut seen = vec![false; cols * rows];
    for start in 0..cols * rows {
        if seen[start] || weights[start] <= 0.0 {
            continue;
        }
        // one group of weighted blocks
        let (mut group, mut stack, mut hit) = (Vec::new(), vec![start], false);
        seen[start] = true;
        while let Some(i) = stack.pop() {
            group.push(i);
            hit |= changed[i];
            let (c, r) = (i % cols, i / cols);
            for rr in r.saturating_sub(1)..(r + 2).min(rows) {
                for cc in c.saturating_sub(1)..(c + 2).min(cols) {
                    let j = rr * cols + cc;
                    if !seen[j] && weights[j] > 0.0 {
                        seen[j] = true;
                        stack.push(j);
                    }
                }
            }
        }
        if hit {
            left_out += 1;
            for i in group {
                weights[i] = 0.0;
            }
        }
    }
    (weights, left_out)
}

/// The strength of Clean scan's tone curve inside a photograph on a page of print (#784): the
/// Photo document type's own, the one a page that is all photograph gets.
pub fn photo_tone_strength() -> u8 {
    crate::preset::params(crate::preset::Preset::Photo).strength
}

/// A document type's change to the tone curve (#299).
pub fn kind_shape(p: &crate::preset::PresetParams) -> Shape {
    Shape { extra_gamma: p.extra_gamma, s_curve: p.s_curve, colour_factor: p.colour_factor }
}

/// The proportions of the page inside `quad` on a photo of `width` × `height` pixels taken with
/// `lens`, as `request` asks them: what [`page_base`] and the first picture ([`crate::first`]) share,
/// so both give a page the same shape.
pub(crate) fn page_shape(request: &PageRequest<'_>, quad: &Quad, width: u32, height: u32, lens: crate::exif::Camera) -> crate::aspect::Proportions {
    // the caller's focal length is the one in use (zoom included); the file's may or may not be
    let camera = match request.focal_35mm {
        Some(f) => Focal::from_35mm(Some(f), None, width, height),
        None => Focal::from_35mm(lens.focal_35mm, lens.digital_zoom, width, height),
    };
    // the scan's own paper first, ID card only for a sure outline (#759)
    let matched = PaperMatch { on: request.snap_paper, hint: request.paper_hint, card: request.card_paper };
    match request.side_proportions {
        true => proportions_by_sides_matched(quad, width, height, matched),
        false => proportions_matched(quad, width, height, camera, matched),
    }
}

/// How much a page of `shape` is enlarged or shrunk when it is made for `request`.
pub(crate) fn page_scale(request: &PageRequest<'_>, shape: &crate::aspect::Proportions) -> Result<f64, ScanError> {
    Ok(match (request.max_side, request.look) {
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
    })
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
    let shape = page_shape(request, &quad, photo.width, photo.height, lens);
    let scale = page_scale(request, &shape)?;
    let (w, h, dpi) = shape.output(scale)?;
    // A page whose outline was not found is the whole photo, table and all. The network is made
    // for a page with its own edges, and in the automatic mode (the check is asked for) nothing
    // would tell a page it bent wrongly from one it flattened: on the owner's photos the one such
    // page read 26 % straighter and was further from the reference (#466). So it is not asked
    // there; the page's lines of text may still flatten it. Flatten page chosen by hand asks it.
    let known = request.flatten_known.filter(|_| request.flatten);
    let flat = match known {
        Some(known) => reuse_flattening(&photo, &quad, (w, h), request, known, &mut |stage, t| lap(stage, t), &mut timings)?,
        None => decide_flattening(&photo, &quad, (w, h), request, &mut |stage, t| lap(stage, t), &mut timings)?,
    };
    let Flat { page, flattened, bend, flatten_check, trim, line_flatten, skipped_flat, foreseen, decision } = flat;
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
        bend,
        quarter_turns,
        orientation,
        turns,
        flatten_check,
        trim,
        line_flatten,
        flatten_decision: decision.filter(|_| request.flatten),
        flatten_reused: known.is_some(),
        flatten_skipped_flat: skipped_flat,
        flatten_foreseen: foreseen,
        timings,
        measured: std::sync::OnceLock::new(),
        look_kept: crate::enhance::Prepared::new(),
    })
}

/// A page straightened by its corners or flattened, and how.
struct Flat {
    page: Rgb,
    flattened: bool,
    bend: f32,
    flatten_check: Option<FlattenCheck>,
    trim: Option<Trim>,
    line_flatten: Option<LineFlatten>,
    skipped_flat: bool,
    foreseen: bool,
    decision: Option<FlattenDecision>,
}

type Lap<'l> = dyn FnMut(&'static str, &mut Vec<(&'static str, u32)>) + 'l;

/// The page flattened or straightened, deciding which afresh: the network (and its check), then
/// the page's lines of text, as asked.
fn decide_flattening(
    photo: &Photo,
    quad: &Quad,
    (w, h): (u32, u32),
    request: &PageRequest<'_>,
    lap: &mut Lap<'_>,
    timings: &mut Vec<(&'static str, u32)>,
) -> Result<Flat, ScanError> {
    // A page whose outline was not found is the whole photo, table and all. The network is made
    // for a page with its own edges, and in the automatic mode (the check is asked for) nothing
    // would tell a page it bent wrongly from one it flattened: on the owner's photos the one such
    // page read 26 % straighter and was further from the reference (#466). So it is not asked
    // there; the page's lines of text may still flatten it. Flatten page chosen by hand asks it.
    let whole = request.corners.iter().zip(WHOLE_PHOTO).all(|(a, b)| (a - b).abs() < 1e-6);
    // A page with four straight sides whose lines of text are already straight needs no
    // flattening (#549): measured on the page straightened by its corners, before the network
    // runs. A page that is not flat keeps that page and its measure for the check.
    let mut measured_plain: Option<(Rgb, Option<Straightness>)> = None;
    // The plain page's measure, when it was taken: the text lines' check needs it again (#785).
    let mut plain_measure: Option<Option<Straightness>> = None;
    let skip_flat = request.skip_flat && request.curved_sides == 0;
    if request.flatten && request.flatten_check && (skip_flat || request.flatten_foresee) {
        let plain = rectify_with_threads(photo, quad, w, h, request.threads)?;
        lap(STAGE_RECTIFY, timings);
        let measure = line_straightness(&evened_grey(&plain));
        lap(STAGE_FLATTEN_FLAT, timings);
        if skip_flat && measure.is_some_and(|m| already_flat(m, request.flatten_min_lines)) {
            let decision =
                FlattenDecision { grid: None, network: false, bend: 0.0, check: None, line_flatten: None, skipped_flat: true, foreseen: false };
            return Ok(Flat {
                page: plain,
                flattened: false,
                bend: 0.0,
                flatten_check: None,
                trim: None,
                line_flatten: None,
                skipped_flat: true,
                foreseen: false,
                decision: Some(decision),
            });
        }
        // The check keeps a flattened page only on what both pages measure. When this page's own
        // measure already rules that out, whatever a flattened page would read, the network's page
        // and the text lines' would both be made and thrown away: neither is made (#785). The
        // check is recorded with the reason and no figure for a flattened page.
        let lost = crate::flatten_guard::foreknown(measure.map(|m| m.px), measure.map(|m| m.lines), request.flatten_min_lines);
        if let Some(why) = lost.filter(|_| request.flatten_foresee) {
            let check = FlattenCheck { kept: false, why: why.id(), min_lines: request.flatten_min_lines, straightened: measure, flattened: None };
            let decision = FlattenDecision {
                grid: None,
                network: false,
                bend: 0.0,
                check: Some(check),
                line_flatten: None,
                skipped_flat: false,
                foreseen: true,
            };
            return Ok(Flat {
                page: plain,
                flattened: false,
                bend: 0.0,
                flatten_check: Some(check),
                trim: None,
                line_flatten: None,
                skipped_flat: false,
                foreseen: true,
                decision: Some(decision),
            });
        }
        plain_measure = request.flatten_foresee.then_some(measure);
        measured_plain = Some((plain, measure));
    }
    let grid = if request.flatten && !(whole && request.flatten_check) { predict_grid(photo, quad) } else { None };
    if request.flatten {
        lap(STAGE_FLATTEN, timings);
    }
    // a grid that cannot be used leaves the page straightened by its corners, as without the switch
    let flat = grid.as_ref().and_then(|grid| unwarp(photo, grid, w, h, request.threads).ok());
    let (page, flatten_check, flattened) = match flat {
        Some(flat) if request.flatten_check => {
            lap(STAGE_RECTIFY, timings);
            let check = match measured_plain.take() {
                Some((plain, measure)) => (plain, compare_measured(measure, &flat, request.flatten_min_lines)),
                None => {
                    let plain = rectify_with_threads(photo, quad, w, h, request.threads)?;
                    let check = compare_flattened(&plain, &flat, request.flatten_min_lines);
                    (plain, check)
                }
            };
            let (plain, check) = check;
            lap(STAGE_FLATTEN_CHECK, timings);
            let kept = check.kept;
            (if kept { flat } else { plain }, Some(check), kept)
        }
        Some(flat) => {
            lap(STAGE_RECTIFY, timings);
            (flat, None, true)
        }
        None => match measured_plain.take() {
            Some((plain, _)) => (plain, None, false),
            None => {
                let plain = rectify_with_threads(photo, quad, w, h, request.threads)?;
                lap(STAGE_RECTIFY, timings);
                (plain, None, false)
            }
        },
    };
    // a flattened page can keep a thin strip of table along an edge: drawn again without it (#302)
    let (page, trim) = match grid.as_ref() {
        Some(grid) if flattened && request.flatten_trim => {
            let (page, trim) = trim_flattened(photo, grid, page, request.threads);
            lap(STAGE_FLATTEN_TRIM, timings);
            (page, Some(trim))
        }
        _ => (page, None),
    };
    let bend = grid.as_ref().map_or(0.0, |g| g.bend() as f32);
    let network = flattened;
    let mut kept_grid = if flattened { grid } else { None };
    // the network was not there or not kept: the page's lines of text may still flatten it
    let (page, flattened, line_flatten) = match request.flatten && request.line_flatten && !flattened {
        true => {
            // the page here is the one straightened by its corners: its measure is not taken twice
            let measure = plain_measure.or(flatten_check.filter(|_| request.flatten_foresee).map(|c| c.straightened));
            let (lined, outcome) = flatten_by_lines_grid(photo, quad, &page, request.threads, request.flatten_min_lines, measure);
            lap(STAGE_FLATTEN_LINES, timings);
            match lined {
                Some((lined, grid)) => {
                    kept_grid = Some(grid);
                    (lined, true, Some(outcome))
                }
                None => (page, false, Some(outcome)),
            }
        }
        false => (page, flattened, None),
    };
    let decision = FlattenDecision {
        grid: kept_grid,
        network,
        bend,
        check: flatten_check,
        line_flatten: line_flatten.clone(),
        skipped_flat: false,
        foreseen: false,
    };
    Ok(Flat { page, flattened, bend, flatten_check, trim, line_flatten, skipped_flat: false, foreseen: false, decision: Some(decision) })
}

/// The page flattened or straightened as `known` decided (#549): drawn once from its grid, or
/// straightened by its corners, with the border trimmed again at this size when it was the
/// network's page.
fn reuse_flattening(
    photo: &Photo,
    quad: &Quad,
    (w, h): (u32, u32),
    request: &PageRequest<'_>,
    known: &FlattenDecision,
    lap: &mut Lap<'_>,
    timings: &mut Vec<(&'static str, u32)>,
) -> Result<Flat, ScanError> {
    let flat = known.grid.as_ref().and_then(|grid| unwarp(photo, grid, w, h, request.threads).ok());
    let (page, flattened) = match flat {
        Some(flat) => (flat, true),
        None => (rectify_with_threads(photo, quad, w, h, request.threads)?, false),
    };
    lap(STAGE_RECTIFY, timings);
    let (page, trim) = match known.grid.as_ref() {
        Some(grid) if flattened && known.network && request.flatten_trim => {
            let (page, trim) = trim_flattened(photo, grid, page, request.threads);
            lap(STAGE_FLATTEN_TRIM, timings);
            (page, Some(trim))
        }
        _ => (page, None),
    };
    Ok(Flat {
        page,
        flattened,
        bend: known.bend,
        flatten_check: known.check,
        trim,
        line_flatten: known.line_flatten.clone(),
        skipped_flat: known.skipped_flat,
        foreseen: known.foreseen,
        decision: Some(known.clone()),
    })
}

/// The page straightened by its corners is flat already (#549): its lines of text are as straight
/// as the guard calls "already straight", and enough of them were followed to say so (the guard's
/// floor, at least [`crate::flatten_guard::FEW_LINES`]).
fn already_flat(m: Straightness, min_lines: u32) -> bool {
    m.px < crate::flatten_guard::STRAIGHT_ENOUGH_PX && m.lines >= min_lines.max(crate::flatten_guard::FEW_LINES)
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
#[cfg(test)]
pub(crate) fn flatten_by_lines(photo: &Photo, quad: &Quad, plain: &Rgb, threads: usize, min_lines: u32) -> (Option<Rgb>, LineFlatten) {
    let (lined, outcome) = flatten_by_lines_grid(photo, quad, plain, threads, min_lines, None);
    (lined.map(|(page, _)| page), outcome)
}

/// [`flatten_by_lines`], with the grid the kept page was drawn from (#549). `measured` is
/// `plain`'s line measure when it was already taken (#785); `None` measures it here.
fn flatten_by_lines_grid(
    photo: &Photo,
    quad: &Quad,
    plain: &Rgb,
    threads: usize,
    min_lines: u32,
    measured: Option<Option<Straightness>>,
) -> (Option<(Rgb, crate::flatten::FlattenGrid)>, LineFlatten) {
    let fit = match fit_page(&evened_grey(plain)) {
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
    let check = match measured {
        Some(measure) => compare_measured(measure, &lined, min_lines),
        None => compare_flattened(plain, &lined, min_lines),
    };
    let outcome = LineFlatten { gave_up: None, fit: Some(fit), check: Some(check) };
    (check.kept.then_some((lined, grid)), outcome)
}

/// Measures the lines of text on the page straightened by its corners and on the flattened one,
/// and keeps the flattened page only when it is clearly straighter (#302, the quality guard) and
/// at least `min_lines` lines were followed on both (0: no floor; a page that reads far
/// straighter is kept on fewer, see [`crate::flatten_guard::guard_with_floor`]).
///
/// Both pages are measured with their light evened out ([`evened_grey`]): under a shadow the
/// lines cannot be followed otherwise (#466: on the owner's sheet in the shade of a tree 4 lines
/// were followed, and 16 once the light was evened).
pub fn compare_flattened(straightened: &Rgb, flattened: &Rgb, min_lines: u32) -> FlattenCheck {
    compare_measured(line_straightness(&evened_grey(straightened)), flattened, min_lines)
}

/// [`compare_flattened`] with the straightened page's lines already measured (`s`, #549).
fn compare_measured(s: Option<Straightness>, flattened: &Rgb, min_lines: u32) -> FlattenCheck {
    let f = line_straightness(&evened_grey(flattened));
    let decision = guard_with_floor(s.map(|m| m.px), f.map(|m| m.px), (s.map(|m| m.lines), f.map(|m| m.lines)), min_lines);
    FlattenCheck { kept: decision.kept == Kept::Flattened, why: decision.why.id(), min_lines, straightened: s, flattened: f }
}

/// The page in shades of grey, for measuring.
fn grey(page: &Rgb) -> Luma {
    let data = page.data.chunks_exact(3).map(|p| crate::photo::luma([p[0], p[1], p[2]])).collect();
    Luma { width: page.width, height: page.height, data }
}

/// Cells along the page's longer side for [`evened_grey`]'s light.
const EVEN_CELLS: usize = 48;
/// The deepest shadow that is evened out, as a share of the page's bright level: darker than
/// this is taken for something that is dark itself. (The owner's sheet under a tree is at 0.6 to
/// 0.7 in its shade.)
const EVEN_DEEPEST: f32 = 0.45;
/// The grey level evened paper is brought to: under 255, so paper a little brighter than its
/// surroundings is not cut off.
const EVEN_PAPER: f32 = 230.0;

/// The page in shades of grey with its light evened out, for following lines of text: every
/// pixel over the paper's brightness near it, paper at [`EVEN_PAPER`].
///
/// The light is the 90th percentile of each cell of the page ([`EVEN_CELLS`] along the longer
/// side: paper shows between lines of print), raised to the middle level of the nine cells
/// around it where the cell itself is mostly print, and read between cell centres. It is far
/// cheaper and rougher than the Auto look's light and is only for measuring: along a shadow's
/// hard edge a band up to a cell wide stays dark, and what is darker than [`EVEN_DEEPEST`] of
/// the page's bright level stays as dark as it is. A page too small for cells comes back as
/// plain grey.
pub(crate) fn evened_grey(page: &Rgb) -> Luma {
    let mut out = grey(page);
    let (w, h) = (out.width as usize, out.height as usize);
    let cell = w.max(h).div_ceil(EVEN_CELLS).max(4);
    let (cw, ch) = (w.div_ceil(cell), h.div_ceil(cell));
    if cw < 3 || ch < 3 || out.data.len() != w * h {
        return out;
    }
    // each cell's bright level: the 90th percentile of its grey levels, from their counts
    let mut bright = vec![0f32; cw * ch];
    for cy in 0..ch {
        for cx in 0..cw {
            let mut counts = [0u32; 256];
            let mut n = 0u32;
            for y in cy * cell..((cy + 1) * cell).min(h) {
                for &v in &out.data[y * w + cx * cell..y * w + ((cx + 1) * cell).min(w)] {
                    counts[usize::from(v)] += 1;
                    n += 1;
                }
            }
            let mut above = n / 10;
            let mut level = 255usize;
            while level > 0 && counts[level] <= above {
                above -= counts[level];
                level -= 1;
            }
            bright[cy * cw + cx] = level as f32;
        }
    }
    // a cell that is mostly print (a heading, a rule, a filled box) takes the middle level of
    // the nine cells around it: paper, unless most of them are dark too, which is a shadow
    let mut light = vec![0f32; cw * ch];
    for cy in 0..ch {
        for cx in 0..cw {
            let mut around = [0f32; 9];
            let mut n = 0;
            for y in cy.saturating_sub(1)..=(cy + 1).min(ch - 1) {
                for x in cx.saturating_sub(1)..=(cx + 1).min(cw - 1) {
                    around[n] = bright[y * cw + x];
                    n += 1;
                }
            }
            around[..n].sort_by(f32::total_cmp);
            light[cy * cw + cx] = around[n / 2].max(bright[cy * cw + cx]);
        }
    }
    // a shadow dims paper; a table beside the page or a dark picture is not dimmed paper. What
    // is darker than [`EVEN_DEEPEST`] of the page's own bright level is left as dark as it is.
    let mut levels = light.clone();
    levels.sort_by(f32::total_cmp);
    let floor = EVEN_DEEPEST * levels[levels.len() * 9 / 10];
    for l in &mut light {
        *l = l.max(floor);
    }
    // read between cell centres, and divide
    let at = |v: usize, n: usize| {
        let pos = ((v as f32 + 0.5) / cell as f32 - 0.5).clamp(0.0, (n - 1) as f32);
        let i = (pos as usize).min(n - 2);
        (i, pos - i as f32)
    };
    for y in 0..h {
        let (cy, fy) = at(y, ch);
        for x in 0..w {
            let (cx, fx) = at(x, cw);
            let (a, b) = (
                light[cy * cw + cx] + (light[cy * cw + cx + 1] - light[cy * cw + cx]) * fx,
                light[(cy + 1) * cw + cx] + (light[(cy + 1) * cw + cx + 1] - light[(cy + 1) * cw + cx]) * fx,
            );
            let paper = (a + (b - a) * fy).max(16.0);
            let v = &mut out.data[y * w + x];
            *v = (f32::from(*v) * EVEN_PAPER / paper).round().clamp(0.0, 255.0) as u8;
        }
    }
    out
}
