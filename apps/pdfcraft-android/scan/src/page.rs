//! The whole page pipeline: decode → straighten → look → encode, each once, with a timing per
//! stage.

use std::time::Instant;

use crate::encode::{Format, JPEG_QUALITY, encode_jpeg, encode_png_grey};
use crate::look::{Look, Pixels, apply};
use crate::warp::{output_size, rectify_with_threads};
use crate::{Photo, Quad, ScanError};

/// The stage names timings are recorded under (fixed: diagnostics never hold document data).
pub const STAGE_DECODE: &str = "scan_decode";
pub const STAGE_RECTIFY: &str = "scan_rectify";
pub const STAGE_LOOK: &str = "scan_look";
pub const STAGE_ENCODE: &str = "scan_encode";

/// A page's long side is taken to be this long (A4) when its resolution is worked out.
const LONG_SIDE_MM: f64 = 297.0;

/// What to make of one photo.
#[derive(Clone, Debug)]
pub struct PageRequest<'a> {
    /// The camera file's bytes (JPEG or PNG).
    pub photo: &'a [u8],
    /// The page's corners as shares (0–1) of the upright photo: x1, y1 … x4, y4.
    pub corners: [f32; 8],
    pub look: Look,
    /// 0–100.
    pub strength: u8,
    /// Threads to straighten with (1–4); the bytes are the same for every value.
    pub threads: usize,
}

/// One finished page.
#[derive(Clone, Debug, PartialEq)]
pub struct PageResult {
    /// The encoded page.
    pub bytes: Vec<u8>,
    pub format: Format,
    pub width: u32,
    pub height: u32,
    /// The resolution at which the long side is 297 mm, between 72 and 600.
    pub dpi: f32,
    /// (stage, milliseconds), in pipeline order.
    pub timings: Vec<(&'static str, u32)>,
}

/// Run the pipeline on one photo.
pub fn process_page(request: &PageRequest<'_>) -> Result<PageResult, ScanError> {
    let mut timings = Vec::with_capacity(4);
    let mut clock = Instant::now();
    let mut lap = |stage: &'static str, timings: &mut Vec<(&'static str, u32)>| {
        let ms = u32::try_from(clock.elapsed().as_millis()).unwrap_or(u32::MAX);
        timings.push((stage, ms));
        clock = Instant::now();
    };

    let photo = Photo::decode(request.photo)?;
    lap(STAGE_DECODE, &mut timings);

    let quad = Quad::from_shares(&request.corners, photo.width, photo.height)?;
    let (w, h) = output_size(&quad)?;
    let page = rectify_with_threads(&photo, &quad, w, h, request.threads)?;
    drop(photo);
    lap(STAGE_RECTIFY, &mut timings);

    let pixels = apply(&page, request.look, request.strength).ok_or(ScanError::Encode)?;
    drop(page);
    lap(STAGE_LOOK, &mut timings);

    let (bytes, format) = match &pixels {
        Pixels::Rgb(rgb) => (encode_jpeg(rgb, false, w, h, JPEG_QUALITY)?, Format::Jpeg),
        Pixels::Grey(grey) if request.look == Look::BlackWhite => (encode_png_grey(grey, w, h)?, Format::Png),
        Pixels::Grey(grey) => (encode_jpeg(grey, true, w, h, JPEG_QUALITY)?, Format::Jpeg),
    };
    lap(STAGE_ENCODE, &mut timings);

    Ok(PageResult { bytes, format, width: w, height: h, dpi: page_dpi(w, h), timings })
}

/// The resolution at which a `w` × `h` page's long side is 297 mm, clamped to 72–600 dpi.
pub fn page_dpi(w: u32, h: u32) -> f32 {
    (f64::from(w.max(h)) * 25.4 / LONG_SIDE_MM).clamp(72.0, 600.0) as f32
}
