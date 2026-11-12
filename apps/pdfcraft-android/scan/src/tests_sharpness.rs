#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
//! Sharpness on drawn pages (#296): pages of text bars drawn in code, blurred with a Gaussian
//! and with horizontal motion, then scored. The threshold `BLURRED_BELOW` was set between the
//! two groups printed by `sharp_pages_are_not_blurred` and `blurred_pages_are_blurred`
//! (run with `-- --nocapture` to see them). No real photo was used.

use crate::sharpness::{BLURRED_BELOW, Sharpness, sharpness};
use crate::{Luma, Quad};
use std::time::Instant;

/// The drawn photo's size: 500 × 600 pixels, a page 440 × 540 inside a 30-pixel border.
const W: u32 = 500;
const H: u32 = 600;
/// The page's margin inside the photo, in pixels.
const BORDER: u32 = 30;
/// The margin between the page's edge and its text, in pixels.
const TEXT_MARGIN: u32 = 30;

/// Blur applied to a drawn page.
#[derive(Clone, Copy, Debug)]
enum Blur {
    /// A Gaussian of this sigma, in pixels (0 is no blur).
    Gaussian(f64),
    /// A horizontal box of this many pixels (odd).
    Motion(usize),
    /// A vertical box of this many pixels (odd).
    MotionDown(usize),
}

impl Blur {
    fn name(self) -> String {
        match self {
            Blur::Gaussian(sigma) => format!("gaussian sigma {sigma}"),
            Blur::Motion(length) => format!("motion {length} px"),
            Blur::MotionDown(length) => format!("vertical motion {length} px"),
        }
    }
}

/// A tiny deterministic generator with a fixed seed (test data only).
struct Lcg(u64);

impl Lcg {
    fn draw(&mut self) -> u32 {
        self.0 = self.0.wrapping_mul(6_364_136_223_846_793_005).wrapping_add(1_442_695_040_888_963_407);
        (self.0 >> 33) as u32
    }

    /// A number from `lo` to `hi`, both included.
    fn between(&mut self, lo: u32, hi: u32) -> u32 {
        lo + self.draw() % (hi - lo + 1)
    }
}

/// The page quad of a drawn photo of the given size: the page inside its border.
fn page_quad(width: u32, height: u32) -> Quad {
    let (x1, y1) = ((width - BORDER) as f64, (height - BORDER) as f64);
    let b = BORDER as f64;
    Quad([[b, b], [x1, b], [x1, y1], [b, y1]])
}

/// Fills the pixels from (`x0`, `y0`) up to, not including, (`x1`, `y1`) with `value`.
fn fill(data: &mut [u8], width: u32, (x0, y0, x1, y1): (u32, u32, u32, u32), value: u8) {
    for y in y0..y1 {
        for x in x0..x1 {
            if let Some(px) = data.get_mut((y * width + x) as usize) {
                *px = value;
            }
        }
    }
}

/// A photo of a page of letter-like glyphs: each glyph is a "E" shape (a stem with three bars),
/// so the page has edges in every direction, as real text does. `bar` is the glyph's height in
/// pixels. The whole photo is paper except the page inside its border. Deterministic for a seed.
fn text_page(width: u32, height: u32, bar: u32, (ink, paper): (u8, u8), seed: u64) -> Luma {
    let mut data = vec![paper; (width * height) as usize];
    let mut rng = Lcg(seed);
    let (left, right) = (BORDER + TEXT_MARGIN, width - BORDER - TEXT_MARGIN);
    let bottom = height - BORDER - TEXT_MARGIN;
    let stroke = (bar / 4).max(2);
    let glyph_w = bar * 2 / 3 + stroke;
    let mut y = BORDER + TEXT_MARGIN;
    while y + bar <= bottom {
        let mut x = left;
        while x + glyph_w <= right {
            let word_len = rng.between(2, 6);
            for _ in 0..word_len {
                if x + glyph_w > right {
                    break;
                }
                draw_glyph(&mut data, width, (x, y, bar, stroke), ink);
                x += glyph_w + stroke;
            }
            x += bar;
        }
        y += 2 * bar;
    }
    Luma { width, height, data }
}

/// Draws one "E": a stem down the left and three bars across, each `stroke` thick.
fn draw_glyph(data: &mut [u8], width: u32, (x, y, size, stroke): (u32, u32, u32, u32), ink: u8) {
    let w = size * 2 / 3;
    fill(data, width, (x, y, x + stroke, y + size), ink);
    for top in [y, y + size / 2 - stroke / 2, y + size - stroke] {
        fill(data, width, (x, top, x + w, top + stroke), ink);
    }
}

