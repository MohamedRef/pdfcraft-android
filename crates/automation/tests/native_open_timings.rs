use pdfcraft_automation::{Automation, Content};
use serde_json::{Value, json};

fn open(a: &mut Automation, args: Value) -> Value {
    match a.call("doc_open", &args).unwrap().remove(0) {
        Content::Json(value) => value,
        other => panic!("expected JSON, got {other:?}"),
    }
}

#[test]
fn open_timings_are_opt_in_and_keep_the_same_document_summary() {
    let dir = tempfile::tempdir().unwrap();
    let bytes = pdfcraft_engine::Session::new().create_blank(200.0, 300.0, 3).unwrap();
    std::fs::write(dir.path().join("a.pdf"), bytes.as_slice()).unwrap();
    let mut a = Automation::new().with_root(dir.path()).unwrap().with_clock(|| 1_700_000_000);
    let plain = open(&mut a, json!({"path": "a.pdf"}));
    assert!(plain.get("open_timings").is_none());
    let traced = open(&mut a, json!({"path": "a.pdf", "open_timings": true}));
    let stages = traced["open_timings"].as_array().unwrap();
    assert!(stages.iter().any(|s| s["stage"] == "native_file_read"));
    assert!(stages.iter().any(|s| s["stage"] == "native_prepare"));
    for s in stages {
        assert_eq!(s.as_object().unwrap().len(), 2);
        assert!(s["duration_ms"].is_u64());
        assert!(s["stage"].as_str().unwrap().starts_with("native_"));
    }
    let mut traced_summary = traced;
    traced_summary.as_object_mut().unwrap().remove("open_timings");
    traced_summary["doc"] = plain["doc"].clone();
    assert_eq!(traced_summary, plain);
    assert!(a.call("doc_open", &json!({"path": "../outside.pdf", "open_timings": true})).is_err());
    assert_eq!(a.session().docs().len(), 2);
    assert_eq!(std::fs::read(dir.path().join("a.pdf")).unwrap().as_slice(), bytes.as_slice());
}

fn call(a: &mut Automation, tool: &str, args: Value) -> Value {
    match a.call(tool, &args).unwrap().remove(0) {
        Content::Json(value) => value,
        other => panic!("expected JSON, got {other:?}"),
    }
}

/// #595: `doc_save` says how long each stage of a save took only when asked, names each stage
/// once and in order, and saves the same way either way.
#[test]
fn save_timings_are_opt_in_and_name_each_stage_of_the_save_once() {
    let dir = tempfile::tempdir().unwrap();
    let bytes = pdfcraft_engine::Session::new().create_blank(200.0, 300.0, 3).unwrap();
    std::fs::write(dir.path().join("a.pdf"), bytes.as_slice()).unwrap();
    let mut a = Automation::new().with_root(dir.path()).unwrap().with_clock(|| 1_700_000_000);
    let doc = open(&mut a, json!({"path": "a.pdf"}))["doc"].clone();
    call(&mut a, "doc_set_info", json!({"doc": doc, "key": "Title", "value": "Plain"}));
    let plain = call(&mut a, "doc_save", json!({"doc": doc}));
    assert!(plain.get("save_timings").is_none());
    call(&mut a, "doc_set_info", json!({"doc": doc, "key": "Title", "value": "Timed"}));
    let traced = call(&mut a, "doc_save", json!({"doc": doc, "save_timings": true}));
    let stages = traced["save_timings"].as_array().unwrap();
    let names: Vec<&str> = stages.iter().map(|s| s["stage"].as_str().unwrap()).collect();
    assert_eq!(names, ["save_build", "save_check_open", "save_check_pages", "save_write", "save_reopen", "save_refresh"]);
    for s in stages {
        assert_eq!(s.as_object().unwrap().len(), 2);
        assert!(s["duration_ms"].is_u64());
    }
    assert_eq!(traced["incremental"], true);
    assert_eq!(plain["incremental"], true);
    let mut keys: Vec<&String> = traced.as_object().unwrap().keys().filter(|k| *k != "save_timings").collect();
    keys.sort();
    let mut plain_keys: Vec<&String> = plain.as_object().unwrap().keys().collect();
    plain_keys.sort();
    assert_eq!(keys, plain_keys);
    assert_eq!(traced["document"]["dirty"], false);
    // both saves are in the file: the timed one appended its revision to the plain one's
    let saved = std::fs::read(dir.path().join("a.pdf")).unwrap();
    assert_eq!(traced["bytes"].as_u64().unwrap() as usize, saved.len());
    assert!(saved.len() > plain["bytes"].as_u64().unwrap() as usize);
    let reopened = open(&mut a, json!({"path": "a.pdf"}))["doc"].clone();
    let info = call(&mut a, "doc_info", json!({"doc": reopened}));
    assert_eq!(info["title"], "Timed");
}

