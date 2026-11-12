//! Is a photographed page sharp enough? (issue #296, spec S10 section 3)
//!
//! Right after a photo is taken, the app asks whether the page in it is blurred, so that it can
//! say "Blurry, taking it again" before the person moves on. The score is the strength of the
//! page's fine edges, divided by the page's own contrast: a dim photo of a sharp page scores the
//! same as a bright one, and a mostly blank page with one line of text is not penalised for
//! having little detail overall. Only the pixels inside the page's quad are measured, so a blurred
//! table around a sharp page does not make the page look blurred.
//!
//! Steps: average the photo to half size (2 × 2 blocks), keep the pixels inside the quad shrunk
//! to 95 % of its size about its centre, take the absolute response of a 3 × 3 Laplacian at each
//! of them, average the largest 10 % of those responses, and divide by the spread of the page's
//! luminance (its 95th minus its 5th percentile, never less than `MIN_RANGE`). Histograms stand in
//! for sorting, so the work is linear in the number of pixels.

use crate::{Luma, Quad};
use pdfcraft_geom::perspective::Point;

/// The verdict for one photo of a page.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Sharpness {
    /// Edge strength over contrast. `0.0` when the page could not be measured.
    pub score: f32,
    /// True when `score` is below `BLURRED_BELOW` and the page has enough detail to judge. Never
    /// true for a page that could not be measured.
    pub blurred: bool,
}

/// The result for a photo whose page could not be measured (no area, outside the photo, bad
/// numbers, or too small). Such a page is never called blurred.
const UNMEASURED: Sharpness = Sharpness { score: 0.0, blurred: false };

/// Below this score a page is called blurred. Set on drawn pages only, not on real photos: sharp
/// drawn pages scored 1.49 to 1.93, Gaussian blur of sigma 1.5 scored 0.61 to 0.83 and of sigma
/// 2.5 or more 0.15 to 0.48 (the measured ranges, printed by the tests). 1.0 lies in the empty
/// gap between the sharp pages and the blurred ones.
pub const BLURRED_BELOW: f32 = 1.0;

/// The measured region is the quad shrunk to this share of its size about its centre. The 5 %
/// margin keeps the page's own edge, where the paper meets the table, out of the measure.
const SHRINK: f64 = 0.95;
/// The share of the strongest responses that the score averages, in percent.
const TOP_PERCENT: u64 = 10;
/// The low and high percentiles of the page's luminance that set its contrast, in percent.
const LOW_PERCENT: u64 = 5;
const HIGH_PERCENT: u64 = 95;
/// The smallest contrast the score divides by, in grey levels. Without a floor a page with no
/// tone spread (blank paper) would divide by zero or by noise.
const MIN_RANGE: f64 = 20.0;
/// A top-10 % mean below this many grey levels means the page has no real detail (blank paper),
/// so it is not called blurred whatever its score.
const BLANK_DETAIL: f64 = 2.0;
/// A quad with less area than this, in square photo pixels, cannot be measured.
const MIN_AREA: f64 = 1.0;
/// The half-size image needs at least this many pixels on each side for a 3 × 3 kernel.
const MIN_HALF_SIDE: usize = 3;
/// The largest absolute 4-neighbour Laplacian of 8-bit values: 4 × 255.
const LAPLACIAN_MAX: usize = 4 * 255;

/// Half-size luminance: each 2 × 2 block of the photo averaged to one value, row-major.
struct Half {
    width: usize,
    height: usize,
    data: Vec<u8>,
}

impl Half {
    /// The value at (`x`, `y`), or 0 outside the image (never reached by the callers).
    fn at(&self, x: usize, y: usize) -> i32 {
        self.data.get(y * self.width + x).map_or(0, |&v| i32::from(v))
    }
}

/// The histograms of the measured pixels.
struct Histograms {
    /// Count of pixels by absolute Laplacian response, `0..=LAPLACIAN_MAX`.
    detail: Vec<u64>,
    /// Count of pixels by luminance.
    tone: [u64; 256],
    /// Number of measured pixels.
    count: u64,
}

/// Scores how sharp the page inside `quad` is in `luma`, and says whether it is blurred.
///
/// `luma` is the photo's luminance (one byte per pixel) and `quad` the page's corners in photo
/// pixels. Returns `Sharpness { score: 0.0, blurred: false }` when the page cannot be measured:
/// a quad with no area, corners that are not finite or lie outside the photo, a photo whose
/// byte count does not match its size, or a photo too small to filter.
pub fn sharpness(luma: &Luma, quad: &Quad) -> Sharpness {
    measure(luma, quad).unwrap_or(UNMEASURED)
}

/// The measure itself; `None` where the page cannot be measured.
fn measure(luma: &Luma, quad: &Quad) -> Option<Sharpness> {
    let corners = measured_corners(quad, luma.width, luma.height)?;
    let half = half_size(luma)?;
    let half_corners = corners.map(|[x, y]| [x / 2.0, y / 2.0]);
    let hist = histograms(&half, &half_corners);
    let detail = top_mean(&hist.detail, hist.count)?;
    let low = percentile(&hist.tone, hist.count, LOW_PERCENT);
    let high = percentile(&hist.tone, hist.count, HIGH_PERCENT);
    let range = (f64::from(high) - f64::from(low)).max(MIN_RANGE);
    let score = detail / range;
    let blurred = detail >= BLANK_DETAIL && score < f64::from(BLURRED_BELOW);
    Some(Sharpness { score: score as f32, blurred })
}

