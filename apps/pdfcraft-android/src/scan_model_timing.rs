//! Timing a model file picked on the phone (#304), for the scanner's testing option that measures
//! whether a learned clean-up model is fast enough. **Measurement only**: the file is read, run on
//! a fixed pattern and dropped; see `pdfcraft_scan::model_timing`.

use std::path::Path;

use crate::MobileError;
use crate::scan::SCAN_ROOT;

/// What timing a model found. Times in milliseconds, memory in kB (`None` where the system does
/// not say).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ScanModelTiming {
    pub bytes: u64,
    /// The first 16 hex digits of the file's SHA-256.
    pub sha256_16: String,
    pub input_shape: Vec<u32>,
    pub inputs: u32,
    pub output_shape: Vec<u32>,
    pub output_finite: bool,
    /// 0: the runtime's own pool (one thread per core).
    pub threads: u32,
    pub cores: u32,
    pub load_ms: u32,
    pub runs_ms: Vec<u32>,
    pub peak_kb_before: Option<u64>,
    pub peak_kb_after: Option<u64>,
}

/// What timing a model came back with (#445): the timing, or why it failed as a short lowercase
/// word for the log (`reason`) with the message the person sees, and how long it ran before it
/// failed.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ScanModelTimingOutcome {
    pub timing: Option<ScanModelTiming>,
    /// Empty when timed; otherwise `not_ready`, `outside_folder`, `unreadable`, `too_large`,
    /// `not_a_model`, `no_input`, `not_fixed`, `input_too_large`, `no_output` or `run_failed`.
    pub reason: String,
    /// The message for the screen; empty when timed.
    pub message: String,
    /// From the call to its answer, in milliseconds.
    pub ms: u32,
}

/// Why timing a model failed (#445): the log's word and the screen's message.
#[derive(Debug, Clone, PartialEq)]
pub(crate) struct Failure {
    pub reason: &'static str,
    pub message: String,
}

impl Failure {
    fn new(reason: &'static str, message: impl Into<String>) -> Self {
        Failure { reason, message: message.into() }
    }
}

impl From<pdfcraft_scan::model_timing::TimingError> for Failure {
    fn from(e: pdfcraft_scan::model_timing::TimingError) -> Self {
        Failure::new(e.reason(), e.to_string())
    }
}

/// `scan_time_model`, with the reason it failed for the log instead of an error (#445).
#[uniffi::export]
pub fn scan_time_model_outcome(path: String, runs: u32, threads: u32) -> ScanModelTimingOutcome {
    let started = std::time::Instant::now();
    let result = time_model_rooted(&path, runs, threads);
    let ms = u32::try_from(started.elapsed().as_millis()).unwrap_or(u32::MAX);
    match result {
        Ok(t) => ScanModelTimingOutcome { timing: Some(t), reason: String::new(), message: String::new(), ms },
        Err(f) => ScanModelTimingOutcome { timing: None, reason: f.reason.into(), message: f.message, ms },
    }
}

/// Load the model file at `path` and time `runs` runs (1 to 5) of it on `threads` threads (0: one
/// per core), on a fixed input of the size the model declares. Blocking, and heavy: a large model
/// takes its own size in memory and more. Like every other scan call that takes a path (#429), the
/// file must be inside the scan root (the app's cache directory, see `scan_set_root`).
#[uniffi::export]
pub fn scan_time_model(path: String, runs: u32, threads: u32) -> Result<ScanModelTiming, MobileError> {
    time_model_rooted(&path, runs, threads).map_err(|f| MobileError::Command(f.message))
}

fn time_model_rooted(path: &str, runs: u32, threads: u32) -> Result<ScanModelTiming, Failure> {
    let root = SCAN_ROOT.read().unwrap_or_else(std::sync::PoisonError::into_inner).clone();
    let root = root.ok_or_else(|| Failure::new("not_ready", "scanning is not ready yet"))?;
    time_model_in(&root, path, runs, threads)
}

