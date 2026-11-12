//! A test set folder for `scan-eval` (#288), and which of its entries may be used as detection
//! ground truth (#425).
//!
//! A test set is `<name>.jpg` with `<name>.json` (`corners`, `kind`, `paper`), looked for in every
//! subfolder. An unzipped kept-scans export from the phone has that shape, but not every page file
//! in it is a detection test:
//!
//! - a page made by a low-light merge (`capture.merge.merged`, or `capture.photos_kept` false): its photo is an already cropped
//!   page, so its corners are the whole picture;
//! - one half of a book spread (`book`): the photo holds the spread, the corners only half of it;
//! - a page of a discarded scan (`outcome` is `discarded`): often the one where something went
//!   wrong, corners included;
//! - a page whose corners nobody checked: they are the detector's own answer, left as they were.
//!
//! The first three are left out always, the last one unless asked for. A page file the app wrote
//! (it has `app`) counts as checked only once someone has set `"checked": true` in it; a page file
//! made by hand (no `app`) counts as checked unless it says `"checked": false`.

use std::path::Path;

use serde_json::Value;

use crate::eval::{Entry, paper_aspect};
use crate::synth::Kind;

/// Why an entry was left out of the measures.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub enum LeftOut {
    /// A low-light merge's page: the photo is already the cropped page.
    Merged,
    /// One half of a book spread: the corners are half the paper in the photo.
    BookHalf,
    /// A page of a scan that was discarded.
    Discarded,
    /// Corners nobody has checked yet.
    Unchecked,
}

impl LeftOut {
    pub const ALL: [LeftOut; 4] = [LeftOut::Merged, LeftOut::BookHalf, LeftOut::Discarded, LeftOut::Unchecked];

    pub fn name(self) -> &'static str {
        match self {
            LeftOut::Merged => "merged",
            LeftOut::BookHalf => "book_half",
            LeftOut::Discarded => "discarded",
            LeftOut::Unchecked => "unchecked",
        }
    }

    /// What the report says about it.
    pub fn why(self) -> &'static str {
        match self {
            LeftOut::Merged => "a low-light merge: the photo is the cropped page already",
            LeftOut::BookHalf => "one half of a book spread: the corners are half the paper in the photo",
            LeftOut::Discarded => "the scan was discarded",
            LeftOut::Unchecked => "nobody checked the corners (set \"checked\": true, or run with --unchecked)",
        }
    }
}

/// Which entries a folder gives.
#[derive(Clone, Copy, Debug, Default)]
pub struct SetOptions {
    /// Also measure entries whose corners nobody checked (`--unchecked`).
    pub unchecked: bool,
}

/// Why the page file `v` is not a detection test, or `None` when it is one. The first reason that
/// applies, in the order of [`LeftOut`].
pub fn left_out(v: &Value, options: SetOptions) -> Option<LeftOut> {
    // `photos_kept` (#426) is false on the same pages; either says so
    if v["capture"]["merge"]["merged"].as_bool() == Some(true) || v["capture"]["photos_kept"].as_bool() == Some(false) {
        return Some(LeftOut::Merged);
    }
    if !v["book"].is_null() {
        return Some(LeftOut::BookHalf);
    }
    if v["outcome"].as_str() == Some("discarded") {
        return Some(LeftOut::Discarded);
    }
    let from_app = v.get("app").is_some();
    let checked = v["checked"].as_bool().unwrap_or(!from_app);
    (!checked && !options.unchecked).then_some(LeftOut::Unchecked)
}

/// A folder's entries, and the ones left out.
#[derive(Debug, Default)]
pub struct Loaded {
    pub entries: Vec<Entry>,
    /// Name and reason, in name order.
    pub left_out: Vec<(String, LeftOut)>,
}

impl Loaded {
    /// How many were left out for each reason, every reason listed.
    pub fn counts(&self) -> Vec<(LeftOut, usize)> {
        LeftOut::ALL.iter().map(|&r| (r, self.left_out.iter().filter(|(_, w)| *w == r).count())).collect()
    }
}

/// Every `<name>.json` under `dir` with a `<name>.jpg` beside it, sorted by name; see the module.
pub fn load_folder(dir: &Path, options: SetOptions) -> Result<Loaded, String> {
    let mut files = Vec::new();
    let mut stack = vec![dir.to_path_buf()];
    while let Some(d) = stack.pop() {
        for item in std::fs::read_dir(&d).map_err(|e| format!("{}: {e}", d.display()))? {
            let path = item.map_err(|e| e.to_string())?.path();
            if path.is_dir() {
                stack.push(path);
            } else if path.extension().is_some_and(|e| e == "json") && path.with_extension("jpg").is_file() {
                files.push(path);
            }
        }
    }
    files.sort();
    let mut out = Loaded::default();
    for json_path in files {
        let text = std::fs::read_to_string(&json_path).map_err(|e| format!("{}: {e}", json_path.display()))?;
        let Ok(v) = serde_json::from_str::<Value>(&text) else {
            eprintln!("skipped {}: not JSON", json_path.display());
            continue;
        };
        let name = json_path.strip_prefix(dir).unwrap_or(&json_path).with_extension("").to_string_lossy().into_owned();
        if let Some(why) = left_out(&v, options) {
            out.left_out.push((name, why));
            continue;
        }
        let corners = v["corners"].as_array().filter(|a| a.len() == 8).and_then(|a| {
            let vals: Vec<f32> = a.iter().filter_map(|x| x.as_f64().map(|f| f as f32)).collect();
            <[f32; 8]>::try_from(vals).ok()
        });
        let photo = std::fs::read(json_path.with_extension("jpg")).map_err(|e| e.to_string())?;
        out.entries.push(Entry {
            name,
            kind: Kind::from_name(v["kind"].as_str().unwrap_or("other")),
            photo,
            corners,
            aspect: v["paper"].as_str().and_then(paper_aspect),
            focal_35mm: None,
            page: None,
        });
    }
    Ok(out)
}
