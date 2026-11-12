//! Tone metrics that score a finished scanned page (issue #288, spec S02 section 3): is the paper
//! white, is the light even across it, is the ink dark, does a stamp keep its colour, and is the
//! print sharp. Every function takes a mask (`true` = this pixel counts; one entry per pixel, row
//! by row) and returns `None` rather than panicking when a length does not match the picture, a
//! mask has no pixel that counts, or a value is not usable. Time and memory are bounded by the
//! pixel count of the input.

use crate::{Luma, Rgb};

/// Share of the counted pixels whose Laplacian is averaged for sharpness: the top 10 %.
const SHARPNESS_TOP_SHARE: f64 = 0.10;
/// Largest magnitude of a 4-neighbour Laplacian of an 8-bit picture: four neighbours of 255
/// against a centre of 0. Sizes the histogram that replaces sorting.
const LAPLACIAN_MAX: usize = 4 * 255;
/// Luminance at or above which a paper pixel counts as white (issue #288).
const PAPER_WHITE_LEVEL: u8 = 245;
/// Gaussian sigma of the illumination low-pass, as a share of the page's longer side (issue #288).
const ILLUM_SIGMA_SHARE: f64 = 0.03;
/// The illumination low-pass runs on a copy reduced this many times in each direction, so its
/// cost does not grow with sigma.
const ILLUM_REDUCE: usize = 4;
/// Percentile of the illumination low-pass taken as its dark end (P5).
const ILLUM_LOW_PERCENT: f64 = 5.0;
/// Percentile of the illumination low-pass taken as its typical level (P50, the median).
const ILLUM_MID_PERCENT: f64 = 50.0;
/// Percentile of the illumination low-pass taken as its bright end (P95).
const ILLUM_HIGH_PERCENT: f64 = 95.0;
/// Percentile of the paper luminance used as the paper level in `ink_contrast` (its median).
const PAPER_LEVEL_PERCENT: f64 = 50.0;
/// Percentile of the ink luminance used as the ink level in `ink_contrast` (its 5th percentile,
/// so a few stray light specks in the ink do not count).
const INK_LEVEL_PERCENT: f64 = 5.0;
/// Smallest chroma (max minus min channel, 0-255) at which a pixel has a hue worth comparing.
/// Below it the colour is grey for this purpose and the pixel is left out of `hue_shift`.
const MIN_CHROMA: u8 = 16;
/// Degrees in a full turn of hue.
const HUE_TURN: f64 = 360.0;
/// Hue sectors per turn: the hue formula gives values in [0, 6) before scaling by 60 degrees.
const HUE_SECTORS: f64 = 6.0;
/// Degrees per hue sector.
const DEGREES_PER_SECTOR: f64 = 60.0;
/// Number of box blurs that together approximate a Gaussian (three is close enough).
const BLUR_PASSES: usize = 3;
/// Histogram bins of an 8-bit grey level.
const LEVELS: usize = 256;

/// Mean of the top 10 % of |3×3 Laplacian| of luminance over the counted pixels (issue #288).
/// A pixel counts when it and its four neighbours are all inside the mask and it is not on the
/// picture's border, so the page's own edge does not read as sharpness. The Laplacian is the
/// 4-neighbour one. Returns `None` when the lengths do not match or no pixel counts.
/// Note: the top tenth is taken over all counted pixels, not over edge pixels only. A sparse page
/// (one line of text on a blank page) therefore scores far below a dense one of the same text.
pub fn sharpness(page: &Luma, inside: &[bool]) -> Option<f64> {
    if !fits(page, inside) {
        return None;
    }
    let (w, h) = dims(page)?;
    let mut hist = [0u64; LAPLACIAN_MAX + 1];
    let mut counted = 0u64;
    for y in 1..h.saturating_sub(1) {
        for x in 1..w.saturating_sub(1) {
            let i = y * w + x;
            if neighbourhood_inside(inside, w, i) {
                hist[laplacian_magnitude(&page.data, w, i)] += 1;
                counted += 1;
            }
        }
    }
    top_share_mean(&hist, counted)
}

