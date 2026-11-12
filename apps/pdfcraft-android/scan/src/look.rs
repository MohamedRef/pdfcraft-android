//! How a straightened page looks. **Auto** is this crate's own enhancement (#298, [`crate::enhance`]).
//! The other looks are still the engine's camera clean-up (`pdfcraft_engine::camera_clean`), so
//! they look exactly as they did before this crate; #299, #300 and #301 replace them on purpose.

use pdfcraft_engine::camera_clean;

use crate::Rgb;
use crate::enhance::{AutoParams, PaperMode, auto};

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
            Look::BlackWhite => Some(camera_clean::Look::BlackWhite),
            Look::Auto => None,
        }
    }
}

/// A page's pixels with its look: RGB8, or one grey byte per pixel.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Pixels {
    Rgb(Vec<u8>),
    Grey(Vec<u8>),
}

/// Give `page` the `look` at `strength` (0–100; for black and white the threshold). `None` when
/// the page's buffer doesn't match its size.
pub fn apply(page: &Rgb, look: Look, strength: u8) -> Option<Pixels> {
    apply_with_threads(page, look, strength, 1)
}

/// [`apply`] on up to `threads` threads (only Auto uses more than one; the bytes are the same for
/// any number).
pub fn apply_with_threads(page: &Rgb, look: Look, strength: u8, threads: usize) -> Option<Pixels> {
    let Some(engine) = look.engine() else {
        return Some(Pixels::Rgb(auto(page, &AutoParams { strength, paper: PaperMode::White, threads })?.rgb.data));
    };
    Some(match camera_clean::apply(&page.data, page.width, page.height, engine, strength.min(camera_clean::MAX_LEVEL))? {
        camera_clean::Pixels::Rgb(v) => Pixels::Rgb(v),
        camera_clean::Pixels::Grey(v) => Pixels::Grey(v),
    })
}
