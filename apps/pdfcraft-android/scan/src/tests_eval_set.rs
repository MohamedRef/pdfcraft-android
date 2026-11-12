//! Which entries of a kept-scans export are detection tests (#425).

use serde_json::{Value, json};

use crate::eval_set::{LeftOut, SetOptions, left_out, load_folder};

const CORNERS: [f64; 8] = [0.1, 0.1, 0.9, 0.1, 0.9, 0.9, 0.1, 0.9];

/// A page file as the app writes it (`scanTestPageJson`), saved, with `checked` as given.
fn kept(checked: Option<bool>) -> Value {
    let mut v = json!({
        "corners": CORNERS, "kind": "other", "paper": "unknown", "curved": false, "notes": "",
        "app": "1.0 (1)", "outcome": "saved", "found": CORNERS, "capture": null, "book": null,
    });
    if let Some(c) = checked {
        v["checked"] = json!(c);
    }
    v
}

#[test]
fn each_kind_of_page_that_is_not_a_detection_test_is_named() {
    let off = SetOptions::default();
    let mut merged = kept(Some(true));
    merged["capture"] = json!({"camera": "in_app", "merge": {"why": "dark", "frames": 4, "merged": true, "used": 3}});
    assert_eq!(left_out(&merged, off), Some(LeftOut::Merged));
    // a burst that fell back to one photo is a photo like any other
    merged["capture"]["merge"]["merged"] = json!(false);
    assert_eq!(left_out(&merged, off), None);
    // #426 writes photos_kept: false on a merged page, whose photos are gone
    merged["capture"]["photos_kept"] = json!(false);
    assert_eq!(left_out(&merged, off), Some(LeftOut::Merged));
    let mut half = kept(Some(true));
    half["book"] = json!({"half": "left", "gutter_found": true});
    assert_eq!(left_out(&half, off), Some(LeftOut::BookHalf));
    let mut discarded = kept(Some(true));
    discarded["outcome"] = json!("discarded");
    assert_eq!(left_out(&discarded, off), Some(LeftOut::Discarded));
    // the app writes "checked": false; until someone sets it, the corners are not ground truth
    assert_eq!(left_out(&kept(Some(false)), off), Some(LeftOut::Unchecked));
    assert_eq!(left_out(&kept(None), off), Some(LeftOut::Unchecked));
    assert_eq!(left_out(&kept(Some(true)), off), None);
    // --unchecked measures them, but never the other three
    let unchecked = SetOptions { unchecked: true };
    assert_eq!(left_out(&kept(Some(false)), unchecked), None);
    assert_eq!(left_out(&half, unchecked), Some(LeftOut::BookHalf));
    // a page file made by hand (no "app") is checked unless it says otherwise
    let hand = json!({"corners": CORNERS, "kind": "light", "paper": "a4"});
    assert_eq!(left_out(&hand, off), None);
    let mut doubtful = hand.clone();
    doubtful["checked"] = json!(false);
    assert_eq!(left_out(&doubtful, off), Some(LeftOut::Unchecked));
}

/// The issue's own check: a folder with one entry of each kind measures only the checked page and
/// says how many were left out and why.
#[test]
fn a_kept_scans_folder_leaves_out_what_is_not_ground_truth() {
    let dir = std::env::temp_dir().join(format!("pdfcraft-eval-set-{}", std::process::id()));
    let scan = dir.join("scan-01");
    std::fs::create_dir_all(&scan).unwrap();
    let mut pages = vec![("page-01", kept(Some(true))), ("page-02", kept(Some(false)))];
    let mut merged = kept(Some(true));
    merged["capture"] = json!({"merge": {"merged": true}});
    pages.push(("page-03", merged));
    let mut half = kept(Some(true));
    half["book"] = json!({"half": "right"});
    pages.push(("page-04", half));
    let mut discarded = kept(Some(true));
    discarded["outcome"] = json!("discarded");
    pages.push(("page-05", discarded));
    for (name, v) in &pages {
        std::fs::write(scan.join(format!("{name}.json")), v.to_string()).unwrap();
        std::fs::write(scan.join(format!("{name}.jpg")), b"photo").unwrap();
    }

    let loaded = load_folder(&dir, SetOptions::default()).unwrap();
    let names: Vec<&str> = loaded.entries.iter().map(|e| e.name.as_str()).collect();
    assert_eq!(names, ["scan-01/page-01"]);
    assert_eq!(loaded.entries[0].corners, Some(CORNERS.map(|v| v as f32)));
    let why: Vec<(&str, LeftOut)> = loaded.left_out.iter().map(|(n, w)| (n.as_str(), *w)).collect();
    assert_eq!(
        why,
        [
            ("scan-01/page-02", LeftOut::Unchecked),
            ("scan-01/page-03", LeftOut::Merged),
            ("scan-01/page-04", LeftOut::BookHalf),
            ("scan-01/page-05", LeftOut::Discarded),
        ]
    );
    assert_eq!(loaded.counts(), [(LeftOut::Merged, 1), (LeftOut::BookHalf, 1), (LeftOut::Discarded, 1), (LeftOut::Unchecked, 1)]);

    let all = load_folder(&dir, SetOptions { unchecked: true }).unwrap();
    assert_eq!(all.entries.len(), 2);
    assert_eq!(all.left_out.len(), 3);
    std::fs::remove_dir_all(dir).unwrap();
}
