//! Developer tool for scans kept for testing (#288). Not part of the app.
//!
//! With "Keep scans for testing" on (Settings, About, Diagnostics), the app keeps each scanned
//! page's camera photo (`page-NN.jpg`) and its facts (`page-NN.json`): the corners as the person
//! left them, what the app found by itself, and the look and testing options in force. Shared,
//! they arrive as a zip with one folder per scan. Unzip it outside the repository and run:
//!
//! ```text
//! cargo run --release -p pdfcraft-scan --example scan_replay -- <folder> [out_dir]
//! ```
//!
//! For every page under `<folder>` this prints how far the corners the phone found were from
//! where the person left them, and how far the detector of this build is, as the largest corner
//! distance in % of the photo's diagonal (0.5 % or less: nobody would have needed to move a
//! corner). With `out_dir`, each page is also made again with the corners, look and options the
//! file records, and written there as `<scan>-<page>.jpg` or `.png`.
//!
//! The folders hold someone's documents: keep them outside the repository.

use std::path::{Path, PathBuf};

use pdfcraft_scan::page_kind::{PresetChoice, preset_from_id};
use pdfcraft_scan::{Finder, Look, PageRequest, Photo, Pyramid, Quad, RefineOptions, detect_with, process_page, refine};
use serde_json::Value;

/// A corner within this share of the diagonal needs no moving by hand (#288, `no_touch`).
const NO_TOUCH: f64 = 0.005;

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

/// The largest distance between matching corners, as a share of the photo's diagonal.
fn off(a: &[f32; 8], b: &[f32; 8], w: u32, h: u32) -> f64 {
    let (w, h) = (f64::from(w), f64::from(h));
    (0..4).map(|i| (f64::from(a[2 * i] - b[2 * i]) * w).hypot(f64::from(a[2 * i + 1] - b[2 * i + 1]) * h) / w.hypot(h)).fold(0.0, f64::max)
}

fn photo_of(json: &Path) -> Option<PathBuf> {
    ["jpg", "jpeg", "png"].iter().map(|ext| json.with_extension(ext)).find(|p| p.is_file())
}

fn look_of(name: &str) -> Look {
    match name {
        "original" => Look::Original,
        "auto" => Look::Auto,
        "greyscale" => Look::Greyscale,
        "bw" => Look::BlackWhite,
        // its own look since #301; Auto color's settings before
        "whiteboard" => Look::Whiteboard,
        _ => Look::Colour,
    }
}

