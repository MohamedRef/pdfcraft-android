//! PdfCraft Android's document scanner (#286): a camera photo becomes a straightened page image in
//! one pass, decoded once and encoded once ([`process_page`]).
//!
//! This crate is owned by the Android app, so scan work does not touch the engine crates pinned to
//! upstream (`docs/android-engine-provenance-lock.json`). Pure Rust, deterministic (the same
//! bytes for the same input, on any number of threads), bounded by the input, and never panics on
//! bad input.
#![forbid(unsafe_code)]
#![deny(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::unimplemented, clippy::todo, clippy::unreachable)]

pub mod aspect;
pub mod encode;
pub mod enhance;
mod error;
pub mod exif;
pub mod look;
pub mod page;
mod photo;
mod quad;
pub mod warp;

pub use aspect::{AspectSource, Focal, Paper, Proportions, proportions};
pub use encode::Format;
pub use enhance::{AutoOutput, AutoParams, PaperMode, auto};
pub use error::ScanError;
pub use look::Look;
pub use page::{PageRequest, PageResult, process_page};
pub use photo::{Luma, MAX_PHOTO_PIXELS, Photo, luma};
pub use quad::Quad;
pub use warp::{Rgb, rectify};

#[cfg(test)]
mod tests;
#[cfg(test)]
mod tests_proportions;
