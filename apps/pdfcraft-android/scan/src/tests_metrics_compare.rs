//! Tests for the comparison metrics (issue #288). Pictures and texts are built in code.

use crate::Luma;
use crate::metrics_compare::{cer, edit_distance, psnr, ssim};

/// A picture of `width` by `height` whose grey at (x, y) is `value(x, y)`.
fn picture(width: u32, height: u32, value: impl Fn(u32, u32) -> u8) -> Luma {
    let data = (0..height).flat_map(|y| (0..width).map(move |x| (x, y))).map(|(x, y)| value(x, y)).collect();
    Luma { width, height, data }
}

/// A smooth ramp pattern whose values run 0 to 250, so adding 5 never clips at 255.
fn ramp(x: u32, y: u32) -> u8 {
    ((x * 7 + y * 13) % 251) as u8
}

/// Vertical stripes one pixel wide, alternating 40 and 200.
fn stripes(x: u32, _y: u32) -> u8 {
    if x.is_multiple_of(2) { 40 } else { 200 }
}

/// Vertical stripes two pixels wide, in runs of four pixels: 40, 40, 200, 200, and so on. A
/// three-tap blur keeps their phase, so the blurred picture still lines up with this one.
fn wide_stripes(x: u32, _y: u32) -> u8 {
    if (x / 2).is_multiple_of(2) { 40 } else { 200 }
}

/// Three-tap horizontal blur of the wide stripes, the end pixels repeated at the edges.
fn blurred_stripes(width: u32, height: u32) -> Luma {
    picture(width, height, |x, y| {
        let left = x.saturating_sub(1);
        let right = (x + 1).min(width - 1);
        let sum = u32::from(wide_stripes(left, y)) + u32::from(wide_stripes(x, y)) + u32::from(wide_stripes(right, y));
        (sum / 3) as u8
    })
}

/// A seeded pseudo-random noise picture, grey shifted by up to 100 either way and clamped.
fn noisy(base: &Luma, seed: u64) -> Luma {
    let mut state = seed;
    let data = base
        .data
        .iter()
        .map(|&v| {
            state ^= state << 13;
            state ^= state >> 7;
            state ^= state << 17;
            let offset = (state % 201) as i32 - 100;
            (i32::from(v) + offset).clamp(0, 255) as u8
        })
        .collect();
    Luma { width: base.width, height: base.height, data }
}

#[test]
fn psnr_of_a_picture_against_itself_plus_a_constant_five_is_about_34_15_db() {
    let a = picture(32, 24, ramp);
    let b = Luma { data: a.data.iter().map(|&v| v + 5).collect(), ..a.clone() };
    let value = psnr(&a, &b).unwrap();
    println!("psnr against itself plus 5: {value:.6} dB");
    let expected = 10.0 * (255.0f64 * 255.0 / 25.0).log10();
    assert!((value - expected).abs() < 1e-9, "got {value}, expected {expected}");
    assert!((value - 34.15).abs() < 0.01);
}

#[test]
fn psnr_of_a_picture_against_itself_is_infinite() {
    let a = picture(16, 16, ramp);
    assert_eq!(psnr(&a, &a.clone()), Some(f64::INFINITY));
}

#[test]
fn psnr_is_none_when_the_sizes_differ_or_a_side_is_zero() {
    let a = picture(16, 16, ramp);
    let wider = picture(17, 16, ramp);
    let taller = picture(16, 17, ramp);
    assert_eq!(psnr(&a, &wider), None);
    assert_eq!(psnr(&a, &taller), None);
    let empty = Luma { width: 0, height: 16, data: Vec::new() };
    assert_eq!(psnr(&empty, &empty.clone()), None);
}

#[test]
fn psnr_is_none_when_the_data_does_not_match_the_size() {
    let a = picture(16, 16, ramp);
    let short = Luma { data: a.data[1..].to_vec(), ..a.clone() };
    assert_eq!(psnr(&a, &short), None);
    assert_eq!(psnr(&short, &short.clone()), None);
}

#[test]
fn ssim_of_a_picture_against_itself_is_one() {
    let a = picture(40, 33, ramp);
    let value = ssim(&a, &a.clone()).unwrap();
    println!("ssim against itself: {value:.12}");
    assert!((value - 1.0).abs() < 1e-9, "got {value}");
}

#[test]
fn ssim_against_strong_noise_is_clearly_lower() {
    let a = picture(64, 64, ramp);
    let same = ssim(&a, &a.clone()).unwrap();
    let noise = noisy(&a, 0x9E37_79B9_7F4A_7C15);
    let value = ssim(&a, &noise).unwrap();
    println!("ssim against itself: {same:.6}, against noise of 100: {value:.6}");
    assert!(value < 0.75, "got {value}");
    assert!(same - value > 0.25);
}

