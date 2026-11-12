//! The full-resolution pass of the Auto look (#298, steps 5 and 6): divide by the local paper
//! colour, make black neutral, apply the tone curve, and put real colour back.

use crate::Rgb;

use super::plane::{Plane, lerp_taps, luma3, median, percentile};

/// The tone curve and colour strength for one strength setting.
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct Tone {
    pub black: f32,
    pub white: f32,
    pub gamma: f32,
    pub colour: f32,
}

impl Tone {
    /// Measured against ten reference pairs at strength 60: black point 0.25, white point 0.97,
    /// gamma 1.4, colour 1.2 (#298).
    pub fn at(strength: u8) -> Tone {
        let k = f32::from(strength.min(100)) / 100.0;
        Tone { black: 0.42 * k, white: 1.0 - 0.05 * k, gamma: 1.0 + 0.67 * k, colour: 1.0 + 0.33 * k }
    }
}

/// Black ink's colour may be at most this far from grey per channel before it is taken for
/// coloured ink and left alone.
const BLACK_TINT_LIMIT: f32 = 0.08;
/// Colour differences under the first value vanish, above the second they are kept in full.
const FAINT: (f32, f32) = (0.04, 0.12);
const CURVE_STEPS: usize = 1024;

fn luma(c: [f32; 3]) -> f32 {
    0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]
}

/// 0 below `a`, 1 above `a + 1 / inv_width`, smooth in between.
fn smoothstep(x: f32, a: f32, inv_width: f32) -> f32 {
    let t = ((x - a) * inv_width).clamp(0.0, 1.0);
    t * t * (3.0 - 2.0 * t)
}

/// Everything a row needs; shared by the threads.
pub(crate) struct Renderer<'a> {
    page: &'a Rgb,
    /// 1 / (255 × light) per channel at working size: a byte times this is the pixel over its
    /// paper colour. Interpolating the gain instead of the light saves three divisions per pixel
    /// and differs only in the second order, because the light is smooth.
    gain: [Plane; 3],
    xs: Vec<(usize, usize, f32)>,
    ys: Vec<(usize, usize, f32)>,
    /// The paper's own colour scaled to a maximum of 1 (all ones for white paper).
    tint: [f32; 3],
    /// Luminance of the page's black, and how far its colour is from grey.
    black: f32,
    off: [f32; 3],
    /// 1 / (1 − black) and 1 / (white − black), so a pixel needs no division.
    inv_fade: f32,
    inv_range: f32,
    tone: Tone,
    curve: Vec<f32>,
}

