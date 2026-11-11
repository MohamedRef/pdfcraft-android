use super::*;
use std::sync::atomic::AtomicUsize;

fn file(bytes: &[u8]) -> String {
    static NEXT: AtomicUsize = AtomicUsize::new(0);
    let dir = std::env::temp_dir().join(format!("pdfcraft-progressive-{}-{}", std::process::id(), NEXT.fetch_add(1, Ordering::Relaxed)));
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join("reading.pdf");
    std::fs::write(&path, bytes).unwrap();
    path.to_string_lossy().into_owned()
}

fn fixture(catalog_extra: &str, first: &str, last: &str, extra: &[&str]) -> Vec<u8> {
    let drawing = "0 0 1 rg 10 10 100 50 re f";
    let stream = format!("<< /Length {} >>\nstream\n{drawing}\nendstream", drawing.len());
    let mut objects = vec![
        format!("<< /Type /Catalog /Pages 2 0 R {catalog_extra} >>"),
        "<< /Type /Pages /Kids [3 0 R 5 0 R] /Count 2 /MediaBox [0 0 200 300] >>".into(),
        format!("<< /Type /Page /Parent 2 0 R /Contents 4 0 R {first} >>"),
        stream.clone(),
        format!("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Rotate 90 /Contents 6 0 R {last} >>"),
        stream,
    ];
    objects.extend(extra.iter().map(|s| s.to_string()));
    let mut bytes = b"%PDF-1.7\n".to_vec();
    let mut offsets = Vec::new();
    for (index, object) in objects.iter().enumerate() {
        offsets.push(bytes.len());
        bytes.extend_from_slice(format!("{} 0 obj\n{object}\nendobj\n", index + 1).as_bytes());
    }
    let xref = bytes.len();
    bytes.extend_from_slice(format!("xref\n0 {}\n0000000000 65535 f \n", objects.len() + 1).as_bytes());
    for offset in offsets {
        bytes.extend_from_slice(format!("{offset:010} 00000 n \n").as_bytes());
    }
    bytes.extend_from_slice(format!("trailer\n<< /Size {} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n", objects.len() + 1).as_bytes());
    bytes
}

#[test]
fn progressive_reading_pixels_geometry_and_final_summary_match_complete_open() {
    let bytes = fixture("", "", "", &[]);
    let path = file(&bytes);
    let session = MobileSession::new();
    let first = session.begin_open(path.clone(), None, 0, true).unwrap();
    let id = first.document.id;
    assert!(first.document.preparing);
    assert!(!first.document.allows_copying && !first.document.allows_printing && !first.document.allows_annotation);
    assert!(first.document.read_only_reason.is_some());
    assert!(first.timings.iter().all(|s| !matches!(s.stage.as_str(), "native_inspect_lazy" | "native_signatures" | "native_display_form_discovery")));
    let pending = session.pending_opens.lock().unwrap();
    assert!(session.with_doc(id, |d| Arc::ptr_eq(&d.bytes, &pending[&id].bytes)).unwrap());
    drop(pending);
    let preview = session.render_page(id, 0, 1.0, None).unwrap();
    for tool in ["doc_save", "comment_add", "page_export"] {
        assert!(session.execute(tool.into(), json!({"doc": id}).to_string()).is_err());
    }
    let completed = session.complete_open(id).unwrap();
    assert!(!completed.document.preparing && completed.document.read_only_reason.is_none());
    assert_eq!(completed.document.id, id);
    assert_eq!(session.documents(), vec![id]);
    assert_eq!(completed.document.generation, first.document.generation + 1);
    assert_eq!(session.render_page(id, 0, 1.0, None).unwrap().rgba, preview.rgba);
    let ordinary = MobileSession::new();
    let expected = ordinary.open(path.clone(), None).unwrap();
    for ((early, ready), plain) in first.document.pages.iter().zip(&completed.document.pages).zip(&expected.pages) {
        assert_eq!((early.width, early.height, early.rotation), (ready.width, ready.height, ready.rotation));
        assert_eq!((ready.width, ready.height, ready.rotation), (plain.width, plain.height, plain.rotation));
    }
    assert_eq!(completed.document.fields, expected.fields);
    assert_eq!(completed.document.signed, expected.signed);
    session.execute("doc_save".into(), json!({"doc": id}).to_string()).unwrap();
    assert_eq!(std::fs::read(path).unwrap(), bytes);
    assert!(session.complete_open(id).is_err());
}

