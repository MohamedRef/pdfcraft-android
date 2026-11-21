//! Developer tool: how many looks of the detector network the search for a page takes, with the
//! turned looks in their fixed order and ordered by how the page lies (#785), on a folder of
//! scans kept for testing. Not part of the app.
//!
//! ```text
//! cargo run --release -p pdfcraft-scan --example scan_find_passes -- <folder>
//! ```
//!
//! For every `page-NN.json` under `<folder>` (see `scan_replay`) the photo is searched three
//! times: with the fixed order, with the looks ordered by the unturned look's own corners, and
//! ordered by an outline, which is the camera's outline at the shutter when the file kept one
//! (`capture.preview_quad`) and else the corners the person left (a stand-in: on a phone the
//! outline comes from a small frame taken a moment earlier). Each line says the looks made and
//! whether the page found is the same to the pixel; every look's own confidence follows.
//!
//! The folders hold someone's documents: keep them outside the repository.

use std::path::{Path, PathBuf};

use pdfcraft_scan::detect::every_look;
use pdfcraft_scan::{CornerRule, Finder, LookOrder, Photo, Quad, detect_by, detect_in_order};
use serde_json::Value;

fn pages(dir: &Path, found: &mut Vec<PathBuf>) {
    let Ok(entries) = std::fs::read_dir(dir) else { return };
    let mut entries: Vec<PathBuf> = entries.filter_map(|e| e.ok().map(|e| e.path())).collect();
    entries.sort();
    for path in entries {
        if path.is_dir() {
            pages(&path, found);
        } else if path.file_name().is_some_and(|n| n.to_string_lossy().starts_with("page-")) && path.extension().is_some_and(|e| e == "json") {
            found.push(path);
        }
    }
}

fn shares(value: &Value) -> Option<[f32; 8]> {
    let list: Vec<f32> = value.as_array()?.iter().filter_map(|v| v.as_f64().map(|v| v as f32)).collect();
    list.try_into().ok()
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let Some(dir) = args.get(1) else {
        eprintln!("usage: scan_find_passes <folder of kept scans>");
        std::process::exit(2);
    };
    let mut files = Vec::new();
    pages(Path::new(dir), &mut files);
    let (mut fixed, mut by_first, mut by_outline, mut same, mut searched) = (0u32, 0u32, 0u32, 0u32, 0u32);
    for json in &files {
        let name = json.file_stem().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
        let Some(facts) = std::fs::read_to_string(json).ok().and_then(|text| serde_json::from_str::<Value>(&text).ok()) else { continue };
        let Some(photo) = ["jpg", "jpeg", "png"].iter().map(|ext| json.with_extension(ext)).find(|p| p.is_file()) else { continue };
        let Ok(photo) = Photo::decode(&std::fs::read(&photo).unwrap_or_default()) else { continue };
        let rule = if facts["options"]["sheet_check"].as_bool().unwrap_or(true) { CornerRule::Sheet } else { CornerRule::Surest };
        let (from_camera, left) = (shares(&facts["capture"]["preview_quad"]), shares(&facts["corners"]));
        let outline = from_camera.or(left).and_then(|s| Quad::from_shares(&s, photo.width, photo.height).ok());
        let before = detect_by(&photo, Finder::Network, rule);
        let first = detect_in_order(&photo, Finder::Network, rule, LookOrder::Upright(None));
        let near = detect_in_order(&photo, Finder::Network, rule, LookOrder::Upright(outline));
        let unchanged = first.quad == before.quad && near.quad == before.quad;
        println!(
            "{name}: {} looks (page {} at {} degrees, {:.2}); {} by the first look's corners, {} by the {}; the page found is {}",
            before.passes,
            if before.quad.is_some() { "found" } else { "not found" },
            before.angle,
            before.confidence,
            first.passes,
            near.passes,
            if from_camera.is_some() { "camera's outline" } else { "corners left" },
            if unchanged { "the same" } else { "ANOTHER" },
        );
        if let Some(looks) = every_look(&photo, rule) {
            let each: Vec<String> = looks.iter().map(|l| format!("{} {:.2}", l.degrees, l.confidence)).collect();
            println!("    every look: {}", each.join(", "));
        }
        searched += 1;
        fixed += u32::from(before.passes);
        by_first += u32::from(first.passes);
        by_outline += u32::from(near.passes);
        same += u32::from(unchanged);
    }
    println!(
        "{searched} photos: {fixed} looks in the fixed order, {by_first} by the first look's corners, {by_outline} by an outline; the same page on {same}"
    );
}
