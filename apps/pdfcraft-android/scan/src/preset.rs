//! Document-type presets (issue #299, spec S13 section 5).
//!
//! A receipt, a whiteboard, an ID card and a printed letter want different clean-up. This module
//! names the kind of a scanned page from a few cheap measurements, and gives the settings for
//! that kind. The rules are checked in the spec's order and the first match wins. A value that is
//! not finite never matches a rule. Nothing here reads a clock or a random source, so the same
//! input always gives the same answer.

use crate::{Luma, Rgb, luma};

/// A kind of document page. Each kind has its own clean-up settings (see `params`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Preset {
    /// A photograph: keep its tones and sharpen little.
    Photo,
    /// An ID card, matched to the card's size: keep the card's own colours.
    IdCard,
    /// A till receipt or other long, narrow slip: lift its faint print and make it grey.
    Receipt,
    /// A whiteboard: thick marker strokes on a pale board, with a curve that lifts the board.
    Whiteboard,
    /// A page of a book: a gutter shadow, two opposite curved sides, or a curved side in book mode
    /// (#442), so it should be flattened.
    BookPage,
    /// A form printed or filled on coloured paper. Never chosen by `classify`: only a person
    /// picks it, because white paper under coloured light also looks coloured.
    ColouredForm,
    /// A page of text with a large photograph on it.
    PhotoRich,
    /// Any other page: the default.
    Document,
}

/// The measurements `classify` reads. All are cheap and come from maps the scanner already has.
#[derive(Clone, Debug, PartialEq)]
pub struct PageFeatures {
    /// Longer side over shorter side.
    pub ratio: f64,
    /// Shorter side in millimetres when the paper's real size is known.
    pub short_mm: Option<f64>,
    /// The page was matched to an ID card's size.
    pub id_card: bool,
    /// Share of pixels darker than half white, after lighting correction.
    pub ink_share: f64,
    /// Share of ink pixels whose chroma is over `COLOUR_CHROMA`.
    pub colour_ink_share: f64,
    /// Share of the page inside the photo mask, by the rule in force: the mask that leaves out
    /// blocks that are mostly paper (#427), or the first rule's when that testing option is off.
    /// This is the one `classify` reads.
    pub photo_share: f64,
    /// What the first photo rule (#299) says, kept for the log whichever rule is in force: it
    /// also counts barcodes, stamps and soft print.
    pub photo_loose: f64,
    /// Median stroke width of the ink, in millimetres, when there is any ink.
    pub stroke_mm: Option<f64>,
    /// How many of the four sides are curved (from the page's edge detection).
    pub curved_sides: u8,
    /// Two opposite sides are both curved: the top and the bottom, or the left and the right
    /// (#442). An open book's page bows along both its top and its bottom edge; a sheet held in
    /// one hand or curling at one corner bends one side.
    pub curved_opposite: bool,
    /// The page is one half of a spread cut in book mode (#303): the person said it is a book.
    pub book_mode: bool,
    /// A dark band runs down one long side (a book's gutter), by the rule in force: the strict
    /// rule of `gutter_reading` (#427), or the first rule (`gutter_band`) when that testing option
    /// is off. This is the one `classify` reads.
    pub gutter_band: bool,
    /// What the first rule (`gutter_band`, #299) says, kept for the log whichever rule is in force.
    pub gutter_loose: bool,
    /// Share of the dark long side's length along which its strip is dark (0–1; see `GutterReading`).
    pub gutter_cover: f64,
    /// Share of pixels with all three channels at `BRIGHT_LEVEL` or more, before the light is
    /// evened. Not a glare measure: white paper in good light reaches it (#427). No rule reads it.
    pub bright_share: f64,
}

