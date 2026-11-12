//! Low-light merge of several straightened frames of one page (issue #297, spec S11 section 3).
//!
//! In poor light a single photo is grainy: the phone raises its sensor gain, and the noise
//! reduction inside the camera then smears fine print. The camera takes a short burst, the app
//! straightens every frame to the same size, and this module lines the frames up to a fraction
//! of a pixel and averages them. Anything that moved between frames (a finger, a shadow, a page
//! corner that lifted) is left out of the average, value by value, so it does not smear into
//! the page. Averaging N frames with independent noise cuts the noise by about the square root
//! of N.
//!
//! Frames are straightened by the caller. The sub-pixel part of the alignment is applied by the
//! caller too, folded into the frame's transform, so a frame is resampled once; [`moved`] is
//! the same move done on a picture, for tests and for callers that have no transform to fold
//! it into.
//!
//! Memory: [`Merger`] holds the reference frame and, per sample, a running sum and a count.
//! Frames are folded in one at a time and never kept.

use crate::{Luma, Rgb};

/// A frame is kept only when its sharpness is at least this share of the sharpest frame's
/// (S11 step 1).
const SHARP_SHARE: f32 = 0.7;
/// Side of the central square that alignment and noise estimates use: 512 pixels, or the whole
/// picture when it is smaller (S11 steps 3 and 4).
const CROP_SIDE: usize = 512;
/// Whole-pixel search radius for the alignment, in pixels (S11 step 3).
const SEARCH_RADIUS: i64 = 3;
/// A move must cut the residual by more than this share of the residual at no move. Less than
/// that and the page is flat (blank paper), so there is no move (S11 step 3).
const MIN_GAIN_SHARE: f64 = 0.02;
/// A move longer than this many pixels means the frame is not this page as it was (S11 step 3).
const MAX_USABLE_MOVE: f32 = 2.5;
/// Scale from the median absolute difference to a Gaussian standard deviation (S11 step 4).
const MAD_TO_SIGMA: f64 = 1.4826;
/// The difference of two frames with independent noise has a standard deviation of sqrt(2)
/// times one frame's, so the estimate divides by this (S11 step 4).
const SQRT_TWO: f64 = std::f64::consts::SQRT_2;
/// Robust average: a value joins when it is within this many sigmas of the reference's
/// (S11 step 5).
const SIGMA_MULTIPLE: f32 = 3.0;
/// Robust average: a grey-level floor added to the sigma limit, so that a noiseless frame still
/// admits small differences (S11 step 5).
const SIGMA_FLOOR: f32 = 2.0;
/// Most frames in one merge, the reference included. With this many, a sum of 255-valued samples
/// still fits in a u16 and a count fits in a u8 (S11 step 6).
const MAX_FRAMES: u8 = 200;
/// Decibels per factor of ten, for the PSNR gain estimate (S11 step 7).
const DB_PER_DECADE: f32 = 10.0;
/// Bytes per pixel of an RGB picture.
const CHANNELS: usize = 3;
/// Grey levels: one histogram bin per value of a byte.
const LEVELS: usize = 256;

/// How one frame lies against the reference.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Shift {
    /// Move the frame by this many pixels (horizontal) to lie on the reference.
    pub dx: f32,
    /// Move the frame by this many pixels (vertical) to lie on the reference.
    pub dy: f32,
    /// Mean absolute difference of luminance after the move, on the central crop.
    pub residual: f32,
    /// False when the move is over 2.5 pixels: the frame is not this page as it was.
    pub usable: bool,
}

/// The merged page and what the merge did with the frames.
#[derive(Clone, Debug, PartialEq)]
pub struct Merged {
    /// The averaged page, RGB8, row-major.
    pub rgb: Rgb,
    /// Frames folded in, the reference included.
    pub used: u8,
    /// 10·log10 of the mean count per sample: the theoretical gain in dB for the samples that
    /// were actually averaged (S11 step 7).
    pub psnr_gain_estimate: f32,
}

/// Which frame to build on and which to use with it: the sharpest, and every frame whose score
/// is at least 70 % of its. `None` for an empty list or for scores that are not finite. The
/// sharpest frame is always in the list, and the list is in index order.
pub fn choose(scores: &[f32]) -> Option<(usize, Vec<usize>)> {
    if scores.is_empty() || scores.iter().any(|s| !s.is_finite()) {
        return None;
    }
    let (mut best, mut best_score) = (0usize, f32::NEG_INFINITY);
    for (i, &s) in scores.iter().enumerate() {
        if s > best_score {
            best_score = s;
            best = i;
        }
    }
    let cutoff = SHARP_SHARE * best_score;
    let keep = scores.iter().enumerate().filter(|&(i, &s)| i == best || s >= cutoff).map(|(i, _)| i).collect();
    Some((best, keep))
}

