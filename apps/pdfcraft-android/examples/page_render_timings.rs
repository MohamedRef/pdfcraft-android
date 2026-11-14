//! Manual host profile of whole-page renders as the viewer asks for them (#236): every page of a
//! local PDF at one scale, several rounds on one open document. Paths and contents are not logged.
use pdfcraft_android::MobileSession;
use std::time::Instant;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let path = std::env::args().nth(1).ok_or("usage: page_render_timings /absolute/file.pdf [scale] [rounds] [page]")?;
    let scale: f32 = std::env::args().nth(2).and_then(|s| s.parse().ok()).unwrap_or(1.702);
    let rounds: u32 = std::env::args().nth(3).and_then(|r| r.parse().ok()).unwrap_or(3);
    let only: Option<u32> = std::env::args().nth(4).and_then(|p| p.parse().ok());
    let session = MobileSession::new();
    let info = session.open(path, None)?;
    for round in 1..=rounds {
        for page in 0..info.pages.len() as u32 {
            if only.is_some_and(|p| p != page + 1) {
                continue;
            }
            let started = Instant::now();
            let img = session.render_page_compact(info.id, page, scale, None, true)?;
            println!("round={round} page={} ms={} px={}x{}", page + 1, started.elapsed().as_millis(), img.width, img.height);
        }
    }
    Ok(())
}
