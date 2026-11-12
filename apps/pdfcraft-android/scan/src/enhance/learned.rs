//! Groundwork for "Max quality" (#304): what DocRes is given besides the page, and how its output
//! is turned into a correction of the full-size page.
//!
//! DocRes (https://github.com/ZZZHANG-jx/DocRes, MIT) is told which task to do by a *prompt*
//! computed from the page itself. For the two tasks #304 uses:
//!
//! - **deshadowing**: an estimate of the paper under the light, [`deshadow_prompt`];
//! - **appearance**: how far each pixel is from that estimate, stretched to 0–255,
//!   [`appearance_prompt`].
//!
//! Both are ports of DocRes's `inference.py`: the page is resized to 1024 × 1024 (bilinear), each
//! channel is dilated with a 7 × 7 square and median-filtered over 21 × 21, and the result is
//! resized back. They are checked against DocRes's own Python on three drawn pages
//! (`tools/scan-models/docres_prompts.py`, fixtures in `scan/tests/data/docres-*.png`).
//!
//! The model never draws the saved page. It runs on a small copy, and [`correction_map`] keeps
//! only the slow change in light it made (output over input, cleaned of anything the size of
//! print), which [`apply_correction`] scales up and multiplies into the full-size page. Text stays
//! as sharp as the photo and no letter can be invented.
//!
//! **The model itself is not here**: its weights could not be fetched on the machine this was
//! written on, so the feasibility measurement the issue starts with has not been made. See
//! `docs/ANDROID_SCAN_MAX_QUALITY.md`.

use crate::warp::Rgb;

/// The side of the square DocRes computes its prompts on.
pub const PROMPT_SIDE: usize = 1024;
/// The dilation's square, and the median's.
const DILATE: usize = 7;
const MEDIAN: usize = 21;
/// The correction map's limits (the issue's): a pixel is at most brightened 5 times or darkened
/// to 0.4.
pub const MAP_LIMITS: (f32, f32) = (0.4, 5.0);
/// Added to output and input before dividing, on a 0–1 scale, so dark pixels don't blow up.
const MAP_FLOOR: f32 = 0.02;
/// The cleaning's median radius, as a share of the map's longer side (the issue's 2 %).
const CLEAN_RADIUS: f32 = 0.02;

/// DocRes's deshadowing prompt for `page`: each channel's paper under the light, at the page's
/// size.
pub fn deshadow_prompt(page: &Rgb) -> Option<Rgb> {
    prompt(page, |_plane, bg| bg.to_vec())
}

/// DocRes's appearance prompt for `page`: `255 - |page - background|` per channel, stretched so
/// its darkest value is 0 and its lightest 255, at the page's size.
pub fn appearance_prompt(page: &Rgb) -> Option<Rgb> {
    prompt(page, |plane, bg| {
        let diff: Vec<u8> = plane.iter().zip(bg).map(|(&p, &b)| 255 - p.abs_diff(b)).collect();
        stretch(&diff)
    })
}

/// The shared steps: split, resize to the prompt square, background per channel, `finish` it,
/// merge, resize back.
fn prompt(page: &Rgb, finish: impl Fn(&[u8], &[u8]) -> Vec<u8>) -> Option<Rgb> {
    let (w, h) = (page.width as usize, page.height as usize);
    if w == 0 || h == 0 || page.data.len() != w * h * 3 {
        return None;
    }
    let mut out = vec![0u8; w * h * 3];
    for c in 0..3 {
        let plane: Vec<u8> = page.data.iter().skip(c).step_by(3).copied().collect();
        let square = resize(&plane, (w, h), (PROMPT_SIDE, PROMPT_SIDE));
        let dilated = dilate(&square, PROMPT_SIDE, PROMPT_SIDE, DILATE);
        let bg = median(&dilated, PROMPT_SIDE, PROMPT_SIDE, MEDIAN);
        let done = finish(&square, &bg);
        for (i, v) in resize(&done, (PROMPT_SIDE, PROMPT_SIDE), (w, h)).into_iter().enumerate() {
            out[i * 3 + c] = v;
        }
    }
    Some(Rgb { width: page.width, height: page.height, data: out })
}

