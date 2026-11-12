//! The page's true proportions from its four corners in the photo (#293).
//!
//! The longest sides give the right shape only when the photo is taken straight on: with the
//! phone tilted, the far side is foreshortened and an A4 page comes out too wide or too tall.
//! The four image corners of a rectangle determine the camera's focal length and the rectangle's
//! aspect ratio in closed form (Zhang and He, "Whiteboard scanning and image enhancement",
//! Microsoft Research), assuming square pixels and the principal point at the photo's centre.

use crate::warp::MAX_OUTPUT_PIXELS;
use crate::{Quad, ScanError};

/// Where the aspect ratio came from.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum AspectSource {
    /// The corners and a focal length the camera reported.
    KnownFocal,
    /// The corners alone: they fix the focal length well enough.
    EstimatedFocal,
    /// The corners and a typical phone camera's focal length, because the corners say little
    /// about it (the page is tilted about one axis only, or hardly at all).
    TypicalFocal,
    /// The mean lengths of opposite sides (corners the formula cannot use).
    SideLengths,
}

/// A paper size the page was matched to.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Paper {
    A4,
    /// Never detected (it has A4's shape); a person can choose it.
    A5,
    Letter,
    Legal,
    /// ISO/IEC 7810 ID-1: bank cards, most identity cards.
    IdCard,
    Square,
    /// No standard size: the page keeps the proportions that were measured.
    Custom,
}

impl Paper {
    /// Long side over short side, for the sizes that have one.
    fn ratio(self) -> Option<f64> {
        match self {
            Paper::A4 | Paper::A5 => Some(std::f64::consts::SQRT_2),
            Paper::Square => Some(1.0),
            Paper::Custom => None,
            other => other.size_mm().map(|(short, long)| long / short),
        }
    }

    /// (short side, long side) in millimetres, for the papers with a fixed size.
    pub fn size_mm(self) -> Option<(f64, f64)> {
        match self {
            Paper::A4 => Some((210.0, 297.0)),
            Paper::A5 => Some((148.0, 210.0)),
            Paper::Letter => Some((215.9, 279.4)),
            Paper::Legal => Some((215.9, 355.6)),
            Paper::IdCard => Some((53.98, 85.6)),
            Paper::Square | Paper::Custom => None,
        }
    }
}

/// A measured ratio within this share of a standard one is taken to be that paper.
const SNAP: f64 = 0.03;
/// Without a fixed size, a page's long side is taken to be this long (A4's).
const LONG_SIDE_MM: f64 = 297.0;
/// The focal lengths (in units of the photo's longer side) the corners may be taken to imply:
/// an ultra-wide camera to a 5× one.
const FOCAL_RANGE: std::ops::RangeInclusive<f64> = 0.35..=4.0;
/// The focal lengths a camera may report, zoom included (wider: what a camera says about itself
/// needs less doubt than what four corners imply).
const KNOWN_FOCAL_RANGE: std::ops::RangeInclusive<f64> = 0.2..=40.0;
/// A known and an estimated focal length further apart than this disagree.
const FOCAL_MISMATCH: f64 = 0.25;
/// A phone's main camera, in units of the photo's longer side: 26 mm equivalent on a 4:3 photo.
const TYPICAL_FOCAL: f64 = 0.75;
/// How far main cameras spread around [`TYPICAL_FOCAL`] (a standard deviation of the logarithm:
/// 22 to 35 mm equivalent is about one either way).
const TYPICAL_FOCAL_SPREAD: f64 = 0.25;
/// The error expected in a corner's position, as a share of the photo's longer side (3 pixels in
/// 4000). It decides how far the focal length the corners imply is trusted.
const CORNER_NOISE: f64 = 0.00075;
/// A focal length from the corners with a spread (of its logarithm) under this is firm enough
/// to be compared with the camera's.
const FIRM_ESTIMATE: f64 = 0.1;
/// The diagonal of a 35 mm frame, which "35 mm equivalent" focal lengths refer to.
const FRAME_35MM_DIAGONAL: f64 = 43.27;

