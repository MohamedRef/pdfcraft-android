//! The **Auto** look (#298): a photographed page made to look like a flatbed scan. Shadows and
//! uneven light are divided out, paper becomes neutral white, ink goes near black, pen, stamps
//! and logos keep their colour, and fingers or printed dark areas are left natural.
//!
//! The steps and every constant were measured on ten real photos against what a reference
//! scanner produced from them; the issue has the tables. `tools/scan-eval/auto_reference.py` is
//! the prototype those numbers were first obtained with, and `tools/scan-eval/reference_pairs.py`
//! repeats the comparison.

mod background;
mod bw;
mod detail;
pub mod learned;
mod objects;
mod plane;
mod tone;
mod whiteboard;

#[cfg(test)]
mod tests;
#[cfg(test)]
mod tests_bw;
#[cfg(test)]
mod tests_detail;
#[cfg(test)]
mod tests_learned;

pub use bw::{Bitmap1, BwParams, DEFAULT_BW_STRENGTH, black_white};
pub use plane::Mask;
pub use whiteboard::{WHITEBOARD_COLOUR_FACTOR, whiteboard_tone};

/// The whiteboard's S-curve, for the tests of the page pipeline.
#[cfg(test)]
pub(crate) fn s_curve_for_tests(t: f32) -> f32 {
    tone::s_curve(t)
}

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

/// How much print is sharpened unless told otherwise, of 100 (#299): low, because on the
/// reference photos the tone curve alone already gives edges as crisp as the reference's.
pub const DEFAULT_SHARPEN: u8 = 30;

/// A page with a side shorter than this is too small to tell paper from ink.
pub(crate) const MIN_SIDE: usize = 16;

/// What the paper becomes.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub enum PaperMode {
    /// Neutral white. The default: a photo cannot tell coloured paper from coloured light.
    #[default]
    White,
    /// The paper's own colour, evenly lit.
    Keep,
}

/// A kind of document's change to the tone curve (#299): `preset::PresetParams`' extra gamma,
/// S-curve and colour factor. The default changes nothing.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Shape {
    /// Added to the curve's gamma; positive darkens faint print (a receipt's +0.3).
    pub extra_gamma: f32,
    /// The whiteboard's S-curve in place of the gamma curve.
    pub s_curve: bool,
    /// Multiplies how much colour is put back (a whiteboard's ×1.5 for its markers).
    pub colour_factor: f32,
}

impl Default for Shape {
    fn default() -> Self {
        Shape { extra_gamma: 0.0, s_curve: false, colour_factor: 1.0 }
    }
}

/// How to run [`auto`].
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct AutoParams {
    /// 0–100; 0 returns the page untouched.
    pub strength: u8,
    pub paper: PaperMode,
    /// Threads for the full-resolution pass (the bytes are the same for any number).
    pub threads: usize,
    /// How much print is sharpened, 0–100; 0 leaves it as it is (#299).
    pub sharpen: u8,
    /// Smooth the grain of a page that has a lot of it (a photo taken in dim light, #299).
    pub denoise: bool,
    /// The page's resolution: sharpening looks one pixel around at 300 dpi and wider above.
    pub dpi: u16,
    /// A kind of document's change to the tone curve (#299).
    pub shape: Shape,
}

