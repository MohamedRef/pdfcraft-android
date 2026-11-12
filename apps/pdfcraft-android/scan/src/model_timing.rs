//! Timing a model file the person picks on their phone (#304), for the testing option that
//! measures whether a learned clean-up model is fast enough to ship. **Measurement only**: the
//! file is never kept, never used on a page, and nothing in the app or the repository ships it.
//!
//! The model is loaded from the bytes, its first input is filled with a fixed smooth pattern of the
//! size the model declares, and it is run a few times. What comes back is how long each step took,
//! the shapes, whether the output is finite, and how much memory the process held at its peak.

use std::time::Instant;

use rten::{Dimension, Model, RunOptions, ThreadPool};
use rten_tensor::Tensor;
use rten_tensor::prelude::*;

/// The largest file read (bytes): larger than any model the scanner could carry.
pub const MAX_BYTES: u64 = 600 * 1024 * 1024;
/// The most runs timed.
pub const MAX_RUNS: usize = 5;
/// The largest input filled (values): 64 MB of floats.
const MAX_VALUES: usize = 16 * 1024 * 1024;

/// What went wrong, in words for the log and the message the person sees.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum TimingError {
    #[error("the file is larger than 600 MB")]
    TooLarge,
    #[error("the file is not a model the runtime can load")]
    NotAModel,
    #[error("the model has no input")]
    NoInput,
    #[error("the model's input size is not fixed ({0}); export it at a fixed size")]
    NotFixed(String),
    #[error("the model's input is too large")]
    InputTooLarge,
    #[error("the model has no output")]
    NoOutput,
    #[error("the model could not be run")]
    RunFailed,
}

/// What timing a model found.
#[derive(Clone, Debug, PartialEq)]
pub struct ModelTiming {
    /// The file's size and the first 16 hex digits of its SHA-256, to tell which file it was.
    pub bytes: u64,
    pub sha256_16: String,
    /// The shape filled in for the first input, and how many inputs the model has.
    pub input_shape: Vec<usize>,
    pub inputs: usize,
    /// The first output's shape after the last run, and whether all its values were finite.
    pub output_shape: Vec<usize>,
    pub output_finite: bool,
    /// Threads asked for (0: the runtime's own pool, one per core) and cores the system reports.
    pub threads: usize,
    pub cores: usize,
    /// Loading the model, then each run, in milliseconds.
    pub load_ms: u32,
    pub runs_ms: Vec<u32>,
    /// The process's peak resident memory (kB) before loading and after the last run; `None`
    /// where the system does not say (`/proc/self/status`, `VmHWM`).
    pub peak_kb_before: Option<u64>,
    pub peak_kb_after: Option<u64>,
}

fn ms(since: Instant) -> u32 {
    u32::try_from(since.elapsed().as_millis()).unwrap_or(u32::MAX)
}

/// The process's peak resident memory in kB, from `/proc/self/status`.
pub fn peak_kb() -> Option<u64> {
    let status = std::fs::read_to_string("/proc/self/status").ok()?;
    let line = status.lines().find(|l| l.starts_with("VmHWM:"))?;
    line.split_whitespace().nth(1)?.parse().ok()
}

/// A fixed, smooth pattern in 0–1 (not noise: some layers run faster on zeros or repeats).
fn pattern(len: usize) -> Vec<f32> {
    (0..len).map(|i| 0.5 + 0.4 * ((i % 9973) as f32 * 0.013).sin()).collect()
}

/// Load the model in `bytes` and time `runs` runs (1 to [`MAX_RUNS`]) on `threads` threads (0:
/// the runtime's own pool).
pub fn time_model(bytes: Vec<u8>, runs: usize, threads: usize) -> Result<ModelTiming, TimingError> {
    use sha2::{Digest, Sha256};

    let size = bytes.len() as u64;
    if size > MAX_BYTES {
        return Err(TimingError::TooLarge);
    }
    let sha256_16: String = Sha256::digest(&bytes).iter().take(8).map(|b| format!("{b:02x}")).collect();
    let peak_kb_before = peak_kb();

    let started = Instant::now();
    let model = Model::load(bytes).map_err(|_| TimingError::NotAModel)?;
    let load_ms = ms(started);

    let ids = model.input_ids();
    let input = *ids.first().ok_or(TimingError::NoInput)?;
    let dims = model.node_info(input).and_then(|i| i.shape()).ok_or_else(|| TimingError::NotFixed("unknown".into()))?;
    let input_shape = dims
        .iter()
        .map(|d| match d {
            Dimension::Fixed(n) => Ok(*n),
            Dimension::Symbolic(name) => Err(TimingError::NotFixed(name.clone())),
        })
        .collect::<Result<Vec<usize>, _>>()?;
    let len = input_shape.iter().try_fold(1usize, |a, &n| a.checked_mul(n)).filter(|&n| n > 0 && n <= MAX_VALUES).ok_or(TimingError::InputTooLarge)?;
    let output = *model.output_ids().first().ok_or(TimingError::NoOutput)?;
    let data = Tensor::from_data(&input_shape, pattern(len));

    let pool = (threads > 0).then(|| std::sync::Arc::new(ThreadPool::with_num_threads(threads)));
    let mut runs_ms = Vec::new();
    let (mut output_shape, mut output_finite) = (Vec::new(), false);
    for _ in 0..runs.clamp(1, MAX_RUNS) {
        let mut opts = RunOptions::default();
        opts.thread_pool.clone_from(&pool);
        let started = Instant::now();
        let mut out = model.run(vec![(input, data.view().into())], &[output], Some(opts)).map_err(|_| TimingError::RunFailed)?;
        runs_ms.push(ms(started));
        let value = out.pop().ok_or(TimingError::RunFailed)?;
        output_shape = value.shape().to_vec();
        output_finite = Tensor::<f32>::try_from(value).is_ok_and(|t| t.iter().all(|v| v.is_finite()));
    }
    Ok(ModelTiming {
        bytes: size,
        sha256_16,
        input_shape,
        inputs: ids.len(),
        output_shape,
        output_finite,
        threads,
        cores: std::thread::available_parallelism().map_or(0, usize::from),
        load_ms,
        runs_ms,
        peak_kb_before,
        peak_kb_after: peak_kb(),
    })
}
