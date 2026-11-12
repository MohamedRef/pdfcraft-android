//! Scan (#287): one photo → one straightened, cleaned page image, through `pdfcraft-scan`.
//!
//! The functions only read and write inside the directory given to [`scan_set_root`] (the app's
//! cache directory): both paths are resolved (links followed) and checked against it.

use std::path::{Path, PathBuf};
use std::sync::RwLock;

use pdfcraft_scan::{AspectSource, Format, Look, PageRequest, Paper, Photo, Pyramid, Quad, QuadSource, RefineOptions, detect, process_page, refine};

use crate::MobileError;

/// A page's look.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ScanLook {
    Original,
    Colour,
    Greyscale,
    BlackWhite,
    /// Like a flatbed scan in one step (#298).
    Auto,
}

/// The format a page image was written in.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ScanFormat {
    Jpeg,
    Png,
}

/// How long one stage of a page took.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ScanTiming {
    pub stage: String,
    pub ms: u32,
}

/// One photo to turn into a page.
#[derive(Debug, Clone, uniffi::Record)]
pub struct ScanPageRequest {
    /// The camera file.
    pub photo_path: String,
    /// 8 shares (0–1) of the upright photo: x1, y1 … x4, y4 (top-left, top-right,
    /// bottom-right, bottom-left).
    pub corners: Vec<f32>,
    pub look: ScanLook,
    /// 0–100.
    pub strength: u8,
    /// Where the page image is written; `.jpg` or `.png` is added.
    pub output_path: String,
    /// The camera's focal length as a 35 mm equivalent, in millimetres, when the caller knows it
    /// (the in-app camera will). `None`: the photo's own EXIF block is used, else the corners.
    #[uniffi(default = None)]
    pub focal_35mm: Option<f32>,
    /// Match the page to standard paper sizes (A4, Letter, …) when its shape is within 3 %.
    #[uniffi(default = true)]
    pub snap_paper: bool,
    /// Flatten a curled, sagging or folded page with the flattening network (#302) instead of
    /// the perspective warp. Off unless asked for; see [`scan_can_flatten`].
    #[uniffi(default = false)]
    pub flatten: bool,
}

/// The standard paper a page was matched to.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ScanPaper {
    A4,
    A5,
    Letter,
    Legal,
    IdCard,
    Square,
    /// No standard size.
    Custom,
}

/// How a page's proportions were worked out (#293).
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ScanAspectSource {
    /// From the corners and the camera's focal length.
    KnownFocal,
    /// From the corners alone.
    EstimatedFocal,
    /// From the corners and a typical phone camera's focal length (the corners say little about
    /// it: the page is tilted one way only, or hardly at all).
    TypicalFocal,
    /// From the lengths of the sides (corners no rectangle gives).
    SideLengths,
}

/// The page written.
#[derive(Debug, Clone, uniffi::Record)]
pub struct ScanPageResult {
    pub path: String,
    pub width: u32,
    pub height: u32,
    pub format: ScanFormat,
    /// The resolution written into the file: the paper's real size for a standard paper, else
    /// the long side taken as 297 mm.
    pub dpi: f32,
    pub paper: ScanPaper,
    pub aspect_source: ScanAspectSource,
    /// The camera's focal length and the one the corners imply disagree.
    pub focal_mismatch: bool,
    /// The page was flattened by the network.
    pub flattened: bool,
    /// How far from flat the network found the page, as a share of its diagonal (0 when it was
    /// not asked).
    pub bend: f32,
    pub timings: Vec<ScanTiming>,
}

/// A page's corners after a closer look at the photo (#291).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ScanQuad {
    /// x1, y1 … x4, y4 as shares (0–1) of the upright photo: top-left, top-right, bottom-right,
    /// bottom-left.
    pub corners: Vec<f32>,
    /// For the top, right, bottom and left sides: the side was moved onto an edge found in the
    /// photo (`false`: no convincing edge near it, it is where it was given).
    pub refined: Vec<bool>,
    /// For the same sides: the edge bends, so the page is not flat.
    pub curved: Vec<bool>,
    pub timings: Vec<ScanTiming>,
}

