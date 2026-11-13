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
    /// Use the S-curve `0.5 − 0.5·cos(t^0.75·π)` in place of the gamma curve (a whiteboard, #299).
    pub s_curve: bool,
}

impl Tone {
    /// Measured against ten reference pairs at strength 60: black point 0.25, white point 0.97,
    /// gamma 1.4, colour 1.2 (#298).
    pub fn at(strength: u8) -> Tone {
        let k = f32::from(strength.min(100)) / 100.0;
        Tone { black: 0.42 * k, white: 1.0 - 0.05 * k, gamma: 1.0 + 0.67 * k, colour: 1.0 + 0.33 * k, s_curve: false }
    }

    /// This tone with a kind of document's shape on top (#299): its extra gamma added (a positive
    /// one darkens faint print), its colour factor multiplied in, and its S-curve if it has one.
    pub fn shaped(self, shape: super::Shape) -> Tone {
        let extra = if shape.extra_gamma.is_finite() { shape.extra_gamma } else { 0.0 };
        let factor = if shape.colour_factor.is_finite() { shape.colour_factor.clamp(0.0, 3.0) } else { 1.0 };
        Tone { gamma: (self.gamma + extra).clamp(MIN_GAMMA, MAX_GAMMA), colour: self.colour * factor, s_curve: shape.s_curve, ..self }
    }
}

/// The gamma a kind's extra gamma can take the curve to, at the least and the most.
const MIN_GAMMA: f32 = 0.3;
const MAX_GAMMA: f32 = 4.0;

/// The S-curve of a whiteboard (#299, from Zhang and He): lifts the board and darkens the marker
/// more steeply in the middle than a gamma curve.
pub(crate) fn s_curve(t: f32) -> f32 {
    0.5 - 0.5 * (t.clamp(0.0, 1.0).powf(0.75) * std::f32::consts::PI).cos()
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
    /// The level from which this page is drawn white ([`white_from`]): 1, or lower on a grainy
    /// page.
    white_from: f32,
}

/// A sample counts as paper for [`paper_spread`] when it is at least this bright against its
/// light: well above print, and low enough to keep the dark half of a grainy page's paper.
const PAPERISH: f32 = 0.7;
/// The paper's spread a page may have before its white point moves: 0.025 of the paper's own
/// brightness. The owner's ten reference photos measure 0.005 to 0.021 (the largest is a sheet in
/// the dappled shade of a tree), and dimmed, grainy copies of them 0.040 to 0.114 (#466).
pub(crate) const GRAIN_FREE: f32 = 0.025;
/// How far the white point comes down for each unit of spread over [`GRAIN_FREE`]: at 4, paper
/// up to about two and a half times the spread under its own level is still drawn white.
const GRAIN_REACH: f32 = 4.0;
/// The lowest white point the grain can bring about.
pub(crate) const GRAIN_WHITE_MIN: f32 = 0.80;

/// How much the paper's brightness varies from place to place after the light has been divided
/// out: the median distance of the paper samples (`lumas` at or over [`PAPERISH`]) from their
/// median, scaled to a standard deviation. On an evenly lit, clean page this is the page's fine
/// grain; a photo taken in dim light has several times more. 0 when too little of the page is
/// paper to say.
pub(crate) fn paper_spread(lumas: &[f32]) -> f32 {
    let mut paper: Vec<f32> = lumas.iter().copied().filter(|&l| l >= PAPERISH).collect();
    if paper.len() < 64 {
        return 0.0;
    }
    let middle = median(&mut paper);
    let mut off: Vec<f32> = paper.iter().map(|l| (l - middle).abs()).collect();
    1.4826 * median(&mut off)
}

/// The level from which a page whose paper varies by `spread` (see [`paper_spread`]) is drawn
/// white, as a share of the way from the black point to the white point: 1 for a page under
/// [`GRAIN_FREE`], lower by [`GRAIN_REACH`] for the spread over it, the white point never under
/// [`GRAIN_WHITE_MIN`].
///
/// The curve is steepest just under white, so the grain of a photo taken in dim light showed as
/// grey specks all over the paper (#466: on dimmed, grainy copies of the owner's photos 5 to 46 %
/// of the paper was not white). A page under [`GRAIN_FREE`] gives exactly 1, so every look made
/// before this is unchanged.
pub(crate) fn white_from(tone: &Tone, spread: f32) -> f32 {
    if !spread.is_finite() || spread <= GRAIN_FREE {
        return 1.0;
    }
    let white = (tone.white - GRAIN_REACH * (spread - GRAIN_FREE)).max(GRAIN_WHITE_MIN.min(tone.white));
    ((white - tone.black) / (tone.white - tone.black).max(1e-3)).clamp(0.3, 1.0)
}

