//! A page's first picture, for the scan's screens to show while the page itself is being made:
//! see `pdfcraft_scan::first`. Only ever shown; nothing here is saved.

use std::path::Path;

use crate::MobileError;
use crate::scan::{SCAN_ROOT, ScanPageRequest, ScanPaper, ScanTiming, markup_of, page_request, read_photo, scan_paper};

/// A copy of the photo at a smaller size, upright: the thumbnail the app made when the photo came.
#[derive(Debug, Clone, uniffi::Record)]
pub struct ScanSmallPhoto {
    pub width: u32,
    pub height: u32,
    /// `width` × `height` pixels, four bytes each: red, green, blue and alpha (not looked at).
    pub rgba: Vec<u8>,
}

/// A page's first picture.
#[derive(Debug, Clone, uniffi::Record)]
pub struct ScanFirstPage {
    pub width: u32,
    pub height: u32,
    /// `width` × `height` pixels, four bytes each: red, green, blue and 255.
    pub rgba: Vec<u8>,
    /// The quarter turns clockwise it was given: the person's own only.
    pub turns: u8,
    /// The paper the page was matched to.
    pub paper: ScanPaper,
    /// It was made from the small copy; false when the photo had to be decoded (no copy was
    /// given, or the one given was not the photo's shape).
    pub from_small: bool,
    /// `scan_first_header`, `scan_first_decode` (only when the photo was decoded),
    /// `scan_first_rectify`, `scan_first_look`.
    pub timings: Vec<ScanTiming>,
}

/// The first picture of the page `request` describes, its longer side `max_side` pixels at most
/// (64 to 1280): the page straightened by its corners in its look, made from `small` in about a
/// tenth of a second, without flattening, the classifier's turn, the document type or the finger
/// search. The request's `output_path` is not used; nothing is written, and nothing is kept for
/// the page made afterwards.
#[uniffi::export]
pub fn scan_first_page(request: ScanPageRequest, max_side: u32, small: Option<ScanSmallPhoto>) -> Result<ScanFirstPage, MobileError> {
    let root = SCAN_ROOT.read().unwrap_or_else(std::sync::PoisonError::into_inner).clone();
    let root = root.ok_or_else(|| MobileError::Command("scanning is not ready yet".into()))?;
    first_in(&root, &request, max_side, small)
}

pub(crate) fn first_in(root: &Path, request: &ScanPageRequest, max_side: u32, small: Option<ScanSmallPhoto>) -> Result<ScanFirstPage, MobileError> {
    let corners: [f32; 8] = request.corners.as_slice().try_into().map_err(|_| MobileError::Command("a page needs four corners".into()))?;
    let bytes = read_photo(root, &request.photo_path)?;
    // a copy whose bytes are not its size is left out: the photo is decoded instead
    let small = small.filter(|s| s.rgba.len() == s.width as usize * s.height as usize * 4).map(|s| pdfcraft_scan::Photo {
        width: s.width,
        height: s.height,
        rgb: s.rgba.chunks_exact(4).flat_map(|p| [p[0], p[1], p[2]]).collect(),
    });
    let markup = request.markup.as_ref().map(markup_of);
    let asked = page_request(request, &bytes, corners, Some(max_side.clamp(64, 1280)), markup.as_ref());
    let first = pdfcraft_scan::first::first_page(&asked, small.as_ref()).map_err(|e| MobileError::Command(e.to_string()))?;
    let pixels = first.width as usize * first.height as usize;
    let mut rgba = Vec::with_capacity(pixels * 4);
    match &first.pixels {
        pdfcraft_scan::look::Pixels::Rgb(rgb) => rgb.chunks_exact(3).for_each(|p| rgba.extend([p[0], p[1], p[2], 255])),
        pdfcraft_scan::look::Pixels::Grey(grey) => grey.iter().for_each(|&v| rgba.extend([v, v, v, 255])),
        pdfcraft_scan::look::Pixels::Bilevel(bits) => bits.to_grey().iter().for_each(|&v| rgba.extend([v, v, v, 255])),
    }
    if rgba.len() != pixels * 4 {
        return Err(MobileError::Command("the page could not be drawn".into()));
    }
    Ok(ScanFirstPage {
        width: first.width,
        height: first.height,
        rgba,
        turns: first.turns,
        paper: scan_paper(first.paper),
        from_small: first.from_small,
        timings: first.timings.into_iter().map(|(stage, ms)| ScanTiming { stage: stage.into(), ms }).collect(),
    })
}

#[cfg(test)]
mod tests {
    use std::path::PathBuf;

    use super::{ScanSmallPhoto, first_in};
    use crate::scan::tests::request;
    use crate::scan::{ScanLook, ScanPageRequest, ScanPaper, preview_in};

