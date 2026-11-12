//! Black and white (#300): ink or paper for every pixel, decided from what is around it.
//!
//! The page's lighting is first evened out as for the Auto look, so paper is near white
//! everywhere. Then each pixel is compared with the middle between the lightest value near it
//! (the paper, within about 2.6 mm) and the darkest value very near it (the ink of the stroke it
//! may belong to, within about 0.4 mm). A pale pencil line and a bold heading each get the
//! threshold halfway up their own edge, so neither breaks up and neither grows fat. Where the
//! two values are close there is nothing to separate: blank paper stays white however noisy, and
//! the inside of a solid dark area is black because it is dark.

use crate::Rgb;

use super::background::background;
use super::plane::{Mask, Plane, lerp_taps, luma3};
use super::tone::{Renderer, Tone};
use super::{DEFAULT_STRENGTH, MIN_SIDE};

/// How to make a page black and white.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct BwParams {
    /// 0–100: how faint a stroke may be and still be kept (0: only strokes a quarter as dark as
    /// the paper is light; 100: a twelfth, at which noisy paper starts to show).
    pub strength: u8,
    /// Photographs and printed grey areas as a pattern of dots instead of solid black and white.
    pub dither_photos: bool,
    /// The page's resolution: the neighbourhoods are sizes on paper.
    pub dpi: f32,
    /// Threads for the lighting pass (the bits are the same for any number).
    pub threads: usize,
}

impl Default for BwParams {
    fn default() -> Self {
        BwParams { strength: DEFAULT_BW_STRENGTH, dither_photos: true, dpi: 300.0, threads: 1 }
    }
}

/// The strength that keeps a pencil line a fifth as dark as the paper is light.
pub const DEFAULT_BW_STRENGTH: u8 = 55;

/// One bit per pixel, as a 1-bit PNG stores it: rows from the top, each padded to a whole byte,
/// the leftmost pixel in the highest bit, 1 for white.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Bitmap1 {
    pub width: u32,
    pub height: u32,
    pub data: Vec<u8>,
}

impl Bitmap1 {
    /// Bytes per row.
    pub fn stride(&self) -> usize {
        (self.width as usize).div_ceil(8)
    }

    /// Whether the pixel is ink.
    pub fn is_black(&self, x: u32, y: u32) -> bool {
        self.data[y as usize * self.stride() + x as usize / 8] & (0x80 >> (x % 8)) == 0
    }

    /// One byte per pixel: 0 for ink, 255 for paper.
    pub fn to_grey(&self) -> Vec<u8> {
        let (w, stride) = (self.width as usize, self.stride());
        self.data.chunks_exact(stride.max(1)).flat_map(|row| (0..w).map(|x| if row[x / 8] & (0x80 >> (x % 8)) == 0 { 0 } else { 255 })).collect()
    }

    fn pack(ink: &[bool], w: usize, h: usize) -> Bitmap1 {
        let stride = w.div_ceil(8);
        let mut data = vec![0u8; stride * h];
        for (row, bits) in ink.chunks_exact(w.max(1)).zip(data.chunks_exact_mut(stride.max(1))) {
            for (x, &black) in row.iter().enumerate() {
                if !black {
                    bits[x / 8] |= 0x80 >> (x % 8);
                }
            }
        }
        Bitmap1 { width: w as u32, height: h as u32, data }
    }
}

/// The paper's neighbourhood at 300 dpi, in pixels: 2.6 mm, a little more than a line of text is
/// high, so there is paper in it wherever there is text.
const WIDE: f32 = 31.0;
/// The stroke's neighbourhood at 300 dpi: 0.76 mm. Small, so a pale line next to bold print is
/// measured against its own darkness and not the print's.
const NEAR: f32 = 9.0;
/// Light and dark closer than this (in grey levels of 255) at strength 0 are not ink and paper...
const GATE: f32 = 60.0;
/// ...and this much closer for each step of strength.
const GATE_PER_STRENGTH: f32 = 0.4;
/// Darker than this share of white is ink whatever is around it: the inside of a solid area.
const DARK: u8 = 102;
/// Ink specks of at most this many pixels with no other ink near them are noise.
const SPECK: usize = 3;
/// 8 × 8 ordered dither thresholds (Bayer), 0–63.
const BAYER: [[u8; 8]; 8] = [
    [0, 32, 8, 40, 2, 34, 10, 42],
    [48, 16, 56, 24, 50, 18, 58, 26],
    [12, 44, 4, 36, 14, 46, 6, 38],
    [60, 28, 52, 20, 62, 30, 54, 22],
    [3, 35, 11, 43, 1, 33, 9, 41],
    [51, 19, 59, 27, 49, 17, 57, 25],
    [15, 47, 7, 39, 13, 45, 5, 37],
    [63, 31, 55, 23, 61, 29, 53, 21],
];

