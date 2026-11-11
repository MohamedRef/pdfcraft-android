//! Opening a document: preparing it away from the session, then registering it.

use std::sync::{
    Arc, OnceLock,
    atomic::{AtomicBool, Ordering},
};

use pdfcraft_cos::{SaveOptions, write_incremental};
use pdfcraft_render::{DocInfo, OpenError, OpenTrace, RenderConfig, RenderPool, inspect, inspect_with_trace};

use crate::document::display_bytes_collect_fields;
use crate::history::{Editor, Keys, TextBlocksCache};
use crate::{
    AppearanceCache, DocId, Document, Session, StatusCaptions, TrustStore, XFA_SCRIPTS_OFF, XfaLayout, display_config, guard, info_fields, js,
    note_warnings, print_preview_layers, recovered_render_password, render_threads, same_pages, signatures_of, xfa, xfa_layout,
    xfa_values_from_datasets,
};

/// Immutable session state needed while a document is prepared away from its owning thread.
#[derive(Clone)]
pub struct OpenContext {
    now: Option<i64>,
    js_off: bool,
    trust: Arc<TrustStore>,
    captions: Arc<StatusCaptions>,
    xfa_time_scale: u32,
    trace: OpenTrace,
    cancelled: Option<Arc<AtomicBool>>,
}

/// A fully prepared document that has not yet been registered in a session.
pub struct PreparedDocument {
    document: Document,
}

struct DocumentOpenData<'a> {
    name: String,
    path: Option<String>,
    bytes: Arc<Vec<u8>>,
    info: DocInfo,
    structure: Option<Result<Result<pdfcraft_cos::Document, pdfcraft_cos::CosError>, Box<dyn std::any::Any + Send>>>,
    render_password: Option<String>,
    password: Option<&'a str>,
    xfa: Option<XfaLayout>,
    reading: Option<ReadingSource>,
}

impl OpenContext {
    /// Attach request-local diagnostics without changing any open or editing behavior.
    pub fn with_open_trace(mut self, trace: OpenTrace) -> Self {
        self.trace = trace;
        self
    }

    pub fn with_open_cancellation(mut self, cancelled: Arc<AtomicBool>) -> Self {
        self.cancelled = Some(cancelled);
        self
    }

    fn check_cancelled(&self) -> Result<(), OpenError> {
        if self.cancelled.as_ref().is_some_and(|c| c.load(Ordering::Acquire)) {
            Err(OpenError::Invalid("opening was cancelled".into()))
        } else {
            Ok(())
        }
    }

