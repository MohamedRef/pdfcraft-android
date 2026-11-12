//! Black and white (#300) on synthetic pages whose ink is known pixel by pixel.

use super::bw::{Bitmap1, BwParams, Darkest, Lightest, black_white, despeckle, extreme, soften, threshold, window};
use crate::Rgb;

struct Rng(u64);

impl Rng {
    fn unit(&mut self) -> f64 {
        self.0 = self.0.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        (self.0 >> 11) as f64 / (1u64 << 53) as f64
    }

    fn below(&mut self, n: usize) -> usize {
        (self.unit() * n as f64) as usize
    }

    fn normal(&mut self) -> f64 {
        (0..12).map(|_| self.unit()).sum::<f64>() - 6.0
    }
}

/// How much of each pixel is covered by what was drawn (0–1), from 4 × 4 samples per pixel.
struct Sheet {
    w: usize,
    h: usize,
    cover: Vec<f32>,
}

impl Sheet {
    fn new(w: usize, h: usize) -> Sheet {
        Sheet { w, h, cover: vec![0.0; w * h] }
    }

    /// Cover the pixels of the box `(x0, y0)–(x1, y1)` where `inside` says so, `by` at most.
    fn draw(&mut self, (x0, y0, x1, y1): (f64, f64, f64, f64), by: f32, inside: impl Fn(f64, f64) -> bool) {
        let (xa, xb) = ((x0.floor().max(0.0)) as usize, (x1.ceil().min(self.w as f64)) as usize);
        let (ya, yb) = ((y0.floor().max(0.0)) as usize, (y1.ceil().min(self.h as f64)) as usize);
        for y in ya..yb {
            for x in xa..xb {
                let hits = (0..16).filter(|k| inside(x as f64 + (f64::from(k % 4) + 0.5) / 4.0, y as f64 + (f64::from(k / 4) + 0.5) / 4.0)).count();
                let at = &mut self.cover[y * self.w + x];
                *at = at.max(by * hits as f32 / 16.0);
            }
        }
    }

    fn bar(&mut self, x0: f64, y0: f64, x1: f64, y1: f64, by: f32) {
        self.draw((x0, y0, x1, y1), by, |x, y| x >= x0 && x < x1 && y >= y0 && y < y1);
    }

    fn dot(&mut self, cx: f64, cy: f64, r: f64, by: f32) {
        self.draw((cx - r, cy - r, cx + r, cy + r), by, |x, y| (x - cx).powi(2) + (y - cy).powi(2) < r * r);
    }

    /// A straight stroke from `a` to `b`, `width` wide.
    fn stroke(&mut self, a: (f64, f64), b: (f64, f64), width: f64, by: f32) {
        let (dx, dy) = (b.0 - a.0, b.1 - a.1);
        let len2 = (dx * dx + dy * dy).max(1e-9);
        let half = width / 2.0;
        let bounds = (a.0.min(b.0) - half, a.1.min(b.1) - half, a.0.max(b.0) + half, a.1.max(b.1) + half);
        self.draw(bounds, by, |x, y| {
            let t = (((x - a.0) * dx + (y - a.1) * dy) / len2).clamp(0.0, 1.0);
            (x - a.0 - t * dx).powi(2) + (y - a.1 - t * dy).powi(2) < half * half
        });
    }