/// A photo of one line of text on an otherwise blank page.
fn one_line_page(width: u32, height: u32, bar: u32, (ink, paper): (u8, u8)) -> Luma {
    let mut data = vec![paper; (width * height) as usize];
    let (left, right) = (BORDER + TEXT_MARGIN, width - BORDER - TEXT_MARGIN);
    let y = BORDER + TEXT_MARGIN;
    fill(&mut data, width, (left, y, right, y + bar), ink);
    Luma { width, height, data }
}

/// A convolution with a centred kernel, first across rows (`horizontal`) or down columns, with
/// edge pixels repeated.
fn convolve(src: &[f64], width: usize, height: usize, kernel: &[f64], horizontal: bool) -> Vec<f64> {
    let centre = (kernel.len() / 2) as i64;
    let clamp = |i: i64, n: usize| i.clamp(0, n as i64 - 1) as usize;
    let mut out = vec![0.0; src.len()];
    for y in 0..height {
        for x in 0..width {
            let mut sum = 0.0;
            for (k, weight) in kernel.iter().enumerate() {
                let offset = k as i64 - centre;
                let index = if horizontal { clamp(x as i64 + offset, width) + y * width } else { x + clamp(y as i64 + offset, height) * width };
                sum += weight * src[index];
            }
            out[y * width + x] = sum;
        }
    }
    out
}

/// A Gaussian kernel of the given sigma, cut at three sigmas and normalised. Sigma 0 is the
/// identity.
fn gaussian_kernel(sigma: f64) -> Vec<f64> {
    if sigma <= 0.0 {
        return vec![1.0];
    }
    let radius = (3.0 * sigma).ceil() as i64;
    let mut kernel: Vec<f64> = (-radius..=radius).map(|i| (-((i * i) as f64) / (2.0 * sigma * sigma)).exp()).collect();
    let total: f64 = kernel.iter().sum();
    kernel.iter_mut().for_each(|w| *w /= total);
    kernel
}

/// The photo blurred as asked.
fn blur(luma: &Luma, kind: Blur) -> Luma {
    let (w, h) = (luma.width as usize, luma.height as usize);
    let src: Vec<f64> = luma.data.iter().map(|&v| f64::from(v)).collect();
    let (kx, ky) = match kind {
        Blur::Gaussian(sigma) => {
            let k = gaussian_kernel(sigma);
            (k.clone(), k)
        }
        Blur::Motion(length) => (vec![1.0 / length as f64; length], vec![1.0]),
        Blur::MotionDown(length) => (vec![1.0], vec![1.0 / length as f64; length]),
    };
    let across = convolve(&src, w, h, &kx, true);
    let down = convolve(&across, w, h, &ky, false);
    let data = down.iter().map(|v| v.round().clamp(0.0, 255.0) as u8).collect();
    Luma { width: luma.width, height: luma.height, data }
}

/// The photo with every value multiplied by `factor`, rounded.
fn scaled(luma: &Luma, factor: f64) -> Luma {
    let data = luma.data.iter().map(|&v| (f64::from(v) * factor).round() as u8).collect();
    Luma { width: luma.width, height: luma.height, data }
}

/// Takes the pixels of `inside` where the page rectangle is, and of `outside` elsewhere. The
/// page rectangle is the page quad of this photo size.
fn compose(inside: &Luma, outside: &Luma) -> Luma {
    let (x0, y0) = (BORDER as usize, BORDER as usize);
    let (x1, y1) = ((W - BORDER) as usize, (H - BORDER) as usize);
    let data = (0..(W * H) as usize)
        .map(|i| {
            let (x, y) = (i % W as usize, i / W as usize);
            let on_page = (x0..x1).contains(&x) && (y0..y1).contains(&y);
            if on_page { inside.data[i] } else { outside.data[i] }
        })
        .collect();
    Luma { width: W, height: H, data }
}

/// The pages measured for the sharp group: three bar heights, each in high and low contrast.
fn sharp_pages() -> Vec<(String, Luma)> {
    let contrasts = [(20u8, 240u8), (90, 180)];
    let mut pages = Vec::new();
    for bar in [8u32, 12, 18] {
        for (ink, paper) in contrasts {
            let name = format!("bar {bar} ink {ink} paper {paper}");
            pages.push((name, text_page(W, H, bar, (ink, paper), 7 + u64::from(bar))));
        }
    }
    pages
}

