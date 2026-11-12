//! How straight and level the lines of text on a straightened page are, in pixels (issues #288
//! and #302; S02 section 3 and S16 section 5). The app keeps a flattened page only when its
//! number is at least 10 % lower than the perspective-only page's, so the number has to be
//! steady: the same bytes always give the same number, and a page with too few lines gives `None`
//! rather than a guess.
//!
//! Method (S02 section 3: "the row-sum profile of the ink mask in 16 vertical strips"):
//! 1. Ink is a pixel darker than the midpoint of the page's 5th and 90th percentile grey level.
//!    A page whose two percentiles are less than 40 grey levels apart is blank.
//! 2. The page is cut into 16 vertical strips of equal width. In each strip the ink in every row
//!    is counted, the counts are smoothed over 3 rows, and each run of rows above 15 % of the
//!    strip's peak (at least 3 rows tall) is a piece of a text line. A run's height is the centre
//!    of mass of its ink.
//! 3. Runs are followed from strip to strip. Within a strip the pairs of an open line and a run
//!    are taken nearest first, and a pair joins when the two heights are within 60 % of the usual
//!    gap between lines (the median gap of that strip). A line seen in at least 8 strips is kept.
//! 4. `px` is the root mean square distance of each height from its line's mean, pooled over all
//!    kept lines. `bowed_px` is the same after each line's straight-line fit is taken out.
//!
//! Every measure is in pixels of the page as passed in; the page is never resampled.

use crate::Luma;

/// The line measures of one page. Lower means straighter, flatter lines.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Straightness {
    /// Root mean square distance of each line's height from that line's mean height, in pixels:
    /// a line that bows or leans counts.
    pub px: f64,
    /// The same after taking out each line's lean (a straight line fitted through it): only
    /// bowing counts.
    pub bowed_px: f64,
    /// Lines of text that were followed across at least half the strips.
    pub lines: u32,
}

/// Strips the page is cut into (S02 section 3: "16 vertical strips").
const STRIPS: usize = 16;
/// Rows averaged for the ink profile before looking for runs: a 3-row box filter (S02 section 3).
const SMOOTH_ROWS: usize = 3;
/// A row belongs to a run when its smoothed ink count is over this share of the strip's peak.
const RUN_PEAK_SHARE: f64 = 0.15;
/// Shortest run that counts as a piece of a text line; thinner runs are specks or rule edges.
const MIN_RUN_ROWS: usize = 3;
/// Percentile of the darkest grey levels that sets the low end of the ink threshold (5 %).
const DARK_PERCENT: u64 = 5;
/// Percentile of the lightest grey levels that sets the high end of the ink threshold (90 %).
const LIGHT_PERCENT: u64 = 90;
/// Grey-level spread between the two percentiles under which the page counts as blank.
const BLANK_SPREAD: u32 = 40;
/// A run joins a line when its height is within this share of the usual gap between lines.
const JOIN_GAP_SHARE: f64 = 0.6;
/// A line followed across at least this many strips is kept: half of the strips.
const MIN_LINE_STRIPS: usize = STRIPS / 2;
/// A line may miss this many strips in a row and still be followed. The brief does not say how
/// long a line may be lost; two strips (about an eighth of the width) is a choice made here.
const MAX_MISSED_STRIPS: usize = 2;
/// Fewest kept lines for which a measure is given; fewer is too little to tell.
const MIN_LINES: usize = 3;

/// The straightness of the text lines on `page`, or `None` when fewer than 3 lines can be
/// followed across at least half the page (a blank page, a photograph, noise). Issues #288 and
/// #302. Pure and deterministic; a page whose data does not match its size gives `None`.
pub fn line_straightness(page: &Luma) -> Option<Straightness> {
    let (w, h) = (page.width as usize, page.height as usize);
    if w < STRIPS || h == 0 || page.data.len() != w.checked_mul(h)? {
        return None;
    }
    let threshold = ink_threshold(&page.data)?;
    let strips: Vec<Vec<f64>> = (0..STRIPS).map(|i| strip_heights(page, strip_edge(i, w), strip_edge(i + 1, w), threshold)).collect();
    let page_gap = median(strips.iter().flat_map(|runs| gaps(runs)).collect());
    let kept: Vec<Vec<(usize, f64)>> =
        follow(&strips, page_gap).into_iter().filter(|t| t.points.len() >= MIN_LINE_STRIPS).map(|t| t.points).collect();
    if kept.len() < MIN_LINES {
        return None;
    }
    pool(&kept)
}

/// A text line followed across strips: its (strip, height) points and the last height and strip
/// it was matched at.
#[derive(Debug)]
struct Track {
    points: Vec<(usize, f64)>,
    last: f64,
    last_strip: usize,
}

