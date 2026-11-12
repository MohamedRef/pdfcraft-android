//! Book mode (#303): one photo of an open book becomes two pages. The page outline the app
//! already has (found, or as the person left it) is cut along the book's gutter by
//! `pdfcraft_scan::book`; each half is then an ordinary page for `scan_process_page`.

use std::path::Path;

use pdfcraft_scan::{Photo, Pyramid, Quad, book};

use crate::MobileError;
use crate::scan::{SCAN_ROOT, ScanTiming, read_photo};

/// What book mode made of one photo.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ScanSpread {
    /// The outline was cut in two. False: the photo stays one page (no gutter and not the shape
    /// of two pages side by side), and the other fields describe nothing.
    pub split: bool,
    /// The left page's corners: 8 shares of the upright photo (top-left, top-right, bottom-right,
    /// bottom-left). Its right side is the gutter.
    pub left: Vec<f32>,
    /// The right page's corners, in the same order. Its left side is the gutter.
    pub right: Vec<f32>,
    /// A dark gutter was found. False with `split`: the cut is the centre line, because the
    /// outline has the shape of two pages or the gutter search was switched off for testing.
    pub gutter_found: bool,
    /// Where the gutter crosses the spread's top and bottom edges, as shares (0–1) of the
    /// straightened spread's width.
    pub gutter_top: f32,
    pub gutter_bottom: f32,
    /// 0–1: how much darker the gutter is than the pages (0 for the centre line).
    pub confidence: f32,
    pub timings: Vec<ScanTiming>,
}

/// Cut the page outline `corners` (8 shares of the upright photo) of the photo at `photo_path`
/// into a left and a right page along the book's gutter (#303). `look_for_gutter` false (a
/// testing option) cuts at the centre line whatever the photo shows.
#[uniffi::export]
pub fn scan_split_spread(photo_path: String, corners: Vec<f32>, look_for_gutter: bool) -> Result<ScanSpread, MobileError> {
    let root = SCAN_ROOT.read().unwrap_or_else(std::sync::PoisonError::into_inner).clone();
    let root = root.ok_or_else(|| MobileError::Command("scanning is not ready yet".into()))?;
    split_in(&root, &photo_path, &corners, look_for_gutter)
}