/// An odd neighbourhood size for `at_300` pixels at 300 dpi, within `range`.
pub(super) fn window(at_300: f32, dpi: f32, range: (usize, usize)) -> usize {
    let dpi = if dpi.is_finite() && dpi > 0.0 { dpi } else { 300.0 };
    ((at_300 * dpi / 300.0).round() as usize | 1).clamp(range.0, range.1)
}

/// Each pixel a quarter of each neighbour and half of itself, along rows then columns (a blur of
/// 0.7 pixels): the lightest and darkest values nearby are then not single noisy pixels.
pub(super) fn soften(src: &[u8], w: usize, h: usize) -> Vec<u8> {
    let mut rows = vec![0u8; src.len()];
    for (out, row) in rows.chunks_exact_mut(w).zip(src.chunks_exact(w)) {
        for x in 0..w {
            let (l, r) = (row[x.saturating_sub(1)], row[(x + 1).min(w - 1)]);
            out[x] = ((u16::from(l) + 2 * u16::from(row[x]) + u16::from(r) + 2) / 4) as u8;
        }
    }
    let mut out = vec![0u8; src.len()];
    for y in 0..h {
        let (above, below) = (y.saturating_sub(1) * w, (y + 1).min(h - 1) * w);
        for x in 0..w {
            out[y * w + x] = ((u16::from(rows[above + x]) + 2 * u16::from(rows[y * w + x]) + u16::from(rows[below + x]) + 2) / 4) as u8;
        }
    }
    out
}

/// Which of a neighbourhood's values is wanted.
pub(super) trait Pick: Copy {
    fn of(a: u8, b: u8) -> u8;
}

/// The lightest value.
#[derive(Clone, Copy)]
pub(super) struct Lightest;
/// The darkest value.
#[derive(Clone, Copy)]
pub(super) struct Darkest;

impl Pick for Lightest {
    fn of(a: u8, b: u8) -> u8 {
        a.max(b)
    }
}

impl Pick for Darkest {
    fn of(a: u8, b: u8) -> u8 {
        a.min(b)
    }
}

/// Where a neighbourhood of `size` (odd) around position `i` of `len` gets its value from, with
/// the positions in blocks of `size`: a run from the start of a block up to `last`, a run from
/// `first` to the end of a block, or both. The neighbourhood is cut off at the borders.
#[derive(Clone, Copy)]
enum Runs {
    /// The neighbourhood starts a block: the run up to `last` is all of it.
    Forward(usize),
    /// It ends a block (only at the far border, where it is cut off).
    Backward(usize),
    /// It lies across two blocks.
    Both(usize, usize),
}

fn runs(i: usize, len: usize, size: usize) -> Runs {
    let (first, last) = (i.saturating_sub(size / 2), (i + size / 2).min(len - 1));
    if first / size != last / size {
        Runs::Both(first, last)
    } else if first % size == 0 {
        Runs::Forward(last)
    } else {
        Runs::Backward(first)
    }
}

/// The lightest or darkest value within `size` pixels along each row. The cost does not grow
/// with `size`: every value is combined once with those before it in its block and once with
/// those after it, and a neighbourhood is at most one run of each kind.
fn extreme_rows<P: Pick>(src: &[u8], w: usize, size: usize) -> Vec<u8> {
    let mut out = vec![0u8; src.len()];
    let (mut forward, mut backward) = (vec![0u8; w], vec![0u8; w]);
    // the same for every row
    let plan: Vec<Runs> = (0..w).map(|x| runs(x, w, size)).collect();
    for (row, out) in src.chunks_exact(w).zip(out.chunks_exact_mut(w)) {
        for ((values, forward), backward) in row.chunks(size).zip(forward.chunks_mut(size)).zip(backward.chunks_mut(size)) {
            let mut run = values[0];
            for (f, &v) in forward.iter_mut().zip(values) {
                run = P::of(run, v);
                *f = run;
            }
            let mut run = values[values.len() - 1];
            for (b, &v) in backward.iter_mut().zip(values).rev() {
                run = P::of(run, v);
                *b = run;
            }
        }
        for (o, run) in out.iter_mut().zip(&plan) {
            *o = match *run {
                Runs::Forward(last) => forward[last],
                Runs::Backward(first) => backward[first],
                Runs::Both(first, last) => P::of(backward[first], forward[last]),
            };
        }
    }
    out
}