/// The clean-up settings for one preset.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct PresetParams {
    /// How strongly the clean-up works, as the scanner's strength scale (0 to 100).
    pub strength: u8,
    /// Sharpening amount, 0 to 100 (the spec's 0.30 is 30).
    pub sharpen: u8,
    /// Keep the paper's own colour instead of whitening it.
    pub keep_paper: bool,
    /// Suggest flattening the page (the flatten step of spec S16).
    pub suggest_flatten: bool,
    /// Suggest a grey, not colour, result.
    pub suggest_greyscale: bool,
    /// Leave photographs alone: sharpen them only a little, see the photo mask.
    pub photo_mask: bool,
    /// Extra gamma added to the tone curve; positive lifts faint print.
    pub extra_gamma: f32,
    /// Use the S-curve `0.5 − 0.5·cos(Y^0.75·π)` instead of the gamma curve.
    pub s_curve: bool,
    /// Multiplier on the colour saturation of the result.
    pub colour_factor: f32,
}

// Thresholds of the rules, from the spec's rule table (section 5).

/// Photo: a page whose photo share is over this is a photograph.
const PHOTO_SHARE_PHOTO: f64 = 0.6;
/// Receipt: the long side over the short side must be over this.
const RECEIPT_RATIO: f64 = 2.2;
/// Receipt: or the short side must be under this many millimetres (when the size is known).
const RECEIPT_SHORT_MM: f64 = 90.0;
/// Whiteboard: the median stroke must be over this many millimetres.
const WHITEBOARD_STROKE_MM: f64 = 1.2;
/// Whiteboard: and the ink share must be under this.
const WHITEBOARD_INK_SHARE: f64 = 0.06;
/// Photo-rich document: a photo share over this makes the page photo-rich.
const PHOTO_RICH_SHARE: f64 = 0.15;

// Settings of each preset, from the spec's parameter column (section 5). A value the spec does
// not give for a preset is the Document preset's value (see `DOCUMENT`).

const PHOTO_STRENGTH: u8 = 25;
const PHOTO_SHARPEN: u8 = 15;
const ID_STRENGTH: u8 = 30;
const ID_SHARPEN: u8 = 25;
const RECEIPT_STRENGTH: u8 = 80;
const RECEIPT_GAMMA: f32 = 0.3;
const RECEIPT_SHARPEN: u8 = 35;
const WHITEBOARD_STRENGTH: u8 = 90;
const WHITEBOARD_COLOUR_FACTOR: f32 = 1.5;
const WHITEBOARD_SHARPEN: u8 = 20;
const BOOK_STRENGTH: u8 = 60;
const COLOURED_STRENGTH: u8 = 55;
const PHOTO_RICH_STRENGTH: u8 = 50;
const DOCUMENT_STRENGTH: u8 = 60;
const DOCUMENT_SHARPEN: u8 = 30;
/// Neutral values for the settings the spec's table does not name: no extra gamma, no change of
/// colour, and no flatten, grey or photo-mask suggestion.
const DOCUMENT_GAMMA: f32 = 0.0;
const DOCUMENT_COLOUR_FACTOR: f32 = 1.0;

/// The Document preset: the default, and the base that every other preset starts from.
const DOCUMENT: PresetParams = PresetParams {
    strength: DOCUMENT_STRENGTH,
    sharpen: DOCUMENT_SHARPEN,
    keep_paper: false,
    suggest_flatten: false,
    suggest_greyscale: false,
    photo_mask: false,
    extra_gamma: DOCUMENT_GAMMA,
    s_curve: false,
    colour_factor: DOCUMENT_COLOUR_FACTOR,
};

// Ink measurements (issue #299).

/// The white level of an 8-bit channel.
const WHITE: f64 = 255.0;
/// Ink is a pixel whose luminance is under this (half of white).
const INK_LUMA: f64 = 0.5 * WHITE;
/// A coloured ink pixel has (max − min) / 255 over this.
const COLOUR_CHROMA: f64 = 0.15;
/// Bright: a pixel with all three channels at this level or more is near white.
const BRIGHT_LEVEL: u8 = 250;
/// Millimetres in an inch: converts pixels to millimetres at a dpi.
const MM_PER_INCH: f64 = 25.4;

// Gutter band (issue #299).

