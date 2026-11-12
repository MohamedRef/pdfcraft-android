//! What the moiré and defect detectors see on a page (#305, #306), for the scanner's testing
//! option that reports them. **Detection only**: nothing here changes a page; see
//! `pdfcraft_scan::inspect`.

use std::path::Path;

use crate::MobileError;
use crate::scan::{SCAN_ROOT, ScanTiming, read_photo};

/// One kind of region the defect search found (#306).
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct ScanRegions {
    pub count: u32,
    /// How many have print around them: left alone, the page should be taken again.
    pub over_print: u32,
    /// Their area together, as a share of the page.
    pub area: f32,
}

/// What the detectors saw on one page. **Not a verdict to act on**: their thresholds were set on
/// drawn pages only, so this is for the diagnostics log and kept scans.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ScanInspection {
    /// The straightened page's size the detectors read.
    pub width: u32,
    pub height: u32,
    /// `none`, `screen` or `halftone` (#305).
    pub moire: String,
    /// The pattern's period in pixels of the straightened page; `None` without a pattern.
    pub moire_period: Option<f32>,
    pub moire_tiles: u32,
    pub moire_busy_tiles: u32,
    pub moire_periodic_tiles: u32,
    /// `None` when the page was too small for the defect search.
    pub fingers: Option<ScanRegions>,
    pub glare: Option<ScanRegions>,
    pub stains: Option<ScanRegions>,
    /// Fingers and glare over print.
    pub flagged: u32,
    /// `scan_inspect_base`, `scan_moire`, `scan_inspect_look`, `scan_defects`.
    pub timings: Vec<ScanTiming>,
}

/// Run the moiré and defect detectors on the page inside `corners` (8 shares, 0–1, of the upright
/// photo, as every scan call takes them) of the photo at `photo_path`, which must be in the scan's
/// folder. The page is straightened as Save would (no flattening, no turn) and nothing is written.
#[uniffi::export]
pub fn scan_inspect_page(photo_path: String, corners: Vec<f32>) -> Result<ScanInspection, MobileError> {
    let root = SCAN_ROOT.read().unwrap_or_else(std::sync::PoisonError::into_inner).clone();
    let root = root.ok_or_else(|| MobileError::Command("scanning is not ready yet".into()))?;
    inspect_in(&root, &photo_path, &corners)
}

pub(crate) fn inspect_in(root: &Path, photo_path: &str, corners: &[f32]) -> Result<ScanInspection, MobileError> {
    let corners: [f32; 8] = corners.try_into().map_err(|_| MobileError::Command("a page needs four corners".into()))?;
    let bytes = read_photo(root, photo_path)?;
    let seen = pdfcraft_scan::inspect::inspect_photo(&bytes, corners).map_err(|e| MobileError::Command(e.to_string()))?;
    let count = |n: usize| u32::try_from(n).unwrap_or(u32::MAX);
    let regions = |r: Option<pdfcraft_scan::inspect::RegionCount>| {
        r.map(|r| ScanRegions { count: count(r.count), over_print: count(r.over_print), area: r.area })
    };
    Ok(ScanInspection {
        width: seen.width,
        height: seen.height,
        moire: seen.moire.word().into(),
        moire_period: seen.moire_period,
        moire_tiles: count(seen.moire_tiles),
        moire_busy_tiles: count(seen.moire_busy_tiles),
        moire_periodic_tiles: count(seen.moire_periodic_tiles),
        fingers: regions(seen.fingers),
        glare: regions(seen.glare),
        stains: regions(seen.stains),
        flagged: count(seen.flagged),
        timings: seen.timings.into_iter().map(|(stage, ms)| ScanTiming { stage: stage.into(), ms }).collect(),
    })
}

#[cfg(test)]
mod tests {
    use std::path::PathBuf;

    use super::inspect_in;

    const WHOLE: [f32; 8] = [0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0];

    fn temp(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("pdfcraft-inspect-{name}-{}", std::process::id()));
        std::fs::create_dir_all(&d).unwrap();
        d.canonicalize().unwrap()
    }

    /// A paper-coloured page with a finger in a skin colour reaching in from its bottom edge.
    fn finger_photo() -> image::RgbImage {
        let (w, h) = (1200u32, 1600u32);
        image::RgbImage::from_fn(w, h, |x, y| {
            let (dx, along) = (x as f32 - 300.0, (h - 1 - y) as f32);
            let inside = dx.abs() < 55.0 && (along < 205.0 || dx.hypot(along - 205.0) <= 55.0);
            if inside { image::Rgb([200, 150, 120]) } else { image::Rgb([245, 243, 238]) }
        })
    }

    /// Through the files: a finger on a blank page is reported, the stages are named, and bad
    /// requests are refused.
    #[test]
    fn a_finger_photo_is_reported_and_bad_requests_refused() {
        let root = temp("files");
        let photo = root.join("finger.png");
        finger_photo().save(&photo).unwrap();
        let path = photo.to_string_lossy().into_owned();
        let seen = inspect_in(&root, &path, &WHOLE).unwrap();
        assert_eq!(seen.moire, "none", "{seen:?}");
        assert_eq!(seen.fingers.map(|f| (f.count, f.over_print)), Some((1, 0)), "{seen:?}");
        assert_eq!(seen.flagged, 0);
        assert_eq!(
            seen.timings.iter().map(|t| t.stage.as_str()).collect::<Vec<_>>(),
            ["scan_inspect_base", "scan_moire", "scan_inspect_look", "scan_defects"]
        );
        assert!(inspect_in(&root, &path, &[0.1; 6]).is_err());
        assert!(inspect_in(&root, &root.join("missing.png").to_string_lossy(), &WHOLE).is_err());
        let other = temp("other");
        assert!(inspect_in(&other, &path, &WHOLE).is_err());
        std::fs::remove_dir_all(root).unwrap();
        std::fs::remove_dir_all(other).unwrap();
    }
}