/// The first column of strip `i` when a `width`-pixel page is cut into equal strips. Strip `i`
/// runs from `strip_edge(i)` up to `strip_edge(i + 1)`.
fn strip_edge(i: usize, width: usize) -> usize {
    i.saturating_mul(width) / STRIPS
}

/// The grey level under which a pixel is ink: the midpoint of the 5th and 90th percentile. `None`
/// when the two are less than `BLANK_SPREAD` apart (a blank page).
fn ink_threshold(data: &[u8]) -> Option<f64> {
    let mut hist = [0u64; 256];
    for &v in data {
        hist[usize::from(v)] += 1;
    }
    let total = data.len() as u64;
    let dark = percentile(&hist, total, DARK_PERCENT);
    let light = percentile(&hist, total, LIGHT_PERCENT);
    if u32::from(light.saturating_sub(dark)) < BLANK_SPREAD {
        return None;
    }
    Some((f64::from(dark) + f64::from(light)) / 2.0)
}

/// The smallest grey level at which the cumulative share of `hist` reaches `percent` of `total`.
fn percentile(hist: &[u64; 256], total: u64, percent: u64) -> u8 {
    let mut seen = 0u64;
    for (level, &n) in hist.iter().enumerate() {
        seen += n;
        if seen.saturating_mul(100) >= total.saturating_mul(percent) {
            return u8::try_from(level).unwrap_or(u8::MAX);
        }
    }
    u8::MAX
}

/// The centre heights of the text-line runs in columns `x0..x1`, top to bottom.
fn strip_heights(page: &Luma, x0: usize, x1: usize, threshold: f64) -> Vec<f64> {
    let counts = row_counts(page, x0, x1, threshold);
    let smoothed = smooth(&counts);
    let peak = smoothed.iter().copied().fold(0.0, f64::max);
    runs_above(&smoothed, RUN_PEAK_SHARE * peak).into_iter().filter_map(|run| centre_of_mass(&counts, run)).collect()
}

/// The number of ink pixels in each row, counting only columns `x0..x1`.
fn row_counts(page: &Luma, x0: usize, x1: usize, threshold: f64) -> Vec<f64> {
    let w = page.width as usize;
    page.data
        .chunks_exact(w)
        .map(|row| {
            let strip = row.get(x0..x1).unwrap_or(&[]);
            strip.iter().filter(|&&v| f64::from(v) < threshold).count() as f64
        })
        .collect()
}

/// Each row's count averaged over the rows within `SMOOTH_ROWS / 2` of it (at the page edges the
/// rows that exist are averaged).
fn smooth(counts: &[f64]) -> Vec<f64> {
    let half = SMOOTH_ROWS / 2;
    (0..counts.len())
        .map(|r| {
            let lo = r.saturating_sub(half);
            let hi = (r + half + 1).min(counts.len());
            let window = counts.get(lo..hi).unwrap_or(&[]);
            window.iter().sum::<f64>() / window.len().max(1) as f64
        })
        .collect()
}

/// The row ranges `[start, end)` where the smoothed count is over `cut`, each at least
/// `MIN_RUN_ROWS` tall.
fn runs_above(smoothed: &[f64], cut: f64) -> Vec<(usize, usize)> {
    let mut runs = Vec::new();
    let mut start = None;
    for (r, &v) in smoothed.iter().enumerate() {
        match (start, v > cut) {
            (None, true) => start = Some(r),
            (Some(a), false) => {
                runs.push((a, r));
                start = None;
            }
            _ => {}
        }
    }
    if let Some(a) = start {
        runs.push((a, smoothed.len()));
    }
    runs.retain(|&(a, b)| b - a >= MIN_RUN_ROWS);
    runs
}

/// The centre of mass, in pixels from the top, of the raw ink counts over rows `start..end`.
/// `None` when the rows hold no ink.
fn centre_of_mass(counts: &[f64], (start, end): (usize, usize)) -> Option<f64> {
    let rows = counts.get(start..end)?;
    let mass: f64 = rows.iter().sum();
    let moment: f64 = rows.iter().enumerate().map(|(i, c)| c * ((start + i) as f64 + 0.5)).sum();
    (mass > 0.0).then(|| moment / mass)
}

/// The distances between neighbouring run heights in one strip.
fn gaps(heights: &[f64]) -> Vec<f64> {
    heights.windows(2).map(|p| p[1] - p[0]).collect()
}

/// The median of `v`, or `None` when it is empty.
fn median(mut v: Vec<f64>) -> Option<f64> {
    if v.is_empty() {
        return None;
    }
    v.sort_by(f64::total_cmp);
    let mid = v.len() / 2;
    let upper = *v.get(mid)?;
    if v.len() % 2 == 1 {
        return Some(upper);
    }
    let lower = *v.get(mid - 1)?;
    Some((lower + upper) / 2.0)
}