pub(crate) fn split_in(root: &Path, photo_path: &str, corners: &[f32], look_for_gutter: bool) -> Result<ScanSpread, MobileError> {
    let corners: [f32; 8] = corners.try_into().map_err(|_| MobileError::Command("a page needs four corners".into()))?;
    let mut timings = Vec::with_capacity(2);
    let mut clock = std::time::Instant::now();
    let mut lap = |stage: &str, timings: &mut Vec<ScanTiming>| {
        timings.push(ScanTiming { stage: stage.into(), ms: u32::try_from(clock.elapsed().as_millis()).unwrap_or(u32::MAX) });
        clock = std::time::Instant::now();
    };
    let bytes = read_photo(root, photo_path)?;
    let photo = Photo::decode(&bytes).map_err(|e| MobileError::Command(e.to_string()))?;
    drop(bytes);
    let (width, height) = (photo.width, photo.height);
    let quad = Quad::from_shares(&corners, width, height).map_err(|e| MobileError::Command(e.to_string()))?;
    let pyramid = Pyramid::new(photo.luma());
    drop(photo);
    lap("scan_decode", &mut timings);
    let found = book::split_photo(&pyramid, &quad, look_for_gutter);
    lap("scan_gutter", &mut timings);
    Ok(match found {
        Some(b) => ScanSpread {
            split: true,
            left: b.spread.left.to_shares(width, height).to_vec(),
            right: b.spread.right.to_shares(width, height).to_vec(),
            gutter_found: b.gutter.found,
            gutter_top: b.gutter.top as f32,
            gutter_bottom: b.gutter.bottom as f32,
            confidence: b.gutter.confidence,
            timings,
        },
        None => ScanSpread {
            split: false,
            left: Vec::new(),
            right: Vec::new(),
            gutter_found: false,
            gutter_top: 0.0,
            gutter_bottom: 0.0,
            confidence: 0.0,
            timings,
        },
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    fn temp(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("pdfcraft-scan-book-{name}-{}", std::process::id()));
        std::fs::create_dir_all(&d).unwrap();
        d.canonicalize().unwrap()
    }

    /// A photo of an open book lying square on a table: the spread from x 200 to 1614 and y 200
    /// to 1200 (two A pages), its gutter a dark valley at `gutter` of its width.
    fn book_photo(path: &Path, gutter: f64, depth: f64) {
        image::RgbImage::from_fn(1800, 1400, |x, y| {
            let (u, v) = ((f64::from(x) - 200.0) / 1414.0, (f64::from(y) - 200.0) / 1000.0);
            if !((0.0..=1.0).contains(&u) && (0.0..=1.0).contains(&v)) {
                return image::Rgb([60, 52, 46]);
            }
            let ink = u > 0.06 && u < 0.94 && (v * 40.0).fract() < 0.3;
            let paper = if ink { 140.0 } else { 230.0 };
            let d = (u - gutter) / 0.012;
            let g = (paper * (1.0 - depth * (-0.5 * d * d).exp())).round() as u8;
            image::Rgb([g, g, g])
        })
        .save(path)
        .unwrap();
    }

    fn shares(x0: f32, y0: f32, x1: f32, y1: f32) -> Vec<f32> {
        let (w, h) = (1800.0, 1400.0);
        vec![x0 / w, y0 / h, x1 / w, y0 / h, x1 / w, y1 / h, x0 / w, y1 / h]
    }

    #[test]
    fn a_spread_becomes_a_left_and_a_right_page() {
        let root = temp("split");
        let shot = root.join("book.png");
        book_photo(&shot, 0.47, 0.35);
        let spread = split_in(&root, &shot.to_string_lossy(), &shares(200.0, 200.0, 1614.0, 1200.0), true).unwrap();
        assert!(spread.split && spread.gutter_found && spread.confidence > 0.5, "{spread:?}");
        assert!((spread.gutter_top - 0.47).abs() < 0.005 && (spread.gutter_bottom - 0.47).abs() < 0.005, "{spread:?}");
        // the left page's right side and the right page's left side lie on the gutter, 0.5 % of
        // the spread's width away from it on each side
        let gutter_x = (200.0 + 0.47 * 1414.0) / 1800.0;
        let gap = 0.005 * 1414.0 / 1800.0;
        assert!((spread.left[2] - (gutter_x - gap)).abs() < 0.004, "left {:?}", spread.left);
        assert!((spread.right[0] - (gutter_x + gap)).abs() < 0.004, "right {:?}", spread.right);
        assert_eq!(spread.left[0..2], shares(200.0, 200.0, 1614.0, 1200.0)[0..2]);
        assert_eq!(spread.timings.iter().map(|t| t.stage.as_str()).collect::<Vec<_>>(), ["scan_decode", "scan_gutter"]);

        // the testing option: the centre line, whatever the photo shows
        let centre = split_in(&root, &shot.to_string_lossy(), &shares(200.0, 200.0, 1614.0, 1200.0), false).unwrap();
        assert!(centre.split && !centre.gutter_found && centre.gutter_top == 0.5 && centre.confidence == 0.0, "{centre:?}");
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn one_page_stays_one_page() {
        let root = temp("single");
        let shot = root.join("page.png");
        book_photo(&shot, 5.0, 0.0);
        // one portrait page of the drawn spread's paper: no valley, and not the shape of two pages
        let spread = split_in(&root, &shot.to_string_lossy(), &shares(300.0, 250.0, 900.0, 1100.0), true).unwrap();
        assert!(!spread.split && spread.left.is_empty() && spread.right.is_empty(), "{spread:?}");
        assert!(split_in(&root, &shot.to_string_lossy(), &[0.0; 4], true).is_err());
        std::fs::remove_dir_all(root).unwrap();
    }
}
