//! #582: an edit to a file opened in place must not copy the whole file.
use super::MobileSession;
use pdfcraft_engine::DocId;
use serde_json::json;
use std::time::Instant;

fn big_pdf(pages: usize, blob_mb: usize) -> Vec<u8> {
    let mut objs: Vec<Vec<u8>> = Vec::new();
    objs.push(b"<< /Type /Catalog /Pages 2 0 R >>".to_vec());
    let kids: String = (0..pages).map(|i| format!("{} 0 R ", i + 4)).collect();
    objs.push(format!("<< /Type /Pages /Kids [{kids}] /Count {pages} /MediaBox [0 0 612 792] >>").into_bytes());
    let mut x: u64 = 0x9E3779B97F4A7C15;
    let blob: Vec<u8> = (0..blob_mb * 1024 * 1024).map(|_| { x ^= x << 13; x ^= x >> 7; x ^= x << 17; (x & 0xff) as u8 }).collect();
    let mut s = format!("<< /Type /XObject /Subtype /Image /Width 1024 /Height {} /ColorSpace /DeviceGray /BitsPerComponent 8 /Length {} >>\nstream\n", blob.len() / 1024, blob.len()).into_bytes();
    s.extend_from_slice(&blob);
    s.extend_from_slice(b"\nendstream");
    objs.push(s);
    for _ in 0..pages {
        objs.push(b"<< /Type /Page /Parent 2 0 R /Resources << /XObject << /Im0 3 0 R >> >> >>".to_vec());
    }
    let mut data = b"%PDF-1.7\n".to_vec();
    let mut offsets = Vec::new();
    for (i, o) in objs.iter().enumerate() {
        offsets.push(data.len());
        data.extend_from_slice(format!("{} 0 obj\n", i + 1).as_bytes());
        data.extend_from_slice(o);
        data.extend_from_slice(b"\nendobj\n");
    }
    let xref = data.len();
    data.extend_from_slice(format!("xref\n0 {}\n0000000000 65535 f \n", objs.len() + 1).as_bytes());
    for o in offsets {
        data.extend_from_slice(format!("{o:010} 00000 n \n").as_bytes());
    }
    data.extend_from_slice(format!("trailer\n<< /Size {} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n", objs.len() + 1).as_bytes());
    data
}

fn open_in_place(mobile: &MobileSession, path: &std::path::Path, tag: &str) -> u64 {
    let lent = std::fs::File::open(path).unwrap();
    let working = std::env::temp_dir().join(format!("pdfcraft-in-place-{}-{tag}-working.pdf", std::process::id())).to_string_lossy().into_owned();
    let opened = mobile.begin_open_lent(std::os::fd::AsRawFd::as_raw_fd(&lent), working, None, 0, false, false).unwrap();
    drop(lent);
    opened.document.id
}

#[test]
fn a_comment_on_a_file_opened_in_place_extends_the_mapping_instead_of_copying_it() {
    let path = std::env::temp_dir().join(format!("pdfcraft-in-place-{}.pdf", std::process::id()));
    std::fs::write(&path, big_pdf(3, 2)).unwrap();
    let mobile = MobileSession::new();
    let doc = open_in_place(&mobile, &path, "map");
    let mapped = |m: &MobileSession| m.lock().session().get(DocId(doc)).unwrap().bytes.is_mapped();
    assert!(mapped(&mobile), "a large file opened in place is mapped");
    for k in 0..3 {
        mobile
            .execute("comment_add".into(), json!({
                "doc": doc, "page": 2, "type": "ink", "strokes": [[[20 + k, 20], [90, 90]]],
                "color": "#161616", "opacity": 1.0, "width": 2.0,
            }).to_string())
            .unwrap();
        // the edited bytes are the mapped file with the new revision after it, not a copy
        assert!(mapped(&mobile), "edit {k} copied the whole file");
    }
    assert_eq!(mobile.comments(doc).unwrap().len(), 3);
    mobile.execute("edit_undo".into(), json!({ "doc": doc }).to_string()).unwrap();
    assert_eq!(mobile.comments(doc).unwrap().len(), 2);
    assert!(mobile.render_page(doc, 1, 1.0, None).is_ok());
    let _ = std::fs::remove_file(&path);
}

