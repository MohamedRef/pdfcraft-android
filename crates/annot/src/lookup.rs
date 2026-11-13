use super::info::{is_comment_subtype, text_value};
use super::types::{FLAG_HIDDEN, FLAG_NO_ROTATE, FLAG_NO_VIEW, Shape};
use super::{AnnotError, Dict, Document, ObjRef, Object, appearance};

// ── pages and /Annots ───────────────────────────────────────────────────────────────────────

/// The page object of each leaf page, in order.
pub fn page_refs(doc: &Document) -> Result<Vec<ObjRef>, AnnotError> {
    let root = doc.root().ok_or(AnnotError::NoPageTree)?;
    let pages = doc.get(root).as_dict().and_then(|d| d.reference(b"Pages")).ok_or(AnnotError::NoPageTree)?;
    Ok(PageWalk::new(doc, pages).collect())
}

/// [`page_refs`] for the reading stage's first look at a document (#235, Android adaptation):
/// the same pages in the same order, with each page-tree node's `/Type` and `/Kids` skimmed
/// from its bytes ([`Document::skim_dict_entries`]) instead of parsing the whole dictionary.
/// A node that cannot be skimmed is read as `page_refs` reads it. For an unedited document only.
pub fn page_refs_skimming(doc: &Document) -> Result<Vec<ObjRef>, AnnotError> {
    let root = doc.root().ok_or(AnnotError::NoPageTree)?;
    let pages = doc.get(root).as_dict().and_then(|d| d.reference(b"Pages")).ok_or(AnnotError::NoPageTree)?;
    let mut walk = PageWalk::new(doc, pages);
    walk.skim = true;
    Ok(walk.collect())
}

/// Depth-first walk over the leaf pages under `top`. It keeps one cursor per open `/Kids` array,
/// never one entry per child, so a child listed many times or shared between nodes is skipped by
/// the seen check without being queued first (`pdfcraft_model::pages` walks the same way).
/// Cycles end at the seen check, a `/Kids` array object shared by several nodes is read once,
/// and at most a million nodes and four million `/Kids` entries are read.
pub(crate) struct PageWalk<'a> {
    doc: &'a Document,
    seen: std::collections::HashSet<ObjRef>,
    pub(crate) pending: Vec<(std::sync::Arc<Object>, usize)>,
    /// `/Kids` entries read so far.
    pub(crate) examined: usize,
    /// Read each node's `/Type` and `/Kids` without parsing the rest ([`page_refs_skimming`]).
    skim: bool,
}

impl<'a> PageWalk<'a> {
    pub(crate) fn new(doc: &'a Document, top: ObjRef) -> Self {
        let top = std::sync::Arc::new(Object::Array(vec![Object::Ref(top)]));
        Self { doc, seen: Default::default(), pending: vec![(top, 0)], examined: 0, skim: false }
    }
}

impl Iterator for PageWalk<'_> {
    type Item = ObjRef;

    fn next(&mut self) -> Option<ObjRef> {
        while let Some((kids, next)) = self.pending.last_mut() {
            let Some(kid) = kids.as_array().and_then(|a| a.get(*next)) else {
                self.pending.pop();
                continue;
            };
            let node = kid.as_ref();
            *next += 1;
            self.examined += 1;
            let Some(node) = node else { continue };
            if self.seen.len() >= 1_000_000 || self.examined > 4_000_000 {
                self.pending.clear();
                return None;
            }
            if !self.seen.insert(node) {
                continue;
            }
            if self.skim
                && let Some(mut entries) = self.doc.skim_dict_entries(node, &[b"Type", b"Kids"])
            {
                // the same decisions as below, on the two values the full dictionary would hold
                let kids = entries.pop().flatten();
                let is_page = entries.pop().flatten().as_ref().and_then(Object::as_name) == Some(b"Page");
                match kids {
                    Some(Object::Ref(array)) if !is_page => {
                        if self.seen.insert(array) {
                            self.pending.push((self.doc.get(array), 0));
                        }
                    }
                    Some(kids) if !is_page => self.pending.push((self.doc.resolve(&kids), 0)),
                    _ => return Some(node),
                }
                continue;
            }
            let obj = self.doc.get(node);
            let Some(d) = obj.as_dict() else { continue };
            match d.get(b"Kids") {
                // A `/Kids` array object shared by several nodes is read under the first: under
                // the others it would only list nodes already seen.
                Some(Object::Ref(array)) if d.name(b"Type") != Some(b"Page") => {
                    if self.seen.insert(*array) {
                        self.pending.push((self.doc.get(*array), 0));
                    }
                }
                Some(kids) if d.name(b"Type") != Some(b"Page") => self.pending.push((self.doc.resolve(kids), 0)),
                _ => return Some(node),
            }
        }
        None
    }
}

