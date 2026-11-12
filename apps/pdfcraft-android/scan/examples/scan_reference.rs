//! Developer tool for the private reference pairs of #288 (section 8). Not part of the app.
//!
//! A reference pair is a photo and what another scanner made of it. `tools/scan-eval/
//! reference_pairs.py register <dir>` lines each pair up and writes `<name>.aligned.png` (the
//! photo on the reference's pixel grid) and `<name>.ref.json` (the reference's crop).
//!
//! ```text
//! cargo run -p pdfcraft-scan --example scan_reference -- look <dir> <out_dir> [strength] [sharpen]
//!     the Auto look on every <dir>/<name>.aligned.png  ->  <out_dir>/<name>.png (sharpen 0-100,
//!     #299; the default when left out)
//!     then: python3 tools/scan-eval/reference_pairs.py compare <dir> <out_dir>
//!
//! cargo run -p pdfcraft-scan --example scan_reference -- page <dir> <out_dir> [strength]
//!     the whole pipeline (decode, straighten to the reference's corners, Auto, encode) on every
//!     <dir>/<name>.before.jpg that has a <name>.ref.json  ->  <out_dir>/<name>.jpg
//!
//! cargo run -p pdfcraft-scan --example scan_reference -- bw <dir> <out_dir> [strength]
//!     the same pipeline in black and white (#300)  ->  <out_dir>/<name>.png (1 bit per pixel)
//!
//! cargo run -p pdfcraft-scan --example scan_reference -- detect <dir> <out_dir>
//!     where the page is found in every <dir>/<name>.before.jpg (#290) and after the edges are
//!     refined (#291), against the reference's crop: the largest corner distance in % of the
//!     photo's diagonal. Nothing is written.
//!
//! cargo run -p pdfcraft-scan --example scan_reference -- flatten <dir> <out_dir>
//!     how bent the flattening network finds each page (#302), and the page flattened
//!     (<name>.png) and by perspective alone (<name>.plain.png), both without a look
//!
//! cargo run -p pdfcraft-scan --example scan_reference -- upright <dir> <out_dir>
//!     which way up the orientation classifier finds each page (#339): the page straightened to
//!     the reference's corners, then turned four ways. The four answers must name the same
//!     upright page; the turn that puts the page as the reference has it upright is printed
//!     with the confidence, the tiles' agreement and the time. Nothing is written.
//! ```
//!
//! The pairs are someone's documents: keep the folders outside the repository.

use std::path::{Path, PathBuf};
use std::time::Instant;

use pdfcraft_scan::{AutoParams, Look, PageRequest, PaperMode, Photo, Pyramid, RefineOptions, Rgb, auto, detect, process_page, refine};

fn files(dir: &Path, suffix: &str) -> Vec<PathBuf> {
    let mut found: Vec<PathBuf> = std::fs::read_dir(dir)
        .unwrap_or_else(|e| panic!("{}: {e}", dir.display()))
        .filter_map(|e| e.ok().map(|e| e.path()))
        .filter(|p| p.to_string_lossy().ends_with(suffix))
        .collect();
    found.sort();
    found
}

fn name_of(path: &Path, suffix: &str) -> String {
    let file = path.file_name().map(|f| f.to_string_lossy().into_owned()).unwrap_or_default();
    file.strip_suffix(suffix).unwrap_or(&file).to_owned()
}

/// The eight numbers after `"corners":` in a `.ref.json`, limited to the photo (a corner outside
/// the frame is not accepted by the pipeline yet, #291).
fn corners(json: &str) -> Option<[f32; 8]> {
    let list = json.split("\"corners\":").nth(1)?.split('[').nth(1)?.split(']').next()?;
    let v: Vec<f32> = list.split(',').filter_map(|s| s.trim().parse::<f32>().ok()).map(|v| v.clamp(0.0, 1.0)).collect();
    v.try_into().ok()
}

fn look(dir: &Path, out: &Path, strength: u8, sharpen: u8, threads: usize) {
    for path in files(dir, ".aligned.png") {
        let name = name_of(&path, ".aligned.png");
        let img = image::open(&path).unwrap_or_else(|e| panic!("{}: {e}", path.display())).to_rgb8();
        let page = Rgb { width: img.width(), height: img.height(), data: img.into_raw() };
        let clock = Instant::now();
        let Some(result) = auto(&page, &AutoParams { strength, paper: PaperMode::White, threads, sharpen, denoise: true, dpi: 300 }) else {
            continue;
        };
        let ms = clock.elapsed().as_millis();
        let megapixels = f64::from(page.width) * f64::from(page.height) / 1e6;
        println!(
            "{name}: {megapixels:.1} MP, {ms} ms, closing radius {}, objects {:.1} %, grain {:.2}",
            result.closing_radius,
            result.objects.share() * 100.0,
            result.noise
        );
        let picture = image::RgbImage::from_raw(result.rgb.width, result.rgb.height, result.rgb.data).expect("the look keeps the page's size");
        picture.save(out.join(format!("{name}.png"))).unwrap_or_else(|e| panic!("{name}: {e}"));
    }
}

