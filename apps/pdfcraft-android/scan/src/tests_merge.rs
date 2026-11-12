//! Tests for the low-light merge (issue #297, spec S11 section 3). Pages are drawn in code and
//! the measured numbers are printed, so `cargo test -- --nocapture` shows them.

use crate::merge::{Merger, align, choose, moved, noise_between};
use crate::{Luma, Rgb, luma};

/// Page width in pixels, as the brief's test pages.
const W: usize = 600;
/// Page height in pixels.
const H: usize = 800;
/// Paper colour of the drawn pages.
const PAPER: [u8; 3] = [215, 210, 198];
/// Ink colour of the text bars.
const INK: [u8; 3] = [50, 48, 55];
/// Seed of the page's word lengths.
const PAGE_SEED: u64 = 0x2545_F491_4F6C_DD1D;
/// Seed of the noise of the frames.
const NOISE_SEED: u64 = 0x9E37_79B9_7F4A_7C15;
/// Text lines on the page.
const LINES: usize = 20;
/// Height of the first text line, in pixels from the top.
const FIRST_LINE_Y: usize = 80;
/// Distance between text lines.
const LINE_PITCH: usize = 36;
/// Height of a text bar.
const LINE_INK_HEIGHT: usize = 12;
/// Left margin of the text.
const LEFT: usize = 60;
/// Right margin of the text.
const RIGHT: usize = 540;

/// A small deterministic generator (xorshift64*), so the tests need no crate.
struct Rng(u64);

impl Rng {
    /// The next 64 random bits.
    fn next_u64(&mut self) -> u64 {
        self.0 ^= self.0 >> 12;
        self.0 ^= self.0 << 25;
        self.0 ^= self.0 >> 27;
        self.0.wrapping_mul(0x2545_F491_4F6C_DD1D)
    }

    /// A number in (0, 1).
    fn unit(&mut self) -> f64 {
        ((self.next_u64() >> 11) as f64 + 0.5) / (1u64 << 53) as f64
    }

    /// A standard Gaussian number (Box–Muller).
    fn gauss(&mut self) -> f64 {
        let (u1, u2) = (self.unit(), self.unit());
        (-2.0 * u1.ln()).sqrt() * (std::f64::consts::TAU * u2).cos()
    }
}

/// A page with lines of words, paper and ink, drawn in code.
fn page() -> Rgb {
    let mut ink = vec![false; W * H];
    let mut rng = Rng(PAGE_SEED);
    for line in 0..LINES {
        let top = FIRST_LINE_Y + line * LINE_PITCH;
        let mut x = LEFT;
        while x + 20 < RIGHT {
            let len = 20 + (rng.next_u64() % 50) as usize;
            let gap = 10 + (rng.next_u64() % 16) as usize;
            for y in top..top + LINE_INK_HEIGHT {
                for xx in x..(x + len).min(RIGHT) {
                    ink[y * W + xx] = true;
                }
            }
            x += len + gap;
        }
    }
    let mut data = Vec::with_capacity(W * H * 3);
    for &is_ink in &ink {
        data.extend_from_slice(&if is_ink { INK } else { PAPER });
    }
    Rgb { width: W as u32, height: H as u32, data }
}

/// A blank sheet of the page's paper colour.
fn blank() -> Rgb {
    let data = PAPER.iter().copied().cycle().take(W * H * 3).collect();
    Rgb { width: W as u32, height: H as u32, data }
}

/// `clean` with independent Gaussian noise of standard deviation `sigma` on every byte.
fn noisy(clean: &Rgb, sigma: f64, rng: &mut Rng) -> Rgb {
    let data = clean.data.iter().map(|&v| (f64::from(v) + sigma * rng.gauss()).round().clamp(0.0, 255.0) as u8).collect();
    Rgb { width: clean.width, height: clean.height, data }
}

/// The luminance of a picture (BT.601, as the crate's `luma`).
fn to_luma(p: &Rgb) -> Luma {
    let data = p.data.chunks_exact(3).map(|c| luma([c[0], c[1], c[2]])).collect();
    Luma { width: p.width, height: p.height, data }
}