/// What found a page in a photo (#290).
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ScanQuadSource {
    /// The detector network.
    Model,
    /// The line detector (the network's model file is not installed).
    Lines,
    /// Nothing convincing was found.
    None,
}

/// The page found in a photo, its corners already moved onto the paper's edges (#290, #291).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ScanDocument {
    /// Whether a page was found. Without one, `corners` is the whole photo.
    pub found: bool,
    /// x1, y1 … x4, y4 as shares (0–1) of the upright photo: top-left, top-right, bottom-right,
    /// bottom-left.
    pub corners: Vec<f32>,
    /// 0–1, how sure the search was.
    pub confidence: f32,
    pub source: ScanQuadSource,
    /// The angle (degrees) the photo was shown to the network at for the answer it gave.
    pub angle: i16,
    /// How many times the network looked.
    pub passes: u8,
    /// For the top, right, bottom and left sides: moved onto an edge found in the full-size photo.
    pub refined: Vec<bool>,
    /// For the same sides: the edge bends, so the page is not flat.
    pub curved: Vec<bool>,
    pub timings: Vec<ScanTiming>,
}

/// The one directory scan functions read from and write to.
static SCAN_ROOT: RwLock<Option<PathBuf>> = RwLock::new(None);

/// Set the directory scan functions may use (the app's cache directory). Ignored unless it is an
/// existing absolute directory.
#[uniffi::export]
pub fn scan_set_root(root: String) {
    let path = Path::new(&root);
    if !path.is_absolute() {
        return;
    }
    if let Ok(canonical) = path.canonicalize()
        && canonical.is_dir()
    {
        *SCAN_ROOT.write().unwrap_or_else(std::sync::PoisonError::into_inner) = Some(canonical);
    }
}

/// Straighten the page in a photo, give it a look and write it once (see [`ScanPageRequest`]).
#[uniffi::export]
pub fn scan_process_page(request: ScanPageRequest) -> Result<ScanPageResult, MobileError> {
    let root = SCAN_ROOT.read().unwrap_or_else(std::sync::PoisonError::into_inner).clone();
    let root = root.ok_or_else(|| MobileError::Command("scanning is not ready yet".into()))?;
    process_in(&root, &request)
}

/// The largest camera file read (a 64 MP PNG is under 200 MB).
const MAX_PHOTO_BYTES: u64 = 256 << 20;

/// The bytes of the photo at `path`, which must be inside `root`.
fn read_photo(root: &Path, path: &str) -> Result<Vec<u8>, MobileError> {
    let unreadable = || MobileError::Command("the photo could not be read".into());
    let photo_path = Path::new(path).canonicalize().map_err(|_| unreadable())?;
    if !photo_path.starts_with(root) {
        return Err(MobileError::Command("the scan's files must be in the app's own folder".into()));
    }
    if std::fs::metadata(&photo_path).map_err(|_| unreadable())?.len() > MAX_PHOTO_BYTES {
        return Err(MobileError::Command("the photo is too large".into()));
    }
    std::fs::read(&photo_path).map_err(|_| unreadable())
}

/// Move a page's corners onto the paper's edges as they are in the full-size photo (#291). A
/// detector sees a small copy of the photo, so its corners are several pixels off at full size.
/// `sides` says which of the top, right, bottom and left sides to look at again (all four when it
/// is not four values); `search_share` is how far from each side to look, as a share of the
/// photo's diagonal (0 for the usual 1.5 %). A side with no convincing edge near it stays.
#[uniffi::export]
pub fn scan_refine_quad(photo_path: String, corners: Vec<f32>, sides: Vec<bool>, search_share: f32) -> Result<ScanQuad, MobileError> {
    let root = SCAN_ROOT.read().unwrap_or_else(std::sync::PoisonError::into_inner).clone();
    let root = root.ok_or_else(|| MobileError::Command("scanning is not ready yet".into()))?;
    refine_in(&root, &photo_path, &corners, &sides, search_share)
}