/// The time of a comment by file size, opened in place (`--ignored --nocapture`, release).
#[test]
#[ignore]
fn edit_time_by_file_size() {
    for mb in [1usize, 50, 100] {
        let path = std::env::temp_dir().join(format!("pdfcraft-big-{mb}.pdf"));
        std::fs::write(&path, big_pdf(869, mb)).unwrap();
        let mobile = MobileSession::new();
        // opened in place, as the app opens a file from My Files
        let id = open_in_place(&mobile, &path, &mb.to_string());
        let mut times = Vec::new();
        for k in 0..4 {
            let t = Instant::now();
            mobile.execute("comment_add".into(), json!({
                "doc": id, "page": 2, "type": "ink", "strokes": [[[20 + k, 20], [90, 90]]],
                "color": "#161616", "opacity": 1.0, "width": 2.0,
            }).to_string()).unwrap();
            times.push(t.elapsed().as_millis());
        }
        eprintln!("{mb} MB: comment_add ms {times:?}");
        let _ = std::fs::remove_file(&path);
    }
}

/// The process's anonymous memory (heap and private maps), in MB: what a full copy of a file adds.
fn anon_mb() -> u64 {
    let status = std::fs::read_to_string("/proc/self/status").unwrap_or_default();
    status.lines().find_map(|l| l.strip_prefix("RssAnon:")).and_then(|v| v.trim().trim_end_matches("kB").trim().parse::<u64>().ok()).unwrap_or(0) / 1024
}

/// Save as the app saves a document opened in place (#654): `doc_save` to the working file,
/// then the new revision appended to the original.
fn save_in_place(mobile: &MobileSession, doc: u64, original: &std::path::Path) -> u128 {
    let t = Instant::now();
    let saved: serde_json::Value = serde_json::from_str(&mobile.execute("doc_save".into(), json!({ "doc": doc }).to_string()).unwrap()).unwrap();
    let elapsed = t.elapsed().as_millis();
    let working = std::fs::read(saved["path"].as_str().unwrap()).unwrap();
    let had = std::fs::metadata(original).unwrap().len() as usize;
    assert!(working.len() > had, "an incremental save appends to what the original has");
    use std::io::Write;
    std::fs::OpenOptions::new().append(true).open(original).unwrap().write_all(&working[had..]).unwrap();
    elapsed
}

fn ink(mobile: &MobileSession, doc: u64, k: i64) {
    mobile
        .execute("comment_add".into(), json!({
            "doc": doc, "page": 2, "type": "ink", "strokes": [[[20 + k, 20], [90, 90]]],
            "color": "#161616", "opacity": 1.0, "width": 2.0,
        }).to_string())
        .unwrap();
}

#[test]
fn saving_a_file_opened_in_place_keeps_it_mapped() {
    let path = std::env::temp_dir().join(format!("pdfcraft-in-place-save-{}.pdf", std::process::id()));
    std::fs::write(&path, big_pdf(3, 2)).unwrap();
    let mobile = MobileSession::new();
    let doc = open_in_place(&mobile, &path, "save");
    let mapped = |m: &MobileSession| {
        let s = m.lock();
        let d = s.session().get(DocId(doc)).unwrap();
        d.bytes.is_mapped() && d.saved_bytes().is_mapped()
    };
    for k in 0..3 {
        ink(&mobile, doc, k);
        // an edit after a save extends the saved mapping too (it copied the book each time, #654)
        assert!(mobile.lock().session().get(DocId(doc)).unwrap().bytes.is_mapped(), "edit {k} copied the whole file");
        save_in_place(&mobile, doc, &path);
        // the saved document is the mapped file with its new revisions after it, not a copy
        assert!(mapped(&mobile), "save {k} copied the whole file");
        // and what was saved is what the original now holds
        assert_eq!(&**mobile.lock().session().get(DocId(doc)).unwrap().saved_bytes(), &std::fs::read(&path).unwrap()[..]);
    }
    assert_eq!(mobile.comments(doc).unwrap().len(), 3);
    assert!(mobile.render_page(doc, 1, 1.0, None).is_ok());
    let _ = std::fs::remove_file(&path);
}

/// Save time and anonymous memory by file size, opened in place (`--ignored --nocapture`, release).
#[test]
#[ignore]
fn save_time_and_memory_by_file_size() {
    for mb in [100usize, 350] {
        let path = std::env::temp_dir().join(format!("pdfcraft-big-save-{mb}.pdf"));
        std::fs::write(&path, big_pdf(869, mb)).unwrap();
        let mobile = MobileSession::new();
        let id = open_in_place(&mobile, &path, &format!("save{mb}"));
        let start = anon_mb();
        let mut lines = Vec::new();
        for k in 0..3 {
            for j in 0..3 {
                ink(&mobile, id, k * 3 + j);
            }
            let edited = anon_mb().saturating_sub(start);
            let ms = save_in_place(&mobile, id, &path);
            lines.push(format!("3 edits: anon +{edited} MB, save {k}: {ms} ms, anon +{} MB", anon_mb().saturating_sub(start)));
        }
        eprintln!("{mb} MB: {}", lines.join("; "));
        let _ = std::fs::remove_file(&path);
    }
}
