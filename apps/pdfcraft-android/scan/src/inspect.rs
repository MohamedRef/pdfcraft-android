//! What the two research detectors see on a page, for the testing option that reports them
//! (#305, #306). **Detection only: no page the app makes is changed by anything here.**
//!
//! [`inspect_photo`] straightens the page as Save would (its corners, proportions and paper size,
//! no flattening, no turn), then runs [`crate::moire::find`] on its grey copy and
//! [`crate::defects::find`] on its Auto look, with the page before the look for glare. The
//! detectors' thresholds were set on drawn pages (`docs/ANDROID_SCAN_MOIRE.md`,
//! `docs/ANDROID_SCAN_DEFECTS.md`), so what comes back is for the log and a kept scan's page
//! file, to compare with what the person saw, not for a decision.

use std::time::Instant;

use crate::defects::{self, Defects, Region};
use crate::look::{LookParams, Pixels, apply_full};
use crate::moire::{self, MoireKind};
use crate::page::{PageRequest, page_base};
use crate::{DEFAULT_SHARPEN, Look, Luma, Rgb, ScanError, luma};

/// The straightening (decode, corners, proportions), as in [`crate::page`].
pub const STAGE_BASE: &str = "scan_inspect_base";
/// The moiré search on the straightened page.
pub const STAGE_MOIRE: &str = "scan_moire";
/// The Auto look the defect search reads.
pub const STAGE_LOOK: &str = "scan_inspect_look";
/// The search for fingers, glare and stains.
pub const STAGE_DEFECTS: &str = "scan_defects";

/// The Auto look's strength the defect search reads the page at (the look's own default).
const LOOK_STRENGTH: u8 = 60;

/// One kind of region the defect search found.
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct RegionCount {
    /// How many.
    pub count: usize,
    /// How many of them have print in the ring around them (left alone; the page should be taken
    /// again).
    pub over_print: usize,
    /// Their area together, as a share of the page.
    pub area: f32,
}

impl RegionCount {
    fn of(regions: &[Region]) -> RegionCount {
        RegionCount { count: regions.len(), over_print: regions.iter().filter(|r| r.covers_ink).count(), area: regions.iter().map(|r| r.area).sum() }
    }
}

/// What the detectors saw on one page.
#[derive(Clone, Debug, PartialEq)]
pub struct Inspection {
    /// The straightened page's size.
    pub width: u32,
    pub height: u32,
    /// Screen, halftone or none (#305).
    pub moire: MoireKind,
    /// The period of the most common pattern, in pixels of the straightened page.
    pub moire_period: Option<f32>,
    /// Squares looked at, squares with detail, and squares with a periodic peak.
    pub moire_tiles: usize,
    pub moire_busy_tiles: usize,
    pub moire_periodic_tiles: usize,
    /// Fingers, glare spots and stains (#306); `None` when the defect search could not run (a
    /// page too small for its working copy).
    pub fingers: Option<RegionCount>,
    pub glare: Option<RegionCount>,
    pub stains: Option<RegionCount>,
    /// Fingers and glare over print (what [`Defects::flagged`] counts); 0 without a defect search.
    pub flagged: usize,
    /// (stage, milliseconds), in order.
    pub timings: Vec<(&'static str, u32)>,
}

/// Straighten the page inside `corners` (8 shares, 0–1, of the upright photo) of the camera file
/// `photo` and report what the moiré and defect detectors see on it. The photo is decoded with the
/// crate's usual limits.
pub fn inspect_photo(photo: &[u8], corners: [f32; 8]) -> Result<Inspection, ScanError> {
    let mut started = Instant::now();
    let mut timings = Vec::with_capacity(4);
    let mut lap = |stage: &'static str, timings: &mut Vec<(&'static str, u32)>| {
        timings.push((stage, u32::try_from(started.elapsed().as_millis()).unwrap_or(u32::MAX)));
        started = Instant::now();
    };
    let base = page_base(&PageRequest::new(photo, corners))?;
    let page = base.page;
    lap(STAGE_BASE, &mut timings);

    let grey = Luma { width: page.width, height: page.height, data: page.data.as_chunks::<3>().0.iter().map(|p| luma(*p)).collect() };
    let found = moire::find(&grey);
    drop(grey);
    lap(STAGE_MOIRE, &mut timings);

    let looked = auto_look(&page, base.dpi);
    lap(STAGE_LOOK, &mut timings);
    let defects = looked.as_ref().and_then(|evened| defects::find(evened, Some(&page)));
    lap(STAGE_DEFECTS, &mut timings);

    Ok(summary(page.width, page.height, &found, defects.as_ref(), timings))
}

/// The page in the Auto look as RGB (paper near white), or `None` when the look fails.
fn auto_look(page: &Rgb, dpi: u16) -> Option<Rgb> {
    let params = LookParams {
        strength: LOOK_STRENGTH,
        threads: 1,
        dpi: f32::from(dpi),
        sharpen: DEFAULT_SHARPEN,
        denoise: true,
        keep_paper: false,
        shape: Default::default(),
        grain_white: true,
    };
    let data = match apply_full(page, Look::Auto, &params)?.pixels {
        Pixels::Rgb(rgb) => rgb,
        Pixels::Grey(grey) => grey.iter().flat_map(|&v| [v, v, v]).collect(),
        Pixels::Bilevel(_) => return None,
    };
    Some(Rgb { width: page.width, height: page.height, data })
}

/// The counts the log and a kept scan's page file take.
pub(crate) fn summary(width: u32, height: u32, found: &moire::Moire, defects: Option<&Defects>, timings: Vec<(&'static str, u32)>) -> Inspection {
    Inspection {
        width,
        height,
        moire: found.kind,
        moire_period: found.period(),
        moire_tiles: found.tiles,
        moire_busy_tiles: found.busy_tiles,
        moire_periodic_tiles: found.periodic_tiles,
        fingers: defects.map(|d| RegionCount::of(&d.fingers)),
        glare: defects.map(|d| RegionCount::of(&d.glare)),
        stains: defects.map(|d| RegionCount::of(&d.stains)),
        flagged: defects.map_or(0, Defects::flagged),
        timings,
    }
}