/// Whether pages can be flattened (#302): the flattening network's model file is installed.
#[uniffi::export]
pub fn scan_can_flatten() -> bool {
    pdfcraft_scan::models::state(pdfcraft_scan::models::FLATTEN) == pdfcraft_scan::models::ModelState::Ready
}

/// Find the page in a photo and move its corners onto the paper's edges: the photo is decoded
/// once, the detector looks at a small copy of it (#290), and each side is then looked at again
/// at full size (#291). When nothing convincing is found, `found` is false and the corners are
/// the whole photo.
#[uniffi::export]
pub fn scan_find_document(photo_path: String) -> Result<ScanDocument, MobileError> {
    let root = SCAN_ROOT.read().unwrap_or_else(std::sync::PoisonError::into_inner).clone();
    let root = root.ok_or_else(|| MobileError::Command("scanning is not ready yet".into()))?;
    find_in(&root, &photo_path)
}

pub(crate) fn find_in(root: &Path, photo_path: &str) -> Result<ScanDocument, MobileError> {
    let bytes = read_photo(root, photo_path)?;
    let mut timings = Vec::with_capacity(3);
    let mut clock = std::time::Instant::now();
    let mut lap = |stage: &str, timings: &mut Vec<ScanTiming>| {
        timings.push(ScanTiming { stage: stage.into(), ms: u32::try_from(clock.elapsed().as_millis()).unwrap_or(u32::MAX) });
        clock = std::time::Instant::now();
    };
    let photo = Photo::decode(&bytes).map_err(|e| MobileError::Command(e.to_string()))?;
    drop(bytes);
    lap("scan_decode", &mut timings);
    let found = detect(&photo);
    lap("scan_detect", &mut timings);
    let source = match found.source {
        QuadSource::Model => ScanQuadSource::Model,
        QuadSource::Lines => ScanQuadSource::Lines,
        QuadSource::None => ScanQuadSource::None,
    };
    let (width, height) = (photo.width, photo.height);
    let Some(quad) = found.quad else {
        let whole = vec![0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0];
        return Ok(ScanDocument {
            found: false,
            corners: whole,
            confidence: found.confidence,
            source,
            angle: found.angle,
            passes: found.passes,
            refined: vec![false; 4],
            curved: vec![false; 4],
            timings,
        });
    };
    let pyramid = Pyramid::new(photo.luma());
    drop(photo);
    let refined = refine(&pyramid, &quad, &RefineOptions::default());
    lap("scan_refine", &mut timings);
    Ok(ScanDocument {
        found: true,
        corners: refined.quad.to_shares(width, height).to_vec(),
        confidence: found.confidence,
        source,
        angle: found.angle,
        passes: found.passes,
        refined: refined.sides.iter().map(|s| s.refined).collect(),
        curved: refined.sides.iter().map(|s| s.curved).collect(),
        timings,
    })
}

pub(crate) fn refine_in(root: &Path, photo_path: &str, corners: &[f32], sides: &[bool], search_share: f32) -> Result<ScanQuad, MobileError> {
    let corners: [f32; 8] = corners.try_into().map_err(|_| MobileError::Command("a page needs four corners".into()))?;
    let bytes = read_photo(root, photo_path)?;
    let mut timings = Vec::with_capacity(2);
    let mut clock = std::time::Instant::now();
    let mut lap = |stage: &str, timings: &mut Vec<ScanTiming>| {
        timings.push(ScanTiming { stage: stage.into(), ms: u32::try_from(clock.elapsed().as_millis()).unwrap_or(u32::MAX) });
        clock = std::time::Instant::now();
    };
    let photo = Photo::decode(&bytes).map_err(|e| MobileError::Command(e.to_string()))?;
    drop(bytes);
    let (width, height) = (photo.width, photo.height);
    let quad = Quad::from_shares(&corners, width, height).map_err(|e| MobileError::Command(e.to_string()))?;
    let pyramid = Pyramid::new(photo.luma());
    drop(photo);
    lap("scan_decode", &mut timings);
    let diagonal = f64::from(width).hypot(f64::from(height));
    let search_px = (search_share.is_finite() && search_share > 0.0).then(|| (f64::from(search_share.min(0.2)) * diagonal) as f32);
    let options = RefineOptions { search_px, sides: sides.try_into().unwrap_or([true; 4]) };
    let found = refine(&pyramid, &quad, &options);
    lap("scan_refine", &mut timings);
    Ok(ScanQuad {
        corners: found.quad.to_shares(width, height).to_vec(),
        refined: found.sides.iter().map(|s| s.refined).collect(),
        curved: found.sides.iter().map(|s| s.curved).collect(),
        timings,
    })
}