/// Follows the runs of every strip into lines. `page_gap` (the median gap over the whole page)
/// stands in for a strip with fewer than two runs, which has no gap of its own.
fn follow(strips: &[Vec<f64>], page_gap: Option<f64>) -> Vec<Track> {
    let mut tracks: Vec<Track> = Vec::new();
    for (s, runs) in strips.iter().enumerate() {
        let reach = median(gaps(runs)).or(page_gap).map(|g| JOIN_GAP_SHARE * g);
        let joined = reach.map_or_else(Vec::new, |r| match_runs(&tracks, runs, s, r));
        let mut run_used = vec![false; runs.len()];
        for (ti, ri) in joined {
            let h = runs.get(ri).copied();
            if let (Some(t), Some(h), Some(used)) = (tracks.get_mut(ti), h, run_used.get_mut(ri)) {
                t.points.push((s, h));
                t.last = h;
                t.last_strip = s;
                *used = true;
            }
        }
        for (&h, &used) in runs.iter().zip(run_used.iter()) {
            if !used {
                tracks.push(Track { points: vec![(s, h)], last: h, last_strip: s });
            }
        }
    }
    tracks
}

/// Pairs open lines with runs one-to-one, nearest first: returns (track index, run index) pairs.
/// A line is open when it was matched at most `MAX_MISSED_STRIPS` strips ago. A pair is allowed
/// when the run lies within `reach` of the line's last height.
fn match_runs(tracks: &[Track], runs: &[f64], s: usize, reach: f64) -> Vec<(usize, usize)> {
    let mut pairs: Vec<(f64, usize, usize)> = Vec::new();
    for (ti, t) in tracks.iter().enumerate() {
        if s > t.last_strip + MAX_MISSED_STRIPS + 1 {
            continue;
        }
        for (ri, &h) in runs.iter().enumerate() {
            let d = (t.last - h).abs();
            if d <= reach {
                pairs.push((d, ti, ri));
            }
        }
    }
    pairs.sort_by(|a, b| a.0.total_cmp(&b.0).then(a.1.cmp(&b.1)).then(a.2.cmp(&b.2)));
    let mut track_used = vec![false; tracks.len()];
    let mut run_used = vec![false; runs.len()];
    let mut out = Vec::new();
    for (_, ti, ri) in pairs {
        let free = !track_used.get(ti).copied().unwrap_or(true) && !run_used.get(ri).copied().unwrap_or(true);
        if free {
            if let Some(u) = track_used.get_mut(ti) {
                *u = true;
            }
            if let Some(u) = run_used.get_mut(ri) {
                *u = true;
            }
            out.push((ti, ri));
        }
    }
    out
}

/// Pools the kept lines into the two measures. `None` when there are no points.
fn pool(lines: &[Vec<(usize, f64)>]) -> Option<Straightness> {
    let mut n = 0usize;
    let mut raw = 0.0;
    let mut bowed = 0.0;
    for line in lines {
        let (r, b) = line_residuals(line);
        raw += r;
        bowed += b;
        n += line.len();
    }
    if n == 0 {
        return None;
    }
    let n = n as f64;
    Some(Straightness { px: (raw / n).sqrt(), bowed_px: (bowed / n).sqrt(), lines: u32::try_from(lines.len()).unwrap_or(u32::MAX) })
}

/// For one line: the sum of squared deviations of its heights from their mean, and the sum of
/// squared residuals after a least-squares straight line is fitted through it (x is the strip
/// index, so the slope is in pixels per strip; residuals do not depend on that scale).
fn line_residuals(points: &[(usize, f64)]) -> (f64, f64) {
    let k = points.len() as f64;
    let mean_x = points.iter().map(|&(x, _)| x as f64).sum::<f64>() / k;
    let mean_y = points.iter().map(|&(_, y)| y).sum::<f64>() / k;
    let sxx: f64 = points.iter().map(|&(x, _)| (x as f64 - mean_x).powi(2)).sum();
    let sxy: f64 = points.iter().map(|&(x, y)| (x as f64 - mean_x) * (y - mean_y)).sum();
    let slope = if sxx > 0.0 { sxy / sxx } else { 0.0 };
    points
        .iter()
        .map(|&(x, y)| {
            let dx = x as f64 - mean_x;
            let dy = y - mean_y;
            (dy * dy, (dy - slope * dx).powi(2))
        })
        .fold((0.0, 0.0), |(a, b), (c, d)| (a + c, b + d))
}