/// Root mean square difference over all bytes.
fn rmse(a: &Rgb, b: &Rgb) -> f64 {
    let sum: f64 = a.data.iter().zip(b.data.iter()).map(|(&x, &y)| (f64::from(x) - f64::from(y)).powi(2)).sum();
    (sum / a.data.len().max(1) as f64).sqrt()
}

/// Peak signal-to-noise ratio in dB against 255.
fn psnr(a: &Rgb, b: &Rgb) -> f64 {
    20.0 * (255.0 / rmse(a, b).max(1e-12)).log10()
}

/// Mean absolute difference over the pixels inside `(x0..x1, y0..y1)`, all channels.
fn mean_abs_in(a: &Rgb, b: &Rgb, (x0, x1, y0, y1): (usize, usize, usize, usize)) -> f64 {
    let (mut total, mut n) = (0.0f64, 0usize);
    for y in y0..y1 {
        for x in x0..x1 {
            for c in 0..3 {
                let i = (y * W + x) * 3 + c;
                total += (f64::from(a.data[i]) - f64::from(b.data[i])).abs();
                n += 1;
            }
        }
    }
    total / n.max(1) as f64
}

/// The blob region of the occluder test: 240 × 200 pixels, which is 10 % of the page.
const BLOB: (usize, usize, usize, usize) = (150, 390, 300, 500);

/// `clean` with the blob region painted dark, as a hand or a shadow would leave it.
fn with_blob(clean: &Rgb) -> Rgb {
    let mut out = clean.clone();
    let (x0, x1, y0, y1) = BLOB;
    for y in y0..y1 {
        for x in x0..x1 {
            let i = (y * W + x) * 3;
            out.data[i..i + 3].copy_from_slice(&[30, 30, 30]);
        }
    }
    out
}

#[test]
fn four_noisy_frames_merge_to_at_most_0_6_of_one_frames_error() {
    let clean = page();
    let mut rng = Rng(NOISE_SEED);
    let frames: Vec<Rgb> = (0..4).map(|_| noisy(&clean, 10.0, &mut rng)).collect();
    let reference = frames[0].clone();
    let mut merger = Merger::new(reference.clone());
    for frame in &frames[1..] {
        let sigma = noise_between(&reference, frame).expect("same size");
        println!("noise_between sigma per channel: {sigma:?}");
        assert!(merger.add(frame, sigma));
    }
    let merged = merger.finish();
    let single = rmse(&clean, &reference);
    let error = rmse(&clean, &merged.rgb);
    println!("one frame: rmse {single:.2}, psnr {:.2} dB", psnr(&clean, &reference));
    println!("merged of {}: rmse {error:.2}, psnr {:.2} dB", merged.used, psnr(&clean, &merged.rgb));
    println!("psnr_gain_estimate {:.2} dB; error ratio {:.3}", merged.psnr_gain_estimate, error / single);
    assert_eq!(merged.used, 4);
    assert!(error <= 0.6 * single, "merged {error} vs one frame {single}");
}

/// Whether the clean page is ink at this pixel (the paper is 215, the ink 50 in red).
fn is_ink(clean: &Rgb, x: usize, y: usize) -> bool {
    clean.data[(y * W + x) * 3] < 128
}

/// Root mean square difference over the pixels that `keep` accepts, all channels.
fn rmse_where(a: &Rgb, b: &Rgb, keep: impl Fn(usize, usize) -> bool) -> f64 {
    let (mut total, mut n) = (0.0f64, 0usize);
    for y in 0..H {
        for x in 0..W {
            if keep(x, y) {
                for c in 0..3 {
                    let i = (y * W + x) * 3 + c;
                    total += (f64::from(a.data[i]) - f64::from(b.data[i])).powi(2);
                    n += 1;
                }
            }
        }
    }
    (total / n.max(1) as f64).sqrt()
}

