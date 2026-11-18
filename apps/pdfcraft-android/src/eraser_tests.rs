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
    for path in [json!([]), json!([[1,2],[1]]), json!(vec![[10, 10]; 4097])] {
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

/// Dark pixels (any channel under 128) in an RGBA8 image.
fn dark(rgba: &[u8]) -> usize {
    rgba.chunks_exact(4).filter(|p| p[0] < 128 || p[1] < 128 || p[2] < 128).count()
}

#[test]
fn a_page_can_be_drawn_without_the_ink_being_erased() {
    // the live eraser's view (#521): the page as it is under the ink, with other ink kept
    let fixture = Fixture::new();
    let mobile = MobileSession::new();
    let doc = mobile.open(fixture.0.to_string_lossy().to_string(), None).unwrap();
    for strokes in [json!([[[20, 20], [90, 90]]]), json!([[[120, 160], [180, 220]]])] {
        tool(&mobile, "comment_add", json!({
            "doc": doc.id, "page": 1, "type": "ink", "strokes": strokes,
            "color": "#161616", "opacity": 1.0, "width": 4.0,
        }));
    }
    let comments = mobile.comments(doc.id).unwrap();
    assert_eq!(comments.len(), 2);
    let all = mobile.render_page(doc.id, 0, 1.0, None).unwrap();
    let without_first = mobile.render_page_hiding(doc.id, 0, 1.0, None, vec![comments[0].index]).unwrap();
    let without_both = mobile.render_page_hiding(doc.id, 0, 1.0, None, vec![comments[0].index, comments[1].index]).unwrap();
    assert!(!without_first.rgb565 && !without_both.rgb565);
    assert_eq!((without_first.width, without_first.height), (all.width, all.height));
    let (inked, one, none) = (dark(&all.rgba), dark(&without_first.pixels), dark(&without_both.pixels));
    assert!(inked > one && one > none, "each hidden stroke leaves the page: {inked} > {one} > {none}");
    assert_eq!(none, 0, "a blank page with its ink hidden has nothing dark");
    // nothing is hidden for good: the next ordinary render draws the ink again
    assert_eq!(dark(&mobile.render_page(doc.id, 0, 1.0, None).unwrap().rgba), inked);
}

/// #572: the Draw eraser's exact arguments (Android sends `erase_highlights` and whole gestures)
/// are accepted, also on pressure ink saved with a width per point.
#[test]
fn the_draw_erasers_own_arguments_are_accepted() {
    let fixture = Fixture::new();
    let mobile = MobileSession::new();
    let doc = mobile.open(fixture.0.to_string_lossy().to_string(), None).unwrap();
    tool(&mobile, "comment_add", json!({
        "doc": doc.id, "page": 1, "type": "ink", "strokes": [[[20, 20], [60, 60], [90, 90]]], "widths": [[1.0, 3.0, 2.0]],
        "color": "#161616", "opacity": 1.0, "width": 2.0,
    }));
    let long: Vec<[f64; 2]> = (0..1617).map(|i| [10.0 + (i % 100) as f64, 10.0 + (i / 20) as f64]).collect();
    for path in [json!([[20, 20]]), json!(long)] {
        let out = mobile.execute("comment_erase".into(), json!({
            "doc": doc.id, "page": 1, "path": path, "radius": 11.81, "erase_highlights": true,
        }).to_string());
        assert!(out.is_ok(), "{out:?}");
    }
    mobile.close(doc.id, true).unwrap();
}

#[test]
fn the_whole_stroke_eraser_removes_a_touched_line_and_is_one_undo_step() {
    // #521: "Erase whole strokes" takes every line the eraser touches, all of it.
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
    let done = tool(&mobile, "comment_erase", json!({
        "doc": doc.id, "page": 1, "path": [[20, 20]], "radius": 3.0, "whole": true,
    }));
    assert_eq!(done["candidates"], 1);
    let after = mobile.comments(doc.id).unwrap();
    assert_eq!(after.len(), 1, "the touched line is gone whole");
    assert_eq!(after[0].rect, before[1].rect, "the other line stays");
    tool(&mobile, "edit_undo", json!({ "doc": doc.id }));
    assert_eq!(mobile.comments(doc.id).unwrap().len(), 2);
    let bad = mobile.execute("comment_erase".into(), json!({ "doc": doc.id, "page": 1, "path": [[20, 20]], "whole": "yes" }).to_string());
    assert!(bad.is_err() || bad.unwrap().contains("error"));
}

#[test]
fn the_whole_stroke_erasers_strokes_are_read_as_the_edit_measures_them() {
    // #655: Draw hides each stroke the whole-stroke eraser touches while it is down, by the
    // edit's own measure: each stroke's points (view space) and half its width at each point
    let fixture = Fixture::new();
    let mobile = MobileSession::new();
    let doc = mobile.open(fixture.0.to_string_lossy().to_string(), None).unwrap();
    // pressure ink, a width per point
    tool(&mobile, "comment_add", json!({
        "doc": doc.id, "page": 1, "type": "ink", "strokes": [[[20, 20], [60, 60], [90, 90]]], "widths": [[1.0, 3.0, 2.0]],
        "color": "#161616", "opacity": 1.0, "width": 2.0,
    }));
    // one drawing of two strokes, 4 pt wide
    tool(&mobile, "comment_add", json!({
        "doc": doc.id, "page": 1, "type": "ink", "strokes": [[[150, 160], [180, 190]], [[150, 250], [190, 250]]],
        "color": "#161616", "opacity": 1.0, "width": 4.0,
    }));
    let close = |a: &[f32], b: &[f32]| a.len() == b.len() && a.iter().zip(b).all(|(x, y)| (x - y).abs() < 1e-3);
    let strokes = mobile.erasable_strokes(doc.id, 0).unwrap();
    assert_eq!(strokes.iter().map(|s| (s.index, s.stroke)).collect::<Vec<_>>(), vec![(0, 0), (1, 0), (1, 1)]);
    assert!(close(&strokes[0].points, &[20.0, 20.0, 60.0, 60.0, 90.0, 90.0]), "{:?}", strokes[0].points);
    assert!(close(&strokes[0].half_widths, &[0.5, 1.5, 1.0]), "{:?}", strokes[0].half_widths);
    assert!(close(&strokes[2].points, &[150.0, 250.0, 190.0, 250.0]), "{:?}", strokes[2].points);
    assert!(close(&strokes[2].half_widths, &[2.0, 2.0]), "{:?}", strokes[2].half_widths);
    // 5 pt from the lower stroke's centre line: the radius (3) plus its half width (2) reach it
    tool(&mobile, "comment_erase", json!({
        "doc": doc.id, "page": 1, "path": [[170, 245.1]], "radius": 3.0, "whole": true, "erase_highlights": true,
    }));
    let left = mobile.erasable_strokes(doc.id, 0).unwrap();
    assert_eq!(left.len(), 2, "only the touched stroke went");
    assert!(close(&left[1].points, &[150.0, 160.0, 180.0, 190.0]), "{:?}", left[1].points);
    assert!(mobile.erasable_strokes(doc.id, 7).unwrap().is_empty(), "no such page");
    mobile.close(doc.id, true).unwrap();
}