/// Spec step 3: the move of `frame` that makes it lie on `reference`, found on the central
/// 512×512 crop (or the whole picture when smaller). The whole-pixel move within ±3 that makes
/// the sum of squared differences smallest, then a parabola through the three values around it
/// on each axis for the part of a pixel. When the best is less than 2 % better than no move
/// (blank paper), the move is exactly zero. `None` when the two pictures differ in size or are
/// empty.
pub fn align(reference: &Luma, frame: &Luma) -> Option<Shift> {
    if reference.width != frame.width || reference.height != frame.height {
        return None;
    }
    let (w, h) = (reference.width as usize, reference.height as usize);
    let r = Plane::new(&reference.data, w, h, 1, 0)?;
    let f = Plane::new(&frame.data, w, h, 1, 0)?;
    let crop = central_crop(w, h);
    if crop.w == 0 || crop.h == 0 {
        return None;
    }
    let (dx, dy) = best_move(r, f, crop);
    let (dx, dy) = (dx as f32, dy as f32);
    Some(Shift { dx, dy, residual: residual_at(r, f, crop, dx, dy), usable: dx.hypot(dy) <= MAX_USABLE_MOVE })
}

/// Spec step 4: per channel, σ = 1.4826 × median(|reference − frame|) / √2 over the central
/// crop. Two copies of one page with independent noise of σ give σ back. `None` when the two
/// pictures differ in size, or are empty.
pub fn noise_between(reference: &Rgb, frame: &Rgb) -> Option<[f32; 3]> {
    if reference.width != frame.width || reference.height != frame.height {
        return None;
    }
    let (w, h) = (reference.width as usize, reference.height as usize);
    let crop = central_crop(w, h);
    if crop.w == 0 || crop.h == 0 {
        return None;
    }
    let mut sigma = [0.0f32; 3];
    for (channel, slot) in sigma.iter_mut().enumerate() {
        let r = Plane::new(&reference.data, w, h, CHANNELS, channel)?;
        let f = Plane::new(&frame.data, w, h, CHANNELS, channel)?;
        let median = median_abs_difference(r, f, crop);
        *slot = (MAD_TO_SIGMA * median / SQRT_TWO) as f32;
    }
    Some(sigma)
}

/// The picture moved by (`dx`, `dy`) pixels: out(x, y) is `frame` read bilinearly at
/// (x − dx, y − dy), with the edges repeated. Non-finite moves count as zero. A picture whose
/// data does not match its size is returned unchanged.
pub fn moved(frame: &Rgb, dx: f32, dy: f32) -> Rgb {
    let (dx, dy) = if dx.is_finite() && dy.is_finite() { (dx, dy) } else { (0.0, 0.0) };
    let (w, h) = (frame.width as usize, frame.height as usize);
    let mut data = vec![0u8; frame.data.len()];
    for channel in 0..CHANNELS {
        let Some(plane) = Plane::new(&frame.data, w, h, CHANNELS, channel) else {
            return frame.clone();
        };
        for y in 0..h {
            for x in 0..w {
                let v = plane.bilinear(x as f32 - dx, y as f32 - dy);
                if let Some(slot) = data.get_mut((y * w + x) * CHANNELS + channel) {
                    *slot = to_byte(v);
                }
            }
        }
    }
    Rgb { width: frame.width, height: frame.height, data }
}

/// Accumulates frames that have already been moved onto the reference (spec steps 5 to 7).
///
/// Holds the reference, and per sample (each pixel and channel) a sum and a count. A value joins
/// the average when it is within 3σ + 2 of the reference's value for that sample, so a moving
/// shadow or a finger, which differs by much more, is left out there and only there.
pub struct Merger {
    /// The reference picture, RGB8. It is the page's proportions and the centre of every
    /// robust average.
    reference: Rgb,
    /// Per sample: the sum of the values that joined the average, the reference's included.
    sum: Vec<u16>,
    /// Per sample: how many values joined the average, the reference's included.
    count: Vec<u8>,
    /// Frames in so far, the reference included. Zero only for an empty or invalid reference.
    frames: u8,
}

impl Merger {
    /// A merger that starts from `reference`. A reference whose data is not `width × height × 3`
    /// bytes is replaced by an empty picture, and then nothing can be added to it.
    pub fn new(reference: Rgb) -> Merger {
        let reference = if valid_rgb(&reference) { reference } else { Rgb { width: 0, height: 0, data: Vec::new() } };
        let sum = reference.data.iter().map(|&v| u16::from(v)).collect();
        let count = vec![1u8; reference.data.len()];
        let frames = u8::from(!reference.data.is_empty());
        Merger { reference, sum, count, frames }
    }

