//! Developer tool: how long a page's preview takes part by part, and how long its first picture
//! takes (`pdfcraft_scan::first`), on a folder of scans kept for testing. Not part of the app.
//!
//! ```text
//! cargo run --release -p pdfcraft-scan --example scan_first_timing -- <folder> [threads] [out_dir] [foresee|plain]
//! ```
//!
//! For every `page-NN.json` under `<folder>` (see `scan_replay`), the page is made as the scan
//! screen's preview asks it (1280 pixels, with the testing options the file records: automatic
//! flattening with its check, the text lines, turning upright, the document type, fingers) and each
//! stage's time is printed; then its first picture is made from a copy of the photo a quarter the
//! size (as the app's thumbnail is), and from the photo itself. With `out_dir`, both are written
//! there as PNG files to look at. A fourth word `foresee` or `plain` switches "Skip flattening that
//! could not be kept" (#785) on or off for every page whatever its file says, so the same folder
//! can be run both ways and the pages in the two `out_dir`s compared; the last lines count how
//! often the flattening network ran and how often its page was thrown away.
//!
//! Times are this computer's, not a phone's. The folders hold someone's documents: keep them and
//! `out_dir` outside the repository.

use std::path::{Path, PathBuf};
use std::time::Instant;

use pdfcraft_scan::first::{first_page, shrunk};
use pdfcraft_scan::look::Pixels;
use pdfcraft_scan::page::page_look_kind;
use pdfcraft_scan::page_kind::PresetChoice;
use pdfcraft_scan::{Look, PageRequest, Paper, Photo, page_base};
use serde_json::Value;

/// The scan screen's preview and the first picture, in pixels on the longer side.
const PREVIEW_SIDE: u32 = 1280;

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

fn median(values: &[f64]) -> f64 {
    let mut v = values.to_vec();
    v.sort_by(f64::total_cmp);
    v.get(v.len() / 2).copied().unwrap_or(0.0)
}

