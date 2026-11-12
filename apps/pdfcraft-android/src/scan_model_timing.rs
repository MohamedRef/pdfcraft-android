//! Timing a model file picked on the phone (#304), for the scanner's testing option that measures
//! whether a learned clean-up model is fast enough. **Measurement only**: the file is read, run on
//! a fixed pattern and dropped; see `pdfcraft_scan::model_timing`.

use crate::MobileError;

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

/// Load the model file at `path` and time `runs` runs (1 to 5) of it on `threads` threads (0: one
/// per core), on a fixed input of the size the model declares. Blocking, and heavy: a large model
/// takes its own size in memory and more.
#[uniffi::export]
pub fn scan_time_model(path: String, runs: u32, threads: u32) -> Result<ScanModelTiming, MobileError> {
    let size = std::fs::metadata(&path).map_err(|_| MobileError::Command("the model file could not be read".into()))?.len();
    if size > pdfcraft_scan::model_timing::MAX_BYTES {
        return Err(MobileError::Command(pdfcraft_scan::model_timing::TimingError::TooLarge.to_string()));
    }
    let bytes = std::fs::read(&path).map_err(|_| MobileError::Command("the model file could not be read".into()))?;
    let n = |v: usize| u32::try_from(v).unwrap_or(u32::MAX);
    let t = pdfcraft_scan::model_timing::time_model(bytes, runs as usize, threads as usize).map_err(|e| MobileError::Command(e.to_string()))?;
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
    use super::scan_time_model;

    /// Through the file: the bundled detector is timed; a missing file and a file that is not a
    /// model are errors.
    #[test]
    fn a_model_file_is_timed_and_bad_files_refused() {
        let edges = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../assets/models/scan-edges.onnx");
        let t = scan_time_model(edges.to_string_lossy().into_owned(), 1, 1).unwrap();
        assert_eq!((t.bytes, t.runs_ms.len(), t.threads), (6_700_744, 1, 1));
        assert!(t.output_finite);
        let dir = std::env::temp_dir().join(format!("pdfcraft-model-timing-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let junk = dir.join("junk.onnx");
        std::fs::write(&junk, b"not a model").unwrap();
        assert!(scan_time_model(junk.to_string_lossy().into_owned(), 1, 1).is_err());
        assert!(scan_time_model(dir.join("missing.onnx").to_string_lossy().into_owned(), 1, 1).is_err());
        std::fs::remove_dir_all(dir).unwrap();
    }
}