/// The focal length in pixels of an upright `width` × `height` photo from its 35 mm equivalent,
/// as a camera writes it into the file. `None` when the photo's shape is not a camera's (it was
/// cropped, so its centre and diagonal no longer describe the lens).
pub fn focal_px(focal_35mm: f32, width: u32, height: u32) -> Option<f64> {
    let (w, h) = (f64::from(width), f64::from(height));
    let shape = w.max(h) / w.min(h).max(1.0);
    let camera_shape = [4.0 / 3.0, 16.0 / 9.0, 3.0 / 2.0, 1.0].iter().any(|s| (shape / s - 1.0).abs() < 0.02);
    let focal = f64::from(focal_35mm);
    (camera_shape && focal.is_finite() && (8.0..=400.0).contains(&focal)).then(|| focal * w.hypot(h) / FRAME_35MM_DIAGONAL)
}

/// What the camera said about its lens.
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct Focal {
    /// The focal length in pixels of the upright photo.
    pub px: Option<f64>,
    /// The digital zoom the camera reported, when it is above 1. Cameras differ on whether the
    /// focal length they write already includes it: the corners decide where they can, and
    /// where they cannot the two fields are taken at their word (a lens, and a zoom on top).
    pub zoom: Option<f64>,
}

impl From<Option<f64>> for Focal {
    fn from(px: Option<f64>) -> Self {
        Focal { px, zoom: None }
    }
}

impl Focal {
    /// From a 35 mm equivalent and a digital zoom ratio, for an upright `width` × `height` photo.
    pub fn from_35mm(focal_35mm: Option<f32>, digital_zoom: Option<f32>, width: u32, height: u32) -> Focal {
        let px = focal_35mm.and_then(|f| focal_px(f, width, height));
        let zoom = digital_zoom.map(f64::from).filter(|z| z.is_finite() && (1.01..=20.0).contains(z));
        Focal { px, zoom: px.and(zoom) }
    }

    /// The focal length to take as known, in units of the photo's longer side `unit`: with the
    /// zoom or without it, whichever the corners' own firm estimate is nearer to.
    fn known(self, unit: f64, firm: Option<f64>) -> Option<f64> {
        let plain = self.px.map(|f| f / unit).filter(|f| f.is_finite())?;
        let zoomed = self.zoom.map_or(plain, |z| plain * z);
        let nearer = |f: f64| if (f / zoomed).ln().abs() <= (f / plain).ln().abs() { zoomed } else { plain };
        Some(firm.map_or(zoomed, nearer)).filter(|f| KNOWN_FOCAL_RANGE.contains(f))
    }
}

type V3 = [f64; 3];

fn cross(a: V3, b: V3) -> V3 {
    [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]
}

fn dot(a: V3, b: V3) -> f64 {
    a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}

/// The page's width and height as vectors in space, each up to the focal length: a side of the
/// page is `(x, y, f·z)` of what is returned. `m` is top-left, top-right, bottom-left,
/// bottom-right, with the photo's centre at the origin. `None` for corners no rectangle gives.
fn side_vectors(m: &[V3; 4]) -> Option<(V3, V3)> {
    let [m1, m2, m3, m4] = *m;
    let k2 = dot(cross(m1, m4), m3) / dot(cross(m2, m4), m3);
    let k3 = dot(cross(m1, m4), m2) / dot(cross(m3, m4), m2);
    let n2: V3 = std::array::from_fn(|i| k2 * m2[i] - m1[i]);
    let n3: V3 = std::array::from_fn(|i| k3 * m3[i] - m1[i]);
    (k2.is_finite() && k3.is_finite() && k2 > 0.0 && k3 > 0.0).then_some((n2, n3))
}

/// The focal length at which the two sides are at right angles, when there is one.
fn focal_of(n2: V3, n3: V3) -> Option<f64> {
    let f2 = -(n2[0] * n3[0] + n2[1] * n3[1]) / (n2[2] * n3[2]);
    (f2.is_finite() && f2 > 0.0).then(|| f2.sqrt())
}

/// Width over height at focal length `f`.
fn ratio_at(n2: V3, n3: V3, f: f64) -> f64 {
    ((n2[0] * n2[0] + n2[1] * n2[1] + f * f * n2[2] * n2[2]) / (n3[0] * n3[0] + n3[1] * n3[1] + f * f * n3[2] * n3[2])).sqrt()
}

