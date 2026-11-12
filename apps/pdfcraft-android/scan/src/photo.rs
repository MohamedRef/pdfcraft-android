//! A camera photo decoded once, upright (its EXIF orientation applied), as RGB8.

use image::ImageDecoder;

use crate::ScanError;

/// The most pixels a photo may have (a 64 MP camera; larger is refused before decoding).
pub const MAX_PHOTO_PIXELS: u64 = 64_000_000;

/// An upright photo, RGB8, row-major.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Photo {
    pub width: u32,
    pub height: u32,
    pub rgb: Vec<u8>,
}

/// One grey byte per pixel (BT.601 weights, as the looks use).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Luma {
    pub width: u32,
    pub height: u32,
    pub data: Vec<u8>,
}

impl Photo {
    /// Decode a JPEG or PNG file's bytes and turn the picture the way it was taken (EXIF
    /// orientation). Photos over [`MAX_PHOTO_PIXELS`] are refused from their header, before any
    /// pixel is decoded.
    pub fn decode(bytes: &[u8]) -> Result<Photo, ScanError> {
        let reader = image::ImageReader::new(std::io::Cursor::new(bytes)).with_guessed_format().map_err(|_| ScanError::Unreadable)?;
        if !matches!(reader.format(), Some(image::ImageFormat::Jpeg | image::ImageFormat::Png)) {
            return Err(ScanError::Unreadable);
        }
        let mut decoder = reader.into_decoder().map_err(|_| ScanError::Unreadable)?;
        let (w, h) = decoder.dimensions();
        let pixels = u64::from(w) * u64::from(h);
        if pixels > MAX_PHOTO_PIXELS {
            return Err(ScanError::TooLarge(pixels.div_ceil(1_000_000)));
        }
        if w == 0 || h == 0 {
            return Err(ScanError::Unreadable);
        }
        let orientation = decoder.orientation().unwrap_or(image::metadata::Orientation::NoTransforms);
        let mut picture = image::DynamicImage::from_decoder(decoder).map_err(|_| ScanError::Unreadable)?;
        picture.apply_orientation(orientation);
        let rgb = picture.into_rgb8();
        Ok(Photo { width: rgb.width(), height: rgb.height(), rgb: rgb.into_raw() })
    }

    /// The photo in shades of grey.
    pub fn luma(&self) -> Luma {
        let data = self.rgb.as_chunks::<3>().0.iter().map(|p| luma(*p)).collect();
        Luma { width: self.width, height: self.height, data }
    }
}

/// BT.601 luma of an RGB pixel, rounded down (the formula of the engine's looks).
pub fn luma([r, g, b]: [u8; 3]) -> u8 {
    // At most 255 × 1000 / 1000.
    ((u32::from(r) * 299 + u32::from(g) * 587 + u32::from(b) * 114) / 1000) as u8
}