    /// Try a conservative read-only stage. None means the existing complete open is required.
    /// Exact geometry is retained; no absence of forms or signatures is inferred from the root.
    pub fn prepare_reading(
        &self,
        name: String,
        path: Option<String>,
        bytes: Arc<Vec<u8>>,
        password: Option<&str>,
        initial_page: usize,
    ) -> Result<Option<PreparedDocument>, OpenError> {
        self.check_cancelled()?;
        let fallback = |reason: &'static str| {
            self.trace.event(reason);
            Ok(None)
        };
        guard(|| {
            let Ok(cos) = self.trace.measure("native_preview_cos", || pdfcraft_cos::Document::open_with_password(bytes.clone(), password)) else {
                return fallback("native_reading_fallback_parser");
            };
            if !cos.repair_log().is_empty() {
                return fallback("native_reading_fallback_repairs");
            }
            let Some(root) = cos.root() else { return fallback("native_reading_fallback_catalog") };
            let catalog = cos.get(root);
            let Some(catalog) = catalog.as_dict() else { return fallback("native_reading_fallback_catalog") };
            let field_pages = match reading_field_pages(&cos, catalog) {
                Ok(pages) => pages,
                Err(reason) => return fallback(reason),
            };
            let Ok(pages) = pdfcraft_annot::page_refs(&cos) else { return fallback("native_reading_fallback_page_tree") };
            if pages.is_empty() {
                return fallback("native_reading_fallback_page_tree");
            }
            if field_pages.iter().any(|p| !pages.contains(p)) {
                return fallback("native_reading_fallback_widget_location");
            }
            let initial_page = if initial_page < pages.len() { initial_page } else { 0 };
            let cancelled = self.cancelled.clone().unwrap_or_else(|| Arc::new(AtomicBool::new(false)));
            let reading = ReadingSource { cos, pages, field_pages, cancelled };
            if let Some(reason) = reading.page_block_reason(initial_page) {
                return fallback(reason);
            }
            if !reading.cos.repair_log().is_empty() {
                return fallback("native_reading_fallback_repairs");
            }
            let mut info = match pdfcraft_render::inspect_geometry_with_trace(bytes.clone(), password, &self.trace) {
                Ok(info) if !info.pages.is_empty() && info.warnings.is_empty() => info,
                _ => return fallback("native_reading_fallback_geometry"),
            };
            if reading.pages.len() != info.pages.len() {
                return fallback("native_reading_fallback_page_count");
            }
            info.encrypted = reading.cos.security().is_some();
            self.check_cancelled()?;
            let document = Session::build_document(
                self,
                DocumentOpenData {
                    name,
                    path,
                    bytes,
                    info,
                    structure: None,
                    render_password: password.map(str::to_owned),
                    password,
                    xfa: None,
                    reading: Some(reading),
                },
            )?;
            Ok(Some(PreparedDocument { document }))
        })
        .unwrap_or_else(|_| fallback("native_reading_fallback_panic"))
    }

    /// Prepare a document without mutating the session that supplied this context.
    pub fn prepare_open(
        &self,
        name: impl Into<String>,
        path: Option<String>,
        bytes: Arc<Vec<u8>>,
        password: Option<&str>,
    ) -> Result<PreparedDocument, OpenError> {
        self.check_cancelled()?;
        let name = name.into();
        guard(|| Session::prepare_open_unguarded(self, name, path, bytes, password))
            .unwrap_or_else(|m| Err(OpenError::Invalid(format!("reading it failed unexpectedly ({m})"))))
    }
}

impl Session {
    /// Snapshot the session state that affects open-time scripts, dates and signature trust.
    pub fn open_context(&self) -> OpenContext {
        OpenContext {
            now: self.now(),
            js_off: self.js_off,
            trust: self.trust.clone(),
            captions: self.captions.clone(),
            xfa_time_scale: self.xfa_time_scale.unwrap_or(1),
            trace: OpenTrace::default(),
            cancelled: None,
        }
    }

    /// Prepare a document without adding it to this session.
    pub fn prepare_open(
        &self,
        name: impl Into<String>,
        path: Option<String>,
        bytes: Arc<Vec<u8>>,
        password: Option<&str>,
    ) -> Result<PreparedDocument, OpenError> {
        self.open_context().prepare_open(name, path, bytes, password)
    }

    /// Register a prepared document. This is the only open step that mutates the session.
    pub fn commit_open(&mut self, prepared: PreparedDocument) -> DocId {
        self.next_id += 1;
        let id = DocId(self.next_id);
        let mut document = prepared.document;
        document.id = id;
        // Trust or the interface language may have changed while it was prepared on another
        // thread.
        if !Arc::ptr_eq(&document.trust, &self.trust) {
            document.captions = self.captions.clone();
            Self::revalidate(&mut document, self.trust.clone());
        } else if *document.captions != *self.captions {
            Self::draw_captions(&mut document, self.captions.clone());
        }
        self.docs.push(document);
        id
    }

    /// Replace only this still-open reading stage, keeping its id and viewport identity.
    pub fn finish_reading(&mut self, id: DocId, prepared: PreparedDocument) -> bool {
        let Some(index) = self.docs.iter().position(|d| d.id == id && d.reading.as_ref().is_some_and(|r| !r.cancelled.load(Ordering::Acquire)))
        else {
            return false;
        };
        let mut document = prepared.document;
        if document.preparing() || !Arc::ptr_eq(&document.bytes, &self.docs[index].bytes) {
            return false;
        }
        document.id = id;
        document.generation = self.docs[index].generation.wrapping_add(1);
        if !Arc::ptr_eq(&document.trust, &self.trust) {
            document.captions = self.captions.clone();
            Self::revalidate(&mut document, self.trust.clone());
        } else if *document.captions != *self.captions {
            Self::draw_captions(&mut document, self.captions.clone());
        }
        self.docs[index] = document;
        true
    }

