//! How a made page is stored (#505): the person's size choice at Save, and an encoding chosen for
//! each page from what is on it.
//!
//! Before #505 every page was a JPEG at quality 92 with the image crate's standard Huffman
//! tables and full-resolution colour, at the page's full size, whatever was on it; only the Black
//! and white look was stored as 1 bit. With the automatic encoding on, a page is still encoded
//! once, from the made pixels:
//!
//! 1. no colour on the page: one grey component instead of three;
//! 2. at Balanced, Small and Smallest, the resolution the page needs: enough pixels across its
//!    typical stroke (`stroke_mm` × dpi), within the choice's floor and cap, never enlarged; a
//!    page with photographs keeps more;
//! 3. the choice's JPEG quality, colour at half resolution below Best;
//! 4. at Small and Smallest, a page of text alone (no colour, no photograph) as 1 bit, which the
//!    PDF stores as CCITT Group 4, when that is smaller than its JPEG;
//! 5. every JPEG with Huffman tables made for the page ([`crate::jpeg`]).
//!
//! The numbers in [`SizeParams::of`] were set by measurement: `docs/ANDROID_SCAN_SIZE.md`.

use std::borrow::Cow;

use crate::ScanError;
use crate::encode::{Format, JPEG_QUALITY, encode_png_bilevel};
use crate::enhance::{Bitmap1, BwParams, black_white};
use crate::jpeg::{Chroma, Samples};
use crate::look::{Look, Pixels};
use crate::page_kind::PageKind;
use crate::preset::Preset;

/// The person's choice of file size at Save (#505).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub enum SizeChoice {
    /// Full size, quality 92: what every page was before #505.
    #[default]
    Best,
    /// For reading and printing.
    Balanced,
    /// For sending by mail or chat.
    Small,
    /// For forms and letters that only have to be read.
    Smallest,
}

impl SizeChoice {
    pub const ALL: [SizeChoice; 4] = [SizeChoice::Best, SizeChoice::Balanced, SizeChoice::Small, SizeChoice::Smallest];

    /// The word the app and the log use.
    pub fn id(self) -> &'static str {
        match self {
            SizeChoice::Best => "best",
            SizeChoice::Balanced => "balanced",
            SizeChoice::Small => "small",
            SizeChoice::Smallest => "smallest",
        }
    }

    /// The choice named `id`; `None` for an unknown word.
    pub fn from_id(id: &str) -> Option<SizeChoice> {
        SizeChoice::ALL.into_iter().find(|c| c.id() == id)
    }
}

/// What a size choice allows.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct SizeParams {
    /// JPEG quality.
    pub quality: u8,
    /// The most dots per inch a page is stored at (`None`: its own).
    pub max_dpi: Option<u16>,
    /// The fewest dots per inch the stroke rule may take a page down to.
    pub min_dpi: u16,
    /// Pixels wanted across the page's typical stroke.
    pub stroke_px: f32,
    /// The most dots per inch for a page with photographs on it.
    pub photo_dpi: Option<u16>,
    /// Colour at half resolution across and down.
    pub half_chroma: bool,
    /// A page of text alone may be stored as 1 bit.
    pub bilevel: bool,
}

impl SizeParams {
    pub fn of(choice: SizeChoice) -> SizeParams {
        match choice {
            SizeChoice::Best => {
                SizeParams { quality: JPEG_QUALITY, max_dpi: None, min_dpi: 0, stroke_px: 0.0, photo_dpi: None, half_chroma: false, bilevel: false }
            }
            SizeChoice::Balanced => {
                SizeParams { quality: 75, max_dpi: Some(250), min_dpi: 200, stroke_px: 3.0, photo_dpi: Some(300), half_chroma: true, bilevel: false }
            }
            SizeChoice::Small => {
                SizeParams { quality: 60, max_dpi: Some(200), min_dpi: 150, stroke_px: 2.5, photo_dpi: Some(200), half_chroma: true, bilevel: true }
            }
            SizeChoice::Smallest => {
                SizeParams { quality: 50, max_dpi: Some(150), min_dpi: 120, stroke_px: 2.0, photo_dpi: Some(150), half_chroma: true, bilevel: true }
            }
        }
    }
}

/// A pixel counts as coloured when its channels differ by more than this (of 255).
pub const COLOUR_CHROMA: u8 = 28;

/// A page is coloured when more than this share of its pixels is.
pub const COLOUR_SHARE: f64 = 0.0005;

/// A page with more than this share of photographs keeps the choice's `photo_dpi`.
pub const PHOTO_SHARE: f64 = 0.02;

