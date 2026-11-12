//! Two pages from one photo of an open book (issue #303, spec S17 part A).
//!
//! `find_gutter` looks for the dark fold between the two pages in a spread that has already been
//! straightened by its four corners. `split` cuts the photographed outline along that gutter into
//! a left and a right page. Both are pure: the same input gives the same output.

use std::ops::Range;

use crate::{Luma, Quad};
use pdfcraft_geom::perspective::{Homography, Point};

/// Share of the spread's width over which a column profile is averaged (S17 A step 2: 1 %).
const SMOOTH_SHARE: f64 = 0.01;
/// Left edge of the window in which the gutter is searched, as a share of the width (the middle
/// 30 % runs from 0.35 to 0.65).
const SEARCH_LO: f64 = 0.35;
/// Right edge of the gutter search window, as a share of the width.
const SEARCH_HI: f64 = 0.65;
/// Least darkness of the valley, relative to the mean of the two page halves, that counts as a
/// gutter (S17 A step 2: 6 %).
const MIN_DARKNESS: f64 = 0.06;
/// Darkness at which the confidence reaches 1.0 (30 % darker).
const FULL_DARKNESS: f64 = 0.30;
/// Number of horizontal bands in which the valley is refined again (S17 A step 2: 8).
const BANDS: usize = 8;
/// Bands whose positions fit the line worst and are dropped before the final fit (S17 A: 2).
const BANDS_DROPPED: usize = 2;
/// Half-width of the search in each band around the valley found for the whole spread, as a
/// share of the width. It must cover a spine tilted by several degrees.
const BAND_SEARCH: f64 = 0.07;
/// Share of the width left out on each side of the gutter, so the two pages do not overlap (0.5 %).
const OVERLAP: f64 = 0.005;
/// Width / height of two A-series pages side by side: 2 × (1/√2) = √2.
const TWO_A_RATIO: f64 = std::f64::consts::SQRT_2;
/// Width / height of two US Letter pages side by side: 2 × (8.5 / 11).
const TWO_LETTER_RATIO: f64 = 2.0 * 8.5 / 11.0;
/// Relative distance from one of the two ratios within which the spread counts as two pages (8 %).
const RATIO_TOLERANCE: f64 = 0.08;
/// Smallest width in columns for which a gutter is searched.
const MIN_WIDTH: usize = 8;

/// Where the gutter runs through a straightened spread, and how sure we are of it.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Gutter {
    /// Where the gutter crosses the spread's top edge, as a share (0–1) of its width.
    pub top: f64,
    /// Where the gutter crosses the spread's bottom edge, as a share (0–1) of its width.
    pub bottom: f64,
    /// 0–1: how much darker the valley is than the pages beside it, scaled so that 6 % darker
    /// gives 0.0 and 30 % darker or more gives 1.0. 0.0 when the centre line was used.
    pub confidence: f32,
    /// A valley was found (false: the centre line, because the spread has the shape of two pages).
    pub found: bool,
}

/// The two halves of a photographed spread, as quads in photo coordinates.
#[derive(Clone, Debug, PartialEq)]
pub struct Spread {
    /// The left page: top left, gutter top, gutter bottom, bottom left of the photo outline.
    pub left: Quad,
    /// The right page: gutter top, top right, bottom right, gutter bottom of the photo outline.
    pub right: Quad,
    /// The confidence of the gutter this split was cut along (see `Gutter::confidence`).
    pub gutter_confidence: f32,
}

/// Finds the gutter of a spread. `spread` is the whole spread already straightened by its four
/// corners, about 800 pixels on its longer side. The gutter is the deepest valley of the column
/// profile in the middle 30 % of the width, when it is at least 6 % darker than the two page
/// halves; its position is then refined in eight horizontal bands and a straight line is fitted
/// through the positions, so a tilted spine is followed. With no valley, a spread whose width to
/// height ratio is that of two A-series or two Letter pages gets the centre line. `None` when there
/// is no valley and the shape is not that of two pages, or when `spread` is not a valid picture.
pub fn find_gutter(spread: &Luma) -> Option<Gutter> {
    let (w, h) = checked_dims(spread)?;
    let profile = smooth(&column_means(spread, 0..h, w), smooth_radius(w));
    match find_valley(&profile, w) {
        Some(valley) => fitted_gutter(spread, (w, h), valley),
        None => centre_line(w, h),
    }
}

