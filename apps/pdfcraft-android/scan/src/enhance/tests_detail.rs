//! Grain and sharpening (#299, part 1) on drawn pages whose truth is known. (What sharpening
//! does to the match with a reference scanner on real photos is measured outside the
//! repository; the numbers are in `docs/ANDROID_SCAN_DETAIL.md`.)

use super::background::background;
use super::detail::{NOISE_FLOOR, denoise, noise_sigma, sharpen};
use super::plane::Mask;
use super::{AutoParams, auto};
use crate::Rgb;

/// A small deterministic random source.
struct Rng(u64);

impl Rng {
    fn unit(&mut self) -> f32 {
        self.0 = self.0.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        ((self.0 >> 40) as f32) / (1u64 << 24) as f32
    }

    /// Roughly normal, standard deviation 1.
    fn normal(&mut self) -> f32 {
        ((0..12).map(|_| self.unit()).sum::<f32>()) - 6.0
    }
}

/// A `w` × `h` grey page: `value(x, y)` in grey levels, with grain of standard deviation `noise`
/// (the same in all three colours, so it is the luminance's grain).
fn page(w: usize, h: usize, noise: f32, seed: u64, value: impl Fn(usize, usize) -> f32) -> Rgb {
    let mut rng = Rng(seed);
    let mut data = Vec::with_capacity(w * h * 3);
    for y in 0..h {
        for x in 0..w {
            let v = (value(x, y) + noise * rng.normal()).round().clamp(0.0, 255.0) as u8;
            data.extend([v, v, v]);
        }
    }
    Rgb { width: w as u32, height: h as u32, data }
}

/// Paper with rows of dark bars like lines of print; `every` is the distance between rows.
fn print(every: usize) -> impl Fn(usize, usize) -> f32 {
    move |x, y| if y % every < 5 && (40..560).contains(&x) && (x / 23) % 4 != 3 && y > 30 { 40.0 } else { 235.0 }
}

fn luma_of(page: &Rgb, x: usize, y: usize) -> f32 {
    let i = (y * page.width as usize + x) * 3;
    0.299 * f32::from(page.data[i]) + 0.587 * f32::from(page.data[i + 1]) + 0.114 * f32::from(page.data[i + 2])
}

/// The standard deviation of the luminance over a rectangle.
fn spread(page: &Rgb, (x0, y0): (usize, usize), (x1, y1): (usize, usize)) -> f32 {
    let values: Vec<f32> = (y0..y1).flat_map(|y| (x0..x1).map(move |x| (x, y))).map(|(x, y)| luma_of(page, x, y)).collect();
    let mean = values.iter().sum::<f32>() / values.len() as f32;
    (values.iter().map(|v| (v - mean).powi(2)).sum::<f32>() / values.len() as f32).sqrt()
}

fn with(page: &Rgb, data: Vec<u8>) -> Rgb {
    Rgb { width: page.width, height: page.height, data }
}

/// The grain is measured to a quarter of the truth from one grey level to ten, and a page full
/// of print reads within a fifth of an empty one.
#[test]
fn grain_is_measured_on_the_paper_whatever_is_printed() {
    for sigma in [1.0f32, 3.0, 6.0, 10.0] {
        let blank = page(600, 800, sigma, 3, |_, _| 235.0);
        let empty = noise_sigma(&blank, &background(&blank, false).light);
        let sparse = page(600, 800, sigma, 4, print(40));
        let some = noise_sigma(&sparse, &background(&sparse, false).light);
        let dense = page(600, 800, sigma, 5, print(11));
        let full = noise_sigma(&dense, &background(&dense, false).light);
        println!("grain {sigma}: empty page {empty:.2}, some print {some:.2}, dense print {full:.2}");
        for found in [empty, some, full] {
            assert!((found / sigma - 1.0).abs() <= 0.25, "grain {sigma}: measured {found}");
        }
        assert!((full / empty - 1.0).abs() <= 0.2 && (some / empty - 1.0).abs() <= 0.2, "grain {sigma}: {empty} {some} {full}");
    }
    // a clean page has none to speak of, and a page too small to ask says none
    let clean = page(600, 800, 0.0, 6, print(16));
    assert!(noise_sigma(&clean, &background(&clean, false).light) < 0.2);
    let tiny = page(2, 2, 0.0, 7, |_, _| 200.0);
    assert_eq!(noise_sigma(&tiny, &background(&clean, false).light), 0.0);
}