pub(crate) fn page_ref(doc: &Document, page: usize) -> Result<ObjRef, AnnotError> {
    page_refs(doc)?.get(page).copied().ok_or(AnnotError::NoSuchPage(page))
}

/// The entries of a page's `/Annots` (references or inline dictionaries).
pub(crate) fn annots(doc: &Document, page: ObjRef) -> Vec<Object> {
    let obj = doc.get(page);
    let Some(a) = obj.as_dict().and_then(|d| d.get(b"Annots")) else { return Vec::new() };
    let list = doc.resolve(a).as_array().cloned().unwrap_or_default();
    #[cfg(test)]
    ANNOTS_ENTRIES_COPIED.with(|n| n.set(n.get() + list.len()));
    list
}

#[cfg(test)]
thread_local! {
    /// Entries copied out of pages' `/Annots` arrays.
    pub(crate) static ANNOTS_ENTRIES_COPIED: std::cell::Cell<usize> = const { std::cell::Cell::new(0) };
}

/// Apple Markup's private copy of a page's markup: on the page and on each of its annotations.
const APPLE_PAGE_MARKUP: [&[u8]; 2] = [b"AAPL:PPK", b"AAPL:PPKHash"];
const APPLE_ANNOT_MARKUP: &[u8] = b"AAPL:AKExtras";

/// Drop Apple Markup's private copy of the page's markup once the annotation list changes. iOS
/// draws Markup annotations from that copy, which no longer matches `/Annots`: iOS 26 showed a
/// Markup signature in its own place and again over a signature added here. Without it, Apple
/// draws every annotation from its appearance, as other viewers do. The drop is recorded in the
/// document's repair log (iOS can no longer re-edit those strokes as live ink).
fn drop_apple_markup(doc: &mut Document, page: ObjRef, list: &mut [Object]) -> Result<(), AnnotError> {
    let mut dropped = false;
    if doc.get(page).as_dict().is_some_and(|d| APPLE_PAGE_MARKUP.iter().any(|k| d.contains(k))) {
        doc.update_dict(page, |d| {
            for k in APPLE_PAGE_MARKUP {
                d.remove(k);
            }
        })?;
        dropped = true;
    }
    for entry in list {
        match entry {
            Object::Dict(d) if d.contains(APPLE_ANNOT_MARKUP) => {
                d.remove(APPLE_ANNOT_MARKUP);
                dropped = true;
            }
            Object::Ref(r) if doc.get(*r).as_dict().is_some_and(|d| d.contains(APPLE_ANNOT_MARKUP)) => {
                doc.update_dict(*r, |d| {
                    d.remove(APPLE_ANNOT_MARKUP);
                })?;
                dropped = true;
            }
            _ => {}
        }
    }
    if dropped {
        doc.note_repair(
            "dropped Apple Markup's private copy of a page's annotations after the page changed; iOS draws them from their appearance instead of as editable ink"
                .into(),
        );
    }
    Ok(())
}

