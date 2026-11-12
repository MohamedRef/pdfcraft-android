//! The scanner's measuring runner (#288, section 4). Not part of the app.
//!
//! ```text
//! cargo run --release -p pdfcraft-scan --features synth --bin scan-eval -- \
//!     (--synthetic N [--seed S] [--size WxH] | <folder>) \
//!     [--finder auto|network|lines|off] [--no-refine] [--unchecked] \
//!     [--json out.json] [--md out.md] [--baseline base.json] [--contact-sheet <folder>]
//! ```
//!
//! `<folder>` is a test set: `<name>.jpg` with `<name>.json` (`corners`, `kind`, `paper`), looked
//! for in every subfolder. An unzipped kept-scans export from the phone has that shape, but its
//! figures mean something only once someone has checked the corners of its pages and set
//! `"checked": true` in their page files (#425): pages the app wrote are left out until then
//! (`--unchecked` measures them anyway). Pages made by a low-light merge, halves of a book spread
//! and pages of discarded scans are always left out, being wrong as detection tests by
//! construction. The report says how many were left out and why (`eval_set`).
//! The report has a table per kind (median, mean and 95th percentile of every measure) and the
//! time of each stage. With `--baseline`, it exits with 1 when a measure's mean got worse than
//! the baseline report's by more than its tolerance (`eval::METRICS`).
//!
//! Contact sheets are the photos with the true outline in green and the found one in red. Never
//! make them from someone's own documents and put them in the repository or a pull request.

use std::fmt::Write as _;
use std::path::{Path, PathBuf};
use std::process::ExitCode;

use pdfcraft_scan::Finder;
use pdfcraft_scan::eval::{self, Entry, METRICS, Measured, Options, Summary};
use pdfcraft_scan::eval_set::{self, Loaded, SetOptions};
use pdfcraft_scan::synth::Kind;
use serde_json::{Value, json};

struct Args {
    source: Source,
    options: Options,
    json: Option<PathBuf>,
    md: Option<PathBuf>,
    baseline: Option<PathBuf>,
    contact_sheet: Option<PathBuf>,
    set: SetOptions,
}

enum Source {
    Synthetic { n: usize, seed: u64, width: u32, height: u32 },
    Folder(PathBuf),
}

const USAGE: &str = "usage: scan-eval (--synthetic N [--seed S] [--size WxH] | <folder>) [--finder auto|network|lines|off] [--no-refine] [--unchecked] [--json out.json] [--md out.md] [--baseline base.json] [--contact-sheet <folder>]";

fn parse(mut it: impl Iterator<Item = String>) -> Result<Args, String> {
    let (mut synthetic, mut seed, mut size, mut folder) = (None, 0u64, (800u32, 1066u32), None);
    let mut options = Options::default();
    let (mut json, mut md, mut baseline, mut contact_sheet) = (None, None, None, None);
    let mut set = SetOptions::default();
    while let Some(arg) = it.next() {
        let mut value = || it.next().ok_or_else(|| format!("{arg} needs a value"));
        match arg.as_str() {
            "--synthetic" => synthetic = Some(value()?.parse::<usize>().map_err(|e| e.to_string())?),
            "--seed" => seed = value()?.parse().map_err(|e: std::num::ParseIntError| e.to_string())?,
            "--size" => {
                let v = value()?;
                let (w, h) = v.split_once('x').ok_or("--size is WxH")?;
                size = (w.parse().map_err(|_| "--size is WxH")?, h.parse().map_err(|_| "--size is WxH")?);
            }
            "--finder" => {
                options.finder = match value()?.as_str() {
                    "auto" => Finder::Auto,
                    "network" => Finder::Network,
                    "lines" => Finder::Lines,
                    "off" => Finder::Off,
                    other => return Err(format!("unknown finder {other}")),
                }
            }
            "--no-refine" => options.refine = false,
            "--unchecked" => set.unchecked = true,
            "--json" => json = Some(PathBuf::from(value()?)),
            "--md" => md = Some(PathBuf::from(value()?)),
            "--baseline" => baseline = Some(PathBuf::from(value()?)),
            "--contact-sheet" => contact_sheet = Some(PathBuf::from(value()?)),
            "-h" | "--help" => return Err(USAGE.into()),
            other if other.starts_with('-') => return Err(format!("unknown option {other}\n{USAGE}")),
            other => folder = Some(PathBuf::from(other)),
        }
    }
    let source = match (synthetic, folder) {
        (Some(n), None) => Source::Synthetic { n, seed, width: size.0, height: size.1 },
        (None, Some(dir)) => Source::Folder(dir),
        _ => return Err(USAGE.into()),
    };
    Ok(Args { source, options, json, md, baseline, contact_sheet, set })
}

