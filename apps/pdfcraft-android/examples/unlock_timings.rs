//! Manual host profile of Unlock PDF (#550): the tool calls `UnlockPdf.kt` makes, timed one by
//! one, on a restrictions-only copy of a local PDF. `prepare` protects a plain PDF with a
//! permissions password only (as a restricted download is); `run` unlocks it each round the old
//! way (doc_unprotect, doc_save full, doc_close) and in one step (doc_unprotect with out). Byte
//! equality of the two is tested in the automation tests (a fixed clock; here /ModDate differs).
//! Paths and PDF contents are not logged.
use pdfcraft_android::MobileSession;
use serde_json::json;
use std::time::Instant;

fn tool(s: &MobileSession, name: &str, args: serde_json::Value) -> Result<serde_json::Value, Box<dyn std::error::Error>> {
    let started = Instant::now();
    let out = s.execute(name.into(), args.to_string())?;
    println!("  {name:<14} {:>7} ms", started.elapsed().as_millis());
    Ok(serde_json::from_str(&out)?)
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = std::env::args().skip(1);
    let usage = "usage: unlock_timings prepare <plain.pdf> <locked.pdf> | run <locked.pdf> <out.pdf> [rounds]";
    let mode = args.next().ok_or(usage)?;
    let input = args.next().ok_or(usage)?;
    let output = args.next().ok_or(usage)?;
    match mode.as_str() {
        "prepare" => {
            let s = MobileSession::new();
            let doc = tool(&s, "doc_open", json!({ "path": input }))?["doc"].clone();
            tool(&s, "doc_protect", json!({ "doc": doc, "permissions_password": "owner12", "printing": "none", "changes": "none", "copy": false }))?;
            tool(&s, "doc_save", json!({ "doc": doc, "path": output, "full": true }))?;
            tool(&s, "doc_close", json!({ "doc": doc, "discard_changes": true }))?;
        }
        "run" => {
            let rounds: u32 = args.next().and_then(|r| r.parse().ok()).unwrap_or(3);
            for (round, two_step) in (1..=rounds).flat_map(|r| [(r, true), (r, false)]) {
                println!("round={round} {}", if two_step { "two_step (unprotect, save, close)" } else { "one_step (unprotect with out)" });
                let s = MobileSession::new();
                let started = Instant::now();
                let doc = tool(&s, "doc_open", json!({ "path": input }))?["doc"].clone();
                tool(&s, "doc_info", json!({ "doc": doc }))?;
                tool(&s, "sign_list", json!({ "doc": doc }))?;
                if two_step {
                    tool(&s, "doc_unprotect", json!({ "doc": doc, "restrictions": true }))?;
                    tool(&s, "doc_save", json!({ "doc": doc, "path": output, "full": true }))?;
                    tool(&s, "doc_close", json!({ "doc": doc, "discard_changes": true }))?;
                } else {
                    tool(&s, "doc_unprotect", json!({ "doc": doc, "restrictions": true, "out": output }))?;
                }
                let bytes = std::fs::read(&output)?;
                println!("  total {:>7} ms  out_bytes={}", started.elapsed().as_millis(), bytes.len());
            }
        }
        _ => return Err(usage.into()),
    }
    Ok(())
}
