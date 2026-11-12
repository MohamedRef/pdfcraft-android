//! Small tensor helpers the model modules share (#289, section 2): turning pixels into a
//! network's planes, resizing with the mapping back to the source, and reading a heat map.
//!
//! `area_weights`, `shrink_rgb`, `Picture` and `sigmoid` were in `detect.rs` and moved here
//! unchanged; `argmax` and `soft_argmax_window` are the two steps `detect::read` did inline,
//! written out once (`tests_tensor.rs` checks the detector reads every map exactly as before).

use pdfcraft_geom::perspective::Point;

/// An RGB picture as floats 0–1, interleaved.
pub struct Picture {
    pub w: usize,
    pub h: usize,
    pub rgb: Vec<f32>,
}

/// For each of `n_out` cells along an axis of `n_in`: the first source index and the weight of
/// each source value in the cell's average (exact area coverage).
pub fn area_weights(n_in: usize, n_out: usize) -> Vec<(usize, Vec<f32>)> {
    let step = n_in as f64 / n_out as f64;
    (0..n_out)
        .map(|o| {
            let (a, b) = (o as f64 * step, (o as f64 + 1.0) * step);
            let first = a.floor() as usize;
            let last = (b.ceil() as usize).min(n_in);
            let weights = (first..last).map(|i| ((b.min(i as f64 + 1.0) - a.max(i as f64)) / step) as f32).collect();
            (first, weights)
        })
        .collect()
}

/// Any `w` × `h` picture of RGB bytes shrunk so its longer side is `side` at most (each pixel the
/// average of what it covers), as floats 0–1, and the scale it was shrunk by.
pub fn shrink_rgb(pixels: &[u8], w: usize, h: usize, side: usize) -> (Picture, f64) {
    let scale = (side as f64 / w.max(h) as f64).min(1.0);
    let (ow, oh) = (((w as f64 * scale).round() as usize).max(1), ((h as f64 * scale).round() as usize).max(1));
    (area_resize(pixels, w, h, ow, oh), scale)
}

/// `pixels` (`w` × `h` RGB bytes) averaged into `ow` × `oh` cells by exact area, as floats 0–1.
/// For shrinking: each output pixel is the mean of the source area it covers.
fn area_resize(pixels: &[u8], w: usize, h: usize, ow: usize, oh: usize) -> Picture {
    let (xs, ys) = (area_weights(w, ow), area_weights(h, oh));
    // rows first: h × ow
    let mut rows = vec![0f32; h * ow * 3];
    for (source, out) in pixels.chunks_exact(w * 3).zip(rows.chunks_exact_mut(ow * 3)) {
        for ((first, weights), px) in xs.iter().zip(out.chunks_exact_mut(3)) {
            for (i, weight) in weights.iter().enumerate() {
                let s = &source[(first + i) * 3..(first + i) * 3 + 3];
                for c in 0..3 {
                    px[c] += weight * f32::from(s[c]);
                }
            }
        }
    }
    let mut rgb = vec![0f32; oh * ow * 3];
    for ((first, weights), out) in ys.iter().zip(rgb.chunks_exact_mut(ow * 3)) {
        for (i, weight) in weights.iter().enumerate() {
            let source = &rows[(first + i) * ow * 3..(first + i + 1) * ow * 3];
            for (o, s) in out.iter_mut().zip(source) {
                *o += weight * s / 255.0;
            }
        }
    }
    Picture { w: ow, h: oh, rgb }
}

/// `pixels` (`w` × `h` RGB bytes) resized to `ow` × `oh` by bilinear sampling of pixel centres,
/// as floats 0–1. For growing.
fn bilinear_resize(pixels: &[u8], w: usize, h: usize, ow: usize, oh: usize) -> Picture {
    let mut rgb = vec![0f32; ow * oh * 3];
    let (sx, sy) = (w as f64 / ow as f64, h as f64 / oh as f64);
    for y in 0..oh {
        let fy = ((y as f64 + 0.5) * sy - 0.5).clamp(0.0, (h - 1) as f64);
        let (y0, ay) = (fy as usize, (fy - fy.floor()) as f32);
        let y1 = (y0 + 1).min(h - 1);
        for x in 0..ow {
            let fx = ((x as f64 + 0.5) * sx - 0.5).clamp(0.0, (w - 1) as f64);
            let (x0, ax) = (fx as usize, (fx - fx.floor()) as f32);
            let x1 = (x0 + 1).min(w - 1);
            for c in 0..3 {
                let at = |x: usize, y: usize| f32::from(pixels[(y * w + x) * 3 + c]);
                let top = at(x0, y0) + (at(x1, y0) - at(x0, y0)) * ax;
                let bottom = at(x0, y1) + (at(x1, y1) - at(x0, y1)) * ax;
                rgb[(y * ow + x) * 3 + c] = (top + (bottom - top) * ay) / 255.0;
            }
        }
    }
    Picture { w: ow, h: oh, rgb }
}

/// How [`resize_rgb`] fits a picture into its output.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ResizeMode {
    /// Each side scaled on its own to fill the output exactly.
    Stretch,
    /// One scale for both sides, centred, the rest black.
    Letterbox,
}

/// Where a resized picture's points came from: `source = (out - offset) / scale` on each axis.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Mapping {
    pub scale: [f64; 2],
    pub offset: [f64; 2],
}

