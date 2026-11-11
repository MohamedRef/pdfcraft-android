//! Manual host profile of the reading stage and background preparation, stage by stage.
//! Supply a synthetic/local PDF; paths and PDF contents are not logged.
use pdfcraft_android::MobileSession;
use std::time::Instant;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let path = std::env::args().nth(1).ok_or("usage: open_stage_timings /absolute/file.pdf [rounds]")?;
    let rounds: u32 = std::env::args().nth(2).and_then(|r| r.parse().ok()).unwrap_or(3);
    let warm = MobileSession::new();
    let info = warm.open(path.clone(), None)?;
    let _ = warm.render_page(info.id, 0, 1.0, None)?;
    warm.close(info.id, true)?;
    for round in 1..=rounds {
        let session = MobileSession::new();
        let started = Instant::now();
        let reading = session.begin_open(path.clone(), None, 0, true)?;
        let _ = session.render_page(reading.document.id, 0, 1.0, None)?;
        let first_ms = started.elapsed().as_millis();
        let complete = if reading.document.preparing { Some(session.complete_open(reading.document.id)?) } else { None };
        let ready_ms = started.elapsed().as_millis();
        println!("round={round} reading_stage={} first_raster_ms={first_ms} ready_ms={ready_ms}", reading.document.preparing);
        for t in reading.timings.iter().chain(complete.iter().flat_map(|c| c.timings.iter())) {
            println!("  {:<40} {:>6}", t.stage, t.duration_ms);
        }
        session.close(reading.document.id, true)?;
    }
    Ok(())
}