    /// Lines of letter-like marks: stems, bars, diagonals and bowls of `stem` width in cells
    /// `size` high, from `(x, y)` to the right edge less a margin.
    fn text(&mut self, x: f64, y: f64, size: f64, stem: f64, lines: usize, rng: &mut Rng) {
        for line in 0..lines {
            let top = y + line as f64 * size * 1.6;
            let mut left = x;
            while left + size < self.w as f64 - x {
                if rng.below(6) == 0 {
                    left += size * 0.6;
                    continue;
                }
                let (w, h) = (size * 0.55, size);
                match rng.below(5) {
                    0 => {
                        self.stroke((left, top), (left, top + h), stem, 1.0);
                        self.stroke((left, top + h * 0.5), (left + w, top + h * 0.5), stem, 1.0);
                        self.stroke((left + w, top + h * 0.5), (left + w, top + h), stem, 1.0);
                    }
                    1 => {
                        self.stroke((left, top + h), (left + w / 2.0, top), stem, 1.0);
                        self.stroke((left + w / 2.0, top), (left + w, top + h), stem, 1.0);
                    }
                    2 => {
                        self.stroke((left, top), (left + w, top), stem, 1.0);
                        self.stroke((left + w / 2.0, top), (left + w / 2.0, top + h), stem, 1.0);
                    }
                    3 => {
                        self.stroke((left, top + h * 0.35), (left, top + h), stem, 1.0);
                        self.stroke((left, top + h * 0.35), (left + w, top + h * 0.35), stem, 1.0);
                        self.stroke((left + w, top + h * 0.35), (left + w, top + h), stem, 1.0);
                        self.stroke((left, top + h), (left + w, top + h), stem, 1.0);
                    }
                    _ => {
                        self.stroke((left + w / 2.0, top + h * 0.35), (left + w / 2.0, top + h), stem, 1.0);
                        self.dot(left + w / 2.0, top + h * 0.1, stem * 0.6, 1.0);
                    }
                }
                left += w + size * 0.25;
            }
        }
    }

    /// The sheet as a camera's evenly lit luminance: paper `paper` (of 255) times a lighting that
    /// varies by `uneven` either way across the page, ink a twelfth as light, blurred by 0.6
    /// pixels, with `noise` grey levels of noise.
    fn luminance(&self, paper: f64, uneven: f64, noise: f64, seed: u64) -> Vec<u8> {
        let (w, h) = (self.w, self.h);
        let mut v: Vec<f32> = (0..w * h)
            .map(|i| {
                let (x, y) = ((i % w) as f64 / w as f64, (i / w) as f64 / h as f64);
                let light = 1.0 + uneven * (std::f64::consts::TAU * (0.8 * x + 0.1)).sin() * (std::f64::consts::TAU * 0.6 * y).cos();
                (paper * light * (1.0 - 0.92 * f64::from(self.cover[i]))) as f32
            })
            .collect();
        // a lens: 0.6 pixels of blur
        let kernel = [0.0127f32, 0.2219, 0.5308, 0.2219, 0.0127];
        for horizontal in [true, false] {
            let src = v.clone();
            for y in 0..h {
                for x in 0..w {
                    v[y * w + x] = (0..5)
                        .map(|k| {
                            let (sx, sy) =
                                if horizontal { ((x + k).saturating_sub(2).min(w - 1), y) } else { (x, (y + k).saturating_sub(2).min(h - 1)) };
                            kernel[k] * src[sy * w + sx]
                        })
                        .sum();
                }
            }
        }
        let mut rng = Rng(seed);
        v.iter().map(|&p| (f64::from(p) + noise * rng.normal()).round().clamp(0.0, 255.0) as u8).collect()
    }

    /// Pixels at least half covered.
    fn ink(&self) -> Vec<bool> {
        self.cover.iter().map(|&c| c >= 0.5).collect()
    }
}

/// Precision, recall and their harmonic mean of `found` against `truth`, over the pixels `count`
/// allows.
fn f_measure(found: &[bool], truth: &[bool], count: impl Fn(usize) -> bool) -> (f64, f64, f64) {
    let (mut both, mut f, mut t) = (0usize, 0usize, 0usize);
    for i in (0..found.len()).filter(|&i| count(i)) {
        both += usize::from(found[i] && truth[i]);
        f += usize::from(found[i]);
        t += usize::from(truth[i]);
    }
    let (p, r) = (both as f64 / f.max(1) as f64, both as f64 / t.max(1) as f64);
    (p, r, 2.0 * p * r / (p + r).max(1e-9))
}

fn params(strength: u8, dpi: f32) -> BwParams {
    BwParams { strength, dither_photos: false, dpi, threads: 1 }
}