/// The strip along a long side is this share of the short side's length.
const GUTTER_STRIP_SHARE: f64 = 0.06;
/// The strip's mean must be this share under the page's median luminance.
const GUTTER_DARK_SHARE: f64 = 0.12;
/// Strict rule (#427): the long side is cut into this many equal pieces along its length.
const GUTTER_SEGMENTS: usize = 10;
/// Strict rule: the strip must be dark in at least this many of the pieces (the whole length,
/// allowing one piece for a corner the crop cut or a page number).
const GUTTER_SEGMENTS_DARK: usize = 9;
/// Strict rule: the inner half of the strip must still be this share under the page's median: a
/// gutter's shadow fades across the strip, where a thin dark surround left by the crop stops.
const GUTTER_INNER_DARK_SHARE: f64 = 0.04;
/// Strict rule: the outer half of the strip must be darker than its inner half by this share of
/// the median: a shadow darkens toward the binding, where a band of dark surround is flat.
const GUTTER_FALLOFF_SHARE: f64 = 0.03;
/// Grey levels in one byte: the size of the luminance histogram.
const LUMA_LEVELS: usize = 256;

/// The spec's rules in the spec's order; the first that matches wins, with the book page rule in
/// force (`is_book_page`, #442). `ColouredForm` is never returned, because the spec says so (white
/// paper under coloured light looked coloured on four of the owner's ten reference photos): only a
/// person can choose it.
pub fn classify(features: &PageFeatures) -> Preset {
    classify_with(features, true)
}

/// `classify` with the book page rule in force (`strict_book`) or the first one (#299, a testing
/// choice; see `is_book_page`).
pub fn classify_with(features: &PageFeatures, strict_book: bool) -> Preset {
    if above(features.photo_share, PHOTO_SHARE_PHOTO) {
        Preset::Photo
    } else if features.id_card {
        Preset::IdCard
    } else if is_receipt(features) {
        Preset::Receipt
    } else if is_whiteboard(features) {
        Preset::Whiteboard
    } else if is_book_page(features, strict_book) {
        Preset::BookPage
    } else if above(features.photo_share, PHOTO_RICH_SHARE) {
        Preset::PhotoRich
    } else {
        Preset::Document
    }
}

/// Book page. The strict rule (#442) needs more than one curved side: the gutter band, the two
/// opposite sides both curved, or a curved side on a page cut in book mode. A sheet held in the
/// hand or curling at one edge has one curved side too, and is not a book: that side suggests
/// flattening (`curve_suggests_flatten`) without changing the kind. The first rule (#299), kept
/// as a testing choice, took any curved side for a book.
pub fn is_book_page(features: &PageFeatures, strict_book: bool) -> bool {
    if strict_book {
        features.gutter_band || features.curved_opposite || (features.curved_sides >= 1 && features.book_mode)
    } else {
        features.curved_sides >= 1 || features.gutter_band
    }
}

/// A page with a curved side is not lying flat, whatever its kind: a good reason to suggest
/// flattening it (#442).
pub fn curve_suggests_flatten(features: &PageFeatures) -> bool {
    features.curved_sides >= 1
}

/// Receipt: a long, narrow page, or a short side under `RECEIPT_SHORT_MM` when the size is known.
fn is_receipt(features: &PageFeatures) -> bool {
    above(features.ratio, RECEIPT_RATIO) || features.short_mm.is_some_and(|s| below(s, RECEIPT_SHORT_MM))
}

/// Whiteboard: thick strokes and little ink.
fn is_whiteboard(features: &PageFeatures) -> bool {
    features.stroke_mm.is_some_and(|s| above(s, WHITEBOARD_STROKE_MM)) && below(features.ink_share, WHITEBOARD_INK_SHARE)
}

/// True when `value` is finite and over `limit`.
fn above(value: f64, limit: f64) -> bool {
    value.is_finite() && value > limit
}

/// True when `value` is finite and under `limit`.
fn below(value: f64, limit: f64) -> bool {
    value.is_finite() && value < limit
}

