//! Tests for the tone metrics (issue #288). Pictures are built in code; the numbers asserted
//! are the ones the brief names, and the measured values are printed for the report.

use crate::metrics_tone::{hue_shift, illum_spread, ink_contrast, paper_white, percentile, sharpness};
use crate::{Luma, Rgb};

fn luma_page(width: u32, height: u32, level: impl Fn(u32, u32) -> u8) -> Luma {
    let data = (0..height).flat_map(|y| (0..width).map(move |x| (x, y))).map(|(x, y)| level(x, y)).collect();
    Luma { width, height, data }
}

fn rgb_page(width: u32, height: u32, colour: impl Fn(u32, u32) -> [u8; 3]) -> Rgb {
    let mut data = Vec::with_capacity((width * height * 3) as usize);
    for y in 0..height {
        for x in 0..width {
            data.extend_from_slice(&colour(x, y));
        }
    }
    Rgb { width, height, data }
}

fn all(n: usize) -> Vec<bool> {
    vec![true; n]
}

/// Deterministic pseudo-random numbers (a fixed-seed LCG), for text-like test pages.
struct Lcg(u64);

impl Lcg {
    fn next_below(&mut self, n: u64) -> u64 {
        self.0 = self.0.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        (self.0 >> 33) % n
    }
}

/// A page of strokes in text lines: `lines` lines of height 24 px from the top, each stroke a
/// 2 px wide dark bar 10 px tall, placed at random on a 4 px grid with the same seed each time.
fn text_page(width: u32, height: u32, lines: u32) -> Luma {
    let mut rng = Lcg(7);
    let mut cells = vec![false; (width * height) as usize];
    for line in 0..lines {
        let top = 4 + line * 24;
        for x in (0..width).step_by(4) {
            if rng.next_below(2) == 0 {
                for dy in 0..10 {
                    for dx in 0..2 {
                        let (px, py) = (x + dx, top + dy);
                        if px < width && py < height {
                            cells[(py * width + px) as usize] = true;
                        }
                    }
                }
            }
        }
    }
    luma_page(width, height, |x, y| if cells[(y * width + x) as usize] { 40 } else { 250 })
}

/// A 7×7 box blur with the edges clamped, for the blurred board.
fn box_blur_7(page: &Luma) -> Luma {
    let (w, h) = (page.width as i64, page.height as i64);
    luma_page(page.width, page.height, |x, y| {
        let mut sum = 0u32;
        for dy in -3..=3i64 {
            for dx in -3..=3i64 {
                let sx = (i64::from(x) + dx).clamp(0, w - 1) as u32;
                let sy = (i64::from(y) + dy).clamp(0, h - 1) as u32;
                sum += u32::from(page.data[(sy * page.width + sx) as usize]);
            }
        }
        (sum / 49) as u8
    })
}

#[test]
fn a_white_page_has_every_paper_pixel_white() {
    let page = luma_page(40, 30, |_, _| 255);
    let share = paper_white(&page, &all(40 * 30)).unwrap();
    println!("paper_white on a 255 page: {share}");
    assert_eq!(share, 1.0);
}

#[test]
fn a_page_at_240_has_no_white_paper_pixels() {
    let page = luma_page(40, 30, |_, _| 240);
    let share = paper_white(&page, &all(40 * 30)).unwrap();
    println!("paper_white on a 240 page: {share}");
    assert_eq!(share, 0.0);
}

#[test]
fn paper_white_counts_only_the_paper_pixels_it_is_given() {
    // Left half 250 (paper and white), right half 240 (paper, not white), and the 240 half is
    // not paper: only the 250 pixels are counted, so the share is 1.0.
    let page = luma_page(10, 4, |x, _| if x < 5 { 250 } else { 240 });
    let paper: Vec<bool> = (0..40).map(|i| i % 10 < 5).collect();
    let share = paper_white(&page, &paper).unwrap();
    println!("paper_white with the 240 half masked out: {share}");
    assert_eq!(share, 1.0);
}

#[test]
fn a_straight_ramp_from_100_to_200_has_an_illumination_spread_near_0_6() {
    let (w, h) = (300u32, 40u32);
    let page = luma_page(w, h, |x, _| (100.0 + 100.0 * f64::from(x) / f64::from(w - 1)).round() as u8);
    let spread = illum_spread(&page, &all((w * h) as usize)).unwrap();
    println!("illum_spread on a 100 to 200 ramp (expect 0.6): {spread}");
    assert!((spread - 0.6).abs() < 0.05, "spread {spread}");
}