/// True when the pixel at `i` and its four neighbours are all inside the mask. The caller
/// guarantees `i` is not on the border, so every index is in range.
fn neighbourhood_inside(inside: &[bool], w: usize, i: usize) -> bool {
    inside[i] && inside[i - 1] && inside[i + 1] && inside[i - w] && inside[i + w]
}

/// Magnitude of the 4-neighbour Laplacian at `i`. The caller guarantees `i` is not on the border.
fn laplacian_magnitude(data: &[u8], w: usize, i: usize) -> usize {
    let at = |j: usize| i32::from(data[j]);
    let lap = at(i - 1) + at(i + 1) + at(i - w) + at(i + w) - 4 * at(i);
    lap.unsigned_abs() as usize
}

/// Mean of the largest tenth of the values in a histogram that counts `counted` values in all.
/// Counts from the top bin down, so no sort is needed.
fn top_share_mean(hist: &[u64], counted: u64) -> Option<f64> {
    if counted == 0 {
        return None;
    }
    let take = ((counted as f64) * SHARPNESS_TOP_SHARE).ceil().max(1.0) as u64;
    let mut left = take;
    let mut sum = 0.0;
    for (value, &n) in hist.iter().enumerate().rev() {
        let used = n.min(left);
        sum += used as f64 * value as f64;
        left -= used;
        if left == 0 {
            break;
        }
    }
    Some(sum / take as f64)
}

/// Share of paper pixels with luminance >= 245 (issue #288). The paper mask says which pixels
/// are paper. `None` when the lengths do not match or there is no paper.
pub fn paper_white(page: &Luma, paper: &[bool]) -> Option<f64> {
    if !fits(page, paper) {
        return None;
    }
    let mut total = 0u64;
    let mut white = 0u64;
    for (&v, &p) in page.data.iter().zip(paper) {
        if p {
            total += 1;
            if v >= PAPER_WHITE_LEVEL {
                white += 1;
            }
        }
    }
    (total > 0).then(|| white as f64 / total as f64)
}

/// (P95 − P5) / P50 of the low-pass luminance over paper pixels (issue #288). The low-pass is a
/// Gaussian with sigma = 3 % of the longer side, built from three box blurs, and it averages
/// paper pixels only, so ink does not darken it. It runs on a copy reduced 4 times in each
/// direction, and each paper pixel takes the value of its reduced cell. `None` when the lengths
/// do not match, there is no paper, or P50 is 0.
pub fn illum_spread(page: &Luma, paper: &[bool]) -> Option<f64> {
    if !fits(page, paper) {
        return None;
    }
    let (w, h) = dims(page)?;
    let (rw, rh) = (w.div_ceil(ILLUM_REDUCE), h.div_ceil(ILLUM_REDUCE));
    let low = paper_low_pass(page, paper, (w, h), (rw, rh))?;
    let mut hist = [0u32; LEVELS];
    for (i, &p) in paper.iter().enumerate() {
        if p {
            let (x, y) = (i % w / ILLUM_REDUCE, i / w / ILLUM_REDUCE);
            let level = low.get(y * rw + x)?;
            hist[level.round().clamp(0.0, 255.0) as usize] += 1;
        }
    }
    let low_level = i32::from(percentile(&hist, ILLUM_LOW_PERCENT)?);
    let high_level = i32::from(percentile(&hist, ILLUM_HIGH_PERCENT)?);
    let mid_level = percentile(&hist, ILLUM_MID_PERCENT)?;
    if mid_level == 0 {
        return None;
    }
    Some(f64::from(high_level - low_level) / f64::from(mid_level))
}

