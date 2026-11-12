//! How a straightened page looks. **Auto** (#298) and **black and white** (#300) are this crate's
//! own ([`crate::enhance`]). The other looks are still the engine's camera clean-up
//! (`pdfcraft_engine::camera_clean`), so they look exactly as they did before this crate; #299
//! and #301 replace them on purpose.

use pdfcraft_engine::camera_clean;

use crate::Rgb;
use crate::enhance::{AutoParams, Bitmap1, BwParams, DEFAULT_SHARPEN, PaperMode, Shape, auto_with_photos, black_white};
use crate::photo_mask::PhotoMask;

/// A page's look.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub enum Look {
    /// The straightened photo as taken.
    Original,
    /// Cleaned up, colour ink kept.
    #[default]
    Colour,
    /// Cleaned up, in shades of grey.
    Greyscale,
    /// Black ink on white paper only.
    BlackWhite,
    /// Like a flatbed scan in one step: no shadows, white paper, dark ink, real colours (#298).
    Auto,
}

impl Look {
    /// The engine look behind this one; `None` for the looks this crate makes itself.
    fn engine(self) -> Option<camera_clean::Look> {
        match self {
            Look::Original => Some(camera_clean::Look::Original),
            Look::Colour => Some(camera_clean::Look::Colour),
            Look::Greyscale => Some(camera_clean::Look::Greyscale),
            Look::BlackWhite | Look::Auto => None,
        }
    }
}

/// A page's pixels with its look: RGB8, one grey byte per pixel, or one bit per pixel.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Pixels {
    Rgb(Vec<u8>),
    Grey(Vec<u8>),
    Bilevel(Bitmap1),
}

/// What a look needs to know besides the pixels.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct LookParams {
    /// 0–100.
    pub strength: u8,
    /// Threads (only the looks of this crate use more than one; the bytes are the same for any
    /// number).
    pub threads: usize,
    /// The page's resolution, for the looks that measure in millimetres.
    pub dpi: f32,
    /// How much Auto sharpens print, 0–100 (#299); 0 for none.
    pub sharpen: u8,
    /// Whether Auto smooths the grain of a page that has a lot of it (#299).
    pub denoise: bool,
    /// Auto keeps the paper's own colour instead of whitening it (a document type's setting, #299).
    pub keep_paper: bool,
    /// A document type's change to Auto's tone curve: extra gamma, S-curve, colour factor (#299).
    pub shape: Shape,
}

/// A page with its look, and what the look found out about the page on the way.
#[derive(Clone, Debug, PartialEq)]
pub struct Applied {
    pub pixels: Pixels,
    /// The page's grain in grey levels (Auto measures it, #299); `None` for the other looks.
    pub noise: Option<f32>,
}

/// Give `page` the `look` at `strength` (0–100), taking it to be at 300 dpi. `None` when the
/// page's buffer doesn't match its size.
pub fn apply(page: &Rgb, look: Look, strength: u8) -> Option<Pixels> {
    apply_with(
        page,
        look,
        &LookParams { strength, threads: 1, dpi: 300.0, sharpen: DEFAULT_SHARPEN, denoise: true, keep_paper: false, shape: Shape::default() },
    )
}

/// Give `page` the `look`.
pub fn apply_with(page: &Rgb, look: Look, params: &LookParams) -> Option<Pixels> {
    apply_full(page, look, params).map(|applied| applied.pixels)
}

/// Give `page` the `look`, and say what the look measured.
pub fn apply_full(page: &Rgb, look: Look, params: &LookParams) -> Option<Applied> {
    apply_with_photos(page, look, params, None)
}

/// [`apply_full`], with the photographs found on the page (#299): Auto sharpens them gently.
pub fn apply_with_photos(page: &Rgb, look: Look, params: &LookParams, photos: Option<&PhotoMask>) -> Option<Applied> {
    let LookParams { strength, threads, dpi, sharpen, denoise, keep_paper, shape } = *params;
    let Some(engine) = look.engine() else {
        return Some(match look {
            Look::BlackWhite => {
                Applied { pixels: Pixels::Bilevel(black_white(page, &BwParams { strength, dither_photos: true, dpi, threads })?), noise: None }
            }
            _ => {
                let dpi = if dpi.is_finite() { dpi.clamp(1.0, 2400.0) as u16 } else { 300 };
                let paper = if keep_paper { PaperMode::Keep } else { PaperMode::White };
                let made = auto_with_photos(page, &AutoParams { strength, paper, threads, sharpen, denoise, dpi, shape }, photos)?;
                Applied { pixels: Pixels::Rgb(made.rgb.data), noise: Some(made.noise) }
            }
        });
    };
    let pixels = match camera_clean::apply(&page.data, page.width, page.height, engine, strength.min(camera_clean::MAX_LEVEL))? {
        camera_clean::Pixels::Rgb(v) => Pixels::Rgb(v),
        camera_clean::Pixels::Grey(v) => Pixels::Grey(v),
    };
    Some(Applied { pixels, noise: None })
}
