//! Comparison metrics for the scanner's self-checks (issue #288, spec S02.md section 3): how
//! close a finished page is to the page it should have been (PSNR and SSIM on luminance), and
//! how close recognised text is to the true text (character error rate). Pure functions over
//! plain slices and strings: no clock, no randomness, no allocation beyond the inputs.

use crate::Luma;

/// The largest grey value of a byte, the peak in the PSNR formula.
const PEAK: f64 = 255.0;
/// Side of the square window SSIM is measured over, in pixels.
const SSIM_WINDOW: usize = 8;
/// Pixels between the starts of neighbouring SSIM windows.
const SSIM_STEP: usize = 4;
/// Luminance stabiliser C1 = (K1 * 255)^2 with K1 = 0.01 (Wang et al. 2004).
const SSIM_C1: f64 = (0.01 * PEAK) * (0.01 * PEAK);
/// Contrast and structure stabiliser C2 = (K2 * 255)^2 with K2 = 0.03 (Wang et al. 2004).
const SSIM_C2: f64 = (0.03 * PEAK) * (0.03 * PEAK);

/// The pixels of a picture, when its byte count is exactly width times height and neither side
/// is zero. Anything else is malformed and gives `None`.
fn pixels(img: &Luma) -> Option<&[u8]> {
    let count = (img.width as usize).checked_mul(img.height as usize)?;
    if count == 0 || img.data.len() != count {
        return None;
    }
    Some(&img.data)
}

/// The pixels of two pictures that are both well formed and exactly the same size.
fn checked_pair<'a>(a: &'a Luma, b: &'a Luma) -> Option<(&'a [u8], &'a [u8])> {
    if a.width != b.width || a.height != b.height {
        return None;
    }
    Some((pixels(a)?, pixels(b)?))
}

/// Peak signal-to-noise ratio in dB between two pictures of the same size, from the mean squared
/// grey difference (issue #288). `None` when the sizes differ, a side is zero or the data does
/// not match the size. Identical pictures give `f64::INFINITY`.
pub fn psnr(a: &Luma, b: &Luma) -> Option<f64> {
    let (x, y) = checked_pair(a, b)?;
    let squares = x.iter().zip(y).fold(0u64, |sum, (&p, &q)| {
        let d = u64::from(p.abs_diff(q));
        sum.saturating_add(d * d)
    });
    if squares == 0 {
        return Some(f64::INFINITY);
    }
    let mean_square = squares as f64 / x.len() as f64;
    Some(10.0 * (PEAK * PEAK / mean_square).log10())
}

/// Structural similarity (Wang et al. 2004) on luminance, the mean over 8×8 windows stepped 4
/// pixels in each direction (issue #288). Windows that would run past an edge are not used, so up
/// to 3 pixels along the right and bottom edges can be left out. `None` when the
/// sizes differ or a side is under 8, or the data does not match the size.
pub fn ssim(a: &Luma, b: &Luma) -> Option<f64> {
    let (x, y) = checked_pair(a, b)?;
    let (width, height) = (a.width as usize, a.height as usize);
    if width < SSIM_WINDOW || height < SSIM_WINDOW {
        return None;
    }
    let mut total = 0.0;
    let mut count = 0usize;
    for top in window_starts(height) {
        for left in window_starts(width) {
            total += window_ssim(x, y, width, top, left);
            count += 1;
        }
    }
    Some(total / count as f64)
}

/// The first pixel of each window along a side of `len` pixels, `len` being at least
/// `SSIM_WINDOW`, so the subtraction cannot wrap.
fn window_starts(len: usize) -> impl Iterator<Item = usize> {
    (0..=len - SSIM_WINDOW).step_by(SSIM_STEP)
}

/// The SSIM of one window whose top-left pixel is at (`left`, `top`). The two passes (means,
/// then centred products) avoid the rounding that makes the one-pass variance go negative.
fn window_ssim(x: &[u8], y: &[u8], width: usize, top: usize, left: usize) -> f64 {
    let n = (SSIM_WINDOW * SSIM_WINDOW) as f64;
    let rows = (top..top + SSIM_WINDOW).map(|row| row * width + left);
    let mut sum_x = 0.0;
    let mut sum_y = 0.0;
    for start in rows.clone() {
        for i in start..start + SSIM_WINDOW {
            sum_x += f64::from(x[i]);
            sum_y += f64::from(y[i]);
        }
    }
    let (mean_x, mean_y) = (sum_x / n, sum_y / n);
    let (mut var_x, mut var_y, mut covar) = (0.0, 0.0, 0.0);
    for start in rows {
        for i in start..start + SSIM_WINDOW {
            let dx = f64::from(x[i]) - mean_x;
            let dy = f64::from(y[i]) - mean_y;
            var_x += dx * dx;
            var_y += dy * dy;
            covar += dx * dy;
        }
    }
    let (var_x, var_y, covar) = (var_x / n, var_y / n, covar / n);
    let luminance = (2.0 * mean_x * mean_y + SSIM_C1) / (mean_x * mean_x + mean_y * mean_y + SSIM_C1);
    let structure = (2.0 * covar + SSIM_C2) / (var_x + var_y + SSIM_C2);
    luminance * structure
}

/// Character error rate: the edit distance between recognised and true text, counted in `char`s,
/// divided by the length of the true text (issue #288). Runs of white space count as one space
/// and both ends are trimmed first. An empty true text gives 0.0 when the recognised text is
/// empty too, and 1.0 otherwise. The rate can exceed 1.0 when the recognised text is longer.
pub fn cer(recognised: &str, truth: &str) -> f64 {
    let recognised = normalise(recognised);
    let truth = normalise(truth);
    if truth.is_empty() {
        return if recognised.is_empty() { 0.0 } else { 1.0 };
    }
    edit_distance(&recognised, &truth) as f64 / truth.len() as f64
}

/// The characters of a text with each run of white space made one space, and no space at either
/// end. Other characters, Arabic included, are kept as they are.
fn normalise(text: &str) -> Vec<char> {
    let mut out = Vec::with_capacity(text.len());
    let mut pending_space = false;
    for c in text.chars() {
        if c.is_whitespace() {
            pending_space = true;
        } else {
            if pending_space && !out.is_empty() {
                out.push(' ');
            }
            pending_space = false;
            out.push(c);
        }
    }
    out
}

/// The edit distance (Levenshtein: insert, delete or replace one item, each costing 1) between
/// two sequences, in two rows of memory (issue #288).
pub fn edit_distance(a: &[char], b: &[char]) -> usize {
    let mut previous: Vec<usize> = (0..=b.len()).collect();
    let mut current = vec![0usize; b.len() + 1];
    for (i, &ca) in a.iter().enumerate() {
        current[0] = i + 1;
        for (j, &cb) in b.iter().enumerate() {
            let replace = previous[j] + usize::from(ca != cb);
            let delete = previous[j + 1] + 1;
            let insert = current[j] + 1;
            current[j + 1] = replace.min(delete).min(insert);
        }
        std::mem::swap(&mut previous, &mut current);
    }
    previous[b.len()]
}