/// OpenCV's `normalize(.., 0, 255, NORM_MINMAX)` on 8 bits: lowest to 0, highest to 255,
/// rounded half to even.
fn stretch(plane: &[u8]) -> Vec<u8> {
    let (lo, hi) = plane.iter().fold((u8::MAX, u8::MIN), |(lo, hi), &v| (lo.min(v), hi.max(v)));
    if hi == lo {
        // OpenCV's scale is then 0 over 0; it writes the shift, which is 0
        return vec![0; plane.len()];
    }
    // the scale and shift in double precision, applied in single precision (OpenCV's convertTo)
    let scale = 255.0 / f64::from(hi - lo);
    let shift = (-f64::from(lo) * scale) as f32;
    let scale = scale as f32;
    plane.iter().map(|&v| (f32::from(v) * scale + shift).round_ties_even().clamp(0.0, 255.0) as u8).collect()
}

/// Bilinear resize as OpenCV 5.0 does it for 8-bit pictures (`INTER_LINEAR`): pixel centres in
/// single precision, the edge pixel repeated, no averaging when shrinking; weights in 1/2048, each
/// rounded on its own; the rows' sums combined as its vector code does (a sixteenth of each sum,
/// the products' high halves, then the last two bits rounded off). Checked against OpenCV on the
/// fixtures, enlarging and shrinking: the same bytes.
fn resize(src: &[u8], (sw, sh): (usize, usize), (dw, dh): (usize, usize)) -> Vec<u8> {
    let taps = |s: usize, d: usize| -> Vec<(usize, usize, i64, i64)> {
        let scale = 1.0 / (d as f64 / s as f64);
        (0..d)
            .map(|i| {
                // in single precision, as OpenCV computes it; the indices are clamped but the
                // weight is kept, which matters on the first and last rows
                let f = ((i as f64 + 0.5) * scale - 0.5) as f32;
                let x = f.floor();
                let t = f - x;
                let last = s as i64 - 1;
                let x = x as i64;
                let (x0, x1) = (x.clamp(0, last) as usize, (x + 1).clamp(0, last) as usize);
                (x0, x1, ((1.0 - t) * 2048.0).round_ties_even() as i64, (t * 2048.0).round_ties_even() as i64)
            })
            .collect()
    };
    let (xs, ys) = (taps(sw, dw), taps(sh, dh));
    let row =
        |y: usize| -> Vec<i64> { xs.iter().map(|&(x0, x1, c0, c1)| i64::from(src[y * sw + x0]) * c0 + i64::from(src[y * sw + x1]) * c1).collect() };
    let mut out = vec![0u8; dw * dh];
    for (dy, &(y0, y1, b0, b1)) in ys.iter().enumerate() {
        let (r0, r1) = (row(y0), row(y1));
        for dx in 0..dw {
            let v = (((b0 * (r0[dx] >> 4)) >> 16) + ((b1 * (r1[dx] >> 4)) >> 16) + 2) >> 2;
            out[dy * dw + dx] = v.clamp(0, 255) as u8;
        }
    }
    out
}

/// The largest value in a `size` × `size` square around each pixel (cut off at the borders, as
/// OpenCV's dilation is).
fn dilate(src: &[u8], w: usize, h: usize, size: usize) -> Vec<u8> {
    let r = size / 2;
    let mut rows = vec![0u8; w * h];
    for y in 0..h {
        for x in 0..w {
            rows[y * w + x] = src[y * w + x.saturating_sub(r)..=y * w + (x + r).min(w - 1)].iter().copied().max().unwrap_or(0);
        }
    }
    let mut out = vec![0u8; w * h];
    for y in 0..h {
        for x in 0..w {
            out[y * w + x] = (y.saturating_sub(r)..=(y + r).min(h - 1)).map(|yy| rows[yy * w + x]).max().unwrap_or(0);
        }
    }
    out
}