fn fmt(v: f64) -> String {
    if !v.is_finite() {
        "–".into()
    } else if v.abs() >= 100.0 {
        format!("{v:.1}")
    } else if v.abs() >= 1.0 {
        format!("{v:.3}")
    } else {
        format!("{v:.4}")
    }
}

fn table(out: &mut String, title: &str, rows: &[(&'static str, Summary)]) {
    let _ = writeln!(out, "\n### {title}\n\n| measure | median | mean | p95 | n |\n|---|---|---|---|---|");
    for (name, s) in rows {
        let _ = writeln!(out, "| {name} | {} | {} | {} | {} |", fmt(s.median), fmt(s.mean), fmt(s.p95), s.n);
    }
}

fn kinds_of(measured: &[Measured]) -> Vec<Kind> {
    let mut kinds: Vec<Kind> = measured.iter().map(|m| m.kind).collect();
    kinds.sort();
    kinds.dedup();
    kinds
}

fn report(measured: &[Measured], source: &str, left_out: &Loaded) -> String {
    let all: Vec<&Measured> = measured.iter().collect();
    let found = measured.iter().filter(|m| m.detected).count();
    let mut out = format!("## scan-eval: {source}\n\n{} photos; the detector found a page in {found}.\n", measured.len());
    if !left_out.left_out.is_empty() {
        let _ = writeln!(out, "\n{} left out, not measured (#425):\n\n| why | n |\n|---|---|", left_out.left_out.len());
        for (why, n) in left_out.counts().into_iter().filter(|(_, n)| *n > 0) {
            let _ = writeln!(out, "| {}: {} | {n} |", why.name(), why.why());
        }
    }
    table(&mut out, "All", &eval::summary_of(&all));
    for kind in kinds_of(measured) {
        let some: Vec<&Measured> = measured.iter().filter(|m| m.kind == kind).collect();
        table(&mut out, kind.name(), &eval::summary_of(&some));
    }
    let _ = writeln!(out, "\n### Time per stage (ms)\n\n| stage | median | mean | p95 |\n|---|---|---|---|");
    for (stage, s) in eval::timing_summary(&all) {
        let _ = writeln!(out, "| {stage} | {:.1} | {:.1} | {:.1} |", s.median, s.mean, s.p95);
    }
    out
}

fn summary_json(rows: &[(&'static str, Summary)]) -> Value {
    let num = |v: f64| if v.is_finite() { json!(v) } else { Value::Null };
    Value::Object(
        rows.iter()
            .map(|(name, s)| ((*name).to_string(), json!({"median": num(s.median), "mean": num(s.mean), "p95": num(s.p95), "n": s.n})))
            .collect(),
    )
}

fn report_json(measured: &[Measured], source: &str, left_out: &Loaded) -> Value {
    let all: Vec<&Measured> = measured.iter().collect();
    let mut summary = serde_json::Map::new();
    summary.insert("all".into(), summary_json(&eval::summary_of(&all)));
    for kind in kinds_of(measured) {
        let some: Vec<&Measured> = measured.iter().filter(|m| m.kind == kind).collect();
        summary.insert(kind.name().into(), summary_json(&eval::summary_of(&some)));
    }
    let entries: Vec<Value> = measured
        .iter()
        .map(|m| {
            let metrics: serde_json::Map<String, Value> = METRICS
                .iter()
                .zip(m.values)
                .map(|(metric, v)| (metric.name.to_string(), if v.is_finite() { json!(v) } else { Value::Null }))
                .collect();
            let timings: serde_json::Map<String, Value> = m.timings.iter().map(|(s, ms)| ((*s).to_string(), json!(ms))).collect();
            json!({"name": m.name, "kind": m.kind.name(), "detected": m.detected, "metrics": metrics, "timings_ms": timings})
        })
        .collect();
    let counts: serde_json::Map<String, Value> = left_out.counts().into_iter().map(|(why, n)| (why.name().to_string(), json!(n))).collect();
    let names: Vec<Value> = left_out.left_out.iter().map(|(name, why)| json!({"name": name, "why": why.name()})).collect();
    json!({
        "source": source,
        "summary": summary,
        "timings_ms": summary_json(&eval::timing_summary(&all)),
        "entries": entries,
        "left_out": {"total": left_out.left_out.len(), "counts": counts, "entries": names},
    })
}

fn baseline_means(path: &Path) -> Result<Vec<(String, f64)>, String> {
    let text = std::fs::read_to_string(path).map_err(|e| format!("{}: {e}", path.display()))?;
    let v: Value = serde_json::from_str(&text).map_err(|e| format!("{}: {e}", path.display()))?;
    let all = v["summary"]["all"].as_object().ok_or("the baseline has no summary.all")?;
    Ok(all.iter().filter_map(|(k, s)| s["mean"].as_f64().map(|m| (k.clone(), m))).collect())
}

/// The photo with the true outline in green and the found one in red, as a PNG.
fn contact_sheet(entry: &Entry, m: &Measured, dir: &Path) -> Result<(), String> {
    let mut img = image::load_from_memory(&entry.photo).map_err(|e| e.to_string())?.to_rgb8();
    let mut draw = |quad: &pdfcraft_scan::Quad, colour: [u8; 3]| {
        let (w, h) = img.dimensions();
        for i in 0..4 {
            let (a, b) = (quad.0[i], quad.0[(i + 1) % 4]);
            let steps = (a[0] - b[0]).abs().max((a[1] - b[1]).abs()).ceil().max(1.0) as u32;
            for k in 0..=steps {
                let t = f64::from(k) / f64::from(steps);
                let (x, y) = (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t);
                for (dx, dy) in [(0, 0), (1, 0), (0, 1), (1, 1)] {
                    let (px, py) = (x as i64 + dx, y as i64 + dy);
                    if px >= 0 && py >= 0 && (px as u32) < w && (py as u32) < h {
                        img.put_pixel(px as u32, py as u32, image::Rgb(colour));
                    }
                }
            }
        }
    };
    if let Some(t) = &m.truth {
        draw(t, [0, 200, 0]);
    }
    if let Some(f) = &m.found {
        draw(f, [230, 0, 0]);
    }
    let path = dir.join(format!("{}.png", m.name.replace(['/', '\\'], "_")));
    img.save(&path).map_err(|e| format!("{}: {e}", path.display()))
}

fn run(args: &Args) -> Result<bool, String> {
    let mut left_out = Loaded::default();
    let (entries, source): (Box<dyn Iterator<Item = Entry>>, String) = match &args.source {
        Source::Synthetic { n, seed, width, height } => {
            (Box::new(eval::synthetic(*n, *seed, *width, *height)), format!("{n} drawn photos, {width}×{height}, seeds from {seed}"))
        }
        Source::Folder(dir) => {
            let loaded = eval_set::load_folder(dir, args.set)?;
            for (name, why) in &loaded.left_out {
                eprintln!("{name}: left out ({})", why.name());
            }
            left_out.left_out = loaded.left_out;
            (
                Box::new(loaded.entries.into_iter()),
                format!("folder {}", dir.file_name().map_or_else(String::new, |n| n.to_string_lossy().into_owned())),
            )
        }
    };
    if let Some(dir) = &args.contact_sheet {
        std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    }
    let mut measured = Vec::new();
    for entry in entries {
        let m = eval::measure(&entry, &args.options);
        if let Some(dir) = &args.contact_sheet {
            contact_sheet(&entry, &m, dir)?;
        }
        eprintln!("{}: iou {} corner_err_max {}", m.name, fmt(m.get("iou")), fmt(m.get("corner_err_max")));
        measured.push(m);
    }
    let md = report(&measured, &source, &left_out);
    println!("{md}");
    if let Some(path) = &args.md {
        std::fs::write(path, &md).map_err(|e| e.to_string())?;
    }
    if let Some(path) = &args.json {
        let text = serde_json::to_string_pretty(&report_json(&measured, &source, &left_out)).map_err(|e| e.to_string())?;
        std::fs::write(path, text).map_err(|e| e.to_string())?;
    }
    let Some(path) = &args.baseline else { return Ok(true) };
    let all: Vec<&Measured> = measured.iter().collect();
    let worse = eval::regressions(&eval::summary_of(&all), &baseline_means(path)?);
    for r in &worse {
        println!("REGRESSION {}: mean {} against {} in the baseline", r.metric, fmt(r.now), fmt(r.baseline));
    }
    Ok(worse.is_empty())
}

fn main() -> ExitCode {
    let args = match parse(std::env::args().skip(1)) {
        Ok(a) => a,
        Err(e) => {
            eprintln!("{e}");
            return ExitCode::from(2);
        }
    };
    match run(&args) {
        Ok(true) => ExitCode::SUCCESS,
        Ok(false) => ExitCode::from(1),
        Err(e) => {
            eprintln!("{e}");
            ExitCode::from(2)
        }
    }
}