fn page(dir: &Path, out: &Path, look: Look, strength: u8, threads: usize) {
    for path in files(dir, ".before.jpg") {
        let name = name_of(&path, ".before.jpg");
        let Some(corners) = std::fs::read_to_string(dir.join(format!("{name}.ref.json"))).ok().as_deref().and_then(corners) else {
            println!("{name}: no corners (run reference_pairs.py register first)");
            continue;
        };
        let photo = std::fs::read(&path).unwrap_or_else(|e| panic!("{}: {e}", path.display()));
        match process_page(&PageRequest { look, strength, threads, ..PageRequest::new(&photo, corners) }) {
            Ok(result) => {
                let stages: Vec<String> = result.timings.iter().map(|(stage, ms)| format!("{stage} {ms} ms")).collect();
                println!(
                    "{name}: {} x {} at {} dpi, {} KiB, {}",
                    result.width,
                    result.height,
                    result.dpi,
                    result.bytes.len() / 1024,
                    stages.join(", ")
                );
                std::fs::write(out.join(format!("{name}.{}", result.format.extension())), &result.bytes).unwrap_or_else(|e| panic!("{name}: {e}"));
            }
            Err(e) => println!("{name}: {e}"),
        }
    }
}

/// The eight numbers after `"corners":` as they are (a reference corner may lie outside the photo).
fn reference_corners(json: &str) -> Option<[f64; 8]> {
    let list = json.split("\"corners\":").nth(1)?.split('[').nth(1)?.split(']').next()?;
    let v: Vec<f64> = list.split(',').filter_map(|s| s.trim().parse().ok()).collect();
    v.try_into().ok()
}

fn find(dir: &Path) {
    for path in files(dir, ".before.jpg") {
        let name = name_of(&path, ".before.jpg");
        let Some(reference) = std::fs::read_to_string(dir.join(format!("{name}.ref.json"))).ok().as_deref().and_then(reference_corners) else {
            continue;
        };
        let Ok(photo) = Photo::decode(&std::fs::read(&path).unwrap_or_default()) else { continue };
        let (w, h) = (f64::from(photo.width), f64::from(photo.height));
        let off = |q: &pdfcraft_scan::Quad| {
            (0..4).map(|i| (q.0[i][0] - reference[2 * i] * w).hypot(q.0[i][1] - reference[2 * i + 1] * h) / w.hypot(h) * 100.0).fold(0.0, f64::max)
        };
        let clock = Instant::now();
        let found = detect(&photo);
        let ms = clock.elapsed().as_millis();
        let Some(quad) = found.quad else {
            println!("{name}: nothing ({:?}, confidence {:.2}, {} passes, {ms} ms)", found.source, found.confidence, found.passes);
            continue;
        };
        let refined = refine(&Pyramid::new(photo.luma()), &quad, &RefineOptions::default());
        let sides = refined.sides.iter().filter(|s| s.refined).count();
        println!(
            "{name}: {:?} confidence {:.2}, turned {}, {} passes, {ms} ms: {:.1} % from the reference, {:.1} % after refining {sides} sides",
            found.source,
            found.confidence,
            found.angle,
            found.passes,
            off(&quad),
            off(&refined.quad)
        );
    }
}

