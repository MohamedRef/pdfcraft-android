//! The detectors' report for the testing option (#305, #306), from a camera file's bytes as the
//! app sends them. Every page is drawn in code or is one of the drawn print pages in `tests/data/`.

use crate::inspect::{STAGE_BASE, STAGE_DEFECTS, STAGE_LOOK, STAGE_MOIRE, inspect_photo};
use crate::moire::MoireKind;
use crate::page::WHOLE_PHOTO;
use crate::warp::Rgb;

fn png(page: &Rgb) -> Vec<u8> {
    let picture = image::RgbImage::from_raw(page.width, page.height, page.data.clone()).unwrap();
    let mut out = std::io::Cursor::new(Vec::new());
    picture.write_to(&mut out, image::ImageFormat::Png).unwrap();
    out.into_inner()
}

fn grey_page(bytes: &[u8]) -> image::GrayImage {
    image::load_from_memory(bytes).unwrap().to_luma8()
}

/// The three drawn print pages and the English one again, two by two, as colour (no resampling,
/// so the print brings no short period of its own).
fn print_sheet() -> Rgb {
    let pages = [
        grey_page(include_bytes!("../tests/data/upright-english.png")),
        grey_page(include_bytes!("../tests/data/upright-arabic.png")),
        grey_page(include_bytes!("../tests/data/upright-form.png")),
        grey_page(include_bytes!("../tests/data/upright-english.png")),
    ];
    let (w, h) = (pages[0].width() as usize, pages[0].height() as usize);
    let mut data = vec![0u8; 4 * w * h * 3];
    for (n, page) in pages.iter().enumerate() {
        let (x0, y0) = ((n % 2) * w, (n / 2) * h);
        for y in 0..h {
            for x in 0..w {
                let v = page.get_pixel(x as u32, y as u32).0[0];
                let i = ((y0 + y) * 2 * w + x0 + x) * 3;
                data[i..i + 3].copy_from_slice(&[v, v, v]);
            }
        }
    }
    Rgb { width: 2 * w as u32, height: 2 * h as u32, data }
}

/// `page` darkened by a sinusoidal grating of `period` pixels at `degrees`: a photographed screen.
fn grated(page: &Rgb, period: f32, degrees: f32) -> Rgb {
    let (s, c) = degrees.to_radians().sin_cos();
    let w = page.width as usize;
    let data = page
        .data
        .iter()
        .enumerate()
        .map(|(i, &v)| {
            let (x, y) = ((i / 3 % w) as f32, (i / 3 / w) as f32);
            let k = 1.0 - 0.3 * (0.5 + 0.5 * (std::f32::consts::TAU * (x * c + y * s) / period).sin());
            (f32::from(v) * k).round() as u8
        })
        .collect();
    Rgb { data, ..page.clone() }
}

const W: u32 = 1200;
const H: u32 = 1600;
const PAPER: [u8; 3] = [245, 243, 238];

/// A page with bars of "print" in its middle and blank margins, as in the defect tests.
fn printed() -> Rgb {
    let mut page = Rgb { width: W, height: H, data: PAPER.iter().copied().cycle().take((W * H * 3) as usize).collect() };
    for y in 200..1100u32 {
        if (y - 200) % 30 >= 10 {
            continue;
        }
        for x in 150..1050u32 {
            if (x - 150) % 70 < 58 {
                set(&mut page, x, y, [30, 30, 35]);
            }
        }
    }
    page
}

fn set(page: &mut Rgb, x: u32, y: u32, c: [u8; 3]) {
    let i = ((y * page.width + x) * 3) as usize;
    page.data[i..i + 3].copy_from_slice(&c);
}

/// A finger in a skin colour reaching `length` px in from the bottom edge at `cx`, or from the
/// left edge at height `cx` when `from_left`.
fn finger(page: &mut Rgb, from_left: bool, at: u32, width: u32, length: u32) {
    let r = width as f32 / 2.0;
    for along in 0..length {
        for across in at - width / 2..at + width / 2 {
            let d = across as f32 - at as f32;
            let tip = length as f32 - r;
            if along as f32 > tip && d.hypot(along as f32 - tip) > r {
                continue;
            }
            let shade = 1.0 - 0.15 * (d / r).powi(2);
            let skin = [(212.0 * shade) as u8, (160.0 * shade) as u8, (128.0 * shade) as u8];
            let (x, y) = if from_left { (along, across) } else { (across, H - 1 - along) };
            set(page, x, y, skin);
        }
    }
}

/// A photographed screen is reported as one, with its period, and has no fingers or glare.
#[test]
fn a_screen_is_reported_with_its_period() {
    let seen = inspect_photo(&png(&grated(&print_sheet(), 4.5, 30.0)), WHOLE_PHOTO).unwrap();
    assert_eq!(seen.moire, MoireKind::Screen, "{seen:?}");
    let period = seen.moire_period.unwrap();
    assert!((period - 4.5).abs() <= 0.3, "period {period}");
    assert!(seen.moire_periodic_tiles > 0 && seen.moire_periodic_tiles <= seen.moire_tiles);
    assert_eq!(seen.fingers.map(|f| f.count), Some(0), "{seen:?}");
    assert_eq!(seen.flagged, 0);
}

/// Plain print: nothing to report.
#[test]
fn plain_print_reports_nothing() {
    let seen = inspect_photo(&png(&print_sheet()), WHOLE_PHOTO).unwrap();
    assert_eq!(seen.moire, MoireKind::None, "{seen:?}");
    assert_eq!(seen.moire_period, None);
    for regions in [seen.fingers, seen.glare] {
        assert_eq!(regions.map(|r| r.count), Some(0), "{seen:?}");
    }
    assert_eq!(seen.flagged, 0);
}

/// A finger on the blank margin is counted and not flagged; the same finger over print is flagged.
#[test]
fn a_finger_is_counted_and_flagged_only_over_print() {
    let mut margin = printed();
    finger(&mut margin, false, 300, 110, 260);
    let seen = inspect_photo(&png(&margin), WHOLE_PHOTO).unwrap();
    let fingers = seen.fingers.unwrap();
    assert_eq!((fingers.count, fingers.over_print), (1, 0), "{seen:?}");
    assert!(fingers.area > 0.005 && fingers.area < 0.05, "{seen:?}");
    assert_eq!(seen.flagged, 0);
    assert_eq!(seen.moire, MoireKind::None);

    let mut over = printed();
    finger(&mut over, true, 600, 110, 300);
    let seen = inspect_photo(&png(&over), WHOLE_PHOTO).unwrap();
    assert_eq!(seen.fingers.map(|f| (f.count, f.over_print)), Some((1, 1)), "{seen:?}");
    assert_eq!(seen.flagged, 1);
}

/// The report names its four stages, in order, and the straightened page's size.
#[test]
fn the_report_has_its_stages_and_size() {
    let seen = inspect_photo(&png(&printed()), WHOLE_PHOTO).unwrap();
    let stages: Vec<&str> = seen.timings.iter().map(|(s, _)| *s).collect();
    assert_eq!(stages, [STAGE_BASE, STAGE_MOIRE, STAGE_LOOK, STAGE_DEFECTS]);
    assert_eq!((seen.width, seen.height), (W, H));
}

/// Bytes that are not a photo are an error, not a panic.
#[test]
fn a_broken_photo_is_an_error() {
    assert!(inspect_photo(b"not a photo", WHOLE_PHOTO).is_err());
}