/// A 1-bit page needs at least this resolution (`page::BW_MIN_DPI` makes the Black and white look
/// at twice the size below it).
pub const BILEVEL_MIN_DPI: u16 = 200;

/// The most a 1-bit page is stored at.
pub const BILEVEL_MAX_DPI: u16 = 300;

/// How a page was stored.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Encoding {
    JpegRgb,
    JpegGrey,
    /// One bit per pixel, which the PDF stores as CCITT Group 4.
    BilevelG4,
}

impl Encoding {
    pub fn id(self) -> &'static str {
        match self {
            Encoding::JpegRgb => "jpeg_rgb",
            Encoding::JpegGrey => "jpeg_grey",
            Encoding::BilevelG4 => "bilevel_g4",
        }
    }
}

/// What storing a page decided (#505): the log's and the kept page file's fields.
#[derive(Clone, Debug, PartialEq)]
pub struct Encoded {
    pub choice: SizeChoice,
    /// The automatic encoding was on.
    pub auto: bool,
    pub encoding: Encoding,
    /// The page's size in pixels as stored.
    pub width: u32,
    pub height: u32,
    /// Its resolution as stored.
    pub dpi: u16,
    /// The JPEG quality (0 for a 1-bit page).
    pub quality: u8,
    /// Colour stored at half resolution.
    pub half_chroma: bool,
    /// The bytes the page takes in the PDF: the JPEG as it is, or a 1-bit page's Group 4 stream.
    pub bytes: u64,
    /// Why, as short words joined by `+`: `colour`, `no_colour`, `stroke`, `cap`, `photo`,
    /// `own_size`, `text_only`, `smaller_as_jpeg`, `look_bw`, `as_before`.
    pub why: String,
}

/// The page stored: its file's bytes and format, and what was decided.
pub struct Stored {
    pub bytes: Vec<u8>,
    pub format: Format,
    pub encoded: Encoded,
}

/// A made page, ready to be stored: its pixels after the look, the finger step and the marks.
#[derive(Clone, Copy, Debug)]
pub struct Made<'a> {
    pub pixels: &'a Pixels,
    pub width: u32,
    pub height: u32,
    pub dpi: u16,
    pub look: Look,
    /// The kind of document Clean scan took the page for, when document types are on.
    pub kind: Option<&'a PageKind>,
}

/// A page's tones, colour or grey.
#[derive(Clone, Copy)]
enum Tones<'a> {
    Rgb(&'a [u8]),
    Grey(&'a [u8]),
}

impl<'a> Tones<'a> {
    /// One grey byte per pixel, JFIF's luma for colour (as a JPEG decoder gives it back).
    fn grey(self) -> Cow<'a, [u8]> {
        match self {
            Tones::Grey(g) => Cow::Borrowed(g),
            Tones::Rgb(rgb) => Cow::Owned(rgb.as_chunks::<3>().0.iter().map(|c| luma(c[0], c[1], c[2])).collect()),
        }
    }
}

/// The made page stored as `choice` and `auto` say (`auto` off: exactly as before #505, whatever
/// the choice).
pub fn store(made: &Made<'_>, choice: SizeChoice, auto: bool) -> Result<Stored, ScanError> {
    if !auto {
        return store_as_before(made.pixels, made.width, made.height, made.dpi, choice);
    }
    match made.pixels {
        Pixels::Bilevel(bits) => bilevel_stored(bits, made.dpi, choice, &["look_bw"], g4_len(bits)),
        Pixels::Rgb(rgb) => store_tones(made, Tones::Rgb(rgb), choice),
        Pixels::Grey(grey) => store_tones(made, Tones::Grey(grey), choice),
    }
}

