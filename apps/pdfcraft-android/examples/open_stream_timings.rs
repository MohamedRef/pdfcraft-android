//! Manual host benchmark, not a device test: cargo run -p pdfcraft-android --example open_stream_timings -- 336
//! Creates two temporary PDFs with healthy xrefs; the second omits one stream's /Length.
use std::fs::File;
use std::io::{Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};

use pdfcraft_android::MobileSession;

struct Cleanup(Vec<PathBuf>);
impl Drop for Cleanup {
    fn drop(&mut self) {
        for path in &self.0 {
            let _ = std::fs::remove_file(path);
        }
    }
}

fn fixture(path: &Path, bytes: u64, length: bool) -> std::io::Result<()> {
    let mut file = File::create(path)?;
    file.write_all(b"%PDF-1.7\n")?;
    let mut offsets = Vec::new();
    let pages = 869;
    let last = pages + 3;
    for number in 1..=last {
        offsets.push(file.stream_position()?);
        writeln!(file, "{number} 0 obj")?;
        match number {
            1 => writeln!(file, "<< /Type /Catalog /Pages 2 0 R >>")?,
            2 => {
                write!(file, "<< /Type /Pages /Count {pages} /Kids [")?;
                for page in 3..last {
                    write!(file, "{page} 0 R ")?;
                }
                writeln!(file, "] /MediaBox [0 0 612 792] >>")?;
            }
            n if n == last => {
                if length {
                    writeln!(file, "<< /Length {bytes} >>")?;
                } else {
                    writeln!(file, "<< >>")?;
                }
                writeln!(file, "stream")?;
                // A sparse range reads as zeros, without constructing another large heap buffer.
                file.seek(SeekFrom::Current(bytes as i64))?;
                writeln!(file, "\nendstream")?;
            }
            _ => writeln!(file, "<< /Type /Page /Parent 2 0 R >>")?,
        }
        writeln!(file, "endobj")?;
    }
    let xref = file.stream_position()?;
    writeln!(file, "xref\n0 {}\n0000000000 65535 f ", last + 1)?;
    for offset in offsets {
        writeln!(file, "{offset:010} 00000 n ")?;
    }
    writeln!(file, "trailer\n<< /Size {} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF", last + 1)
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mib = std::env::args().nth(1).map(|s| s.parse::<u64>()).transpose()?.unwrap_or(336);
    if !(1..=480).contains(&mib) {
        return Err("choose 1..480 MiB, below the native input cap".into());
    }
    let mut cleanup = Cleanup(Vec::new());
    for length in [true, false] {
        let path = std::env::temp_dir().join(format!("pdfcraft-stream-bench-{}-{length}.pdf", std::process::id()));
        cleanup.0.push(path.clone());
        fixture(&path, mib * 1024 * 1024, length)?;
        let session = MobileSession::new();
        let opened = session.open_with_timings(path.to_string_lossy().into_owned(), None)?;
        println!(
            "length_present={length} bytes={} pages={} repairs={}",
            opened.document.file_size,
            opened.document.pages.len(),
            session.repair_count(opened.document.id)?
        );
        for stage in opened.timings {
            println!("  {} {} ms", stage.stage, stage.duration_ms);
        }
        session.close(opened.document.id, true)?;
    }
    Ok(())
}
