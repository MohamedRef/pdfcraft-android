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
