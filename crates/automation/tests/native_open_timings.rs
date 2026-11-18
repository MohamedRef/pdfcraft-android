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