/// The focal length the corners imply and how uncertain it is: the logarithm's variance when
/// every corner coordinate is off by [`CORNER_NOISE`]. With one pair of sides parallel in the
/// photo the corners say nothing about the focal length, and this shows as a huge variance.
fn focal_estimate(m: &[V3; 4]) -> Option<(f64, f64)> {
    let focal = |m: &[V3; 4]| side_vectors(m).and_then(|(n2, n3)| focal_of(n2, n3));
    let f = focal(m)?;
    let mut variance = 0.0;
    for corner in 0..4 {
        for axis in 0..2 {
            let mut moved = *m;
            moved[corner][axis] += CORNER_NOISE;
            variance += (focal(&moved)? / f).ln().powi(2);
        }
    }
    (variance.is_finite()).then_some((f, variance))
}

/// The focal length to use (in units of the longer side `unit`), where it came from, the
/// camera's own when it gave one, and the corners' estimate when it is firm.
fn focal(camera: Focal, unit: f64, estimate: Option<(f64, f64)>) -> (f64, AspectSource, Option<f64>, Option<f64>) {
    let firm = estimate.filter(|&(_, variance)| variance.sqrt() <= FIRM_ESTIMATE).map(|(f, _)| f);
    let known = camera.known(unit, firm);
    if let Some(f) = known {
        return (f, AspectSource::KnownFocal, known, firm);
    }
    let Some((f, variance)) = estimate else { return (TYPICAL_FOCAL, AspectSource::TypicalFocal, None, None) };
    // the corners' estimate and the typical camera, each weighted by how sure it is
    let (corners, typical) = (1.0 / variance.max(1e-12), 1.0 / (TYPICAL_FOCAL_SPREAD * TYPICAL_FOCAL_SPREAD));
    let both = ((f.ln() * corners + TYPICAL_FOCAL.ln() * typical) / (corners + typical)).exp();
    let source = if corners >= typical { AspectSource::EstimatedFocal } else { AspectSource::TypicalFocal };
    (both.clamp(*FOCAL_RANGE.start(), *FOCAL_RANGE.end()), source, None, firm)
}

/// The paper whose shape is within [`SNAP`] of `ratio` (width over height), closest first.
fn snap(ratio: f64) -> Paper {
    let long_short = ratio.max(1.0 / ratio);
    [Paper::A4, Paper::Letter, Paper::Legal, Paper::IdCard, Paper::Square]
        .into_iter()
        .filter_map(|p| p.ratio().map(|r| (p, (long_short / r - 1.0).abs())))
        .filter(|&(_, off)| off <= SNAP)
        .min_by(|a, b| a.1.total_cmp(&b.1))
        .map_or(Paper::Custom, |(p, _)| p)
}

/// A page's shape, and the size it has in the photo.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Proportions {
    /// Width over height (a standard paper's exact ratio when [`Proportions::paper`] is one).
    pub ratio: f64,
    pub source: AspectSource,
    pub paper: Paper,
    /// The focal length the corners imply, in pixels, when they fix it firmly.
    pub estimated_focal_px: Option<f64>,
    /// The camera's focal length and the one the corners imply differ by more than a quarter:
    /// one of the assumptions (uncropped photo, flat page, right corners) does not hold.
    pub focal_mismatch: bool,
    /// The page's size in photo pixels at these proportions: the nearer (larger) side keeps its
    /// full resolution.
    pub width: f64,
    pub height: f64,
}

/// The proportions of the page inside `quad` in a `photo_w` × `photo_h` photo. `camera` is what
/// the camera said about its lens (`None` for nothing); `snap_paper` matches the result to
/// standard papers.
pub fn proportions(quad: &Quad, photo_w: u32, photo_h: u32, camera: impl Into<Focal>, snap_paper: bool) -> Proportions {
    shape(quad, photo_w, photo_h, camera.into(), snap_paper, true)
}

/// The page's proportions from the lengths of its sides in the photo alone, as before #293: right
/// for a page seen square on, too short along the direction it leans away for any other. A
/// testing choice (#288), to tell a page drawn with the wrong proportions by [`proportions`]
/// from one whose corners are wrong.
pub fn proportions_by_sides(quad: &Quad, photo_w: u32, photo_h: u32, snap_paper: bool) -> Proportions {
    shape(quad, photo_w, photo_h, Focal::default(), snap_paper, false)
}