#[test]
fn a_flat_page_has_an_illumination_spread_of_0() {
    let (w, h) = (300u32, 40u32);
    let spread = illum_spread(&luma_page(w, h, |_, _| 180), &all((w * h) as usize)).unwrap();
    println!("illum_spread on a flat 180 page: {spread}");
    assert!(spread.abs() < 1e-9, "spread {spread}");
}

#[test]
fn ink_left_out_of_the_paper_mask_does_not_darken_the_illumination() {
    // Flat 200 paper with black dots; the dots are not paper, so the spread stays 0.
    let (w, h) = (120u32, 80u32);
    let dot = |x: u32, y: u32| x % 10 == 5 && y % 10 == 5;
    let page = luma_page(w, h, |x, y| if dot(x, y) { 0 } else { 200 });
    let paper: Vec<bool> = (0..h).flat_map(|y| (0..w).map(move |x| !dot(x, y))).collect();
    let spread = illum_spread(&page, &paper).unwrap();
    println!("illum_spread on flat paper with ink dots left out: {spread}");
    assert!(spread.abs() < 1e-9, "spread {spread}");
}

#[test]
fn black_bars_on_white_paper_have_an_ink_contrast_of_255() {
    let page = luma_page(60, 20, |x, _| if x % 10 < 3 { 0 } else { 255 });
    let paper: Vec<bool> = page.data.iter().map(|&v| v == 255).collect();
    let ink: Vec<bool> = page.data.iter().map(|&v| v == 0).collect();
    let contrast = ink_contrast(&page, &paper, &ink).unwrap();
    println!("ink_contrast black bars on white: {contrast}");
    assert_eq!(contrast, 255.0);
}

#[test]
fn grey_128_bars_on_white_paper_have_an_ink_contrast_of_127() {
    let page = luma_page(60, 20, |x, _| if x % 10 < 3 { 128 } else { 255 });
    let paper: Vec<bool> = page.data.iter().map(|&v| v == 255).collect();
    let ink: Vec<bool> = page.data.iter().map(|&v| v == 128).collect();
    let contrast = ink_contrast(&page, &paper, &ink).unwrap();
    println!("ink_contrast grey 128 bars on white: {contrast}");
    assert_eq!(contrast, 127.0);
}

#[test]
fn a_red_patch_turned_to_orange_shifts_its_hue_by_30_degrees() {
    let before = rgb_page(8, 8, |_, _| [255, 0, 0]);
    let after = rgb_page(8, 8, |_, _| [255, 128, 0]);
    let shift = hue_shift(&before, &after, &all(64)).unwrap();
    println!("hue_shift red to orange (expect 30): {shift}");
    assert!((shift - 30.0).abs() < 1.0, "shift {shift}");
}

#[test]
fn a_hue_that_crosses_zero_shifts_by_20_degrees_the_short_way() {
    // 350 degrees to 10 degrees: 340 the long way round, 20 the short way.
    let before = rgb_page(8, 8, |_, _| [255, 0, 43]);
    let after = rgb_page(8, 8, |_, _| [255, 43, 0]);
    let shift = hue_shift(&before, &after, &all(64)).unwrap();
    println!("hue_shift 350 to 10 degrees (expect 20): {shift}");
    assert!((shift - 20.0).abs() < 1.0, "shift {shift}");
}

#[test]
fn grey_pixels_are_left_out_of_the_hue_shift() {
    // Half the stamp is a red-to-orange patch (30 degrees), half is grey that changes from 100
    // to 200. The grey half has no hue, so it must not dilute the mean: the shift stays 30.
    let before = rgb_page(8, 8, |x, _| if x < 4 { [255, 0, 0] } else { [100, 100, 100] });
    let after = rgb_page(8, 8, |x, _| if x < 4 { [255, 128, 0] } else { [200, 200, 200] });
    let shift = hue_shift(&before, &after, &all(64)).unwrap();
    println!("hue_shift with grey pixels in the stamp (expect 30): {shift}");
    assert!((shift - 30.0).abs() < 1.0, "shift {shift}");
}

#[test]
fn a_checkerboard_is_at_least_3_times_sharper_than_the_same_board_blurred() {
    let (w, h) = (128u32, 128u32);
    let board = luma_page(w, h, |x, y| if (x / 8 + y / 8) % 2 == 0 { 0 } else { 255 });
    let blurred = box_blur_7(&board);
    let inside = all((w * h) as usize);
    let sharp = sharpness(&board, &inside).unwrap();
    let soft = sharpness(&blurred, &inside).unwrap();
    println!("sharpness: checkerboard {sharp}, blurred {soft}, ratio {}", sharp / soft);
    assert!(sharp >= 3.0 * soft, "sharp {sharp} soft {soft}");
}

