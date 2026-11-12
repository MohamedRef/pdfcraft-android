//! Straighten the page inside a quad to an upright rectangle, sampling the photo once.
//!
//! Where the page is magnified (or kept at its size) each pixel is a bicubic (Catmull-Rom)
//! sample. Where it is shrunk, a grid of bilinear samples covers the output pixel, so fine print
//! is averaged instead of aliased into moiré. Rows are shared out to up to [`MAX_THREADS`]
//! threads in fixed bands; each row depends only on its own coordinates, so the result is the
//! same for every thread count.

use pdfcraft_geom::perspective::Homography;

use crate::{Photo, Quad, ScanError};

/// The most pixels a straightened page has.
pub const MAX_OUTPUT_PIXELS: f64 = 40_000_000.0;
/// The most threads one page is straightened with.
pub const MAX_THREADS: usize = 4;
/// From this local shrink factor on, a pixel is a grid of samples instead of one bicubic sample.
const SUPERSAMPLE_FROM: f64 = 1.5;
/// The most samples along each side of an output pixel.
const MAX_GRID: usize = 8;

/// A straightened page, RGB8, row-major.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Rgb {
    pub width: u32,
    pub height: u32,
    pub data: Vec<u8>,
}

/// The output size for this issue's rule: the longer of each pair of opposite sides, scaled down
/// so the page has at most [`MAX_OUTPUT_PIXELS`].
pub fn output_size(quad: &Quad) -> Result<(u32, u32), ScanError> {
    let [top, right, bottom, left] = quad.side_lengths();
    let (w, h) = (top.max(bottom), left.max(right));
    let k = (MAX_OUTPUT_PIXELS / (w * h).max(1.0)).sqrt().min(1.0);
    // Corners given as f32 shares land a hair off whole pixels (0.1 × 3000 = 300.0000045): a side
    // within a thousandth of a pixel of a whole number keeps it, so a frontal page is copied
    // pixel for pixel instead of being resampled by a sliver.
    let (w, h) = ((w * k + 1e-3).floor().max(1.0), (h * k + 1e-3).floor().max(1.0));
    if !(w.is_finite() && h.is_finite()) || w * h > MAX_OUTPUT_PIXELS {
        return Err(ScanError::BadCorners);
    }
    // Both are in 1..=MAX_OUTPUT_PIXELS, so the casts are exact.
    Ok((w as u32, h as u32))
}

/// The page inside `quad`, straightened to `out_w` × `out_h`, using up to [`MAX_THREADS`]
/// threads. Outside the photo is white.
pub fn rectify(photo: &Photo, quad: &Quad, out_w: u32, out_h: u32) -> Result<Rgb, ScanError> {
    let threads = std::thread::available_parallelism().map_or(1, usize::from).min(MAX_THREADS);
    rectify_with_threads(photo, quad, out_w, out_h, threads)
}

/// [`rectify`] with an exact number of threads (1 or more): the same pixels for any number.
pub fn rectify_with_threads(photo: &Photo, quad: &Quad, out_w: u32, out_h: u32, threads: usize) -> Result<Rgb, ScanError> {
    let (pw, ph) = (photo.width as usize, photo.height as usize);
    if pw == 0 || ph == 0 || pw.checked_mul(ph).and_then(|n| n.checked_mul(3)) != Some(photo.rgb.len()) {
        return Err(ScanError::Unreadable);
    }
    if out_w == 0 || out_h == 0 || f64::from(out_w) * f64::from(out_h) > MAX_OUTPUT_PIXELS {
        return Err(ScanError::BadCorners);
    }
    let (ow, oh) = (f64::from(out_w), f64::from(out_h));
    let rect = [[0.0, 0.0], [ow, 0.0], [ow, oh], [0.0, oh]];
    let to_photo = Homography::from_points(rect, quad.0).ok_or(ScanError::BadCorners)?;
    Ok(resample(photo, &Plane(to_photo), out_w, out_h, threads))
}

/// Where each point of the output page is in the photo.
pub(crate) trait ToPhoto: Sync {
    /// The photo point for an output point (pixel edges at integers), or `None` for no point.
    fn apply(&self, p: [f64; 2]) -> Option<[f64; 2]>;

    /// How many photo pixels one output pixel covers along each side at `p`.
    fn shrink(&self, p: [f64; 2]) -> f64;
}

/// A flat page seen in perspective.
struct Plane(Homography);

impl ToPhoto for Plane {
    fn apply(&self, p: [f64; 2]) -> Option<[f64; 2]> {
        self.0.apply(p)
    }

    /// The square root of the Jacobian's determinant.
    fn shrink(&self, [x, y]: [f64; 2]) -> f64 {
        let h = &self.0.0;
        let w = h[6] * x + h[7] * y + h[8];
        let Some([u, v]) = self.0.apply([x, y]) else { return 1.0 };
        let (dux, duy) = ((h[0] - u * h[6]) / w, (h[1] - u * h[7]) / w);
        let (dvx, dvy) = ((h[3] - v * h[6]) / w, (h[4] - v * h[7]) / w);
        let s = (dux * dvy - duy * dvx).abs().sqrt();
        if s.is_finite() { s } else { 1.0 }
    }
}