/// Replace a page's `/Annots` after an annotation was added, removed or answered: the point at
/// which Apple Markup's private copy of the page goes stale (see [`drop_apple_markup`]).
pub(crate) fn change_annots(doc: &mut Document, page: ObjRef, mut list: Vec<Object>) -> Result<(), AnnotError> {
    drop_apple_markup(doc, page, &mut list)?;
    Ok(set_annots(doc, page, list)?)
}

/// Replace a page's `/Annots`, writing into the shared array object when it is indirect. An empty
/// list removes a direct `/Annots`.
pub fn set_annots(doc: &mut Document, page: ObjRef, list: Vec<Object>) -> Result<(), pdfcraft_cos::CosError> {
    let existing = doc.get(page).as_dict().and_then(|d| d.reference(b"Annots"));
    match existing {
        Some(r) if doc.get(r).as_array().is_some() => doc.set(r, Object::Array(list)),
        _ => doc.update_dict(page, |d| {
            if list.is_empty() {
                d.remove(b"Annots");
            } else {
                d.set(b"Annots".to_vec(), Object::Array(list));
            }
        })?,
    }
    Ok(())
}

/// Whether the annotation dictionary `d` has a comment `/Subtype` ([`is_comment_subtype`]).
pub(crate) fn is_comment_dict(d: &Dict) -> bool {
    d.name(b"Subtype").is_some_and(|s| is_comment_subtype(&String::from_utf8_lossy(s)))
}

/// The comment annotation at `(page, index)` as an indirect object (inline dictionaries are promoted).
pub(crate) fn annot_ref(doc: &mut Document, page: usize, index: usize) -> Result<(ObjRef, ObjRef), AnnotError> {
    let p = page_ref(doc, page)?;
    let mut list = annots(doc, p);
    let entry = list.get(index).cloned().ok_or(AnnotError::NoSuchAnnotation { page, index })?;
    let r = match entry {
        Object::Ref(r) if doc.get(r).as_dict().is_some_and(is_comment_dict) => r,
        Object::Dict(d) if is_comment_dict(&d) => {
            let r = doc.add(Object::Dict(d));
            if let Some(slot) = list.get_mut(index) {
                *slot = Object::Ref(r);
            }
            set_annots(doc, p, list)?;
            r
        }
        _ => return Err(AnnotError::NoSuchAnnotation { page, index }),
    };
    Ok((p, r))
}

pub(crate) fn annot_dict(doc: &Document, r: ObjRef) -> Dict {
    doc.get(r).as_dict().cloned().unwrap_or_default()
}

/// A Fill & Sign item: typed text, a check/cross/dot/line, or a typed, drawn or image signature.
///
/// New items carry `/PCFillSign`. Older files are recognised from the dictionaries PdfCraft
/// already writes (`/IT /FreeTextTypeWriter`, stamp `/Name`, drawn ink whose subject is
/// "Signature").
pub fn is_fill_sign(doc: &Document, d: &Dict) -> bool {
    if matches!(d.get(b"PCFillSign").map(|o| doc.resolve(o)).as_deref(), Some(Object::Bool(true))) {
        return true;
    }
    let name_is = |key: &[u8], expected: &[u8]| d.get(key).is_some_and(|o| doc.resolve(o).as_name() == Some(expected));
    if name_is(b"Subtype", b"FreeText") && name_is(b"IT", b"FreeTextTypeWriter") {
        return true;
    }
    if name_is(b"Subtype", b"Stamp") {
        if matches!(d.name(b"Name"), Some(b"PCCheck" | b"PCCross" | b"PCDot" | b"PCLine" | b"PCTypedSignature")) {
            return true;
        }
        if matches!(d.name(b"Name"), Some(b"PCCustomSignature" | b"PCCustomInitials"))
            && matches!(d.get(b"PCPictureImage").map(|o| doc.resolve(o)).as_deref(), Some(Object::Bool(true)))
        {
            return true;
        }
    }
    name_is(b"Subtype", b"Ink") && text_value(doc, d, b"Subj").as_deref() == Some("Signature")
}