    fn temp(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("pdfcraft-first-{name}-{}", std::process::id()));
        std::fs::create_dir_all(&d).unwrap();
        d.canonicalize().unwrap()
    }

    /// An A4 page of grey bars on a darker table, 1600 × 2000.
    fn photo() -> image::RgbImage {
        image::RgbImage::from_fn(1600, 2000, |x, y| {
            let inside = (240..1360).contains(&x) && (208..1792).contains(&y);
            let bar = inside && (y / 40) % 2 == 0 && (320..1280).contains(&x);
            if bar {
                image::Rgb([60, 60, 70])
            } else if inside {
                image::Rgb([235, 232, 225])
            } else {
                image::Rgb([90, 70, 60])
            }
        })
    }

    /// The photo with every fourth pixel kept, as the app's thumbnail is, four bytes a pixel.
    fn small(photo: &image::RgbImage) -> ScanSmallPhoto {
        let small = image::imageops::resize(photo, photo.width() / 4, photo.height() / 4, image::imageops::FilterType::Triangle);
        ScanSmallPhoto { width: small.width(), height: small.height(), rgba: small.pixels().flat_map(|p| [p.0[0], p.0[1], p.0[2], 255]).collect() }
    }

    /// Through the files: the first picture has the preview's shape and paper, comes from the copy
    /// without decoding, names its stages, and bad requests are refused.
    #[test]
    fn a_first_picture_is_made_from_the_copy_and_has_the_previews_shape() {
        let root = temp("files");
        let shot = root.join("page.jpg");
        let drawn = photo();
        drawn.save(&shot).unwrap();
        let ask = ScanPageRequest {
            corners: vec![0.15, 0.104, 0.85, 0.104, 0.85, 0.896, 0.15, 0.896],
            look: ScanLook::Auto,
            strength: 60,
            // what the first picture leaves out may be asked for all the same
            flatten: true,
            flatten_check: true,
            upright: true,
            preset: "auto".into(),
            remove_fingers: true,
            ..request(&shot, &root.join("unused"))
        };
        let first = first_in(&root, &ask, 640, Some(small(&drawn))).unwrap();
        assert!(first.from_small);
        assert_eq!(first.rgba.len(), (first.width * first.height * 4) as usize);
        assert!(first.rgba.chunks_exact(4).all(|p| p[3] == 255));
        assert_eq!(first.turns, 0);
        assert_eq!(
            first.timings.iter().map(|t| t.stage.as_str()).collect::<Vec<_>>(),
            ["scan_first_header", "scan_first_rectify", "scan_first_look"]
        );
        // the preview of the same request: the same paper and proportions, twice the size
        let preview = preview_in(&root, &ScanPageRequest { flatten: false, flatten_check: false, ..ask.clone() }, 1280).unwrap();
        assert_eq!(first.paper, preview.paper);
        assert_eq!(first.paper, ScanPaper::A4);
        let ratio = |w: u32, h: u32| f64::from(w) / f64::from(h);
        assert!((ratio(first.width, first.height) / ratio(preview.width, preview.height) - 1.0).abs() < 0.005, "{} x {}", first.width, first.height);
        assert!(first.width.max(first.height).abs_diff(640) <= 6);
        // paper is white and the bars are dark, as in the preview
        let at = |rgba: &[u8], w: u32, x: u32, y: u32| rgba[((y * w + x) * 4) as usize];
        assert!(at(&first.rgba, first.width, first.width / 2, 4) > 235);
        assert!(at(&first.rgba, first.width, first.width / 2, 21) < 120);

        // the person's turn is in it
        let turned = first_in(&root, &ScanPageRequest { upright: false, turns: 1, ..ask.clone() }, 640, Some(small(&drawn))).unwrap();
        assert_eq!((turned.width, turned.height, turned.turns), (first.height, first.width, 1));

        // no copy, or one whose bytes are not its size: the photo is decoded, the size is the same
        for copy in [None, Some(ScanSmallPhoto { width: 400, height: 500, rgba: vec![0; 12] })] {
            let slow = first_in(&root, &ask, 640, copy).unwrap();
            assert!(!slow.from_small);
            assert_eq!((slow.width, slow.height), (first.width, first.height));
            assert!(slow.timings.iter().any(|t| t.stage == "scan_first_decode"));
        }
        // the size is limited to the preview's
        assert!(first_in(&root, &ask, 9999, Some(small(&drawn))).unwrap().width.max(1) <= 1290);

        assert!(first_in(&root, &ScanPageRequest { corners: vec![0.1; 6], ..ask.clone() }, 640, None).is_err());
        assert!(
            first_in(&root, &ScanPageRequest { photo_path: root.join("missing.jpg").to_string_lossy().into_owned(), ..ask.clone() }, 640, None)
                .is_err()
        );
        let other = temp("other");
        assert!(first_in(&other, &ask, 640, Some(small(&drawn))).is_err());
        std::fs::remove_dir_all(root).unwrap();
        std::fs::remove_dir_all(other).unwrap();
    }

    /// A first picture keeps nothing: the preview made after it is made from the photo, not reused.
    #[test]
    fn a_first_picture_is_not_kept_as_a_preview() {
        let _alone = crate::scan::PREVIEWS_TEST_LOCK.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        let root = temp("kept");
        let shot = root.join("page.jpg");
        let drawn = photo();
        drawn.save(&shot).unwrap();
        let ask = ScanPageRequest { look: ScanLook::Auto, ..request(&shot, &root.join("unused")) };
        first_in(&root, &ask, 640, Some(small(&drawn))).unwrap();
        // at the first picture's own size too: nothing of it is taken for the page
        assert!(!preview_in(&root, &ask, 640).unwrap().reused);
        assert!(preview_in(&root, &ask, 640).unwrap().reused);
        std::fs::remove_dir_all(root).unwrap();
    }
}
