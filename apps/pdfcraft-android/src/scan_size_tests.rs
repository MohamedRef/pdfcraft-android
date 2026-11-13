//! The size choices measured (#505): each realistic page photo from
//! `tools/scan-eval/size_pages.py` made with Clean scan as before #505 and at each size choice,
//! then the stored page read by the app's own recogniser (character error rate against the page's
//! true text) and compared with the page stored as before (SSIM on grey, after scaling it back to
//! that page's size).
//!
//! Not run by default, it needs the photos and takes minutes:
//!
//! ```sh
//! python3 tools/scan-eval/size_pages.py /tmp/size-pages
//! PDFCRAFT_SIZE_PAGES=/tmp/size-pages cargo test --release -p pdfcraft-android scan_size -- --ignored --nocapture
//! ```
//!
//! Drawn pages photographed in code, not the owner's photos and not a phone. The figures are in
//! docs/ANDROID_SCAN_SIZE.md.

use pdfcraft_engine::ocr::{Models, Ocr};
use pdfcraft_scan::look::Look;
use pdfcraft_scan::metrics_compare::{cer, ssim};
use pdfcraft_scan::page::{PageRequest, process_page};
use pdfcraft_scan::page_kind::PresetChoice;
use pdfcraft_scan::size::SizeChoice;
use pdfcraft_scan::{Luma, encode::Format};

fn grey_of(bytes: &[u8], format: Format) -> Luma {
    let f = match format {
        Format::Jpeg => image::ImageFormat::Jpeg,
        Format::Png => image::ImageFormat::Png,
    };
    let g = image::load_from_memory_with_format(bytes, f).unwrap().to_luma8();
    Luma { width: g.width(), height: g.height(), data: g.into_raw() }
}

fn scaled(l: &Luma, w: u32, h: u32) -> Luma {
    if (l.width, l.height) == (w, h) {
        return l.clone();
    }
    let img = image::GrayImage::from_raw(l.width, l.height, l.data.clone()).unwrap();
    let out = image::imageops::resize(&img, w, h, image::imageops::FilterType::CatmullRom);
    Luma { width: w, height: h, data: out.into_raw() }
}

/// The share of the true text's words the reading lacks, in any order (lower case, letters and
/// digits only): unlike the character error rate, a block read in another order costs nothing.
fn word_error(read: &str, truth: &str) -> f64 {
    let words = |t: &str| -> Vec<String> {
        t.split_whitespace().map(|w| w.chars().filter(|c| c.is_alphanumeric()).collect::<String>().to_lowercase()).filter(|w| !w.is_empty()).collect()
    };
    let mut found = words(read);
    let truth = words(truth);
    let mut missing = 0usize;
    for w in &truth {
        match found.iter().position(|f| f == w) {
            Some(i) => {
                found.swap_remove(i);
            }
            None => missing += 1,
        }
    }
    missing as f64 / truth.len().max(1) as f64
}

fn read(ocr: &Ocr, l: &Luma) -> String {
    let rgb: Vec<u8> = l.data.iter().flat_map(|&v| [v, v, v]).collect();
    ocr.recognize(&rgb, l.width, l.height).unwrap().iter().map(|line| line.text()).collect::<Vec<_>>().join(" ")
}

#[test]
#[ignore = "needs PDFCRAFT_SIZE_PAGES (tools/scan-eval/size_pages.py) and minutes"]
fn scan_size_choices_measured() {
    let dir = std::env::var("PDFCRAFT_SIZE_PAGES").expect("PDFCRAFT_SIZE_PAGES");
    let ocr = Models::find().map(|m| Ocr::load(&m).unwrap());
    let mut names: Vec<String> =
        std::fs::read_dir(&dir).unwrap().filter_map(|e| e.ok()?.file_name().into_string().ok()?.strip_suffix(".json").map(str::to_owned)).collect();
    names.sort();
    let out = std::env::var("PDFCRAFT_SIZE_OUT").ok();
    println!("| Page | Stored as | Encoding | Pixels | dpi | Quality | Bytes | vs before | CER | Words missed | SSIM | Encode ms | Why |");
    println!("|---|---|---|---|---|---|---|---|---|---|---|---|---|");
    for name in names {
        let meta: serde_json::Value = serde_json::from_slice(&std::fs::read(format!("{dir}/{name}.json")).unwrap()).unwrap();
        let photo = std::fs::read(format!("{dir}/{name}.jpg")).unwrap();
        let corners: Vec<f32> = meta["corners"].as_array().unwrap().iter().map(|v| v.as_f64().unwrap() as f32).collect();
        let truth = meta["text"].as_str().unwrap();
        let reads = meta["ocr"].as_bool().unwrap_or(false);
        let mut before: Option<(Luma, u64)> = None;
        let variants = std::iter::once(("before", SizeChoice::Best, false)).chain(SizeChoice::ALL.into_iter().map(|c| (c.id(), c, true)));
        for (label, choice, auto) in variants {
            let mut request = PageRequest::new(&photo, corners.clone().try_into().unwrap());
            request.look = Look::Auto;
            request.preset = PresetChoice::Auto;
            request.threads = 4;
            request.size = choice;
            request.auto_encode = auto;
            let page = process_page(&request).unwrap();
            let e = page.encoded.clone().unwrap();
            let encode_ms = page.timings.iter().find(|(s, _)| *s == "scan_encode").map_or(0, |t| t.1);
            let grey = grey_of(&page.bytes, page.format);
            let (ssim_v, ratio) = match &before {
                None => (1.0, 1.0),
                Some((b, bytes)) => (ssim(b, &scaled(&grey, b.width, b.height)).unwrap(), *bytes as f64 / e.bytes as f64),
            };
            let (cer_v, wer_v) = match (&ocr, reads) {
                (Some(ocr), true) => {
                    let text = read(ocr, &grey);
                    (format!("{:.2} %", 100.0 * cer(&text, truth)), format!("{:.2} %", 100.0 * word_error(&text, truth)))
                }
                _ => ("–".into(), "–".into()),
            };
            if let Some(out) = &out {
                std::fs::write(format!("{out}/{name}-{label}.{}", page.format.extension()), &page.bytes).unwrap();
            }
            println!(
                "| {name} | {label} | {} | {}×{} | {} | {} | {} | {:.1}× smaller | {cer_v} | {wer_v} | {ssim_v:.4} | {encode_ms} | {} |",
                e.encoding.id(),
                e.width,
                e.height,
                e.dpi,
                e.quality,
                e.bytes,
                ratio,
                e.why
            );
            if before.is_none() {
                before = Some((grey, e.bytes));
            }
        }
    }
}