/// The low-pass luminance of each reduced cell (normalised convolution over paper pixels only).
/// Returns one value per cell of the `(rw, rh)` grid, in row order.
fn paper_low_pass(page: &Luma, paper: &[bool], (w, h): (usize, usize), (rw, rh): (usize, usize)) -> Option<Vec<f64>> {
    let mut sums = vec![0.0f64; rw * rh];
    let mut counts = vec![0.0f64; rw * rh];
    for (i, (&v, &p)) in page.data.iter().zip(paper).enumerate() {
        if p {
            let cell = (i / w / ILLUM_REDUCE) * rw + (i % w) / ILLUM_REDUCE;
            sums[cell] += f64::from(v);
            counts[cell] += 1.0;
        }
    }
    let sigma = ILLUM_SIGMA_SHARE * w.max(h) as f64 / ILLUM_REDUCE as f64;
    let radii = box_radii(sigma);
    let mut prefix = Vec::with_capacity(rw.max(rh) + 1);
    for &r in &radii {
        blur_2d(&mut sums, rw, rh, r, &mut prefix);
        blur_2d(&mut counts, rw, rh, r, &mut prefix);
    }
    Some(sums.iter().zip(&counts).map(|(s, c)| if *c > 0.0 { s / c } else { 0.0 }).collect())
}

/// Box widths (as radii) of `BLUR_PASSES` boxes whose blurs together match a Gaussian of the
/// given sigma. Kutskir's method: the ideal width gives the right variance, rounded to an odd
/// width below and above, and the split between them is chosen to match the variance.
fn box_radii(sigma: f64) -> [usize; BLUR_PASSES] {
    let n = BLUR_PASSES as f64;
    let ideal = (12.0 * sigma * sigma / n + 1.0).sqrt();
    let mut lower = ideal.floor().max(1.0) as i64;
    if lower % 2 == 0 {
        lower -= 1;
    }
    let upper = lower + 2;
    let lo = lower as f64;
    let lower_count = (12.0 * sigma * sigma - n * lo * lo - 4.0 * n * lo - 3.0 * n) / (-4.0 * lo - 4.0);
    let count = lower_count.round().clamp(0.0, n) as usize;
    std::array::from_fn(|k| {
        let width = if k < count { lower } else { upper };
        ((width - 1) / 2).max(0) as usize
    })
}

/// One separable box blur of the whole `w` × `h` field: each row, then each column.
fn blur_2d(field: &mut [f64], w: usize, h: usize, radius: usize, prefix: &mut Vec<f64>) {
    for y in 0..h {
        box_blur_line(field, y * w, 1, w, radius, prefix);
    }
    for x in 0..w {
        box_blur_line(field, x, w, h, radius, prefix);
    }
}

/// Box blur of one line of `len` values starting at `start` with step `stride`, averaging over
/// the values that exist within `radius` (so the edges are not padded). Sums come from running
/// prefix sums, so the cost is linear in `len` whatever the radius.
fn box_blur_line(field: &mut [f64], start: usize, stride: usize, len: usize, radius: usize, prefix: &mut Vec<f64>) {
    prefix.clear();
    prefix.push(0.0);
    let mut acc = 0.0;
    for k in 0..len {
        acc += field[start + k * stride];
        prefix.push(acc);
    }
    for k in 0..len {
        let lo = k.saturating_sub(radius);
        let hi = (k + radius + 1).min(len);
        field[start + k * stride] = prefix[hi] - prefix[lo];
    }
}

/// The p-th percentile (0–100) of a 256-bin histogram, as a grey level (issue #288). The result
/// is the lowest level at which the cumulative count reaches `p` % of the total. `None` when `p`
/// is outside 0–100 (or not a number) or the histogram is empty.
pub fn percentile(histogram: &[u32; LEVELS], p: f64) -> Option<u8> {
    if !(0.0..=100.0).contains(&p) {
        return None;
    }
    let total: u64 = histogram.iter().map(|&c| u64::from(c)).sum();
    if total == 0 {
        return None;
    }
    let rank = ((p / 100.0) * total as f64).ceil().max(1.0) as u64;
    let mut cumulative = 0u64;
    for (level, &count) in histogram.iter().enumerate() {
        cumulative += u64::from(count);
        if cumulative >= rank {
            return u8::try_from(level).ok();
        }
    }
    None
}

