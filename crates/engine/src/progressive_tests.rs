use super::*;
use std::sync::{
    Arc,
    atomic::{AtomicBool, Ordering},
};

#[test]
fn reading_stage_preserves_password_authentication_and_final_permissions() {
    for algorithm in [pdfcraft_cos::Algorithm::Aes256, pdfcraft_cos::Algorithm::Aes128] {
        let mut cos = pdfcraft_cos::Document::open(Arc::new(crate::tests::fixture(2))).unwrap();
        cos.set_encryption(&pdfcraft_cos::NewEncryption {
            algorithm,
            user_password: "user",
            owner_password: "owner",
            permissions: 0,
            encrypt_metadata: true,
            seed: [7; 32],
        })
        .unwrap();
        let bytes = Arc::new(pdfcraft_cos::write_full(&cos, &Default::default()).unwrap());
        let mut session = Session::new();
        let context = session.open_context();
        assert!(context.prepare_open("protected.pdf", None, bytes.clone(), None).is_err());
        assert!(context.prepare_open("protected.pdf", None, bytes.clone(), Some("wrong")).is_err());
        for password in ["user", "owner"] {
            let early = context.prepare_reading("protected.pdf".into(), None, bytes.clone(), Some(password), 0).unwrap();
            let full = context.prepare_open("protected.pdf", None, bytes.clone(), Some(password)).unwrap();
            let id = if let Some(early) = early {
                let id = session.commit_open(early);
                let doc = session.get(id).unwrap();
                assert!(doc.preparing() && doc.info.encrypted);
                assert!(!doc.allows_copying() && !doc.allows_printing() && !doc.editable());
                assert!(session.save_bytes(id).is_err());
                assert!(session.finish_reading(id, full));
                id
            } else {
                session.commit_open(full)
            };
            let doc = session.get(id).unwrap();
            assert!(!doc.preparing());
            assert_eq!(doc.allows_copying(), password == "owner");
            session.close(id);
        }
    }
}

#[test]
fn reading_completion_is_cancelled_and_cannot_replace_a_closed_or_ready_document() {
    let mut session = Session::new();
    let bytes = Arc::new(crate::tests::fixture(2));
    let flag = Arc::new(AtomicBool::new(false));
    let context = session.open_context().with_open_cancellation(flag.clone());
    let early = context.prepare_reading("book.pdf".into(), None, bytes.clone(), None, 0).unwrap().unwrap();
    let full = context.prepare_open("book.pdf", None, bytes.clone(), None).unwrap();
    let id = session.commit_open(early);
    session.close(id);
    assert!(flag.load(Ordering::Acquire));
    assert!(!session.finish_reading(id, full));
    assert!(context.prepare_open("book.pdf", None, bytes.clone(), None).is_err());
    assert!(session.docs().is_empty());
    let ready = session.open("ready.pdf", None, bytes.clone(), None).unwrap();
    let another = session.prepare_open("other.pdf", None, bytes, None).unwrap();
    assert!(!session.finish_reading(ready, another));
}

#[test]
fn reading_stage_keeps_xfa_and_repaired_sources_on_the_complete_path() {
    let mut cos = pdfcraft_cos::Document::open(Arc::new(crate::tests::fixture(2))).unwrap();
    let root = cos.root().unwrap();
    let object = pdfcraft_cos::Lexer::new(b"<< /XFA [] /Fields [] >>", 0).object().unwrap();
    cos.update_dict(root, |d| d.set(b"AcroForm".to_vec(), object)).unwrap();
    let xfa = Arc::new(pdfcraft_cos::write_incremental(&cos, &Default::default()).unwrap());
    let context = Session::new().open_context();
    assert!(context.prepare_reading("xfa.pdf".into(), None, xfa, None, 0).unwrap().is_none());
    let mut damaged = crate::tests::fixture(2);
    let offset = damaged.windows(9).rposition(|w| w == b"startxref").unwrap();
    damaged.truncate(offset);
    let damaged = Arc::new(damaged);
    assert!(context.prepare_reading("repaired.pdf".into(), None, damaged.clone(), None, 0).unwrap().is_none());
    assert!(context.prepare_open("repaired.pdf", None, damaged, None).is_ok());
}
