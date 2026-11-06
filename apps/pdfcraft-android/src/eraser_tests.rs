use super::MobileSession;
use serde_json::{Value, json};
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};

static NEXT: AtomicU64 = AtomicU64::new(1);

fn blank_pdf() -> Vec<u8> {
    let objs = [
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 200 300] >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 300] >>",
    ];
    let mut data = b"%PDF-1.7\n".to_vec();
    let mut offsets = Vec::new();
    for (i, object) in objs.iter().enumerate() {
        offsets.push(data.len());
        data.extend_from_slice(format!("{} 0 obj\n{}\nendobj\n", i + 1, object).as_bytes());
    }
    let xref = data.len();
    data.extend_from_slice(format!("xref\n0 {}\n0000000000 65535 f \n", objs.len() + 1).as_bytes());
    for offset in offsets {
        data.extend_from_slice(format!("{offset:010} 00000 n \n").as_bytes());
    }
    data.extend_from_slice(format!("trailer\n<< /Size {} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n", objs.len() + 1).as_bytes());
    data
}

struct Fixture(PathBuf);
impl Fixture {
    fn new() -> Self {
        let id = NEXT.fetch_add(1, Ordering::Relaxed);
        let path = std::env::temp_dir().join(format!("pdfcraft-android-eraser-{}-{id}.pdf", std::process::id()));
        std::fs::write(&path, blank_pdf()).unwrap();
        Self(path)
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
    }
}

fn tool(mobile: &MobileSession, name: &str, args: Value) -> Value {
    serde_json::from_str(&mobile.execute(name.into(), args.to_string()).unwrap()).unwrap()
}

#[test]
fn eraser_rewrites_only_intersecting_ink_and_is_undoable() {
    let fixture = Fixture::new();
    let mobile = MobileSession::new();
    let doc = mobile.open(fixture.0.to_string_lossy().to_string(), None).unwrap();
    for strokes in [json!([[[20, 20], [90, 90]]]), json!([[[150, 160], [180, 190]]])] {
        tool(&mobile, "comment_add", json!({
            "doc": doc.id, "page": 1, "type": "ink", "strokes": strokes,
            "color": "#161616", "opacity": 1.0, "width": 2.0,
        }));
    }
    let before = mobile.comments(doc.id).unwrap();
    assert_eq!(before.len(), 2);
    let far_rect = before[1].rect.clone();

    let no_hit = tool(&mobile, "comment_erase", json!({
        "doc": doc.id, "page": 1, "path": [[195, 290]], "radius": 3.0,
    }));
    assert_eq!(no_hit["candidates"], 0);
    let done = tool(&mobile, "comment_erase", json!({
        "doc": doc.id, "page": 1, "path": [[20, 20], [27, 27]], "radius": 9.0,
    }));
    assert_eq!(done["candidates"], 1);
    let changed = mobile.comments(doc.id).unwrap();
    assert_eq!(changed.len(), 2);
    assert_ne!(changed[0].rect, before[0].rect, "part of the line should be removed, not the entire comment");
    assert_eq!(changed[1].rect, far_rect, "unrelated ink must stay untouched");

    tool(&mobile, "edit_undo", json!({ "doc": doc.id }));
    let restored = mobile.comments(doc.id).unwrap();
    assert_eq!(restored[0].rect, before[0].rect);
    assert_eq!(restored[1].rect, before[1].rect);

    tool(&mobile, "edit_redo", json!({ "doc": doc.id }));
    assert_eq!(mobile.comments(doc.id).unwrap()[0].rect, changed[0].rect);
    mobile.close(doc.id, true).unwrap();
}

#[test]
fn eraser_rejects_missing_or_excessive_paths_without_edit() {
    let fixture = Fixture::new();
    let mobile = MobileSession::new();
    let doc = mobile.open(fixture.0.to_string_lossy().to_string(), None).unwrap();
    for path in [json!([]), json!([[1,2],[1]]), json!(vec![[10, 10]; 513])] {
        assert!(mobile.execute("comment_erase".into(), json!({
            "doc": doc.id, "page": 1, "path": path, "radius": 8.0,
        }).to_string()).is_err());
    }
    assert!(mobile.execute("comment_erase".into(), json!({
        "doc": doc.id, "page": 1, "path": [[5, 5]], "radius": 150.0,
    }).to_string()).is_err());
    assert!(mobile.comments(doc.id).unwrap().is_empty());
    mobile.close(doc.id, true).unwrap();
}
