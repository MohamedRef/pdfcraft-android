//! Detail (#299, part 1): how much grain a page has, less of it where there is too much, and
//! crisper print without halos. All of it happens on the straightened page **before** the tone
//! curve: measured on the ten reference photos, sharpening before the curve matched the reference
//! a little better than sharpening after it, where the curve has already pushed paper to white
//! and ink to black and there is little left to sharpen but halos.
//!
//! Every pixel's result depends on its neighbours in the page as it came in, never on a pixel
//! already written, so the bytes are the same for any number of threads.

use crate::Rgb;

use super::plane::{Mask, Plane, lerp_taps, luma3, median};

/// With less grain than this (grey levels of 255) nothing is smoothed.
pub(crate) const NOISE_FLOOR: f32 = 2.0;
/// A pixel is paper when it is at least this bright against the light that falls on it.
const PAPER: f32 = 0.85;
/// About this many places on the page are asked about its grain.
const SAMPLES: f64 = 400_000.0;
/// What a sharpening setting of 1 adds of the difference between a pixel and its surroundings.
const AMOUNT: f32 = 0.9;
/// How far past its neighbours a sharpened pixel may go, in grey levels: no halo round a letter.
const OVERSHOOT: f32 = 4.0;
/// The share of the sharpening that photographs and other things that are not print get.
const GENTLE: f32 = 0.3;
/// Rows worked on at a time; bounds the scratch memory whatever the page's size.
const BAND: usize = 96;

fn luma(px: &[u8]) -> f32 {
    0.299 * f32::from(px[0]) + 0.587 * f32::from(px[1]) + 0.114 * f32::from(px[2])
}

fn smoothstep(x: f32, from: f32, to: f32) -> f32 {
    let t = ((x - from) / (to - from).max(1e-6)).clamp(0.0, 1.0);
    t * t * (3.0 - 2.0 * t)
}

/// The grain of the page: the standard deviation of its noise in grey levels, measured on blank
/// paper. `light` is the paper's colour under the local light (working size, 0–1), which says
/// where the paper is.
///
/// The measure is the median absolute response of the 3 × 3 Laplacian (`0 1 0 / 1 −4 1 / 0 1 0`),
/// which amplifies independent noise by √20, at places where the pixel and its four neighbours
/// are all paper; a median is not moved by the few places that are not flat after all.
pub(crate) fn noise_sigma(page: &Rgb, light: &[Plane; 3]) -> f32 {
    let (w, h) = (page.width as usize, page.height as usize);
    let bright = luma3(light);
    if w < 3 || h < 3 || bright.w == 0 || bright.h == 0 || page.data.len() != w * h * 3 {
        return 0.0;
    }
    let step = (((w * h) as f64 / SAMPLES).sqrt().ceil() as usize).max(1);
    let (xs, ys) = (lerp_taps(w, bright.w), lerp_taps(h, bright.h));
    let at = |x: usize, y: usize| luma(&page.data[(y * w + x) * 3..(y * w + x) * 3 + 3]);
    let mut found: Vec<f32> = Vec::new();
    for y in (1 + step / 2..h - 1).step_by(step) {
        let (y0, y1, wy) = ys[y];
        let (top, bottom) = (bright.row(y0), bright.row(y1));
        for x in (1 + step / 2..w - 1).step_by(step) {
            let (x0, x1, wx) = xs[x];
            let (a, b) = (top[x0] + (top[x1] - top[x0]) * wx, bottom[x0] + (bottom[x1] - bottom[x0]) * wx);
            let paper = 255.0 * PAPER * (a + (b - a) * wy);
            let around = [at(x, y), at(x - 1, y), at(x + 1, y), at(x, y - 1), at(x, y + 1)];
            if around.iter().all(|&v| v >= paper) {
                found.push((around[1] + around[2] + around[3] + around[4] - 4.0 * around[0]).abs());
            }
        }
    }
    if found.len() < 100 {
        return 0.0;
    }
    1.4826 * median(&mut found) / 20f32.sqrt()
}

/// The page's rows `from..to` as luminance, row after row; a row past the top or bottom is the
/// nearest row of the page.
fn luma_rows(page: &Rgb, from: isize, to: isize, into: &mut Vec<f32>) {
    let (w, h) = (page.width as usize, page.height as usize);
    into.clear();
    for y in from..to {
        let y = y.clamp(0, h as isize - 1) as usize;
        into.extend(page.data[y * w * 3..(y + 1) * w * 3].chunks_exact(3).map(luma));
    }
}

/// Run `work` over the page in bands of [`BAND`] rows on up to `threads` threads. `work` gets
/// the band's first row and its rows of `out` (which start as the page's own bytes).
fn in_bands(page: &Rgb, threads: usize, work: impl Fn(usize, &mut [u8]) + Sync) -> Vec<u8> {
    let w = page.width as usize;
    let mut out = page.data.clone();
    if w == 0 {
        return out;
    }
    let mut bands: Vec<(usize, &mut [u8])> = out.chunks_mut(BAND * w * 3).enumerate().map(|(i, rows)| (i * BAND, rows)).collect();
    let per_thread = bands.len().div_ceil(threads.clamp(1, 8)).max(1);
    let work = &work;
    std::thread::scope(|scope| {
        for group in bands.chunks_mut(per_thread) {
            scope.spawn(move || {
                for (first, rows) in group.iter_mut() {
                    work(*first, rows);
                }
            });
        }
    });
    out
}

