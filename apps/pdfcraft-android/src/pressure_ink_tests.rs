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

/// The Android lasso edits several saved comments as one undo step.
#[test]
fn lasso_edits_many_comments_as_one_undo_step() {
    let s = MobileSession::new();
    let doc = s.open(blank_letter(), None).unwrap().id;
    for x in [100.0, 200.0, 300.0] {
        s.execute("comment_add".into(), json!({"doc": doc, "page": 1, "type": "ink", "strokes": [[[x, 100.0], [x + 20.0, 120.0]]]}).to_string()).unwrap();
    }
    let rects = |s: &MobileSession| s.comments(doc).unwrap().iter().map(|c| c.rect[0]).collect::<Vec<_>>();
    let before = rects(&s);
    let r: Value = serde_json::from_str(&s.execute("comments_edit_many".into(), json!({"doc": doc, "page": 1, "comments": [1, 3], "move": [10, 0]}).to_string()).unwrap()).unwrap();
    assert_eq!(r["changed"], 2);
    let moved = rects(&s);
    assert!((moved[0] - before[0] - 10.0).abs() < 0.01 && (moved[1] - before[1]).abs() < 0.01 && (moved[2] - before[2] - 10.0).abs() < 0.01, "{before:?} {moved:?}");
    s.execute("edit_undo".into(), json!({"doc": doc}).to_string()).unwrap();
    assert_eq!(rects(&s), before, "one undo restores every moved comment");
    s.execute("comments_edit_many".into(), json!({"doc": doc, "page": 1, "comments": [3, 1], "delete": true}).to_string()).unwrap();
    let left = s.comments(doc).unwrap();
    assert_eq!(left.len(), 1);
    assert!((left[0].rect[0] - before[1]).abs() < 0.01, "the middle stroke stays");
    s.execute("edit_undo".into(), json!({"doc": doc}).to_string()).unwrap();
    assert_eq!(s.comments(doc).unwrap().len(), 3);
    let generation = s.document(doc).unwrap().generation;
    for bad in [json!({"comments": [1], "move": [1, 1], "delete": true}), json!({"comments": [9], "delete": true}), json!({"comments": [], "delete": true}), json!({"comments": [1]})] {
        let mut args = bad.as_object().unwrap().clone();
        args.insert("doc".into(), json!(doc));
        args.insert("page".into(), json!(1));
        assert!(s.execute("comments_edit_many".into(), Value::Object(args).to_string()).is_err(), "{bad}");
    }
    assert_eq!(s.document(doc).unwrap().generation, generation);
}

/// A pencil stroke (#569) along y = [`Y`] from x=100 to x=300, 10 pt wide, at one ink `density`.
fn pencil_stroke(s: &MobileSession, doc: u64, density: f64) -> Result<String, super::MobileError> {
    let points: Vec<Value> = (0..=20).map(|i| json!([100.0 + i as f64 * 10.0, Y as f64])).collect();
    s.execute(
        "comment_add".into(),
        json!({"doc": doc, "page": 1, "type": "ink", "strokes": [points], "widths": [vec![10.0; 21]], "densities": [vec![density; 21]], "color": "#000000", "width": 10}).to_string(),
    )
}

/// The share of the pixels in the stroke's middle (x 150…250, 3 pt either side of the line) that
/// are inked, rendered at `scale`.
fn inked_share(img: &super::RenderedImage, scale: f64) -> f64 {
    let (x0, x1) = ((150.0 * scale) as usize, (250.0 * scale) as usize);
    let (y0, y1) = (((Y as f64 - 3.0) * scale) as usize, ((Y as f64 + 3.0) * scale) as usize);
    let mut inked = 0usize;
    let mut all = 0usize;
    for y in y0..y1 {
        for x in x0..x1 {
            all += 1;
            if pixel(img, x, y)[0] < 128 {
                inked += 1;
            }
        }
    }
    inked as f64 / all as f64
}

#[test]
fn a_pencil_shows_the_paper_through_its_grain() {
    let s = MobileSession::new();
    for (density, lo, hi) in [(0.5, 0.35, 0.65), (0.25, 0.12, 0.4), (1.0, 0.98, 1.0)] {
        let doc = s.open(blank_letter(), None).unwrap().id;
        pencil_stroke(&s, doc, density).unwrap();
        // a large render is drawn at a lower scale (the engine's pixel limit): measure at the real one
        let img = s.render_page(doc, 0, 6.0, None).unwrap();
        let share = inked_share(&img, img.width as f64 / 612.0);
        assert!(share >= lo && share <= hi, "density {density}: {share:.2} of the stroke inked");
    }
}

#[test]
fn a_pencils_densities_survive_the_eraser_and_a_bad_list_is_refused() {
    let s = MobileSession::new();
    let doc = s.open(blank_letter(), None).unwrap().id;
    pencil_stroke(&s, doc, 0.5).unwrap();
    s.execute("comment_erase".into(), json!({"doc": doc, "page": 1, "path": [[200.0, Y as f64]], "radius": 6.0}).to_string()).unwrap();
    let img = s.render_page(doc, 0, 6.0, None).unwrap();
    let k = img.width as f64 / 612.0;
    // still grainy either side of the cut
    let (x0, x1) = ((120.0 * k) as usize, (180.0 * k) as usize);
    let row = (Y as f64 * k) as usize;
    let inked = (x0..x1).filter(|&x| pixel(&img, x, row)[0] < 128).count() as f64 / (x1 - x0) as f64;
    assert!(inked > 0.25 && inked < 0.75, "{inked:.2}");
    // one density per point, from 0 to 1
    for bad in [json!([[0.5, 0.5]]), json!([[0.5, 0.5, 1.5]]), json!([[0.5, -0.1, 0.5]])] {
        let r = s.execute(
            "comment_add".into(),
            json!({"doc": doc, "page": 1, "type": "ink", "strokes": [[[10, 10], [20, 20], [30, 10]]], "widths": [[1, 2, 3]], "densities": bad}).to_string(),
        );
        assert!(r.is_err(), "{bad} was accepted");
    }
}