fn median(values: &mut [f64]) -> f64 {
    values.sort_by(f64::total_cmp);
    values.get(values.len() / 2).copied().unwrap_or(0.0)
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let Some(dir) = args.get(1) else {
        eprintln!("usage: scan_replay <folder of kept scans> [out_dir]");
        std::process::exit(2);
    };
    let out = args.get(2).map(PathBuf::from);
    if let Some(out) = &out {
        std::fs::create_dir_all(out).unwrap_or_else(|e| panic!("{}: {e}", out.display()));
    }
    let mut files = Vec::new();
    pages(Path::new(dir), &mut files);
    let threads = std::thread::available_parallelism().map_or(1, usize::from).min(4);
    let (mut phone, mut now) = (Vec::new(), Vec::new());
    for json in &files {
        let scan = json.parent().and_then(Path::file_name).map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
        let name = format!("{scan}/{}", json.file_stem().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default());
        let Some(facts) = std::fs::read_to_string(json).ok().and_then(|text| serde_json::from_str::<Value>(&text).ok()) else {
            println!("{name}: its facts cannot be read");
            continue;
        };
        let (Some(left), Some(path)) = (shares(&facts["corners"]), photo_of(json)) else {
            println!("{name}: no corners or no photo");
            continue;
        };
        let bytes = std::fs::read(&path).unwrap_or_default();
        let Ok(photo) = Photo::decode(&bytes) else {
            println!("{name}: the photo cannot be read");
            continue;
        };
        let (w, h) = (photo.width, photo.height);
        let options = &facts["options"];

        // what the phone found, against where its corners were left
        let on_phone = shares(&facts["found"]).map(|found| off(&found, &left, w, h));
        // what this build finds with the detector the phone was told to use
        let finder = match options["finder"].as_str() {
            Some("network") => Finder::Network,
            Some("lines") => Finder::Lines,
            Some("off") => Finder::Off,
            _ => Finder::Auto,
        };
        let found = detect_with(&photo, finder);
        let here = found.quad.map(|quad| {
            let quad = match options["refine"].as_bool() {
                Some(false) => quad,
                _ => refine(&Pyramid::new(photo.luma()), &quad, &RefineOptions::default()).quad,
            };
            quad.to_shares(w, h)
        });
        let here_off = here.map(|found| off(&found, &left, w, h));
        let moved = on_phone.is_some_and(|d| d > 0.0);
        let show = |d: Option<f64>| d.map_or_else(|| "nothing".to_owned(), |d| format!("{:.2} %", 100.0 * d));
        println!(
            "{name}: {w} x {h}, {}; phone {} ({}), this build {} ({:?} {:.2})",
            facts["outcome"].as_str().unwrap_or("?"),
            show(on_phone),
            if moved { "corners moved by hand" } else { "corners left as found" },
            show(here_off),
            found.source,
            found.confidence
        );
        // counted where the phone found a page: there the corners left are a judgement of what
        // it found (a page it found nothing on was left as the whole photo, moved or not)
        if let Some(d) = on_phone {
            phone.push(d);
            now.push(here_off.unwrap_or(1.0));
        }

        let Some(out) = &out else { continue };
        if Quad::from_shares(&left, w, h).is_err() {
            println!("    made again: the corners left make no page");
            continue;
        }
        // the page's turn as the phone settled it (#339): the person's own turn, or the one it took
        // from its neighbours, stands in for the classifier; files from before then have neither
        let turned = &facts["turns"];
        let fixed = turned["manual"].as_u64().or_else(|| turned["followed"].as_bool().filter(|f| *f).and(turned["total"].as_u64()));
        let request = PageRequest {
            look: look_of(facts["look"].as_str().unwrap_or("auto")),
            strength: facts["strength"].as_u64().map_or(60, |v| v.min(100) as u8),
            threads,
            snap_paper: options["snap_paper"].as_bool().unwrap_or(true),
            side_proportions: options["side_proportions"].as_bool().unwrap_or(false),
            // the scan screen's switch, or every page checked by the automatic mode (#302)
            flatten: facts["flatten"].as_bool().unwrap_or(false) || (options["flatten_auto"].as_bool().unwrap_or(false)),
            flatten_check: !facts["flatten"].as_bool().unwrap_or(false) && options["flatten_auto"].as_bool().unwrap_or(false),
            // the border trim of a flattened page (#302); files from before it have none
            flatten_trim: options["flatten_trim"].as_bool().unwrap_or(false),
            // the text-line fallback (#303): files from before it have no such option
            line_flatten: options["line_flatten"].as_bool().unwrap_or(false),
            // a file from before pages were turned upright (#339) has no such option
            upright: fixed.is_none() && options["upright"].as_bool().unwrap_or(false) && turned["auto"].as_bool().unwrap_or(true),
            turns: fixed.map_or(0, |t| (t % 4) as u8),
            // nor one from before the Auto look sharpened and smoothed (#299)
            // with the page-strength option off (#424) every level but none was the kind's own
            sharpen: match (
                options["sharpen"].as_u64().map_or(0, |v| v.min(100) as u8),
                options["page_strength"].as_bool(),
                options["presets"].as_bool(),
            ) {
                (s, Some(false), Some(true)) if s > 0 => pdfcraft_scan::DEFAULT_SHARPEN,
                (s, _, _) => s,
            },
            denoise: options["denoise"].as_bool().unwrap_or(false),
            // the kind of document (#299): one the person chose is kept; one the phone worked out
            // is worked out again by this build; files from before then have none
            preset: match (facts["document_type"]["preset"].as_str().and_then(preset_from_id), facts["document_type"]["chosen"].as_bool()) {
                (Some(p), Some(true)) => PresetChoice::Fixed(p),
                (Some(_), _) => PresetChoice::Auto,
                (None, _) => PresetChoice::Off,
            },
            curved_sides: facts["finding"]["curved_sides"].as_u64().map_or(0, |v| v.min(4) as u8),
            // which sides were curved and book mode (#442); files from before then say neither
            curved_opposite: facts["finding"]["curved_opposite"].as_bool().unwrap_or(false),
            book_mode: !facts["book"].is_null(),
            // the book page rule (#442): the phone's choice, or the rule in force for older files
            strict_book: options["strict_book"].as_bool().unwrap_or(true),
            // set by hand (#424): the testing option's strength, or the page's slider; files from
            // before #424 say only whether the testing option set one
            fixed_strength: facts["strength_applied"]["fixed"].as_bool().unwrap_or_else(|| !options["strength_override"].is_null()),
            // a document type's tone and the photo mask in sharpening (#299); files from before them have neither
            kind_tone: options["kind_tone"].as_bool().unwrap_or(false),
            photo_sharpen: options["photo_sharpen"].as_bool().unwrap_or(false),
            // the paper the person chose for the page (#301); files from before then leave it to the document type
            keep_paper: match facts["page_look"]["paper"].as_str() {
                Some("keep") => Some(true),
                Some("white") => Some(false),
                _ => None,
            },
            ..PageRequest::new(&bytes, left)
        };
        match process_page(&request) {
            Ok(page) => {
                let recorded = (facts["result"]["width"].as_u64(), facts["result"]["height"].as_u64());
                let same = recorded == (Some(u64::from(page.width)), Some(u64::from(page.height)));
                let turn = match (page.orientation, facts["result"]["quarter_turns"].as_u64()) {
                    (Some(found), Some(phone)) => {
                        format!(
                            "; turned {} (confidence {:.2}, agreement {:.2}), the phone turned {phone}",
                            page.quarter_turns, found.confidence, found.agreement
                        )
                    }
                    (Some(found), None) => {
                        format!("; turned {} (confidence {:.2}, agreement {:.2})", page.quarter_turns, found.confidence, found.agreement)
                    }
                    _ => String::new(),
                };
                println!(
                    "    made again: {} x {} at {} dpi{}{turn}",
                    page.width,
                    page.height,
                    page.dpi,
                    match recorded {
                        (Some(_), Some(_)) if same => ", the size the phone made".to_owned(),
                        (Some(rw), Some(rh)) => format!(", the phone made {rw} x {rh}"),
                        _ => String::new(),
                    }
                );
                let file = out.join(format!("{}.{}", name.replace('/', "-"), page.format.extension()));
                std::fs::write(&file, &page.bytes).unwrap_or_else(|e| panic!("{}: {e}", file.display()));
            }
            Err(e) => println!("    made again: {e}"),
        }
    }
    let count = phone.len();
    if count > 0 {
        let share = |values: &[f64]| 100.0 * values.iter().filter(|&&d| d <= NO_TOUCH).count() as f64 / count as f64;
        println!(
            "{count} pages with found corners: no corner needed moving on {:.0} % on the phone and {:.0} % with this build; median distance {:.2} % and {:.2} %",
            share(&phone),
            share(&now),
            100.0 * median(&mut phone),
            100.0 * median(&mut now)
        );
    }
}