fn shape(quad: &Quad, photo_w: u32, photo_h: u32, camera: Focal, snap_paper: bool, perspective: bool) -> Proportions {
    let unit = f64::from(photo_w.max(photo_h)).max(1.0);
    let (cx, cy) = (f64::from(photo_w) / 2.0, f64::from(photo_h) / 2.0);
    let m = |i: usize| -> V3 { [(quad.0[i][0] - cx) / unit, (quad.0[i][1] - cy) / unit, 1.0] };
    let [top, right, bottom, left] = quad.side_lengths();
    let by_sides = ((top + bottom) / (left + right).max(1e-9), AspectSource::SideLengths, None, None);
    // top-left, top-right, bottom-left, bottom-right: mind the order
    let corners = [m(0), m(1), m(3), m(2)];
    let measured = side_vectors(&corners).filter(|_| perspective).map(|(n2, n3)| {
        let (f, source, known, firm) = focal(camera, unit, focal_estimate(&corners));
        (ratio_at(n2, n3, f), source, known, firm)
    });
    // a ratio far from what the sides show is a formula gone wrong on bad corners, not a shape
    let (ratio, source, known, estimated) =
        measured.filter(|&(r, ..)| r.is_finite() && r > 0.0 && (r / by_sides.0).max(by_sides.0 / r) <= 3.0).unwrap_or(by_sides);
    let focal_mismatch = matches!((known, estimated), (Some(k), Some(e)) if (e / k - 1.0).abs() > FOCAL_MISMATCH);
    let paper = if snap_paper { snap(ratio) } else { Paper::Custom };
    let ratio = match paper.ratio() {
        Some(r) if ratio >= 1.0 => r,
        Some(r) => 1.0 / r,
        None => ratio,
    };
    let (w, h) = (top.max(bottom), left.max(right));
    let (width, height) = if w / h.max(1e-9) >= ratio { (w, w / ratio) } else { (h * ratio, h) };
    Proportions { ratio, source, paper, estimated_focal_px: estimated.map(|f| f * unit), focal_mismatch, width, height }
}

/// The resolution at which a page `long_px` pixels long is as long as A4 (297 mm), the size a
/// page without a standard paper is given.
pub fn dpi_without_paper(long_px: f64) -> u16 {
    (long_px * 25.4 / LONG_SIDE_MM).round().clamp(72.0, 1200.0) as u16
}

impl Proportions {
    /// The page's size in whole pixels and its resolution in dots per inch, with the size in the
    /// photo enlarged `scale` times (1 for none) and limited to [`MAX_OUTPUT_PIXELS`]. A standard
    /// paper gets exactly its size at that resolution, so the PDF page is exactly A4 or Letter.
    pub fn output(&self, scale: f64) -> Result<(u32, u32, u16), ScanError> {
        let scale = if scale.is_finite() { scale.max(1e-6) } else { 1.0 };
        let (w, h) = (self.width * scale, self.height * scale);
        if !(w.is_finite() && h.is_finite()) || w <= 0.0 || h <= 0.0 {
            return Err(ScanError::BadCorners);
        }
        let k = (MAX_OUTPUT_PIXELS / (w * h)).sqrt().min(1.0);
        let (w, h) = (w * k, h * k);
        let long_mm = self.paper.size_mm().map_or(LONG_SIDE_MM, |(_, long)| long);
        let wanted = w.max(h) * 25.4 / long_mm;
        let dpi = wanted.round().clamp(72.0, 1200.0);
        let fixed = self.paper.size_mm().filter(|_| (72.0..=1200.0).contains(&wanted));
        let Some((short_mm, long_mm)) = fixed else {
            // a side within a thousandth of a pixel of a whole number keeps it (a frontal page is
            // then copied pixel for pixel)
            let side = |v: f64| (v + 1e-3).floor().max(1.0) as u32;
            return Ok((side(w), side(h), dpi as u16));
        };
        // the largest whole resolution at which the paper's exact size fits the pixel limit
        let mut dpi = dpi;
        let size = |dpi: f64| ((dpi * long_mm / 25.4).round(), (dpi * short_mm / 25.4).round());
        while dpi > 72.0 && size(dpi).0 * size(dpi).1 > MAX_OUTPUT_PIXELS {
            dpi -= 1.0;
        }
        let (long, short) = size(dpi);
        let (ow, oh) = if w >= h { (long, short) } else { (short, long) };
        Ok((ow.max(1.0) as u32, oh.max(1.0) as u32, dpi as u16))
    }
}
