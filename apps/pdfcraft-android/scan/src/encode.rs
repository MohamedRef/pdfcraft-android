//! Write a page's pixels once: JPEG for photos and greys, lossless PNG for black and white (JPEG
//! would smear grey around every letter).

use image::ImageEncoder;

use crate::ScanError;

/// JPEG quality of a page.
pub const JPEG_QUALITY: u8 = 92;

/// The file format a page was written in.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Format {
    Jpeg,
    Png,
}

impl Format {
    /// The file name extension, without the dot.
    pub fn extension(self) -> &'static str {
        match self {
            Format::Jpeg => "jpg",
            Format::Png => "png",
        }
    }
}

/// `pixels` (`grey`: one byte per pixel, else RGB8) as a JPEG at `quality`.
pub fn encode_jpeg(pixels: &[u8], grey: bool, width: u32, height: u32, quality: u8) -> Result<Vec<u8>, ScanError> {
    let mut out = Vec::new();
    let colour = if grey { image::ExtendedColorType::L8 } else { image::ExtendedColorType::Rgb8 };
    image::codecs::jpeg::JpegEncoder::new_with_quality(&mut out, quality.clamp(1, 100))
        .write_image(pixels, width, height, colour)
        .map_err(|_| ScanError::Encode)?;
    Ok(out)
}

/// One grey byte per pixel as a PNG.
pub fn encode_png_grey(grey: &[u8], width: u32, height: u32) -> Result<Vec<u8>, ScanError> {
    let mut out = Vec::new();
    image::codecs::png::PngEncoder::new(&mut out).write_image(grey, width, height, image::ExtendedColorType::L8).map_err(|_| ScanError::Encode)?;
    Ok(out)
}