/// A colour or grey page stored by what is on it (see the module's steps).
fn store_tones(made: &Made<'_>, tones: Tones<'_>, choice: SizeChoice) -> Result<Stored, ScanError> {
    let (width, height, dpi, kind) = (made.width, made.height, made.dpi, made.kind);
    let p = SizeParams::of(choice);
    let mut why: Vec<&'static str> = Vec::new();
    let colour = match tones {
        Tones::Rgb(rgb) => coloured_share(rgb) > COLOUR_SHARE,
        Tones::Grey(_) => false,
    };
    why.push(if colour { "colour" } else { "no_colour" });
    let photo = kind.is_some_and(|k| k.features.photo_share > PHOTO_SHARE || k.preset == Preset::Photo || k.whole_photo);
    let target = target_dpi(dpi, kind.and_then(|k| k.features.stroke_mm), photo, &p, &mut why);
    // the made pixels in the colour they keep, at the size they need
    let (plane, channels): (Cow<'_, [u8]>, usize) = match tones {
        Tones::Rgb(rgb) if colour => (Cow::Borrowed(rgb), 3),
        _ => (tones.grey(), 1),
    };
    let (ow, oh) = scaled_size(width, height, dpi, target);
    let resized = if (ow, oh) == (width, height) { plane } else { Cow::Owned(resize_area(&plane, width, height, channels, ow, oh)) };
    let half = channels == 3 && p.half_chroma;
    let samples = if channels == 3 { Samples::Rgb(&resized, if half { Chroma::Half } else { Chroma::Full }) } else { Samples::Grey(&resized) };
    let jpeg = crate::jpeg::encode(samples, ow, oh, p.quality, target)?;
    let encoding = if channels == 3 { Encoding::JpegRgb } else { Encoding::JpegGrey };
    // a page of text alone: 1 bit, when that is smaller
    let text_only = p.bilevel
        && channels == 1
        && !photo
        && kind.is_some_and(|k| matches!(k.preset, Preset::Document | Preset::Receipt | Preset::BookPage))
        && made.look != Look::Original
        && dpi >= BILEVEL_MIN_DPI;
    if text_only {
        let bw_dpi = dpi.min(BILEVEL_MAX_DPI);
        let (bw_w, bw_h) = scaled_size(width, height, dpi, bw_dpi);
        let grey = tones.grey();
        let grey = if (bw_w, bw_h) == (width, height) { grey } else { Cow::Owned(resize_area(&grey, width, height, 1, bw_w, bw_h)) };
        if let Some(bits) = binarise(&grey, bw_w, bw_h, bw_dpi) {
            let g4 = g4_len(&bits);
            if g4.is_some_and(|g4| g4 < jpeg.len() as u64) {
                why.push("text_only");
                return bilevel_stored(&bits, bw_dpi, choice, &why, g4);
            }
            why.push("smaller_as_jpeg");
        }
    }
    let bytes = jpeg.len() as u64;
    Ok(Stored {
        bytes: jpeg,
        format: Format::Jpeg,
        encoded: Encoded {
            choice,
            auto: true,
            encoding,
            width: ow,
            height: oh,
            dpi: target,
            quality: p.quality,
            half_chroma: half,
            bytes,
            why: why.join("+"),
        },
    })
}

/// Every page as before #505: a JPEG at quality 92 through the image crate, a 1-bit PNG for
/// Black and white.
fn store_as_before(pixels: &Pixels, width: u32, height: u32, dpi: u16, choice: SizeChoice) -> Result<Stored, ScanError> {
    use crate::encode::{Raster, encode_jpeg};
    let raster = |pixels, grey| Raster { pixels, grey, width, height, dpi };
    let (bytes, encoding) = match pixels {
        Pixels::Rgb(rgb) => (encode_jpeg(&raster(rgb, false), JPEG_QUALITY)?, Encoding::JpegRgb),
        Pixels::Grey(grey) => (encode_jpeg(&raster(grey, true), JPEG_QUALITY)?, Encoding::JpegGrey),
        Pixels::Bilevel(bits) => {
            return bilevel_stored(bits, dpi, choice, &["as_before"], g4_len(bits)).map(|mut s| {
                s.encoded.auto = false;
                s
            });
        }
    };
    let len = bytes.len() as u64;
    Ok(Stored {
        bytes,
        format: Format::Jpeg,
        encoded: Encoded {
            choice,
            auto: false,
            encoding,
            width,
            height,
            dpi,
            quality: JPEG_QUALITY,
            half_chroma: false,
            bytes: len,
            why: "as_before".into(),
        },
    })
}

/// A 1-bit page as a PNG; `g4` is its Group 4 stream's length when that was made.
fn bilevel_stored(bits: &Bitmap1, dpi: u16, choice: SizeChoice, why: &[&str], g4: Option<u64>) -> Result<Stored, ScanError> {
    let png = encode_png_bilevel(bits, dpi)?;
    // the PDF stores a 1-bit page as Group 4 when that is smaller than Flate (pdfcraft-create);
    // the PNG's length stands for Flate's
    let bytes = g4.unwrap_or(png.len() as u64).min(png.len() as u64);
    Ok(Stored {
        bytes: png,
        format: Format::Png,
        encoded: Encoded {
            choice,
            auto: true,
            encoding: Encoding::BilevelG4,
            width: bits.width,
            height: bits.height,
            dpi,
            quality: 0,
            half_chroma: false,
            bytes,
            why: why.join("+"),
        },
    })
}

