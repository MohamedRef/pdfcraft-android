//! Android pressure ink: one Ink annotation per drawing with a width per point (`/PCWidths`).

use super::MobileSession;
use serde_json::{Value, json};
use std::sync::atomic::{AtomicU32, Ordering};

/// A blank US Letter page in a file of its own.
fn blank_letter() -> String {
    static N: AtomicU32 = AtomicU32::new(0);
    let bytes = pdfcraft_engine::Session::new().create_blank(612.0, 792.0, 1).unwrap();
    let path = std::env::temp_dir().join(format!("pdfcraft-pressure-ink-{}-{}.pdf", std::process::id(), N.fetch_add(1, Ordering::Relaxed)));
    std::fs::write(&path, bytes.as_slice()).unwrap();
    path.to_string_lossy().into_owned()
}

const Y: usize = 100;

/// A horizontal stroke from x=100 to x=300 whose width grows from 2 to 14 points.
fn pressure_stroke(s: &MobileSession, doc: u64, opacity: f64) -> Result<String, super::MobileError> {
    let points: Vec<Value> = (0..=20).map(|i| json!([100.0 + i as f64 * 10.0, Y as f64])).collect();
    let widths: Vec<f64> = (0..=20).map(|i| 2.0 + i as f64 * 0.6).collect();
    s.execute(
        "comment_add".into(),
        json!({"doc": doc, "page": 1, "type": "ink", "strokes": [points], "widths": [widths], "color": "#0000FF", "opacity": opacity, "width": 4}).to_string(),
    )
}

fn pixel(img: &super::RenderedImage, x: usize, y: usize) -> [u8; 4] {
    let i = (y * img.width as usize + x) * 4;
    img.rgba[i..i + 4].try_into().unwrap()
}

/// Rows painted (not white) in column `x` of a page rendered at scale 1.
fn thickness(img: &super::RenderedImage, x: usize) -> usize {
    (0..img.height as usize).filter(|&y| pixel(img, x, y) != [255, 255, 255, 255]).count()
}

#[test]
fn a_pressure_stroke_is_one_drawing_with_a_growing_width() {
    let s = MobileSession::new();
    let doc = s.open(blank_letter(), None).unwrap().id;
    pressure_stroke(&s, doc, 1.0).unwrap();
    let comments = s.comments(doc).unwrap();
    assert_eq!(comments.len(), 1, "one annotation for the whole stroke");
    assert_eq!(comments[0].subtype, "Ink");
    let img = s.render_page(doc, 0, 1.0, None).unwrap();
    let (thin, thick) = (thickness(&img, 110), thickness(&img, 290));
    assert!(thin >= 2 && thin <= 5, "thin end {thin}");
    assert!(thick >= 12 && thick <= 16, "thick end {thick}");
}

#[test]
fn translucent_pressure_ink_has_no_darker_joints() {
    let s = MobileSession::new();
    let doc = s.open(blank_letter(), None).unwrap().id;
    pressure_stroke(&s, doc, 0.5).unwrap();
    let img = s.render_page(doc, 0, 1.0, None).unwrap();
    // Along the centre line (points every 10 pt, samples between them), one uniform tint.
    let along: Vec<[u8; 4]> = (105..=295).map(|x| pixel(&img, x, Y)).collect();
    let first = along[0];
    assert!(first != [255, 255, 255, 255]);
    for (i, p) in along.iter().enumerate() {
        for c in 0..3 {
            assert!((p[c] as i32 - first[c] as i32).abs() <= 2, "x={} {:?} vs {:?}", 105 + i, p, first);
        }
    }
}

#[test]
fn erasing_and_saving_keep_the_widths() {
    let s = MobileSession::new();
    let doc = s.open(blank_letter(), None).unwrap().id;
    pressure_stroke(&s, doc, 1.0).unwrap();
    s.execute("comment_erase".into(), json!({"doc": doc, "page": 1, "path": [[200.0, Y as f64]], "radius": 6.0}).to_string()).unwrap();
    let comments = s.comments(doc).unwrap();
    assert_eq!(comments.len(), 1, "the cut drawing stays one annotation");
    let img = s.render_page(doc, 0, 1.0, None).unwrap();
    assert_eq!(thickness(&img, 200), 0, "erased in the middle");
    assert!(thickness(&img, 290) >= 12, "the thick end keeps its width");
    assert!(thickness(&img, 110) <= 5, "the thin end keeps its width");
    let out = std::env::temp_dir().join(format!("pdfcraft-pressure-saved-{}.pdf", std::process::id()));
    s.execute("doc_save".into(), json!({"doc": doc, "path": out}).to_string()).unwrap();
    s.close(doc, true).unwrap();
    let again = s.open(out.to_string_lossy().into_owned(), None).unwrap().id;
    let reopened = s.render_page(again, 0, 1.0, None).unwrap();
    assert_eq!((thickness(&reopened, 110), thickness(&reopened, 290)), (thickness(&img, 110), thickness(&img, 290)));
    let _ = std::fs::remove_file(out);
}

#[test]
fn changing_the_line_width_scales_every_point() {
    let s = MobileSession::new();
    let doc = s.open(blank_letter(), None).unwrap().id;
    pressure_stroke(&s, doc, 1.0).unwrap();
    let before = s.render_page(doc, 0, 1.0, None).unwrap();
    // The stored width is the mean (8 points); doubling it doubles each point's width.
    s.execute("comment_edit".into(), json!({"doc": doc, "page": 1, "index": 1, "width": 16}).to_string()).unwrap();
    let after = s.render_page(doc, 0, 1.0, None).unwrap();
    let ratio = thickness(&after, 290) as f64 / thickness(&before, 290) as f64;
    assert!((1.7..=2.3).contains(&ratio), "{ratio}");
    assert!(thickness(&after, 110) < thickness(&after, 290));
}

#[test]
fn widths_that_do_not_match_the_points_are_refused_without_an_edit() {
    let s = MobileSession::new();
    let doc = s.open(blank_letter(), None).unwrap().id;
    let generation = s.document(doc).unwrap().generation;
    for widths in [json!([[1.0, 2.0]]), json!([[1.0, 2.0, 0.0]]), json!([[1.0, 2.0, 3.0], [1.0]]), json!("wide")] {
        let r = s.execute(
            "comment_add".into(),
            json!({"doc": doc, "page": 1, "type": "ink", "strokes": [[[10, 10], [20, 20], [30, 10]]], "widths": widths}).to_string(),
        );
        assert!(r.is_err(), "{widths}");
    }
    assert!(s.execute("comment_add".into(), json!({"doc": doc, "page": 1, "type": "freehand_highlight", "strokes": [[[10, 10], [20, 20]]], "widths": [[1, 2]]}).to_string()).is_err());
    assert_eq!(s.document(doc).unwrap().generation, generation);
    assert!(s.comments(doc).unwrap().is_empty());
    // Plain ink without widths is unchanged.
    s.execute("comment_add".into(), json!({"doc": doc, "page": 1, "type": "ink", "strokes": [[[10, 10], [20, 20]]]}).to_string()).unwrap();
    assert_eq!(s.comments(doc).unwrap().len(), 1);
}