    /// Folds in a frame already moved onto the reference. A value joins the average when it is
    /// within 3·σ + 2 of the reference's value, σ being `sigma` for its channel (a negative or
    /// non-finite σ counts as zero). Returns false, and changes nothing, when the size differs,
    /// when there is no picture to merge into, or when 200 frames are already in.
    pub fn add(&mut self, frame: &Rgb, sigma: [f32; 3]) -> bool {
        let same_size = frame.width == self.reference.width && frame.height == self.reference.height && frame.data.len() == self.reference.data.len();
        if self.frames == 0 || self.frames >= MAX_FRAMES || !same_size {
            return false;
        }
        let limits = limits(sigma);
        for (k, (&r, &f)) in self.reference.data.iter().zip(frame.data.iter()).enumerate() {
            let joins = (f32::from(f) - f32::from(r)).abs() <= limits[k % CHANNELS];
            if let (true, Some(s), Some(c)) = (joins, self.sum.get_mut(k), self.count.get_mut(k)) {
                *s = s.saturating_add(u16::from(f));
                *c = c.saturating_add(1);
            }
        }
        self.frames += 1;
        true
    }

    /// The averaged page, each sample rounded to the nearest grey level, and what the merge
    /// did. Consumes the merger.
    pub fn finish(self) -> Merged {
        let data: Vec<u8> = self.sum.iter().zip(self.count.iter()).map(|(&s, &c)| average(s, c)).collect();
        let mean_count = if self.count.is_empty() { 1.0 } else { self.count.iter().map(|&c| f64::from(c)).sum::<f64>() / self.count.len() as f64 };
        Merged {
            rgb: Rgb { width: self.reference.width, height: self.reference.height, data },
            used: self.frames,
            psnr_gain_estimate: DB_PER_DECADE * mean_count.log10() as f32,
        }
    }
}

/// One channel of an 8-bit picture, read with the edges repeated and bilinear interpolation.
#[derive(Clone, Copy)]
struct Plane<'a> {
    data: &'a [u8],
    width: usize,
    height: usize,
    /// Bytes per pixel: 1 for luminance, 3 for RGB.
    stride: usize,
    /// Which byte of the pixel this plane reads.
    channel: usize,
}

impl<'a> Plane<'a> {
    /// A plane over `data`, or `None` when the byte count is not `width × height × stride`.
    fn new(data: &'a [u8], width: usize, height: usize, stride: usize, channel: usize) -> Option<Self> {
        let len = width.checked_mul(height)?.checked_mul(stride)?;
        (len == data.len() && channel < stride).then_some(Plane { data, width, height, stride, channel })
    }

    /// The value at a whole-pixel position, with the position clamped to the picture.
    fn at(&self, x: i64, y: i64) -> f32 {
        let i = (clamp_index(y, self.height) * self.width + clamp_index(x, self.width)) * self.stride + self.channel;
        self.data.get(i).map_or(0.0, |&v| f32::from(v))
    }

    /// The value at a fractional position, interpolated bilinearly.
    fn bilinear(&self, x: f32, y: f32) -> f32 {
        let (x0, y0) = (x.floor(), y.floor());
        let (fx, fy) = (x - x0, y - y0);
        let (ix, iy) = (x0 as i64, y0 as i64);
        let top = self.at(ix, iy) * (1.0 - fx) + self.at(ix.saturating_add(1), iy) * fx;
        let bottom = self.at(ix, iy.saturating_add(1)) * (1.0 - fx) + self.at(ix.saturating_add(1), iy.saturating_add(1)) * fx;
        top * (1.0 - fy) + bottom * fy
    }
}

/// The central square of a picture: its origin and size.
#[derive(Clone, Copy)]
struct Crop {
    x0: usize,
    y0: usize,
    w: usize,
    h: usize,
}

/// The central `CROP_SIDE` square of a `width × height` picture, or the whole picture when it is
/// smaller.
fn central_crop(width: usize, height: usize) -> Crop {
    let w = width.min(CROP_SIDE);
    let h = height.min(CROP_SIDE);
    Crop { x0: (width - w) / 2, y0: (height - h) / 2, w, h }
}

/// `v` clamped to `0..n`, so a position outside the picture reads its nearest edge.
fn clamp_index(v: i64, n: usize) -> usize {
    let hi = (n as i64 - 1).max(0);
    v.clamp(0, hi) as usize
}