    /// Synchronous convenience API used by the CLI and automation.
    pub fn open(&mut self, name: impl Into<String>, path: Option<String>, bytes: Arc<Vec<u8>>, password: Option<&str>) -> Result<DocId, OpenError> {
        let prepared = self.prepare_open(name, path, bytes, password)?;
        Ok(self.commit_open(prepared))
    }

    fn prepare_open_unguarded(
        context: &OpenContext,
        name: String,
        path: Option<String>,
        bytes: Arc<Vec<u8>>,
        password: Option<&str>,
    ) -> Result<PreparedDocument, OpenError> {
        // Android adaptation (#212): the editing parser and the renderer's inspection read the
        // same bytes independently, so they run side by side. Each repairs a damaged file on its
        // own, which took seconds one after the other on a 350 MB file.
        let open_cos = || {
            context
                .trace
                .measure("native_cos_open", || std::panic::catch_unwind(|| pdfcraft_cos::Document::open_with_password(bytes.clone(), password)))
        };
        #[cfg(not(target_arch = "wasm32"))]
        let (cos, inspected) = std::thread::scope(|s| {
            let cos = s.spawn(open_cos);
            let inspected = context.trace.measure("native_inspect", || inspect_with_trace(bytes.clone(), password, &context.trace));
            (cos.join().unwrap_or_else(Err), inspected)
        });
        #[cfg(target_arch = "wasm32")]
        let (cos, inspected) = (open_cos(), inspect_with_trace(bytes.clone(), password, &context.trace));
        // The renderer authenticates on its own. It cannot use the owner password of R2–R4
        // files, so give it the user password that owner authentication recovers.
        let (info, render_password) = match inspected {
            Ok(info) => (info, password.map(str::to_owned)),
            Err(OpenError::WrongPassword) => {
                let user = match &cos {
                    Ok(Ok(d)) => recovered_render_password(d),
                    _ => None,
                };
                let user = user.ok_or(OpenError::WrongPassword)?;
                (inspect_with_trace(bytes.clone(), Some(&user), &context.trace)?, Some(user))
            }
            Err(e) => return Err(e),
        };
        context.check_cancelled()?;
        // A dynamic XFA form is a shell around an XML template; lay the template out into real
        // pages and fields so the rest of the engine works on it. The original bytes stay: the
        // laid-out form is one appended revision.
        let opts = SaveOptions { mod_date: context.now.map(pdfcraft_cos::pdf_date), ..SaveOptions::default() };
        // Write `work` as one more revision and reopen it: what the document then is. The notes
        // about damage repaired while reading the file stay with it (Document Properties), once.
        let rebase = |work: &pdfcraft_cos::Document| -> Result<(Arc<Vec<u8>>, DocInfo, pdfcraft_cos::Document), String> {
            let new_bytes = write_incremental(work, &opts).map(Arc::new).map_err(|e| e.to_string())?;
            let new_info = inspect(new_bytes.clone(), render_password.as_deref()).map_err(|e| e.to_string())?;
            let mut new_cos = pdfcraft_cos::Document::open_with_password(new_bytes.clone(), password).map_err(|e| e.to_string())?;
            for line in work.repair_log() {
                if !new_cos.repair_log().contains(line) {
                    new_cos.note_repair(line.clone());
                }
            }
            Ok((new_bytes, new_info, new_cos))
        };
        let (bytes, info, cos, xfa) = match (info.xfa, cos) {
            (Some(pdfcraft_render::Xfa::Dynamic), Ok(Ok(cos))) if pdfcraft_xfa::existing_layout(&cos).is_none() => {
                let mut work = cos.clone();
                let laid_out = guard(|| xfa_layout(&mut work))
                    .unwrap_or_else(|m| Err(format!("laying it out failed unexpectedly ({m})")))
                    .and_then(|report| rebase(&work).map(|(b, i, c)| (b, i, c, report)));
                match laid_out {
                    Ok((b, i, c, report)) => (b, i, Ok(Ok(c)), Some(report)),
                    Err(e) => {
                        let mut info = info;
                        info.warnings.push(format!("This dynamic XFA form could not be laid out: {e}"));
                        (bytes, info, Ok(Ok(cos)), None)
                    }
                }
            }
            // A static XFA form, or a dynamic one laid out earlier: its datasets may hold values
            // filled in by another viewer since; give the fields those values.
            (Some(_), Ok(Ok(cos))) => {
                let report = pdfcraft_xfa::existing_layout(&cos);
                let mut work = cos.clone();
                let synced =
                    guard(|| xfa_values_from_datasets(&mut work)).unwrap_or_else(|m| Err(format!("reading its data failed unexpectedly ({m})")));
                match synced {
                    Ok(names) if names.is_empty() => (bytes, info, Ok(Ok(cos)), report),
                    Ok(names) => match rebase(&work) {
                        Ok((b, mut i, c)) => {
                            // The fields were rewritten from the XFA data: say so, and which.
                            let shown: Vec<&str> = names.iter().take(5).map(String::as_str).collect();
                            let more = if names.len() > shown.len() { format!(" and {} more", names.len() - shown.len()) } else { String::new() };
                            i.warnings.push(format!(
                                "{} form field value{} were taken from this form's XFA data (filled in by another viewer): {}{more}",
                                names.len(),
                                if names.len() == 1 { "" } else { "s" },
                                shown.join(", ")
                            ));
                            (b, i, Ok(Ok(c)), report)
                        }
                        Err(e) => {
                            let mut info = info;
                            info.warnings.push(format!("The values in this form's XFA data could not be applied: {e}"));
                            (bytes, info, Ok(Ok(cos)), report)
                        }
                    },
                    Err(e) => {
                        let mut info = info;
                        info.warnings.push(format!("The values in this form's XFA data could not be applied: {e}"));
                        (bytes, info, Ok(Ok(cos)), report)
                    }
                }
            }
            (_, cos) => (bytes, info, cos, None),
        };
        // A laid-out XFA form: its initialize and calculate scripts run now, as on opening in
        // Acrobat; what they change is one more revision, still nothing to save.
        let mut script_output = js::JsOutput::default();
        let mut ran_away = false;
        let xfa_template = match (&xfa, &cos) {
            (Some(_), Ok(Ok(c))) => xfa::template(c),
            _ => None,
        };
        let (bytes, info, cos) = match (&xfa, cos) {
            (Some(_), Ok(Ok(cos))) if !context.js_off => match xfa_template.clone() {
                Some(tpl) => {
                    let mut work = cos.clone();
                    let pages = info.pages.len();
                    let ran = guard(|| {
                        let mut run = xfa::XfaRun {
                            page_count: pages,
                            out: &mut script_output,
                            datasets: None,
                            ran_away: false,
                            time_scale: context.xfa_time_scale,
                        };
                        let changes = xfa::on_open(&mut work, &tpl, &mut run);
                        if run.ran_away {
                            ran_away = true;
                        }
                        changes
                    })
                    .unwrap_or_else(|m| Err(format!("its scripts failed unexpectedly ({m})")));
                    match ran {
                        Ok(changes) if work.is_modified() => match rebase(&work) {
                            Ok((b, mut i, c)) => {
                                // The file as saved differs from what opening shows: say so.
                                if !changes.is_empty() {
                                    i.warnings.push(format!(
                                        "This form's scripts changed it on opening ({}); saving keeps those changes",
                                        changes.describe()
                                    ));
                                }
                                (b, i, Ok(Ok(c)))
                            }
                            Err(e) => {
                                script_output.errors.push(format!("The form's scripts could not be applied: {e}"));
                                (bytes, info, Ok(Ok(cos)))
                            }
                        },
                        Ok(_) => (bytes, info, Ok(Ok(cos))),
                        Err(e) => {
                            script_output.errors.push(format!("The form's scripts could not run: {e}"));
                            (bytes, info, Ok(Ok(cos)))
                        }
                    }
                }
                None => (bytes, info, Ok(Ok(cos))),
            },
            (_, cos) => (bytes, info, cos),
        };
        // The catalog leads to no page, but the reader found pages by scanning: gather the page
        // objects into a page tree as one more revision, or page edits address none of them
        // (#1656). Kept only when the reader then shows the same pages, and never on a signed
        // document, where writing the repair could rewrite the file under its signatures.
        let (bytes, info, cos) = match cos {
            Ok(Ok(cos)) if !info.pages.is_empty() && pdfcraft_organize::page_count(&cos).unwrap_or(0) == 0 => {
                let mut work = cos.clone();
                let signed = || pdfcraft_sign::pdf::list(&cos, &bytes, &context.trust).iter().any(|s| s.signed);
                let rebuilt = match pdfcraft_organize::recover_page_tree(&mut work) {
                    Ok(n) if n > 0 && !signed() => rebase(&work).ok().filter(|(_, i, _)| same_pages(&i.pages, &info.pages)),
                    _ => None,
                };
                match rebuilt {
                    Some((b, i, mut c)) => {
                        c.note_repair(format!("the page tree is missing; its {} pages were found by scanning the file", i.pages.len()));
                        (b, i, Ok(Ok(c)))
                    }
                    None => (bytes, info, Ok(Ok(cos))),
                }
            }
            cos => (bytes, info, cos),
        };
        // XFA notes survive the document being re-read after edits.
        let xfa_warnings: Vec<String> = if info.xfa.is_some() { info.warnings.clone() } else { Vec::new() };
        let mut document = Self::build_document(
            context,
            DocumentOpenData { name, path, bytes, info, structure: Some(cos), render_password, password, xfa, reading: None },
        )?;
        // A script that ran away on open turns the form's scripts off for this document.
        document.xfa_template = if ran_away { None } else { xfa_template };
        document.js_output.append(script_output);
        if ran_away {
            document.xfa_scripts_off = true;
            document.js_output.errors.push(XFA_SCRIPTS_OFF.into());
        }
        note_warnings(&mut document.xfa_warnings, &xfa_warnings);
        Ok(PreparedDocument { document })
    }