/// An `out_w` × `out_h` page (both at least 1, the photo's buffer matching its size), each pixel
/// sampled from the photo where `to_photo` says it is. Outside the photo is white.
pub(crate) fn resample(photo: &Photo, to_photo: &dyn ToPhoto, out_w: u32, out_h: u32, threads: usize) -> Rgb {
    let row_len = out_w as usize * 3;
    let mut data = vec![0u8; row_len * out_h as usize];
    let sampler = Sampler { photo, to_photo, out_w };
    let threads = threads.clamp(1, MAX_THREADS);
    let band = (out_h as usize).div_ceil(threads).max(1);
    std::thread::scope(|scope| {
        for (b, chunk) in data.chunks_mut(band * row_len).enumerate() {
            let sampler = &sampler;
            scope.spawn(move || {
                for (i, row) in chunk.chunks_mut(row_len).enumerate() {
                    sampler.row(b * band + i, row);
                }
            });
        }
    });
    Rgb { width: out_w, height: out_h, data }
}

struct Sampler<'a> {
    photo: &'a Photo,
    to_photo: &'a dyn ToPhoto,
    out_w: u32,
}

impl Sampler<'_> {
    /// Output row `y` into `out` (RGB8).
    fn row(&self, y: usize, out: &mut [u8]) {
        let yc = y as f64 + 0.5;
        let shrink = self.to_photo.shrink([f64::from(self.out_w) / 2.0, yc]);
        let grid = if shrink < SUPERSAMPLE_FROM { 1 } else { ((2.0 * shrink).ceil() as usize).clamp(2, MAX_GRID) };
        for (x, px) in out.chunks_exact_mut(3).enumerate() {
            let c = if grid == 1 { self.bicubic_at([x as f64 + 0.5, yc]) } else { self.grid_at(x as f64, y as f64, grid) };
            for (o, v) in px.iter_mut().zip(c) {
                *o = v.round().clamp(0.0, 255.0) as u8;
            }
        }
    }

    /// The mean of `n` × `n` bilinear samples spread evenly over output pixel (`x`, `y`).
    fn grid_at(&self, x: f64, y: f64, n: usize) -> [f64; 3] {
        let mut sum = [0.0f64; 3];
        for j in 0..n {
            for i in 0..n {
                let p = [x + (i as f64 + 0.5) / n as f64, y + (j as f64 + 0.5) / n as f64];
                let c = self.to_photo.apply(p).map_or(WHITE, |s| self.bilinear(s));
                for (a, v) in sum.iter_mut().zip(c) {
                    *a += v;
                }
            }
        }
        sum.map(|v| v / (n * n) as f64)
    }

    fn bicubic_at(&self, p: [f64; 2]) -> [f64; 3] {
        self.to_photo.apply(p).map_or(WHITE, |s| self.bicubic(s))
    }

    /// Photo pixel `(x, y)`, clamped to the photo.
    fn pixel(&self, x: i64, y: i64) -> [f64; 3] {
        let (w, h) = (i64::from(self.photo.width), i64::from(self.photo.height));
        let i = ((y.clamp(0, h - 1) * w + x.clamp(0, w - 1)) * 3) as usize;
        match self.photo.rgb.get(i..i + 3) {
            Some(p) => [f64::from(p[0]), f64::from(p[1]), f64::from(p[2])],
            None => WHITE,
        }
    }

    /// Whether photo point `p` (pixel edges at integers) is on the photo.
    fn inside(&self, [sx, sy]: [f64; 2]) -> bool {
        sx >= 0.0 && sy >= 0.0 && sx < f64::from(self.photo.width) && sy < f64::from(self.photo.height)
    }

    fn bilinear(&self, s: [f64; 2]) -> [f64; 3] {
        if !self.inside(s) {
            return WHITE;
        }
        let (fx, fy) = (s[0] - 0.5, s[1] - 0.5);
        let (x0, y0) = (fx.floor(), fy.floor());
        let (ax, ay) = (fx - x0, fy - y0);
        let (x0, y0) = (x0 as i64, y0 as i64);
        let (p00, p10, p01, p11) = (self.pixel(x0, y0), self.pixel(x0 + 1, y0), self.pixel(x0, y0 + 1), self.pixel(x0 + 1, y0 + 1));
        std::array::from_fn(|c| {
            let top = p00[c] * (1.0 - ax) + p10[c] * ax;
            let bottom = p01[c] * (1.0 - ax) + p11[c] * ax;
            top * (1.0 - ay) + bottom * ay
        })
    }

    fn bicubic(&self, s: [f64; 2]) -> [f64; 3] {
        if !self.inside(s) {
            return WHITE;
        }
        let (fx, fy) = (s[0] - 0.5, s[1] - 0.5);
        let (x0, y0) = (fx.floor(), fy.floor());
        let (wx, wy) = (catmull_rom(fx - x0), catmull_rom(fy - y0));
        let (x0, y0) = (x0 as i64, y0 as i64);
        let mut c = [0.0f64; 3];
        for (j, wy) in wy.iter().enumerate() {
            let mut row = [0.0f64; 3];
            for (i, wx) in wx.iter().enumerate() {
                let p = self.pixel(x0 - 1 + i as i64, y0 - 1 + j as i64);
                for (r, v) in row.iter_mut().zip(p) {
                    *r += wx * v;
                }
            }
            for (a, r) in c.iter_mut().zip(row) {
                *a += wy * r;
            }
        }
        c
    }
}

const WHITE: [f64; 3] = [255.0; 3];

/// Catmull-Rom weights (a = −0.5) of the four pixels around a sample `t` (0–1) past the second.
fn catmull_rom(t: f64) -> [f64; 4] {
    let (t2, t3) = (t * t, t * t * t);
    [-0.5 * t3 + t2 - 0.5 * t, 1.5 * t3 - 2.5 * t2 + 1.0, -1.5 * t3 + 2.0 * t2 + 0.5 * t, 0.5 * t3 - 0.5 * t2]
}
