#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

use crate::synth::Rng;

#[test]
fn the_same_seed_gives_the_same_numbers_and_another_seed_others() {
    let take = |seed: u64| -> Vec<u64> {
        let mut rng = Rng::new(seed);
        (0..16).map(|_| rng.next_u64()).collect()
    };
    assert_eq!(take(7), take(7));
    assert_ne!(take(7), take(8));
    assert_ne!(take(0), take(1));
    println!("first draws for seed 7: {:?}", &take(7)[..3]);
}

#[test]
fn unit_numbers_stay_in_zero_to_one_and_have_mean_near_one_half() {
    let mut rng = Rng::new(42);
    let draws: Vec<f64> = (0..100_000).map(|_| rng.unit()).collect();
    assert!(draws.iter().all(|&v| (0.0..1.0).contains(&v)));
    let mean = draws.iter().sum::<f64>() / draws.len() as f64;
    println!("unit mean over 100000 draws: {mean:.5}");
    assert!((mean - 0.5).abs() < 0.005, "mean {mean}");
}

#[test]
fn gaussian_numbers_have_mean_zero_and_spread_one() {
    let mut rng = Rng::new(9);
    let draws: Vec<f64> = (0..100_000).map(|_| rng.gauss()).collect();
    let mean = draws.iter().sum::<f64>() / draws.len() as f64;
    let var = draws.iter().map(|v| (v - mean) * (v - mean)).sum::<f64>() / draws.len() as f64;
    println!("gauss mean {mean:.4}, sd {:.4}", var.sqrt());
    assert!(mean.abs() < 0.02);
    assert!((var.sqrt() - 1.0).abs() < 0.02);
    assert!(draws.iter().all(|v| v.is_finite()));
}

#[test]
fn below_stays_inside_its_range_and_is_zero_for_an_empty_range() {
    let mut rng = Rng::new(3);
    assert!((0..1000).all(|_| rng.below(5) < 5));
    assert_eq!(rng.below(0), 0);
}

use crate::synth::{page, Page, PageOptions};

fn page_options() -> PageOptions {
    PageOptions { width: 320, height: 440, tint: [248, 246, 240], table: false, stamp: false, signature: false, photo_region: false, pencil: false }
}

fn full_page_options() -> PageOptions {
    PageOptions { table: true, stamp: true, signature: true, photo_region: true, pencil: true, ..page_options() }
}

fn luma_of(p: &[u8]) -> f64 {
    0.299 * f64::from(p[0]) + 0.587 * f64::from(p[1]) + 0.114 * f64::from(p[2])
}

fn count(mask: &[bool]) -> usize {
    mask.iter().filter(|&&b| b).count()
}

#[test]
fn the_same_seed_and_options_give_the_same_page_and_another_seed_another_page() {
    let a = page(5, &full_page_options());
    assert_eq!(a, page(5, &full_page_options()));
    assert_ne!(a.clean.data, page(6, &full_page_options()).clean.data);
    println!("page bytes for seed 5: {} bytes, {} ink pixels", a.clean.data.len(), count(&a.ink));
}

#[test]
fn the_four_masks_partition_the_page_exactly() {
    let p: Page = page(11, &full_page_options());
    let n = (p.clean.width * p.clean.height) as usize;
    assert_eq!(p.clean.data.len(), n * 3);
    for i in 0..n {
        let hits = [p.paper[i], p.ink[i], p.stamp[i], p.photo_region[i]].iter().filter(|&&b| b).count();
        assert_eq!(hits, 1, "pixel {i} is in {hits} masks");
    }
    println!("paper {} ink {} stamp {} photo {} of {n}", count(&p.paper), count(&p.ink), count(&p.stamp), count(&p.photo_region));
}

#[test]
fn paper_pixels_are_exactly_the_tint_and_ink_is_never_paper() {
    let p = page(12, &full_page_options());
    for (i, &is_paper) in p.paper.iter().enumerate() {
        if is_paper {
            assert_eq!(&p.clean.data[3 * i..3 * i + 3], &[248, 246, 240], "paper pixel {i}");
        }
    }
    assert!(p.paper.iter().zip(&p.ink).all(|(&a, &b)| !(a && b)));
}

#[test]
fn every_dark_pixel_is_ink_stamp_or_photo_region_and_text_covers_a_sane_share() {
    let p = page(13, &full_page_options());
    for (i, px) in p.clean.data.chunks_exact(3).enumerate() {
        if luma_of(px) < 100.0 {
            assert!(p.ink[i] || p.stamp[i] || p.photo_region[i], "dark pixel {i} is paper");
        }
    }
    let share = count(&p.ink) as f64 / p.ink.len() as f64;
    println!("ink share of the page: {share:.3}");
    assert!((0.02..0.40).contains(&share), "ink share {share}");
}