pub(crate) fn annotation_flags(doc: &Document, d: &Dict) -> i64 {
    d.get(b"F").and_then(|f| doc.resolve(f).as_int()).unwrap_or(0)
}

/// Whether the renderer keeps the annotation upright on a turned page: the NoRotate flag, with
/// `/F` read as the renderer reads it (a real truncated, a value that isn't a `u32` no flags).
pub(crate) fn drawn_upright(doc: &Document, d: &Dict) -> bool {
    let f = match d.get(b"F").map(|f| doc.resolve(f)).as_deref() {
        Some(Object::Int(i)) => *i,
        Some(Object::Real(r)) => *r as i64,
        _ => 0,
    };
    u32::try_from(f).is_ok_and(|f| i64::from(f) & FLAG_NO_ROTATE != 0)
}

/// Whether any page has a Fill & Sign annotation that flatten would bake in (not hidden).
pub fn has_visible_fill_sign(doc: &Document) -> bool {
    let Ok(pages) = page_refs(doc) else { return false };
    for page in pages {
        for entry in annots(doc, page) {
            let obj = doc.resolve(&entry);
            let Some(d) = obj.as_dict() else { continue };
            let flags = annotation_flags(doc, d);
            if flags & (FLAG_HIDDEN | FLAG_NO_VIEW) != 0 {
                continue;
            }
            if is_fill_sign(doc, d) {
                return true;
            }
        }
    }
    false
}

pub(crate) fn fill_sign_shape(shape: &Shape) -> bool {
    match shape {
        Shape::Typewriter { .. } | Shape::Mark { .. } | Shape::Signature { .. } | Shape::TypedSignature { .. } => true,
        Shape::CustomStamp { name, image: true, .. } => name == "Signature" || name == "Initials",
        _ => false,
    }
}

/// The embedded image of a Fill & Sign image signature or initials (0-based target).
/// Other stamps have appearances that can't be represented by this image alone.
pub fn signature_image(doc: &Document, page: usize, index: usize) -> Result<Option<ObjRef>, AnnotError> {
    let p = page_ref(doc, page)?;
    let list = annots(doc, p);
    let entry = list.get(index).ok_or(AnnotError::NoSuchAnnotation { page, index })?;
    let obj = doc.resolve(entry);
    let Some(d) = obj.as_dict() else { return Ok(None) };
    Ok((d.name(b"Subtype") == Some(b"Stamp")
        && matches!(d.name(b"Name"), Some(b"PCCustomSignature" | b"PCCustomInitials"))
        && matches!(d.get(b"PCPictureImage"), Some(Object::Bool(true))))
    .then(|| d.reference(b"PCPicture"))
    .flatten())
}

/// The page `/Rotate` the picture of the image signature at `(page, index)` is drawn turned back
/// by, so it reads upright on a page shown that way: 0 for one added to an unturned page, or by
/// another app. The picture appears turned by the page's current rotation less this.
pub fn picture_rotation(doc: &Document, page: usize, index: usize) -> Result<i64, AnnotError> {
    let p = page_ref(doc, page)?;
    let list = annots(doc, p);
    let entry = list.get(index).ok_or(AnnotError::NoSuchAnnotation { page, index })?;
    Ok(doc.resolve(entry).as_dict().map_or(0, appearance::picture_turn))
}

/// Leave page `page` of (a copy of) a document showing only its annotation at `index`, without
/// the page's content or other annotations, so a render draws that annotation alone: to lay it
/// over the page while it is dragged. `false`, with `doc` unchanged, when the annotation has no
/// normal appearance (`/AP /N`) for that render to draw.
pub fn isolate_annotation(doc: &mut Document, page: usize, index: usize) -> Result<bool, AnnotError> {
    isolate_annotations(doc, page, &[index])
}

