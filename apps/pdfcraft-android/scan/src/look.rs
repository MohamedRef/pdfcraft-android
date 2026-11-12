//! How a straightened page looks. For now each look is the engine's camera clean-up
//! (`pdfcraft_engine::camera_clean`), so pages look exactly as they did before this crate;
//! #298, #299 and #300 replace the bodies on purpose.

use pdfcraft_engine::camera_clean;

use crate::Rgb;

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
}

impl Look {
    fn engine(self) -> camera_clean::Look {
        match self {
            Look::Original => camera_clean::Look::Original,
            Look::Colour => camera_clean::Look::Colour,
            Look::Greyscale => camera_clean::Look::Greyscale,
            Look::BlackWhite => camera_clean::Look::BlackWhite,
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
    Some(match camera_clean::apply(&page.data, page.width, page.height, look.engine(), strength.min(camera_clean::MAX_LEVEL))? {
        camera_clean::Pixels::Rgb(v) => Pixels::Rgb(v),
        camera_clean::Pixels::Grey(v) => Pixels::Grey(v),
    })
}