/// The clean-up settings for `preset`, from the spec's table (section 5).
pub fn params(preset: Preset) -> PresetParams {
    match preset {
        Preset::Photo => PresetParams { strength: PHOTO_STRENGTH, sharpen: PHOTO_SHARPEN, keep_paper: true, ..DOCUMENT },
        Preset::IdCard => PresetParams { strength: ID_STRENGTH, sharpen: ID_SHARPEN, keep_paper: true, ..DOCUMENT },
        Preset::Receipt => {
            PresetParams { strength: RECEIPT_STRENGTH, sharpen: RECEIPT_SHARPEN, extra_gamma: RECEIPT_GAMMA, suggest_greyscale: true, ..DOCUMENT }
        }
        Preset::Whiteboard => PresetParams {
            strength: WHITEBOARD_STRENGTH,
            sharpen: WHITEBOARD_SHARPEN,
            s_curve: true,
            colour_factor: WHITEBOARD_COLOUR_FACTOR,
            ..DOCUMENT
        },
        Preset::BookPage => PresetParams { strength: BOOK_STRENGTH, suggest_flatten: true, ..DOCUMENT },
        Preset::ColouredForm => PresetParams { strength: COLOURED_STRENGTH, keep_paper: true, ..DOCUMENT },
        Preset::PhotoRich => PresetParams { strength: PHOTO_RICH_STRENGTH, photo_mask: true, ..DOCUMENT },
        Preset::Document => DOCUMENT,
    }
}

impl Preset {
    /// Every preset, in the order of the enum. Used by tests and by the chip's list.
    pub const ALL: [Preset; 8] = [
        Preset::Photo,
        Preset::IdCard,
        Preset::Receipt,
        Preset::Whiteboard,
        Preset::BookPage,
        Preset::ColouredForm,
        Preset::PhotoRich,
        Preset::Document,
    ];

    /// A short lowercase word for the diagnostics log, with no space: "photo", "id_card", ….
    pub fn id(self) -> &'static str {
        match self {
            Preset::Photo => "photo",
            Preset::IdCard => "id_card",
            Preset::Receipt => "receipt",
            Preset::Whiteboard => "whiteboard",
            Preset::BookPage => "book_page",
            Preset::ColouredForm => "coloured_form",
            Preset::PhotoRich => "photo_rich",
            Preset::Document => "document",
        }
    }
}

/// Ink measurements of one straightened page.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct InkFeatures {
    /// Share of all pixels with luminance under `INK_LUMA`.
    pub ink_share: f64,
    /// Share of the ink pixels whose chroma is over `COLOUR_CHROMA` (0 when there is no ink).
    pub colour_ink_share: f64,
    /// Median stroke width in millimetres, `None` when there is no ink.
    pub stroke_mm: Option<f64>,
    /// Share of all pixels with all three channels at `BRIGHT_LEVEL` or more. This is the plain
    /// share, not glare: white paper in good light reaches it (#427). The caller measures it
    /// before the paper is whitened, because on whitened paper every pixel would count.
    pub bright_share: f64,
}

/// Measures the ink of a page, from the page after lighting correction. Ink: luminance under
/// half of white. Coloured ink: ink whose (max − min) / 255 is over `COLOUR_CHROMA`. Stroke: the
/// median run length of ink along the rows and along the columns, in millimetres at `dpi`; the
/// smaller of the two medians is the stroke, because a run along the stroke is its length and a
/// run across it is its width. `None` for an empty page, a page whose data length does not match
/// its size, or a `dpi` that is not a positive finite number.
pub fn ink_features(page: &Rgb, dpi: f32) -> Option<InkFeatures> {
    let (w, h, n) = dims(page.width, page.height)?;
    if page.data.len() != n.checked_mul(3)? || !(dpi.is_finite() && dpi > 0.0) {
        return None;
    }
    let (ink, tally) = scan_pixels(&page.data);
    let total = n as f64;
    let colour_ink_share = if tally.ink == 0 { 0.0 } else { tally.coloured as f64 / tally.ink as f64 };
    Some(InkFeatures {
        ink_share: tally.ink as f64 / total,
        colour_ink_share,
        stroke_mm: stroke_mm(&ink, w, h, dpi),
        bright_share: tally.bright as f64 / total,
    })
}

/// Counts from one pass over the pixels.
#[derive(Default)]
struct Tally {
    ink: usize,
    coloured: usize,
    bright: usize,
}

