//! What kind of document a straightened page is, and the Clean scan settings for it (#299,
//! S13 section 5): the measurements `preset::classify` reads, taken from the page itself.
//!
//! This wires `preset` and `photo_mask` (#382) into the page pipeline. The page is measured on a
//! working copy about 1000 pixels long, lit evenly by the Auto look's own light map (each channel
//! divided by the paper's colour under the local light, with no tone curve), because both blocks
//! expect "the page after lighting correction". Two measurements are taken before that correction, as their blocks ask:
//! glare (whitened paper would count as glare) and the gutter's dark band (the correction exists
//! to remove it).
//!
//! Pure Rust and deterministic: the same page gives the same answer on any number of threads.

use crate::Rgb;
use crate::aspect::Paper;
use crate::enhance::evened;
use crate::photo::Luma;
use crate::photo_mask::{PhotoMask, photo_mask};
use crate::preset::{PageFeatures, Preset, PresetParams, classify, gutter_band, ink_features, params};

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
    /// Where the photographs are (#299, section 4), over the page as a whole; `None` when the page
    /// was too small to cut into blocks.
    pub photos: Option<PhotoMask>,
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

/// What `page` (straightened, before its look, at `dpi`, matched to `paper`) measures as. The
/// page's edge detection said `curved_sides` of its four sides are curved. `None` for a page too
/// small or malformed to measure.
pub fn page_features(page: &Rgb, dpi: u16, paper: Paper, curved_sides: u8) -> Option<(PageFeatures, Option<PhotoMask>)> {
    let (small, step) = working_copy(page)?;
    let small_dpi = f32::from(dpi.max(1)) / step as f32;
    // glare and the gutter before the light is evened out (see the module's comment)
    let glare_share = ink_features(&small, small_dpi)?.glare_share;
    let gutter = gutter_band(&luma_of(&small));
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
        curved_sides: curved_sides.min(4),
        gutter_band: gutter,
        glare_share,
    };
    Some((features, mask))
}

/// The kind of `page` and its settings under `choice`; `None` when presets are off or the page
/// cannot be measured.
pub fn page_kind(page: &Rgb, dpi: u16, paper: Paper, curved_sides: u8, choice: PresetChoice) -> Option<PageKind> {
    if choice == PresetChoice::Off {
        return None;
    }
    let (features, photos) = page_features(page, dpi, paper, curved_sides)?;
    let whole_photo = photos.as_ref().is_some_and(|m| m.whole_page);
    let (preset, chosen) = match choice {
        PresetChoice::Fixed(p) => (p, true),
        _ => (classify(&features), false),
    };
    Some(PageKind { preset, chosen, params: params(preset), features, whole_photo, photos })
}
