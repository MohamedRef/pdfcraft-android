//! Tests for `straightness.rs` (issues #288 and #302). Every page is drawn in code: rows of dark
//! word bars with gaps between the words, on light paper, about 1000 x 1400 pixels.

use crate::Luma;
use crate::straightness::{Straightness, line_straightness};

const W: usize = 1000;
const H: usize = 1400;
const PAPER: u8 = 240;
const INK: u8 = 40;
/// Top of the first line of text, in pixels.
const FIRST_TOP: f64 = 120.0;
/// Distance between neighbouring lines of text, in pixels.
const PITCH: f64 = 60.0;
/// Thickness of a line of text (a row of word bars), in pixels.
const BAR: f64 = 18.0;
/// Number of lines of text on a full page.
const LINES: usize = 19;
/// Word length and word gap in pixels: a word is 70 px, the gap after it 22 px.
const WORD: usize = 70;
const PERIOD: usize = 92;

/// The vertical offset of a line at relative position `t` (0 at the left edge, 1 at the right).
type Shape = dyn Fn(f64) -> f64;

fn level(_t: f64) -> f64 {
    0.0
}

/// A parabola with its middle `depth` pixels lower than its ends.
fn bow(depth: f64) -> impl Fn(f64) -> f64 {
    move |t: f64| depth * (1.0 - (2.0 * t - 1.0).powi(2))
}

/// A line that leans: `rise` pixels lower at the right edge than at the left.
fn lean(rise: f64) -> impl Fn(f64) -> f64 {
    move |t: f64| rise * t
}

/// Lines of word bars drawn between relative x positions `span`, each line bent by `shape`.
/// Pixel rows are inked when their centre falls inside the bar, so there is no anti-aliasing.
fn text_page(shape: &Shape, span: (f64, f64)) -> Luma {
    let mut data = vec![PAPER; W * H];
    for k in 0..LINES {
        let base = FIRST_TOP + k as f64 * PITCH;
        for x in 0..W {
            let t = (x as f64 + 0.5) / W as f64;
            let in_span = t >= span.0 && t < span.1;
            if !in_span || (x + 17 * k) % PERIOD >= WORD {
                continue;
            }
            let top = base + shape(t);
            for y in 0..H {
                let centre = y as f64 + 0.5;
                if centre >= top && centre < top + BAR {
                    data[y * W + x] = INK;
                }
            }
        }
    }
    Luma { width: W as u32, height: H as u32, data }
}

fn full_page(shape: &Shape) -> Luma {
    text_page(shape, (0.0, 1.0))
}

/// A small xorshift generator with a fixed seed: the only randomness in the tests.
struct Rng(u64);

impl Rng {
    fn next_u64(&mut self) -> u64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        self.0
    }

    /// A value in (0, 1].
    fn unit(&mut self) -> f64 {
        ((self.next_u64() >> 11) as f64 + 1.0) / (1u64 << 53) as f64
    }

    /// A normal deviate with mean 0 and standard deviation `sigma` (Box-Muller).
    fn gauss(&mut self, sigma: f64) -> f64 {
        let (u1, u2) = (self.unit(), self.unit());
        sigma * (-2.0 * u1.ln()).sqrt() * (2.0 * std::f64::consts::PI * u2).cos()
    }
}

/// Adds Gaussian noise of standard deviation `sigma` to every pixel, clamped to 0..=255.
fn add_noise(page: &Luma, sigma: f64, seed: u64) -> Luma {
    let mut rng = Rng(seed);
    let data = page.data.iter().map(|&v| (f64::from(v) + rng.gauss(sigma)).round().clamp(0.0, 255.0) as u8).collect();
    Luma { width: page.width, height: page.height, data }
}

/// Separable Gaussian blur of standard deviation `sigma`, with the edge pixels repeated.
fn blur(page: &Luma, sigma: f64) -> Luma {
    let radius = (3.0 * sigma).ceil() as isize;
    let kernel: Vec<f64> = (-radius..=radius).map(|i| (-((i * i) as f64) / (2.0 * sigma * sigma)).exp()).collect();
    let total: f64 = kernel.iter().sum();
    let (w, h) = (page.width as usize, page.height as usize);
    let src: Vec<f64> = page.data.iter().map(|&v| f64::from(v)).collect();
    let across = convolve(&src, w, h, &kernel, total, true);
    let down = convolve(&across, w, h, &kernel, total, false);
    let data = down.iter().map(|v| v.round().clamp(0.0, 255.0) as u8).collect();
    Luma { width: page.width, height: page.height, data }
}

/// One pass of the blur: along rows when `horizontal`, else down columns.
fn convolve(src: &[f64], w: usize, h: usize, kernel: &[f64], total: f64, horizontal: bool) -> Vec<f64> {
    let radius = (kernel.len() / 2) as isize;
    let mut out = vec![0.0; w * h];
    for y in 0..h {
        for x in 0..w {
            let mut acc = 0.0;
            for (ki, k) in kernel.iter().enumerate() {
                let off = ki as isize - radius;
                let (sx, sy) = if horizontal { (clamp_index(x as isize + off, w), y) } else { (x, clamp_index(y as isize + off, h)) };
                acc += k * src.get(sy * w + sx).copied().unwrap_or(0.0);
            }
            out[y * w + x] = acc / total;
        }
    }
    out
}

fn clamp_index(i: isize, len: usize) -> usize {
    i.clamp(0, len as isize - 1) as usize
}

fn assert_close(value: f64, want: f64, tol: f64, what: &str) {
    assert!((value - want).abs() <= tol, "{what}: {value} is not within {tol} of {want}");
}