    /// The last step of opening: build the document record and register it.
    fn build_document(context: &OpenContext, data: DocumentOpenData<'_>) -> Result<Document, OpenError> {
        let DocumentOpenData { name, path, bytes, mut info, structure: cos, render_password, password, xfa, reading } = data;
        let mut config = RenderConfig { password: render_password.as_deref().map(Arc::from), ..Default::default() };
        let (mut editor, read_only_reason) = match cos {
            Some(Ok(Ok(cos))) => {
                let keys = Keys { render: render_password.clone(), reopen: password.map(str::to_owned) };
                let saved = cos.bytes().clone();
                (
                    Some(Editor {
                        cos,
                        saved,
                        saved_keys: keys.clone(),
                        unsaved_file: false,
                        undo: Vec::new(),
                        redo: Vec::new(),
                        keys,
                        appearances: AppearanceCache::default(),
                        field_appearances: pdfcraft_forms::FieldAppearanceCache::default(),
                        text_blocks: TextBlocksCache::default(),
                    }),
                    None,
                )
            }
            Some(Ok(Err(e))) => (None, Some(e.to_string())),
            None => (None, Some("Preparing editing and signature status".into())),
            Some(Err(_)) => (None, Some("the document structure could not be read for editing".into())),
        };
        context.check_cancelled()?;
        let sig_cache = Arc::new(pdfcraft_sign::DigestCache::default());
        let mut signatures = if reading.is_some() {
            Vec::new()
        } else {
            context.trace.measure("native_signatures", || {
                editor.as_ref().map(|e| signatures_of(&e.cos, &bytes, &context.trust, &sig_cache)).unwrap_or_default()
            })
        };
        context.check_cancelled()?;
        // The inspection reads only `/Info`; a file stating its title or author only in XMP still
        // shows them (#2373).
        if let Some(e) = &editor {
            for (field, value) in context.trace.measure("native_metadata", || info_fields(&mut info, &e.cos)) {
                if field.is_none() {
                    *field = value;
                }
            }
        }
        // The print preview draws the layers as they print.
        config.print_layers = context.trace.measure("native_print_layers", || print_preview_layers(editor.as_ref().map(|e| &e.cos), &info.layers));
        let mut discovered_fields = None;
        let display = if reading.is_some() {
            bytes.clone()
        } else {
            context.trace.measure("native_display", || {
                display_bytes_collect_fields(
                    editor.as_mut(),
                    &bytes,
                    &mut signatures,
                    &context.captions,
                    Some(&mut discovered_fields),
                    &context.trace,
                )
            })
        };
        context.check_cancelled()?;
        let renderer = context.trace.measure("native_render_pool", || RenderPool::new(display.clone(), render_threads(), display_config(&config)));
        let (form, adopted) = editor
            .as_ref()
            .map(|e| {
                let mut fields = context
                    .trace
                    .measure("native_form_fields", || discovered_fields.take().unwrap_or_else(|| pdfcraft_forms::fields_with_adopted(&e.cos)));
                xfa::mark_script_buttons(&e.cos, &mut fields.items);
                (fields.items, fields.adopted_page_fields)
            })
            .unwrap_or_default();
        // Fields the file lists only as page widgets are adopted leniently; the file says so.
        if adopted > 0
            && let Some(e) = &mut editor
        {
            let line = if adopted == 1 {
                "the form's /Fields list omits 1 field; it was read from the page annotations".to_string()
            } else {
                format!("the form's /Fields list omits {adopted} fields; they were read from the page annotations")
            };
            e.cos.note_repair(line);
        }
        Ok(Document {
            id: DocId(0),
            name,
            path,
            bytes,
            display,
            info,
            reading,
            renderer,
            password: render_password,
            dirty: false,
            generation: 0,
            snapshot_generation: 0,
            read_only_reason,
            form: Arc::new(form),
            marker_scan: OnceLock::new(),
            links: OnceLock::new(),
            signatures: Arc::new(signatures),
            trust: context.trust.clone(),
            captions: context.captions.clone(),
            sig_cache,
            editor,
            config,
            js_output: Default::default(),
            xfa,
            xfa_template: None,
            xfa_scripts_off: false,
            fonts_stale: false,
            xfa_warnings: Vec::new(),
            edit_notices: Vec::new(),
        })
    }
}