/// The top tenth is taken over every counted pixel, so the figure follows how much of the page
/// is print: only pictures of the same page can be compared by it.
#[test]
fn a_page_with_one_line_of_text_scores_well_below_a_full_page_of_the_same_text() {
    let (w, h) = (300u32, 400u32);
    let inside = all((w * h) as usize);
    let full = sharpness(&text_page(w, h, 16), &inside).unwrap();
    let one_line = sharpness(&text_page(w, h, 1), &inside).unwrap();
    let ratio = one_line / full;
    println!("sharpness: full text page {full}, one line on a blank page {one_line}, ratio {ratio}");
    assert!(ratio > 0.0 && ratio < 0.5, "ratio {ratio}");
}

#[test]
fn sharpness_of_a_flat_page_is_0() {
    let page = luma_page(40, 40, |_, _| 90);
    let value = sharpness(&page, &all(1600)).unwrap();
    println!("sharpness of a flat page: {value}");
    assert_eq!(value, 0.0);
}

#[test]
fn wrong_lengths_give_none_in_every_function() {
    let page = luma_page(10, 10, |_, _| 200);
    let short = vec![true; 99];
    let good = all(100);
    let rgb = rgb_page(10, 10, |_, _| [200, 10, 10]);
    let rgb_other = rgb_page(10, 9, |_, _| [200, 10, 10]);
    assert_eq!(sharpness(&page, &short), None);
    assert_eq!(paper_white(&page, &short), None);
    assert_eq!(illum_spread(&page, &short), None);
    assert_eq!(ink_contrast(&page, &good, &short), None);
    assert_eq!(ink_contrast(&page, &short, &good), None);
    assert_eq!(hue_shift(&rgb, &rgb_other, &good), None);
    assert_eq!(hue_shift(&rgb, &rgb, &short), None);
}

#[test]
fn empty_masks_and_empty_pictures_give_none() {
    let page = luma_page(10, 10, |_, _| 200);
    let none_counted = vec![false; 100];
    assert_eq!(sharpness(&page, &none_counted), None);
    assert_eq!(paper_white(&page, &none_counted), None);
    assert_eq!(illum_spread(&page, &none_counted), None);
    assert_eq!(ink_contrast(&page, &all(100), &none_counted), None);
    assert_eq!(ink_contrast(&page, &none_counted, &all(100)), None);
    let rgb = rgb_page(10, 10, |_, _| [200, 10, 10]);
    assert_eq!(hue_shift(&rgb, &rgb, &none_counted), None);
    let empty = Luma { width: 0, height: 0, data: Vec::new() };
    assert_eq!(paper_white(&empty, &[]), None);
    assert_eq!(illum_spread(&empty, &[]), None);
    assert_eq!(sharpness(&empty, &[]), None);
}

#[test]
fn a_one_pixel_picture_gives_neutral_values_without_panicking() {
    let page = luma_page(1, 1, |_, _| 200);
    assert_eq!(sharpness(&page, &[true]), None);
    assert_eq!(paper_white(&page, &[true]), Some(0.0));
    assert_eq!(illum_spread(&page, &[true]), Some(0.0));
}

#[test]
fn a_zero_median_paper_gives_no_illumination_spread() {
    let page = luma_page(20, 20, |_, _| 0);
    assert_eq!(illum_spread(&page, &all(400)), None);
}

#[test]
fn percentiles_of_a_known_histogram_are_exact() {
    // 5 pixels at level 10, 90 at level 20, 5 at level 200: 100 in all.
    let mut hist = [0u32; 256];
    hist[10] = 5;
    hist[20] = 90;
    hist[200] = 5;
    assert_eq!(percentile(&hist, 0.0), Some(10));
    assert_eq!(percentile(&hist, 5.0), Some(10));
    assert_eq!(percentile(&hist, 6.0), Some(20));
    assert_eq!(percentile(&hist, 50.0), Some(20));
    assert_eq!(percentile(&hist, 95.0), Some(20));
    assert_eq!(percentile(&hist, 96.0), Some(200));
    assert_eq!(percentile(&hist, 100.0), Some(200));
}

#[test]
fn a_percentile_outside_0_to_100_or_of_an_empty_histogram_is_none() {
    let mut hist = [0u32; 256];
    hist[7] = 1;
    assert_eq!(percentile(&hist, -1.0), None);
    assert_eq!(percentile(&hist, 100.5), None);
    assert_eq!(percentile(&hist, f64::NAN), None);
    assert_eq!(percentile(&[0u32; 256], 50.0), None);
}