#[test]
fn the_lightest_and_darkest_nearby_equal_a_direct_search() {
    let mut rng = Rng(1);
    for (w, h) in [(37usize, 29usize), (64, 64), (5, 70), (1, 9), (9, 1)] {
        let src: Vec<u8> = (0..w * h).map(|_| rng.below(256) as u8).collect();
        for size in [3usize, 5, 15, 31, 63] {
            for max in [true, false] {
                let fast = if max { extreme::<Lightest>(&src, w, h, size) } else { extreme::<Darkest>(&src, w, h, size) };
                let half = size / 2;
                for y in 0..h {
                    for x in 0..w {
                        let all = (y.saturating_sub(half)..=(y + half).min(h - 1))
                            .flat_map(|yy| (x.saturating_sub(half)..=(x + half).min(w - 1)).map(move |xx| (xx, yy)))
                            .map(|(xx, yy)| src[yy * w + xx]);
                        let direct = if max { all.max() } else { all.min() };
                        assert_eq!(Some(fast[y * w + x]), direct, "{w} x {h}, size {size}, max {max}, at ({x}, {y})");
                    }
                }
            }
        }
    }
    // a flat picture stays flat through the blur, and the neighbourhoods follow the resolution
    assert!(soften(&[200u8; 35], 7, 5).iter().all(|&v| v == 200));
    assert_eq!(
        (
            window(31.0, 300.0, (15, 63)),
            window(31.0, 150.0, (15, 63)),
            window(31.0, 1200.0, (15, 63)),
            window(9.0, 200.0, (5, 19)),
            window(9.0, f32::NAN, (5, 19))
        ),
        (31, 17, 63, 7, 9)
    );
}

/// A page of print in five sizes under lighting 10 % uneven with noise: where the ink is, against
/// one threshold for the whole page (what black and white was until now).
#[test]
fn print_is_found_better_than_by_one_threshold_for_the_page() {
    let (w, h) = (1500, 1000);
    for (dpi, sizes, needed) in [(300.0, [34.0, 30.0, 27.0, 24.0, 21.0], 0.96), (200.0, [23.0, 20.0, 18.0, 16.0, 14.0], 0.90)] {
        let mut sheet = Sheet::new(w, h);
        let mut rng = Rng(2);
        let mut top = 50.0;
        for size in sizes {
            sheet.text(60.0, top, size, size * 0.11, 3, &mut rng);
            top += 3.0 * size * 1.6 + 20.0;
        }
        let truth = sheet.ink();
        let luma = sheet.luminance(235.0, 0.10, 4.0, 3);
        let found = threshold(&luma, w, h, &params(55, dpi), None);
        let (p, r, f) = f_measure(&found, &truth, |_| true);
        // 0.69 of white: the old look at the level the app used
        let global: Vec<bool> = luma.iter().map(|&y| f32::from(y) < 0.69 * 255.0).collect();
        let (gp, gr, gf) = f_measure(&global, &truth, |_| true);
        println!("{dpi} dpi: F {f:.3} (precision {p:.3}, recall {r:.3}); one threshold: F {gf:.3} (precision {gp:.3}, recall {gr:.3})");
        assert!(f >= needed && f > gf + 0.02, "{dpi} dpi: {f} against {gf}");
    }
}