pub(crate) fn time_model_in(root: &Path, path: &str, runs: u32, threads: u32) -> Result<ScanModelTiming, Failure> {
    let unreadable = || Failure::new("unreadable", "the model file could not be read");
    // resolved first, so `..` and links cannot lead out of the root
    let model_path = Path::new(path).canonicalize().map_err(|_| unreadable())?;
    if !model_path.starts_with(root) {
        return Err(Failure::new("outside_folder", "the scan's files must be in the app's own folder"));
    }
    let size = std::fs::metadata(&model_path).map_err(|_| unreadable())?.len();
    if size > pdfcraft_scan::model_timing::MAX_BYTES {
        return Err(pdfcraft_scan::model_timing::TimingError::TooLarge.into());
    }
    let bytes = std::fs::read(&model_path).map_err(|_| unreadable())?;
    let n = |v: usize| u32::try_from(v).unwrap_or(u32::MAX);
    let t = pdfcraft_scan::model_timing::time_model(bytes, runs as usize, threads as usize)?;
    Ok(ScanModelTiming {
        bytes: t.bytes,
        sha256_16: t.sha256_16,
        input_shape: t.input_shape.into_iter().map(n).collect(),
        inputs: n(t.inputs),
        output_shape: t.output_shape.into_iter().map(n).collect(),
        output_finite: t.output_finite,
        threads: n(t.threads),
        cores: n(t.cores),
        load_ms: t.load_ms,
        runs_ms: t.runs_ms,
        peak_kb_before: t.peak_kb_before,
        peak_kb_after: t.peak_kb_after,
    })
}

#[cfg(test)]
mod tests {
    use super::time_model_in;

    fn temp_dir(name: &str) -> std::path::PathBuf {
        let dir = std::env::temp_dir().join(format!("pdfcraft-model-timing-{name}-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        dir.canonicalize().unwrap()
    }

    /// Through the file: the bundled detector, copied into the root, is timed; a missing file and a
    /// file that is not a model are errors.
    #[test]
    fn a_model_file_is_timed_and_bad_files_refused() {
        let root = temp_dir("root");
        let edges = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../assets/models/scan-edges.onnx");
        let model = root.join("model.onnx");
        std::fs::copy(&edges, &model).unwrap();
        let t = time_model_in(&root, &model.to_string_lossy(), 1, 1).unwrap();
        assert_eq!((t.bytes, t.runs_ms.len(), t.threads), (6_700_744, 1, 1));
        assert!(t.output_finite);
        let junk = root.join("junk.onnx");
        std::fs::write(&junk, b"not a model").unwrap();
        // #445: each failure says why, as a word for the log
        assert_eq!(time_model_in(&root, &junk.to_string_lossy(), 1, 1).unwrap_err().reason, "not_a_model");
        assert_eq!(time_model_in(&root, &root.join("missing.onnx").to_string_lossy(), 1, 1).unwrap_err().reason, "unreadable");
        std::fs::remove_dir_all(root).unwrap();
    }

    /// #429: a model outside the root is refused before it is read, also when the path only looks
    /// as if it were inside (`..`), as `read_photo` refuses a photo.
    #[test]
    fn a_model_outside_the_root_is_refused() {
        let root = temp_dir("inside");
        let outside = temp_dir("outside");
        let edges = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../assets/models/scan-edges.onnx");
        let model = outside.join("model.onnx");
        std::fs::copy(&edges, &model).unwrap();
        let refused = |path: &std::path::Path| match time_model_in(&root, &path.to_string_lossy(), 1, 1) {
            Err(f) => f.message.contains("app's own folder") && f.reason == "outside_folder",
            _ => false,
        };
        assert!(refused(&model));
        let dotted = root.join("..").join(outside.file_name().unwrap()).join("model.onnx");
        assert!(refused(&dotted));
        // the bundled asset itself is outside the root too
        assert!(refused(&edges));
        std::fs::remove_dir_all(root).unwrap();
        std::fs::remove_dir_all(outside).unwrap();
    }
}