/// The median of a `size` × `size` square around each pixel, the edge pixels repeated beyond the
/// border (OpenCV's `medianBlur`). A running histogram along each row.
fn median(src: &[u8], w: usize, h: usize, size: usize) -> Vec<u8> {
    let r = size as isize / 2;
    let at = |x: isize, y: isize| src[(y.clamp(0, h as isize - 1) as usize) * w + x.clamp(0, w as isize - 1) as usize];
    let half = size * size / 2;
    let mut out = vec![0u8; w * h];
    for y in 0..h as isize {
        let mut hist = [0usize; 256];
        for dy in -r..=r {
            for dx in -r..=r {
                hist[usize::from(at(dx, y + dy))] += 1;
            }
        }
        for x in 0..w as isize {
            if x > 0 {
                for dy in -r..=r {
                    hist[usize::from(at(x - r - 1, y + dy))] -= 1;
                    hist[usize::from(at(x + r, y + dy))] += 1;
                }
            }
            let mut seen = 0;
            for (v, &n) in hist.iter().enumerate() {
                seen += n;
                if seen > half {
                    out[y as usize * w + x as usize] = v as u8;
                    break;
                }
            }
        }
    }
    out
}

/// A correction per pixel and channel at the model's size: what to multiply the page by.
#[derive(Clone, Debug, PartialEq)]
pub struct CorrectionMap {
    pub width: usize,
    pub height: usize,
    /// Three gains per pixel, row-major.
    pub gain: Vec<[f32; 3]>,
}

/// The correction the model made, cleaned: `(output + 0.02) / (input + 0.02)` per channel,
/// limited to [`MAP_LIMITS`], then a median over 2 % of the map's longer side (on a copy at half
/// size, each value the middle of its four: what remains is light, not print) and a light
/// blur. The model re-draws strokes, and those differences must not be carried into the page. `None` when the two differ in size.
pub fn correction_map(input: &Rgb, output: &Rgb) -> Option<CorrectionMap> {
    let (w, h) = (input.width as usize, input.height as usize);
    if (output.width, output.height) != (input.width, input.height)
        || input.data.len() != w * h * 3
        || output.data.len() != w * h * 3
        || w < 2
        || h < 2
    {
        return None;
    }
    let (hw, hh) = (w.div_ceil(2), h.div_ceil(2));
    let radius = ((CLEAN_RADIUS * hw.max(hh) as f32).round() as usize).max(1);
    let mut gain = vec![[1f32; 3]; w * h];
    for c in 0..3 {
        // the raw map's logarithm at half size
        let mut half = vec![0f32; hw * hh];
        for (i, v) in half.iter_mut().enumerate() {
            let (x, y) = (i % hw * 2, i / hw * 2);
            // the middle of the four (a stroke's edge is one or two of them)
            let mut four: Vec<f32> = [(x, y), (x + 1, y), (x, y + 1), (x + 1, y + 1)]
                .into_iter()
                .filter(|&(sx, sy)| sx < w && sy < h)
                .map(|(sx, sy)| {
                    let j = (sy * w + sx) * 3 + c;
                    let a = f32::from(output.data[j]) / 255.0;
                    let s = f32::from(input.data[j]) / 255.0;
                    ((a + MAP_FLOOR) / (s + MAP_FLOOR)).clamp(MAP_LIMITS.0, MAP_LIMITS.1).ln()
                })
                .collect();
            four.sort_by(f32::total_cmp);
            let n = four.len();
            *v = if n % 2 == 1 { four[n / 2] } else { (four[n / 2 - 1] + four[n / 2]) / 2.0 };
        }
        let smooth = blur(&median_f32(&half, hw, hh, radius), hw, hh, (radius / 2).max(1));
        for (i, g) in gain.iter_mut().enumerate() {
            let (x, y) = (i % w, i / w);
            let (fx, fy) = (((x as f32 + 0.5) / 2.0 - 0.5).max(0.0), ((y as f32 + 0.5) / 2.0 - 0.5).max(0.0));
            g[c] = sample(&smooth, hw, hh, fx, fy).exp();
        }
    }
    Some(CorrectionMap { width: w, height: h, gain })
}