fn measure(page: &Luma) -> Straightness {
    line_straightness(page).expect("a text page must give a measure")
}

#[test]
fn level_lines_give_px_under_0_6() {
    let s = measure(&full_page(&level));
    println!("level lines: px {:.3} bowed_px {:.3} lines {}", s.px, s.bowed_px, s.lines);
    assert!(s.px < 0.6, "level lines gave px {}", s.px);
}

#[test]
fn level_lines_are_all_followed_as_lines() {
    let s = measure(&full_page(&level));
    println!("level lines followed: {}", s.lines);
    assert_eq!(s.lines as usize, LINES);
}

#[test]
fn parabola_12_px_deep_gives_px_between_2_5_and_5_and_bowed_close_to_px() {
    let s = measure(&full_page(&bow(12.0)));
    println!("parabola 12 px: px {:.3} bowed_px {:.3} lines {}", s.px, s.bowed_px, s.lines);
    assert!((2.5..=5.0).contains(&s.px), "parabola px {} not in 2.5..=5", s.px);
    assert_close(s.bowed_px, s.px, 0.2, "parabola bowed_px against px");
}

#[test]
fn lean_of_10_px_across_the_page_gives_px_near_2_9_and_bowed_under_0_8() {
    let s = measure(&full_page(&lean(10.0)));
    println!("lean 10 px: px {:.3} bowed_px {:.3} lines {}", s.px, s.bowed_px, s.lines);
    assert_close(s.px, 10.0 / 12f64.sqrt(), 0.3, "lean px");
    assert!(s.bowed_px < 0.8, "lean bowed_px {} is not under 0.8", s.bowed_px);
}

#[test]
fn deeper_bow_gives_a_larger_px_than_a_shallower_one_in_order() {
    let shallow = measure(&full_page(&bow(4.0))).px;
    let middle = measure(&full_page(&bow(8.0))).px;
    let deep = measure(&full_page(&bow(12.0))).px;
    println!("bow depths 4, 8, 12 px: px {shallow:.3} {middle:.3} {deep:.3}");
    assert!(shallow < middle, "4 px bow {shallow} not below 8 px bow {middle}");
    assert!(middle < deep, "8 px bow {middle} not below 12 px bow {deep}");
}

#[test]
fn blank_page_gives_none() {
    let page = Luma { width: W as u32, height: H as u32, data: vec![255; W * H] };
    assert_eq!(line_straightness(&page), None);
}

#[test]
fn page_of_noise_gives_none() {
    let mut rng = Rng(0x5EED_0001);
    let data = (0..W * H).map(|_| (rng.next_u64() >> 56) as u8).collect();
    let page = Luma { width: W as u32, height: H as u32, data };
    let value = line_straightness(&page);
    println!("page of uniform noise: {value:?}");
    assert_eq!(value, None);
}

#[test]
fn mild_blur_and_noise_sigma_6_change_px_by_under_0_5() {
    let clean = measure(&full_page(&bow(12.0)));
    let page = add_noise(&blur(&full_page(&bow(12.0)), 1.5), 6.0, 0x5EED_0002);
    let noisy = measure(&page);
    println!("bow 12 px clean px {:.3}, blur 1.5 + noise 6 px {:.3} (change {:.3})", clean.px, noisy.px, (noisy.px - clean.px).abs());
    assert!((noisy.px - clean.px).abs() < 0.5, "noise and blur moved px by {}", noisy.px - clean.px);
}

#[test]
fn ruled_table_page_returns_a_value_without_panicking() {
    let mut page = text_page(&bow(12.0), (0.0, 1.0));
    let without = measure(&page);
    for j in 0..6 {
        let y = 300 + j * 180;
        for row in y..y + 2 {
            for x in 0..W {
                page.data[row * W + x] = INK;
            }
        }
    }
    for x in [100usize, 500, 900] {
        for y in 250..H {
            for col in x..x + 2 {
                page.data[y * W + col] = INK;
            }
        }
    }
    let value = line_straightness(&page);
    println!("bow 12 px text without rules: px {:.3}; with ruled table: {value:?}", without.px);
    assert!(value.is_some(), "a ruled table with text should still give a value");
}

#[test]
fn page_of_short_lines_returns_without_panicking() {
    let wide = line_straightness(&text_page(&level, (0.0, 0.62)));
    let narrow = line_straightness(&text_page(&level, (0.0, 0.3)));
    println!("lines over 62% of the width: {wide:?}");
    println!("lines over 30% of the width: {narrow:?}");
    assert!(wide.is_some(), "lines spanning 10 of 16 strips should be followed");
    assert_eq!(narrow, None, "lines spanning 5 of 16 strips should not be followed");
}

#[test]
fn page_with_only_two_lines_gives_none() {
    let mut page = full_page(&level);
    for (i, v) in page.data.iter_mut().enumerate() {
        if i / W > 200 {
            *v = PAPER;
        }
    }
    let value = line_straightness(&page);
    println!("two lines only: {value:?}");
    assert_eq!(value, None);
}

#[test]
fn same_page_gives_the_same_number_twice() {
    let page = full_page(&bow(12.0));
    assert_eq!(line_straightness(&page), line_straightness(&page));
}

#[test]
fn data_that_does_not_match_the_size_gives_none_without_panicking() {
    let short = Luma { width: W as u32, height: H as u32, data: vec![PAPER; 10] };
    let empty = Luma { width: 0, height: 0, data: Vec::new() };
    let thin = Luma { width: 4, height: 100, data: vec![PAPER; 400] };
    assert_eq!(line_straightness(&short), None);
    assert_eq!(line_straightness(&empty), None);
    assert_eq!(line_straightness(&thin), None);
}