#[test]
fn a_plain_page_has_no_stamp_no_photo_no_red_and_no_grey_pencil() {
    let p = page(14, &page_options());
    assert_eq!(count(&p.stamp), 0);
    assert_eq!(count(&p.photo_region), 0);
    assert!(p.clean.data.chunks_exact(3).all(|px| i32::from(px[0]) <= i32::from(px[2]) + 80));
    assert!(p.clean.data.chunks_exact(3).enumerate().all(|(i, px)| !p.ink[i] || px != [170, 170, 170]));
}

#[test]
fn stamp_pixels_are_bluish_and_the_stamp_is_present_when_asked() {
    let p = page(15, &full_page_options());
    assert!(count(&p.stamp) > 100, "stamp pixels {}", count(&p.stamp));
    for (i, px) in p.clean.data.chunks_exact(3).enumerate() {
        if p.stamp[i] {
            assert!(i32::from(px[2]) > i32::from(px[0]) + 100 && i32::from(px[2]) > i32::from(px[1]) + 60, "stamp pixel {i} is {px:?}");
        }
    }
}

#[test]
fn the_signature_is_red_and_the_pencil_paragraph_is_grey_170_on_paper_245() {
    let options = PageOptions { tint: [245, 245, 245], ..full_page_options() };
    let p = page(16, &options);
    let red = p.clean.data.chunks_exact(3).filter(|px| i32::from(px[0]) > i32::from(px[2]) + 100).count();
    let pencil = (0..p.ink.len()).filter(|&i| p.ink[i] && p.clean.data[3 * i..3 * i + 3] == [170, 170, 170]).count();
    println!("red signature pixels {red}, pencil pixels {pencil}");
    assert!(red > 50);
    assert!(pencil > 100);
    let plain = page(16, &PageOptions { tint: [245, 245, 245], ..page_options() });
    assert!(plain.clean.data.chunks_exact(3).all(|px| i32::from(px[0]) <= i32::from(px[2]) + 100));
}

#[test]
fn the_photo_region_is_smooth_noise_not_text() {
    let options = PageOptions { photo_region: true, ..page_options() };
    let p = page(17, &options);
    let w = p.clean.width as usize;
    let (mut total, mut steps) = (0.0, 0usize);
    for (i, &m) in p.photo_region.iter().enumerate().take(p.photo_region.len() - 1) {
        if m && p.photo_region[i + 1] {
            total += (luma_of(&p.clean.data[3 * i..]) - luma_of(&p.clean.data[3 * (i + 1)..])).abs();
            steps += 1;
        }
    }
    let mean_step = total / steps.max(1) as f64;
    println!("photo region pixels {}, mean step between neighbours {mean_step:.2}, width {w}", count(&p.photo_region));
    assert!(count(&p.photo_region) > 1000);
    assert!(mean_step < 12.0, "mean step {mean_step}");
}

#[test]
fn table_rules_make_a_long_run_of_ink_across_the_page() {
    let p = page(18, &PageOptions { table: true, ..page_options() });
    let w = p.clean.width as usize;
    let full_rows = (0..p.clean.height as usize)
        .filter(|&y| (0..w).filter(|&x| p.ink[y * w + x]).count() * 10 >= w * 7)
        .count();
    println!("rows that are at least 70 percent ink: {full_rows}");
    assert!(full_rows >= 6, "rule rows {full_rows}");
}

#[test]
fn extreme_sizes_are_clamped_and_do_not_panic() {
    let tiny = page(1, &PageOptions { width: 0, height: 1, ..page_options() });
    assert_eq!((tiny.clean.width, tiny.clean.height), (16, 16));
    let huge = page(1, &PageOptions { width: 4_000_000_000, height: 100_000, ..full_page_options() });
    assert_eq!((huge.clean.width, huge.clean.height), (1600, 1600));
    assert_eq!(huge.paper.len(), 1600 * 1600);
    println!("clamped sizes: {}x{} and {}x{}", tiny.clean.width, tiny.clean.height, huge.clean.width, huge.clean.height);
}

use crate::synth::{locate, Background, Page as P2, SceneOptions, Settings};

fn scene_options(share: f32, tilt: f32, turn: f32) -> SceneOptions {
    SceneOptions {
        width: 640,
        height: 480,
        background: Background::Light,
        tilt_deg: tilt,
        rotation_deg: turn,
        page_share: share,
        light_ratio: 1.0,
        shadow: false,
        cast: [1.0, 1.0, 1.0],
        blur_sigma: 0.0,
        noise_sigma: 0.0,
    }
}

fn located(p: &P2, o: &SceneOptions) -> Result<crate::synth::Located, crate::ScanError> {
    locate(p, &Settings::new(o))
}