/// The share of a normal distribution of standard deviation `blur` that lies below `t`, to four
/// decimals: a hard edge at 0 seen through that much blur.
fn below(t: f32, blur: f32) -> f32 {
    let t = t / (blur * std::f32::consts::SQRT_2);
    let erf = t.signum() * (1.0 - 1.0 / (1.0 + 0.278393 * t.abs() + 0.230389 * t * t + 0.000972 * t.abs().powi(3) + 0.078108 * t.powi(4)).powi(4));
    0.5 * (1.0 + erf)
}

/// A soft edge between `low` and `high` at `x = 100`, blurred by `blur` pixels.
fn edge(low: f32, high: f32, blur: f32) -> impl Fn(usize, usize) -> f32 {
    move |x, _| low + (high - low) * below(x as f32 - 99.5, blur)
}

/// How many pixels the middle row takes to rise from 10 % to 90 % of the way from `low` to
/// `high`, read between pixels.
fn rise(page: &Rgb, low: f32, high: f32) -> f32 {
    let row: Vec<f32> = (0..page.width as usize).map(|x| luma_of(page, x, page.height as usize / 2)).collect();
    let crossing = |level: f32| {
        let i = row.iter().position(|&v| v >= level).unwrap_or(0).max(1);
        (i - 1) as f32 + (level - row[i - 1]) / (row[i] - row[i - 1]).max(1e-3)
    };
    crossing(low + 0.9 * (high - low)) - crossing(low + 0.1 * (high - low))
}

/// A soft edge gets steeper, never overshoots by more than four grey levels, flat grainy paper
/// stays as grainy as it was, and a setting of nothing changes nothing.
#[test]
fn print_gets_crisper_without_halos_or_grain() {
    let (low, high) = (60.0, 200.0);
    let soft = page(200, 40, 0.0, 1, edge(low, high, 1.2));
    let before = rise(&soft, low, high);
    // (#299 asks for a quarter less; its own formula gives 18 % at the strongest setting)
    for (k_sharp, at_least) in [(0.3, 0.05), (1.0, 0.15)] {
        let crisp = with(&soft, sharpen(&soft, k_sharp, 0.0, 300.0, None, 1));
        let after = rise(&crisp, low, high);
        let (darkest, lightest) = crisp.data.iter().fold((255u8, 0u8), |(a, b), &v| (a.min(v), b.max(v)));
        println!("setting {k_sharp}: the edge rises in {after:.2} pixels (was {before:.2}); values {darkest}-{lightest}");
        assert!(after <= before * (1.0 - at_least), "setting {k_sharp}: {before} -> {after}");
        assert!(f32::from(lightest) <= high + 4.5 && f32::from(darkest) >= low - 4.5, "setting {k_sharp}: {darkest}-{lightest}");
    }
    // nothing asked, nothing done
    assert_eq!(sharpen(&soft, 0.0, 0.0, 300.0, None, 1), soft.data);
    assert_eq!(sharpen(&soft, f32::NAN, 0.0, 300.0, None, 1), soft.data);
    // grainy paper with nothing on it: within its grain nothing is sharpened
    let grainy = page(300, 300, 4.0, 2, |_, _| 220.0);
    let sharpened = with(&grainy, sharpen(&grainy, 1.0, 4.0, 300.0, None, 1));
    let (was, is) = (spread(&grainy, (10, 10), (290, 290)), spread(&sharpened, (10, 10), (290, 290)));
    println!("grain of 4 on empty paper: {was:.2} before, {is:.2} after sharpening");
    assert!(is <= was * 1.05, "{was} -> {is}");
    // told there is no grain, the same page is sharpened: the gate is what protects it
    let fooled = with(&grainy, sharpen(&grainy, 1.0, 0.0, 300.0, None, 1));
    assert!(spread(&fooled, (10, 10), (290, 290)) > was * 1.2);
    // a higher resolution looks wider around: the same edge at 600 dpi is two pixels' worth
    let wide = with(&soft, sharpen(&soft, 1.0, 0.0, 600.0, None, 1));
    assert_ne!(wide.data, sharpen(&soft, 1.0, 0.0, 300.0, None, 1));
}