pub(crate) fn process_in(root: &Path, request: &ScanPageRequest) -> Result<ScanPageResult, MobileError> {
    let outside = || MobileError::Command("the scan's files must be in the app's own folder".into());
    let corners: [f32; 8] = request.corners.as_slice().try_into().map_err(|_| MobileError::Command("a page needs four corners".into()))?;
    // The output's folder must exist inside the root; its name may not carry a folder.
    let out = Path::new(&request.output_path);
    let (Some(parent), Some(name)) = (out.parent(), out.file_name()) else { return Err(outside()) };
    let parent = parent.canonicalize().map_err(|_| outside())?;
    if !parent.starts_with(root) {
        return Err(outside());
    }
    let bytes = read_photo(root, &request.photo_path)?;
    let look = match request.look {
        ScanLook::Original => Look::Original,
        ScanLook::Colour => Look::Colour,
        ScanLook::Greyscale => Look::Greyscale,
        ScanLook::BlackWhite => Look::BlackWhite,
        ScanLook::Auto => Look::Auto,
    };
    let threads = std::thread::available_parallelism().map_or(1, usize::from).min(pdfcraft_scan::warp::MAX_THREADS);
    let page = process_page(&PageRequest {
        look,
        strength: request.strength,
        threads,
        focal_35mm: request.focal_35mm,
        snap_paper: request.snap_paper,
        flatten: request.flatten,
        ..PageRequest::new(&bytes, corners)
    })
    .map_err(|e| MobileError::Command(e.to_string()))?;
    drop(bytes);
    let mut file_name = name.to_os_string();
    file_name.push(".");
    file_name.push(page.format.extension());
    let target = parent.join(file_name);
    std::fs::write(&target, &page.bytes).map_err(|_| MobileError::Command("the page could not be saved".into()))?;
    Ok(ScanPageResult {
        path: target.to_string_lossy().into_owned(),
        width: page.width,
        height: page.height,
        format: match page.format {
            Format::Jpeg => ScanFormat::Jpeg,
            Format::Png => ScanFormat::Png,
        },
        dpi: page.dpi,
        paper: match page.paper {
            Paper::A4 => ScanPaper::A4,
            Paper::A5 => ScanPaper::A5,
            Paper::Letter => ScanPaper::Letter,
            Paper::Legal => ScanPaper::Legal,
            Paper::IdCard => ScanPaper::IdCard,
            Paper::Square => ScanPaper::Square,
            Paper::Custom => ScanPaper::Custom,
        },
        aspect_source: match page.aspect {
            AspectSource::KnownFocal => ScanAspectSource::KnownFocal,
            AspectSource::EstimatedFocal => ScanAspectSource::EstimatedFocal,
            AspectSource::TypicalFocal => ScanAspectSource::TypicalFocal,
            AspectSource::SideLengths => ScanAspectSource::SideLengths,
        },
        focal_mismatch: page.focal_mismatch,
        flattened: page.flattened,
        bend: page.bend,
        timings: page.timings.into_iter().map(|(stage, ms)| ScanTiming { stage: stage.into(), ms }).collect(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn photo(dir: &Path) -> PathBuf {
        let img = image::RgbImage::from_fn(64, 48, |x, y| image::Rgb([(x * 4) as u8, (y * 5) as u8, 200]));
        let p = dir.join("shot.jpg");
        img.save(&p).unwrap();
        p
    }

    fn request(photo: &Path, out: &Path) -> ScanPageRequest {
        ScanPageRequest {
            photo_path: photo.to_string_lossy().into_owned(),
            corners: vec![0.1, 0.1, 0.9, 0.1, 0.9, 0.9, 0.1, 0.9],
            look: ScanLook::Colour,
            strength: 50,
            output_path: out.to_string_lossy().into_owned(),
            focal_35mm: None,
            snap_paper: true,
            flatten: false,
        }
    }

    fn temp(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("pdfcraft-scan-{name}-{}", std::process::id()));
        std::fs::create_dir_all(&d).unwrap();
        d.canonicalize().unwrap()
    }

    #[test]
    fn a_page_is_written_beside_the_request_with_its_extension() {
        let root = temp("ok");
        let shot = photo(&root);
        let r = process_in(&root, &request(&shot, &root.join("page-0"))).unwrap();
        assert!(r.path.ends_with("page-0.jpg"), "{}", r.path);
        assert_eq!(r.format, ScanFormat::Jpeg);
        assert!(std::fs::metadata(&r.path).unwrap().len() > 0);
        let stages: Vec<_> = r.timings.iter().map(|t| t.stage.as_str()).collect();
        assert_eq!(stages, ["scan_decode", "scan_rectify", "scan_look", "scan_encode"]);
        let bw = process_in(&root, &ScanPageRequest { look: ScanLook::BlackWhite, ..request(&shot, &root.join("page-1")) }).unwrap();
        assert!(bw.path.ends_with("page-1.png"));
        std::fs::remove_dir_all(root).unwrap();
    }

    /// The sizes of the pages of a PDF made from `paths`, in points, each image at its own
    /// resolution (as Save does when every page carries one).
    fn pdf_page_sizes(paths: &[&str]) -> Vec<(f64, f64)> {
        let session = crate::MobileSession::new();
        let args = serde_json::json!({ "from": "images", "paths": paths, "name": "Scan" });
        let created: serde_json::Value = serde_json::from_str(&session.execute("doc_create".into(), args.to_string()).unwrap()).unwrap();
        let info: serde_json::Value =
            serde_json::from_str(&session.execute("doc_info".into(), serde_json::json!({ "doc": created["doc"] }).to_string()).unwrap()).unwrap();
        info["pages"].as_array().unwrap().iter().map(|p| (p["width"].as_f64().unwrap(), p["height"].as_f64().unwrap())).collect()
    }

    /// Pages of different papers in one PDF, each exactly its size (#293): an A4 page, a
    /// landscape Letter page and a whole photo left as the camera took it.
    #[test]
    fn each_page_of_the_pdf_has_its_papers_size() {
        let root = temp("sizes");
        let shot = root.join("wide.jpg");
        image::RgbImage::from_fn(1600, 1200, |x, y| image::Rgb([(x / 8) as u8, (y / 6) as u8, 180])).save(&shot).unwrap();
        // frontal pages: 700 x 990 photo pixels is A4, 1100 x 850 is Letter on its side
        let page = |w: f32, h: f32, look, out: &str| {
            let corners = vec![0.1, 0.1, 0.1 + w / 1600.0, 0.1, 0.1 + w / 1600.0, 0.1 + h / 1200.0, 0.1, 0.1 + h / 1200.0];
            process_in(&root, &ScanPageRequest { corners, look, ..request(&shot, &root.join(out)) }).unwrap()
        };
        let a4 = page(700.0, 990.0, ScanLook::Colour, "page-0");
        let letter = page(1100.0, 850.0, ScanLook::BlackWhite, "page-1");
        let whole = process_in(
            &root,
            &ScanPageRequest {
                corners: vec![0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0],
                look: ScanLook::Original,
                ..request(&shot, &root.join("page-2"))
            },
        )
        .unwrap();
        assert_eq!((a4.paper, letter.paper, whole.paper), (ScanPaper::A4, ScanPaper::Letter, ScanPaper::Custom));
        assert_eq!((letter.format, whole.format), (ScanFormat::Png, ScanFormat::Jpeg));
        // the whole photo is the camera's file with a resolution, not a second encoding
        assert_eq!(std::fs::read(&whole.path).unwrap()[20..], std::fs::read(&shot).unwrap()[20..]);

        let sizes = pdf_page_sizes(&[&a4.path, &letter.path, &whole.path]);
        let near = |(w, h): (f64, f64), (tw, th): (f64, f64)| (w - tw).abs() <= 0.5 && (h - th).abs() <= 0.5;
        assert!(near(sizes[0], (595.28, 841.89)), "A4: {:?}", sizes[0]);
        assert!(near(sizes[1], (792.0, 612.0)), "Letter on its side: {:?}", sizes[1]);
        // 1600 pixels as long as A4: 137 dots per inch
        assert!(near(sizes[2], (1600.0 * 72.0 / 137.0, 1200.0 * 72.0 / 137.0)), "the whole photo: {:?}", sizes[2]);
        std::fs::remove_dir_all(root).unwrap();
    }

    /// A light sheet on a dark desk, found roughly: the corners come back on the sheet's edges.
    #[test]
    fn rough_corners_are_moved_onto_the_sheets_edges() {
        let root = temp("refine");
        let shot = root.join("desk.png");
        let (w, h) = (1600u32, 1200u32);
        image::GrayImage::from_fn(w, h, |x, y| image::Luma([if (300..1300).contains(&x) && (200..1000).contains(&y) { 230 } else { 50 }]))
            .save(&shot)
            .unwrap();
        let path = shot.to_string_lossy().into_owned();
        // each corner about 10 pixels off
        let rough =
            [290.0 / 1600.0, 212.0 / 1200.0, 1311.0 / 1600.0, 195.0 / 1200.0, 1292.0 / 1600.0, 1009.0 / 1200.0, 306.0 / 1600.0, 991.0 / 1200.0];
        let found = refine_in(&root, &path, &rough, &[], 0.0).unwrap();
        let truth = [300.0, 200.0, 1300.0, 200.0, 1300.0, 1000.0, 300.0, 1000.0];
        for (i, share) in found.corners.iter().enumerate() {
            let px = share * if i % 2 == 0 { w as f32 } else { h as f32 };
            assert!((px - truth[i]).abs() < 0.5, "corner coordinate {i}: {px} against {}", truth[i]);
        }
        assert_eq!((found.refined.as_slice(), found.curved.as_slice()), ([true; 4].as_slice(), [false; 4].as_slice()));
        assert_eq!(found.timings.iter().map(|t| t.stage.as_str()).collect::<Vec<_>>(), ["scan_decode", "scan_refine"]);
        // only two sides asked for: the corner between the other two stays where it was given
        let two = refine_in(&root, &path, &rough, &[true, true, false, false], 0.0).unwrap();
        assert_eq!(two.refined, [true, true, false, false]);
        assert!((two.corners[6] - rough[6]).abs() < 1e-6 && (two.corners[7] - rough[7]).abs() < 1e-6);
        // refused like the page function: outside the root, not four corners
        let other = temp("refine-other");
        assert!(refine_in(&other, &path, &rough, &[], 0.0).is_err());
        assert!(refine_in(&root, &path, &rough[..6], &[], 0.0).is_err());
        std::fs::remove_dir_all(root).unwrap();
        std::fs::remove_dir_all(other).unwrap();
    }

    /// A black-and-white page (#300) stays one bit per pixel all the way into the saved PDF.
    #[test]
    fn a_black_and_white_page_is_one_bit_in_the_saved_pdf() {
        let root = temp("bw");
        // an A4 page at 200 dpi with rows of small dark marks
        let shot = root.join("page.png");
        image::GrayImage::from_fn(1654, 2339, |x, y| {
            let mark = (200..2100).contains(&y)
                && (150..1500).contains(&x)
                && (y / 20) % 2 == 0
                && ((x / 7) * 7 + (y / 40) * 13) % 5 != 0
                && x % 7 < 4
                && y % 40 > 6;
            image::Luma([if mark { 30 } else { 232 }])
        })
        .save(&shot)
        .unwrap();
        let whole = vec![0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0];
        let page =
            process_in(&root, &ScanPageRequest { corners: whole, look: ScanLook::BlackWhite, strength: 55, ..request(&shot, &root.join("page-0")) })
                .unwrap();
        assert_eq!((page.format, page.paper, page.width, page.height, page.dpi), (ScanFormat::Png, ScanPaper::A4, 1654, 2339, 200.0));
        let png = std::fs::read(&page.path).unwrap();
        // the PNG header: bit depth 1, colour type 0 (grey)
        assert_eq!(&png[24..26], [1, 0]);
        let session = crate::MobileSession::new();
        let args = serde_json::json!({ "from": "images", "paths": [page.path], "name": "Scan" });
        let created: serde_json::Value = serde_json::from_str(&session.execute("doc_create".into(), args.to_string()).unwrap()).unwrap();
        let out = root.join("scan.pdf");
        session.execute("doc_save".into(), serde_json::json!({ "doc": created["doc"], "path": out.to_string_lossy() }).to_string()).unwrap();
        let pdf = std::fs::read(&out).unwrap();
        let text = String::from_utf8_lossy(&pdf);
        assert!(text.contains("/BitsPerComponent 1") && !text.contains("/BitsPerComponent 8"), "the page image is not 1 bit per pixel in the PDF");
        assert!(pdf.len() < 2 * png.len() + 4096, "PDF {} bytes for a PNG of {}", pdf.len(), png.len());
        std::fs::remove_dir_all(root).unwrap();
    }

    /// A sheet on a table: found by the network and put on its edges, in one call.
    #[test]
    fn a_sheet_is_found_and_put_on_its_edges() {
        let root = temp("find");
        let shot = root.join("desk.png");
        let (w, h) = (1200u32, 1600u32);
        // a light sheet with rows of print, a little turned, on a dark table
        let (sin, cos) = 4f64.to_radians().sin_cos();
        image::RgbImage::from_fn(w, h, |x, y| {
            let (dx, dy) = (f64::from(x) - 600.0, f64::from(y) - 800.0);
            let (u, v) = (dx * cos + dy * sin, -dx * sin + dy * cos);
            let sheet = u.abs() < 380.0 && v.abs() < 540.0;
            let print = sheet
                && u.abs() < 320.0
                && v.abs() < 480.0
                && (v as i64).rem_euclid(26) < 8
                && ((u as i64).div_euclid(15) + (v as i64).div_euclid(26)).rem_euclid(4) != 0;
            image::Rgb(if print {
                [45, 45, 50]
            } else if sheet {
                [236, 234, 228]
            } else {
                [62 + (x % 7) as u8, 50 + (y % 5) as u8, 44]
            })
        })
        .save(&shot)
        .unwrap();
        let found = find_in(&root, &shot.to_string_lossy()).unwrap();
        assert!(found.found && found.source == ScanQuadSource::Model && found.confidence >= 0.5, "{found:?}");
        let truth = [(-380.0, -540.0), (380.0, -540.0), (380.0, 540.0), (-380.0, 540.0)]
            .map(|(u, v): (f64, f64)| [600.0 + u * cos - v * sin, 800.0 + u * sin + v * cos]);
        for (i, corner) in truth.iter().enumerate() {
            let (x, y) = (f64::from(found.corners[2 * i]) * f64::from(w), f64::from(found.corners[2 * i + 1]) * f64::from(h));
            assert!((x - corner[0]).hypot(y - corner[1]) < 1.5, "corner {i}: ({x:.1}, {y:.1}) against {corner:?}");
        }
        assert_eq!(found.refined, [true; 4]);
        assert_eq!(found.timings.iter().map(|t| t.stage.as_str()).collect::<Vec<_>>(), ["scan_decode", "scan_detect", "scan_refine"]);
        // nothing to find: the whole photo, and said so
        let blank = root.join("blank.png");
        image::RgbImage::from_fn(640, 480, |x, y| image::Rgb([90 + (x % 3) as u8, 90 + (y % 3) as u8, 90])).save(&blank).unwrap();
        let none = find_in(&root, &blank.to_string_lossy()).unwrap();
        assert!(!none.found && none.source == ScanQuadSource::None && none.corners == [0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0], "{none:?}");
        // outside the root: refused
        let other = temp("find-other");
        assert!(find_in(&other, &shot.to_string_lossy()).is_err());
        std::fs::remove_dir_all(root).unwrap();
        std::fs::remove_dir_all(other).unwrap();
    }

    /// A page asked to be flattened goes through the flattening network (#302).
    #[test]
    fn a_page_is_flattened_when_asked() {
        let root = temp("flatten");
        let shot = root.join("page.png");
        // a sheet whose top and bottom edges bow by 24 pixels, on a dark table
        image::RgbImage::from_fn(900, 1200, |x, y| {
            let bow = 24.0 * (std::f64::consts::PI * (f64::from(x) - 150.0) / 600.0).sin();
            let (level, sheet) = (f64::from(y) + bow, (150..750).contains(&x));
            let sheet = sheet && level > 180.0 && level < 1020.0;
            let print = sheet && (200..700).contains(&x) && level > 240.0 && level < 960.0 && (level as i64 - 240).rem_euclid(30) < 9;
            image::Rgb(if print {
                [40, 40, 46]
            } else if sheet {
                [236, 233, 226]
            } else {
                [66, 54, 46]
            })
        })
        .save(&shot)
        .unwrap();
        assert!(scan_can_flatten(), "assets/models/scan-flatten.onnx is missing or is not the pinned file");
        let corners =
            vec![150.0 / 900.0, 180.0 / 1200.0, 750.0 / 900.0, 180.0 / 1200.0, 750.0 / 900.0, 1020.0 / 1200.0, 150.0 / 900.0, 1020.0 / 1200.0];
        let plain = process_in(&root, &ScanPageRequest { corners: corners.clone(), look: ScanLook::Original, ..request(&shot, &root.join("plain")) })
            .unwrap();
        assert!(!plain.flattened && plain.bend == 0.0);
        let flat =
            process_in(&root, &ScanPageRequest { corners, look: ScanLook::Original, flatten: true, ..request(&shot, &root.join("flat")) }).unwrap();
        assert!(flat.flattened && flat.bend > 0.003, "{flat:?}");
        assert_eq!((flat.width, flat.height), (plain.width, plain.height));
        let stages: Vec<_> = flat.timings.iter().map(|t| t.stage.as_str()).collect();
        assert_eq!(stages, ["scan_decode", "scan_flatten", "scan_rectify", "scan_look", "scan_encode"]);
        // the rows of print are level in the flattened page and arched in the other: how much
        // the first row's top edge moves between the page's sides and its middle
        let arch = |path: &str| {
            let page = image::open(path).unwrap().to_luma8();
            let top = |x: u32| (page.height() / 30..page.height() / 4).find(|&y| page.get_pixel(x, y).0[0] < 110).map_or(0.0, f64::from);
            let (w, scale) = (page.width(), f64::from(page.height()) / 840.0);
            ((top(w / 2) - top(w / 6)).abs() + (top(w / 2) - top(w * 5 / 6)).abs()) / 2.0 / scale
        };
        let (was, now) = (arch(&plain.path), arch(&flat.path));
        assert!(was > 8.0 && now < was / 3.0, "arched by {was:.1} px before and {now:.1} px after");
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn paths_outside_the_root_are_refused() {
        let root = temp("root");
        let other = temp("other");
        let inside = photo(&root);
        let outside = photo(&other);
        assert!(process_in(&root, &request(&outside, &root.join("p"))).is_err(), "photo outside");
        assert!(process_in(&root, &request(&inside, &other.join("p"))).is_err(), "output outside");
        assert!(process_in(&root, &request(&inside, &root.join("..").join(other.file_name().unwrap()).join("p"))).is_err(), "output through ..");
        assert!(process_in(&root, &request(&inside, &root.join("missing").join("p"))).is_err(), "no such folder");
        assert!(process_in(&root, &ScanPageRequest { corners: vec![0.0; 6], ..request(&inside, &root.join("p")) }).is_err(), "six numbers");
        assert!(!other.join("p.jpg").exists());
        std::fs::remove_dir_all(root).unwrap();
        std::fs::remove_dir_all(other).unwrap();
    }
}
