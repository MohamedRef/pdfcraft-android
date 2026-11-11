//! Host tests of the API the Android app uses (`cargo test -p pdfcraft-android`).

use std::path::PathBuf;
use std::sync::atomic::{AtomicU32, Ordering};

use super::*;

/// A fresh folder for one test's files.
fn scratch() -> PathBuf {
    static N: AtomicU32 = AtomicU32::new(0);
    let dir = std::env::temp_dir().join(format!("pdfcraft-android-test-{}-{}", std::process::id(), N.fetch_add(1, Ordering::Relaxed)));
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

/// A blank PDF of `pages` US Letter pages, written to a fresh folder.
fn blank_pdf(pages: usize) -> String {
    let bytes = pdfcraft_engine::Session::new().create_blank(612.0, 792.0, pages).unwrap();
    let path = scratch().join("blank.pdf");
    std::fs::write(&path, bytes.as_slice()).unwrap();
    path.to_string_lossy().into_owned()
}

thread_local! {
    /// The engine call that panics next on this test's thread (#114).
    static PANIC_IN: std::cell::RefCell<Option<String>> = const { std::cell::RefCell::new(None) };
}

/// Make the next engine call named `what` on this thread panic, as a bug in the engine would.
fn panic_next(what: &str) {
    PANIC_IN.with(|p| *p.borrow_mut() = Some(what.to_owned()));
}

/// Called by `MobileSession::with_auto` before every engine call in tests.
#[allow(clippy::panic)]
pub(super) fn inject_panic(what: &str) {
    if PANIC_IN.with(|p| p.borrow().as_deref() == Some(what)) {
        PANIC_IN.with(|p| *p.borrow_mut() = None);
        panic!("injected engine panic in {what}");
    }
}

#[test]
fn a_panicking_tool_fails_with_an_error_and_the_session_keeps_working() {
    let s = MobileSession::new();
    let doc = s.open(blank_pdf(2), None).unwrap().id;
    panic_next("page_rotate");
    let err = s.execute("page_rotate".into(), format!(r#"{{"doc": {doc}, "degrees": 90}}"#)).unwrap_err();
    assert!(matches!(&err, MobileError::Command(m) if m.contains("internal error") && m.contains("page_rotate")), "{err:?}");
    assert!(!s.auto.is_poisoned());
    // The next calls run as usual on the same document.
    s.execute("page_rotate".into(), format!(r#"{{"doc": {doc}, "degrees": 90}}"#)).unwrap();
    let info = s.document(doc).unwrap();
    assert_eq!(info.pages[0].rotation, 90);
    assert!(info.dirty);
    s.close(doc, true).unwrap();
    assert!(s.documents().is_empty());
}

#[test]
fn a_panicking_open_fails_with_an_error_and_leaves_no_document() {
    let s = MobileSession::new();
    let path = blank_pdf(1);
    panic_next("doc_open");
    assert!(matches!(s.open(path.clone(), None), Err(MobileError::Command(m)) if m.contains("internal error")));
    assert!(s.documents().is_empty());
    assert_eq!(s.open(path.clone(), None).unwrap().pages.len(), 1);
    panic_next("doc_open");
    assert!(matches!(s.begin_open(path.clone(), None, 0, false), Err(MobileError::Command(_))));
    assert!(!s.auto.is_poisoned());
    assert_eq!(s.documents().len(), 1);
    s.begin_open(path, None, 0, false).unwrap();
    assert_eq!(s.documents().len(), 2);
}

#[test]
fn a_poisoned_session_lock_is_recovered_and_cleared() {
    let s = MobileSession::new();
    let doc = s.open(blank_pdf(1), None).unwrap().id;
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| s.with_doc(doc, |_| std::panic::panic_any("a read panicked"))));
    assert!(s.auto.is_poisoned());
    assert_eq!(s.document(doc).unwrap().pages.len(), 1);
    assert!(!s.auto.is_poisoned());
}

#[test]
fn opens_a_document_and_describes_its_pages() {
    let s = MobileSession::new();
    let info = s.open(blank_pdf(3), None).unwrap();
    assert_eq!(info.name, "blank.pdf");
    assert_eq!(info.pages.len(), 3);
    assert_eq!((info.pages[0].width, info.pages[0].height), (612.0, 792.0));
    assert_eq!(info.pages[2].label, "3");
    assert!(!info.dirty);
    assert!(info.undo.is_none());
    assert_eq!(s.documents(), vec![info.id]);
}

#[test]
fn file_properties_are_read_only_and_match_the_parsed_document() {
    let s = MobileSession::new();
    let info = s.open(blank_pdf(2), None).unwrap();
    let before = s.document(info.id).unwrap().generation;
    let properties = s.file_properties(info.id).unwrap();
    let expected = s
        .with_doc(info.id, |d| (d.info_value("CreationDate"), d.info_value("ModDate"), d.custom_info(), d.allows_modification(), d.xmp_metadata()))
        .unwrap();
    assert_eq!(properties.creation_date, expected.0);
    assert_eq!(properties.modification_date, expected.1);
    assert_eq!(properties.custom_metadata.iter().map(|field| (field.name.clone(), field.value.clone())).collect::<Vec<_>>(), expected.2);
    assert_eq!(properties.allows_modification, expected.3);
    assert_eq!(properties.xmp_packet, expected.4);
    assert_eq!(s.document(info.id).unwrap().generation, before);
    assert!(!s.document(info.id).unwrap().dirty);
    assert!(matches!(s.file_properties(u64::MAX), Err(MobileError::NoDocument(_))));
}

#[test]
fn renders_a_page_and_a_tile() {
    let s = MobileSession::new();
    let doc = s.open(blank_pdf(1), None).unwrap().id;
    let img = s.render_page(doc, 0, 0.5, None).unwrap();
    assert_eq!((img.width, img.height), (306, 396));
    assert_eq!(img.rgba.len(), 306 * 396 * 4);
    // a blank page is opaque white
    assert!(img.rgba.chunks(4).all(|p| p == [255, 255, 255, 255]));
    let tile = s.render_page(doc, 0, 2.0, Some(TileRect { x: 100, y: 200, width: 64, height: 32 })).unwrap();
    assert_eq!((tile.width, tile.height), (64, 32));
    // renderers are reused, and again after an edit changes the document
    s.render_page(doc, 0, 0.25, None).unwrap();
    s.execute("page_rotate".into(), format!(r#"{{"doc": {doc}, "degrees": 90}}"#)).unwrap();
    let turned = s.render_page(doc, 0, 0.5, None).unwrap();
    assert_eq!((turned.width, turned.height), (396, 306));
}

#[test]
fn reading_colours_reach_cached_page_renderers() {
    let s = MobileSession::new();
    let doc = s.open(blank_pdf(1), None).unwrap().id;
    // warm the renderer cache with the default view
    let white = s.render_page(doc, 0, 0.25, None).unwrap();
    assert!(white.rgba.chunks(4).all(|p| p == [255, 255, 255, 255]));
    s.execute("view_reading_colors".into(), format!(r#"{{"doc": {doc}, "mode": "white-on-black"}}"#)).unwrap();
    // a view setting: the file is unchanged, but the next render must not reuse the old renderer
    assert!(!s.document(doc).unwrap().dirty);
    let dark = s.render_page(doc, 0, 0.25, None).unwrap();
    assert!(dark.rgba.chunks(4).all(|p| p == [0, 0, 0, 255]), "the page clears to the reading background");
    let tile = s.render_page(doc, 0, 1.0, Some(TileRect { x: 10, y: 10, width: 16, height: 16 })).unwrap();
    assert!(tile.rgba.chunks(4).all(|p| p == [0, 0, 0, 255]));
    s.execute("view_reading_colors".into(), format!(r#"{{"doc": {doc}, "mode": "off"}}"#)).unwrap();
    let again = s.render_page(doc, 0, 0.25, None).unwrap();
    assert_eq!(again.rgba, white.rgba, "turning reading colours off draws the page exactly as before");
}

#[test]
fn hiding_comments_is_a_view_setting() {
    let s = MobileSession::new();
    let doc = s.open(blank_pdf(1), None).unwrap().id;
    s.execute("comments_hide".into(), format!(r#"{{"doc": {doc}, "hidden": true}}"#)).unwrap();
    let info = s.document(doc).unwrap();
    assert!(info.comments_hidden);
    assert!(!info.dirty, "hiding annotations must not mark the file edited");
    s.execute("comments_hide".into(), format!(r#"{{"doc": {doc}, "hidden": false}}"#)).unwrap();
    assert!(!s.document(doc).unwrap().comments_hidden);
}

#[test]
fn refuses_bad_render_requests() {
    let s = MobileSession::new();
    let doc = s.open(blank_pdf(2), None).unwrap().id;
    assert!(matches!(s.render_page(doc, 2, 1.0, None), Err(MobileError::Render(_))));
    for scale in [0.0, -1.0, f32::NAN, f32::INFINITY] {
        assert!(matches!(s.render_page(doc, 0, scale, None), Err(MobileError::Render(_))), "{scale}");
    }
    assert!(matches!(s.render_page(doc + 1, 0, 1.0, None), Err(MobileError::NoDocument(_))));
    // an absurd scale is capped, not an allocation of terabytes
    let huge = s.render_page(doc, 0, 1.0e9, None).unwrap();
    assert!(huge.width <= pdfcraft_render::MAX_SIDE as u32 && huge.height <= pdfcraft_render::MAX_SIDE as u32);
    assert!(u64::from(huge.width) * u64::from(huge.height) <= MAX_MOBILE_PAGE_PIXELS);
    assert_eq!(huge.rgba.len(), huge.width as usize * huge.height as usize * 4);
    // Reject hostile tile sizes before asking the native renderer to allocate pixels.
    assert!(matches!(s.render_page(doc, 0, 1.0, Some(TileRect { x: 0, y: 0, width: 0, height: 1 })), Err(MobileError::Render(_))));
    assert!(matches!(s.render_page(doc, 0, 1.0, Some(TileRect { x: 0, y: 0, width: 8192, height: 8192 })), Err(MobileError::Render(_))));
}

#[test]
fn runs_tools_and_tracks_undo() {
    let s = MobileSession::new();
    let doc = s.open(blank_pdf(2), None).unwrap().id;
    let before = s.document(doc).unwrap().generation;
    let out = s.execute("page_delete".into(), format!(r#"{{"doc": {doc}, "pages": [2]}}"#)).unwrap();
    let v: Value = serde_json::from_str(&out).unwrap();
    assert_eq!(v["pages"], 1);
    let info = s.document(doc).unwrap();
    assert_eq!(info.pages.len(), 1);
    assert!(info.dirty);
    assert!(info.undo.is_some());
    assert_ne!(info.generation, before);
    s.execute("edit_undo".into(), format!(r#"{{"doc": {doc}}}"#)).unwrap();
    assert_eq!(s.document(doc).unwrap().pages.len(), 2);
}

#[test]
fn reports_tool_errors_instead_of_failing_hard() {
    let s = MobileSession::new();
    assert!(matches!(s.execute("no_such_tool".into(), String::new()), Err(MobileError::Command(_))));
    assert!(matches!(s.execute("doc_info".into(), "{not json".into()), Err(MobileError::Command(_))));
    assert!(matches!(s.execute("doc_info".into(), r#"{"doc": 99}"#.into()), Err(MobileError::Command(_))));
    assert!(matches!(s.execute("doc_info".into(), "[1, 2]".into()), Err(MobileError::Command(_))));
    assert!(!s.tools().is_empty());
}

#[test]
fn open_failures_are_told_apart() {
    let s = MobileSession::new();
    assert!(matches!(s.open("relative.pdf".into(), None), Err(MobileError::Open(_))));
    let junk = scratch().join("junk.pdf");
    std::fs::write(&junk, b"this is not a pdf").unwrap();
    assert!(matches!(s.open(junk.to_string_lossy().into_owned(), None), Err(MobileError::Open(_))));
    let missing = scratch().join("missing.pdf");
    assert!(matches!(s.open(missing.to_string_lossy().into_owned(), None), Err(MobileError::Open(_))));
}

#[test]
fn asks_for_a_password_and_opens_with_the_right_one() {
    let s = MobileSession::new();
    let path = blank_pdf(1);
    let doc = s.open(path.clone(), None).unwrap().id;
    s.execute("doc_protect".into(), format!(r#"{{"doc": {doc}, "open_password": "secret"}}"#)).unwrap();
    let locked = scratch().join("locked.pdf").to_string_lossy().into_owned();
    s.execute("doc_save".into(), json!({ "doc": doc, "path": locked }).to_string()).unwrap();
    s.close(doc, true).unwrap();
    assert!(matches!(s.open(locked.clone(), None), Err(MobileError::NeedsPassword)));
    assert!(matches!(s.open(locked.clone(), Some("wrong".into())), Err(MobileError::WrongPassword)));
    let info = s.open(locked, Some("secret".into())).unwrap();
    assert!(info.encrypted);
    let img = s.render_page(info.id, 0, 0.1, None).unwrap();
    assert!(img.width > 0);
}

#[test]
fn flattens_the_outline() {
    let s = MobileSession::new();
    let doc = s.open(blank_pdf(3), None).unwrap().id;
    assert!(s.outline(doc).unwrap().is_empty());
    s.execute("bookmark_add".into(), format!(r#"{{"doc": {doc}, "title": "Intro", "page": 1}}"#)).unwrap();
    s.execute("bookmark_add".into(), format!(r#"{{"doc": {doc}, "title": "End", "page": 3}}"#)).unwrap();
    let outline = s.outline(doc).unwrap();
    assert_eq!(outline.iter().map(|e| (e.title.as_str(), e.level, e.page)).collect::<Vec<_>>(), vec![("Intro", 0, Some(0)), ("End", 0, Some(2))]);
    assert!(s.document(doc).unwrap().has_outline);
}

#[test]
fn closing_refuses_to_lose_edits() {
    let s = MobileSession::new();
    let doc = s.open(blank_pdf(2), None).unwrap().id;
    s.execute("page_delete".into(), format!(r#"{{"doc": {doc}, "pages": [1]}}"#)).unwrap();
    assert!(s.close(doc, false).is_err());
    s.close(doc, true).unwrap();
    assert!(s.documents().is_empty());
    assert!(matches!(s.document(doc), Err(MobileError::NoDocument(_))));
}

/// A PDF made from plain text, written to a fresh folder.
fn text_pdf(text: &str) -> String {
    let bytes = pdfcraft_engine::Session::new().create_from_text("Notes", text).unwrap();
    let path = scratch().join("notes.pdf");
    std::fs::write(&path, bytes.as_slice()).unwrap();
    path.to_string_lossy().into_owned()
}

#[test]
fn selects_words_and_reads_their_text() {
    let s = MobileSession::new();
    let doc = s.open(text_pdf("Hello brave world\nSecond line here"), None).unwrap().id;
    assert!(s.glyph_count(doc, 0).unwrap() > 20);
    let hits = s.find_in_page(doc, 0, "brave".into(), false, false).unwrap();
    assert_eq!(hits.len(), 1);
    let hit = &hits[0];
    assert_eq!(s.text_of(doc, hit.range).unwrap(), "brave");
    assert_eq!(hit.rects.len(), 4);
    assert!(hit.context.contains("Hello brave world"));
    // a long press in the middle of the word selects all of it
    let (cx, cy) = ((hit.rects[0] + hit.rects[2]) / 2.0, (hit.rects[1] + hit.rects[3]) / 2.0);
    let word = s.word_at(doc, 0, cx, cy, 4.0).unwrap().unwrap();
    assert_eq!(word, hit.range);
    // far from any text there is no word
    assert!(s.word_at(doc, 0, 600.0, 780.0, 4.0).unwrap().is_none());
    // a range across the line break reads both lines
    let all = TextRange { page: 0, start: 0, end: s.glyph_count(doc, 0).unwrap() };
    assert_eq!(s.text_of(doc, all).unwrap(), "Hello brave world\nSecond line here");
    assert_eq!(s.text_rects(doc, all).unwrap().len(), 8);
    // out-of-range glyph indices are clamped, not a crash
    let wild = TextRange { page: 0, start: 5, end: u32::MAX };
    assert!(s.text_of(doc, wild).is_ok());
    assert!(s.glyph_rect(doc, 0, u32::MAX).unwrap().is_empty());
    assert!(s.text_of(doc, TextRange { page: 9, start: 0, end: 1 }).is_err());
}

#[test]
fn search_options_and_fresh_text_after_an_edit() {
    let s = MobileSession::new();
    let doc = s.open(text_pdf("Cat cat CAT category"), None).unwrap().id;
    assert_eq!(s.find_in_page(doc, 0, "cat".into(), false, false).unwrap().len(), 4);
    assert_eq!(s.find_in_page(doc, 0, "cat".into(), true, false).unwrap().len(), 2);
    assert_eq!(s.find_in_page(doc, 0, "cat".into(), false, true).unwrap().len(), 3);
    assert!(s.find_in_page(doc, 0, "  ".into(), false, false).unwrap().is_empty());
    let before = s.find_in_page(doc, 0, "Cat".into(), true, false).unwrap()[0].rects.clone();
    // turning the page changes where the text is: the cache follows the document's version
    s.execute("page_rotate".into(), format!(r#"{{"doc": {doc}, "degrees": 90}}"#)).unwrap();
    let after = s.find_in_page(doc, 0, "Cat".into(), true, false).unwrap()[0].rects.clone();
    assert_ne!(before, after);
}

#[test]
fn lists_comments_links_widgets_and_thumbnails() {
    let s = MobileSession::new();
    let path = blank_pdf(2);
    let doc = s.open(path.clone(), None).unwrap().id;
    assert!(s.comments(doc).unwrap().is_empty());
    s.execute(
        "comment_add".into(),
        json!({"doc": doc, "page": 2, "type": "note", "at": [100, 120], "contents": "Look here", "author": "Ann"}).to_string(),
    )
    .unwrap();
    let comments = s.comments(doc).unwrap();
    assert_eq!(comments.len(), 1);
    let c = &comments[0];
    assert_eq!((c.page, c.subtype.as_str(), c.contents.as_deref(), c.author.as_deref()), (1, "Text", Some("Look here"), Some("Ann")));
    // its rectangle is in page points from the top-left, where it was put
    assert!((c.rect[0] - 100.0).abs() < 1.0 && (c.rect[1] - 120.0).abs() < 1.0, "{:?}", c.rect);
    // the index is the one the comment tools take (1-based there)
    s.execute("comment_delete".into(), json!({"doc": doc, "page": c.page + 1, "index": c.index + 1}).to_string()).unwrap();
    assert!(s.comments(doc).unwrap().is_empty());

    s.execute("link_add".into(), json!({"doc": doc, "page": 1, "rect": [50, 60, 150, 80], "to_page": 2}).to_string()).unwrap();
    s.execute("link_add".into(), json!({"doc": doc, "page": 1, "rect": [50, 100, 150, 120], "url": "https://example.org/a"}).to_string()).unwrap();
    let links = s.links(doc, 0).unwrap();
    assert_eq!(links.len(), 2);
    let to_page = links.iter().find(|l| l.page.is_some()).unwrap();
    assert_eq!(to_page.page, Some(1));
    assert!((to_page.rect[1] - 60.0).abs() < 1.0 && (to_page.rect[3] - 80.0).abs() < 1.0, "{:?}", to_page.rect);
    assert_eq!(links.iter().find_map(|l| l.uri.clone()).as_deref(), Some("https://example.org/a"));
    assert!(s.links(doc, 7).unwrap().is_empty());

    s.execute("form_add_field".into(), json!({"doc": doc, "page": 1, "type": "text", "rect": [72, 200, 272, 224], "name": "who"}).to_string())
        .unwrap();
    s.execute("form_add_field".into(), json!({"doc": doc, "page": 1, "type": "checkbox", "rect": [72, 240, 90, 258], "name": "ok"}).to_string())
        .unwrap();
    let widgets = s.widgets(doc).unwrap();
    let who = widgets.iter().find(|w| w.field == "who").unwrap();
    assert_eq!((who.kind.as_str(), who.page), ("text", 0));
    assert!((who.rect[0] - 72.0).abs() < 1.0 && (who.rect[1] - 200.0).abs() < 1.0, "{:?}", who.rect);
    s.execute("form_fill".into(), json!({"doc": doc, "values": {"who": "Bo", "ok": true}}).to_string()).unwrap();
    let widgets = s.widgets(doc).unwrap();
    assert_eq!(widgets.iter().find(|w| w.field == "who").unwrap().value.as_deref(), Some("Bo"));
    assert!(widgets.iter().find(|w| w.field == "ok").unwrap().on);

    let thumb = file_thumbnail(path, 100).unwrap();
    assert!(thumb.height <= 100 && thumb.height >= 99 && thumb.width < thumb.height);
    assert!(file_thumbnail("/no/such/file.pdf".into(), 100).is_err());
    assert!(s.attachments(doc).unwrap().is_empty());
    assert!(s.save_attachment(doc, 0, scratch().join("x").to_string_lossy().into_owned()).is_err());
}

/// A WAV file of 16-bit mono PCM at 16 kHz holding `samples`.
fn wav_file(samples: &[i16]) -> String {
    let data: Vec<u8> = samples.iter().flat_map(|s| s.to_le_bytes()).collect();
    let mut w = Vec::new();
    w.extend_from_slice(b"RIFF");
    w.extend_from_slice(&(36 + data.len() as u32).to_le_bytes());
    w.extend_from_slice(b"WAVEfmt ");
    w.extend_from_slice(&16u32.to_le_bytes());
    w.extend_from_slice(&[1, 0, 1, 0]);
    w.extend_from_slice(&16000u32.to_le_bytes());
    w.extend_from_slice(&32000u32.to_le_bytes());
    w.extend_from_slice(&[2, 0, 16, 0]);
    w.extend_from_slice(b"data");
    w.extend_from_slice(&(data.len() as u32).to_le_bytes());
    w.extend_from_slice(&data);
    let path = scratch().join("voice.wav");
    std::fs::write(&path, w).unwrap();
    path.to_string_lossy().into_owned()
}

#[test]
fn a_voice_comment_keeps_its_recording() {
    let s = MobileSession::new();
    let doc = s.open(blank_pdf(1), None).unwrap().id;
    let samples: Vec<i16> = (0..1600).map(|i| ((i as f32 * 0.2).sin() * 12000.0) as i16).collect();
    let wav = wav_file(&samples);
    let args = serde_json::json!({ "doc": doc, "page": 1, "type": "sound", "at": [100, 100], "path": wav, "author": "Tester" });
    s.execute("comment_add".into(), args.to_string()).unwrap();
    let c = s.comments(doc).unwrap();
    assert_eq!(c.len(), 1);
    assert_eq!(c[0].subtype, "Sound");
    // the icon sits where the page was tapped
    assert!((c[0].rect[0] - 100.0).abs() < 0.5 && (c[0].rect[1] - 100.0).abs() < 0.5, "{:?}", c[0].rect);
    // the recording comes back out as the same samples
    let out = scratch().join("out.wav");
    s.save_sound(doc, 0, c[0].index, out.to_string_lossy().into_owned()).unwrap();
    let back = std::fs::read(&out).unwrap();
    assert_eq!(&back[..4], b"RIFF");
    assert_eq!(u32::from_le_bytes([back[24], back[25], back[26], back[27]]), 16000);
    let pcm: Vec<i16> = back[44..].as_chunks::<2>().0.iter().map(|b| i16::from_le_bytes(*b)).collect();
    assert_eq!(pcm, samples);
    // it survives saving and reopening
    let saved = scratch().join("voice.pdf");
    s.execute("doc_save".into(), serde_json::json!({ "doc": doc, "path": saved, "full": true }).to_string()).unwrap();
    let again = s.open(saved.to_string_lossy().into_owned(), None).unwrap().id;
    let c = s.comments(again).unwrap();
    assert_eq!(c[0].subtype, "Sound");
    s.save_sound(again, 0, c[0].index, out.to_string_lossy().into_owned()).unwrap();
    assert_eq!(std::fs::read(&out).unwrap(), back);
    // not a recording: a clear error, no comment
    let bad = scratch().join("bad.wav");
    std::fs::write(&bad, b"RIFF....WAVEjunk").unwrap();
    let args = serde_json::json!({ "doc": doc, "page": 1, "type": "sound", "at": [10, 10], "path": bad });
    assert!(s.execute("comment_add".into(), args.to_string()).is_err());
    assert!(s.save_sound(doc, 0, 99, out.to_string_lossy().into_owned()).is_err());
}

/// A one-page PDF with a file attached to the document (`data.csv`) and one on a comment (`note.txt`).
fn attachments_pdf() -> String {
    let objects = [
        "<< /Type /Catalog /Pages 2 0 R /Names << /EmbeddedFiles 6 0 R >> >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Annots [4 0 R] >>",
        "<< /Type /Annot /Subtype /FileAttachment /Rect [10 10 30 30] /FS 5 0 R /Contents (note) >>",
        "<< /Type /Filespec /F (note.txt) /EF << /F 7 0 R >> >>",
        "<< /Names [(data.csv) 8 0 R] >>",
        "<< /Type /EmbeddedFile /Length 5 >>\nstream\nhello\nendstream",
        "<< /Type /Filespec /F (data.csv) /EF << /F 9 0 R >> >>",
        "<< /Type /EmbeddedFile /Length 3 >>\nstream\na,b\nendstream",
    ];
    let mut pdf = String::from("%PDF-1.7\n");
    let mut offsets = Vec::new();
    for (i, body) in objects.iter().enumerate() {
        offsets.push(pdf.len());
        pdf.push_str(&format!("{} 0 obj\n{body}\nendobj\n", i + 1));
    }
    let xref = pdf.len();
    pdf.push_str(&format!("xref\n0 {}\n0000000000 65535 f \n", objects.len() + 1));
    for o in offsets {
        pdf.push_str(&format!("{o:010} 00000 n \n"));
    }
    pdf.push_str(&format!("trailer\n<< /Size {} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n", objects.len() + 1));
    let path = scratch().join("attachments.pdf");
    std::fs::write(&path, pdf).unwrap();
    path.to_string_lossy().into_owned()
}

#[test]
fn tells_document_attachments_from_comment_attachments() {
    let s = MobileSession::new();
    let info = s.open(attachments_pdf(), None).unwrap();
    // only the document's own file counts for the Attachments list
    assert_eq!(info.attachments, 1);
    let list = s.attachments(info.id).unwrap();
    let doc_file = list.iter().find(|a| a.name == "data.csv").unwrap();
    let comment_file = list.iter().find(|a| a.name == "note.txt").unwrap();
    assert!(!doc_file.on_page);
    assert!(comment_file.on_page);
}

#[test]
fn reading_blocks_split_paragraphs_and_map_characters_to_glyphs() {
    let s = MobileSession::new();
    let doc = s.open(text_pdf("First paragraph here.\n\nSecond one follows."), None).unwrap().id;
    let blocks = s.reading_blocks(doc, 0).unwrap();
    let texts: Vec<&str> = blocks.iter().map(|b| b.text.as_str()).collect();
    assert_eq!(texts, vec!["First paragraph here.", "Second one follows."]);
    for b in &blocks {
        // one glyph per character, within the block's range and in order
        assert_eq!(b.glyph_of_char.len(), b.text.chars().count());
        assert!(b.glyph_of_char.windows(2).all(|w| w[0] <= w[1]));
        assert!(b.glyph_of_char.iter().all(|&g| g >= b.range.start && g < b.range.end));
    }
    // the word "Second" maps to glyphs whose text is that word
    let second = &blocks[1];
    let range = TextRange { page: 0, start: second.glyph_of_char[0], end: second.glyph_of_char[5] + 1 };
    assert_eq!(s.text_of(doc, range).unwrap(), "Second");
}

#[test]
fn signs_with_a_digital_id_and_trusts_its_signer() {
    let s = MobileSession::new();
    let dir = scratch();
    let id = dir.join("me.p12").to_string_lossy().into_owned();
    let made = json!({ "name": "Test Signer", "email": "signer@example.com", "password": "secret12", "path": id });
    s.execute("sign_id_create".into(), made.to_string()).unwrap();
    // the file's certificate, and a wrong password told apart
    let info = digital_id_info(id.clone(), "secret12".into()).unwrap();
    assert_eq!(info.name, "Test Signer");
    assert_eq!(info.email.as_deref(), Some("signer@example.com"));
    assert!(info.self_signed && info.valid_to > info.valid_from && info.problem.is_none());
    assert_eq!(info.sha256.replace(' ', "").len(), 64);
    assert!(matches!(digital_id_info(id.clone(), "wrong".into()), Err(MobileError::WrongPassword)));
    let junk = dir.join("junk.p12");
    std::fs::write(&junk, b"not a digital id").unwrap();
    assert!(matches!(digital_id_info(junk.to_string_lossy().into_owned(), "x".into()), Err(MobileError::Command(_))));

    // signing writes the signed file over the working copy the document was opened from
    let path = blank_pdf(1);
    let doc = s.open(path.clone(), None).unwrap().id;
    assert!(s.signer_certificate(doc, "Signature1".into()).unwrap().is_none());
    let args =
        json!({ "doc": doc, "id": id, "password": "secret12", "page": 1, "rect": [72, 72, 252, 122], "graphic": { "text": "Test" }, "out": path });
    let out: Value = serde_json::from_str(&s.execute("sign_document".into(), args.to_string()).unwrap()).unwrap();
    let field = out["signature"]["field"].as_str().unwrap().to_string();
    assert_eq!(out["signature"]["status"], "unknown");
    let info = s.document(doc).unwrap();
    assert!(info.signed && !info.dirty && info.undo.is_none());
    assert_eq!(std::fs::read(&path).unwrap().len() as u64, out["bytes"].as_u64().unwrap());

    // trusting the signer's certificate makes the signature valid
    let cert = s.signer_certificate(doc, field).unwrap().unwrap();
    let cer = dir.join("signer.cer");
    std::fs::write(&cer, &cert).unwrap();
    s.execute("sign_trust".into(), json!({ "paths": [cer.to_string_lossy()] }).to_string()).unwrap();
    let list: Value = serde_json::from_str(&s.execute("sign_list".into(), json!({ "doc": doc }).to_string()).unwrap()).unwrap();
    assert_eq!(list["all_valid"], true);

    // an edit after signing is saved incrementally: the signature stays valid
    s.execute("comment_add".into(), json!({ "doc": doc, "page": 1, "type": "note", "at": [300, 300], "contents": "after" }).to_string()).unwrap();
    s.execute("doc_save".into(), json!({ "doc": doc }).to_string()).unwrap();
    let list: Value = serde_json::from_str(&s.execute("sign_list".into(), json!({ "doc": doc }).to_string()).unwrap()).unwrap();
    assert_eq!(list["signatures"][0]["status"], "valid");
    assert_eq!(list["signatures"][0]["modification"], "allowed");
}

/// A form's scripts run through the calls the app makes: filling a field recalculates a scripted
/// total and returns its alert, and a button's script (from `form_actions`) runs with `js_run`.
#[test]
fn form_scripts_run_when_fields_are_filled_and_buttons_pressed() {
    let s = MobileSession::new();
    let doc = s.open(blank_pdf(1), None).unwrap().id;
    let run = |tool: &str, args: Value| -> Value { serde_json::from_str(&s.execute(tool.into(), args.to_string()).unwrap()).unwrap() };
    for (i, name) in ["a", "b", "total"].into_iter().enumerate() {
        let y = 100 + 30 * i;
        run("form_add_field", json!({"doc": doc, "page": 1, "type": "text", "rect": [200, y, 360, y + 20], "name": name}));
    }
    run("form_add_field", json!({"doc": doc, "page": 1, "type": "button", "rect": [200, 200, 360, 224], "name": "double", "caption": "Double a"}));
    let sum = "event.value = Number(this.getField(\"a\").value) + Number(this.getField(\"b\").value); if (event.value > 9) app.alert(\"Big\");";
    run("form_set_script", json!({"doc": doc, "field": "total", "event": "calculate", "script": sum}));
    let double = "var a = this.getField(\"a\"); a.value = Number(a.value) * 2; app.alert(\"a is now \" + a.value);";
    run("form_set_script", json!({"doc": doc, "field": "double", "event": "mouse_up", "script": double}));
    let total = |s: &MobileSession| s.widgets(doc).unwrap().into_iter().find(|w| w.field == "total").and_then(|w| w.value);

    run("form_fill", json!({"doc": doc, "values": {"a": "2"}}));
    let filled = run("form_fill", json!({"doc": doc, "values": {"b": "3"}}));
    assert_eq!(total(&s).as_deref(), Some("5"), "{filled}");
    let actions = run("form_actions", json!({"doc": doc, "field": "double"}));
    let script = actions["actions"][0]["javascript"].as_str().unwrap().to_owned();
    let pressed = run("js_run", json!({"doc": doc, "field": "double", "script": script}));
    assert_eq!(pressed["alerts"].as_array().map(Vec::len), Some(1), "{pressed}");
    assert_eq!(pressed["alerts"][0]["message"], "a is now 4", "{pressed}");
    assert_eq!(total(&s).as_deref(), Some("7"));
    let big = run("form_fill", json!({"doc": doc, "values": {"b": "8"}}));
    assert_eq!(big["alerts"].as_array().map(Vec::len), Some(1), "{big}");
    assert_eq!(big["alerts"][0]["message"], "Big", "{big}");
}

/// Exercise the exact headless route used by the opt-in Android backgrounds panel.
#[test]
fn backdrop_inspection_is_scoped_and_does_not_edit_the_document() {
    use serde_json::{Value, json};
    let fill = "q 1 g 0 0 612 792 re f Q";
    let image = "q 612 0 0 792 0 0 cm /Im Do Q";
    let objects = vec![
        "<< /Type /Catalog /Pages 2 0 R >>".to_string(),
        "<< /Type /Pages /Kids [3 0 R 4 0 R 5 0 R] /Count 3 /MediaBox [0 0 612 792] >>".into(),
        "<< /Type /Page /Parent 2 0 R /Contents 6 0 R >>".into(),
        "<< /Type /Page /Parent 2 0 R /Contents 7 0 R /Resources << /XObject << /Im 8 0 R >> >> >>".into(),
        "<< /Type /Page /Parent 2 0 R >>".into(),
        format!("<< /Length {} >>\nstream\n{fill}\nendstream", fill.len()),
        format!("<< /Length {} >>\nstream\n{image}\nendstream", image.len()),
        "<< /Type /XObject /Subtype /Image /Width 1 /Height 1 /ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /ASCIIHexDecode /Length 3 >>\nstream\nFF>\nendstream".into(),
    ];
    let mut bytes = b"%PDF-1.7\n".to_vec();
    let mut offsets = Vec::new();
    for (i, object) in objects.iter().enumerate() {
        offsets.push(bytes.len());
        bytes.extend_from_slice(format!("{} 0 obj\n{object}\nendobj\n", i + 1).as_bytes());
    }
    let xref = bytes.len();
    bytes.extend_from_slice(format!("xref\n0 {}\n0000000000 65535 f \n", objects.len() + 1).as_bytes());
    for offset in offsets {
        bytes.extend_from_slice(format!("{offset:010} 00000 n \n").as_bytes());
    }
    bytes.extend_from_slice(format!("trailer\n<< /Size {} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n", objects.len() + 1).as_bytes());
    let dir = scratch();
    let path = dir.join("backgrounds.pdf");
    std::fs::write(&path, &bytes).unwrap();
    let s = MobileSession::new();
    let doc = s.open(path.to_string_lossy().into_owned(), None).unwrap();
    let inspect = |pages: Value| -> Value {
        serde_json::from_str(&s.execute("doc_backdrops".into(), json!({"doc": doc.id, "pages": pages}).to_string()).unwrap()).unwrap()
    };
    assert_eq!(
        inspect(json!([1, 2, 3]))["pages"],
        json!([
            {"page": 1, "backdrop": "fill"}, {"page": 2, "backdrop": "image"},
        ])
    );
    assert_eq!(inspect(json!([2]))["pages"], json!([{"page": 2, "backdrop": "image"}]));
    assert_eq!(inspect(json!([3]))["pages"], json!([]));
    assert!(s.execute("doc_backdrops".into(), json!({"doc": doc.id, "pages": [4]}).to_string()).is_err());
    let after = s.document(doc.id).unwrap();
    assert_eq!(after.generation, doc.generation);
    assert!(!after.dirty);
    assert!(after.undo.is_none());
    assert_eq!(std::fs::read(&path).unwrap(), bytes);
    std::fs::remove_dir_all(dir).unwrap();
}

#[test]
fn style_bookmarks_append_undo_and_survive_save_through_mobile_tools() {
    let s = MobileSession::new();
    let path = text_pdf("Heading\nBody text");
    let original = std::fs::read(&path).unwrap();
    let doc = s.open(path.clone(), None).unwrap().id;
    s.execute("bookmark_add".into(), json!({"doc": doc, "title": "Keep", "page": 1}).to_string()).unwrap();
    let before = s.document(doc).unwrap();
    let styles: Value = serde_json::from_str(&s.execute("text_styles".into(), json!({"doc": doc}).to_string()).unwrap()).unwrap();
    assert_eq!(s.document(doc).unwrap().generation, before.generation, "style analysis is read-only");
    let style = styles["styles"].as_array().unwrap().first().unwrap();
    let level = json!({"font": style["font"], "size": style["size"], "bold": style["bold"], "italic": style["italic"]});
    s.execute("bookmark_from_text_styles".into(), json!({"doc": doc, "levels": [level]}).to_string()).unwrap();
    let expected = s.outline(doc).unwrap();
    assert_eq!(expected[0].title, "Untitled");
    assert!(expected.iter().any(|entry| entry.title == "Keep"));
    assert!(expected.len() >= 3);
    s.execute("edit_undo".into(), json!({"doc": doc}).to_string()).unwrap();
    assert_eq!(s.outline(doc).unwrap().iter().map(|entry| entry.title.as_str()).collect::<Vec<_>>(), ["Keep"]);
    s.execute("edit_redo".into(), json!({"doc": doc}).to_string()).unwrap();
    let out = scratch().join("style-bookmarks.pdf");
    s.execute("doc_save".into(), json!({"doc": doc, "path": out}).to_string()).unwrap();
    s.close(doc, true).unwrap();
    let reopened = s.open(out.to_string_lossy().into_owned(), None).unwrap();
    let restored = s.outline(reopened.id).unwrap();
    assert_eq!(
        restored.iter().map(|entry| (&entry.title, entry.level, entry.page)).collect::<Vec<_>>(),
        expected.iter().map(|entry| (&entry.title, entry.level, entry.page)).collect::<Vec<_>>()
    );
    assert_eq!(std::fs::read(&path).unwrap(), original);
}

#[test]
fn paragraph_script_uses_native_unicode_toggle_and_survives_reopen() {
    let s = MobileSession::new();
    let path = text_pdf("H2O + CO2");
    let original = std::fs::read(&path).unwrap();
    let doc = s.open(path.clone(), None).unwrap().id;
    let rows: Value = serde_json::from_str(&s.execute("text_paragraphs".into(), json!({"doc": doc, "page": 1}).to_string()).unwrap()).unwrap();
    let paragraph = rows["paragraphs"].as_array().unwrap().iter().find(|row| row["text"] == "H2O + CO2").unwrap()["paragraph"].clone();
    let args = json!({"doc": doc, "page": 1, "paragraph": paragraph, "script": "subscript", "range": [1, 2]});
    let result: Value = serde_json::from_str(&s.execute("text_script".into(), args.to_string()).unwrap()).unwrap();
    assert_eq!(result["paragraph"]["text"], "H₂O + CO2");
    s.execute("edit_undo".into(), json!({"doc": doc}).to_string()).unwrap();
    assert!(s.page_plain_text(doc, 0).unwrap().contains("H2O + CO2"));
    s.execute("edit_redo".into(), json!({"doc": doc}).to_string()).unwrap();
    assert!(s.page_plain_text(doc, 0).unwrap().contains("H₂O + CO2"));
    let out = scratch().join("script.pdf");
    s.execute("doc_save".into(), json!({"doc": doc, "path": out}).to_string()).unwrap();
    s.close(doc, true).unwrap();
    let reopened = s.open(out.to_string_lossy().into_owned(), None).unwrap().id;
    assert!(s.page_plain_text(reopened, 0).unwrap().contains("H₂O + CO2"));
    assert_eq!(std::fs::read(&path).unwrap(), original);
}

#[test]
fn upgraded_tool_table_preserves_mobile_tools_and_adds_stable_020_schemas() {
    let session = MobileSession::new();
    let tools = session.tools();
    let names: std::collections::HashSet<_> = tools.iter().map(|t| t.name.as_str()).collect();
    assert_eq!(names.len(), tools.len(), "tool names must remain unique");
    assert_eq!(names.len(), 201);
    for name in [
        "photo_find_document",
        "comment_erase",
        "comment_add",
        "bookmark_export",
        "bookmark_import",
        "bookmark_copy",
        "bookmark_from_text_styles",
        "text_script",
        "doc_backdrops",
        "form_fill",
        "sign_document",
        "comments_copy",
        "comments_paste",
        "ocr_dictionary",
        "ocr_words",
        "ocr_review_apply",
        "page_enhance_scan",
        "accessibility_layout_table_to_blocks",
        "doc_fix_hairlines",
        "object_copy",
        "object_paste",
        "text_prepare",
        "comments_edit_many",
    ] {
        assert!(names.contains(name), "missing mobile/stable tool: {name}");
    }
    for omitted in ["model_add", "model_render"] {
        assert!(!names.contains(omitted));
    }
    for tool in &tools {
        let schema: Value = serde_json::from_str(&tool.input_schema).unwrap();
        assert_eq!(schema["type"], "object", "invalid schema for {}", tool.name);
    }
}

#[test]
fn new_comment_clipboard_keeps_ink_and_refuses_losing_embedded_payloads() {
    let s = MobileSession::new();
    let source = blank_pdf(1);
    let original = std::fs::read(&source).unwrap();
    let doc = s.open(source.clone(), None).unwrap().id;
    let samples = [1i16, -42, 1100, -32000];
    let wav = wav_file(&samples);
    s.execute("comment_add".into(), serde_json::json!({"doc":doc,"page":1,"type":"sound","at":[100,100],"path":wav}).to_string()).unwrap();
    s.execute(
        "comment_add".into(),
        serde_json::json!({"doc":doc,"page":1,"type":"ink","strokes":[[[10,10],[40,50]]],"width":3,"opacity":0.6}).to_string(),
    )
    .unwrap();
    let comments = s.comments(doc).unwrap();
    let sound = comments.iter().find(|c| c.subtype == "Sound").unwrap();
    let ink = comments.iter().find(|c| c.subtype == "Ink").unwrap();
    let generation = s.document(doc).unwrap().generation;
    let copied = s.execute("comments_copy".into(), serde_json::json!({"doc":doc,"comments":[[1,sound.index+1],[1,ink.index+1]]}).to_string());
    assert!(copied.unwrap_err().to_string().contains("recordings"));
    assert_eq!(s.document(doc).unwrap().generation, generation);
    let out = scratch().join("still-voice.wav");
    s.save_sound(doc, 0, sound.index, out.to_string_lossy().into_owned()).unwrap();
    assert_eq!(&std::fs::read(&out).unwrap()[44..], samples.iter().flat_map(|s| s.to_le_bytes()).collect::<Vec<_>>());
    let copied: Value =
        serde_json::from_str(&s.execute("comments_copy".into(), serde_json::json!({"doc":doc,"comments":[[1,ink.index+1]]}).to_string()).unwrap())
            .unwrap();
    let target = s.open(blank_pdf(1), None).unwrap().id;
    s.execute("comments_paste".into(), serde_json::json!({"doc":target,"page":1,"xfdf":copied["xfdf"]}).to_string()).unwrap();
    let pasted = s.comments(target).unwrap();
    assert_eq!(pasted.len(), 1);
    assert_eq!(pasted[0].subtype, "Ink");
    assert_eq!(pasted[0].width, Some(3.0));
    assert!((pasted[0].opacity - 0.6).abs() < 0.01);
    s.execute("edit_undo".into(), serde_json::json!({"doc":target}).to_string()).unwrap();
    assert!(s.comments(target).unwrap().is_empty());
    assert_eq!(std::fs::read(source).unwrap(), original);
    let attachment_doc = s.open(attachments_pdf(), None).unwrap().id;
    let attachments = s.comments(attachment_doc).unwrap();
    let attachment = attachments.iter().find(|c| c.subtype == "FileAttachment").unwrap();
    let generation = s.document(attachment_doc).unwrap().generation;
    let result = s.execute("comments_copy".into(), serde_json::json!({"doc":attachment_doc,"comments":[[1,attachment.index+1]]}).to_string());
    assert!(result.unwrap_err().to_string().contains("attachments"));
    assert_eq!(s.document(attachment_doc).unwrap().generation, generation);
}

#[test]
fn profiled_open_preserves_document_rendering_and_records_only_stage_durations() {
    let s = MobileSession::new();
    let path = blank_pdf(3);
    let original = std::fs::read(&path).unwrap();
    let ordinary = s.open(path.clone(), None).unwrap();
    let profiled = s.open_with_timings(path.clone(), None).unwrap();
    assert_eq!(profiled.document.pages.len(), ordinary.pages.len());
    assert_eq!(profiled.document.file_size, ordinary.file_size);
    assert_eq!(profiled.document.dirty, ordinary.dirty);
    let names: Vec<_> = profiled.timings.iter().map(|t| t.stage.as_str()).collect();
    for expected in [
        "native_file_read",
        "native_cos_open",
        "native_render_parse",
        "native_page_geometry",
        "native_inspect_lazy",
        "native_prepare",
        "native_signatures",
        "native_summary",
    ] {
        assert!(names.contains(&expected), "missing {expected}: {names:?}");
    }
    assert!(names.iter().all(|stage| stage.starts_with("native_") && stage.bytes().all(|b| b.is_ascii_lowercase() || b == b'_')));
    assert!(profiled.timings.len() <= 64);
    let a = s.render_page(ordinary.id, 0, 0.25, None).unwrap();
    let b = s.render_page(profiled.document.id, 0, 0.25, None).unwrap();
    assert_eq!(a.rgba, b.rgba);
    assert_eq!(std::fs::read(path).unwrap(), original);
    assert!(matches!(s.open_with_timings("relative.pdf".into(), None), Err(MobileError::Open(_))));
    assert_eq!(s.documents().len(), 2);
}

/// One 100 x 100 page painted red inside layer "Red" (an optional content group).
fn layered_pdf() -> String {
    let content = "1 0 0 rg /OC /L1 BDC 0 0 100 100 re f EMC";
    let objects = [
        "<< /Type /Catalog /Pages 2 0 R /OCProperties << /OCGs [5 0 R] /D << /Order [5 0 R] /ON [5 0 R] >> >> >>".to_string(),
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_string(),
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Contents 4 0 R /Resources << /Properties << /L1 5 0 R >> >> >>".to_string(),
        format!("<< /Length {} >>\nstream\n{content}\nendstream", content.len()),
        "<< /Type /OCG /Name (Red) >>".to_string(),
    ];
    let mut pdf = String::from("%PDF-1.7\n");
    let mut offsets = Vec::new();
    for (i, body) in objects.iter().enumerate() {
        offsets.push(pdf.len());
        pdf.push_str(&format!("{} 0 obj\n{body}\nendobj\n", i + 1));
    }
    let xref = pdf.len();
    pdf.push_str(&format!("xref\n0 {}\n0000000000 65535 f \n", objects.len() + 1));
    for o in offsets {
        pdf.push_str(&format!("{o:010} 00000 n \n"));
    }
    pdf.push_str(&format!("trailer\n<< /Size {} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n", objects.len() + 1));
    let path = scratch().join("layered.pdf");
    std::fs::write(&path, pdf).unwrap();
    path.to_string_lossy().into_owned()
}

#[test]
fn hiding_a_layer_changes_the_page_render_without_editing() {
    let s = MobileSession::new();
    let info = s.open(layered_pdf(), None).unwrap();
    let doc = info.id;
    let centre = |img: &RenderedImage| {
        let i = ((img.height / 2) * img.width + img.width / 2) as usize * 4;
        [img.rgba[i], img.rgba[i + 1], img.rgba[i + 2]]
    };
    // a first render leaves an idle renderer made with the layer shown
    assert_eq!(centre(&s.render_page(doc, 0, 1.0, None).unwrap()), [255, 0, 0]);
    let out: Value = serde_json::from_str(&s.execute("layer_set".into(), format!(r#"{{"doc": {doc}, "layer": "Red", "visible": false}}"#)).unwrap()).unwrap();
    assert_eq!(out["layers"][0]["visible"], json!(false));
    assert_eq!(centre(&s.render_page(doc, 0, 1.0, None).unwrap()), [255, 255, 255]);
    let after = s.document(doc).unwrap();
    // a view setting: nothing to save, nothing to undo, the same generation
    assert!(!after.dirty);
    assert_eq!(after.undo, None);
    assert_eq!(after.generation, info.generation);
    s.execute("layer_set".into(), format!(r#"{{"doc": {doc}, "layer": 1, "visible": true}}"#)).unwrap();
    assert_eq!(centre(&s.render_page(doc, 0, 1.0, None).unwrap()), [255, 0, 0]);
}

#[test]
fn lists_layers_with_their_visibility() {
    let s = MobileSession::new();
    let info = s.open(layered_pdf(), None).unwrap();
    assert_eq!(info.layers, 1);
    let layers = s.layers(info.id).unwrap();
    assert_eq!((layers[0].name.as_str(), layers[0].visible), ("Red", true));
    s.execute("layer_set".into(), format!(r#"{{"doc": {}, "layer": 1, "visible": false}}"#, info.id)).unwrap();
    assert!(!s.layers(info.id).unwrap()[0].visible);
    assert_eq!(s.open(blank_pdf(1), None).unwrap().layers, 0);
}

/// The bookmark moves the app's Edit contents sends (`outlineMoveArgs` in the Kotlin app).
#[test]
fn bookmark_moves_the_app_sends_land_where_expected() {
    let s = MobileSession::new();
    let doc = s.open(blank_pdf(5), None).unwrap().id;
    let run = |name: &str, args: Value| {
        let mut args = args;
        args["doc"] = json!(doc);
        s.execute(name.into(), args.to_string()).unwrap();
    };
    let titles = || s.outline(doc).unwrap().iter().map(|e| format!("{}{}", "  ".repeat(e.level as usize), e.title)).collect::<Vec<_>>().join("|");
    run("bookmark_add", json!({ "title": "A", "page": 1 }));
    run("bookmark_add", json!({ "title": "A1", "page": 2, "parent": [1] }));
    run("bookmark_add", json!({ "title": "A2", "page": 3, "parent": [1] }));
    run("bookmark_add", json!({ "title": "B", "page": 4 }));
    run("bookmark_add", json!({ "title": "C", "page": 5 }));
    assert_eq!(titles(), "A|  A1|  A2|B|C");
    // Down: A1 after A2
    run("bookmark_move", json!({ "path": [1, 1], "parent": [1], "position": 2 }));
    assert_eq!(titles(), "A|  A2|  A1|B|C");
    // Out: A1 (now [1, 2]) to the top level right after A
    run("bookmark_move", json!({ "path": [1, 2], "position": 2 }));
    assert_eq!(titles(), "A|  A2|A1|B|C");
    // In: B ([3]) becomes the last child of A1 ([2])
    run("bookmark_move", json!({ "path": [3], "parent": [2] }));
    assert_eq!(titles(), "A|  A2|A1|  B|C");
    // Up: C ([3]) before A1
    run("bookmark_move", json!({ "path": [3], "position": 2 }));
    assert_eq!(titles(), "A|  A2|C|A1|  B");
}