/// A pale pencil line (a fifth as dark as the paper is light) and a bold heading on one page:
/// both are kept, the line also right under the heading.
#[test]
fn a_pale_line_and_a_bold_heading_are_both_kept() {
    let (w, h) = (1200, 500);
    let mut heading = Sheet::new(w, h);
    heading.text(60.0, 60.0, 70.0, 12.0, 1, &mut Rng(4));
    let mut pencil = Sheet::new(w, h);
    for (y, by) in [(150.0, 0.2f32), (300.0, 0.2), (400.0, 0.2)] {
        for x in (60..1100).step_by(8) {
            let wave = |x: f64| y + 9.0 * (x / 37.0).sin();
            pencil.stroke((f64::from(x), wave(f64::from(x))), (f64::from(x) + 8.0, wave(f64::from(x) + 8.0)), 4.7, by);
        }
    }
    let mut both = Sheet::new(w, h);
    both.cover = heading.cover.iter().zip(&pencil.cover).map(|(a, b)| a.max(*b)).collect();
    let luma = both.luminance(235.0, 0.10, 4.0, 5);
    let core = |sheet: &Sheet, least: f32| -> Vec<bool> { sheet.cover.iter().map(|&c| c >= least).collect() };
    // the pencil's own pixels, where the heading is not
    let (line, bold) = (core(&pencil, 0.18), core(&heading, 0.8));
    let recall =
        |found: &[bool], of: &[bool]| (0..found.len()).filter(|&i| of[i] && found[i]).count() as f64 / of.iter().filter(|&&o| o).count() as f64;
    let found = threshold(&luma, w, h, &params(55, 300.0), None);
    let global: Vec<bool> = luma.iter().map(|&y| f32::from(y) < 0.69 * 255.0).collect();
    println!("pencil kept: {:.2} (one threshold: {:.2}); heading kept: {:.2}", recall(&found, &line), recall(&global, &line), recall(&found, &bold));
    assert!(recall(&found, &line) >= 0.85 && recall(&found, &bold) >= 0.97, "pencil {}, heading {}", recall(&found, &line), recall(&found, &bold));
    assert!(recall(&global, &line) < 0.5, "one threshold already kept the pencil");
    // strength is how faint a stroke may be: at 0 the pencil goes, the heading stays
    let clean = threshold(&luma, w, h, &params(0, 300.0), None);
    assert!(recall(&clean, &line) < 0.2 && recall(&clean, &bold) >= 0.97, "pencil {}, heading {}", recall(&clean, &line), recall(&clean, &bold));
}

#[test]
fn blank_paper_stays_white_and_solid_black_stays_black() {
    let (w, h) = (900, 700);
    // noisy blank paper
    let blank = Sheet::new(w, h).luminance(235.0, 0.10, 6.0, 6);
    for strength in [0, 55, 75] {
        let black = threshold(&blank, w, h, &params(strength, 300.0), None).iter().filter(|&&b| b).count();
        assert!((black as f64) < 0.0001 * (w * h) as f64, "strength {strength}: {black} black pixels on blank paper");
    }
    // a filled box much larger than any neighbourhood
    let mut sheet = Sheet::new(w, h);
    sheet.bar(200.0, 150.0, 700.0, 550.0, 1.0);
    let found = threshold(&sheet.luminance(235.0, 0.10, 4.0, 7), w, h, &params(55, 300.0), None);
    let inside = (160..540).flat_map(|y| (210..690).map(move |x| y * w + x));
    let black = inside.clone().filter(|&i| found[i]).count() as f64 / inside.count() as f64;
    assert!(black >= 0.97, "{black} of the box is black");
    let (_, _, f) = f_measure(&found, &sheet.ink(), |_| true);
    assert!(f >= 0.99, "{f}");
}

/// Full stops, commas and the dots of "i" (0.35 mm across) are not taken for specks.
#[test]
fn punctuation_survives_the_speck_removal() {
    for dpi in [200.0f64, 300.0] {
        let (w, h) = (600, 300);
        let mut sheet = Sheet::new(w, h);
        let r = 0.35 / 25.4 * dpi / 2.0;
        let mut centres = Vec::new();
        for row in 0..5 {
            for col in 0..12 {
                // at every phase of the pixel grid
                let (cx, cy) = (40.0 + f64::from(col) * 45.0 + f64::from(col) * 0.09, 40.0 + f64::from(row) * 50.0 + f64::from(row) * 0.21);
                sheet.dot(cx, cy, r, 1.0);
                centres.push((cx as usize, cy as usize));
            }
        }
        let found = threshold(&sheet.luminance(235.0, 0.10, 4.0, 8), w, h, &params(55, dpi as f32), None);
        let kept = centres.iter().filter(|&&(x, y)| found[y * w + x]).count();
        assert_eq!(kept, centres.len(), "{dpi} dpi: {kept} of {} dots kept", centres.len());
    }
    // what is removed: up to three pixels on their own; not four, and not three beside other ink
    let (w, h) = (20, 12);
    let mut ink = vec![false; w * h];
    for i in [
        2 * w + 2,
        2 * w + 3,
        3 * w + 3,
        2 * w + 10,
        2 * w + 11,
        3 * w + 10,
        3 * w + 11,
        8 * w + 6,
        8 * w + 8,
        8 * w + 9,
        8 * w + 10,
        8 * w + 11,
        10 * w + 17,
    ] {
        ink[i] = true;
    }
    despeckle(&mut ink, w, h);
    let left: Vec<usize> = (0..w * h).filter(|&i| ink[i]).collect();
    // gone: the three together and the one far from everything; kept: the four together, the
    // run of four and the single pixel two away from it
    assert_eq!(left, [2 * w + 10, 2 * w + 11, 3 * w + 10, 3 * w + 11, 8 * w + 6, 8 * w + 8, 8 * w + 9, 8 * w + 10, 8 * w + 11]);
}