/// What `doc_info` shows of a document, without what belongs to this visit (its number, name,
/// path, undo and redo).
fn shown(mut info: Value) -> Value {
    info.as_object_mut().unwrap().remove("document");
    info
}

/// #595: after an incremental save the document is not inspected again, so it must show exactly
/// what opening the saved file shows: pages, comments, metadata, fonts and the file's size, also
/// after a second save on top of the first and after a page turned since.
#[test]
fn a_document_after_an_incremental_save_shows_what_the_saved_file_opened_anew_shows() {
    let dir = tempfile::tempdir().unwrap();
    let bytes = pdfcraft_engine::Session::new().create_blank(200.0, 300.0, 3).unwrap();
    std::fs::write(dir.path().join("a.pdf"), bytes.as_slice()).unwrap();
    let mut a = Automation::new().with_root(dir.path()).unwrap().with_clock(|| 1_700_000_000);
    let doc = open(&mut a, json!({"path": "a.pdf"}))["doc"].clone();
    call(&mut a, "doc_set_info", json!({"doc": doc, "key": "Title", "value": "Saved"}));
    call(&mut a, "comment_add", json!({"doc": doc, "page": 1, "type": "ink", "strokes": [[[10, 280], [50, 260], [90, 285]]]}));
    call(&mut a, "comment_add", json!({"doc": doc, "page": 2, "type": "note", "at": [150, 20], "contents": "Sticky"}));
    for round in 0..2 {
        if round == 1 {
            call(&mut a, "comment_add", json!({"doc": doc, "page": 3, "type": "ink", "strokes": [[[20, 20], [60, 80]]]}));
            call(&mut a, "page_rotate", json!({"doc": doc, "pages": [2], "degrees": 90}));
        }
        let saved = call(&mut a, "doc_save", json!({"doc": doc, "save_timings": true}));
        assert_eq!(saved["incremental"], true, "round {round}");
        let file = std::fs::read(dir.path().join("a.pdf")).unwrap();
        assert_eq!(saved["bytes"].as_u64().unwrap() as usize, file.len());
        let after_save = shown(call(&mut a, "doc_info", json!({"doc": doc})));
        assert_eq!(after_save["file_size"].as_u64().unwrap() as usize, file.len(), "round {round}");
        let fresh = open(&mut a, json!({"path": "a.pdf"}))["doc"].clone();
        let opened = shown(call(&mut a, "doc_info", json!({"doc": fresh})));
        assert_eq!(after_save, opened, "round {round}: the saved document reads as the saved file");
        call(&mut a, "doc_close", json!({"doc": fresh}));
    }
    // and it goes on drawing and saving from there
    let page = a.call("page_render", &json!({"doc": doc, "page": 1, "dpi": 36})).unwrap();
    assert!(!page.is_empty());
}

/// #738: Export a copy writes the document as it is now, edits included, and leaves it unsaved at
/// its own path; the file it was opened from is not touched.
#[test]
fn a_copy_has_the_edits_and_the_document_stays_unsaved_at_its_own_path() {
    let dir = tempfile::tempdir().unwrap();
    let bytes = pdfcraft_engine::Session::new().create_blank(200.0, 300.0, 2).unwrap();
    std::fs::write(dir.path().join("a.pdf"), bytes.as_slice()).unwrap();
    let mut a = Automation::new().with_root(dir.path()).unwrap().with_clock(|| 1_700_000_000);
    let doc = open(&mut a, json!({"path": "a.pdf"}))["doc"].clone();
    call(&mut a, "doc_set_info", json!({"doc": doc, "key": "Title", "value": "Exported"}));
    let out = call(&mut a, "doc_save", json!({"doc": doc, "path": "b.pdf", "copy": true}));
    assert_eq!(out["copy"], true);
    assert_eq!(out["document"]["dirty"], true, "still unsaved");
    assert!(out["document"]["path"].as_str().unwrap().ends_with("a.pdf"), "still at its own path");
    assert_eq!(std::fs::read(dir.path().join("a.pdf")).unwrap().as_slice(), bytes.as_slice(), "the original is untouched");
    let copy = open(&mut a, json!({"path": "b.pdf"}))["doc"].clone();
    assert_eq!(call(&mut a, "doc_info", json!({"doc": copy}))["title"], "Exported");
    // a copy needs a place, and doesn't change the document by flattening it
    assert!(a.call("doc_save", &json!({"doc": doc, "copy": true})).is_err());
    assert!(a.call("doc_save", &json!({"doc": doc, "path": "c.pdf", "copy": true, "flatten_fill_sign": true})).is_err());
    // the document can still be saved as usual afterwards
    let saved = call(&mut a, "doc_save", json!({"doc": doc}));
    assert_eq!(saved["document"]["dirty"], false);
}