/// One pass over the RGB bytes: the ink mask (one flag per pixel) and the counts.
fn scan_pixels(data: &[u8]) -> (Vec<bool>, Tally) {
    let mut tally = Tally::default();
    let mut ink = Vec::with_capacity(data.len() / 3);
    for px in data.chunks_exact(3) {
        let [r, g, b] = [px[0], px[1], px[2]];
        let is_ink = f64::from(luma([r, g, b])) < INK_LUMA;
        ink.push(is_ink);
        if is_ink {
            tally.ink += 1;
            let chroma = f64::from(r.max(g).max(b) - r.min(g).min(b)) / WHITE;
            if chroma > COLOUR_CHROMA {
                tally.coloured += 1;
            }
        }
        if r >= BRIGHT_LEVEL && g >= BRIGHT_LEVEL && b >= BRIGHT_LEVEL {
            tally.bright += 1;
        }
    }
    (ink, tally)
}

/// Width and height and pixel count of a page, `None` when empty or too large to count.
fn dims(width: u32, height: u32) -> Option<(usize, usize, usize)> {
    let w = usize::try_from(width).ok()?;
    let h = usize::try_from(height).ok()?;
    let n = w.checked_mul(h)?;
    (n > 0).then_some((w, h, n))
}

/// The median stroke width of the ink mask, in millimetres at `dpi`.
fn stroke_mm(ink: &[bool], w: usize, h: usize, dpi: f32) -> Option<f64> {
    let mut row_runs = Vec::new();
    for row in ink.chunks_exact(w) {
        row_runs.extend(run_lengths(row.iter().copied()));
    }
    let mut col_runs = Vec::new();
    for x in 0..w {
        col_runs.extend(run_lengths((0..h).map(|y| ink.get(y * w + x).copied().unwrap_or(false))));
    }
    let pixels = median(row_runs)?.min(median(col_runs)?);
    Some(pixels as f64 * MM_PER_INCH / f64::from(dpi))
}

/// The lengths of the runs of `true` in a line of flags.
fn run_lengths(line: impl Iterator<Item = bool>) -> Vec<usize> {
    let mut runs = Vec::new();
    let mut current = 0usize;
    for flag in line {
        if flag {
            current += 1;
        } else if current > 0 {
            runs.push(current);
            current = 0;
        }
    }
    if current > 0 {
        runs.push(current);
    }
    runs
}

/// The median of a list of run lengths (the upper middle one for an even count), `None` when empty.
fn median(mut values: Vec<usize>) -> Option<usize> {
    values.sort_unstable();
    values.get(values.len() / 2).copied()
}

/// A dark band along one of the long sides (a book's gutter). The mean luminance of the outer
/// `GUTTER_STRIP_SHARE` strip along a long side must be at least `GUTTER_DARK_SHARE` under the
/// page's median, and the other long side's strip must not be. A square page has no long side,
/// so it is never a gutter. A page whose data does not match its size is never a gutter.
pub fn gutter_band(page: &Luma) -> bool {
    let Some((w, h, n)) = dims(page.width, page.height) else { return false };
    if w == h || page.data.len() != n {
        return false;
    }
    let landscape = w > h;
    let (long, short) = if landscape { (w, h) } else { (h, w) };
    let strip = ((short as f64 * GUTTER_STRIP_SHARE).round() as usize).clamp(1, short);
    let pixel = |along: usize, across: usize| {
        let (x, y) = if landscape { (along, across) } else { (across, along) };
        page.data.get(y * w + x).copied().unwrap_or(0)
    };
    let mean = |across: std::ops::Range<usize>| {
        let sum: u64 = (0..long).flat_map(|a| across.clone().map(move |c| (a, c))).map(|(a, c)| u64::from(pixel(a, c))).sum();
        sum as f64 / (long * strip) as f64
    };
    let limit = median_level(&page.data) * (1.0 - GUTTER_DARK_SHARE);
    let first_dark = mean(0..strip) <= limit;
    let second_dark = mean(short - strip..short) <= limit;
    first_dark != second_dark
}