/// A photographed page through the whole look: lighting that falls off to one side and a shadow
/// across the text. The old look's one threshold loses the text in the shadow.
#[test]
fn a_photographed_page_keeps_its_text_in_the_shadow() {
    let (w, h) = (1200usize, 900usize);
    let mut sheet = Sheet::new(w, h);
    sheet.text(70.0, 80.0, 30.0, 3.4, 12, &mut Rng(9));
    let truth = sheet.ink();
    let flat = sheet.luminance(235.0, 0.0, 3.0, 10);
    // light falling off to the right, and a soft-edged shadow over the lower left
    let lit = |x: usize, y: usize| {
        let fall = 1.0 - 0.35 * x as f64 / w as f64;
        let edge = (x as f64 * 0.6 + (h - y) as f64 - 520.0) / 40.0;
        fall * (0.55 + 0.45 / (1.0 + (-edge).exp()))
    };
    let data: Vec<u8> = (0..w * h)
        .flat_map(|i| [1.0, 0.97, 0.9].map(|tint| (f64::from(flat[i]) * lit(i % w, i / w) * tint).round().clamp(0.0, 255.0) as u8))
        .collect();
    let page = Rgb { width: w as u32, height: h as u32, data };
    let ours = black_white(&page, &BwParams::default()).unwrap();
    let found: Vec<bool> = (0..w * h).map(|i| ours.is_black((i % w) as u32, (i / w) as u32)).collect();
    let (p, r, f) = f_measure(&found, &truth, |_| true);
    use pdfcraft_engine::camera_clean as cc;
    let old: Vec<bool> = match cc::apply(&page.data, page.width, page.height, cc::Look::BlackWhite, 60).unwrap() {
        cc::Pixels::Grey(v) => v.iter().map(|&g| g < 128).collect(),
        cc::Pixels::Rgb(v) => v.chunks_exact(3).map(|p| p[0] < 128).collect(),
    };
    let (op, or, of) = f_measure(&old, &truth, |_| true);
    println!("shadow and fall-off: F {f:.3} (precision {p:.3}, recall {r:.3}); the engine's look: F {of:.3} (precision {op:.3}, recall {or:.3})");
    assert!(f >= 0.93 && f > of, "{f} against the engine's {of}");
    // the same bits on any number of threads
    assert_eq!(black_white(&page, &BwParams { threads: 4, ..BwParams::default() }).unwrap(), ours);
}

#[test]
fn bits_pack_and_small_or_wrong_pages_do_not_panic() {
    let page = |w: u32, h: u32, v: u8| Rgb { width: w, height: h, data: vec![v; (w * h * 3) as usize] };
    // too small to look around: dark is ink
    let tiny = black_white(&page(10, 3, 20), &BwParams::default()).unwrap();
    assert_eq!((tiny.stride(), tiny.data.len(), tiny.to_grey()), (2, 6, vec![0u8; 30]));
    let white = black_white(&page(10, 3, 240), &BwParams::default()).unwrap();
    assert!(white.to_grey().iter().all(|&g| g == 255) && !white.is_black(9, 2));
    // the padding bits of each row stay 0
    assert_eq!(white.data, [0xff, 0xc0, 0xff, 0xc0, 0xff, 0xc0]);
    assert_eq!(black_white(&Rgb { width: 4, height: 4, data: vec![0; 5] }, &BwParams::default()), None);
    assert_eq!(black_white(&page(0, 0, 0), &BwParams::default()), None);
    let _: Option<Bitmap1> = black_white(&page(64, 64, 128), &BwParams { strength: 255, dpi: f32::NAN, ..BwParams::default() });
}

