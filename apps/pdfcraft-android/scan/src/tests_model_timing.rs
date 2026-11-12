//! Timing a picked model file (#304), on the scanner's own bundled detector, the only model file
//! the tests can count on.

use crate::model_timing::{MAX_RUNS, TimingError, time_model};

fn edges() -> Vec<u8> {
    let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../../assets/models/scan-edges.onnx");
    std::fs::read(path).unwrap()
}

/// The detector loads, runs on its own input size, and every step is timed.
#[test]
fn a_bundled_model_is_timed() {
    let t = time_model(edges(), 2, 1).unwrap();
    assert_eq!(t.input_shape.first(), Some(&1), "{t:?}");
    assert_eq!(t.input_shape.len(), 4, "{t:?}");
    assert_eq!(t.runs_ms.len(), 2);
    assert!(!t.output_shape.is_empty() && t.output_finite, "{t:?}");
    assert_eq!(t.sha256_16, "f5bf500c0ab7482e");
    assert_eq!(t.bytes, 6_700_744);
    assert_eq!(t.threads, 1);
    // Linux keeps resident-memory counts per thread and folds them in late, so with other tests
    // running at once the two readings are not strictly ordered: only that both are there
    #[cfg(target_os = "linux")]
    assert!(t.peak_kb_before.is_some_and(|k| k > 0) && t.peak_kb_after.is_some_and(|k| k > 0), "{t:?}");
}

/// Runs are kept between 1 and the most allowed.
#[test]
fn runs_are_bounded() {
    assert_eq!(time_model(edges(), 0, 1).unwrap().runs_ms.len(), 1);
    assert_eq!(time_model(edges(), 99, 0).unwrap().runs_ms.len(), MAX_RUNS);
}

/// A file that is not a model is refused, not a panic.
#[test]
fn a_file_that_is_not_a_model_is_refused() {
    assert_eq!(time_model(b"not a model".to_vec(), 1, 1), Err(TimingError::NotAModel));
    assert_eq!(time_model(Vec::new(), 1, 1), Err(TimingError::NotAModel));
}

/// By hand on a developer machine: `PDFCRAFT_TIME_MODEL=docres-512x384.onnx cargo test --release
/// -- --ignored a_model_file_from_the_environment --nocapture` prints what the phone's option would.
#[test]
#[ignore = "needs a model file named in PDFCRAFT_TIME_MODEL"]
fn a_model_file_from_the_environment() {
    let path = std::env::var("PDFCRAFT_TIME_MODEL").unwrap();
    let threads = std::env::var("PDFCRAFT_TIME_THREADS").ok().and_then(|t| t.parse().ok()).unwrap_or(0);
    println!("{:?}", time_model(std::fs::read(path).unwrap(), 2, threads).unwrap());
}