/// The same down each column, a whole row at a time so the work stays in step with memory.
fn extreme_columns<P: Pick>(src: &[u8], w: usize, h: usize, size: usize) -> Vec<u8> {
    let combine = |into: &mut [u8], a: &[u8], b: &[u8]| into.iter_mut().zip(a.iter().zip(b)).for_each(|(o, (&a, &b))| *o = P::of(a, b));
    let mut forward = src.to_vec();
    for y in (1..h).filter(|y| y % size != 0) {
        let (above, here) = forward.split_at_mut(y * w);
        combine(&mut here[..w], &above[(y - 1) * w..], &src[y * w..(y + 1) * w]);
    }
    let mut backward = src.to_vec();
    for y in (0..h.saturating_sub(1)).rev().filter(|y| (y + 1) % size != 0) {
        let (here, below) = backward.split_at_mut((y + 1) * w);
        combine(&mut here[y * w..], &below[..w], &src[y * w..(y + 1) * w]);
    }
    let mut out = vec![0u8; src.len()];
    for (y, out) in out.chunks_exact_mut(w).enumerate() {
        match runs(y, h, size) {
            Runs::Forward(last) => out.copy_from_slice(&forward[last * w..(last + 1) * w]),
            Runs::Backward(first) => out.copy_from_slice(&backward[first * w..(first + 1) * w]),
            Runs::Both(first, last) => combine(out, &backward[first * w..(first + 1) * w], &forward[last * w..(last + 1) * w]),
        }
    }
    out
}

/// The lightest or darkest value in the `size` × `size` neighbourhood of every pixel.
pub(super) fn extreme<P: Pick>(src: &[u8], w: usize, h: usize, size: usize) -> Vec<u8> {
    extreme_columns::<P>(&extreme_rows::<P>(src, w, size), w, h, size)
}

/// Ink pixels of at most [`SPECK`] pixels together, with no other ink within two pixels of
/// them, are removed. The dot of an "i" is larger at any resolution a page is kept at.
pub(super) fn despeckle(ink: &mut [bool], w: usize, h: usize) {
    let mut member = Vec::with_capacity(SPECK + 1);
    for start in 0..ink.len() {
        if !ink[start] {
            continue;
        }
        // the connected pixels, up to one more than a speck has
        member.clear();
        member.push(start);
        let mut next = 0;
        while next < member.len() && member.len() <= SPECK {
            let (x, y) = (member[next] % w, member[next] / w);
            next += 1;
            for ny in y.saturating_sub(1)..=(y + 1).min(h - 1) {
                for nx in x.saturating_sub(1)..=(x + 1).min(w - 1) {
                    let n = ny * w + nx;
                    if ink[n] && !member.contains(&n) && member.len() <= SPECK {
                        member.push(n);
                    }
                }
            }
        }
        if member.len() > SPECK {
            continue;
        }
        let (x0, x1) = (member.iter().map(|m| m % w).min().unwrap_or(0), member.iter().map(|m| m % w).max().unwrap_or(0));
        let (y0, y1) = (member.iter().map(|m| m / w).min().unwrap_or(0), member.iter().map(|m| m / w).max().unwrap_or(0));
        let alone = (y0.saturating_sub(2)..=(y1 + 2).min(h - 1))
            .all(|y| (x0.saturating_sub(2)..=(x1 + 2).min(w - 1)).all(|x| !ink[y * w + x] || member.contains(&(y * w + x))));
        if alone {
            for &m in &member {
                ink[m] = false;
            }
        }
    }
}

/// Ink or paper for every pixel of an evenly lit luminance (white paper near 255). `photo` marks
/// pixels to be dithered from `toned` instead (both `None` for none).
pub(super) fn threshold(luma: &[u8], w: usize, h: usize, params: &BwParams, dither: Option<(&[bool], &[u8])>) -> Vec<bool> {
    let soft = soften(luma, w, h);
    let lightest = extreme::<Lightest>(&soft, w, h, window(WIDE, params.dpi, (15, 63)));
    let darkest = extreme::<Darkest>(&soft, w, h, window(NEAR, params.dpi, (5, 19)));
    drop(soft);
    let gate = (GATE - GATE_PER_STRENGTH * f32::from(params.strength.min(100))).round() as u16;
    let mut ink: Vec<bool> = (0..luma.len())
        .map(|i| {
            let (hi, lo, y) = (u16::from(lightest[i]), u16::from(darkest[i].min(lightest[i])), u16::from(luma[i]));
            y < u16::from(DARK) || (hi - lo >= gate && 2 * y < hi + lo)
        })
        .collect();
    drop((lightest, darkest));
    despeckle(&mut ink, w, h);
    if let Some((photo, toned)) = dither {
        for (i, black) in ink.iter_mut().enumerate().filter(|(i, _)| photo[*i]) {
            // 0–255 against thresholds at the middles of 64 equal steps
            *black = u16::from(toned[i]) * 64 < (u16::from(BAYER[(i / w) % 8][(i % w) % 8]) * 2 + 1) * 255 / 2;
        }
    }
    ink
}