/// P50 of paper − P5 of ink, in grey levels (issue #288). Paper and ink come from the masks,
/// and the paper level is the median of the paper, so a few specks do not move it. Negative
/// when the ink is lighter than the paper. `None` when the lengths do not match or either mask
/// has no pixel that counts.
pub fn ink_contrast(page: &Luma, paper: &[bool], ink: &[bool]) -> Option<f64> {
    if !fits(page, paper) || !fits(page, ink) {
        return None;
    }
    let paper_hist = luma_histogram(&page.data, paper);
    let ink_hist = luma_histogram(&page.data, ink);
    let paper_level = percentile(&paper_hist, PAPER_LEVEL_PERCENT)?;
    let ink_level = percentile(&ink_hist, INK_LEVEL_PERCENT)?;
    Some(f64::from(paper_level) - f64::from(ink_level))
}

/// Histogram of the grey levels of the pixels the mask counts.
fn luma_histogram(data: &[u8], mask: &[bool]) -> [u32; LEVELS] {
    let mut hist = [0u32; LEVELS];
    for (&v, &on) in data.iter().zip(mask) {
        if on {
            hist[usize::from(v)] = hist[usize::from(v)].saturating_add(1);
        }
    }
    hist
}

/// Mean absolute change of hue between two pictures of the same size over the stamp's pixels,
/// in degrees, 0 to 180 (issue #288). Hue is a circle, so the change takes the shorter way
/// round. A pixel is left out when either picture has less than 16 of chroma there (max minus
/// min channel), since a near-grey pixel has no hue to compare. `None` when the pictures differ
/// in size, a length does not match, or no pixel is left in.
pub fn hue_shift(before: &Rgb, after: &Rgb, stamp: &[bool]) -> Option<f64> {
    if before.width != after.width || before.height != after.height {
        return None;
    }
    let n = pixel_count(before.width, before.height)?;
    if before.data.len() != 3 * n || after.data.len() != 3 * n || stamp.len() != n {
        return None;
    }
    let mut sum = 0.0;
    let mut counted = 0u64;
    for (i, &on) in stamp.iter().enumerate() {
        let both = if on { hue_at(&before.data, i).zip(hue_at(&after.data, i)) } else { None };
        if let Some((a, b)) = both {
            sum += hue_gap(a, b);
            counted += 1;
        }
    }
    (counted > 0).then(|| sum / counted as f64)
}

/// Hue in degrees (0 to 360) of pixel `i` of an RGB8 buffer, or `None` when its chroma is under
/// `MIN_CHROMA`. Returns `None` rather than panicking when the pixel is out of the buffer.
fn hue_at(data: &[u8], i: usize) -> Option<f64> {
    let [r, g, b] = <[u8; 3]>::try_from(data.get(3 * i..3 * i + 3)?).ok()?;
    let max = r.max(g).max(b);
    let chroma = max - r.min(g).min(b);
    if chroma < MIN_CHROMA {
        return None;
    }
    let (rf, gf, bf, cf) = (f64::from(r), f64::from(g), f64::from(b), f64::from(chroma));
    let sector = if max == r {
        ((gf - bf) / cf).rem_euclid(HUE_SECTORS)
    } else if max == g {
        (bf - rf) / cf + 2.0
    } else {
        (rf - gf) / cf + 4.0
    };
    Some(sector * DEGREES_PER_SECTOR)
}

/// The shorter way round between two hues in degrees, 0 to 180.
fn hue_gap(a: f64, b: f64) -> f64 {
    let d = (a - b).abs().rem_euclid(HUE_TURN);
    d.min(HUE_TURN - d)
}

/// Pixel count of a picture, or `None` when it overflows `usize`.
fn pixel_count(width: u32, height: u32) -> Option<usize> {
    usize::try_from(width).ok()?.checked_mul(usize::try_from(height).ok()?)
}

/// Width and height as `usize`, or `None` for a picture with no pixels.
fn dims(page: &Luma) -> Option<(usize, usize)> {
    let (w, h) = (usize::try_from(page.width).ok()?, usize::try_from(page.height).ok()?);
    (w > 0 && h > 0).then_some((w, h))
}

/// True when the luminance data and the mask both have one entry per pixel of the picture.
fn fits(page: &Luma, mask: &[bool]) -> bool {
    pixel_count(page.width, page.height).is_some_and(|n| page.data.len() == n && mask.len() == n)
}