/// [`isolate_annotation`] for several annotations of one page: those of them with a normal
/// appearance stay, in their order on the page. `false`, with `doc` unchanged, when none has one.
/// An index that isn't one of the page's annotations is an error even when the others are, and
/// leaves `doc` unchanged too.
pub fn isolate_annotations(doc: &mut Document, page: usize, indices: &[usize]) -> Result<bool, AnnotError> {
    let p = page_ref(doc, page)?;
    let list = annots(doc, p);
    let mut kept = Vec::new();
    for (index, entry) in list.iter().enumerate() {
        if !indices.contains(&index) {
            continue;
        }
        let obj = doc.resolve(entry);
        if obj.as_dict().and_then(|d| d.get(b"AP")).is_some_and(|ap| doc.resolve(ap).as_dict().is_some_and(|ap| ap.contains(b"N"))) {
            kept.push(entry.clone());
        }
    }
    if let Some(&index) = indices.iter().find(|&&i| i >= list.len()) {
        return Err(AnnotError::NoSuchAnnotation { page, index });
    }
    if kept.is_empty() {
        return Ok(false);
    }
    set_annots(doc, p, kept)?;
    doc.update_dict(p, |d| {
        d.remove(b"Contents");
    })?;
    Ok(true)
}

/// #235: the reading stage's skimming walk finds the same pages, in the same order, as the
/// full one, on the shapes page trees take, including damaged and hostile ones.
#[cfg(test)]
mod skim_tests {
    use super::{page_refs, page_refs_skimming};
    use pdfcraft_cos::{Document, SaveOptions, write_full};
    use std::sync::Arc;

    /// A file of `objects` (numbered from 1), its table pointing at `offsets` moved by `shift`.
    fn pdf(objects: &[String], shift: &[(usize, isize)]) -> Vec<u8> {
        let mut out = b"%PDF-1.7\n".to_vec();
        let mut offsets = Vec::new();
        for (i, o) in objects.iter().enumerate() {
            offsets.push(out.len());
            out.extend_from_slice(format!("{} 0 obj\n{o}\nendobj\n", i + 1).as_bytes());
        }
        for (i, d) in shift {
            offsets[*i] = offsets[*i].saturating_add_signed(*d);
        }
        let xref = out.len();
        let mut table = format!("xref\n0 {}\n0000000000 65535 f \n", objects.len() + 1);
        for off in offsets {
            table.push_str(&format!("{off:010} 00000 n \n"));
        }
        table.push_str(&format!("trailer\n<< /Size {} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n", objects.len() + 1));
        out.extend_from_slice(table.as_bytes());
        out
    }

    fn assert_same(bytes: Vec<u8>, what: &str) {
        let doc = Document::open(Arc::new(bytes)).expect("opens");
        let full = page_refs(&doc).ok();
        // a fresh document, so nothing the first walk cached is reused
        let skimmed = page_refs_skimming(&Document::open(doc.bytes().clone()).expect("reopens")).ok();
        assert_eq!(skimmed, full, "{what}");
        assert_eq!(page_refs_skimming(&doc).ok(), full, "{what} (after the full walk)");
    }

    /// Catalog, root node, then `nodes` intermediate nodes of `per` pages each; each page has
    /// inline links and resources. `page` is the text of each page dictionary's extra entries.
    fn tree(nodes: usize, per: usize, page: &str) -> Vec<String> {
        let first_node = 3;
        let first_page = first_node + nodes;
        let mut o = vec!["<< /Type /Catalog /Pages 2 0 R >>".to_string()];
        let kids: String = (0..nodes).map(|n| format!("{} 0 R ", first_node + n)).collect();
        o.push(format!("<< /Type /Pages /Count {} /Kids [{kids}] >>", nodes * per));
        for n in 0..nodes {
            let kids: String = (0..per).map(|p| format!("{} 0 R ", first_page + n * per + p)).collect();
            o.push(format!("<< /Type /Pages /Parent 2 0 R /Count {per} /Kids [{kids}] >>"));
        }
        for n in 0..nodes {
            for p in 0..per {
                o.push(format!(
                    "<< /Type /Page /Parent {} 0 R /MediaBox [0 0 612 792] {page} /Annots [<< /Subtype /Link /Rect [0 0 1 1] /A << /S /URI /URI (u\\)({n}/{p}) >> >>] /Resources << /Font << /F1 << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> >> >> >>",
                    first_node + n
                ));
            }
        }
        o
    }

