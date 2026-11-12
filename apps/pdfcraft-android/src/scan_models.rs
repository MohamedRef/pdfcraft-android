//! The scanner's model files as the app's Models list shows them (#289).

use pdfcraft_scan::models::{self, ModelState};

/// Whether a model file can be used.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ScanModelState {
    /// The file is there and is exactly the pinned one.
    Ready,
    /// No file of that name.
    Missing,
    /// A file of that name is there, but it is not the pinned one (its feature is off).
    Corrupt,
}

/// One model file the scanner can use.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ScanModelInfo {
    /// `edges`, `flatten` or `upright`.
    pub id: String,
    /// The file's name in the models folder.
    pub file: String,
    /// Its pinned size.
    pub bytes: u64,
    /// SPDX identifier of its licence.
    pub licence: String,
    /// Shipped in the app, or an optional download.
    pub bundled: bool,
    pub state: ScanModelState,
}

fn info(spec: &models::ModelSpec, state: ModelState) -> ScanModelInfo {
    ScanModelInfo {
        id: spec.id.to_owned(),
        file: spec.file.to_owned(),
        bytes: spec.bytes,
        licence: spec.licence.to_owned(),
        bundled: spec.bundled,
        state: match state {
            ModelState::Ready => ScanModelState::Ready,
            ModelState::Missing => ScanModelState::Missing,
            ModelState::Corrupt => ScanModelState::Corrupt,
        },
    }
}

/// Every scanner model and whether it can be used. Files not loaded yet are read and hashed
/// (about 26 MB): call it off the main thread.
#[uniffi::export]
pub fn scan_model_states() -> Vec<ScanModelInfo> {
    models::states().into_iter().map(|(spec, state)| info(spec, state)).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lists_every_model_in_order_and_ready_from_the_repository() {
        let states = scan_model_states();
        let ids: Vec<&str> = states.iter().map(|m| m.id.as_str()).collect();
        assert_eq!(ids, ["edges", "flatten", "upright"]);
        for m in &states {
            assert_eq!(m.state, ScanModelState::Ready, "{} should be found in assets/models", m.file);
            assert!(m.bytes > 0 && !m.licence.is_empty() && m.file.ends_with(".onnx"));
        }
    }

    #[test]
    fn maps_every_state() {
        let spec = &models::MODELS[0];
        assert_eq!(info(spec, ModelState::Missing).state, ScanModelState::Missing);
        assert_eq!(info(spec, ModelState::Corrupt).state, ScanModelState::Corrupt);
        assert_eq!(info(spec, ModelState::Ready).state, ScanModelState::Ready);
        assert!(info(spec, ModelState::Ready).bundled);
    }
}