/// Multiply `page` by `map`, scaled up to it (bilinear). `region` is where the page lies in the
/// map (the model's input may be padded to keep the page's shape): x, y, width, height in map
/// pixels. `None` when the page's buffer does not match its size or the region is outside the map.
pub fn apply_correction(page: &Rgb, map: &CorrectionMap, region: (usize, usize, usize, usize)) -> Option<Rgb> {
    let (pw, ph) = (page.width as usize, page.height as usize);
    let (rx, ry, rw, rh) = region;
    if page.data.len() != pw * ph * 3 || rw == 0 || rh == 0 || rx + rw > map.width || ry + rh > map.height || pw == 0 || ph == 0 {
        return None;
    }
    let (sx, sy) = (rw as f32 / pw as f32, rh as f32 / ph as f32);
    let mut out = page.data.clone();
    let planes: Vec<Vec<f32>> = (0..3).map(|c| map.gain.iter().map(|g| g[c]).collect()).collect();
    for y in 0..ph {
        let my = (ry as f32 + (y as f32 + 0.5) * sy - 0.5).clamp(0.0, (map.height - 1) as f32);
        for x in 0..pw {
            let mx = (rx as f32 + (x as f32 + 0.5) * sx - 0.5).clamp(0.0, (map.width - 1) as f32);
            for (c, plane) in planes.iter().enumerate() {
                let i = (y * pw + x) * 3 + c;
                let g = sample(plane, map.width, map.height, mx, my);
                out[i] = (f32::from(page.data[i]) * g).round().clamp(0.0, 255.0) as u8;
            }
        }
    }
    Some(Rgb { width: page.width, height: page.height, data: out })
}

fn sample(plane: &[f32], w: usize, h: usize, x: f32, y: f32) -> f32 {
    let (x0, y0) = ((x as usize).min(w - 1), (y as usize).min(h - 1));
    let (x1, y1) = ((x0 + 1).min(w - 1), (y0 + 1).min(h - 1));
    let (tx, ty) = (x - x0 as f32, y - y0 as f32);
    let top = plane[y0 * w + x0] * (1.0 - tx) + plane[y0 * w + x1] * tx;
    let bottom = plane[y1 * w + x0] * (1.0 - tx) + plane[y1 * w + x1] * tx;
    top * (1.0 - ty) + bottom * ty
}

/// The median of a square of radius `r` around each value, the edge repeated, by sorting (the map
/// is small).
fn median_f32(src: &[f32], w: usize, h: usize, r: usize) -> Vec<f32> {
    let r = r as isize;
    let mut window = Vec::with_capacity(((2 * r + 1) * (2 * r + 1)) as usize);
    let mut out = vec![0f32; w * h];
    for y in 0..h as isize {
        for x in 0..w as isize {
            window.clear();
            for dy in -r..=r {
                let yy = (y + dy).clamp(0, h as isize - 1) as usize;
                for dx in -r..=r {
                    let xx = (x + dx).clamp(0, w as isize - 1) as usize;
                    window.push(src[yy * w + xx]);
                }
            }
            let mid = window.len() / 2;
            window.select_nth_unstable_by(mid, f32::total_cmp);
            out[y as usize * w + x as usize] = window[mid];
        }
    }
    out
}

/// A box blur of radius `r`, twice (close to a Gaussian), the edge repeated.
fn blur(src: &[f32], w: usize, h: usize, r: usize) -> Vec<f32> {
    let pass = |src: &[f32], horizontal: bool| -> Vec<f32> {
        let mut out = vec![0f32; src.len()];
        let r = r as isize;
        for y in 0..h as isize {
            for x in 0..w as isize {
                let mut sum = 0f32;
                for d in -r..=r {
                    let (xx, yy) = if horizontal { ((x + d).clamp(0, w as isize - 1), y) } else { (x, (y + d).clamp(0, h as isize - 1)) };
                    sum += src[yy as usize * w + xx as usize];
                }
                out[y as usize * w + x as usize] = sum / (2 * r + 1) as f32;
            }
        }
        out
    };
    let once = pass(&pass(src, true), false);
    pass(&pass(&once, true), false)
}