/// The move that makes `f` lie on `r`, in pixels: the whole-pixel best within the search, then
/// the parabola part. Zero when the best is not clearly better than no move.
fn best_move(r: Plane, f: Plane, crop: Crop) -> (f64, f64) {
    let zero = ssd_at(r, f, crop, 0, 0);
    let (mut best, mut best_ssd) = ((0i64, 0i64), zero);
    for my in -SEARCH_RADIUS..=SEARCH_RADIUS {
        for mx in -SEARCH_RADIUS..=SEARCH_RADIUS {
            let s = ssd_at(r, f, crop, mx, my);
            if s < best_ssd {
                best_ssd = s;
                best = (mx, my);
            }
        }
    }
    if best_ssd >= zero * (1.0 - MIN_GAIN_SHARE) {
        return (0.0, 0.0);
    }
    let (wx, wy) = best;
    let part_x = parabola_offset(ssd_at(r, f, crop, wx - 1, wy), best_ssd, ssd_at(r, f, crop, wx + 1, wy));
    let part_y = parabola_offset(ssd_at(r, f, crop, wx, wy - 1), best_ssd, ssd_at(r, f, crop, wx, wy + 1));
    (wx as f64 + part_x, wy as f64 + part_y)
}

/// Sum of squared luminance differences on the crop, with the frame moved by the whole pixels
/// (`mx`, `my`).
fn ssd_at(r: Plane, f: Plane, crop: Crop, mx: i64, my: i64) -> f64 {
    let mut total = 0.0f64;
    for row in 0..crop.h {
        let y = (crop.y0 + row) as i64;
        for col in 0..crop.w {
            let x = (crop.x0 + col) as i64;
            let d = f64::from(r.at(x, y) - f.at(x - mx, y - my));
            total += d * d;
        }
    }
    total
}

/// Mean absolute difference on the crop, with the frame moved by (`dx`, `dy`) and read
/// bilinearly.
fn residual_at(r: Plane, f: Plane, crop: Crop, dx: f32, dy: f32) -> f32 {
    let mut total = 0.0f64;
    for y in crop.y0..crop.y0 + crop.h {
        for x in crop.x0..crop.x0 + crop.w {
            let a = r.at(x as i64, y as i64);
            let b = f.bilinear(x as f32 - dx, y as f32 - dy);
            total += f64::from((a - b).abs());
        }
    }
    (total / (crop.w * crop.h).max(1) as f64) as f32
}

/// The vertex of the parabola through the values at offsets −1, 0 and +1, as an offset in
/// pixels, clamped to ±0.5. Zero when the middle value is not a minimum.
fn parabola_offset(minus: f64, centre: f64, plus: f64) -> f64 {
    let curvature = minus - 2.0 * centre + plus;
    if !(curvature.is_finite() && curvature > 0.0) {
        return 0.0;
    }
    let offset = 0.5 * (minus - plus) / curvature;
    if offset.is_finite() { offset.clamp(-0.5, 0.5) } else { 0.0 }
}

/// Median of |a − b| over the crop, one channel, in grey levels. The differences are whole
/// levels, so the median is read off a histogram and interpolated inside its bin, which takes a
/// bin of value v to cover v − ½ to v + ½. Taking the bin's whole value instead reads the median
/// half a level high, and every noise estimate about 5 % high.
fn median_abs_difference(r: Plane, f: Plane, crop: Crop) -> f64 {
    let mut hist = [0usize; LEVELS];
    for y in crop.y0..crop.y0 + crop.h {
        for x in crop.x0..crop.x0 + crop.w {
            let d = (r.at(x as i64, y as i64) - f.at(x as i64, y as i64)).abs() as usize;
            hist[d.min(LEVELS - 1)] += 1;
        }
    }
    let target = (crop.w * crop.h) as f64 / 2.0;
    let mut seen = 0usize;
    for (level, &n) in hist.iter().enumerate() {
        if n > 0 && (seen + n) as f64 > target {
            let within = (target - seen as f64) / n as f64;
            return (level as f64 - 0.5 + within).max(0.0);
        }
        seen += n;
    }
    0.0
}

/// The largest difference from the reference that joins the average, per channel: 3σ + 2.
fn limits(sigma: [f32; 3]) -> [f32; 3] {
    sigma.map(|s| {
        let s = if s.is_finite() { s.max(0.0) } else { 0.0 };
        SIGMA_MULTIPLE * s + SIGMA_FLOOR
    })
}

/// A sum over a count, rounded to the nearest grey level. The count is at least one, as the
/// reference always counts.
fn average(sum: u16, count: u8) -> u8 {
    let n = u32::from(count.max(1));
    let v = (u32::from(sum) * 2 + n) / (2 * n);
    u8::try_from(v).unwrap_or(u8::MAX)
}

/// A value in 0–255, rounded to the nearest whole number.
fn to_byte(v: f32) -> u8 {
    v.round().clamp(0.0, 255.0) as u8
}

/// Whether the byte count of an RGB picture matches its size.
fn valid_rgb(p: &Rgb) -> bool {
    Plane::new(&p.data, p.width as usize, p.height as usize, CHANNELS, 0).is_some()
}
