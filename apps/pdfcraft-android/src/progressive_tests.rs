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

/// #212: a page with saved handwriting and highlights (comments that carry their own normal
/// appearance) opens in the reading stage, and draws exactly as the complete document does.
#[test]
fn progressive_comments_with_their_own_appearance_draw_early_and_identically() {
    let ink = "q 0 0 1 RG 3 w 1 J 1 j 10 10 m 60 80 l 110 20 l S Q";
    let mark = "q 1 1 0 rg 0 0 120 14 re f Q";
    let bytes = fixture(
        "",
        "/Annots [7 0 R 9 0 R 11 0 R 12 0 R]",
        "",
        &[
            "<< /Type /Annot /Subtype /Ink /Rect [10 10 120 90] /InkList [[10 10 60 80 110 20]] /C [0 0 1] /AP << /N 8 0 R >> >>",
            &format!("<< /Type /XObject /Subtype /Form /BBox [10 10 120 90] /Length {} >>\nstream\n{ink}\nendstream", ink.len()),
            "<< /Type /Annot /Subtype /Highlight /Rect [20 100 140 114] /QuadPoints [20 114 140 114 20 100 140 100] /C [1 1 0] /AP << /N 10 0 R >> >>",
            &format!("<< /Type /XObject /Subtype /Form /BBox [0 0 120 14] /Matrix [1 0 0 1 20 100] /Length {} >>\nstream\n{mark}\nendstream", mark.len()),
            "<< /Type /Annot /Subtype /Link /Rect [10 150 90 170] /Dest [5 0 R /Fit] >>",
            "<< /Type /Annot /Subtype /Popup /Rect [130 150 190 190] /Parent 9 0 R >>",
        ],
    );
    let path = file(&bytes);
    let session = MobileSession::new();
    let first = session.begin_open(path.clone(), None, 0, true).unwrap();
    assert!(first.document.preparing, "{:?}", first.timings.iter().map(|s| &s.stage).collect::<Vec<_>>());
    assert!(first.timings.iter().all(|s| !s.stage.starts_with("native_reading_fallback_")));
    let early = session.render_page(first.document.id, 0, 1.0, None).unwrap();
    let ready = session.complete_open(first.document.id).unwrap();
    assert!(!ready.document.preparing);
    assert_eq!(session.render_page(ready.document.id, 0, 1.0, None).unwrap().rgba, early.rgba);
    let ordinary = MobileSession::new();
    let plain = ordinary.open(path.clone(), None).unwrap();
    assert_eq!(ordinary.render_page(plain.id, 0, 1.0, None).unwrap().rgba, early.rgba);
    assert_eq!(std::fs::read(path).unwrap(), bytes);
}

/// #212: the parse the reading stage made for its page sizes waits as the first renderer, and
/// draws the same pixels as a renderer that parses the file itself.
#[test]
fn progressive_first_render_starts_from_the_geometry_parse() {
    let path = file(&fixture("", "", "", &[]));
    let session = MobileSession::new();
    let first = session.begin_open(path.clone(), None, 1, true).unwrap();
    assert!(first.document.preparing);
    let id = first.document.id;
    assert_eq!(session.cache().get(&id).map(|(_, _, idle)| idle.len()), Some(1));
    assert!(first.timings.iter().any(|s| s.stage == "native_reading_pages"));
    let early = session.render_page(id, 1, 1.0, None).unwrap();
    let ordinary = MobileSession::new();
    let plain = ordinary.open(path, None).unwrap();
    assert_eq!(ordinary.render_page(plain.id, 1, 1.0, None).unwrap().rgba, early.rgba);
    // completion keeps it while the document still draws the same bytes
    session.complete_open(id).unwrap();
    assert!(session.with_doc(id, |d| pdfcraft_cos::same_storage(&d.display, &d.bytes)).unwrap());
    assert_eq!(session.cache().get(&id).map(|(_, _, idle)| idle.len()), Some(1));
    // a fallback to the complete open keeps no reading parse
    let fallback = MobileSession::new();
    let full = fallback
        .begin_open(file(&fixture("", "/Annots [7 0 R]", "", &["<< /Type /Annot /Subtype /Text /Rect [10 10 25 25] >>"])), None, 0, false)
        .unwrap();
    assert!(!full.document.preparing);
    assert!(fallback.cache().get(&full.document.id).is_none_or(|(_, _, idle)| idle.is_empty()));
}