#[test]
fn a_dark_blob_in_one_frame_leaves_no_trace_on_the_paper_and_the_rest_still_gains() {
    let clean = page();
    let mut rng = Rng(NOISE_SEED ^ 1);
    let blobbed = noisy(&with_blob(&clean), 10.0, &mut rng);
    let reference = noisy(&clean, 10.0, &mut rng);
    let others: Vec<Rgb> = (0..2).map(|_| noisy(&clean, 10.0, &mut rng)).collect();

    let sigma = [10.0f32; 3];
    let mut with = Merger::new(reference.clone());
    assert!(with.add(&blobbed, sigma));
    for f in &others {
        assert!(with.add(f, sigma));
    }
    let mut without = Merger::new(reference.clone());
    for f in &others {
        assert!(without.add(f, sigma));
    }
    let with = with.finish();
    let without = without.finish();

    let (x0, x1, y0, y1) = BLOB;
    let (mut paper_changed, mut ink_changed, mut ink_total, mut ink_gap) = (0usize, 0usize, 0usize, 0.0f64);
    for y in y0..y1 {
        for x in x0..x1 {
            let i = (y * W + x) * 3;
            let changed = with.rgb.data[i..i + 3] != without.rgb.data[i..i + 3];
            if !is_ink(&clean, x, y) {
                paper_changed += usize::from(changed);
            } else {
                ink_total += 1;
                ink_changed += usize::from(changed);
                ink_gap += f64::from(with.rgb.data[i].abs_diff(without.rgb.data[i]));
            }
        }
    }
    println!("blob on paper: samples changed by the dark frame {paper_changed}");
    println!("blob on ink: pixels changed {ink_changed} of {ink_total}; mean red change {:.2} grey levels", ink_gap / ink_total.max(1) as f64);

    let inside_error = mean_abs_in(&with.rgb, &clean, BLOB);
    let inside_single = mean_abs_in(&reference, &clean, BLOB);
    println!("inside the blob: mean |merged - clean| {inside_error:.2}, one frame {inside_single:.2}");

    let outside = |x: usize, y: usize| !(x0..x1).contains(&x) || !(y0..y1).contains(&y);
    let gain_ratio = rmse_where(&with.rgb, &clean, outside) / rmse_where(&reference, &clean, outside);
    println!("outside the blob: error ratio against one frame {gain_ratio:.3}");

    assert_eq!(paper_changed, 0, "the dark frame must not change any sample of the paper in the blob");
    assert!(inside_error < inside_single, "within noise of the clean page inside the blob");
    assert!(gain_ratio <= 0.6, "outside the blob the merge still gains: ratio {gain_ratio}");
}

#[test]
fn a_frame_moved_by_1_4_and_minus_0_6_is_found_within_0_15_pixel() {
    let clean = page();
    let frame = moved(&clean, 1.4, -0.6);
    let shift = align(&to_luma(&clean), &to_luma(&frame)).expect("same size");
    println!(
        "clean frame moved by (1.4, -0.6): found move ({:.3}, {:.3}), residual {:.3}, usable {}",
        shift.dx, shift.dy, shift.residual, shift.usable
    );
    // The found move is the one that takes the frame back onto the reference.
    assert!((shift.dx - -1.4).abs() <= 0.15, "dx {}", shift.dx);
    assert!((shift.dy - 0.6).abs() <= 0.15, "dy {}", shift.dy);
    assert!(shift.usable);
}

#[test]
fn a_noisy_frame_moved_by_1_4_and_minus_0_6_is_found_within_0_15_pixel() {
    let clean = page();
    let mut rng = Rng(NOISE_SEED ^ 2);
    let reference = noisy(&clean, 10.0, &mut rng);
    let frame = noisy(&moved(&clean, 1.4, -0.6), 10.0, &mut rng);
    let shift = align(&to_luma(&reference), &to_luma(&frame)).expect("same size");
    println!("noisy frame moved by (1.4, -0.6): found move ({:.3}, {:.3}), usable {}", shift.dx, shift.dy, shift.usable);
    assert!((shift.dx - -1.4).abs() <= 0.15, "dx {}", shift.dx);
    assert!((shift.dy - 0.6).abs() <= 0.15, "dy {}", shift.dy);
}

#[test]
fn a_frame_moved_by_minus_2_and_2_is_found_within_0_15_pixel() {
    let clean = page();
    let frame = moved(&clean, -2.0, 2.0);
    let shift = align(&to_luma(&clean), &to_luma(&frame)).expect("same size");
    println!("clean frame moved by (-2.0, 2.0): found move ({:.3}, {:.3})", shift.dx, shift.dy);
    assert!((shift.dx - 2.0).abs() <= 0.15, "dx {}", shift.dx);
    assert!((shift.dy - -2.0).abs() <= 0.15, "dy {}", shift.dy);
}

