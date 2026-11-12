//! Developer tool for the private reference pairs of #288 (section 8). Not part of the app.
//!
//! A reference pair is a photo and what another scanner made of it. `tools/scan-eval/
//! reference_pairs.py register <dir>` lines each pair up and writes `<name>.aligned.png` (the
//! photo on the reference's pixel grid) and `<name>.ref.json` (the reference's crop).
//!
//! ```text
//! cargo run -p pdfcraft-scan --example scan_reference -- look <dir> <out_dir> [strength]
//!     the Auto look on every <dir>/<name>.aligned.png  ->  <out_dir>/<name>.png
//!     then: python3 tools/scan-eval/reference_pairs.py compare <dir> <out_dir>
//!
//! cargo run -p pdfcraft-scan --example scan_reference -- page <dir> <out_dir> [strength]
//!     the whole pipeline (decode, straighten to the reference's corners, Auto, encode) on every
//!     <dir>/<name>.before.jpg that has a <name>.ref.json  ->  <out_dir>/<name>.jpg
//! ```
//!
//! The pairs are someone's documents: keep the folders outside the repository.

use std::path::{Path, PathBuf};
use std::time::Instant;

use pdfcraft_scan::{AutoParams, Look, PageRequest, PaperMode, Rgb, auto, process_page};

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

fn look(dir: &Path, out: &Path, strength: u8, threads: usize) {
    for path in files(dir, ".aligned.png") {
        let name = name_of(&path, ".aligned.png");
        let img = image::open(&path).unwrap_or_else(|e| panic!("{}: {e}", path.display())).to_rgb8();
        let page = Rgb { width: img.width(), height: img.height(), data: img.into_raw() };
        let clock = Instant::now();
        let Some(result) = auto(&page, &AutoParams { strength, paper: PaperMode::White, threads }) else { continue };
        let ms = clock.elapsed().as_millis();
        let megapixels = f64::from(page.width) * f64::from(page.height) / 1e6;
        println!("{name}: {megapixels:.1} MP, {ms} ms, closing radius {}, objects {:.1} %", result.closing_radius, result.objects.share() * 100.0);
        let picture = image::RgbImage::from_raw(result.rgb.width, result.rgb.height, result.rgb.data).expect("the look keeps the page's size");
        picture.save(out.join(format!("{name}.png"))).unwrap_or_else(|e| panic!("{name}: {e}"));
    }
}

fn page(dir: &Path, out: &Path, strength: u8, threads: usize) {
    for path in files(dir, ".before.jpg") {
        let name = name_of(&path, ".before.jpg");
        let Some(corners) = std::fs::read_to_string(dir.join(format!("{name}.ref.json"))).ok().as_deref().and_then(corners) else {
            println!("{name}: no corners (run reference_pairs.py register first)");
            continue;
        };
        let photo = std::fs::read(&path).unwrap_or_else(|e| panic!("{}: {e}", path.display()));
        match process_page(&PageRequest { photo: &photo, corners, look: Look::Auto, strength, threads }) {
            Ok(result) => {
                let stages: Vec<String> = result.timings.iter().map(|(stage, ms)| format!("{stage} {ms} ms")).collect();
                println!("{name}: {} x {}, {}", result.width, result.height, stages.join(", "));
                std::fs::write(out.join(format!("{name}.jpg")), &result.bytes).unwrap_or_else(|e| panic!("{name}: {e}"));
            }
            Err(e) => println!("{name}: {e}"),
        }
    }
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let (Some(mode), Some(dir), Some(out)) = (args.get(1), args.get(2), args.get(3)) else {
        eprintln!("usage: scan_reference look|page <dir> <out_dir> [strength]");
        std::process::exit(2);
    };
    let strength = args.get(4).and_then(|v| v.parse().ok()).unwrap_or(60);
    let threads = std::thread::available_parallelism().map_or(1, usize::from).min(4);
    std::fs::create_dir_all(out).unwrap_or_else(|e| panic!("{out}: {e}"));
    match mode.as_str() {
        "look" => look(Path::new(dir), Path::new(out), strength, threads),
        "page" => page(Path::new(dir), Path::new(out), strength, threads),
        other => eprintln!("unknown mode {other}: use look or page"),
    }
}