/// The curve's input with everything from `white_from` up drawn white: unchanged up to
/// [`KNEE`] below that level, then rising smoothly to 1 at it. Print and mid greys keep their
/// tone; only the top of the range, where the paper and its grain are, moves.
pub(crate) fn lifted(t: f32, white_from: f32) -> f32 {
    if white_from >= 1.0 {
        return t;
    }
    let knee = (white_from - KNEE).max(0.0);
    if t <= knee {
        t
    } else if t >= white_from {
        1.0
    } else {
        let x = (t - knee) / (white_from - knee);
        t + (1.0 - white_from) * x * x
    }
}

/// How far below the level drawn white the curve starts to leave its own shape.
const KNEE: f32 = 0.2;

/// The tone curve as a table over 0–1, with everything from `white_from` up drawn white.
fn curve_table(tone: &Tone, white_from: f32) -> Vec<f32> {
    (0..=CURVE_STEPS)
        .map(|i| lifted(i as f32 / CURVE_STEPS as f32, white_from))
        .map(|t| if tone.s_curve { s_curve(t) } else { t.powf(tone.gamma) })
        .collect()
}

impl<'a> Renderer<'a> {
    pub fn new(page: &'a Rgb, light: &[Plane; 3], tone: Tone, keep_paper: bool) -> Renderer<'a> {
        let (w, h) = (page.width as usize, page.height as usize);
        let curve = curve_table(&tone, 1.0);
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
            white_from: 1.0,
        };
        let spread;
        (r.black, r.off, spread) = r.black_colour();
        r.inv_fade = 1.0 / (1.0 - r.black + 1e-3);
        // a grainy page's paper is drawn white from a lower level (#466)
        r.white_from = white_from(&tone, spread);
        if r.white_from < 1.0 {
            r.curve = curve_table(&tone, r.white_from);
        }
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
    /// samples): its luminance, and its distance from grey limited to [`BLACK_TINT_LIMIT`]. And,
    /// from the same samples, the paper's spread ([`paper_spread`]).
    fn black_colour(&self) -> (f32, [f32; 3], f32) {
        let (w, h) = (self.page.width as usize, self.page.height as usize);
        let step = (((w * h) as f64 / 1e6).sqrt().ceil() as usize).max(1);
        let mut light = vec![0f32; self.gain[0].w * 3];
        let mut samples: Vec<[f32; 3]> = Vec::new();
        for y in (step / 2..h).step_by(step) {
            self.light_row(y, &mut light);
            let row = &self.page.data[y * w * 3..(y + 1) * w * 3];
            samples.extend((step / 2..w).step_by(step).map(|x| self.flat(x, &row[x * 3..x * 3 + 3], &light)));
        }
        let mut lumas: Vec<f32> = samples.iter().map(|&s| luma(s)).collect();
        let spread = paper_spread(&lumas);
        let limit = percentile(&mut lumas, 2.0);
        let dark: Vec<[f32; 3]> = samples.into_iter().filter(|&s| luma(s) <= limit).collect();
        if dark.is_empty() {
            return (0.0, [0.0; 3], spread);
        }
        let d = [0, 1, 2].map(|c| median(&mut dark.iter().map(|s| s[c]).collect::<Vec<_>>()));
        let l = luma(d);
        (l, d.map(|v| (v - l).clamp(-BLACK_TINT_LIMIT, BLACK_TINT_LIMIT)), spread)
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

    /// `channels` bytes per pixel for the whole page, each row written by `fill`, on `threads`
    /// threads (row bands; the bytes are the same for any number).
    fn rows(&self, threads: usize, channels: usize, fill: impl Fn(usize, &mut [u8], &mut [f32]) + Sync) -> Vec<u8> {
        let (w, h) = (self.page.width as usize, self.page.height as usize);
        let mut data = vec![0u8; w * h * channels];
        let band = h.div_ceil(threads.clamp(1, 8)).max(1);
        let fill = &fill;
        std::thread::scope(|scope| {
            for (b, chunk) in data.chunks_mut(band * w * channels).enumerate() {
                scope.spawn(move || {
                    let mut light = vec![0f32; self.gain[0].w * 3];
                    for (i, row) in chunk.chunks_mut(w * channels).enumerate() {
                        fill(b * band + i, row, &mut light);
                    }
                });
            }
        });
        data
    }

    /// The whole page with the look, RGB8.
    pub fn render(&self, threads: usize) -> Vec<u8> {
        self.rows(threads, 3, |y, out, light| self.row(y, out, light))
    }

    /// The luminance of the page with the look, for the pixels marked in `wanted` (0 for the
    /// others; rows with none are skipped).
    pub fn toned_luma(&self, wanted: &[bool]) -> Vec<u8> {
        let (w, h) = (self.page.width as usize, self.page.height as usize);
        let mut out = vec![0u8; w * h];
        let (mut rgb, mut light) = (vec![0u8; w * 3], vec![0f32; self.gain[0].w * 3]);
        for y in (0..h).filter(|y| wanted[y * w..(y + 1) * w].contains(&true)) {
            self.row(y, &mut rgb, &mut light);
            for (x, px) in rgb.chunks_exact(3).enumerate().filter(|(x, _)| wanted[y * w + x]) {
                out[y * w + x] = crate::luma([px[0], px[1], px[2]]);
            }
        }
        out
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

#[cfg(test)]
mod grain_tests {
    //! A grainy page's paper is drawn white (#466), on pages drawn here. In this file, not with
    //! the look's other tests, because it tests this file's own functions.
    use super::*;
    use crate::enhance::{AutoParams, auto};

    /// A `w` × `h` page under a light `level` (0–1) of full brightness: white paper with bars of
    /// print, and `grain` grey levels of noise on every pixel and channel.
    fn page(w: usize, h: usize, level: f32, grain: f32, seed: u64) -> Rgb {
        let mut state = seed | 1;
        let mut normal = move || {
            // the sum of four uniform numbers: close enough to a bell for a test, and cheap
            let mut sum = 0.0f32;
            for _ in 0..4 {
                state = state.wrapping_mul(6_364_136_223_846_793_005).wrapping_add(1_442_695_040_888_963_407);
                sum += (state >> 40) as f32 / (1u64 << 24) as f32;
            }
            (sum - 2.0) * 1.732
        };
        let mut data = Vec::with_capacity(w * h * 3);
        for y in 0..h {
            for x in 0..w {
                let print = y % 40 < 6 && (60..w - 60).contains(&x) && (80..h - 80).contains(&y) && x % 23 < 15;
                let base = if print { 30.0 } else { 235.0 } * level;
                for _ in 0..3 {
                    data.push((base + grain * normal()).round().clamp(0.0, 255.0) as u8);
                }
            }
        }
        Rgb { width: w as u32, height: h as u32, data }
    }

    /// Share of the page's paper (every pixel that is not within 4 px of a bar of print) that is
    /// not drawn white, and the mean grey level of the print.
    fn drawn(look: &Rgb) -> (f32, f32) {
        let (w, h) = (look.width as usize, look.height as usize);
        let (mut paper, mut grey, mut ink, mut ink_sum) = (0usize, 0usize, 0usize, 0f32);
        for y in 0..h {
            for x in 0..w {
                let v = crate::luma([look.data[(y * w + x) * 3], look.data[(y * w + x) * 3 + 1], look.data[(y * w + x) * 3 + 2]]);
                let in_text = (60..w - 60).contains(&x) && (80..h - 80).contains(&y);
                if in_text && y % 40 < 6 && x % 23 < 15 {
                    if (1..5).contains(&(y % 40)) && (2..13).contains(&(x % 23)) {
                        ink += 1;
                        ink_sum += f32::from(v);
                    }
                } else if !in_text || (10..36).contains(&(y % 40)) {
                    paper += 1;
                    grey += usize::from(v < 240);
                }
            }
        }
        (grey as f32 / paper.max(1) as f32, ink_sum / ink.max(1) as f32)
    }

    #[test]
    fn the_spread_is_small_on_clean_paper_and_large_on_grainy_paper() {
        // paper within ±0.012 of its level, and a ninth of the samples print: under the free spread
        let clean: Vec<f32> = (0..4000).map(|i| if i % 9 == 0 { 0.2 } else { 1.0 + 0.004 * ((i % 7) as f32 - 3.0) }).collect();
        let spread = paper_spread(&clean);
        assert!((0.005..GRAIN_FREE).contains(&spread), "{spread}");
        // paper spread evenly over ±0.1: half of it is within 0.05 of the middle
        let grainy: Vec<f32> = (0..4001).map(|i| 0.9 + 0.2 * i as f32 / 4000.0).collect();
        let spread = paper_spread(&grainy);
        assert!((spread - 1.4826 * 0.05).abs() < 0.002, "{spread}");
        // too little paper to say
        assert_eq!(paper_spread(&[1.0; 10]), 0.0);
        assert_eq!(paper_spread(&[0.3; 500]), 0.0);
    }

    #[test]
    fn a_page_under_the_free_spread_keeps_its_curve_exactly() {
        let tone = Tone::at(60);
        for spread in [0.0, 0.01, GRAIN_FREE, f32::NAN] {
            assert_eq!(white_from(&tone, spread), 1.0, "{spread}");
        }
        for i in 0..=100 {
            let t = i as f32 / 100.0;
            assert_eq!(lifted(t, 1.0), t);
        }
        assert_eq!(curve_table(&tone, 1.0), (0..=CURVE_STEPS).map(|i| (i as f32 / CURVE_STEPS as f32).powf(tone.gamma)).collect::<Vec<_>>());
    }

    #[test]
    fn the_lift_leaves_print_alone_rises_without_a_step_and_stops_at_the_floor() {
        let tone = Tone::at(60);
        let from = white_from(&tone, 0.05);
        // 0.97 − 4 × 0.025 = 0.87, as a share of 0.25…0.97
        assert!((from - (0.87 - tone.black) / (tone.white - tone.black)).abs() < 1e-4, "{from}");
        let mut last = 0.0;
        for i in 0..=1000 {
            let t = i as f32 / 1000.0;
            let u = lifted(t, from);
            assert!(u >= last && u >= t && u <= 1.0, "{t}: {u} after {last}");
            assert!(u - last < 0.01, "a step at {t}: {last} to {u}");
            if t <= from - KNEE {
                assert_eq!(u, t);
            }
            if t >= from {
                assert_eq!(u, 1.0);
            }
            last = u;
        }
        // however grainy, the white point is not brought under the floor
        let floor = (GRAIN_WHITE_MIN - tone.black) / (tone.white - tone.black);
        assert!((white_from(&tone, 0.5) - floor).abs() < 1e-4);
    }

    /// The owner's case (#466), drawn: a page photographed in dim light, grainy. Without the
    /// lift a good part of its paper is grey specks; with it the paper is white and the print
    /// is as dark as before.
    #[test]
    fn a_dim_grainy_page_has_white_paper_and_the_same_print() {
        let params = AutoParams { denoise: false, sharpen: 0, ..AutoParams::default() };
        let clean = auto(&page(480, 640, 0.9, 0.5, 3), &params).unwrap();
        let (clean_grey, clean_ink) = drawn(&clean.rgb);
        assert!(clean_grey < 0.005, "{clean_grey}");

        let grainy = page(480, 640, 0.45, 8.0, 7);
        let look = auto(&grainy, &params).unwrap();
        let (grey, ink) = drawn(&look.rgb);
        println!("grainy page: {:.1} % of the paper not white, print at {ink:.0} (clean page: {clean_ink:.0})", 100.0 * grey);
        assert!(grey < 0.06, "{grey}");
        assert!((ink - clean_ink).abs() < 12.0, "{ink} against {clean_ink}");

        // the same page drawn with the curve as it was: the specks this is about
        let bg = crate::enhance::background::background(&grainy, false);
        let mut plain = Renderer::new(&grainy, &bg.light, Tone::at(params.strength), false);
        assert!(plain.white_from < 1.0);
        plain.white_from = 1.0;
        plain.curve = curve_table(&plain.tone, 1.0);
        let before = Rgb { width: grainy.width, height: grainy.height, data: plain.render(1) };
        let (grey_before, ink_before) = drawn(&before);
        println!("the same without the lift: {:.1} % not white, print at {ink_before:.0}", 100.0 * grey_before);
        assert!(grey_before > 0.08 && grey_before > 4.0 * grey, "{grey_before} against {grey}");
        assert!((ink - ink_before).abs() < 2.0, "{ink} against {ink_before}");
    }
}
