//! PdfCraft Android's document scanner (#286): a camera photo becomes a straightened page image in
//! one pass, decoded once and encoded once ([`process_page`]).
//!
//! This crate is owned by the Android app, so scan work does not touch the engine crates pinned to
//! upstream (`docs/android-engine-provenance-lock.json`). Pure Rust, deterministic (the same
//! bytes for the same input, on any number of threads), bounded by the input, and never panics on
//! bad input.
//!
//! Some modules are building blocks the pipeline does not call yet (`book`, `flatten_guard`,
//! `merge`, `metrics_*`, `photo_mask`, `preset`, `sharpness`, `straightness`, `upright_pages`):
//! `docs/ANDROID_SCAN_BLOCKS.md` says what each gives and what is left to wire in.
#![forbid(unsafe_code)]
#![deny(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::unimplemented, clippy::todo, clippy::unreachable)]

pub mod aspect;
pub mod book;
pub mod detect;
pub mod encode;
pub mod enhance;
mod error;
pub mod exif;
pub mod flatten;
pub mod flatten_guard;
pub mod look;
pub mod merge;
pub mod metrics_compare;
pub mod metrics_geometry;
pub mod metrics_tone;
pub mod models;
pub mod page;
mod photo;
pub mod photo_mask;
pub mod preset;
mod quad;
pub mod refine;
pub mod sharpness;
pub mod straightness;
#[cfg(any(test, feature = "synth"))]
pub mod synth;
pub mod upright;
pub mod upright_pages;
pub mod warp;

pub use aspect::{AspectSource, Focal, Paper, Proportions, proportions, proportions_by_sides};
pub use detect::{Detection, Finder, QuadSource, detect, detect_with};
pub use encode::Format;
pub use enhance::{AutoOutput, AutoParams, DEFAULT_SHARPEN, PaperMode, auto};
pub use error::ScanError;
pub use flatten::{FlattenGrid, flat_grid, predict_grid, unwarp};
pub use look::Look;
pub use page::{PageBase, PageRequest, PageResult, page_base, page_look, process_page};
pub use photo::{Luma, MAX_PHOTO_PIXELS, Photo, luma};
pub use quad::Quad;
pub use refine::{Pyramid, RefineOptions, Refined, SideFit, refine};
pub use upright::{Upright, turned, upright};
pub use warp::{Rgb, rectify};

#[cfg(test)]
mod tests;
#[cfg(test)]
mod tests_book;
#[cfg(test)]
mod tests_detect;
#[cfg(test)]
mod tests_flatten;
#[cfg(test)]
mod tests_flatten_guard;
#[cfg(test)]
mod tests_merge;
#[cfg(test)]
mod tests_metrics_compare;
#[cfg(test)]
mod tests_metrics_geometry;
#[cfg(test)]
mod tests_metrics_tone;
#[cfg(test)]
mod tests_photo_mask;
#[cfg(test)]
mod tests_preset;
#[cfg(test)]
mod tests_proportions;
#[cfg(test)]
mod tests_refine;
#[cfg(test)]
mod tests_sharpness;
#[cfg(test)]
mod tests_straightness;
#[cfg(test)]
mod tests_synth;
#[cfg(test)]
mod tests_upright;
#[cfg(test)]
mod tests_upright_pages;