impl<'a> Renderer<'a> {
    pub fn new(page: &'a Rgb, light: &[Plane; 3], tone: Tone, keep_paper: bool) -> Renderer<'a> {
        let (w, h) = (page.width as usize, page.height as usize);
        let curve = (0..=CURVE_STEPS).map(|i| (i as f32 / CURVE_STEPS as f32).powf(tone.gamma)).collect();
        let tint = if keep_paper { paper_tint(light) } else { [1.0; 3] };
        let gain = [0, 1, 2].map(|c| light[c].map(|l| tint[c] / (255.0 * l.max(0.02))));
        let mut r = Renderer {
            page,
            gain,
            xs: lerp_taps(w, light[0].w),
            ys: lerp_taps(h, light[0].h),
            tint,
            black: 0.0,
            off: [0.0; 3],
            inv_fade: 1.0,
            inv_range: 1.0 / (tone.white - tone.black).max(1e-3),
            tone,
            curve,
        };
        (r.black, r.off) = r.black_colour();
        r.inv_fade = 1.0 / (1.0 - r.black + 1e-3);
        r
    }

    /// The gain for page row `y`, interleaved RGB at working width.
    fn light_row(&self, y: usize, into: &mut [f32]) {
        let (y0, y1, wy) = self.ys[y];
        for (c, plane) in self.gain.iter().enumerate() {
            let (a, b) = (plane.row(y0), plane.row(y1));
            for (x, (&top, &bottom)) in a.iter().zip(b).enumerate() {
                into[x * 3 + c] = top + (bottom - top) * wy;
            }
        }
    }

    /// The pixel divided by its paper colour: paper is 1 in every channel (or the paper's tint).
    fn flat(&self, x: usize, px: &[u8], light: &[f32]) -> [f32; 3] {
        let (x0, x1, wx) = self.xs[x];
        let (a, b) = (&light[x0 * 3..x0 * 3 + 3], &light[x1 * 3..x1 * 3 + 3]);
        [0, 1, 2].map(|c| (f32::from(px[c]) * (a[c] + (b[c] - a[c]) * wx)).clamp(0.0, 1.3 * self.tint[c]))
    }

    /// The colour of the darkest 2 % of the page (from a grid of at most about a million
    /// samples): its luminance, and its distance from grey limited to [`BLACK_TINT_LIMIT`].
    fn black_colour(&self) -> (f32, [f32; 3]) {
        let (w, h) = (self.page.width as usize, self.page.height as usize);
        let step = (((w * h) as f64 / 1e6).sqrt().ceil() as usize).max(1);
        let mut light = vec![0f32; self.gain[0].w * 3];
        let mut samples: Vec<[f32; 3]> = Vec::new();
        for y in (step / 2..h).step_by(step) {
            self.light_row(y, &mut light);
            let row = &self.page.data[y * w * 3..(y + 1) * w * 3];
            samples.extend((step / 2..w).step_by(step).map(|x| self.flat(x, &row[x * 3..x * 3 + 3], &light)));
        }
        let limit = percentile(&mut samples.iter().map(|&s| luma(s)).collect::<Vec<_>>(), 2.0);
        let dark: Vec<[f32; 3]> = samples.into_iter().filter(|&s| luma(s) <= limit).collect();
        if dark.is_empty() {
            return (0.0, [0.0; 3]);
        }
        let d = [0, 1, 2].map(|c| median(&mut dark.iter().map(|s| s[c]).collect::<Vec<_>>()));
        let l = luma(d);
        (l, d.map(|v| (v - l).clamp(-BLACK_TINT_LIMIT, BLACK_TINT_LIMIT)))
    }

    fn curve(&self, t: f32) -> f32 {
        let pos = t.clamp(0.0, 1.0) * CURVE_STEPS as f32;
        let i = (pos as usize).min(CURVE_STEPS - 1);
        self.curve[i] + (self.curve[i + 1] - self.curve[i]) * (pos - i as f32)
    }

    fn pixel(&self, flat: [f32; 3]) -> [u8; 3] {
        let fade = ((1.0 - luma(flat)) * self.inv_fade).clamp(0.0, 1.0);
        let n = [0, 1, 2].map(|c| (flat[c] - self.off[c] * fade).clamp(0.0, 1.3));
        let y = luma(n);
        let t = self.curve((y - self.tone.black) * self.inv_range);
        let chroma = n[0].max(n[1]).max(n[2]) - n[0].min(n[1]).min(n[2]);
        let keep = self.tone.colour * smoothstep(chroma, FAINT.0, 1.0 / (FAINT.1 - FAINT.0));
        // + 0.5 and cut: rounding for values that are never negative, without a library call
        n.map(|v| ((t + keep * (v - y)).clamp(0.0, 1.0) * 255.0 + 0.5) as u8)
    }

    /// Write page row `y` into `out` (RGB8); `light` is scratch of working width × 3.
    pub fn row(&self, y: usize, out: &mut [u8], light: &mut [f32]) {
        let w = self.page.width as usize;
        self.light_row(y, light);
        let row = &self.page.data[y * w * 3..(y + 1) * w * 3];
        for (x, (o, px)) in out.chunks_exact_mut(3).zip(row.chunks_exact(3)).enumerate() {
            o.copy_from_slice(&self.pixel(self.flat(x, px, light)));
        }
    }

    /// The whole page on `threads` threads (row bands; the bytes are the same for any number).
    pub fn render(&self, threads: usize) -> Vec<u8> {
        let (w, h) = (self.page.width as usize, self.page.height as usize);
        let mut data = vec![0u8; w * h * 3];
        let band = h.div_ceil(threads.clamp(1, 8)).max(1);
        std::thread::scope(|scope| {
            for (b, chunk) in data.chunks_mut(band * w * 3).enumerate() {
                scope.spawn(move || {
                    let mut light = vec![0f32; self.gain[0].w * 3];
                    for (i, row) in chunk.chunks_mut(w * 3).enumerate() {
                        self.row(b * band + i, row, &mut light);
                    }
                });
            }
        });
        data
    }
}

/// The colour of the lit paper (the brightest 15 % of the light map), scaled so its largest
/// channel is 1: what "keep the paper's colour" multiplies back in.
fn paper_tint(light: &[Plane; 3]) -> [f32; 3] {
    let y = luma3(light);
    let from = percentile(&mut y.d.clone(), 85.0);
    let p = [0, 1, 2].map(|c| median(&mut light[c].d.iter().zip(&y.d).filter(|&(_, &l)| l >= from).map(|(&v, _)| v).collect::<Vec<_>>()));
    let top = p[0].max(p[1]).max(p[2]).max(1e-3);
    p.map(|v| v / top)
}