impl Mapping {
    /// The point of the source picture that output point `p` shows (pixel edges, not centres).
    pub fn to_source(self, [x, y]: Point) -> Point {
        [(x - self.offset[0]) / self.scale[0], (y - self.offset[1]) / self.scale[1]]
    }

    /// Where source point `p` lands in the output.
    pub fn to_output(self, [x, y]: Point) -> Point {
        [x * self.scale[0] + self.offset[0], y * self.scale[1] + self.offset[1]]
    }
}

/// `pixels` (`w` × `h` RGB bytes) at `out_w` × `out_h`, as floats 0–1 interleaved, with the
/// mapping back to the source. An axis that shrinks is averaged by area, one that grows is
/// sampled bilinearly. `None` for an empty or short input.
pub fn resize_rgb(pixels: &[u8], w: usize, h: usize, out_w: usize, out_h: usize, mode: ResizeMode) -> Option<(Picture, Mapping)> {
    if w == 0 || h == 0 || out_w == 0 || out_h == 0 || pixels.len() < w * h * 3 {
        return None;
    }
    let pixels = &pixels[..w * h * 3];
    let (iw, ih, offset) = match mode {
        ResizeMode::Stretch => (out_w, out_h, [0.0, 0.0]),
        ResizeMode::Letterbox => {
            let scale = (out_w as f64 / w as f64).min(out_h as f64 / h as f64);
            let (iw, ih) = (((w as f64 * scale).round() as usize).clamp(1, out_w), ((h as f64 * scale).round() as usize).clamp(1, out_h));
            (iw, ih, [((out_w - iw) / 2) as f64, ((out_h - ih) / 2) as f64])
        }
    };
    let inner = if iw <= w && ih <= h {
        area_resize(pixels, w, h, iw, ih)
    } else if iw >= w && ih >= h {
        bilinear_resize(pixels, w, h, iw, ih)
    } else {
        // one axis grows, the other shrinks: area on the shrinking one first, then bilinear
        let (mw, mh) = (iw.min(w), ih.min(h));
        let mid = area_resize(pixels, w, h, mw, mh);
        let bytes: Vec<u8> = mid.rgb.iter().map(|v| (v * 255.0).round().clamp(0.0, 255.0) as u8).collect();
        bilinear_resize(&bytes, mw, mh, iw, ih)
    };
    let mapping = Mapping { scale: [iw as f64 / w as f64, ih as f64 / h as f64], offset };
    if iw == out_w && ih == out_h {
        return Some((inner, mapping));
    }
    let mut rgb = vec![0f32; out_w * out_h * 3];
    let (ox, oy) = (offset[0] as usize, offset[1] as usize);
    for (y, row) in inner.rgb.chunks_exact(iw * 3).enumerate() {
        let at = ((oy + y) * out_w + ox) * 3;
        rgb[at..at + iw * 3].copy_from_slice(row);
    }
    Some((Picture { w: out_w, h: out_h, rgb }, mapping))
}

/// `rgb` (interleaved floats, `w` × `h`) as three planes, each value `(v - mean) / std`.
pub fn planes(rgb: &[f32], w: usize, h: usize, mean: [f32; 3], std: [f32; 3]) -> Vec<f32> {
    let n = w * h;
    let mut out = vec![0f32; 3 * n];
    for (i, px) in rgb.as_chunks::<3>().0.iter().take(n).enumerate() {
        for c in 0..3 {
            out[c * n + i] = (px[c] - mean[c]) / std[c];
        }
    }
    out
}

/// `rgb` (interleaved bytes, `w` × `h`) as three planes of `(v / 255 - mean) / std`.
pub fn rgb_to_nchw_f32(rgb: &[u8], w: usize, h: usize, mean: [f32; 3], std: [f32; 3]) -> Vec<f32> {
    let unit: Vec<f32> = rgb.iter().take(w * h * 3).map(|&v| f32::from(v) / 255.0).collect();
    planes(&unit, w, h, mean, std)
}

pub fn sigmoid(x: f32) -> f32 {
    1.0 / (1.0 + (-x).exp())
}

/// The index of the largest value (the last of equal largest ones, as `Iterator::max_by` gives),
/// 0 for an empty map.
pub fn argmax(map: &[f32]) -> usize {
    map.iter().enumerate().max_by(|a, b| a.1.total_cmp(b.1)).map_or(0, |(i, _)| i)
}

/// The cell `(x, y)` of the largest value of a `w`-wide map.
pub fn argmax_2d(map: &[f32], w: usize) -> (usize, usize) {
    let peak = argmax(map);
    (peak % w.max(1), peak / w.max(1))
}

/// The centre, in cells, of the probabilities (sigmoid of the logits) within `radius` cells of
/// `peak` in a `w` × `h` map of logits.
pub fn soft_argmax_window(logits: &[f32], w: usize, h: usize, peak: (usize, usize), radius: usize) -> Point {
    let (px, py) = peak;
    let (mut sum, mut sx, mut sy) = (0.0f64, 0.0f64, 0.0f64);
    for y in py.saturating_sub(radius)..=(py + radius).min(h.saturating_sub(1)) {
        for x in px.saturating_sub(radius)..=(px + radius).min(w.saturating_sub(1)) {
            let Some(&logit) = logits.get(y * w + x) else { continue };
            let p = f64::from(sigmoid(logit));
            sum += p;
            sx += p * x as f64;
            sy += p * y as f64;
        }
    }
    [sx / sum.max(1e-12), sy / sum.max(1e-12)]
}
