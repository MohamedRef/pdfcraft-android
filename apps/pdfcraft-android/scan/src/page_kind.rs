//! What kind of document a straightened page is, and the Clean scan settings for it (#299,
//! S13 section 5): the measurements `preset::classify` reads, taken from the page itself.
//!
//! This wires `preset` and `photo_mask` (#382) into the page pipeline. The page is measured on a
//! working copy about 1000 pixels long, lit evenly by the Auto look's own light map (each channel
//! divided by the paper's colour under the local light, with no tone curve), because both blocks
//! expect "the page after lighting correction". Two measurements are taken before that correction, as their blocks ask:
//! the share of bright pixels (whitened paper would all count) and the gutter's dark band (the
//! correction exists to remove it).
//!
//! The measurements depend only on the straightened page, so a `PageBase` keeps them once taken
//! (`Measured`, #427): the preview's looks and strengths reuse them instead of measuring again.
//!
//! Pure Rust and deterministic: the same page gives the same answer on any number of threads.

use crate::Rgb;
use crate::aspect::Paper;
use crate::enhance::evened;
use crate::photo::Luma;
use crate::photo_mask::photo_mask;
use crate::preset::{PageFeatures, Preset, PresetParams, classify, gutter_reading, ink_features, params};

/// The working copy's longer side is shrunk to this many pixels or a little more (whole steps).
const WORKING_SIDE: u32 = 1000;
/// Millimetres in an inch.
const MM_PER_INCH: f64 = 25.4;

/// How the Clean scan look picks its settings for a page (#299).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum PresetChoice {
    /// No document types: the look's own strength and sharpening, as before #299 (a testing choice).
    #[default]
    Off,
    /// The kind is worked out from the page.
    Auto,
    /// The person chose the kind for this page.
    Fixed(Preset),
}

/// The kind a page was given, the settings that come with it, and what it was measured as.
#[derive(Clone, Debug, PartialEq)]
pub struct PageKind {
    pub preset: Preset,
    /// The person chose it (`PresetChoice::Fixed`); false when it was worked out.
    pub chosen: bool,
    pub params: PresetParams,
    /// What the page measured as (also for a chosen kind, so the log can show what Auto would have read).
    pub features: PageFeatures,
    /// The photo mask found the whole page to be a picture.
    pub whole_photo: bool,
}

/// The preset a short word names ("receipt", "id_card", …: `Preset::id`), or `None`.
pub fn preset_from_id(id: &str) -> Option<Preset> {
    Preset::ALL.into_iter().find(|p| p.id() == id)
}

/// The page shrunk by a whole step so its longer side is about `WORKING_SIDE` (box average), and
/// the step. A page already that small is copied as it is.
fn working_copy(page: &Rgb) -> Option<(Rgb, u32)> {
    let (w, h) = (page.width, page.height);
    let n = (w as usize).checked_mul(h as usize)?;
    if w == 0 || h == 0 || page.data.len() != n.checked_mul(3)? {
        return None;
    }
    let step = (w.max(h) / WORKING_SIDE).max(1);
    if step == 1 {
        return Some((page.clone(), 1));
    }
    let (sw, sh) = (w / step, h / step);
    let mut data = Vec::with_capacity(sw as usize * sh as usize * 3);
    let area = u64::from(step) * u64::from(step);
    for y in 0..sh {
        for x in 0..sw {
            let mut sum = [0u64; 3];
            for dy in 0..step {
                let row = ((y * step + dy) as usize * w as usize + (x * step) as usize) * 3;
                for px in page.data.get(row..row + step as usize * 3)?.as_chunks::<3>().0 {
                    for (s, &v) in sum.iter_mut().zip(px) {
                        *s += u64::from(v);
                    }
                }
            }
            data.extend(sum.map(|s| ((s + area / 2) / area) as u8));
        }
    }
    Some((Rgb { width: sw, height: sh, data }, step))
}

fn luma_of(page: &Rgb) -> Luma {
    let data = page.data.as_chunks::<3>().0.iter().map(|&p| crate::luma(p)).collect();
    Luma { width: page.width, height: page.height, data }
}