/// The quad shrunk about its centre, in photo pixels. `None` when the quad has no usable area,
/// has a corner that is not finite, or the shrunk quad reaches outside the photo.
fn measured_corners(quad: &Quad, width: u32, height: u32) -> Option<[Point; 4]> {
    let q = quad.0;
    if q.iter().flatten().any(|v| !v.is_finite()) || quad.area() < MIN_AREA {
        return None;
    }
    let c = centre(&q);
    let shrunk = q.map(|[x, y]| [c[0] + SHRINK * (x - c[0]), c[1] + SHRINK * (y - c[1])]);
    let (w, h) = (f64::from(width), f64::from(height));
    let inside_photo = shrunk.iter().all(|&[x, y]| (0.0..=w).contains(&x) && (0.0..=h).contains(&y));
    inside_photo.then_some(shrunk)
}

/// The mean of four points.
fn centre(points: &[Point; 4]) -> Point {
    let sum = points.iter().fold([0.0, 0.0], |[ax, ay], &[x, y]| [ax + x, ay + y]);
    [sum[0] / 4.0, sum[1] / 4.0]
}

/// The photo at half size. `None` when the byte count does not match the size, or the half-size
/// image would be too small.
fn half_size(luma: &Luma) -> Option<Half> {
    let w = usize::try_from(luma.width).ok()?;
    let h = usize::try_from(luma.height).ok()?;
    if w.checked_mul(h)? != luma.data.len() {
        return None;
    }
    let (hw, hh) = (w / 2, h / 2);
    if hw < MIN_HALF_SIDE || hh < MIN_HALF_SIDE {
        return None;
    }
    let at = |k: usize| luma.data.get(k).map(|&v| u32::from(v));
    let mut data = Vec::with_capacity(hw * hh);
    for y in 0..hh {
        for x in 0..hw {
            let i = 2 * y * w + 2 * x;
            let sum = at(i)? + at(i + 1)? + at(i + w)? + at(i + w + 1)?;
            data.push(((sum + 2) / 4) as u8);
        }
    }
    Some(Half { width: hw, height: hh, data })
}

/// Fills the histograms for the pixels of the half-size image whose centres lie inside the
/// region. Pixels on the image border are left out, since the 3 × 3 kernel needs neighbours.
fn histograms(half: &Half, corners: &[Point; 4]) -> Histograms {
    let mut hist = Histograms { detail: vec![0; LAPLACIAN_MAX + 1], tone: [0; 256], count: 0 };
    for y in 1..half.height.saturating_sub(1) {
        for x in 1..half.width.saturating_sub(1) {
            if !inside(corners, [x as f64 + 0.5, y as f64 + 0.5]) {
                continue;
            }
            hist.count += 1;
            let c = half.at(x, y);
            let neighbours = half.at(x - 1, y) + half.at(x + 1, y) + half.at(x, y - 1) + half.at(x, y + 1);
            let magnitude = (4 * c - neighbours).unsigned_abs() as usize;
            if let Some(bin) = hist.detail.get_mut(magnitude) {
                *bin += 1;
            }
            if let Some(bin) = hist.tone.get_mut(c as usize) {
                *bin += 1;
            }
        }
    }
    hist
}

/// Whether `p` lies on the same side of all four edges of the convex quad `corners` (its
/// boundary counts as inside). Works for either winding.
fn inside(corners: &[Point; 4], p: Point) -> bool {
    let edges = [(corners[0], corners[1]), (corners[1], corners[2]), (corners[2], corners[3]), (corners[3], corners[0])];
    let mut positive = false;
    let mut negative = false;
    for (a, b) in edges {
        let cross = (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0]);
        positive |= cross > 0.0;
        negative |= cross < 0.0;
    }
    !(positive && negative)
}

/// The mean of the largest `TOP_PERCENT` of the responses, read from their histogram. `None`
/// when no pixel was measured.
fn top_mean(detail: &[u64], count: u64) -> Option<f64> {
    if count == 0 {
        return None;
    }
    let wanted = (count * TOP_PERCENT).div_ceil(100).max(1);
    let mut left = wanted;
    let mut sum: u64 = 0;
    for (value, &n) in detail.iter().enumerate().rev() {
        if left == 0 {
            break;
        }
        let take = n.min(left);
        sum += take * value as u64;
        left -= take;
    }
    Some(sum as f64 / wanted as f64)
}

/// The grey level below which `percent` of the measured pixels fall (the smallest level whose
/// cumulative count reaches that share).
fn percentile(tone: &[u64; 256], count: u64, percent: u64) -> u8 {
    let target = (count * percent).div_ceil(100).max(1);
    let mut seen: u64 = 0;
    for (value, &n) in tone.iter().enumerate() {
        seen += n;
        if seen >= target {
            return value as u8;
        }
    }
    u8::MAX
}