/// Sticky notes (painted by the overlay from the complete comment list) and widgets keep their
/// page on the complete path, with a reason the open timings show.
#[test]
fn progressive_notes_widgets_and_missing_appearances_name_their_fallback() {
    for (annotation, reason) in [
        ("<< /Type /Annot /Subtype /Text /Rect [10 10 25 25] /Contents (note) >>", "native_reading_fallback_annotation_note"),
        ("<< /Type /Annot /Subtype /Widget /FT /Btn /Rect [10 10 25 25] /AP << /N 8 0 R >> >>", "native_reading_fallback_annotation_widget"),
        ("<< /Type /Annot /Subtype /Square /Rect [10 10 90 90] /AP << /D 8 0 R >> >>", "native_reading_fallback_annotation_appearance"),
        ("<< /Type /Annot /Rect [10 10 90 90] >>", "native_reading_fallback_annotations"),
    ] {
        let stream = "<< /Type /XObject /Subtype /Form /BBox [0 0 1 1] /Length 0 >>\nstream\n\nendstream";
        let session = MobileSession::new();
        let opened = session.begin_open(file(&fixture("", "/Annots [7 0 R]", "", &[annotation, stream])), None, 0, true).unwrap();
        assert!(!opened.document.preparing, "{annotation}");
        let reasons: Vec<_> = opened.timings.iter().filter(|s| s.stage.starts_with("native_reading_fallback_")).map(|s| s.stage.as_str()).collect();
        assert_eq!(reasons, vec![reason], "{annotation}");
    }
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
    session.return_renderer(opened.document.id, &bytes, &RenderConfig::default(), late);
    assert!(!session.cache().contains_key(&opened.document.id));
    let widget = "<< /Type /Annot /Subtype /Widget /FT /Tx /T (orphan) /V (kept) /Rect [10 10 90 35] /P 5 0 R >>";
    let opened = session.begin_open(file(&fixture("", "", "/Annots [7 0 R]", &[widget])), None, 0, false).unwrap();
    let bytes = session.with_doc(opened.document.id, |d| d.display.clone()).unwrap();
    let late = session.take_renderer(opened.document.id, &bytes, RenderConfig::default());
    session.complete_open(opened.document.id).unwrap();
    assert!(!session.with_doc(opened.document.id, |d| Arc::ptr_eq(&d.display, &bytes)).unwrap());
    session.return_renderer(opened.document.id, &bytes, &RenderConfig::default(), late);
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

#[test]
fn visible_overlays_match_complete_lists_and_reject_pending_closed_and_unbounded_requests() {
    let bytes = fixture(
        "",
        "/Annots [7 0 R]",
        "/Annots [8 0 R 9 0 R]",
        &[
            "<< /Type /Annot /Subtype /Text /Rect [10 10 25 25] /NM (root) /Contents (first) /CA 0.6 >>",
            "<< /Type /Annot /Subtype /Text /Rect [30 30 45 45] /IRT 7 0 R /Contents (reply) >>",
            "<< /Type /Annot /Subtype /Widget /FT /Tx /T (value) /V (kept) /P 5 0 R /Rect [20 20 90 45] >>",
        ],
    );
    let path = file(&bytes);
    let session = MobileSession::new();
    let info = session.open(path.clone(), None).unwrap();
    let all = session.comments(info.id).unwrap();
    let first = session.page_overlays(info.id, vec![0, 0]).unwrap();
    assert_eq!(first.pages, vec![0]);
    assert_eq!(first.generation, info.generation);
    assert_eq!(first.comments.len(), 1);
    assert!(first.widgets.is_empty());
    let second = session.page_overlays(info.id, vec![1]).unwrap();
    assert_eq!(second.comments.len(), 1);
    assert_eq!(second.widgets.len(), session.widgets(info.id).unwrap().len());
    let mut combined = first.comments;
    combined.extend(second.comments);
    for (actual, expected) in combined.iter().zip(all.iter()) {
        assert_eq!(
            (actual.page, actual.index, &actual.contents, actual.opacity, &actual.reply_to),
            (expected.page, expected.index, &expected.contents, expected.opacity, &expected.reply_to)
        );
    }
    assert!(session.page_overlays(info.id, vec![2]).is_err());
    assert!(session.page_overlays(info.id, vec![0; 17]).is_err());
    let snapshot = session.overlay_snapshot(info.id, Some(&[0])).unwrap();
    let before = snapshot.comment_properties();
    session.close(info.id, true).unwrap();
    assert!(session.page_overlays(info.id, vec![0]).is_err());
    assert_eq!(snapshot.comment_properties(), before, "snapshot outlives its session entry");
    assert_eq!(std::fs::read(path).unwrap(), bytes);
    let pending = session.begin_open(file(&fixture("", "", "", &[])), None, 0, false).unwrap();
    assert!(session.page_overlays(pending.document.id, vec![0]).is_err());
}

#[test]
fn compact_rasters_reduce_transfer_while_tiles_and_opt_out_keep_exact_rgba() {
    let session = MobileSession::new();
    let doc = session.open(file(&fixture("", "", "", &[])), None).unwrap();
    let original = session.render_page(doc.id, 0, 1.0, None).unwrap();
    let compact = session.render_page_compact(doc.id, 0, 1.0, None, true).unwrap();
    assert!(compact.rgb565);
    assert_eq!((compact.width, compact.height), (original.width, original.height));
    assert_eq!(compact.pixels.len() * 2, original.rgba.len());
    for (packed, rgba) in compact.pixels.chunks_exact(2).zip(original.rgba.chunks_exact(4)) {
        let pixel = u16::from_ne_bytes([packed[0], packed[1]]);
        assert_eq!((pixel >> 11) as u8, rgba[0] >> 3);
        assert_eq!(((pixel >> 5) & 63) as u8, rgba[1] >> 2);
        assert_eq!((pixel & 31) as u8, rgba[2] >> 3);
    }
    let exact = session.render_page_compact(doc.id, 0, 1.0, None, false).unwrap();
    assert!(!exact.rgb565);
    assert_eq!(exact.pixels, original.rgba);
    let tile = Some(TileRect { x: 0, y: 0, width: 64, height: 64 });
    let detailed = session.render_page_compact(doc.id, 0, 2.0, tile, true).unwrap();
    assert!(!detailed.rgb565);
    assert_eq!(detailed.pixels, session.render_page(doc.id, 0, 2.0, tile).unwrap().rgba);
}

#[test]
fn oversized_file_list_previews_are_refused_before_pdf_parsing() {
    let path = file(b"%PDF-1.7 deliberately incomplete");
    std::fs::OpenOptions::new().write(true).open(&path).unwrap().set_len(100 * 1024 * 1024 + 1).unwrap();
    let error = file_thumbnail(path.clone(), 160).unwrap_err().to_string();
    assert!(error.contains("input exceeds 100 MiB"), "{error}");
    assert_eq!(std::fs::metadata(path).unwrap().len(), 100 * 1024 * 1024 + 1);
}

/// #122: a renderer kept from before "Hide all comments" is not reused after it.
#[test]
fn renderers_built_before_a_view_change_are_not_reused_after_it() {
    let ink = "q 0 0 1 RG 3 w 1 J 1 j 10 10 m 60 80 l 110 20 l S Q";
    let bytes = fixture(
        "",
        "/Annots [7 0 R]",
        "",
        &[
            "<< /Type /Annot /Subtype /Ink /Rect [10 10 120 90] /InkList [[10 10 60 80 110 20]] /C [0 0 1] /AP << /N 8 0 R >> >>",
            &format!("<< /Type /XObject /Subtype /Form /BBox [10 10 120 90] /Length {} >>\nstream\n{ink}\nendstream", ink.len()),
        ],
    );
    let session = MobileSession::new();
    let doc = session.open(file(&bytes), None).unwrap().id;
    let shown = session.render_page(doc, 0, 1.0, None).unwrap().rgba;
    session.execute("comments_hide".into(), json!({ "doc": doc, "hidden": true }).to_string()).unwrap();
    let hidden = session.render_page(doc, 0, 1.0, None).unwrap().rgba;
    assert_ne!(hidden, shown, "the hidden drawing is no longer drawn");
    session.execute("comments_hide".into(), json!({ "doc": doc, "hidden": false }).to_string()).unwrap();
    assert_eq!(session.render_page(doc, 0, 1.0, None).unwrap().rgba, shown);
}

/// #115: notes the PDF marks Hidden or NoView are reported as hidden for the app's overlay.
#[test]
fn hidden_and_no_view_notes_are_reported_hidden() {
    let bytes = fixture(
        "",
        "/Annots [7 0 R 8 0 R 9 0 R]",
        "",
        &[
            "<< /Type /Annot /Subtype /Text /Rect [10 10 30 30] /F 2 /Contents (hidden) >>",
            "<< /Type /Annot /Subtype /Text /Rect [40 10 60 30] /F 32 /Contents (no view) >>",
            "<< /Type /Annot /Subtype /Text /Rect [70 10 90 30] /F 4 /Contents (shown) >>",
        ],
    );
    let session = MobileSession::new();
    let doc = session.open(file(&bytes), None).unwrap().id;
    let notes = session.comments(doc).unwrap();
    assert_eq!(notes.iter().map(|c| (c.contents.clone().unwrap_or_default(), c.hidden)).collect::<Vec<_>>(), [
        ("hidden".to_string(), true),
        ("no view".to_string(), true),
        ("shown".to_string(), false)
    ]);
}