/// Immutable source used only to decide whether a requested provisional page is safe to draw.
pub(crate) struct ReadingSource {
    cos: pdfcraft_cos::Document,
    pages: Vec<pdfcraft_cos::ObjRef>,
    field_pages: std::collections::HashSet<pdfcraft_cos::ObjRef>,
    pub(crate) cancelled: Arc<AtomicBool>,
}

impl ReadingSource {
    pub(crate) fn page_ready(&self, page: usize) -> bool {
        self.page_block_reason(page).is_none()
    }

    fn page_block_reason(&self, page: usize) -> Option<&'static str> {
        let Some(reference) = self.pages.get(page) else { return Some("native_reading_fallback_page_tree") };
        if self.field_pages.contains(reference) {
            return Some("native_reading_fallback_initial_page_form");
        }
        let object = self.cos.get(*reference);
        let Some(page) = object.as_dict() else { return Some("native_reading_fallback_page_tree") };
        let Some(annots) = page.get(b"Annots") else { return None };
        let annots = self.cos.resolve(annots);
        let Some(annots) = annots.as_array() else { return Some("native_reading_fallback_annotations") };
        if annots.len() > 4096 || !annots.iter().all(|a| self.cos.resolve(a).as_dict().is_some_and(|d| d.name(b"Subtype") == Some(b"Link"))) {
            return Some("native_reading_fallback_annotations");
        }
        None
    }
}

