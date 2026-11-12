//! The model files the scanner uses (#289). Each is pinned by size and SHA-256: a file that is
//! not exactly the one named here never reaches the model parser.
//!
//! The files are looked for in `$PDFCRAFT_MODELS` (the app unpacks its bundled models into its
//! own folder and points there) and then in the repository's `assets/models`, so tests and
//! developer tools find them without setup.

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Arc, Mutex, OnceLock, PoisonError};

use sha2::{Digest, Sha256};

/// A model file and what it must be.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ModelSpec {
    pub id: &'static str,
    /// The file's name in the models directory.
    pub file: &'static str,
    pub sha256: &'static str,
    pub bytes: u64,
    /// SPDX identifier of the licence the file is distributed under.
    pub licence: &'static str,
}

/// The document detector (#290): DocQuadNet-256 from MakeACopy.
pub const EDGES: &str = "edges";

/// The page flattener (#302): UVDoc.
pub const FLATTEN: &str = "flatten";

/// The orientation classifier (#339): PP-LCNet x1.0, text image orientation.
pub const UPRIGHT: &str = "upright";

/// Every model the scanner can use. `assets/models/ATTRIBUTION.txt` says where each is from.
pub static MODELS: &[ModelSpec] = &[
    ModelSpec {
        id: EDGES,
        file: "scan-edges.onnx",
        sha256: "f5bf500c0ab7482ebd9e949c7cfcd3c9f43dcef619baa8e41e0f83e88a3f9880",
        bytes: 6_700_744,
        licence: "Apache-2.0",
    },
    ModelSpec {
        id: FLATTEN,
        file: "scan-flatten.onnx",
        sha256: "13fc4e8322de53f2d8b74ed43d996ba8e70e3b875fc5e88766c30943ada2fb1b",
        bytes: 15_803_619,
        licence: "MIT",
    },
    ModelSpec {
        id: UPRIGHT,
        file: "scan-upright.onnx",
        sha256: "96b65002cc0388d4c0e0c07ac6f3aea045ce8d46c1e3fb9e0242624ad75033fd",
        bytes: 3_439_151,
        licence: "Apache-2.0",
    },
];

/// Whether a model can be used.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ModelState {
    /// The file is there and is the pinned one.
    Ready,
    /// No such file in any of the directories.
    Missing,
    /// A file of that name is there, but it is not the pinned one.
    Corrupt,
}

fn spec(id: &str) -> Option<&'static ModelSpec> {
    MODELS.iter().find(|m| m.id == id)
}

fn search_dirs() -> Vec<PathBuf> {
    let mut dirs: Vec<PathBuf> = std::env::var_os("PDFCRAFT_MODELS").map(PathBuf::from).into_iter().collect();
    dirs.push(std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../../assets/models"));
    dirs
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// The pinned file's bytes, or why not.
fn read(spec: &ModelSpec) -> Result<Vec<u8>, ModelState> {
    let Some(path) = search_dirs().into_iter().map(|d| d.join(spec.file)).find(|p| p.is_file()) else { return Err(ModelState::Missing) };
    // the size first: a wrong file is refused without reading it
    if std::fs::metadata(&path).map(|m| m.len()).ok() != Some(spec.bytes) {
        return Err(ModelState::Corrupt);
    }
    let bytes = std::fs::read(&path).map_err(|_| ModelState::Missing)?;
    if hex(&Sha256::digest(&bytes)) != spec.sha256 {
        return Err(ModelState::Corrupt);
    }
    Ok(bytes)
}

type Loaded = Mutex<HashMap<&'static str, Arc<rten::Model>>>;

fn loaded() -> &'static Loaded {
    static LOADED: OnceLock<Loaded> = OnceLock::new();
    LOADED.get_or_init(Loaded::default)
}

/// Whether the model `id` can be used (its file is read and hashed unless it is loaded already).
pub fn state(id: &str) -> ModelState {
    let Some(spec) = spec(id) else { return ModelState::Missing };
    if loaded().lock().unwrap_or_else(PoisonError::into_inner).contains_key(spec.id) {
        return ModelState::Ready;
    }
    read(spec).map_or_else(|state| state, |_| ModelState::Ready)
}

/// The model `id`, loaded once and kept until [`release`]. `None` when its file is missing, is
/// not the pinned one, or cannot be parsed; that is not remembered, so a model unpacked later is
/// found.
pub fn load(id: &str) -> Option<Arc<rten::Model>> {
    let spec = spec(id)?;
    let mut models = loaded().lock().unwrap_or_else(PoisonError::into_inner);
    if let Some(model) = models.get(spec.id) {
        return Some(Arc::clone(model));
    }
    let model = Arc::new(rten::Model::load(read(spec).ok()?).ok()?);
    models.insert(spec.id, Arc::clone(&model));
    Some(model)
}

/// Drop the loaded models (when memory is short); they load again when next needed.
pub fn release() {
    loaded().lock().unwrap_or_else(PoisonError::into_inner).clear();
}