#[test]
fn progressive_missing_comment_appearance_on_initial_page_uses_full_open() {
    let bytes = fixture("", "/Annots [7 0 R]", "", &["<< /Type /Annot /Subtype /Square /Rect [10 10 90 90] /C [1 0 0] >>"]);
    let path = file(&bytes);
    let session = MobileSession::new();
    let opened = session.begin_open(path.clone(), None, 0, true).unwrap();
    assert!(!opened.document.preparing);
    assert!(session.pending_opens.lock().unwrap().is_empty());
    let ordinary = MobileSession::new();
    let expected = ordinary.open(path.clone(), None).unwrap();
    assert_eq!(session.render_page(opened.document.id, 0, 1.0, None).unwrap().rgba, ordinary.render_page(expected.id, 0, 1.0, None).unwrap().rgba);
    assert_eq!(std::fs::read(path).unwrap(), bytes);
}

#[test]
fn progressive_orphan_widget_is_not_hidden_or_mistaken_for_an_absent_form() {
    let widget = "<< /Type /Annot /Subtype /Widget /FT /Tx /T (orphan) /V (kept) /Rect [10 10 90 35] /P 5 0 R >>";
    let path = file(&fixture("", "", "/Annots [7 0 R]", &[widget]));
    let session = MobileSession::new();
    let reading = session.begin_open(path.clone(), None, 0, false).unwrap();
    assert!(reading.document.preparing);
    assert!(session.render_page(reading.document.id, 1, 1.0, None).is_err());
    let full = session.complete_open(reading.document.id).unwrap();
    assert_eq!(full.document.fields, 1);
    assert_eq!(session.widgets(full.document.id).unwrap()[0].value.as_deref(), Some("kept"));
    assert!(session.render_page(full.document.id, 1, 1.0, None).is_ok());
    let restored = MobileSession::new().begin_open(path, None, 1, false).unwrap();
    assert!(!restored.document.preparing, "restored initial pages get the same appearance gate");
}

#[test]
fn progressive_standalone_timestamp_without_acroform_is_discovered_on_completion() {
    let stamp = "<< /Type /Sig /SubFilter /ETSI.RFC3161 /ByteRange [0 1 2 1] /Contents <> >>";
    let bytes = fixture("", "", "", &[stamp]);
    let path = file(&bytes);
    let session = MobileSession::new();
    let reading = session.begin_open(path.clone(), None, 0, false).unwrap();
    assert!(reading.document.preparing);
    assert!(session.execute("doc_save".into(), json!({"doc": reading.document.id}).to_string()).is_err());
    let ready = session.complete_open(reading.document.id).unwrap();
    assert!(ready.document.signed);
    assert_eq!(session.with_doc(ready.document.id, |d| d.signatures.len()).unwrap(), 1);
    assert_eq!(std::fs::read(path).unwrap(), bytes);
}

#[test]
fn progressive_close_cancels_pending_work_and_late_completion_cannot_reopen_it() {
    let path = file(&fixture("", "", "", &[]));
    let session = MobileSession::new();
    let reading = session.begin_open(path.clone(), None, 0, false).unwrap();
    let cancelled = session.pending_opens.lock().unwrap()[&reading.document.id].cancelled.clone();
    session.close(reading.document.id, false).unwrap();
    assert!(cancelled.load(Ordering::Acquire));
    assert!(session.complete_open(reading.document.id).is_err());
    assert!(session.documents().is_empty());
    assert!(session.pending_opens.lock().unwrap().is_empty());
}

#[test]
fn progressive_completion_uses_the_original_shared_bytes_without_rereading_the_path() {
    let path = file(&fixture("", "", "", &[]));
    let session = MobileSession::new();
    let reading = session.begin_open(path.clone(), None, 0, false).unwrap();
    std::fs::remove_file(path).unwrap();
    let ready = session.complete_open(reading.document.id).unwrap();
    assert!(!ready.document.preparing);
    assert!(session.render_page(ready.document.id, 0, 1.0, None).is_ok());
}