#[test]
fn several_shifts_up_to_2_3_pixels_are_each_found_within_0_2_pixel() {
    let clean = page();
    for (mx, my) in [(2.3f32, -2.3f32), (-1.7, 0.9), (0.35, -1.25), (2.3, 2.3)] {
        let frame = moved(&clean, mx, my);
        let shift = align(&to_luma(&clean), &to_luma(&frame)).expect("same size");
        println!("moved by ({mx}, {my}): found ({:.3}, {:.3})", shift.dx, shift.dy);
        assert!((shift.dx + mx).abs() <= 0.2, "dx {} for {mx}", shift.dx);
        assert!((shift.dy + my).abs() <= 0.2, "dy {} for {my}", shift.dy);
    }
}

#[test]
fn a_blank_page_gives_a_move_of_exactly_zero() {
    let flat = blank();
    let shift = align(&to_luma(&flat), &to_luma(&flat)).expect("same size");
    println!("blank, no noise: move ({}, {}), residual {}", shift.dx, shift.dy, shift.residual);
    assert_eq!((shift.dx, shift.dy), (0.0, 0.0));

    let mut rng = Rng(NOISE_SEED ^ 3);
    let a = noisy(&flat, 10.0, &mut rng);
    let b = noisy(&flat, 10.0, &mut rng);
    let shift = align(&to_luma(&a), &to_luma(&b)).expect("same size");
    println!("blank, two noisy copies: move ({}, {}), usable {}", shift.dx, shift.dy, shift.usable);
    assert_eq!((shift.dx, shift.dy), (0.0, 0.0));
}

#[test]
fn a_move_of_4_pixels_is_not_usable() {
    let clean = page();
    let frame = moved(&clean, 4.0, 0.0);
    let shift = align(&to_luma(&clean), &to_luma(&frame)).expect("same size");
    println!("clean frame moved by (4, 0): found ({:.3}, {:.3}), usable {}", shift.dx, shift.dy, shift.usable);
    assert!(!shift.usable);
}

#[test]
fn noise_between_two_copies_with_sigma_6_gives_6_plus_minus_1() {
    let clean = page();
    let mut rng = Rng(NOISE_SEED ^ 4);
    let a = noisy(&clean, 6.0, &mut rng);
    let b = noisy(&clean, 6.0, &mut rng);
    let sigma = noise_between(&a, &b).expect("same size");
    println!("noise_between, two copies with sigma 6: {sigma:?}");
    for s in sigma {
        assert!((s - 6.0).abs() <= 1.0, "sigma {s}");
    }
}

#[test]
fn choose_takes_the_sharpest_and_every_frame_at_least_70_percent_of_it() {
    assert_eq!(choose(&[3.0, 5.0, 3.4, 4.9]), Some((1, vec![1, 3])));
    assert_eq!(choose(&[2.0]), Some((0, vec![0])));
    assert_eq!(choose(&[5.0, 5.0, 1.0]), Some((0, vec![0, 1])));
}

#[test]
fn degenerate_inputs_give_none_or_false_and_never_panic() {
    assert_eq!(choose(&[]), None);
    assert_eq!(choose(&[1.0, f32::NAN]), None);
    assert_eq!(choose(&[f32::INFINITY, 1.0]), None);

    let empty_luma = Luma { width: 0, height: 0, data: Vec::new() };
    assert!(align(&empty_luma, &empty_luma).is_none());
    let small = Luma { width: 4, height: 4, data: vec![7; 16] };
    let other = Luma { width: 5, height: 4, data: vec![7; 20] };
    assert!(align(&small, &other).is_none());
    let short = Luma { width: 4, height: 4, data: vec![7; 3] };
    assert!(align(&short, &small).is_none());

    let empty_rgb = Rgb { width: 0, height: 0, data: Vec::new() };
    assert!(noise_between(&empty_rgb, &empty_rgb).is_none());
    let a = Rgb { width: 4, height: 4, data: vec![9; 48] };
    let b = Rgb { width: 4, height: 5, data: vec![9; 60] };
    assert!(noise_between(&a, &b).is_none());

    let mut merger = Merger::new(a.clone());
    assert!(!merger.add(&b, [1.0; 3]), "a different size is refused");
    assert!(merger.add(&a, [f32::NAN, -4.0, f32::INFINITY]), "odd sigmas do not refuse the frame");
    let merged = merger.finish();
    assert_eq!(merged.used, 2);

    let mut none = Merger::new(empty_rgb.clone());
    assert!(!none.add(&empty_rgb, [1.0; 3]));
    let none = none.finish();
    assert_eq!((none.rgb.width, none.rgb.height, none.used), (0, 0, 0));
    assert!(none.rgb.data.is_empty());

    let broken = Merger::new(Rgb { width: 4, height: 4, data: vec![1; 10] }).finish();
    assert_eq!((broken.rgb.width, broken.rgb.height, broken.used), (0, 0, 0));

    assert!(moved(&empty_rgb, 1.0, 1.0).data.is_empty());
    assert_eq!(moved(&a, f32::NAN, 2.0), a);
}

