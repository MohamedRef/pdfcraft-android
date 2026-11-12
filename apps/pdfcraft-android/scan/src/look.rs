//! How a straightened page looks. **Auto** (#298) and **black and white** (#300) are this crate's
//! own ([`crate::enhance`]). The other looks are still the engine's camera clean-up
//! (`pdfcraft_engine::camera_clean`), so they look exactly as they did before this crate; #299
//! and #301 replace them on purpose.

use pdfcraft_engine::camera_clean;

use crate::Rgb;
use crate::enhance::{AutoParams, Bitmap1, BwParams, PaperMode, auto, black_white};

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
}

/// Give `page` the `look` at `strength` (0–100), taking it to be at 300 dpi. `None` when the
/// page's buffer doesn't match its size.
pub fn apply(page: &Rgb, look: Look, strength: u8) -> Option<Pixels> {
    apply_with(page, look, &LookParams { strength, threads: 1, dpi: 300.0 })
}

/// Give `page` the `look`.
pub fn apply_with(page: &Rgb, look: Look, params: &LookParams) -> Option<Pixels> {
    let LookParams { strength, threads, dpi } = *params;
    let Some(engine) = look.engine() else {
        return Some(match look {
            Look::BlackWhite => Pixels::Bilevel(black_white(page, &BwParams { strength, dither_photos: true, dpi, threads })?),
            _ => Pixels::Rgb(auto(page, &AutoParams { strength, paper: PaperMode::White, threads })?.rgb.data),
        });
    };
    Some(match camera_clean::apply(&page.data, page.width, page.height, engine, strength.min(camera_clean::MAX_LEVEL))? {
        camera_clean::Pixels::Rgb(v) => Pixels::Rgb(v),
        camera_clean::Pixels::Grey(v) => Pixels::Grey(v),
    })
}
