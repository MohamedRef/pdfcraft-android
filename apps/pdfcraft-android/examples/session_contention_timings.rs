//! Manual host measurements for the Android bridge (issues #87, #90, #113, #131). Supply a local
//! or synthetic PDF; paths and PDF contents are not logged.
//!
//! `session_contention_timings edit /abs/file.pdf`  first repaint after one comment edit
//! `session_contention_timings save /abs/file.pdf`  a render while `doc_save` holds the session
//! `session_contention_timings text /abs/file.pdf`  search every page, then report the text cache
use pdfcraft_android::MobileSession;
use serde_json::json;
use std::sync::Arc;
use std::time::Instant;

fn ms(t: Instant) -> u128 {
    t.elapsed().as_millis()
}

/// Resident memory of this process (Linux), in MiB.
fn rss_mib() -> u64 {
    std::fs::read_to_string("/proc/self/statm")
        .ok()
        .and_then(|s| s.split_whitespace().nth(1).and_then(|v| v.parse::<u64>().ok()))
        .map_or(0, |pages| pages * 4096 / (1024 * 1024))
}

fn open(s: &MobileSession, path: &str) -> Result<u64, Box<dyn std::error::Error>> {
    let reading = s.begin_open(path.to_string(), None, 0, false)?;
    if reading.document.preparing {
        s.complete_open(reading.document.id)?;
    }
    Ok(reading.document.id)
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = std::env::args().skip(1);
    let mode = args.next().ok_or("usage: session_contention_timings edit|save|text|lower /absolute/file.pdf")?;
    let path = args.next().ok_or("missing PDF path")?;
    let s = Arc::new(MobileSession::new());
    let doc = open(&s, &path)?;
    let pages = s.document(doc)?.pages.len() as u32;
    match mode.as_str() {
        "edit" => {
            for page in [0, 1] {
                let t = Instant::now();
                s.render_page(doc, page, 2.0, None)?;
                println!("warm_render page={page} ms={}", ms(t));
            }
            for round in 1..=3 {
                let t = Instant::now();
                s.execute(
                    "comment_add".into(),
                    json!({"doc": doc, "page": 1, "type": "ink", "strokes": [[[100.0 + round as f32, 100.0], [140.0, 160.0]]]}).to_string(),
                )?;
                let tool = ms(t);
                let t = Instant::now();
                s.render_page(doc, 0, 2.0, None)?;
                let first = ms(t);
                let t = Instant::now();
                s.render_page(doc, 1, 2.0, None)?;
                let second = ms(t);
                println!("round={round} comment_add_ms={tool} first_repaint_ms={first} next_page_ms={second}");
            }
        }
        "save" => {
            let t = Instant::now();
            s.render_page(doc, 3, 2.0, None)?;
            println!("idle_render_ms={}", ms(t));
            s.execute(
                "comment_add".into(),
                json!({"doc": doc, "page": 1, "type": "ink", "strokes": [[[100.0, 100.0], [140.0, 160.0]]]}).to_string(),
            )?;
            s.render_page(doc, 3, 2.0, None)?;
            let out = std::env::temp_dir().join("pdfcraft-contention-save.pdf");
            let saver = {
                let s = s.clone();
                let out = out.to_string_lossy().into_owned();
                std::thread::spawn(move || {
                    let t = Instant::now();
                    let r = s.execute("doc_save".into(), json!({"doc": doc, "path": out}).to_string());
                    (ms(t), r.is_ok())
                })
            };
            std::thread::sleep(std::time::Duration::from_millis(20));
            let t = Instant::now();
            s.render_page(doc, 4, 2.0, None)?;
            let during = ms(t);
            let (save_ms, ok) = saver.join().map_err(|_| "save thread panicked")?;
            println!("save_ms={save_ms} save_ok={ok} render_during_save_ms={during}");
            let _ = std::fs::remove_file(out);
        }
        "text" => {
            let before = rss_mib();
            let t = Instant::now();
            let mut hits = 0usize;
            for page in 0..pages {
                hits += s.find_in_page(doc, page, "fox".into(), false, false)?.len();
            }
            println!("pages={pages} hits={hits} search_ms={} rss_before_mib={before} rss_after_mib={}", ms(t), rss_mib());
            // a long press on page 0 while nothing else runs
            let t = Instant::now();
            s.word_at(doc, 0, 60.0, 45.0, 4.0)?;
            println!("word_at_after_search_ms={}", ms(t));
        }
        "lower" => {
            // one whole page at the 4 MP mobile cap, then the UniFFI lowering the app pays (#86)
            let t = Instant::now();
            let img = s.render_page(doc, 0, 4.0, None)?;
            let render = ms(t);
            let bytes = img.rgba.len();
            let t = Instant::now();
            let buf = <pdfcraft_android::RenderedImage as uniffi::Lower<pdfcraft_android::UniFfiTag>>::lower(img);
            let lower = t.elapsed().as_micros();
            println!("render_ms={render} pixel_bytes={bytes} uniffi_lower_us={lower} buffer_len={}", buf.len());
            drop(buf.destroy_into_vec());
        }
        other => return Err(format!("unknown mode {other}").into()),
    }
    s.close(doc, true)?;
    Ok(())
}
