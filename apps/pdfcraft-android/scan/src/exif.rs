//! The two camera facts the page's proportions use, read from a photo's EXIF block: the focal
//! length as a 35 mm equivalent and the digital zoom (#293). Every read is bounds-checked; a
//! damaged block simply yields nothing.

/// What the camera wrote about its lens.
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct Camera {
    /// Focal length as on a 35 mm camera, in millimetres.
    pub focal_35mm: Option<f32>,
    /// Digital zoom factor (1 = none).
    pub digital_zoom: Option<f32>,
}

const EXIF_IFD: u16 = 0x8769;
const DIGITAL_ZOOM_RATIO: u16 = 0xA404;
const FOCAL_LENGTH_IN_35MM_FILM: u16 = 0xA405;
/// No sane directory has more entries; a damaged count cannot make this loop long.
const MAX_ENTRIES: usize = 512;

struct Tiff<'a> {
    bytes: &'a [u8],
    little: bool,
}

impl Tiff<'_> {
    fn u16(&self, at: usize) -> Option<u16> {
        let b: [u8; 2] = self.bytes.get(at..at.checked_add(2)?)?.try_into().ok()?;
        Some(if self.little { u16::from_le_bytes(b) } else { u16::from_be_bytes(b) })
    }

    fn u32(&self, at: usize) -> Option<u32> {
        let b: [u8; 4] = self.bytes.get(at..at.checked_add(4)?)?.try_into().ok()?;
        Some(if self.little { u32::from_le_bytes(b) } else { u32::from_be_bytes(b) })
    }

    /// The offset of the 12-byte entry for `tag` in the directory at `ifd`.
    fn entry(&self, ifd: usize, tag: u16) -> Option<usize> {
        let count = usize::from(self.u16(ifd)?).min(MAX_ENTRIES);
        (0..count).map(|i| ifd + 2 + 12 * i).find(|&at| self.u16(at) == Some(tag))
    }
}

/// Read [`Camera`] from an EXIF block (a TIFF structure, with or without its `Exif\0\0` prefix).
pub fn camera(exif: &[u8]) -> Camera {
    read(exif.strip_prefix(b"Exif\0\0").unwrap_or(exif)).unwrap_or_default()
}

fn read(bytes: &[u8]) -> Option<Camera> {
    let little = match bytes.get(..4)? {
        b"II\x2a\0" => true,
        b"MM\0\x2a" => false,
        _ => return None,
    };
    let tiff = Tiff { bytes, little };
    let ifd0 = tiff.u32(4)? as usize;
    let exif_ifd = tiff.u32(tiff.entry(ifd0, EXIF_IFD)? + 8)? as usize;
    // SHORT, one value, stored in the entry itself
    let focal_35mm = tiff
        .entry(exif_ifd, FOCAL_LENGTH_IN_35MM_FILM)
        .filter(|&at| tiff.u16(at + 2) == Some(3))
        .and_then(|at| tiff.u16(at + 8))
        .filter(|&v| v > 0)
        .map(f32::from);
    // RATIONAL: the entry holds the offset of numerator and denominator
    let digital_zoom = tiff
        .entry(exif_ifd, DIGITAL_ZOOM_RATIO)
        .filter(|&at| tiff.u16(at + 2) == Some(5))
        .and_then(|at| tiff.u32(at + 8))
        .and_then(|at| Some((tiff.u32(at as usize)?, tiff.u32(at as usize + 4)?)))
        .filter(|&(n, d)| n > 0 && d > 0)
        .map(|(n, d)| n as f32 / d as f32);
    Some(Camera { focal_35mm, digital_zoom })
}
