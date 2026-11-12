//! The **Auto** look (#298): a photographed page made to look like a flatbed scan. Shadows and
//! uneven light are divided out, paper becomes neutral white, ink goes near black, pen, stamps
//! and logos keep their colour, and fingers or printed dark areas are left natural.
//!
//! The steps and every constant were measured on ten real photos against what a reference
//! scanner produced from them; the issue has the tables. `tools/scan-eval/auto_reference.py` is
//! the prototype those numbers were first obtained with, and `tools/scan-eval/reference_pairs.py`
//! repeats the comparison.

mod background;
mod objects;
mod plane;
mod tone;

#[cfg(test)]
mod tests;

pub use plane::Mask;

use crate::Rgb;

/// The strength the constants were measured at.
pub const DEFAULT_STRENGTH: u8 = 60;
/// Below this strength the result is blended with the input, so the slider has no jump at 0.
const BLEND_BELOW: u8 = 20;
/// A straightened page smaller than this is enlarged to it before the tone curve (step 0): the
/// curve then turns small soft strokes into smooth solid ones.
pub const MIN_OUTPUT_PIXELS: f64 = 3_600_000.0;
/// ...by at most this factor per side.
pub const MAX_ENLARGE: f64 = 4.0;

/// What the paper becomes.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub enum PaperMode {
    /// Neutral white. The default: a photo cannot tell coloured paper from coloured light.
    #[default]
    White,
    /// The paper's own colour, evenly lit.
    Keep,
}

/// How to run [`auto`].
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct AutoParams {
    /// 0–100; 0 returns the page untouched.
    pub strength: u8,
    pub paper: PaperMode,
    /// Threads for the full-resolution pass (the bytes are the same for any number).
    pub threads: usize,
}

impl Default for AutoParams {
    fn default() -> Self {
        AutoParams { strength: DEFAULT_STRENGTH, paper: PaperMode::White, threads: 1 }
    }
}

/// The page with the look, and what was found on the way.
#[derive(Clone, Debug, PartialEq)]
pub struct AutoOutput {
    pub rgb: Rgb,
    /// Printed dark areas, photographs and fingers, at working size: later steps treat them
    /// gently (#299).
    pub objects: Mask,
    /// The closing radius used, in working pixels (0 when the page was left alone).
    pub closing_radius: u8,
}

/// How much a `w` × `h` page is enlarged per side so it has [`MIN_OUTPUT_PIXELS`] (1 when it is
/// large enough already).
pub fn enlargement(w: f64, h: f64) -> f64 {
    (MIN_OUTPUT_PIXELS / (w * h).max(1.0)).sqrt().clamp(1.0, MAX_ENLARGE)
}

/// Give `page` the Auto look. `None` when the page's buffer doesn't match its size.
pub fn auto(page: &Rgb, params: &AutoParams) -> Option<AutoOutput> {
    let (w, h) = (page.width as usize, page.height as usize);
    if page.data.len() != w.checked_mul(h)?.checked_mul(3)? {
        return None;
    }
    let untouched = |page: &Rgb| AutoOutput { rgb: page.clone(), objects: Mask::new(0, 0), closing_radius: 0 };
    // too small to tell paper from ink
    if params.strength == 0 || w < 16 || h < 16 {
        return Some(untouched(page));
    }
    let bg = background::background(page, params.threads > 1);
    let strength = params.strength.clamp(BLEND_BELOW, 100);
    let renderer = tone::Renderer::new(page, &bg.light, tone::Tone::at(strength), params.paper == PaperMode::Keep);
    let mut data = renderer.render(params.threads);
    if params.strength < BLEND_BELOW {
        let (a, n) = (u32::from(params.strength), u32::from(BLEND_BELOW));
        for (o, &i) in data.iter_mut().zip(&page.data) {
            *o = ((u32::from(*o) * a + u32::from(i) * (n - a) + n / 2) / n) as u8;
        }
    }
    Some(AutoOutput { rgb: Rgb { width: page.width, height: page.height, data }, objects: bg.objects, closing_radius: bg.radius as u8 })
}
