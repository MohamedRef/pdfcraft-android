//! Manual host comparison. Supply a synthetic/local PDF; paths and PDF contents are not logged.
use pdfcraft_android::MobileSession;
use std::time::Instant;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let path = std::env::args().nth(1).ok_or("usage: progressive_reading_timings /absolute/file.pdf")?;
    // Warm filesystem and one-time renderer initialization before comparing both paths.
    let warm = MobileSession::new();
    let info = warm.open(path.clone(), None)?;
    let _ = warm.render_page(info.id, 0, 1.0, None)?;
    warm.close(info.id, true)?;
    for round in 1..=3 {
        let session = MobileSession::new();
        let started = Instant::now();
        let full = session.open(path.clone(), None)?;
        let _ = session.render_page(full.id, 0, 1.0, None)?;
        let complete_first_raster_ms = started.elapsed().as_millis();
        session.close(full.id, true)?;

        let session = MobileSession::new();
        let started = Instant::now();
        let reading = session.begin_open(path.clone(), None, 0, true)?;
        let early_stage = reading.document.preparing;
        let _ = session.render_page(reading.document.id, 0, 1.0, None)?;
        let reading_first_raster_ms = started.elapsed().as_millis();
        if early_stage {
            session.complete_open(reading.document.id)?;
        }
        let full_ready_ms = started.elapsed().as_millis();
        println!(
            "round={round} reading_stage={early_stage} complete_first_raster_ms={complete_first_raster_ms} reading_first_raster_ms={reading_first_raster_ms} full_ready_ms={full_ready_ms}"
        );
    }
    Ok(())
}