fn write_png(out: &Path, name: &str, pixels: &Pixels, width: u32, height: u32) {
    let file = out.join(name);
    let saved = match pixels {
        Pixels::Rgb(d) => image::save_buffer(&file, d, width, height, image::ColorType::Rgb8),
        Pixels::Grey(d) => image::save_buffer(&file, d, width, height, image::ColorType::L8),
        Pixels::Bilevel(b) => image::save_buffer(&file, &b.to_grey(), width, height, image::ColorType::L8),
    };
    saved.unwrap_or_else(|e| panic!("{}: {e}", file.display()));
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let Some(dir) = args.get(1) else {
        eprintln!("usage: scan_first_timing <folder of kept scans> [threads] [out_dir]");
        std::process::exit(2);
    };
    let threads = args.get(2).and_then(|t| t.parse().ok()).unwrap_or_else(|| std::thread::available_parallelism().map_or(1, usize::from).min(4));
    let out = args.get(3).map(PathBuf::from);
    let foresee = args.get(4).map(|word| word == "foresee");
    // how often flattening ran: the network, its page thrown away, not run as known beforehand, the text lines
    let (mut ran, mut lost, mut foreseen, mut lines) = (0u32, 0u32, 0u32, 0u32);
    if let Some(out) = &out {
        std::fs::create_dir_all(out).unwrap_or_else(|e| panic!("{}: {e}", out.display()));
    }
    let mut files = Vec::new();
    pages(Path::new(dir), &mut files);
    println!("threads {threads}; times in ms on this computer");
    let mut totals: Vec<(&str, Vec<f64>)> = Vec::new();
    let mut note = |name: &'static str, ms: f64| match totals.iter_mut().find(|(n, _)| *n == name) {
        Some((_, v)) => v.push(ms),
        None => totals.push((name, vec![ms])),
    };
    for json in &files {
        let name = json.file_stem().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
        let Some(facts) = std::fs::read_to_string(json).ok().and_then(|text| serde_json::from_str::<Value>(&text).ok()) else { continue };
        let corners: Option<[f32; 8]> =
            facts["corners"].as_array().and_then(|l| l.iter().filter_map(|v| v.as_f64().map(|v| v as f32)).collect::<Vec<_>>().try_into().ok());
        let photo = ["jpg", "jpeg", "png"].iter().map(|ext| json.with_extension(ext)).find(|p| p.is_file());
        let (Some(corners), Some(photo)) = (corners, photo) else { continue };
        let bytes = std::fs::read(&photo).unwrap_or_default();
        let options = &facts["options"];
        let on = |name: &str| options[name].as_bool().unwrap_or(false);
        let chosen = facts["flatten"].as_bool().unwrap_or(false);
        let turned = &facts["turns"];
        let fixed = turned["manual"].as_u64();
        let ask = PageRequest {
            look: match facts["look"].as_str().unwrap_or("auto") {
                "original" => Look::Original,
                "greyscale" => Look::Greyscale,
                "bw" => Look::BlackWhite,
                "whiteboard" => Look::Whiteboard,
                "auto" => Look::Auto,
                _ => Look::Colour,
            },
            strength: facts["strength"].as_u64().map_or(60, |v| v.min(100) as u8),
            threads,
            snap_paper: options["snap_paper"].as_bool().unwrap_or(true),
            paper_hint: match facts["paper_hint"].as_str() {
                Some("a4") => Some(Paper::A4),
                Some("a5") => Some(Paper::A5),
                Some("letter") => Some(Paper::Letter),
                _ => None,
            },
            card_paper: facts["card_paper"].as_bool().unwrap_or(true),
            flatten: chosen || on("flatten_auto"),
            flatten_check: !chosen && on("flatten_auto"),
            flatten_trim: on("flatten_trim"),
            flatten_min_lines: if on("flatten_line_floor") { 8 } else { 0 },
            line_flatten: on("line_flatten"),
            skip_flat: on("skip_flat"),
            flatten_foresee: foresee.unwrap_or_else(|| on("flatten_foresee")),
            curved_sides: facts["finding"]["curved_sides"].as_u64().map_or(0, |v| v.min(4) as u8),
            upright: fixed.is_none() && on("upright"),
            turns: fixed.map_or(0, |t| (t % 4) as u8),
            sharpen: options["sharpen"].as_u64().map_or(30, |v| v.min(100) as u8),
            denoise: on("denoise"),
            preset: if on("presets") { PresetChoice::Auto } else { PresetChoice::Off },
            kind_tone: on("kind_tone"),
            photo_sharpen: on("photo_sharpen"),
            remove_fingers: on("remove_fingers"),
            max_side: Some(PREVIEW_SIDE),
            ..PageRequest::new(&bytes, corners)
        };

        // the preview as it is made today
        let clock = Instant::now();
        let base = match page_base(&ask) {
            Ok(base) => base,
            Err(e) => {
                println!("{name}: {e}");
                continue;
            }
        };
        let base_ms = clock.elapsed().as_secs_f64() * 1000.0;
        let clock = Instant::now();
        let Ok((look, _)) = page_look_kind(&base, &ask) else { continue };
        let look_ms = clock.elapsed().as_secs_f64() * 1000.0;
        let stages: Vec<String> =
            base.timings.iter().chain(look.stages.iter()).map(|(s, ms)| format!("{} {ms}", s.trim_start_matches("scan_"))).collect();
        println!(
            "{name}: preview {} x {} in {:.0} (base {base_ms:.0}, look {look_ms:.0}); flattened {} ({}), turned {}",
            base.page.width,
            base.page.height,
            base_ms + look_ms,
            base.flattened,
            base.flatten_check.map_or(if base.flatten_skipped_flat { "flat already" } else { "not checked" }, |c| c.why),
            base.turns,
        );
        println!("    {}", stages.join(", "));
        note("preview", base_ms + look_ms);
        for (stage, ms) in base.timings.iter().chain(look.stages.iter()) {
            note(stage, f64::from(*ms));
        }
        let stage_ran = |stage: &str| base.timings.iter().any(|(s, _)| *s == stage);
        ran += u32::from(stage_ran(pdfcraft_scan::page::STAGE_FLATTEN));
        lost += u32::from(stage_ran(pdfcraft_scan::page::STAGE_FLATTEN_CHECK) && !base.flatten_check.is_some_and(|c| c.kept));
        foreseen += u32::from(base.flatten_foreseen);
        lines += u32::from(stage_ran(pdfcraft_scan::page::STAGE_FLATTEN_LINES));
        if !base.flattened && base.flatten_check.is_some() {
            let spent: u32 = base.timings.iter().filter(|(s, _)| s.contains("flatten")).map(|(_, ms)| ms).sum();
            note("flattening not kept", f64::from(spent));
        }

        // the first picture: from a copy a quarter the size (not timed: the app holds it), and from the photo
        let Ok(decoded) = Photo::decode(&bytes) else { continue };
        let small = shrunk(&decoded, 4);
        for (label, side, copy) in
            [("first 640 from copy", 640, Some(&small)), ("first 960 from copy", 960, Some(&small)), ("first 640 from photo", 640, None)]
        {
            let clock = Instant::now();
            let Ok(first) = first_page(&PageRequest { max_side: Some(side), ..ask.clone() }, copy) else { continue };
            let ms = clock.elapsed().as_secs_f64() * 1000.0;
            let stages: Vec<String> = first.timings.iter().map(|(s, ms)| format!("{} {ms}", s.trim_start_matches("scan_first_"))).collect();
            println!("    {label}: {} x {} in {ms:.0} ({})", first.width, first.height, stages.join(", "));
            note(label, ms);
            if let Some(out) = &out {
                write_png(out, &format!("{name}-{}.png", label.replace(' ', "-")), &first.pixels, first.width, first.height);
            }
        }
        if let Some(out) = &out {
            write_png(out, &format!("{name}-preview.png"), &look.pixels, base.page.width, base.page.height);
        }
    }
    println!(
        "flattening on {} pages: the network ran on {ran}, its page was thrown away on {lost}, it was not run on {foreseen} (known beforehand), the text lines ran on {lines}",
        files.len()
    );
    println!("medians over the pages (and the most), in ms:");
    for (name, values) in &totals {
        println!("    {name}: {:.0} ({:.0}) on {} pages", median(values), values.iter().copied().fold(0.0, f64::max), values.len());
    }
}
