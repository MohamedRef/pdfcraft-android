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

/// A page's pixels and the resolution written into its file, so a PDF made from the file gets
/// the page's physical size (#293).
#[derive(Clone, Copy, Debug)]
pub struct Raster<'a> {
    /// One byte per pixel when `grey`, else RGB8.
    pub pixels: &'a [u8],
    pub grey: bool,
    pub width: u32,
    pub height: u32,
    /// Dots per inch.
    pub dpi: u16,
}

/// The page as a JPEG at `quality`, its resolution in the JFIF header.
pub fn encode_jpeg(page: &Raster<'_>, quality: u8) -> Result<Vec<u8>, ScanError> {
    let mut out = Vec::new();
    let colour = if page.grey { image::ExtendedColorType::L8 } else { image::ExtendedColorType::Rgb8 };
    let mut encoder = image::codecs::jpeg::JpegEncoder::new_with_quality(&mut out, quality.clamp(1, 100));
    encoder.set_pixel_density(image::codecs::jpeg::PixelDensity::dpi(page.dpi.max(1)));
    encoder.write_image(page.pixels, page.width, page.height, colour).map_err(|_| ScanError::Encode)?;
    Ok(out)
}

/// A grey page as a PNG, its resolution in the `pHYs` chunk.
pub fn encode_png_grey(page: &Raster<'_>) -> Result<Vec<u8>, ScanError> {
    let mut out = Vec::new();
    let mut encoder = png::Encoder::new(&mut out, page.width, page.height);
    encoder.set_color(png::ColorType::Grayscale);
    encoder.set_depth(png::BitDepth::Eight);
    let per_metre = (f64::from(page.dpi.max(1)) / 0.0254).round() as u32;
    encoder.set_pixel_dims(Some(png::PixelDimensions { xppu: per_metre, yppu: per_metre, unit: png::Unit::Meter }));
    let mut writer = encoder.write_header().map_err(|_| ScanError::Encode)?;
    writer.write_image_data(page.pixels).map_err(|_| ScanError::Encode)?;
    writer.finish().map_err(|_| ScanError::Encode)?;
    Ok(out)
}

/// A JPEG file unchanged but for its resolution: `dpi` in its JFIF header, which is added when
/// the file has none (a camera's usually has only EXIF). Not one pixel is decoded or re-encoded.
/// `None` when the bytes are not a JPEG file.
pub fn stamp_jpeg_density(jpeg: &[u8], dpi: u16) -> Option<Vec<u8>> {
    let rest = jpeg.strip_prefix(&[0xff, 0xd8])?;
    let d = dpi.max(1).to_be_bytes();
    // the marker, its length, "JFIF\0", the version, then the unit and the two densities
    let has_header = rest.len() >= 16 && rest[..2] == [0xff, 0xe0] && rest[4..9] == *b"JFIF\0" && u16::from_be_bytes([rest[2], rest[3]]) >= 16;
    let mut out = Vec::with_capacity(jpeg.len() + 18);
    out.extend_from_slice(&[0xff, 0xd8, 0xff, 0xe0]);
    if has_header {
        out.extend_from_slice(&rest[2..11]);
        out.extend_from_slice(&[1, d[0], d[1], d[0], d[1]]);
        out.extend_from_slice(&rest[16..]);
    } else {
        out.extend_from_slice(&[0, 16]);
        out.extend_from_slice(b"JFIF\0");
        out.extend_from_slice(&[1, 1, 1, d[0], d[1], d[0], d[1], 0, 0]);
        out.extend_from_slice(rest);
    }
    Some(out)
}