/// Inside a photograph the sharpening is 0.3 of what print gets.
#[test]
fn photographs_are_sharpened_gently() {
    let soft = page(200, 40, 0.0, 1, edge(60.0, 200.0, 1.2));
    let full = with(&soft, sharpen(&soft, 1.0, 0.0, 300.0, None, 1));
    // the mask is at a smaller size, as the look's object mask is: all of it set
    let everywhere = Mask { width: 20, height: 4, data: vec![true; 80] };
    let gentle = with(&soft, sharpen(&soft, 1.0, 0.0, 300.0, Some(&everywhere), 1));
    let change = |a: &Rgb| (0..200).map(|x| (luma_of(a, x, 20) - luma_of(&soft, x, 20)).abs()).fold(0.0f32, f32::max);
    println!("largest change: {:.1} in print, {:.1} in a photograph", change(&full), change(&gentle));
    assert!(change(&gentle) > 0.0 && change(&gentle) <= 0.3 * change(&full) + 1.0, "{} against {}", change(&gentle), change(&full));
    // a mask with nothing set, or one that is not its size, changes nothing
    let nowhere = Mask { width: 20, height: 4, data: vec![false; 80] };
    assert_eq!(sharpen(&soft, 1.0, 0.0, 300.0, Some(&nowhere), 1), full.data);
    let broken = Mask { width: 20, height: 4, data: vec![true; 7] };
    assert_eq!(sharpen(&soft, 1.0, 0.0, 300.0, Some(&broken), 1), full.data);
}

/// Grain of six grey levels on empty paper falls by 40 % or more, a line a pixel and a half
/// wide keeps its contrast, coloured speckle goes, and a page with little grain is not touched.
#[test]
fn heavy_grain_is_smoothed_and_thin_lines_are_kept() {
    let sigma = 6.0;
    // a thin dark line down the page: 1.5 pixels wide (one full pixel and half of the next)
    let line = |x: usize, _: usize| match x {
        150 => 60.0,
        151 => 147.5,
        _ => 235.0,
    };
    let grainy = page(300, 300, sigma, 8, line);
    let smooth = with(&grainy, denoise(&grainy, sigma, 1));
    let (was, is) = (spread(&grainy, (10, 10), (140, 290)), spread(&smooth, (10, 10), (140, 290)));
    println!("grain of {sigma}: {was:.2} before, {is:.2} after");
    assert!(is <= 0.6 * was, "{was} -> {is}");
    // the line's contrast: paper beside it less the line itself, averaged down the page
    let contrast = |p: &Rgb| (20..280).map(|y| luma_of(p, 140, y) - luma_of(p, 150, y)).sum::<f32>() / 260.0;
    println!("the line's contrast: {:.1} before, {:.1} after", contrast(&grainy), contrast(&smooth));
    assert!(contrast(&smooth) >= 0.92 * contrast(&grainy), "{} -> {}", contrast(&grainy), contrast(&smooth));
    // under the floor the page is handed back
    assert_eq!(denoise(&grainy, NOISE_FLOOR - 0.01, 1), grainy.data);
    assert_eq!(denoise(&grainy, f32::NAN, 1), grainy.data);
    // coloured speckle: each colour its own grain
    let mut rng = Rng(9);
    let speckled =
        Rgb { width: 200, height: 200, data: (0..200 * 200 * 3).map(|_| (200.0 + 8.0 * rng.normal()).round().clamp(0.0, 255.0) as u8).collect() };
    let calm = with(&speckled, denoise(&speckled, 5.0, 1));
    let colour = |p: &Rgb| {
        let d: Vec<f32> = p.data.chunks_exact(3).map(|px| f32::from(px[0]) - f32::from(px[2])).collect();
        let mean = d.iter().sum::<f32>() / d.len() as f32;
        (d.iter().map(|v| (v - mean).powi(2)).sum::<f32>() / d.len() as f32).sqrt()
    };
    println!("red against blue: {:.2} before, {:.2} after", colour(&speckled), colour(&calm));
    assert!(colour(&calm) <= 0.3 * colour(&speckled));
}