/// Cuts the spread's outline in the photo (`quad`: top left, top right, bottom right, bottom left)
/// along the gutter. Each side of the gutter gives up 0.5 % of the spread's width. `None` when a
/// half would have no area: a gutter at 0 or 1 (or within 0.5 % of an edge), a non-finite gutter,
/// or a quad whose halves are not convex.
pub fn split(quad: &Quad, gutter: &Gutter) -> Option<Spread> {
    let (top, bottom) = (gutter.top, gutter.bottom);
    // The left half ends at s - OVERLAP and the right half starts at s + OVERLAP, so both must lie
    // strictly inside the unit width.
    let room = |s: f64| s.is_finite() && s - OVERLAP > 0.0 && s + OVERLAP < 1.0;
    if !(room(top) && room(bottom)) {
        return None;
    }
    // Map the unit square (the straightened spread) onto the photo's outline; points on the
    // spread's top and bottom edges then stay on the outline's top and bottom sides.
    let unit = [[0.0, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 1.0]];
    let map = Homography::from_points(unit, quad.0)?;
    let at = |u: f64, v: f64| map.apply([u, v]);
    let left = [at(0.0, 0.0)?, at(top - OVERLAP, 0.0)?, at(bottom - OVERLAP, 1.0)?, at(0.0, 1.0)?];
    let right = [at(top + OVERLAP, 0.0)?, at(1.0, 0.0)?, at(1.0, 1.0)?, at(bottom + OVERLAP, 1.0)?];
    if !(is_convex(&left) && is_convex(&right)) {
        return None;
    }
    Some(Spread { left: Quad(left), right: Quad(right), gutter_confidence: gutter.confidence })
}

/// The deepest valley of a column profile that qualifies as a gutter.
#[derive(Clone, Copy, Debug)]
struct Valley {
    /// Position of the valley in columns; column `i` has its centre at `i`.
    x: f64,
    /// 1 − (valley value / mean of the two page halves): 0.30 means 30 % darker.
    darkness: f64,
}

/// Width and height in pixels, or `None` when the picture is too small or its data does not
/// match its size.
fn checked_dims(spread: &Luma) -> Option<(usize, usize)> {
    let w = spread.width as usize;
    let h = spread.height as usize;
    let pixels = w.checked_mul(h)?;
    (w >= MIN_WIDTH && h >= BANDS && pixels == spread.data.len()).then_some((w, h))
}

/// Half-width of the smoothing window in columns: half of the 1 % share, at least one column.
fn smooth_radius(w: usize) -> usize {
    ((SMOOTH_SHARE * w as f64) / 2.0).round().max(1.0) as usize
}

/// Mean luminance of each column over the rows in `rows`, for a picture `w` columns wide.
fn column_means(spread: &Luma, rows: Range<usize>, w: usize) -> Vec<f64> {
    let n = rows.len().max(1) as f64;
    let mut sums = vec![0u64; w];
    for row in rows {
        if let Some(line) = spread.data.get(row * w..(row + 1) * w) {
            for (sum, &v) in sums.iter_mut().zip(line) {
                *sum += u64::from(v);
            }
        }
    }
    sums.into_iter().map(|s| s as f64 / n).collect()
}

/// Box average of `profile` over `radius` samples on each side, with the window cut at the ends.
fn smooth(profile: &[f64], radius: usize) -> Vec<f64> {
    let n = profile.len();
    (0..n)
        .map(|i| {
            let lo = i.saturating_sub(radius);
            let hi = (i + radius + 1).min(n);
            profile[lo..hi].iter().sum::<f64>() / (hi - lo) as f64
        })
        .collect()
}

/// Offset in (−0.5, 0.5) of the lowest point of the parabola through three equally spaced
/// samples, so the valley can be placed between two columns.
fn parabola_offset(left: f64, mid: f64, right: f64) -> f64 {
    let curvature = left - 2.0 * mid + right;
    if curvature <= 0.0 {
        return 0.0;
    }
    (0.5 * (left - right) / curvature).clamp(-0.5, 0.5)
}

/// The deepest column of `profile` between the indices `lo` and `hi` (both inclusive, clipped to
/// the profile), placed to a fraction of a column. `None` for an empty range.
fn valley_at(profile: &[f64], lo: usize, hi: usize) -> Option<f64> {
    let last = profile.len().checked_sub(1)?;
    let hi = hi.min(last);
    if lo > hi {
        return None;
    }
    let i = (lo..=hi).min_by(|&a, &b| profile[a].total_cmp(&profile[b]))?;
    let offset = if i > 0 && i < last { parabola_offset(profile[i - 1], profile[i], profile[i + 1]) } else { 0.0 };
    Some(i as f64 + offset)
}

/// The gutter valley of the whole-spread column profile, if it is dark enough.
fn find_valley(profile: &[f64], w: usize) -> Option<Valley> {
    let lo = (SEARCH_LO * w as f64 - 0.5).ceil().max(0.0) as usize;
    let hi = (SEARCH_HI * w as f64 - 0.5).floor().max(0.0) as usize;
    let x = valley_at(profile, lo, hi)?;
    let c = x.round() as usize;
    let (left, right) = (profile.get(..c)?, profile.get(c + 1..)?);
    if left.is_empty() || right.is_empty() {
        return None;
    }
    let mean = |s: &[f64]| s.iter().sum::<f64>() / s.len() as f64;
    let pages = (mean(left) + mean(right)) / 2.0;
    if pages <= 0.0 {
        return None;
    }
    let darkness = 1.0 - profile.get(c)? / pages;
    (darkness >= MIN_DARKNESS).then_some(Valley { x, darkness })
}