fn page_of(seed: u64, w: u32, h: u32) -> P2 {
    page(seed, &PageOptions { width: w, height: h, ..page_options() })
}

fn cross(o: [f64; 2], a: [f64; 2], b: [f64; 2]) -> f64 {
    (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
}

#[test]
fn the_quad_is_convex_inside_the_frame_and_covers_the_asked_share_within_three_percent() {
    let cases = [
        (1u64, 320u32, 440u32, 0.30f32, 20.0f32, 8.0f32),
        (2, 320, 440, 0.30, 30.0, -12.0),
        (3, 440, 320, 0.60, 10.0, 8.0),
        (4, 320, 440, 0.45, 0.0, 0.0),
        (5, 440, 320, 0.30, 45.0, 0.0),
    ];
    for (seed, w, h, share, tilt, turn) in cases {
        let o = scene_options(share, tilt, turn);
        let p = page_of(seed, w, h);
        let l = located(&p, &o).unwrap_or_else(|e| panic!("seed {seed}: {e:?}"));
        let q = l.quad.0;
        let turns: Vec<f64> = (0..4).map(|i| cross(q[i], q[(i + 1) % 4], q[(i + 2) % 4])).collect();
        assert!(turns.iter().all(|&t| t > 0.0) || turns.iter().all(|&t| t < 0.0), "not convex: {q:?}");
        assert!(q.iter().all(|c| (0.0..=640.0).contains(&c[0]) && (0.0..=480.0).contains(&c[1])), "outside: {q:?}");
        let covered = l.quad.area() / (640.0 * 480.0);
        println!("seed {seed}: asked {share:.2}, covered {covered:.4}, tilt {tilt}, turn {turn}");
        assert!((covered - f64::from(share)).abs() <= 0.03 * f64::from(share) + 1e-9, "covered {covered} asked {share}");
    }
}

#[test]
fn with_no_tilt_the_quad_width_over_height_equals_the_page_ratio_within_half_a_percent() {
    for (w, h, turn) in [(320u32, 440u32, 0.0f32), (300, 200, 12.0), (250, 400, -18.0)] {
        let p = page_of(9, w, h);
        let l = located(&p, &scene_options(0.3, 0.0, turn)).unwrap_or_else(|e| panic!("{e:?}"));
        let s = l.quad.side_lengths();
        let width = (s[0] + s[2]) / 2.0;
        let height = (s[1] + s[3]) / 2.0;
        let ratio = f64::from(w) / f64::from(h);
        println!("page {w}x{h} turn {turn}: quad width/height {:.5}, page ratio {ratio:.5}", width / height);
        assert!(((width / height) / ratio - 1.0).abs() < 0.005);
    }
}

#[test]
fn with_no_turn_and_no_tilt_the_quad_is_an_axis_aligned_rectangle_centred_in_the_frame() {
    let p = page_of(10, 300, 400);
    let l = located(&p, &scene_options(0.4, 0.0, 0.0)).unwrap_or_else(|e| panic!("{e:?}"));
    let q = l.quad.0;
    assert!((q[0][0] - q[3][0]).abs() < 1e-9 && (q[1][0] - q[2][0]).abs() < 1e-9, "left and right edges are not vertical: {q:?}");
    assert!((q[0][1] - q[1][1]).abs() < 1e-9 && (q[2][1] - q[3][1]).abs() < 1e-9, "top and bottom edges are not level: {q:?}");
    let cx = (q[0][0] + q[2][0]) / 2.0;
    let cy = (q[0][1] + q[2][1]) / 2.0;
    println!("quad centre ({cx:.4}, {cy:.4}) in a 640x480 frame, focal {:.2}", l.focal);
    assert!((cx - 320.0).abs() < 1e-6 && (cy - 240.0).abs() < 1e-6);
}

#[test]
fn a_page_that_cannot_fit_in_the_frame_is_refused_not_panicked_on() {
    let tall = page_of(20, 16, 1600);
    let r = located(&tall, &scene_options(0.85, 40.0, 20.0));
    println!("tall page at 0.85 share: {r:?}");
    assert!(matches!(r, Err(crate::ScanError::BadCorners)));
    let fine = page_of(20, 320, 440);
    assert!(located(&fine, &scene_options(0.30, 10.0, 5.0)).is_ok());
    // A portrait page at 0.35 of the frame, leaned 30 and turned -12 degrees: its near corner
    // leaves the frame, so the brief's "does not fit" answer is an error.
    assert!(matches!(located(&fine, &scene_options(0.35, 30.0, -12.0)), Err(crate::ScanError::BadCorners)));
}

#[test]
fn a_page_whose_pixels_do_not_match_its_size_is_refused_as_an_encode_error() {
    let mut broken = page_of(21, 40, 60);
    broken.clean.data.truncate(10);
    assert_eq!(located(&broken, &scene_options(0.4, 0.0, 0.0)).err(), Some(crate::ScanError::Encode));
}


use crate::synth::{scene, sample};
use crate::geom::Homography;
use crate::{Photo, Quad};

fn plain_scene_options(share: f32, tilt: f32, turn: f32) -> SceneOptions {
    scene_options(share, tilt, turn)
}

/// Straightens a photo back onto the page's rectangle: each page pixel's centre is mapped into the
/// photo with the homography from the rectangle to the quad, and sampled there.
fn straighten(photo: &Photo, quad: &Quad, page_w: usize, page_h: usize) -> Vec<f32> {
    let (pw, ph) = (page_w as f64, page_h as f64);
    let rect = [[0.0, 0.0], [pw, 0.0], [pw, ph], [0.0, ph]];
    let back = Homography::from_points(rect, quad.0).expect("a quad maps from a rectangle");
    let mut out = Vec::with_capacity(page_w * page_h * 3);
    for v in 0..page_h {
        for u in 0..page_w {
            let q = back.apply([u as f64 + 0.5, v as f64 + 0.5]).expect("maps to the photo");
            out.extend(sample(&photo.rgb, photo.width as usize, photo.height as usize, q));
        }
    }
    out
}

#[test]
fn straightening_the_photo_gives_the_clean_page_within_three_grey_levels_on_average() {
    let p = page(30, &PageOptions { table: true, stamp: true, signature: true, pencil: true, photo_region: true, ..page_options() });
    let s = scene(31, &p, &plain_scene_options(0.45, 15.0, 10.0)).unwrap_or_else(|e| panic!("{e:?}"));
    let back = straighten(&s.photo, &s.quad, p.clean.width as usize, p.clean.height as usize);
    let total: f64 = back.iter().zip(&p.clean.data).map(|(&a, &b)| (f64::from(a) - f64::from(b)).abs()).sum();
    let mean = total / back.len() as f64;
    println!("straightened photo: mean absolute error {mean:.3} grey levels per channel over {} values", back.len());
    assert!(mean <= 3.0, "mean error {mean}");
}

#[test]
fn every_background_draws_a_page_and_its_own_background_around_it() {
    use crate::synth::Background;
    let p = page(40, &page_options());
    for bg in [Background::Dark, Background::Light, Background::Wood, Background::Checker, Background::Noise] {
        let o = SceneOptions { background: bg, ..plain_scene_options(0.3, 0.0, 0.0) };
        let s = scene(41, &p, &o).unwrap_or_else(|e| panic!("{bg:?}: {e:?}"));
        let (w, h) = (s.photo.width as usize, s.photo.height as usize);
        let outside: Vec<f64> = (0..w * h)
            .filter(|&i| {
                let (x, y) = ((i % w) as f64 + 0.5, (i / w) as f64 + 0.5);
                let q = s.quad.0;
                let xs = q.iter().map(|c| c[0]);
                let ys = q.iter().map(|c| c[1]);
                let (x0, x1) = (xs.clone().fold(f64::MAX, f64::min), xs.fold(f64::MIN, f64::max));
                let (y0, y1) = (ys.clone().fold(f64::MAX, f64::min), ys.fold(f64::MIN, f64::max));
                x < x0 - 2.0 || x > x1 + 2.0 || y < y0 - 2.0 || y > y1 + 2.0
            })
            .map(|i| f64::from(s.photo.rgb[3 * i]))
            .collect();
        let mean = outside.iter().sum::<f64>() / outside.len() as f64;
        let sd = (outside.iter().map(|v| (v - mean) * (v - mean)).sum::<f64>() / outside.len() as f64).sqrt();
        println!("{bg:?}: {} background pixels, red mean {mean:.1}, red sd {sd:.1}", outside.len());
        assert!(outside.len() > 1000);
        match bg {
            Background::Dark => assert!(mean < 60.0),
            Background::Light => assert!(mean > 180.0),
            _ => assert!(sd > 1.0, "{bg:?} is flat"),
        }
    }
}

#[test]
fn a_scene_is_the_same_for_the_same_seed_and_another_for_another() {
    let p = page(50, &page_options());
    let o = plain_scene_options(0.4, 10.0, 5.0);
    let a = scene(1, &p, &o).unwrap_or_else(|e| panic!("{e:?}"));
    assert_eq!(a.photo, scene(1, &p, &o).unwrap_or_else(|e| panic!("{e:?}")).photo);
    let noisy = SceneOptions { background: crate::synth::Background::Noise, ..o.clone() };
    let b = scene(1, &p, &noisy).unwrap_or_else(|e| panic!("{e:?}"));
    let c = scene(2, &p, &noisy).unwrap_or_else(|e| panic!("{e:?}"));
    assert_ne!(b.photo.rgb, c.photo.rgb);
}