/// Classify a small, fully read field tree without visiting every page annotation.
/// The result only blocks affected pages. It never declares signatures or forms absent.
fn reading_field_pages(
    cos: &pdfcraft_cos::Document,
    catalog: &pdfcraft_cos::Dict,
) -> Result<std::collections::HashSet<pdfcraft_cos::ObjRef>, &'static str> {
    use pdfcraft_cos::Object;
    use std::collections::HashSet;
    let mut pages = HashSet::new();
    let Some(form) = catalog.get(b"AcroForm") else { return Ok(pages) };
    let form = cos.resolve(form);
    if matches!(&*form, Object::Null) {
        return Ok(pages);
    }
    let Some(form) = form.as_dict() else { return Err("native_reading_fallback_form_dictionary") };
    if form.contains(b"XFA") {
        return Err("native_reading_fallback_xfa");
    }
    let Some(fields) = form.get(b"Fields") else { return Ok(pages) };
    let fields = cos.resolve(fields);
    if matches!(&*fields, Object::Null) {
        return Ok(pages);
    }
    let Some(fields) = fields.as_array() else {
        return Err("native_reading_fallback_field_tree");
    };
    if fields.len() > 512 {
        return Err("native_reading_fallback_field_budget");
    }
    let mut pending: Vec<_> = fields.iter().cloned().map(|f| (f, 0usize)).collect();
    let mut seen = HashSet::new();
    let mut remaining = 512usize;
    while let Some((field, depth)) = pending.pop() {
        if remaining == 0 || depth > 32 {
            return Err("native_reading_fallback_field_budget");
        }
        remaining -= 1;
        if let Some(reference) = field.as_ref()
            && !seen.insert(reference)
        {
            return Err("native_reading_fallback_field_tree");
        }
        let field = cos.resolve(&field);
        if matches!(&*field, Object::Null) {
            continue;
        }
        let Some(field) = field.as_dict() else {
            return Err("native_reading_fallback_field_tree");
        };
        // A merged field can be its own widget through Rect even without Subtype Widget.
        if (field.contains(b"Rect") || field.name(b"Subtype") == Some(b"Widget")) && reading_widget_visible(cos, field)? {
            let Some(page) = field.reference(b"P") else {
                return Err("native_reading_fallback_widget_location");
            };
            pages.insert(page);
        }
        if let Some(kids) = field.get(b"Kids") {
            let kids = cos.resolve(kids);
            if matches!(&*kids, Object::Null) {
                continue;
            }
            let Some(kids) = kids.as_array() else {
                return Err("native_reading_fallback_field_tree");
            };
            if kids.len() > remaining.saturating_sub(pending.len()) {
                return Err("native_reading_fallback_field_budget");
            }
            pending.extend(kids.iter().cloned().map(|f| (f, depth + 1)));
        }
    }
    Ok(pages)
}

/// Match the viewer's Hidden/NoView and finite nondegenerate rectangle rules.
/// An invisible signature remains unverified and is still discovered by complete opening.
fn reading_widget_visible(cos: &pdfcraft_cos::Document, widget: &pdfcraft_cos::Dict) -> Result<bool, &'static str> {
    let flags = widget.get(b"F").and_then(|f| cos.resolve(f).as_int()).unwrap_or(0);
    if flags & (2 | 32) != 0 {
        return Ok(false);
    }
    let Some(rect) = widget.get(b"Rect") else { return Err("native_reading_fallback_widget_appearance") };
    let rect = cos.resolve(rect);
    let Some(rect) = rect.as_array().filter(|r| r.len() == 4) else { return Err("native_reading_fallback_widget_appearance") };
    let mut numbers = [0.0f64; 4];
    for (out, value) in numbers.iter_mut().zip(rect) {
        *out = cos.resolve(value).as_f64().filter(|v| v.is_finite()).ok_or("native_reading_fallback_widget_appearance")?;
    }
    Ok((numbers[0] - numbers[2]).abs() > 0.5 && (numbers[1] - numbers[3]).abs() > 0.5)
}