/// The length of the page as a Group 4 stream.
fn g4_len(bits: &Bitmap1) -> Option<u64> {
    pdfcraft_filters::encode_g4(&bits.data, bits.width, bits.height).ok().map(|v| v.len() as u64)
}

/// The resolution a page at `dpi` is stored at.
fn target_dpi(dpi: u16, stroke_mm: Option<f64>, photo: bool, p: &SizeParams, why: &mut Vec<&'static str>) -> u16 {
    let Some(cap) = (if photo { p.photo_dpi } else { p.max_dpi }) else {
        why.push("own_size");
        return dpi;
    };
    // pixels across the typical stroke: stroke_px of them needs this many dots per inch
    let need = stroke_mm.filter(|s| s.is_finite() && *s > 0.0).map(|s| (f64::from(p.stroke_px) * 25.4 / s).ceil() as u16);
    let wanted = match need {
        Some(n) if n >= cap => {
            why.push("cap");
            cap
        }
        Some(n) => {
            why.push("stroke");
            n.max(p.min_dpi)
        }
        None => {
            why.push("cap");
            cap
        }
    };
    if photo {
        why.push("photo");
    }
    if wanted >= dpi {
        why.push("own_size");
        dpi
    } else {
        wanted
    }
}

/// The page's size in pixels at `to` dots per inch, from `w` × `h` at `from`; never enlarged.
pub fn scaled_size(w: u32, h: u32, from: u16, to: u16) -> (u32, u32) {
    if to >= from || from == 0 {
        return (w, h);
    }
    let s = f64::from(to) / f64::from(from);
    (((f64::from(w) * s).round() as u32).max(1), ((f64::from(h) * s).round() as u32).max(1))
}

/// The share of the pixels whose channels differ by more than [`COLOUR_CHROMA`].
pub fn coloured_share(rgb: &[u8]) -> f64 {
    let n = rgb.len() / 3;
    if n == 0 {
        return 0.0;
    }
    let coloured = rgb.as_chunks::<3>().0.iter().filter(|c| c[0].max(c[1]).max(c[2]) - c[0].min(c[1]).min(c[2]) > COLOUR_CHROMA).count();
    coloured as f64 / n as f64
}

/// JFIF's luma of a pixel, as a JPEG decoder gives it back.
fn luma(r: u8, g: u8, b: u8) -> u8 {
    (0.299 * f32::from(r) + 0.587 * f32::from(g) + 0.114 * f32::from(b)).round().clamp(0.0, 255.0) as u8
}

/// `pixels` (`w` × `h`, `channels` bytes each) averaged into `ow` × `oh` by exact area.
pub fn resize_area(pixels: &[u8], w: u32, h: u32, channels: usize, ow: u32, oh: u32) -> Vec<u8> {
    let (w, h, ow, oh) = (w as usize, h as usize, ow as usize, oh as usize);
    let xs = crate::tensor::area_weights(w, ow);
    let ys = crate::tensor::area_weights(h, oh);
    // across first, one row at a time, then down
    let mut across = vec![0f32; ow * channels * h];
    for y in 0..h {
        let row = &pixels[y * w * channels..(y + 1) * w * channels];
        let out = &mut across[y * ow * channels..(y + 1) * ow * channels];
        for (ox, (first, weights)) in xs.iter().enumerate() {
            for c in 0..channels {
                out[ox * channels + c] = weights.iter().enumerate().map(|(i, wt)| wt * f32::from(row[(first + i) * channels + c])).sum();
            }
        }
    }
    let mut out = vec![0u8; ow * oh * channels];
    for (oy, (first, weights)) in ys.iter().enumerate() {
        let dst = &mut out[oy * ow * channels..(oy + 1) * ow * channels];
        for (k, d) in dst.iter_mut().enumerate() {
            let v: f32 = weights.iter().enumerate().map(|(i, wt)| wt * across[(first + i) * ow * channels + k]).sum();
            *d = v.round().clamp(0.0, 255.0) as u8;
        }
    }
    out
}

/// A grey page made 1 bit with the Black and white look's own rule (#300), without dithering: a
/// page of text has nothing to dither.
fn binarise(grey: &[u8], w: u32, h: u32, dpi: u16) -> Option<Bitmap1> {
    let rgb = crate::Rgb { width: w, height: h, data: grey.iter().flat_map(|&v| [v, v, v]).collect() };
    let params = BwParams { dither_photos: false, dpi: f32::from(dpi), ..BwParams::default() };
    black_white(&rgb, &params)
}
