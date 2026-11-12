//! Scan (#287): one photo → one straightened, cleaned page image, through `pdfcraft-scan`.
//!
//! The functions only read and write inside the directory given to [`scan_set_root`] (the app's
//! cache directory): both paths are resolved (links followed) and checked against it.

use std::path::{Path, PathBuf};
use std::sync::RwLock;

use pdfcraft_scan::{AspectSource, Format, Look, PageRequest, Paper, process_page};

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

pub(crate) fn process_in(root: &Path, request: &ScanPageRequest) -> Result<ScanPageResult, MobileError> {
    let outside = || MobileError::Command("the scan's files must be in the app's own folder".into());
    let photo_path = Path::new(&request.photo_path).canonicalize().map_err(|_| MobileError::Command("the photo could not be read".into()))?;
    if !photo_path.starts_with(root) {
        return Err(outside());
    }
    let corners: [f32; 8] = request.corners.as_slice().try_into().map_err(|_| MobileError::Command("a page needs four corners".into()))?;
    // The output's folder must exist inside the root; its name may not carry a folder.
    let out = Path::new(&request.output_path);
    let (Some(parent), Some(name)) = (out.parent(), out.file_name()) else { return Err(outside()) };
    let parent = parent.canonicalize().map_err(|_| outside())?;
    if !parent.starts_with(root) {
        return Err(outside());
    }
    let size = std::fs::metadata(&photo_path).map_err(|_| MobileError::Command("the photo could not be read".into()))?.len();
    if size > MAX_PHOTO_BYTES {
        return Err(MobileError::Command("the photo is too large".into()));
    }
    let bytes = std::fs::read(&photo_path).map_err(|_| MobileError::Command("the photo could not be read".into()))?;
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