/// What the gutter rules read on a page (#299, #427).
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct GutterReading {
    /// The first rule (`gutter_band`): one long side's strip is dark on average and the other's is not.
    pub loose: bool,
    /// The strict rule (#427): the first rule, and on the dark side the band also runs the whole
    /// length (`GUTTER_SEGMENTS_DARK` of `GUTTER_SEGMENTS` pieces), is narrow (the next strip in is
    /// not dark), and fades inward (darker at the edge, still dark in the strip's inner half).
    pub strict: bool,
    /// Share of the pieces along the darker long side whose strip is dark (0–1).
    pub cover: f64,
}

/// Both gutter rules on `page` (#427). The first rule (`gutter_band`) fired on a sheet in dappled
/// shade and on a flat label in the review of #427: a patch of shade or a band of dark surround
/// along one side is enough for it. A book's gutter is a shadow that runs the whole length of the
/// binding side, stays near it, and fades toward the page's middle; the strict rule asks for all
/// three. A page whose data does not match its size, or a square page, reads as no gutter.
pub fn gutter_reading(page: &Luma) -> GutterReading {
    let Some((w, h, n)) = dims(page.width, page.height) else { return GutterReading::default() };
    if w == h || page.data.len() != n {
        return GutterReading::default();
    }
    let landscape = w > h;
    let (long, short) = if landscape { (w, h) } else { (h, w) };
    let strip = ((short as f64 * GUTTER_STRIP_SHARE).round() as usize).clamp(1, short);
    let pixel = |along: usize, across: usize| {
        let (x, y) = if landscape { (along, across) } else { (across, along) };
        u64::from(page.data.get(y * w + x).copied().unwrap_or(0))
    };
    // mean over `along` × `across`, where `across` counts from the first long side (`from_far`
    // false) or from the second
    let mean = |along: std::ops::Range<usize>, across: std::ops::Range<usize>, from_far: bool| {
        let count = along.len() * across.len();
        if count == 0 {
            return f64::NAN;
        }
        let sum: u64 = along.flat_map(|a| across.clone().map(move |c| (a, c))).map(|(a, c)| pixel(a, if from_far { short - 1 - c } else { c })).sum();
        sum as f64 / count as f64
    };
    let median = median_level(&page.data);
    let limit = median * (1.0 - GUTTER_DARK_SHARE);
    let first_dark = mean(0..long, 0..strip, false) <= limit;
    let second_dark = mean(0..long, 0..strip, true) <= limit;
    let loose = first_dark != second_dark;
    // the strict checks run on the darker side (the dark one when the first rule fired)
    let far = if loose { second_dark } else { mean(0..long, 0..strip, true) < mean(0..long, 0..strip, false) };
    let pieces = GUTTER_SEGMENTS.min(long);
    let dark_pieces = (0..pieces).filter(|&i| mean(i * long / pieces..(i + 1) * long / pieces, 0..strip, far) <= limit).count();
    let cover = dark_pieces as f64 / pieces as f64;
    let half = (strip / 2).max(1);
    let outer = mean(0..long, 0..half, far);
    let inner = if half < strip { mean(0..long, half..strip, far) } else { outer };
    let beyond_end = (2 * strip).min(short / 2);
    let beyond = if beyond_end > strip { mean(0..long, strip..beyond_end, far) } else { f64::NAN };
    let whole_length = dark_pieces * GUTTER_SEGMENTS >= GUTTER_SEGMENTS_DARK * pieces;
    let narrow = beyond.is_finite() && beyond > limit;
    let fades = inner <= median * (1.0 - GUTTER_INNER_DARK_SHARE) && inner - outer >= median * GUTTER_FALLOFF_SHARE;
    GutterReading { loose, strict: loose && whole_length && narrow && fades, cover }
}

/// The median grey level of the bytes, from a 256-bin histogram (the lower median).
fn median_level(data: &[u8]) -> f64 {
    let mut hist = [0usize; LUMA_LEVELS];
    for &v in data {
        hist[usize::from(v)] += 1;
    }
    let half = data.len().div_ceil(2);
    let mut seen = 0usize;
    for (level, count) in hist.iter().enumerate() {
        seen += count;
        if seen >= half {
            return level as f64;
        }
    }
    0.0
}