impl Default for AutoParams {
    fn default() -> Self {
        AutoParams {
            strength: DEFAULT_STRENGTH,
            paper: PaperMode::White,
            threads: 1,
            sharpen: DEFAULT_SHARPEN,
            denoise: true,
            dpi: 300,
            shape: Shape::default(),
        }
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
    /// The page's grain: the standard deviation of its noise on blank paper, in grey levels of
    /// 255 (0 when the page was left alone). At 2 or more it was smoothed, if that was asked for.
    pub noise: f32,
}

/// How much a `w` × `h` page is enlarged per side so it has [`MIN_OUTPUT_PIXELS`] (1 when it is
/// large enough already).
pub fn enlargement(w: f64, h: f64) -> f64 {
    (MIN_OUTPUT_PIXELS / (w * h).max(1.0)).sqrt().clamp(1.0, MAX_ENLARGE)
}

/// Give `page` the Auto look. `None` when the page's buffer doesn't match its size.
pub fn auto(page: &Rgb, params: &AutoParams) -> Option<AutoOutput> {
    auto_with_photos(page, params, None)
}

/// [`auto`], with the photographs `photos` found on the page (#299, the block photo mask)
/// sharpened as gently as the objects the look finds itself.
pub fn auto_with_photos(page: &Rgb, params: &AutoParams, photos: Option<&crate::photo_mask::PhotoMask>) -> Option<AutoOutput> {
    let (w, h) = (page.width as usize, page.height as usize);
    if page.data.len() != w.checked_mul(h)?.checked_mul(3)? {
        return None;
    }
    let untouched = |page: &Rgb| AutoOutput { rgb: page.clone(), objects: Mask::new(0, 0), closing_radius: 0, noise: 0.0 };
    // too small to tell paper from ink
    if params.strength == 0 || w < MIN_SIDE || h < MIN_SIDE {
        return Some(untouched(page));
    }
    let bg = background::background(page, params.threads > 1);
    let strength = params.strength.clamp(BLEND_BELOW, 100);
    // less grain where there is a lot, then crisper print, both before the curve (#299)
    let noise = detail::noise_sigma(page, &bg.light);
    let mut detailed: Option<Rgb> = None;
    if params.denoise && noise >= detail::NOISE_FLOOR {
        detailed = Some(Rgb { width: page.width, height: page.height, data: detail::denoise(page, noise, params.threads) });
    }
    if params.sharpen > 0 {
        let k_sharp = f32::from(params.sharpen.min(100)) / 100.0;
        let gentle = photos.map_or_else(|| bg.objects.clone(), |p| with_photos(&bg.objects, p));
        let data = detail::sharpen(detailed.as_ref().unwrap_or(page), k_sharp, noise, f32::from(params.dpi), Some(&gentle), params.threads);
        detailed = Some(Rgb { width: page.width, height: page.height, data });
    }
    let renderer = tone::Renderer::new(
        detailed.as_ref().unwrap_or(page),
        &bg.light,
        tone::Tone::at(strength).shaped(params.shape),
        params.paper == PaperMode::Keep,
    );
    let mut data = renderer.render(params.threads);
    if params.strength < BLEND_BELOW {
        let (a, n) = (u32::from(params.strength), u32::from(BLEND_BELOW));
        for (o, &i) in data.iter_mut().zip(&page.data) {
            *o = ((u32::from(*o) * a + u32::from(i) * (n - a) + n / 2) / n) as u8;
        }
    }
    Some(AutoOutput { rgb: Rgb { width: page.width, height: page.height, data }, objects: bg.objects, closing_radius: bg.radius as u8, noise })
}

/// The cells of `objects` (at its own size) set, and those where `photos` (a block mask over the
/// same page) weighs at least a half. A mask with no cells takes the photo mask's block grid.
pub(crate) fn with_photos(objects: &Mask, photos: &crate::photo_mask::PhotoMask) -> Mask {
    let (w, h) = if objects.width > 0 && objects.height > 0 && objects.data.len() == objects.width * objects.height {
        (objects.width, objects.height)
    } else {
        (photos.cols, photos.rows)
    };
    if w == 0 || h == 0 || photos.cols == 0 || photos.rows == 0 || photos.weight.len() != photos.cols * photos.rows {
        return objects.clone();
    }
    let mut out = Mask::new(w, h);
    for y in 0..h {
        let row = ((y * 2 + 1) * photos.rows / (h * 2)).min(photos.rows - 1);
        for x in 0..w {
            let col = ((x * 2 + 1) * photos.cols / (w * 2)).min(photos.cols - 1);
            let object = objects.data.get(y * objects.width + x).copied().unwrap_or(false) && objects.width == w;
            out.data[y * w + x] = object || photos.weight[row * photos.cols + col] >= 0.5;
        }
    }
    out
}

/// `page` with its light evened out and nothing else: each channel divided by the paper's colour
/// under the local light, so paper is near white and ink keeps its depth (#299: the "lighting
/// correction" the photo mask and the ink measures of the document types read). No tone curve, so
/// a photograph keeps its middle tones. `None` for a malformed or tiny page.
pub(crate) fn evened(page: &Rgb) -> Option<Rgb> {
    let (w, h) = (page.width as usize, page.height as usize);
    if page.data.len() != w.checked_mul(h)?.checked_mul(3)? || w < MIN_SIDE || h < MIN_SIDE {
        return None;
    }
    let bg = background::background(page, false);
    let (lw, lh) = (bg.light[0].w, bg.light[0].h);
    if lw == 0 || lh == 0 {
        return None;
    }
    let mut data = Vec::with_capacity(page.data.len());
    for y in 0..h {
        let ly = (y * lh / h).min(lh - 1);
        for x in 0..w {
            let lx = (x * lw / w).min(lw - 1);
            let i = (y * w + x) * 3;
            for c in 0..3 {
                let light = bg.light[c].d[ly * lw + lx].max(0.02);
                data.push((f32::from(page.data[i + c]) / light).round().clamp(0.0, 255.0) as u8);
            }
        }
    }
    Some(Rgb { width: page.width, height: page.height, data })
}