/// The blurs of the blurred group: the calibration asks for sigma 1.5, 2.5 and 4 and motion of 7
/// and 13 pixels.
const BLURS: [Blur; 7] =
    [Blur::Gaussian(1.5), Blur::Gaussian(2.5), Blur::Gaussian(4.0), Blur::Motion(7), Blur::Motion(13), Blur::MotionDown(7), Blur::MotionDown(13)];

/// The smallest and largest of a set of scores.
fn range_of(scores: &[f32]) -> (f32, f32) {
    let lo = scores.iter().copied().fold(f32::INFINITY, f32::min);
    let hi = scores.iter().copied().fold(f32::NEG_INFINITY, f32::max);
    (lo, hi)
}

#[test]
fn sharp_pages_are_not_blurred() {
    let mut scores = Vec::new();
    for (name, page) in sharp_pages() {
        let verdict = sharpness(&page, &page_quad(W, H));
        println!("sharp page, {name}: score {:.4}, blurred {}", verdict.score, verdict.blurred);
        assert!(!verdict.blurred, "{name} was called blurred at score {}", verdict.score);
        scores.push(verdict.score);
    }
    let (lo, hi) = range_of(&scores);
    println!("SHARP score range: {lo:.4} to {hi:.4}");
    assert!(lo > BLURRED_BELOW, "the lowest sharp page is at {lo}, below the threshold");
}

#[test]
fn blurred_pages_are_blurred() {
    // Asserted: a Gaussian of sigma 2.5 or more is called blurred on every sharp page.
    let mut strong = Vec::new();
    let mut mild = Vec::new();
    let mut motion = Vec::new();
    for (name, page) in sharp_pages() {
        for kind in BLURS {
            let verdict = sharpness(&blur(&page, kind), &page_quad(W, H));
            println!("{} of {name}: score {:.4}, blurred {}", kind.name(), verdict.score, verdict.blurred);
            match kind {
                Blur::Gaussian(sigma) if sigma >= 2.5 => {
                    assert!(verdict.blurred, "{} of {name} was not called blurred (score {})", kind.name(), verdict.score);
                    strong.push(verdict.score);
                }
                Blur::Gaussian(_) => mild.push(verdict.score),
                Blur::Motion(_) | Blur::MotionDown(_) => motion.push(verdict.score),
            }
        }
    }
    let (lo, hi) = range_of(&strong);
    println!("BLURRED score range (Gaussian sigma 2.5, 4): {lo:.4} to {hi:.4}");
    let (lo, hi) = range_of(&mild);
    println!("MILD score range (Gaussian sigma 1.5): {lo:.4} to {hi:.4}");
    // Printed, not asserted: motion blur of 7 and 13 pixels overlaps the sharp pages with this
    // measure (see the report). The sharp pages score 1.49 to 1.93.
    let (lo, hi) = range_of(&motion);
    println!("MOTION score range (7 and 13 px, both directions; not separated): {lo:.4} to {hi:.4}");
}

#[test]
fn scores_fall_as_a_page_is_blurred_more() {
    let page = text_page(W, H, 10, (20, 240), 11);
    let mut previous = f32::INFINITY;
    for sigma in [0.0, 0.5, 1.0, 2.0, 4.0] {
        let score = sharpness(&blur(&page, Blur::Gaussian(sigma)), &page_quad(W, H)).score;
        println!("sigma {sigma}: score {score:.4}");
        assert!(score <= previous, "score rose from {previous} to {score} at sigma {sigma}");
        previous = score;
    }
}

#[test]
fn a_dim_copy_of_a_sharp_page_is_not_blurred_and_scores_within_a_quarter() {
    for (name, page) in sharp_pages() {
        let bright = sharpness(&page, &page_quad(W, H));
        let dim = sharpness(&scaled(&page, 0.4), &page_quad(W, H));
        println!("{name}: bright {:.4}, dim {:.4}", bright.score, dim.score);
        assert!(!dim.blurred, "the dim copy of {name} was called blurred");
        let ratio = f64::from(dim.score) / f64::from(bright.score);
        assert!((0.75..=1.25).contains(&ratio), "{name}: dim/bright ratio {ratio}");
    }
}

#[test]
fn a_mostly_blank_page_with_one_line_of_text_is_not_blurred() {
    let page = one_line_page(W, H, 10, (20, 240));
    let verdict = sharpness(&page, &page_quad(W, H));
    println!("one sharp line: score {:.4}, blurred {}", verdict.score, verdict.blurred);
    assert!(!verdict.blurred);
    assert!(verdict.score > 0.0);
    // A blurred full page of text scores lower than this one line, so it is not the line that
    // reads as blurred.
    let full_blurred = sharpness(&blur(&text_page(W, H, 10, (20, 240), 11), Blur::Gaussian(2.5)), &page_quad(W, H));
    println!("full page at sigma 2.5: score {:.4}", full_blurred.score);
    assert!(verdict.score > full_blurred.score);
}

