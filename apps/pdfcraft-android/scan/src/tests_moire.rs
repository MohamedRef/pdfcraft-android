//! Moiré (#305): the FFT itself, what is found and what is not, and what the notches take out.
//! Every page is drawn in code or is one of the drawn print pages in `tests/data/`.

use crate::moire::{Frequency, MoireKind, TILE, fft, find, remove};
use crate::photo::Luma;

fn print_page(png: &[u8]) -> Luma {
    let picture = image::load_from_memory(png).unwrap().to_luma8();
    Luma { width: picture.width(), height: picture.height(), data: picture.into_raw() }
}

fn english() -> Luma {
    print_page(include_bytes!("../tests/data/upright-english.png"))
}

fn pages() -> [(&'static str, Luma); 3] {
    [
        ("english", english()),
        ("arabic", print_page(include_bytes!("../tests/data/upright-arabic.png"))),
        ("form", print_page(include_bytes!("../tests/data/upright-form.png"))),
    ]
}

/// A page of print large enough for many squares, without resampling (an enlarged copy has a
/// pattern at 2 pixels of its own): the three drawn pages and the English one again, two by two.
fn print_sheet() -> Luma {
    let [(_, a), (_, b), (_, c)] = pages();
    let d = english();
    let (w, h) = (a.width as usize, a.height as usize);
    let mut data = vec![0u8; 4 * w * h];
    for (n, page) in [a, b, c, d].iter().enumerate() {
        let (x0, y0) = ((n % 2) * w, (n / 2) * h);
        for y in 0..h {
            data[(y0 + y) * 2 * w + x0..(y0 + y) * 2 * w + x0 + w].copy_from_slice(&page.data[y * w..(y + 1) * w]);
        }
    }
    Luma { width: 2 * w as u32, height: 2 * h as u32, data }
}

/// The grating's phase at a pixel: `2π (x cos θ + y sin θ) / period`.
fn phase(x: u32, y: u32, period: f32, degrees: f32) -> f32 {
    let (s, c) = degrees.to_radians().sin_cos();
    std::f32::consts::TAU * (x as f32 * c + y as f32 * s) / period
}

/// The page multiplied by a sinusoidal grating darkening it by up to `modulation`.
fn grated(page: &Luma, period: f32, degrees: f32, modulation: f32) -> Luma {
    let w = page.width;
    let data = page
        .data
        .iter()
        .enumerate()
        .map(|(i, &v)| {
            let (x, y) = (i as u32 % w, i as u32 / w);
            let k = 1.0 - modulation * (0.5 + 0.5 * phase(x, y, period, degrees).sin());
            (f32::from(v) * k).round().clamp(0.0, 255.0) as u8
        })
        .collect();
    Luma { width: page.width, height: page.height, data }
}

/// The grating's amplitude in `a - b`, by projecting the difference on its sine and cosine.
fn amplitude(a: &Luma, b: &Luma, period: f32, degrees: f32) -> f32 {
    let w = a.width;
    let (mut s, mut c) = (0f64, 0f64);
    for (i, (&p, &q)) in a.data.iter().zip(&b.data).enumerate() {
        let d = f64::from(p) - f64::from(q);
        let ph = f64::from(phase(i as u32 % w, i as u32 / w, period, degrees));
        s += d * ph.sin();
        c += d * ph.cos();
    }
    (2.0 * s.hypot(c) / a.data.len() as f64) as f32
}

/// Mean absolute difference between neighbours, across and down: how crisp the print is.
fn sharpness(page: &Luma) -> f32 {
    let (w, h) = (page.width as usize, page.height as usize);
    let mut sum = 0f64;
    for y in 0..h - 1 {
        for x in 0..w - 1 {
            let v = i32::from(page.data[y * w + x]);
            sum += f64::from((v - i32::from(page.data[y * w + x + 1])).abs() + (v - i32::from(page.data[(y + 1) * w + x])).abs());
        }
    }
    (sum / ((w - 1) * (h - 1)) as f64) as f32
}

fn blank(w: u32, h: u32, grey: u8) -> Luma {
    Luma { width: w, height: h, data: vec![grey; (w * h) as usize] }
}

/// A ruled table: dark lines `thick` pixels wide every `pitch` pixels, both ways.
fn ruled(pitch: u32, thick: u32) -> Luma {
    let (w, h) = (1280, 1280);
    let data = (0..w * h)
        .map(|i| {
            let (x, y) = (i % w, i / w);
            if x % pitch < thick || y % pitch < thick { 40 } else { 235 }
        })
        .collect();
    Luma { width: w, height: h, data }
}

/// A dotted background: round dots of radius `r` every `pitch` pixels.
fn dotted(pitch: u32, r: f32) -> Luma {
    let (w, h) = (1280, 1280);
    let c = pitch as f32 / 2.0;
    let data = (0..w * h)
        .map(|i| {
            let (x, y) = ((i % w) % pitch, (i / w) % pitch);
            if (x as f32 + 0.5 - c).hypot(y as f32 + 0.5 - c) <= r { 150 } else { 240 }
        })
        .collect();
    Luma { width: w, height: h, data }
}

#[test]
fn the_fft_finds_a_cosine_and_comes_back() {
    let n = 64;
    let original: Vec<f32> = (0..n).map(|i| (std::f32::consts::TAU * 5.0 * i as f32 / n as f32).cos() + 0.25).collect();
    let mut re = original.clone();
    let mut im = vec![0f32; n];
    fft(&mut re, &mut im, false);
    // all of the cosine is in bins 5 and n - 5, half each; the offset is in bin 0
    assert!((re[5] - n as f32 / 2.0).abs() < 1e-3 && (re[n - 5] - n as f32 / 2.0).abs() < 1e-3, "{} {}", re[5], re[n - 5]);
    assert!((re[0] - 0.25 * n as f32).abs() < 1e-3);
    let others = (1..n).filter(|&k| k != 5 && k != n - 5).map(|k| re[k].hypot(im[k])).fold(0f32, f32::max);
    assert!(others < 1e-3, "{others}");
    fft(&mut re, &mut im, true);
    for (a, b) in re.iter().zip(&original) {
        assert!((a / n as f32 - b).abs() < 1e-5);
    }
}

/// The issue's first test: a page times a grating with a period of 3 to 12 pixels, at any
/// angle, with 10 to 30 % modulation, is found, at its own period and angle.
#[test]
fn a_grating_on_a_page_is_a_screen() {
    let page = print_sheet();
    for period in [3.0f32, 4.5, 7.0, 12.0] {
        for degrees in [0.0f32, 30.0, 45.0, 77.0, 120.0] {
            for modulation in [0.1f32, 0.3] {
                let seen = find(&grated(&page, period, degrees, modulation));
                let label = format!("period {period}, {degrees}°, {modulation}: {seen:?}");
                assert_eq!(seen.kind, MoireKind::Screen, "{label}");
                let main = seen.frequencies[0];
                assert!((main.period() - period).abs() <= period * 0.06, "{label}");
                let angle_off = (main.angle() - degrees).rem_euclid(180.0);
                assert!(angle_off.min(180.0 - angle_off) <= 3.0, "{label}");
            }
        }
    }
}

/// The issue's second test: plain print, a ruled table and a dotted background with a period
/// over 20 pixels are not taken for a screen or a halftone.
#[test]
fn print_tables_and_wide_dots_are_not_moire() {
    for (name, page) in pages().into_iter().chain([("sheet", print_sheet())]) {
        let seen = find(&page);
        assert_eq!(seen.kind, MoireKind::None, "{name}: {seen:?}");
    }
    for pitch in [24, 32, 40, 57] {
        for thick in [1, 3] {
            let seen = find(&ruled(pitch, thick));
            assert_eq!(seen.kind, MoireKind::None, "table every {pitch} px, {thick} px lines: {seen:?}");
        }
    }
    for pitch in [21, 26, 34] {
        let seen = find(&dotted(pitch, pitch as f32 / 4.0));
        assert_eq!(seen.kind, MoireKind::None, "dots every {pitch} px: {seen:?}");
    }
    // blank paper, and a page too small for one square
    let seen = find(&blank(1024, 1024, 230));
    assert_eq!((seen.kind, seen.busy_tiles), (MoireKind::None, 0));
    let seen = find(&grated(&blank(200, 900, 230), 5.0, 0.0, 0.3));
    assert_eq!((seen.kind, seen.tiles), (MoireKind::None, 0));
}

fn dots_on_sheet() -> Luma {
    let mut page = print_sheet();
    let w = page.width as usize;
    // a 768×768 picture of dots every 4 pixels, its grey shading top to bottom
    let (x0, y0) = (256usize, 512usize);
    for y in y0..y0 + 3 * TILE {
        for x in x0..x0 + 3 * TILE {
            let shade = 80.0 + 120.0 * (y - y0) as f32 / (3 * TILE) as f32;
            let dot = (x % 4 < 2) && (y % 4 < 2);
            page.data[y * w + x] = if dot { (shade * 0.6) as u8 } else { shade as u8 };
        }
    }
    page
}

/// A printed photograph made of dots on a page of print: the dots are a compact block of
/// periodic squares, not the whole page.
#[test]
fn a_block_of_dots_is_a_halftone() {
    let page = dots_on_sheet();
    let seen = find(&page);
    assert_eq!(seen.kind, MoireKind::Halftone, "{seen:?}");
    assert!(seen.periodic_tiles >= 4 && seen.periodic_tiles <= 16, "{seen:?}");
    assert!(seen.period().is_some_and(|p| (p - 4.0).abs() < 0.3 || (p - 2.83).abs() < 0.3), "{seen:?}");
}

/// The issue's notch test: the grating's amplitude falls by at least 80 %, and the print's
/// sharpness by less than 10 % compared with the page before the grating (the ripple left over
/// can only raise that measure, so it is checked one way).
#[test]
fn notches_take_the_grating_out_and_leave_the_print() {
    let page = print_sheet();
    let before = sharpness(&page);
    for (period, degrees) in [(3.0f32, 0.0f32), (5.0, 45.0), (8.0, 77.0), (12.0, 120.0)] {
        let photo = grated(&page, period, degrees, 0.2);
        let seen = find(&photo);
        assert_eq!(seen.kind, MoireKind::Screen, "{period} {degrees}: {seen:?}");
        let cleaned = remove(&photo, &seen);
        // measured against the page darkened by the grating's mean, so only the ripple counts
        let mean = grated(&page, period, degrees, 0.0);
        let dimmed = Luma { data: mean.data.iter().map(|&v| (f32::from(v) * 0.9).round() as u8).collect(), ..mean };
        let left = amplitude(&cleaned, &dimmed, period, degrees);
        let had = amplitude(&photo, &dimmed, period, degrees);
        assert!(left <= 0.2 * had, "period {period}, {degrees}°: amplitude {had:.2} -> {left:.2}");
        // the print is not blurred: what is left of the ripple can only add to this measure
        let after = sharpness(&cleaned) / 0.9;
        assert!(after > 0.9 * before, "period {period}, {degrees}°: sharpness {before:.3} -> {after:.3}");
        eprintln!("period {period}, {degrees}°: amplitude {had:.2} -> {left:.2}, sharpness {before:.3} -> {after:.3}");
    }
}

/// Where no pattern was found, nothing changes; where the page has none but the frequency is
/// given, the squares without a peak there are left as they were: under 1 % of the pixels
/// change by more than a grey level.
#[test]
fn notches_leave_pages_without_the_pattern_alone() {
    let page = english();
    let seen = find(&page);
    assert_eq!(remove(&page, &seen), page);
    let forced = crate::moire::Moire { frequencies: vec![Frequency { u: 40.0, v: 13.0 }], kind: MoireKind::Screen, ..seen };
    let out = remove(&page, &forced);
    let changed = out.data.iter().zip(&page.data).filter(|(a, b)| a.abs_diff(**b) > 1).count();
    assert!(changed * 100 < page.data.len(), "{changed} of {} pixels changed by more than one level", page.data.len());
}

/// The same page gives the same answer and the same pixels every time.
#[test]
fn moire_is_deterministic() {
    let photo = grated(&english(), 6.0, 30.0, 0.2);
    let (a, b) = (find(&photo), find(&photo));
    assert_eq!(a, b);
    assert_eq!(remove(&photo, &a), remove(&photo, &b));
}

/// Time on a 12-megapixel page (4000 × 3000) made of the print sheet repeated, with a grating:
/// not a test of anything, run by hand with `--ignored --nocapture` for the figures in
/// `docs/ANDROID_SCAN_MOIRE.md`.
#[test]
#[ignore]
fn moire_timing() {
    let sheet = print_sheet();
    let (w, h) = (4000usize, 3000usize);
    let (sw, sh) = (sheet.width as usize, sheet.height as usize);
    let data = (0..w * h).map(|i| sheet.data[(i / w % sh) * sw + i % w % sw]).collect();
    let page = grated(&Luma { width: w as u32, height: h as u32, data }, 5.0, 30.0, 0.2);
    let t = std::time::Instant::now();
    let seen = find(&page);
    let found = t.elapsed();
    let t = std::time::Instant::now();
    let _ = remove(&page, &seen);
    eprintln!("find {:?} ({:?}), remove {:?}", found, seen.kind, t.elapsed());
}