/// The weights of a Gaussian of standard deviation `sigma`, from the centre outwards to three
/// standard deviations, adding up to 1 over the whole kernel.
fn gaussian(sigma: f32) -> Vec<f32> {
    let radius = (3.0 * sigma).ceil().max(1.0) as usize;
    let raw: Vec<f32> = (0..=radius).map(|i| (-((i * i) as f32) / (2.0 * sigma * sigma)).exp()).collect();
    let total = raw[0] + 2.0 * raw[1..].iter().sum::<f32>();
    raw.iter().map(|v| v / total).collect()
}

/// Crisper print: each pixel is moved away from the average of its surroundings (an unsharp
/// mask on the luminance, the colour kept), with three safeguards.
///
/// - Where the difference is no larger than the grain (`sigma_n`, from [`noise_sigma`]) nothing
///   is done, so flat paper is not made grainier.
/// - A pixel never goes more than [`OVERSHOOT`] grey levels past its lightest and darkest
///   neighbour, so no bright line follows the outline of a letter.
/// - Inside `gentle` (photographs, fingers, dark printed areas, at any size) the amount is
///   [`GENTLE`] of what print gets.
///
/// `k_sharp` is the setting (0 does nothing, 0.3 is the default, 1 is a lot); `dpi` sets the
/// width of the surroundings: one pixel's worth at 300 dpi.
pub(crate) fn sharpen(page: &Rgb, k_sharp: f32, sigma_n: f32, dpi: f32, gentle: Option<&Mask>, threads: usize) -> Vec<u8> {
    let (w, h) = (page.width as usize, page.height as usize);
    let amount = AMOUNT * k_sharp;
    if amount.is_nan() || amount <= 0.0 || w < 3 || h < 3 || page.data.len() != w * h * 3 {
        return page.data.clone();
    }
    let sigma_n = if sigma_n.is_finite() { sigma_n.max(0.0) } else { 0.0 };
    let kernel = gaussian(if dpi.is_finite() { (dpi / 300.0).clamp(0.6, 2.0) } else { 1.0 });
    let radius = kernel.len() - 1;
    let (gate_from, gate_to) = (2.0 * sigma_n + 1.0, 4.0 * sigma_n + 4.0);
    let gentle = gentle.filter(|m| m.width > 0 && m.height > 0 && m.data.len() == m.width * m.height && m.data.contains(&true));
    let margin = (radius + 1) as isize;
    in_bands(page, threads, |first, out| {
        let rows = out.len() / (w * 3);
        let from = first as isize - margin;
        let mut y = Vec::new();
        luma_rows(page, from, (first + rows) as isize + margin, &mut y);
        // along the rows first
        let mut along = vec![0f32; y.len()];
        for (source, blurred) in y.chunks_exact(w).zip(along.chunks_exact_mut(w)) {
            for (x, value) in blurred.iter_mut().enumerate() {
                let mut sum = kernel[0] * source[x];
                for (k, weight) in kernel.iter().enumerate().skip(1) {
                    sum += weight * (source[x.saturating_sub(k)] + source[(x + k).min(w - 1)]);
                }
                *value = sum;
            }
        }
        for (r, row) in out.chunks_exact_mut(w * 3).enumerate() {
            // this row in the scratch rows
            let e = r + margin as usize;
            let mask_row = gentle.map(|m| &m.data[((first + r) * m.height / h).min(m.height - 1) * m.width..][..m.width]);
            for (x, px) in row.chunks_exact_mut(3).enumerate() {
                let here = y[e * w + x];
                let mut blurred = kernel[0] * along[e * w + x];
                for (k, weight) in kernel.iter().enumerate().skip(1) {
                    blurred += weight * (along[(e - k) * w + x] + along[(e + k) * w + x]);
                }
                let difference = here - blurred;
                let gate = smoothstep(difference.abs(), gate_from, gate_to);
                if gate <= 0.0 {
                    continue;
                }
                let soft = mask_row.is_some_and(|m| m[(x * m.len() / w).min(m.len() - 1)]);
                let wanted = here + amount * if soft { GENTLE } else { 1.0 } * gate * difference;
                let (mut low, mut high) = (here, here);
                for beside in [e - 1, e, e + 1] {
                    for v in &y[beside * w + x.saturating_sub(1)..=beside * w + (x + 1).min(w - 1)] {
                        low = low.min(*v);
                        high = high.max(*v);
                    }
                }
                let change = wanted.clamp(low - OVERSHOOT, high + OVERSHOOT) - here;
                for c in px.iter_mut() {
                    *c = (f32::from(*c) + change + 0.5).clamp(0.0, 255.0) as u8;
                }
            }
        }
    })
}