/// Inside a photograph the page is a pattern of dots as dark on average as the photograph.
#[test]
fn photographs_are_dithered_to_their_own_darkness() {
    let (w, h) = (256usize, 256usize);
    // a ramp from black to white, as the toned luminance of a photograph
    let toned: Vec<u8> = (0..w * h).map(|i| (i % w) as u8).collect();
    let photo = vec![true; w * h];
    let ink = threshold(&vec![255u8; w * h], w, h, &params(55, 300.0), Some((&photo, &toned)));
    for band in 0..8 {
        let columns = band * 32..(band + 1) * 32;
        let black = (0..h).flat_map(|y| columns.clone().map(move |x| y * w + x)).filter(|&i| ink[i]).count() as f64 / (32 * h) as f64;
        let dark = 1.0 - (columns.start + columns.end - 1) as f64 / 2.0 / 255.0;
        assert!((black - dark).abs() < 0.02, "band {band}: {black} black for a darkness of {dark}");
    }
    // outside the photograph nothing is dithered: the same ramp, thresholded, is black then white
    let none = vec![false; w * h];
    let ink = threshold(&toned, w, h, &params(55, 300.0), Some((&none, &toned)));
    assert!(ink[100 * w + 20] && !ink[100 * w + 230]);

    // through the whole look: a grey photograph on a page of text is dots with dithering on, and
    // an outline with it off
    let (w, h) = (900usize, 700usize);
    let mut sheet = Sheet::new(w, h);
    sheet.text(60.0, 60.0, 28.0, 3.2, 4, &mut Rng(11));
    let mut data: Vec<u8> = sheet.luminance(235.0, 0.0, 2.0, 12).iter().flat_map(|&y| [y; 3]).collect();
    for y in 380..580 {
        for x in 250..510 {
            // a soft picture: mid greys with a slow pattern
            let v = (110.0 + 50.0 * ((x as f64 / 40.0).sin() * (y as f64 / 55.0).cos())) as u8;
            data[(y * w + x) * 3..(y * w + x) * 3 + 3].copy_from_slice(&[v; 3]);
        }
    }
    let page = Rgb { width: w as u32, height: h as u32, data };
    let share = |bits: &Bitmap1| {
        (400..560).flat_map(|y| (270..490).map(move |x| (x, y))).filter(|&(x, y)| bits.is_black(x, y)).count() as f64 / (160.0 * 220.0)
    };
    let dots = share(&black_white(&page, &BwParams::default()).unwrap());
    let flat = share(&black_white(&page, &BwParams { dither_photos: false, ..BwParams::default() }).unwrap());
    println!("a grey photograph: {dots:.2} black as dots, {flat:.2} without dithering");
    assert!((0.6..=0.95).contains(&dots) && (dots - flat).abs() > 0.2, "dots {dots}, flat {flat}");
}