/// The same bytes for any number of threads, and whatever the page's height is against the
/// band the rows are worked in.
#[test]
fn the_bytes_do_not_depend_on_the_threads() {
    for h in [5usize, 96, 97, 300] {
        let grainy = page(160, h, 5.0, 10 + h as u64, print(13));
        let (sharp, smooth) = (sharpen(&grainy, 0.6, 5.0, 300.0, None, 1), denoise(&grainy, 5.0, 1));
        for threads in [2, 3, 8, 50] {
            assert_eq!(sharpen(&grainy, 0.6, 5.0, 300.0, None, threads), sharp, "{h} rows, {threads} threads");
            assert_eq!(denoise(&grainy, 5.0, threads), smooth, "{h} rows, {threads} threads");
        }
        assert_ne!(sharp, grainy.data);
        assert_ne!(smooth, grainy.data);
    }
}

/// Through the Auto look: the grain is reported, a grainy page is smoothed when that is asked
/// for, and print is crisper with sharpening than without.
#[test]
fn the_auto_look_reports_the_grain_and_uses_both() {
    let soft = page(600, 800, 0.0, 11, |x, y| {
        // rows of soft print: bars six pixels high every sixteen, out of focus by a pixel and a half
        let d = (y % 16) as f32;
        let ink = below(d - 5.0, 1.5) - below(d - 11.0, 1.5);
        if (40..560).contains(&x) && y > 30 { 235.0 - 150.0 * ink } else { 235.0 }
    });
    let look = |sharpen, denoise, p: &Rgb| auto(p, &AutoParams { sharpen, denoise, ..AutoParams::default() }).unwrap();
    let (plain, crisp) = (look(0, false, &soft), look(100, false, &soft));
    // no grain was drawn; soft print this close together leaves under one grey level of curve in
    // the paper between the rows, far below what is taken for grain
    assert!(plain.noise < 1.0 && plain.noise == crisp.noise, "{} {}", plain.noise, crisp.noise);
    // steeper edges: the same rise in fewer, larger steps, so the squares of the steps add up to more
    let steps = |p: &Rgb| (41..799).map(|y| (luma_of(p, 300, y) - luma_of(p, 300, y - 1)).powi(2)).sum::<f32>();
    println!("squared steps down a column: {:.0} without sharpening, {:.0} with", steps(&plain.rgb), steps(&crisp.rgb));
    assert!(steps(&crisp.rgb) > 1.02 * steps(&plain.rgb));
    assert_ne!(look(30, false, &soft).rgb, plain.rgb);
    // a grainy page: measured, and smoother with denoising than without
    let grainy = page(600, 800, 7.0, 12, print(16));
    let (kept, smoothed) = (look(0, false, &grainy), look(0, true, &grainy));
    println!("measured grain: {:.2}", kept.noise);
    assert!((kept.noise / 7.0 - 1.0).abs() < 0.25 && kept.noise == smoothed.noise);
    assert_ne!(kept.rgb, smoothed.rgb);
    // a page with little grain is the same with denoising on or off
    assert_eq!(look(0, true, &soft).rgb, plain.rgb);
}

/// Not a requirement, a record: how long a 12 MP page takes on this machine.
#[test]
fn time_at_12_megapixels() {
    let grainy = page(4000, 3000, 4.0, 13, print(40));
    let clock = std::time::Instant::now();
    let grain = noise_sigma(&grainy, &background(&grainy, false).light);
    println!("12 MP: the light and the grain ({grain:.2}) in {:.0} ms", clock.elapsed().as_secs_f64() * 1e3);
    for threads in [1, 4] {
        let clock = std::time::Instant::now();
        let sharp = sharpen(&grainy, 0.3, grain, 300.0, None, threads);
        let sharpened = clock.elapsed().as_secs_f64() * 1e3;
        let clock = std::time::Instant::now();
        let smooth = denoise(&grainy, grain, threads);
        println!("12 MP, {threads} thread(s): sharpening {sharpened:.0} ms, smoothing {:.0} ms", clock.elapsed().as_secs_f64() * 1e3);
        assert!(sharp.len() == grainy.data.len() && smooth.len() == grainy.data.len());
    }
}
