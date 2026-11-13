//! Developer tool for #446: how long Clean scan takes on a preview-sized page, part by part, the
//! first time and for each strength or paper change after it. Not part of the app.
//!
//! ```text
//! cargo run --release -p pdfcraft-scan --example scan_look_timing [-- <threads>]
//! ```
//!
//! The pages are the repository's own drawn test pages (`tests/data/upright-*.png`), laid on a
//! darker table under light that falls off across the photo, with some grain, and saved as JPEG:
//! a stand-in for a phone photo, not a phone photo. Each is straightened to a 1280-pixel preview
//! (as the scan screen asks), then given Clean scan with the document types on, first once and
//! then at nine strengths and both paper choices, once keeping the light estimate with the page
//! (`reuse_look`, the default) and once measuring it again every time (as before #446). Every
//! look is checked to give the same bytes both ways. Times are this computer's, not a phone's.

use std::time::Instant;

use pdfcraft_scan::page::page_look_kind;
use pdfcraft_scan::page_kind::PresetChoice;
use pdfcraft_scan::{Look, PageRequest, look::Pixels, page_base};

const PAGES: [(&str, &[u8]); 3] = [
    ("english", include_bytes!("../tests/data/upright-english.png")),
    ("arabic", include_bytes!("../tests/data/upright-arabic.png")),
    ("form", include_bytes!("../tests/data/upright-form.png")),
];

/// A photo of `page`: on a table, under uneven light, with grain, as a JPEG.
fn photo(page: &[u8]) -> (Vec<u8>, [f32; 8]) {
    let drawn = image::load_from_memory(page).unwrap().to_rgb8();
    let (pw, ph) = (3000u32, 4000u32);
    let large = image::imageops::resize(&drawn, 2700, 3600, image::imageops::FilterType::CatmullRom);
    let mut seed = 0x9e37_79b9_7f4a_7c15u64;
    let mut grain = move || {
        seed ^= seed << 13;
        seed ^= seed >> 7;
        seed ^= seed << 17;
        ((seed >> 40) % 13) as f32 - 6.0
    };
    let out = image::RgbImage::from_fn(pw, ph, |x, y| {
        let inside = (150..2850).contains(&x) && (200..3800).contains(&y);
        let base = if inside { *large.get_pixel(x - 150, y - 200) } else { image::Rgb([70, 60, 52]) };
        // light falling off from the top left, a little warm
        let light = 1.0 - 0.35 * (x as f32 / pw as f32 + y as f32 / ph as f32) / 2.0;
        let g = grain();
        image::Rgb([0, 1, 2].map(|c| {
            let warm = [1.0, 0.96, 0.88][c];
            (f32::from(base.0[c]) * light * warm + g).clamp(0.0, 255.0) as u8
        }))
    });
    let mut jpeg = Vec::new();
    image::codecs::jpeg::JpegEncoder::new_with_quality(&mut jpeg, 90).encode_image(&out).unwrap();
    let corners =
        [150.0 / 3000.0, 200.0 / 4000.0, 2850.0 / 3000.0, 200.0 / 4000.0, 2850.0 / 3000.0, 3800.0 / 4000.0, 150.0 / 3000.0, 3800.0 / 4000.0];
    (jpeg, corners)
}

fn pixels(p: &Pixels) -> &[u8] {
    match p {
        Pixels::Rgb(v) | Pixels::Grey(v) => v,
        Pixels::Bilevel(_) => &[],
    }
}

fn median(v: &mut [f64]) -> f64 {
    v.sort_by(f64::total_cmp);
    v[v.len() / 2]
}

fn main() {
    let threads = std::env::args()
        .nth(1)
        .and_then(|t| t.parse().ok())
        .unwrap_or_else(|| std::thread::available_parallelism().map_or(1, usize::from).min(pdfcraft_scan::warp::MAX_THREADS));
    println!("threads {threads}; times in ms on this computer");
    for (name, page) in PAGES {
        let (jpeg, corners) = photo(page);
        let ask = |strength: u8, keep: Option<bool>, reuse: bool| PageRequest {
            look: Look::Auto,
            strength,
            threads,
            preset: PresetChoice::Auto,
            fixed_strength: true,
            max_side: Some(1280),
            keep_paper: keep,
            reuse_look: reuse,
            ..PageRequest::new(&jpeg, corners)
        };
        for reuse in [false, true] {
            let base = page_base(&ask(60, None, reuse)).unwrap();
            let clock = Instant::now();
            let (first, _) = page_look_kind(&base, &ask(60, None, reuse)).unwrap();
            let first_ms = clock.elapsed().as_secs_f64() * 1000.0;
            let mut changes = Vec::new();
            let mut parts: Vec<(&str, Vec<f64>)> = Vec::new();
            for keep in [None, Some(true)] {
                for strength in (20..=100).step_by(10) {
                    let clock = Instant::now();
                    let (made, _) = page_look_kind(&base, &ask(strength, keep, reuse)).unwrap();
                    changes.push(clock.elapsed().as_secs_f64() * 1000.0);
                    for &(stage, ms) in &made.stages {
                        match parts.iter_mut().find(|(s, _)| *s == stage) {
                            Some((_, v)) => v.push(f64::from(ms)),
                            None => parts.push((stage, vec![f64::from(ms)])),
                        }
                    }
                    // the same bytes as a look measured from nothing
                    let fresh = page_base(&ask(60, None, false)).unwrap();
                    let (alone, _) = page_look_kind(&fresh, &ask(strength, keep, false)).unwrap();
                    assert!(pixels(&made.pixels) == pixels(&alone.pixels), "{name} {strength} {keep:?}: kept and fresh differ");
                }
            }
            let first_parts: Vec<String> = first.stages.iter().map(|(s, ms)| format!("{s} {ms}")).collect();
            let change_parts: Vec<String> = parts.iter_mut().map(|(s, v)| format!("{s} {:.0}", median(v))).collect();
            println!(
                "{name} {}x{} reuse={reuse}: first look {first_ms:.0} ({}); per change median {:.0}, max {:.0} ({})",
                base.page.width,
                base.page.height,
                first_parts.join(", "),
                median(&mut changes.clone()),
                changes.iter().copied().fold(0.0, f64::max),
                change_parts.join(", "),
            );
        }
    }
}