#[test]
fn ssim_survives_a_constant_brightness_shift_of_ten() {
    let a = picture(32, 32, stripes);
    let b = Luma { data: a.data.iter().map(|&v| v + 10).collect(), ..a.clone() };
    let value = ssim(&a, &b).unwrap();
    println!("ssim under a brightness shift of 10: {value:.6}");
    assert!(value > 0.9, "got {value}");
}

#[test]
fn ssim_drops_below_0_7_when_a_striped_picture_is_blurred() {
    let a = picture(32, 32, wide_stripes);
    let blurred = blurred_stripes(32, 32);
    let value = ssim(&a, &blurred).unwrap();
    println!("ssim of striped picture against its blur: {value:.6}");
    assert!(value < 0.7, "got {value}");
}

#[test]
fn ssim_is_symmetric() {
    let a = picture(40, 30, ramp);
    let b = blurred_stripes(40, 30);
    let ab = ssim(&a, &b).unwrap();
    let ba = ssim(&b, &a).unwrap();
    println!("ssim a-b {ab:.12}, b-a {ba:.12}");
    assert!((ab - ba).abs() < 1e-12);
}

#[test]
fn ssim_is_none_when_a_side_is_under_eight() {
    let thin = picture(7, 20, ramp);
    assert_eq!(ssim(&thin, &thin.clone()), None);
    let short = picture(20, 7, ramp);
    assert_eq!(ssim(&short, &short.clone()), None);
    let eight = picture(8, 8, ramp);
    assert!(ssim(&eight, &eight.clone()).is_some());
}

#[test]
fn ssim_is_none_when_the_sizes_differ_or_the_data_is_malformed() {
    let a = picture(16, 16, ramp);
    let other = picture(16, 15, ramp);
    assert_eq!(ssim(&a, &other), None);
    let broken = Luma { data: vec![0; 10], ..a.clone() };
    assert_eq!(ssim(&broken, &broken.clone()), None);
}

#[test]
fn cer_of_kitten_and_sitting_is_three_sevenths() {
    let value = cer("kitten", "sitting");
    println!("cer kitten/sitting: {value:.12}");
    assert!((value - 3.0 / 7.0).abs() < 1e-12);
}

#[test]
fn cer_of_an_arabic_word_with_one_letter_changed_is_one_over_its_length() {
    let truth = "مكتبة الجامعة";
    let recognised = "مكتبه الجامعة";
    let length = truth.chars().count();
    let value = cer(recognised, truth);
    println!("cer arabic one letter: {value:.12} over {length} characters");
    assert!((value - 1.0 / length as f64).abs() < 1e-12);
}

#[test]
fn cer_counts_each_run_of_white_space_as_one_space_and_trims_the_ends() {
    assert_eq!(cer("  the\t\tquick \n brown  ", "the quick brown"), 0.0);
    assert_eq!(cer("the quick brown", "  the   quick\u{00A0}brown\n"), 0.0);
    let value = cer("a  b", "a b");
    assert_eq!(value, 0.0, "a double space should count as one space");
}

#[test]
fn cer_of_an_empty_true_text_is_zero_only_when_the_recognised_text_is_empty() {
    assert_eq!(cer("", ""), 0.0);
    assert_eq!(cer("   ", "\n"), 0.0);
    assert_eq!(cer("words", ""), 1.0);
    assert_eq!(cer("", "words"), 1.0);
}

#[test]
fn cer_of_identical_text_is_zero_and_of_a_missing_word_is_its_share() {
    assert_eq!(cer("scan this page", "scan this page"), 0.0);
    let value = cer("scan page", "scan this page");
    println!("cer missing word: {value:.12}");
    assert!((value - 5.0 / 14.0).abs() < 1e-12);
}

#[test]
fn edit_distance_counts_each_insert_delete_and_replace_once() {
    let chars = |s: &str| s.chars().collect::<Vec<char>>();
    assert_eq!(edit_distance(&chars(""), &chars("abc")), 3);
    assert_eq!(edit_distance(&chars("abc"), &chars("")), 3);
    assert_eq!(edit_distance(&chars("abc"), &chars("abc")), 0);
    assert_eq!(edit_distance(&chars("abc"), &chars("abxc")), 1);
    assert_eq!(edit_distance(&chars("abc"), &chars("ac")), 1);
    assert_eq!(edit_distance(&chars("abc"), &chars("axc")), 1);
    assert_eq!(edit_distance(&chars("flaw"), &chars("lawn")), 2);
}