/// The page in black and white. `None` when the page's buffer doesn't match its size.
pub fn black_white(page: &Rgb, params: &BwParams) -> Option<Bitmap1> {
    let (w, h) = (page.width as usize, page.height as usize);
    if page.data.len() != w.checked_mul(h)?.checked_mul(3)? || w == 0 || h == 0 {
        return None;
    }
    // too small to tell paper from ink by its surroundings: dark is ink
    if w < MIN_SIDE || h < MIN_SIDE {
        let ink: Vec<bool> = page.data.chunks_exact(3).map(|p| crate::luma([p[0], p[1], p[2]]) < 128).collect();
        return Some(Bitmap1::pack(&ink, w, h));
    }
    let bg = background(page, params.threads > 1);
    let luma = even_luma(page, &bg.light, params.threads);
    let photos = (params.dither_photos && bg.objects.data.iter().any(|&o| o)).then(|| photo_pixels(&bg.objects, w, h));
    // the photographs' own tones, as the Auto look gives them
    let toned = photos.as_ref().map(|photo| Renderer::new(page, &bg.light, Tone::at(DEFAULT_STRENGTH), false).toned_luma(photo));
    let ink = threshold(&luma, w, h, params, photos.as_deref().zip(toned.as_deref()));
    Some(Bitmap1::pack(&ink, w, h))
}

/// The page's luminance over its paper's: one byte per pixel, 255 for paper and anything
/// lighter. The paper's light is taken as one grey value per place (its colour does not matter
/// to black and white), stretched from working size along each row and between rows.
fn even_luma(page: &Rgb, light: &[Plane; 3], threads: usize) -> Vec<u8> {
    let (w, h) = (page.width as usize, page.height as usize);
    // 255 / the light's luminance: a luminance byte times this is 0–255 with paper at 255
    let gain = luma3(light).map(|l| 1.0 / l.max(0.02));
    let (xs, ys) = (lerp_taps(w, gain.w), lerp_taps(h, gain.h));
    let mut out = vec![0u8; w * h];
    let band = h.div_ceil(threads.clamp(1, 8)).max(1);
    let (gain, xs, ys) = (&gain, &xs, &ys);
    std::thread::scope(|scope| {
        for (b, chunk) in out.chunks_mut(band * w).enumerate() {
            scope.spawn(move || {
                let mut across = vec![0f32; w];
                for (i, row) in chunk.chunks_exact_mut(w).enumerate() {
                    let y = b * band + i;
                    let (y0, y1, wy) = ys[y];
                    let (top, bottom) = (gain.row(y0), gain.row(y1));
                    for (g, &(x0, x1, wx)) in across.iter_mut().zip(xs) {
                        let (a, b) = (top[x0] + (bottom[x0] - top[x0]) * wy, top[x1] + (bottom[x1] - top[x1]) * wy);
                        *g = a + (b - a) * wx;
                    }
                    let pixels = &page.data[y * w * 3..(y + 1) * w * 3];
                    for ((o, px), g) in row.iter_mut().zip(pixels.chunks_exact(3)).zip(&across) {
                        *o = (f32::from(crate::luma([px[0], px[1], px[2]])) * g + 0.5).min(255.0) as u8;
                    }
                }
            });
        }
    });
    out
}

/// The objects mask (at working size) for every pixel of a `w` × `h` page.
fn photo_pixels(objects: &Mask, w: usize, h: usize) -> Vec<bool> {
    let column: Vec<usize> = (0..w).map(|x| (x * objects.width / w).min(objects.width - 1)).collect();
    let mut out = Vec::with_capacity(w * h);
    for y in 0..h {
        let row = &objects.data[(y * objects.height / h).min(objects.height - 1) * objects.width..];
        out.extend(column.iter().map(|&x| row[x]));
    }
    out
}