/// The page pipeline: a 1-bit PNG with the page's resolution, small, and at twice the size for
/// a page under 200 dpi.
#[test]
fn a_black_and_white_page_is_a_small_one_bit_png() {
    use crate::page::WHOLE_PHOTO;
    use crate::{Format, Look, PageRequest, Paper, process_page};
    let png_of = |luma: &[u8], w: usize, h: usize| {
        let mut out = Vec::new();
        let rgb: Vec<u8> = luma.iter().flat_map(|&y| [y; 3]).collect();
        image::ImageEncoder::write_image(image::codecs::png::PngEncoder::new(&mut out), &rgb, w as u32, h as u32, image::ExtendedColorType::Rgb8)
            .unwrap();
        out
    };
    let read = |bytes: &[u8]| {
        let mut reader = png::Decoder::new(std::io::Cursor::new(bytes)).read_info().unwrap();
        let info = reader.info();
        let header = (info.width as usize, info.height as usize, info.bit_depth, info.color_type, info.pixel_dims.map(|d| (d.xppu, d.unit)));
        let mut rows = vec![0u8; reader.output_buffer_size().unwrap()];
        reader.next_frame(&mut rows).unwrap();
        (header, rows)
    };

    // A4 at 300 dpi, a full page of print
    let (w, h) = (2480usize, 3508usize);
    let mut sheet = Sheet::new(w, h);
    sheet.text(200.0, 220.0, 42.0, 4.6, 46, &mut Rng(13));
    let clock = std::time::Instant::now();
    let page = process_page(&PageRequest {
        look: Look::BlackWhite,
        strength: 55,
        ..PageRequest::new(&png_of(&sheet.luminance(235.0, 0.08, 3.0, 14), w, h), WHOLE_PHOTO)
    })
    .unwrap();
    println!(
        "A4 at 300 dpi: {} KiB as a 1-bit PNG, {:.0} ms for the whole page on one thread, look {} ms",
        page.bytes.len() / 1024,
        clock.elapsed().as_secs_f64() * 1e3,
        page.timings[2].1
    );
    assert_eq!((page.format, page.paper, page.width as usize, page.height as usize, page.dpi), (Format::Png, Paper::A4, w, h, 300.0));
    assert!(page.bytes.len() < 200 * 1024, "{} bytes", page.bytes.len());
    let (header, rows) = read(&page.bytes);
    assert_eq!(header, (w, h, png::BitDepth::One, png::ColorType::Grayscale, Some((11811, png::Unit::Meter))));
    // the bits are the print
    let stride = w.div_ceil(8);
    let found: Vec<bool> = (0..w * h).map(|i| rows[(i / w) * stride + (i % w) / 8] & (0x80 >> ((i % w) % 8)) == 0).collect();
    let (_, _, f) = f_measure(&found, &sheet.ink(), |_| true);
    assert!(f >= 0.96, "{f}");

    // the same sheet of paper photographed at 150 dpi comes back at 300, with its full stops
    let (w, h) = (1240usize, 1754usize);
    let mut sheet = Sheet::new(w, h);
    sheet.text(100.0, 110.0, 21.0, 2.3, 20, &mut Rng(15));
    let r = 0.35 / 25.4 * 150.0 / 2.0;
    let dots: Vec<(f64, f64)> = (0..40).map(|k| (150.0 + f64::from(k) * 23.37, 1500.0 + f64::from(k % 5) * 31.21)).collect();
    for &(x, y) in &dots {
        sheet.dot(x, y, r, 1.0);
    }
    let page = process_page(&PageRequest {
        look: Look::BlackWhite,
        strength: 55,
        ..PageRequest::new(&png_of(&sheet.luminance(235.0, 0.08, 3.0, 16), w, h), WHOLE_PHOTO)
    })
    .unwrap();
    assert_eq!((page.width as usize, page.height as usize, page.dpi, page.paper), (2 * w, 2 * h, 300.0, Paper::A4));
    let (_, rows) = read(&page.bytes);
    let stride = (2 * w).div_ceil(8);
    let black = |x: usize, y: usize| rows[y * stride + x / 8] & (0x80 >> (x % 8)) == 0;
    let kept = dots.iter().filter(|&&(x, y)| black((2.0 * x) as usize, (2.0 * y) as usize)).count();
    assert_eq!(kept, dots.len(), "{kept} of {} full stops kept", dots.len());
}

/// Not a requirement, a record: how long a 12 MP page takes on this machine.
#[test]
fn time_at_12_megapixels() {
    let (w, h) = (4000usize, 3000usize);
    let mut sheet = Sheet::new(w, h);
    sheet.text(300.0, 300.0, 44.0, 4.8, 36, &mut Rng(17));
    let data: Vec<u8> = sheet.luminance(230.0, 0.1, 3.0, 18).iter().flat_map(|&y| [y; 3]).collect();
    let page = Rgb { width: w as u32, height: h as u32, data };
    for threads in [1, 4] {
        let clock = std::time::Instant::now();
        let bits = black_white(&page, &BwParams { threads, ..BwParams::default() }).unwrap();
        println!("12 MP black and white, {threads} thread(s): {:.0} ms ({} bytes of bits)", clock.elapsed().as_secs_f64() * 1e3, bits.data.len());
    }
}