#[test]
fn progressive_invalid_and_relative_inputs_do_not_leave_pending_documents() {
    let session = MobileSession::new();
    assert!(session.begin_open("relative.pdf".into(), None, 0, false).is_err());
    assert!(session.begin_open(file(b"not a PDF"), None, 0, false).is_err());
    assert!(session.documents().is_empty());
    assert!(session.pending_opens.lock().unwrap().is_empty());
}

#[test]
fn progressive_tree_listed_widget_without_page_annotation_uses_complete_open() {
    let bytes = fixture(
        "/AcroForm 7 0 R",
        "",
        "",
        &["<< /Fields [8 0 R] >>", "<< /Type /Annot /Subtype /Widget /FT /Tx /T (tree) /V (kept) /Rect [10 10 90 35] /P 3 0 R >>"],
    );
    let session = MobileSession::new();
    let opened = session.begin_open(file(&bytes), None, 0, false).unwrap();
    assert!(!opened.document.preparing);
    assert_eq!(opened.document.fields, 1);
}

#[test]
fn progressive_late_renderers_do_not_revive_closed_or_superseded_cache_entries() {
    let session = MobileSession::new();
    let opened = session.begin_open(file(&fixture("", "", "", &[])), None, 0, false).unwrap();
    let bytes = session.with_doc(opened.document.id, |d| d.display.clone()).unwrap();
    let late = session.take_renderer(opened.document.id, &bytes, RenderConfig::default());
    session.close(opened.document.id, true).unwrap();
    session.return_renderer(opened.document.id, &bytes, late);
    assert!(!session.cache().contains_key(&opened.document.id));
    let widget = "<< /Type /Annot /Subtype /Widget /FT /Tx /T (orphan) /V (kept) /Rect [10 10 90 35] /P 5 0 R >>";
    let opened = session.begin_open(file(&fixture("", "", "/Annots [7 0 R]", &[widget])), None, 0, false).unwrap();
    let bytes = session.with_doc(opened.document.id, |d| d.display.clone()).unwrap();
    let late = session.take_renderer(opened.document.id, &bytes, RenderConfig::default());
    session.complete_open(opened.document.id).unwrap();
    assert!(!session.with_doc(opened.document.id, |d| Arc::ptr_eq(&d.display, &bytes)).unwrap());
    session.return_renderer(opened.document.id, &bytes, late);
    assert!(!session.cache().contains_key(&opened.document.id));
}

#[test]
fn progressive_explicit_null_form_entries_do_not_force_complete_open() {
    for (root, objects) in [
        ("/AcroForm null", vec![]),
        ("/AcroForm 7 0 R", vec!["null"]),
        ("/AcroForm 7 0 R", vec!["<< /Fields null >>"]),
        ("/AcroForm 7 0 R", vec!["<< /Fields 8 0 R >>", "null"]),
    ] {
        let bytes = fixture(root, "", "", &objects);
        let path = file(&bytes);
        let session = MobileSession::new();
        let reading = session.begin_open(path.clone(), None, 0, true).unwrap();
        assert!(reading.document.preparing, "{root} {objects:?}");
        assert!(!reading.timings.iter().any(|s| s.stage.starts_with("native_reading_fallback_")));
        let early = session.render_page(reading.document.id, 0, 1.0, None).unwrap();
        let ready = session.complete_open(reading.document.id).unwrap();
        assert_eq!(session.render_page(ready.document.id, 0, 1.0, None).unwrap().rgba, early.rgba);
        assert_eq!(std::fs::read(path).unwrap(), bytes);
    }
}

