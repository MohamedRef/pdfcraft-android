//! "Max quality" groundwork (#304): DocRes's prompts against DocRes's own Python, and the
//! correction map. The pages are drawn (`scan/tests/data/`); no model runs here.

use super::learned::{CorrectionMap, appearance_prompt, apply_correction, correction_map, deshadow_prompt};
use crate::warp::Rgb;

fn png(bytes: &[u8]) -> Rgb {
    let picture = image::load_from_memory(bytes).unwrap().to_rgb8();
    Rgb { width: picture.width(), height: picture.height(), data: picture.into_raw() }
}

/// (name, input, Python's deshadowing prompt, Python's appearance prompt), from
/// `tools/scan-models/docres_prompts.py`.
fn fixtures() -> [(&'static str, Rgb, Rgb, Rgb); 3] {
    [
        (
            "english",
            png(include_bytes!("../../tests/data/docres-english.png")),
            png(include_bytes!("../../tests/data/docres-english-deshadow.png")),
            png(include_bytes!("../../tests/data/docres-english-appearance.png")),
        ),
        (
            "arabic",
            png(include_bytes!("../../tests/data/docres-arabic.png")),
            png(include_bytes!("../../tests/data/docres-arabic-deshadow.png")),
            png(include_bytes!("../../tests/data/docres-arabic-appearance.png")),
        ),
        (
            "form",
            png(include_bytes!("../../tests/data/docres-form.png")),
            png(include_bytes!("../../tests/data/docres-form-deshadow.png")),
            png(include_bytes!("../../tests/data/docres-form-appearance.png")),
        ),
    ]
}

fn largest_difference(a: &Rgb, b: &Rgb) -> u8 {
    assert_eq!((a.width, a.height), (b.width, b.height));
    a.data.iter().zip(&b.data).map(|(x, y)| x.abs_diff(*y)).max().unwrap()
}

/// The issue's first test: the prompts equal DocRes's Python output to within one grey level.
/// (When written, both prompts were the same bytes on all three pages except one level in a few
/// pixels of the Arabic page's appearance prompt.)
#[test]
fn prompts_match_docres_python() {
    for (name, input, deshadow, appearance) in fixtures() {
        let d = largest_difference(&deshadow_prompt(&input).unwrap(), &deshadow);
        let a = largest_difference(&appearance_prompt(&input).unwrap(), &appearance);
        assert!(d <= 1 && a <= 1, "{name}: deshadowing off by {d}, appearance by {a}");
    }
}

fn blur3(page: &Rgb) -> Vec<f32> {
    let (w, h) = (page.width as usize, page.height as usize);
    let mut out = vec![0f32; w * h * 3];
    for y in 0..h {
        for x in 0..w {
            for c in 0..3 {
                let (mut s, mut n) = (0f32, 0f32);
                for yy in y.saturating_sub(1)..=(y + 1).min(h - 1) {
                    for xx in x.saturating_sub(1)..=(x + 1).min(w - 1) {
                        s += f32::from(page.data[(yy * w + xx) * 3 + c]);
                        n += 1.0;
                    }
                }
                out[(y * w + x) * 3 + c] = s / n;
            }
        }
    }
    out
}

/// The issue's cleaning test: a model output equal to its input except for sharpened print
/// gives a map within 2 % of 1.0 everywhere (the re-drawn strokes are not carried over).
#[test]
fn sharpened_print_leaves_no_trace_in_the_map() {
    for (name, input, _, _) in fixtures() {
        let soft = blur3(&input);
        let data = input.data.iter().zip(&soft).map(|(&v, &b)| (f32::from(v) * 2.0 - b).round().clamp(0.0, 255.0) as u8).collect();
        let sharpened = Rgb { data, ..input.clone() };
        let map = correction_map(&input, &sharpened).unwrap();
        let worst = map.gain.iter().flatten().map(|g| (g - 1.0).abs()).fold(0f32, f32::max);
        assert!(worst <= 0.02, "{name}: the map strays {worst:.4} from 1");
    }
}

/// The light that falls on the drawn page: dimmer to one corner, warm, with a soft dark blob.
fn light(x: f32, y: f32, w: f32, h: f32) -> [f32; 3] {
    let fall = 1.0 - 0.45 * (x / w + y / h) / 2.0;
    let blob = 1.0 - 0.35 * (-(((x - 0.7 * w) / (0.18 * w)).powi(2) + ((y - 0.35 * h) / (0.22 * h)).powi(2))).exp();
    let k = fall * blob;
    [k, k * 0.93, k * 0.82]
}

fn shadow(page: &Rgb) -> Rgb {
    let (w, h) = (page.width as f32, page.height as f32);
    let data = page
        .data
        .iter()
        .enumerate()
        .map(|(i, &v)| {
            let p = i / 3;
            let l = light((p % page.width as usize) as f32 + 0.5, (p / page.width as usize) as f32 + 0.5, w, h);
            (f32::from(v) * l[i % 3]).round() as u8
        })
        .collect();
    Rgb { data, ..page.clone() }
}

/// The page shrunk `k` times by averaging.
fn shrink(page: &Rgb, k: usize) -> Rgb {
    let (w, h) = (page.width as usize / k, page.height as usize / k);
    let mut data = vec![0u8; w * h * 3];
    for y in 0..h {
        for x in 0..w {
            for c in 0..3 {
                let mut s = 0u32;
                for yy in y * k..(y + 1) * k {
                    for xx in x * k..(x + 1) * k {
                        s += u32::from(page.data[(yy * page.width as usize + xx) * 3 + c]);
                    }
                }
                data[(y * w + x) * 3 + c] = (s as f32 / (k * k) as f32).round() as u8;
            }
        }
    }
    Rgb { width: w as u32, height: h as u32, data }
}

fn sharpness(page: &Rgb) -> f64 {
    let w = page.width as usize;
    let g: Vec<i32> = page.data.chunks(3).map(|p| (i32::from(p[0]) * 299 + i32::from(p[1]) * 587 + i32::from(p[2]) * 114) / 1000).collect();
    let h = g.len() / w;
    let mut sum = 0f64;
    for y in 0..h - 1 {
        for x in 0..w - 1 {
            sum += f64::from((g[y * w + x] - g[y * w + x + 1]).abs() + (g[y * w + x] - g[(y + 1) * w + x]).abs());
        }
    }
    sum / ((w - 1) * (h - 1)) as f64
}

/// What the map is for: a model that took the shadow off its small copy, perfectly. The map
/// carries that to the full-size page: paper comes back to within 4 grey levels of the page
/// before the shadow, and print is as crisp (the issue's sharpness test asks within 3 %).
#[test]
fn a_perfect_model_s_correction_lifts_the_shadow_off_the_full_page() {
    let grey = image::load_from_memory(include_bytes!("../../tests/data/upright-english.png")).unwrap().to_rgb8();
    let clean = Rgb { width: grey.width(), height: grey.height(), data: grey.into_raw() };
    let photo = shadow(&clean);
    let small_in = shrink(&photo, 3);
    let small_out = shrink(&clean, 3);
    let map = correction_map(&small_in, &small_out).unwrap();
    let fixed = apply_correction(&photo, &map, (0, 0, map.width, map.height)).unwrap();
    // paper: pixels white on the clean page and well away from print
    let mut errors: Vec<u8> = clean.data.iter().zip(&fixed.data).filter(|(c, _)| **c >= 250).map(|(c, f)| c.abs_diff(*f)).collect();
    errors.sort_unstable();
    let median = errors[errors.len() / 2];
    assert!(median <= 4, "paper off by {median} (median)");
    let (before, after) = (sharpness(&clean), sharpness(&fixed));
    assert!((after - before).abs() <= 0.03 * before, "sharpness {before:.3} -> {after:.3}");
}

#[test]
fn mismatched_buffers_are_refused() {
    let a = Rgb { width: 4, height: 4, data: vec![0; 48] };
    let b = Rgb { width: 4, height: 3, data: vec![0; 36] };
    assert!(correction_map(&a, &b).is_none());
    assert!(deshadow_prompt(&Rgb { width: 4, height: 4, data: vec![0; 3] }).is_none());
    let map = CorrectionMap { width: 2, height: 2, gain: vec![[1.0; 3]; 4] };
    assert!(apply_correction(&a, &map, (1, 1, 2, 2)).is_none());
    assert_eq!(apply_correction(&a, &map, (0, 0, 2, 2)).unwrap(), a);
}

/// Time of both prompts and of a correction map at the issue's 384 × 512, run by hand with
/// `--ignored --nocapture` for the figures in `docs/ANDROID_SCAN_MAX_QUALITY.md`.
#[test]
#[ignore]
fn learned_timing() {
    let (_, input, _, _) = fixtures().into_iter().next().unwrap();
    let w = 384usize;
    let h = 512usize;
    let data = (0..w * h * 3).map(|i| input.data[i % input.data.len()]).collect();
    let page = Rgb { width: w as u32, height: h as u32, data };
    let t = std::time::Instant::now();
    let _ = deshadow_prompt(&page).unwrap();
    let _ = appearance_prompt(&page).unwrap();
    let prompts = t.elapsed();
    let t = std::time::Instant::now();
    let _ = correction_map(&page, &page).unwrap();
    eprintln!("prompts {prompts:?}, map {:?}", t.elapsed());
}