/// The numbers behind the choices (#505): each page made once with Clean scan, then stored grey
/// (or colour) at each resolution and quality, and as 1 bit, each read back. Prints a table; the
/// choices in `pdfcraft_scan::size::SizeParams` were set from it.
#[test]
#[ignore = "needs PDFCRAFT_SIZE_PAGES (tools/scan-eval/size_pages.py) and minutes"]
fn scan_size_sweep() {
    use pdfcraft_scan::jpeg::{Chroma, Samples, encode};
    use pdfcraft_scan::look::Pixels;
    use pdfcraft_scan::page::{page_base, page_look_kind};
    use pdfcraft_scan::size::{coloured_share, resize_area, scaled_size};
    let dir = std::env::var("PDFCRAFT_SIZE_PAGES").expect("PDFCRAFT_SIZE_PAGES");
    let ocr = Models::find().map(|m| Ocr::load(&m).unwrap());
    let mut names: Vec<String> =
        std::fs::read_dir(&dir).unwrap().filter_map(|e| e.ok()?.file_name().into_string().ok()?.strip_suffix(".json").map(str::to_owned)).collect();
    names.sort();
    println!("| Page | Kind | Stroke mm | Coloured share | dpi | Quality | Bytes | CER | Words missed |");
    println!("|---|---|---|---|---|---|---|---|---|");
    for name in names {
        let meta: serde_json::Value = serde_json::from_slice(&std::fs::read(format!("{dir}/{name}.json")).unwrap()).unwrap();
        if !meta["ocr"].as_bool().unwrap_or(false) {
            continue;
        }
        let photo = std::fs::read(format!("{dir}/{name}.jpg")).unwrap();
        let corners: Vec<f32> = meta["corners"].as_array().unwrap().iter().map(|v| v.as_f64().unwrap() as f32).collect();
        let truth = meta["text"].as_str().unwrap();
        let mut request = PageRequest::new(&photo, corners.clone().try_into().unwrap());
        request.look = Look::Auto;
        request.preset = PresetChoice::Auto;
        request.threads = 4;
        let base = page_base(&request).unwrap();
        let (applied, kind) = page_look_kind(&base, &request).unwrap();
        let Pixels::Rgb(rgb) = &applied.pixels else { panic!() };
        let (w, h, dpi) = (base.page.width, base.page.height, base.dpi);
        let colour = coloured_share(rgb);
        let stroke = kind.as_ref().and_then(|k| k.features.stroke_mm).unwrap_or(-1.0);
        let preset = kind.as_ref().map_or("none", |k| k.preset.id());
        let grey: Vec<u8> =
            rgb.chunks_exact(3).map(|c| (0.299 * f32::from(c[0]) + 0.587 * f32::from(c[1]) + 0.114 * f32::from(c[2])).round() as u8).collect();
        let colourful = colour > pdfcraft_scan::size::COLOUR_SHARE;
        for to in [300u16, 250, 200, 175, 150] {
            let (ow, oh) = scaled_size(w, h, dpi, to);
            let (plane, ch) = if colourful { (resize_area(rgb, w, h, 3, ow, oh), 3) } else { (resize_area(&grey, w, h, 1, ow, oh), 1) };
            for q in [92u8, 85, 80, 75, 70, 60, 50] {
                let samples = if ch == 3 { Samples::Rgb(&plane, Chroma::Half) } else { Samples::Grey(&plane) };
                let bytes = encode(samples, ow, oh, q, to.min(dpi)).unwrap();
                let l = grey_of(&bytes, Format::Jpeg);
                let text = ocr.as_ref().map(|o| read(o, &l)).unwrap_or_default();
                let (c, wm) = (100.0 * cer(&text, truth), 100.0 * word_error(&text, truth));
                println!("| {name} | {preset} | {stroke:.3} | {colour:.5} | {} | {q} | {} | {c:.2} % | {wm:.2} % |", to.min(dpi), bytes.len());
            }
        }
    }
}