#[test]
fn progressive_located_tree_widget_blocks_its_page_without_blocking_unaffected_pages() {
    let bytes = fixture(
        "/AcroForm 7 0 R",
        "",
        "",
        &["<< /Fields [8 0 R] >>", "<< /Type /Annot /Subtype /Widget /FT /Tx /T (offpage) /V (kept) /Rect [10 10 90 35] /P 5 0 R >>"],
    );
    let path = file(&bytes);
    let session = MobileSession::new();
    let reading = session.begin_open(path.clone(), None, 0, true).unwrap();
    assert!(reading.document.preparing);
    let early = session.render_page(reading.document.id, 0, 1.0, None).unwrap();
    assert!(session.render_page(reading.document.id, 1, 1.0, None).is_err());
    let ready = session.complete_open(reading.document.id).unwrap();
    assert_eq!(ready.document.fields, 1);
    assert_eq!(session.with_doc(ready.document.id, |d| d.form[0].value.first().cloned()).unwrap().as_deref(), Some("kept"));
    // The complete engine locates widgets through page annotations; preserve its behavior
    // for this tree-only fixture while conservatively blocking its declared preview page.
    let ordinary = MobileSession::new();
    let expected = ordinary.open(path.clone(), None).unwrap();
    let widgets = |s: &MobileSession, id| s.widgets(id).unwrap().into_iter().map(|w| (w.page, w.field, w.rect, w.value)).collect::<Vec<_>>();
    assert_eq!(widgets(&session, ready.document.id), widgets(&ordinary, expected.id));
    assert_eq!(session.render_page(ready.document.id, 0, 1.0, None).unwrap().rgba, early.rgba);
    let restored = MobileSession::new().begin_open(path.clone(), None, 1, true).unwrap();
    assert!(!restored.document.preparing);
    assert!(restored.timings.iter().any(|s| s.stage == "native_reading_fallback_initial_page_form"));
    assert_eq!(std::fs::read(path).unwrap(), bytes);
}

#[test]
fn progressive_unknown_widget_locations_and_invalid_trees_have_fixed_fallback_reasons() {
    for (objects, reason) in [
        (vec!["17"], "native_reading_fallback_form_dictionary"),
        (vec!["<< /Fields [8 0 R] >>", "<< /Subtype /Widget /FT /Tx /T (unknown) /Rect [10 10 90 35] >>"], "native_reading_fallback_widget_location"),
        (vec!["<< /Fields [8 0 R] >>", "<< /FT /Tx /T (cycle) /Kids [8 0 R] >>"], "native_reading_fallback_field_tree"),
        (vec!["<< /Fields [8 0 R] >>", "<< /Subtype /Widget /FT /Tx /T (tiny) /Rect [0 0 0.25 0.25] >>"], "native_reading_fallback_widget_location"),
    ] {
        let session = MobileSession::new();
        let opened = session.begin_open(file(&fixture("/AcroForm 7 0 R", "", "", &objects)), None, 0, true).unwrap();
        assert!(!opened.document.preparing);
        let reasons: Vec<_> = opened.timings.iter().filter(|s| s.stage.starts_with("native_reading_fallback_")).collect();
        assert_eq!(reasons.len(), 1);
        assert_eq!(reasons[0].stage, reason);
        assert_eq!(reasons[0].duration_ms, 0);
    }
}

#[test]
fn progressive_large_field_trees_have_a_bounded_diagnostic_fallback() {
    let form = format!("<< /Fields [{}] >>", "null ".repeat(513));
    let session = MobileSession::new();
    let opened = session.begin_open(file(&fixture("/AcroForm 7 0 R", "", "", &[&form])), None, 0, true).unwrap();
    assert!(!opened.document.preparing);
    assert!(opened.timings.iter().any(|s| s.stage == "native_reading_fallback_field_budget"));
}

#[test]
fn progressive_invisible_signature_widgets_stay_pending_and_are_validated_on_completion() {
    for geometry in ["/Rect [0 0 0 0]", "/Rect [10 10 90 35] /F 2", "/Rect [10 10 90 35] /F 32"] {
        let widget = format!("<< /Subtype /Widget /FT /Sig /T (invisible) /V 9 0 R {geometry} >>");
        let bytes = fixture(
            "/AcroForm 7 0 R",
            "",
            "",
            &["<< /Fields [8 0 R] >>", &widget, "<< /Type /Sig /ByteRange [0 1 2 1] /Contents <> /SubFilter /adbe.pkcs7.detached >>"],
        );
        let path = file(&bytes);
        let session = MobileSession::new();
        let reading = session.begin_open(path.clone(), None, 0, true).unwrap();
        assert!(reading.document.preparing, "{geometry}");
        assert!(!reading.document.allows_copying);
        assert!(session.execute("doc_save".into(), json!({"doc":reading.document.id}).to_string()).is_err());
        let ready = session.complete_open(reading.document.id).unwrap();
        assert!(!ready.document.preparing && ready.document.signed);
        assert!(!session.with_doc(ready.document.id, |d| d.signatures[0].visible).unwrap());
        assert_eq!(std::fs::read(path).unwrap(), bytes);
    }
}