#[test]
fn a_whole_pixel_move_copies_pixels_and_repeats_the_edges() {
    let p = page();
    let m = moved(&p, 2.0, 0.0);
    // out(x, y) = in(x - 2, y): the pixel at x = 10 comes from x = 8, and the first two columns repeat x = 0.
    let i = |x: usize, y: usize| (y * W + x) * 3;
    assert_eq!(&m.data[i(10, 50)..i(10, 50) + 3], &p.data[i(8, 50)..i(8, 50) + 3]);
    assert_eq!(&m.data[i(0, 50)..i(0, 50) + 3], &p.data[i(0, 50)..i(0, 50) + 3]);
    assert_eq!(&m.data[i(1, 50)..i(1, 50) + 3], &p.data[i(0, 50)..i(0, 50) + 3]);
}

#[test]
fn the_merge_is_deterministic() {
    let clean = page();
    let mut rng = Rng(NOISE_SEED ^ 5);
    let frames: Vec<Rgb> = (0..3).map(|_| noisy(&clean, 8.0, &mut rng)).collect();
    let run = || {
        let mut m = Merger::new(frames[0].clone());
        for f in &frames[1..] {
            assert!(m.add(f, [8.0; 3]));
        }
        m.finish()
    };
    assert_eq!(run(), run());
}

#[test]
fn the_robust_average_takes_only_values_within_3_sigma_plus_2_and_rounds() {
    // Threshold with sigma 2 is 8 grey levels: 104 joins (100 + 104) / 2 = 102, 120 does not.
    let one = |v: u8| Rgb { width: 1, height: 1, data: vec![v; 3] };
    let mut m = Merger::new(one(100));
    assert!(m.add(&one(104), [2.0; 3]));
    assert!(m.add(&one(120), [2.0; 3]));
    let merged = m.finish();
    assert_eq!(merged.rgb.data, vec![102, 102, 102]);
    assert_eq!(merged.used, 3);
}

#[test]
fn the_merge_stops_taking_frames_at_200() {
    let one = |v: u8| Rgb { width: 2, height: 2, data: vec![v; 12] };
    let mut m = Merger::new(one(200));
    for i in 1..200 {
        assert!(m.add(&one(200), [1.0; 3]), "frame {i} is taken");
    }
    assert!(!m.add(&one(200), [1.0; 3]), "frame 200 is refused");
    let merged = m.finish();
    println!("200 identical frames: used {}, psnr_gain_estimate {:.3} dB", merged.used, merged.psnr_gain_estimate);
    assert_eq!(merged.used, 200);
    assert!((merged.psnr_gain_estimate - 10.0 * 200f32.log10()).abs() < 1e-3);
    assert_eq!(merged.rgb.data, vec![200; 12]);
}

#[test]
fn the_psnr_gain_estimate_is_ten_log10_of_the_mean_count() {
    let one = |v: u8| Rgb { width: 2, height: 2, data: vec![v; 12] };
    let mut m = Merger::new(one(90));
    for _ in 0..3 {
        assert!(m.add(&one(90), [0.0; 3]));
    }
    let merged = m.finish();
    println!("four identical frames: used {}, psnr_gain_estimate {:.4} dB", merged.used, merged.psnr_gain_estimate);
    assert_eq!(merged.used, 4);
    assert!((merged.psnr_gain_estimate - 10.0 * 4f32.log10()).abs() < 1e-3);
}