/// Less grain, for a page with more of it than [`NOISE_FLOOR`] (`sigma_n` from [`noise_sigma`];
/// below the floor the page comes back as it is).
///
/// - **Colour**: the two colour differences (blue and red against the luminance) are averaged
///   over 5 × 5 pixels. Coloured speckle goes; edges stay where they are, because they are in
///   the luminance.
/// - **Luminance**: only where the pixel is within three times the grain of its 5 × 5 average,
///   which is flat paper and not the edge of a stroke, it becomes an average of its neighbours
///   weighted by nearness and by likeness (a bilateral filter: 1.5 pixels, 2.5 times the grain),
///   two pixels to either side along the row and then two up and down the column. Doing the
///   two directions one after the other costs 10 neighbours a pixel instead of 25.
pub(crate) fn denoise(page: &Rgb, sigma_n: f32, threads: usize) -> Vec<u8> {
    let (w, h) = (page.width as usize, page.height as usize);
    if sigma_n.is_nan() || sigma_n < NOISE_FLOOR || w < 5 || h < 5 || page.data.len() != w * h * 3 {
        return page.data.clone();
    }
    const HALF: usize = 2;
    // nearness: 1.5 pixels, for one and two pixels away
    let near = [1.0f32, 2.0].map(|d| (-(d * d) / (2.0 * 1.5 * 1.5)).exp());
    // likeness, by quarter grey levels
    let range = 2.5 * sigma_n;
    let like: Vec<f32> = (0..=255 * 4).map(|i| (-((i as f32 / 4.0).powi(2)) / (2.0 * range * range)).exp()).collect();
    let flat = 3.0 * sigma_n;
    // a pixel and the four beside it in one direction, weighted by nearness and likeness
    let alike = |here: f32, beside: [f32; 4]| {
        let (mut sum, mut weights) = (here, 1f32);
        for (v, nearness) in beside.into_iter().zip([near[0], near[0], near[1], near[1]]) {
            let weight = nearness * like[(((v - here).abs() * 4.0) as usize).min(like.len() - 1)];
            sum += weight * v;
            weights += weight;
        }
        sum / weights
    };
    in_bands(page, threads, |first, out| {
        let rows = out.len() / (w * 3);
        let from = first as isize - HALF as isize;
        let count = rows + 2 * HALF;
        // luminance, and blue and red against it, for the band and two rows either side
        let (mut y, mut blue, mut red) = (Vec::with_capacity(count * w), Vec::with_capacity(count * w), Vec::with_capacity(count * w));
        for row in from..from + count as isize {
            let row = row.clamp(0, h as isize - 1) as usize;
            for px in page.data[row * w * 3..(row + 1) * w * 3].chunks_exact(3) {
                let l = luma(px);
                y.push(l);
                blue.push(f32::from(px[2]) - l);
                red.push(f32::from(px[0]) - l);
            }
        }
        // 5 × 5 sums: along the rows, then down the columns
        let along = |plane: &[f32]| -> Vec<f32> {
            let mut sums = vec![0f32; plane.len()];
            for (source, sum) in plane.chunks_exact(w).zip(sums.chunks_exact_mut(w)) {
                for (x, value) in sum.iter_mut().enumerate() {
                    *value = (0..=2 * HALF).map(|k| source[(x + k).saturating_sub(HALF).min(w - 1)]).sum();
                }
            }
            sums
        };
        let (y_along, blue_along, red_along) = (along(&y), along(&blue), along(&red));
        let mean = |sums: &[f32], e: usize, x: usize| (e - HALF..=e + HALF).map(|row| sums[row * w + x]).sum::<f32>() / 25.0;
        // the luminance smoothed along the rows, for every row the columns will need
        let mut smooth = vec![0f32; y.len()];
        for (source, into) in y.chunks_exact(w).zip(smooth.chunks_exact_mut(w)) {
            for (x, value) in into.iter_mut().enumerate() {
                let at = |d: isize| source[(x as isize + d).clamp(0, w as isize - 1) as usize];
                *value = alike(source[x], [at(-1), at(1), at(-2), at(2)]);
            }
        }
        for (r, row) in out.chunks_exact_mut(w * 3).enumerate() {
            let e = r + HALF;
            for (x, px) in row.chunks_exact_mut(3).enumerate() {
                let here = y[e * w + x];
                let mut l = here;
                if (here - mean(&y_along, e, x)).abs() < flat {
                    let at = |row: usize| smooth[row * w + x];
                    l = alike(at(e), [at(e - 1), at(e + 1), at(e - 2), at(e + 2)]);
                }
                let (r_out, b_out) = (l + mean(&red_along, e, x), l + mean(&blue_along, e, x));
                let g_out = (l - 0.299 * r_out - 0.114 * b_out) / 0.587;
                for (c, v) in px.iter_mut().zip([r_out, g_out, b_out]) {
                    *c = (v + 0.5).clamp(0.0, 255.0) as u8;
                }
            }
        }
    })
}