/// Maps the darkness of a valley to a confidence between 0 and 1.
fn confidence(darkness: f64) -> f32 {
    ((darkness - MIN_DARKNESS) / (FULL_DARKNESS - MIN_DARKNESS)).clamp(0.0, 1.0) as f32
}

/// The gutter from a found valley: positions refined per band, then a fitted straight line.
fn fitted_gutter(spread: &Luma, dims: (usize, usize), valley: Valley) -> Option<Gutter> {
    let points = band_points(spread, dims, valley.x)?;
    let (a, m) = robust_line(&points)?;
    Some(Gutter { top: a.clamp(0.0, 1.0), bottom: (a + m).clamp(0.0, 1.0), confidence: confidence(valley.darkness), found: true })
}

/// For each horizontal band, the valley position as a share of the width, with the band's centre
/// height as a share of the spread's height: `(t, share)` pairs.
fn band_points(spread: &Luma, dims: (usize, usize), centre: f64) -> Option<Vec<(f64, f64)>> {
    let (w, h) = dims;
    let reach = BAND_SEARCH * w as f64;
    let lo = (centre - reach).floor().max(0.0) as usize;
    let hi = (centre + reach).ceil().max(0.0) as usize;
    (0..BANDS)
        .map(|b| {
            let (r0, r1) = (b * h / BANDS, (b + 1) * h / BANDS);
            let profile = smooth(&column_means(spread, r0..r1, w), smooth_radius(w));
            let x = valley_at(&profile, lo, hi)?;
            let t = (r0 + r1) as f64 / (2.0 * h as f64);
            Some((t, (x + 0.5) / w as f64))
        })
        .collect()
}

/// Least-squares line `share = a + m · t` through `points`: `(a, m)`. `None` with fewer than two
/// points or when all `t` are equal.
fn fit_line(points: &[(f64, f64)]) -> Option<(f64, f64)> {
    if points.len() < 2 {
        return None;
    }
    let n = points.len() as f64;
    let mean_t = points.iter().map(|p| p.0).sum::<f64>() / n;
    let mean_s = points.iter().map(|p| p.1).sum::<f64>() / n;
    let spread_tt: f64 = points.iter().map(|p| (p.0 - mean_t).powi(2)).sum();
    if spread_tt < 1e-12 {
        return None;
    }
    let spread_ts: f64 = points.iter().map(|p| (p.0 - mean_t) * (p.1 - mean_s)).sum();
    let m = spread_ts / spread_tt;
    Some((mean_s - m * mean_t, m))
}

/// Fits a line through the band positions, drops the `BANDS_DROPPED` positions that fit it worst,
/// and fits again. Returns `(a, m)` as in `fit_line`.
fn robust_line(points: &[(f64, f64)]) -> Option<(f64, f64)> {
    let (a, m) = fit_line(points)?;
    let mut by_residual: Vec<(f64, (f64, f64))> = points.iter().map(|&(t, s)| ((s - (a + m * t)).abs(), (t, s))).collect();
    by_residual.sort_by(|x, y| x.0.total_cmp(&y.0));
    let keep = points.len().saturating_sub(BANDS_DROPPED);
    let kept: Vec<(f64, f64)> = by_residual.iter().take(keep).map(|p| p.1).collect();
    fit_line(&kept)
}

/// The centre line of a spread whose width to height ratio is that of two pages side by side
/// (within 8 % of two A-series or two Letter pages). `None` for any other shape.
fn centre_line(w: usize, h: usize) -> Option<Gutter> {
    let ratio = w as f64 / h as f64;
    let two_pages = [TWO_A_RATIO, TWO_LETTER_RATIO].iter().any(|&r| (ratio - r).abs() <= RATIO_TOLERANCE * r);
    two_pages.then_some(Gutter { top: 0.5, bottom: 0.5, confidence: 0.0, found: false })
}

/// True when the four points, taken in order, turn the same way at every corner with no
/// degenerate corner: a convex quadrilateral with area.
fn is_convex(q: &[Point; 4]) -> bool {
    let turn = |i: usize| {
        let (a, b, c) = (q[i], q[(i + 1) % 4], q[(i + 2) % 4]);
        (b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0])
    };
    let turns = [turn(0), turn(1), turn(2), turn(3)];
    let same_sign = turns.iter().all(|&t| t > 0.0) || turns.iter().all(|&t| t < 0.0);
    same_sign && turns.iter().all(|t| t.is_finite())
}