/// Which way up each page is found to be, shown to the classifier turned four ways.
fn which_way_up(dir: &Path) {
    for path in files(dir, ".before.jpg") {
        let name = name_of(&path, ".before.jpg");
        let Some(corners) = std::fs::read_to_string(dir.join(format!("{name}.ref.json"))).ok().as_deref().and_then(corners) else { continue };
        let Ok(photo) = Photo::decode(&std::fs::read(&path).unwrap_or_default()) else { continue };
        let Ok(quad) = pdfcraft_scan::Quad::from_shares(&corners, photo.width, photo.height) else { continue };
        let [top, right, bottom, left] = quad.side_lengths();
        let Ok(page) = pdfcraft_scan::rectify(&photo, &quad, top.max(bottom).round() as u32, left.max(right).round() as u32) else { continue };
        let mut answers = Vec::new();
        for k in 0..4u8 {
            let shown = pdfcraft_scan::turned(&page, k);
            let clock = Instant::now();
            let Some(found) = pdfcraft_scan::upright(&shown) else {
                println!("{name}: the orientation model is not installed");
                return;
            };
            // the turn that would put the page as the reference has it upright
            answers.push(((found.quarter_turns + k) % 4, found, clock.elapsed().as_millis()));
        }
        let same = answers.iter().all(|a| a.0 == answers[0].0);
        let sure = answers.iter().filter(|a| a.1.sure).count();
        let detail: Vec<String> =
            answers.iter().map(|(turn, f, ms)| format!("{turn} ({:.2}, {:.2}, {} tiles, {ms} ms)", f.confidence, f.agreement, f.tiles)).collect();
        println!(
            "{name}: {} x {}; quarter turns clockwise from the reference's page to upright, shown turned 0-3: {}{}; sure of {sure}",
            page.width,
            page.height,
            detail.join(", "),
            if same { "" } else { "  <- the four do not agree" }
        );
    }
}

/// How bent the flattening network finds each page, and the page flattened (as the camera took
/// it otherwise) for looking at.
fn flatten(dir: &Path, out: &Path) {
    for path in files(dir, ".before.jpg") {
        let name = name_of(&path, ".before.jpg");
        let Some(corners) = std::fs::read_to_string(dir.join(format!("{name}.ref.json"))).ok().as_deref().and_then(corners) else { continue };
        let Ok(photo) = Photo::decode(&std::fs::read(&path).unwrap_or_default()) else { continue };
        let Ok(quad) = pdfcraft_scan::Quad::from_shares(&corners, photo.width, photo.height) else { continue };
        let clock = Instant::now();
        let Some(grid) = pdfcraft_scan::predict_grid(&photo, &quad) else {
            println!("{name}: the flattening model is not installed");
            continue;
        };
        let model_ms = clock.elapsed().as_millis();
        let [top, right, bottom, left] = quad.side_lengths();
        let (w, h) = (top.max(bottom).round() as u32, left.max(right).round() as u32);
        let clock = Instant::now();
        let Ok(page) = pdfcraft_scan::unwarp(&photo, &grid, w, h, 4) else { continue };
        println!(
            "{name}: bend {:.2} %, {} folds, model {model_ms} ms, unwarp {} ms at {w} x {h}",
            100.0 * grid.bend(),
            grid.folds(),
            clock.elapsed().as_millis()
        );
        let picture = image::RgbImage::from_raw(page.width, page.height, page.data).expect("the page has its size");
        picture.save(out.join(format!("{name}.png"))).unwrap_or_else(|e| panic!("{name}: {e}"));
        if let Ok(plain) = pdfcraft_scan::rectify(&photo, &quad, w, h) {
            let picture = image::RgbImage::from_raw(plain.width, plain.height, plain.data).expect("the page has its size");
            picture.save(out.join(format!("{name}.plain.png"))).unwrap_or_else(|e| panic!("{name}: {e}"));
        }
    }
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let (Some(mode), Some(dir), Some(out)) = (args.get(1), args.get(2), args.get(3)) else {
        eprintln!("usage: scan_reference look|page|bw|detect|flatten|upright <dir> <out_dir> [strength]");
        std::process::exit(2);
    };
    let strength = args.get(4).and_then(|v| v.parse().ok()).unwrap_or(if mode == "bw" { 55 } else { 60 });
    let sharpen = args.get(5).and_then(|v| v.parse().ok()).unwrap_or(pdfcraft_scan::DEFAULT_SHARPEN);
    let threads = std::thread::available_parallelism().map_or(1, usize::from).min(4);
    std::fs::create_dir_all(out).unwrap_or_else(|e| panic!("{out}: {e}"));
    match mode.as_str() {
        "look" => look(Path::new(dir), Path::new(out), strength, sharpen, threads),
        "page" => page(Path::new(dir), Path::new(out), Look::Auto, strength, threads),
        "bw" => page(Path::new(dir), Path::new(out), Look::BlackWhite, strength, threads),
        "detect" => find(Path::new(dir)),
        "flatten" => flatten(Path::new(dir), Path::new(out)),
        "upright" => which_way_up(Path::new(dir)),
        other => eprintln!("unknown mode {other}: use look, page, bw, detect, flatten or upright"),
    }
}