/// What a straightened page measures as, before the request's own inputs (the curved sides and
/// the gutter rule) are put in: what a `PageBase` keeps (#427).
#[derive(Clone, Debug, PartialEq)]
pub struct Measured {
    /// The features with no curved side, and `gutter_band` by the strict rule.
    pub features: PageFeatures,
    /// The gutter's strict reading (`gutter_band` holds it too; kept so the rule can be swapped).
    pub gutter_strict: bool,
    /// The photo mask found the whole page to be a picture.
    pub whole_photo: bool,
}

impl Measured {
    /// The features for a page whose edge detection found `curved_sides` curved sides, under the
    /// strict gutter rule (`strict_gutter`) or the first one.
    pub fn features(&self, curved_sides: u8, strict_gutter: bool) -> PageFeatures {
        PageFeatures {
            curved_sides: curved_sides.min(4),
            gutter_band: if strict_gutter { self.gutter_strict } else { self.features.gutter_loose },
            ..self.features.clone()
        }
    }
}

/// What `page` (straightened, before its look, at `dpi`, matched to `paper`) measures as. The
/// page's edge detection said `curved_sides` of its four sides are curved. `gutter_band` is the
/// strict rule's (#427). `None` for a page too small or malformed to measure.
pub fn page_features(page: &Rgb, dpi: u16, paper: Paper, curved_sides: u8) -> Option<(PageFeatures, bool)> {
    let measured = measure(page, dpi, paper)?;
    Some((measured.features(curved_sides, true), measured.whole_photo))
}

/// Takes the measurements of `page` (see `page_features`).
pub fn measure(page: &Rgb, dpi: u16, paper: Paper) -> Option<Measured> {
    let (small, step) = working_copy(page)?;
    let small_dpi = f32::from(dpi.max(1)) / step as f32;
    // bright pixels and the gutter before the light is evened out (see the module's comment)
    let bright_share = ink_features(&small, small_dpi)?.bright_share;
    let gutter = gutter_reading(&luma_of(&small));
    let lit = evened(&small)?;
    let ink = ink_features(&lit, small_dpi)?;
    let mask = photo_mask(&lit);
    let (long, short) = (page.width.max(page.height), page.width.min(page.height));
    let short_mm = match paper {
        Paper::Custom => None,
        _ => Some(f64::from(short) / f64::from(dpi.max(1)) * MM_PER_INCH),
    };
    let features = PageFeatures {
        ratio: f64::from(long) / f64::from(short.max(1)),
        short_mm,
        id_card: paper == Paper::IdCard,
        ink_share: ink.ink_share,
        colour_ink_share: ink.colour_ink_share,
        photo_share: mask.as_ref().map_or(0.0, |m| m.share),
        stroke_mm: ink.stroke_mm,
        curved_sides: 0,
        gutter_band: gutter.strict,
        gutter_loose: gutter.loose,
        gutter_cover: gutter.cover,
        bright_share,
    };
    Some(Measured { features, gutter_strict: gutter.strict, whole_photo: mask.is_some_and(|m| m.whole_page) })
}

/// The kind of `page` and its settings under `choice`, with the strict gutter rule (#427); `None`
/// when presets are off or the page cannot be measured.
pub fn page_kind(page: &Rgb, dpi: u16, paper: Paper, curved_sides: u8, choice: PresetChoice) -> Option<PageKind> {
    if choice == PresetChoice::Off {
        return None;
    }
    kind_of(&measure(page, dpi, paper)?, curved_sides, choice, true)
}

/// The kind under `choice` of a page measured as `measured` whose edge detection found
/// `curved_sides` curved sides, by the strict gutter rule or the first one; `None` when presets
/// are off.
pub fn kind_of(measured: &Measured, curved_sides: u8, choice: PresetChoice, strict_gutter: bool) -> Option<PageKind> {
    if choice == PresetChoice::Off {
        return None;
    }
    let features = measured.features(curved_sides, strict_gutter);
    let whole_photo = measured.whole_photo;
    let (preset, chosen) = match choice {
        PresetChoice::Fixed(p) => (p, true),
        _ => (classify(&features), false),
    };
    Some(PageKind { preset, chosen, params: params(preset), features, whole_photo })
}