    #[test]
    fn ordinary_trees_plain_and_in_object_streams() {
        let plain = pdf(&tree(4, 5, ""), &[]);
        assert_same(plain.clone(), "plain");
        let doc = Document::open(Arc::new(plain)).unwrap();
        let packed = write_full(&doc, &SaveOptions { object_streams: true, ..SaveOptions::default() }).unwrap();
        // both shapes are skimmed, not left to the full reader
        let node = pdfcraft_cos::ObjRef::new(3, 0);
        assert!(doc.skim_dict_entries(node, &[b"Type"]).is_some());
        assert!(Document::open(Arc::new(packed.clone())).unwrap().skim_dict_entries(node, &[b"Type"]).is_some());
        assert_same(packed, "object streams");
    }

    #[test]
    fn odd_and_damaged_nodes() {
        let cases: Vec<(&str, Vec<String>, Vec<(usize, isize)>)> = vec![
            // a page with a second /Type: the last one counts, here a leaf
            ("duplicate type", tree(2, 2, "/Type /Pages /Kids [1 0 R]"), vec![]),
            // /Type by reference is not a name: the node with /Kids is a node
            ("type by reference", {
                let mut o = tree(1, 2, "");
                o[2] = o[2].replace("/Type /Pages", "/Type 1 0 R");
                o
            }, vec![]),
            // /Kids as a reference to an array, shared by two nodes, and a cycle back to the root
            ("shared kids array", vec![
                "<< /Type /Catalog /Pages 2 0 R >>".into(),
                "<< /Type /Pages /Kids [3 0 R 4 0 R 2 0 R 4 0 R] >>".into(),
                "<< /Type /Pages /Kids 5 0 R >>".into(),
                "<< /Type /Pages /Kids 5 0 R >>".into(),
                "[6 0 R 7 0 R 2 0 R 99 0 R (x) 6 0 R]".into(),
                "<< /Type /Page /Contents << /Length 3 >> >>".into(),
                "<< /Type /Page /Junk [1 bad 2] >>".into(),
            ], vec![]),
            // a node that is a stream, one that is not a dictionary, one unreadable
            ("streams and junk", vec![
                "<< /Type /Catalog /Pages 2 0 R >>".into(),
                "<< /Type /Pages /Kids [3 0 R 4 0 R 5 0 R 6 0 R] >>".into(),
                "<< /Type /Pages /Kids [6 0 R] /Length 2 >>\nstream\nab\nendstream".into(),
                "[/Type /Page]".into(),
                "<< /Type /Page /X (unterminated".into(),
                "<< /Type /Page >>".into(),
            ], vec![]),
            // table offsets a little off: the full reader's repairs are used for those
            ("offsets off", tree(2, 3, ""), vec![(3, 2), (5, -1), (6, 40)]),
            // a page whose /Kids is inline and empty, and a node without /Type
            ("no type", vec![
                "<< /Type /Catalog /Pages 2 0 R >>".into(),
                "<< /Kids [3 0 R 4 0 R] >>".into(),
                "<< /Kids [] >>".into(),
                "<< /Type /Page /Kids [] >>".into(),
            ], vec![]),
        ];
        for (what, objects, shift) in cases {
            assert_same(pdf(&objects, &shift), what);
        }
    }
}