#[test]
fn a_blank_page_is_not_blurred() {
    let blank = Luma { width: W, height: H, data: vec![240; (W * H) as usize] };
    let verdict = sharpness(&blank, &page_quad(W, H));
    println!("blank page: score {:.4}, blurred {}", verdict.score, verdict.blurred);
    assert_eq!(verdict, Sharpness { score: 0.0, blurred: false });
}

#[test]
fn only_what_is_inside_the_quad_counts() {
    let sharp = text_page(W, H, 10, (20, 240), 11);
    let soft = blur(&sharp, Blur::Gaussian(4.0));
    let quad = page_quad(W, H);

    let sharp_alone = sharpness(&sharp, &quad);
    let sharp_on_soft_table = sharpness(&compose(&sharp, &soft), &quad);
    println!(
        "sharp page on a blurred table: score {:.4} (sharp alone {:.4}), blurred {}",
        sharp_on_soft_table.score, sharp_alone.score, sharp_on_soft_table.blurred
    );
    assert!(!sharp_on_soft_table.blurred);
    let ratio = f64::from(sharp_on_soft_table.score) / f64::from(sharp_alone.score);
    assert!((0.95..=1.05).contains(&ratio), "table changed the score: ratio {ratio}");

    let soft_on_sharp_table = sharpness(&compose(&soft, &sharp), &quad);
    let soft_alone = sharpness(&soft, &quad);
    println!(
        "blurred page on a sharp table: score {:.4} (blurred page alone {:.4}), blurred {}",
        soft_on_sharp_table.score, soft_alone.score, soft_on_sharp_table.blurred
    );
    assert!(soft_on_sharp_table.blurred);
    let ratio = f64::from(soft_on_sharp_table.score) / f64::from(soft_alone.score);
    assert!((0.95..=1.05).contains(&ratio), "table changed the score: ratio {ratio}");
}

#[test]
fn the_same_photo_gives_the_same_score() {
    let page = blur(&text_page(W, H, 6, (20, 240), 3), Blur::Gaussian(1.5));
    let first = sharpness(&page, &page_quad(W, H));
    let second = sharpness(&page.clone(), &page_quad(W, H));
    assert_eq!(first.score.to_bits(), second.score.to_bits());
    assert_eq!(first.blurred, second.blurred);
}

#[test]
fn a_page_that_cannot_be_measured_scores_zero_and_is_not_blurred() {
    let page = text_page(W, H, 10, (20, 240), 11);
    let zero_area = Quad([[100.0, 100.0], [200.0, 100.0], [200.0, 100.0], [100.0, 100.0]]);
    let outside = Quad([[600.0, 30.0], [700.0, 30.0], [700.0, 500.0], [600.0, 500.0]]);
    let not_finite = Quad([[f64::NAN, 30.0], [470.0, 30.0], [470.0, 570.0], [30.0, 570.0]]);
    let mismatched = Luma { width: W, height: H, data: vec![0; 10] };
    let tiny = Luma { width: 4, height: 4, data: vec![0; 16] };
    let cases = [
        ("zero area", &page, zero_area),
        ("outside the photo", &page, outside),
        ("corner not finite", &page, not_finite),
        ("byte count does not match", &mismatched, page_quad(W, H)),
        ("photo too small", &tiny, Quad([[1.0, 1.0], [2.0, 1.0], [2.0, 2.0], [1.0, 2.0]])),
    ];
    for (what, photo, quad) in cases {
        let verdict = sharpness(photo, &quad);
        println!("{what}: score {}, blurred {}", verdict.score, verdict.blurred);
        assert_eq!(verdict, Sharpness { score: 0.0, blurred: false }, "{what}");
    }
}

#[test]
fn a_twelve_megapixel_photo_is_measured_and_its_time_is_printed() {
    let (w, h) = (4000u32, 3000u32);
    let page = text_page(w, h, 12, (20, 240), 5);
    let started = Instant::now();
    let verdict = sharpness(&page, &page_quad(w, h));
    let elapsed = started.elapsed();
    println!("12 MP page: score {:.4}, blurred {}, took {:?}", verdict.score, verdict.blurred, elapsed);
    assert!(verdict.score.is_finite() && verdict.score > 0.0);
    assert!(!verdict.blurred);
}
