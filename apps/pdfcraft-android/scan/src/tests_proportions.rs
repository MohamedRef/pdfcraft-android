//! True proportions (#293): the corner formula against a synthetic pinhole camera, paper sizes,
//! the resolution written into the files, and the lens data read from a photo.

use crate::Quad;
use crate::aspect::{AspectSource, Focal, Paper, focal_px, proportions, proportions_by_sides};
use crate::encode::{Raster, encode_jpeg, encode_png_grey};
use crate::exif::{Camera, camera};

const PHOTO: (u32, u32) = (4000, 3000);

struct Rng(u64);

impl Rng {
    fn unit(&mut self) -> f64 {
        self.0 = self.0.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        (self.0 >> 11) as f64 / (1u64 << 53) as f64
    }

    fn between(&mut self, a: f64, b: f64) -> f64 {
        a + self.unit() * (b - a)
    }

    fn normal(&mut self) -> f64 {
        (0..12).map(|_| self.unit()).sum::<f64>() - 6.0
    }
}

/// One view of a page of `ratio` (width over height) by a pinhole camera with focal length
/// `focal` (in units of the photo's longer side): tilted by `ax` and `ay` degrees about the
/// page's horizontal and vertical axes and turned by `az` in the photo. The four corners in
/// photo pixels (the page's own top-left first, then clockwise), with `noise` pixels of jitter.
fn view(ratio: f64, focal: f64, (ax, ay, az): (f64, f64, f64), noise: f64, rng: &mut Rng) -> [[f64; 2]; 4] {
    let (h, w) = (1.0, ratio);
    let scale = 0.45 / w.max(h);
    let (ax, ay, az) = (ax.to_radians(), ay.to_radians(), az.to_radians());
    let long = f64::from(PHOTO.0.max(PHOTO.1));
    [[-w / 2.0, -h / 2.0], [w / 2.0, -h / 2.0], [w / 2.0, h / 2.0], [-w / 2.0, h / 2.0]].map(|[x, y]| {
        let (x, y, z) = (x * scale, y * scale, 0.0f64);
        // about x, then about y, then in the photo's plane
        let (y, z) = (y * ax.cos() - z * ax.sin(), y * ax.sin() + z * ax.cos());
        let (x, z) = (x * ay.cos() + z * ay.sin(), -x * ay.sin() + z * ay.cos());
        let (x, y) = (x * az.cos() - y * az.sin(), x * az.sin() + y * az.cos());
        let depth = focal + z;
        [
            f64::from(PHOTO.0) / 2.0 + long * focal * x / depth + noise * rng.normal(),
            f64::from(PHOTO.1) / 2.0 + long * focal * y / depth + noise * rng.normal(),
        ]
    })
}

/// The error of the measured long-over-short ratio against `truth`, as a share.
fn error(measured: f64, truth: f64) -> f64 {
    (measured.max(1.0 / measured) / truth.max(1.0 / truth) - 1.0).abs()
}

struct Trial {
    error: f64,
    source: AspectSource,
}

/// What the trials vary: the tilt about each of the page's axes (degrees, either way), the
/// camera's focal length (in units of the photo's longer side) and the corner noise in pixels.
struct Setup {
    tilt_x: (f64, f64),
    tilt_y: (f64, f64),
    focal: (f64, f64),
    noise: f64,
    known: bool,
}

/// A phone's main camera: 23 to 30 mm equivalent on a 4:3 photo.
const MAIN_CAMERA: (f64, f64) = (0.65, 0.85);

fn trials(n: usize, s: &Setup, seed: u64) -> Vec<Trial> {
    let mut rng = Rng(seed);
    (0..n)
        .filter_map(|i| {
            let ratio = [std::f64::consts::SQRT_2, 1.2941, 1.0, 2.5, 1.0 / std::f64::consts::SQRT_2][i % 5];
            let focal = rng.between(s.focal.0, s.focal.1);
            let either = |rng: &mut Rng, (a, b): (f64, f64)| if rng.unit() < 0.5 { -rng.between(a, b) } else { rng.between(a, b) };
            // the strongly tilted axis is the page's width as often as its height
            let (a, b) = (either(&mut rng, s.tilt_x), either(&mut rng, s.tilt_y));
            let angles = if i % 2 == 0 { (a, b, rng.between(0.0, 360.0)) } else { (b, a, rng.between(0.0, 360.0)) };
            let quad = Quad::new(view(ratio, focal, angles, s.noise, &mut rng)).ok()?;
            let p = proportions(&quad, PHOTO.0, PHOTO.1, s.known.then_some(focal * 4000.0), false);
            Some(Trial { error: error(p.ratio, ratio), source: p.source })
        })
        .collect()
}

/// The median and the 95th percentile of the errors.
fn summary(t: &[Trial]) -> (f64, f64) {
    let mut e: Vec<f64> = t.iter().map(|t| t.error).collect();
    e.sort_by(f64::total_cmp);
    (e[e.len() / 2], e[e.len() * 95 / 100])
}

#[test]
fn tilted_pages_get_their_true_ratio_from_the_corners_alone() {
    let t = trials(2000, &Setup { tilt_x: (10.0, 50.0), tilt_y: (10.0, 50.0), focal: (0.6, 1.2), noise: 0.5, known: false }, 1);
    let (median, p95) = summary(&t);
    let estimated = t.iter().filter(|t| t.source == AspectSource::EstimatedFocal).count();
    println!("two-axis tilt: median {median:.4}, p95 {p95:.4}, focal from the corners on {estimated} of {}", t.len());
    assert!(median <= 0.005 && p95 <= 0.015, "median {median}, p95 {p95}");
    assert!(estimated * 10 >= t.len() * 9, "the corners gave a focal length on only {estimated} of {}", t.len());
}

#[test]
fn a_known_focal_length_makes_it_exact_at_any_tilt() {
    for (tilt_y, seed) in [((0.0, 50.0), 2), ((0.0, 3.0), 3)] {
        let t = trials(2000, &Setup { tilt_x: (0.0, 50.0), tilt_y, focal: (0.6, 1.2), noise: 0.5, known: true }, seed);
        let (median, p95) = summary(&t);
        println!("known focal, second axis {tilt_y:?}: median {median:.4}, p95 {p95:.4}");
        assert!(median <= 0.003 && p95 <= 0.01, "median {median}, p95 {p95}");
        assert!(t.iter().all(|t| t.source == AspectSource::KnownFocal));
    }
}

/// The usual way to hold a phone: tilted towards the person, hardly sideways. One pair of the
/// page's sides is then nearly parallel in the photo and the corners say little about the focal
/// length, so a typical camera's stands in for it. The side lengths alone would be off by a
/// third at 45 degrees.
#[test]
fn a_page_tilted_about_one_axis_leans_on_a_typical_camera() {
    let setup = |noise| Setup { tilt_x: (10.0, 50.0), tilt_y: (0.0, 3.0), focal: MAIN_CAMERA, noise, known: false };
    for (noise, seed, limit) in [(0.5, 4, (0.01, 0.05)), (3.0, 5, (0.02, 0.08))] {
        let t = trials(2000, &setup(noise), seed);
        let (median, p95) = summary(&t);
        let typical = t.iter().filter(|t| t.source == AspectSource::TypicalFocal).count();
        println!("one-axis tilt, noise {noise}: median {median:.4}, p95 {p95:.4}, typical focal on {typical} of {}", t.len());
        assert!(median <= limit.0 && p95 <= limit.1, "noise {noise}: median {median}, p95 {p95}");
    }
    // exactly one axis: the corners give no focal length at all
    let mut rng = Rng(6);
    let sqrt2 = std::f64::consts::SQRT_2;
    for (tilt, limit) in [(15.0, 0.015), (30.0, 0.045), (45.0, 0.09)] {
        for focal in [0.65, 0.75, 0.85] {
            let quad = Quad::new(view(sqrt2, focal, (tilt, 0.0, 0.0), 0.0, &mut rng)).unwrap();
            let p = proportions(&quad, PHOTO.0, PHOTO.1, None, false);
            assert_eq!(p.source, AspectSource::TypicalFocal);
            assert!(error(p.ratio, sqrt2) <= limit, "tilt {tilt}, focal {focal}: {}", p.ratio);
            let [top, right, bottom, left] = quad.side_lengths();
            assert!(error(p.ratio, sqrt2) < error((top + bottom) / (left + right), sqrt2), "no better than the side lengths at tilt {tilt}");
            // with the focal length known it is exact
            let known = proportions(&quad, PHOTO.0, PHOTO.1, Some(focal * 4000.0), false);
            assert!(error(known.ratio, sqrt2) < 1e-6, "tilt {tilt}, focal {focal}: {}", known.ratio);
        }
    }
}

/// Everyday photos: up to 35 degrees one way, up to 10 the other, corners a few pixels off.
#[test]
fn everyday_photos_keep_their_shape_within_a_few_percent() {
    for (noise, seed, limit) in [(0.5, 7, (0.003, 0.02)), (3.0, 8, (0.01, 0.04))] {
        let t = trials(3000, &Setup { tilt_x: (5.0, 35.0), tilt_y: (0.0, 10.0), focal: MAIN_CAMERA, noise, known: false }, seed);
        let (median, p95) = summary(&t);
        println!("everyday, noise {noise}: median {median:.4}, p95 {p95:.4}");
        assert!(median <= limit.0 && p95 <= limit.1, "noise {noise}: median {median}, p95 {p95}");
    }
}

#[test]
fn nearly_frontal_pages_need_no_focal_length() {
    let t = trials(1000, &Setup { tilt_x: (0.0, 3.0), tilt_y: (0.0, 3.0), focal: (0.6, 1.2), noise: 0.5, known: false }, 9);
    let (median, p95) = summary(&t);
    println!("frontal: median {median:.4}, p95 {p95:.4}");
    assert!(median <= 0.002 && p95 <= 0.005, "median {median}, p95 {p95}");
    // exactly frontal: no perspective at all
    let quad = Quad::new([[1000.0, 500.0], [3000.0, 500.0], [3000.0, 2500.0], [1000.0, 2500.0]]).unwrap();
    let p = proportions(&quad, PHOTO.0, PHOTO.1, None, false);
    assert!((p.ratio - 1.0).abs() < 1e-12 && p.source == AspectSource::TypicalFocal, "{p:?}");
}

/// A camera that reports one focal length while the corners clearly show another (a cropped
/// photo, a page that is not flat, wrong corners) is flagged, and only then.
#[test]
fn a_focal_length_the_corners_contradict_is_flagged() {
    let mut rng = Rng(10);
    let corners = view(std::f64::consts::SQRT_2, 0.75, (30.0, 25.0, 10.0), 0.0, &mut rng);
    let quad = Quad::new(corners).unwrap();
    let right = proportions(&quad, PHOTO.0, PHOTO.1, Some(0.75 * 4000.0), false);
    assert!(!right.focal_mismatch && (right.estimated_focal_px.unwrap() / 3000.0 - 1.0).abs() < 1e-6, "{right:?}");
    assert!(proportions(&quad, PHOTO.0, PHOTO.1, Some(1.2 * 4000.0), false).focal_mismatch);
    // tilted about one axis, the corners imply nothing firm and nothing is flagged
    let quad = Quad::new(view(std::f64::consts::SQRT_2, 0.75, (30.0, 0.5, 0.0), 0.0, &mut rng)).unwrap();
    let p = proportions(&quad, PHOTO.0, PHOTO.1, Some(1.2 * 4000.0), false);
    assert!(!p.focal_mismatch && p.estimated_focal_px.is_none(), "{p:?}");
}

#[test]
fn turning_or_scaling_the_photo_does_not_change_the_shape() {
    let mut rng = Rng(5);
    let corners = view(1.2941, 0.8, (25.0, -18.0, 12.0), 0.0, &mut rng);
    let base = proportions(&Quad::new(corners).unwrap(), PHOTO.0, PHOTO.1, None, false).ratio;
    assert!(error(base, 1.2941) < 1e-3, "{base}");
    // a quarter turn of the photo: (x, y) -> (H - y, x), and the page's width and height swap
    let turned = corners.map(|[x, y]| [f64::from(PHOTO.1) - y, x]);
    let r = proportions(&Quad::new(turned).unwrap(), PHOTO.1, PHOTO.0, None, false).ratio;
    assert!((r * base - 1.0).abs() < 1e-6, "turned {r}, upright {base}");
    // the same photo at half size
    let half = corners.map(|[x, y]| [x / 2.0, y / 2.0]);
    let r = proportions(&Quad::new(half).unwrap(), PHOTO.0 / 2, PHOTO.1 / 2, None, false).ratio;
    assert!((r / base - 1.0).abs() < 1e-9);
}

#[test]
fn bad_corners_never_give_a_wild_shape() {
    // a dented quad the formula would turn into nonsense, and corners pushed by heavy noise
    let mut rng = Rng(11);
    for _ in 0..500 {
        let Ok(quad) =
            Quad::new(view(std::f64::consts::SQRT_2, 0.8, (rng.between(0.0, 60.0), rng.between(0.0, 60.0), rng.between(0.0, 360.0)), 40.0, &mut rng))
        else {
            continue;
        };
        let p = proportions(&quad, PHOTO.0, PHOTO.1, None, true);
        let [top, right, bottom, left] = quad.side_lengths();
        let sides = (top + bottom) / (left + right);
        assert!(p.ratio.is_finite() && (p.ratio / sides).max(sides / p.ratio) <= 3.0 * 1.04, "{} against sides {sides}", p.ratio);
        assert!(p.width.is_finite() && p.height.is_finite() && p.width >= 1.0 && p.height >= 1.0);
        assert!(p.output(1.0).is_ok());
    }
}

/// The testing choice (#288): the side lengths alone, as before #293. Right square on, and short
/// along the lean for a tilted page, which is what the corner formula is for.
#[test]
fn the_side_lengths_alone_can_be_asked_for() {
    let square_on = proportions_by_sides(&frontal(1000.0, 1400.0), PHOTO.0, PHOTO.1, true);
    assert_eq!((square_on.source, square_on.paper), (AspectSource::SideLengths, Paper::A4));
    assert!(!square_on.focal_mismatch && square_on.estimated_focal_px.is_none());
    let mut rng = Rng(12);
    let quad = Quad::new(view(std::f64::consts::SQRT_2, 0.75, (35.0, 20.0, 5.0), 0.0, &mut rng)).unwrap();
    let (sides, formula) = (proportions_by_sides(&quad, PHOTO.0, PHOTO.1, false), proportions(&quad, PHOTO.0, PHOTO.1, None, false));
    let [top, right, bottom, left] = quad.side_lengths();
    assert_eq!(sides.source, AspectSource::SideLengths);
    assert!((sides.ratio - (top + bottom) / (left + right)).abs() < 1e-12, "{sides:?}");
    println!("tilted A4: the sides say {:.4}, the corners {:.4}", sides.ratio, formula.ratio);
    assert!(error(formula.ratio, std::f64::consts::SQRT_2) < 0.001 && error(sides.ratio, std::f64::consts::SQRT_2) > 0.05);
}

fn frontal(w: f64, h: f64) -> Quad {
    Quad::new([[100.0, 100.0], [100.0 + w, 100.0], [100.0 + w, 100.0 + h], [100.0, 100.0 + h]]).unwrap()
}

#[test]
fn shapes_within_three_percent_snap_to_a_paper() {
    let paper = |w: f64, h: f64, snap| proportions(&frontal(w, h), PHOTO.0, PHOTO.1, None, snap).paper;
    assert_eq!(paper(1000.0, 1400.0, true), Paper::A4);
    assert_eq!(paper(1400.0, 1000.0, true), Paper::A4);
    assert_eq!(paper(1000.0, 1360.0, true), Paper::Custom);
    assert_eq!(paper(1000.0, 1300.0, true), Paper::Letter);
    assert_eq!(paper(1000.0, 1640.0, true), Paper::Legal);
    assert_eq!(paper(1580.0, 1000.0, true), Paper::IdCard);
    assert_eq!(paper(1000.0, 1010.0, true), Paper::Square);
    assert_eq!(paper(1000.0, 2500.0, true), Paper::Custom);
    assert_eq!(paper(1000.0, 1400.0, false), Paper::Custom);
    // a snapped page has the paper's exact ratio, in the page's own orientation
    let p = proportions(&frontal(1400.0, 1000.0), PHOTO.0, PHOTO.1, None, true);
    assert_eq!(p.ratio, std::f64::consts::SQRT_2);
    assert_eq!(proportions(&frontal(1000.0, 1400.0), PHOTO.0, PHOTO.1, None, true).ratio, 1.0 / std::f64::consts::SQRT_2);
}

#[test]
fn a_standard_paper_gets_exactly_its_size() {
    // A4 at the resolution the photo gives: the PDF page is 595 x 842 points to within half a point
    let p = proportions(&frontal(2000.0, 2800.0), PHOTO.0, PHOTO.1, None, true);
    let (w, h, dpi) = p.output(1.0).unwrap();
    let points = |px: u32| f64::from(px) * 72.0 / f64::from(dpi);
    assert!((points(w) - 595.28).abs() <= 0.5 && (points(h) - 841.89).abs() <= 0.5, "{w} x {h} at {dpi} dpi = {} x {} pt", points(w), points(h));
    assert!((2800..=2830).contains(&h), "the nearer side keeps its resolution: {h}");
    // landscape Letter
    let (w, h, dpi) = proportions(&frontal(2200.0, 1700.0), PHOTO.0, PHOTO.1, None, true).output(1.0).unwrap();
    assert!((points_of(w, dpi) - 792.0).abs() <= 0.5 && (points_of(h, dpi) - 612.0).abs() <= 0.5, "{w} x {h} at {dpi}");
    // enlarged, as Auto does for small pages
    let (w2, h2, dpi2) = p.output(1.5).unwrap();
    assert!((f64::from(h2) / f64::from(h_of(&p)) - 1.5).abs() < 0.01 && dpi2 > dpi, "{w2} x {h2} at {dpi2}");
    // a custom shape keeps its pixels (a frontal page is copied one to one) and its long side is 297 mm
    let (w, h, dpi) = proportions(&frontal(1000.0, 1200.0), PHOTO.0, PHOTO.1, None, true).output(1.0).unwrap();
    assert_eq!((w, h, dpi), (1000, 1200, 103));
    // never more than 40 megapixels
    let (w, h, _) = proportions(&frontal(3000.0, 4200.0), PHOTO.0, PHOTO.1, None, true).output(4.0).unwrap();
    assert!(u64::from(w) * u64::from(h) <= 40_000_000, "{w} x {h}");
}

fn points_of(px: u32, dpi: u16) -> f64 {
    f64::from(px) * 72.0 / f64::from(dpi)
}

fn h_of(p: &crate::Proportions) -> u32 {
    p.output(1.0).unwrap().1
}

#[test]
fn the_focal_length_in_pixels_comes_from_the_35mm_equivalent() {
    // 26 mm on a 4:3 photo: 26 / 43.27 of the diagonal, which is 0.75 of the longer side
    let f = focal_px(26.0, 4000, 3000).unwrap();
    assert!((f - 26.0 * 5000.0 / 43.27).abs() < 1e-6 && (f / 4000.0 - 0.751).abs() < 1e-3, "{f}");
    assert_eq!(focal_px(26.0, 3000, 4000), Some(f));
    // a cropped photo's centre and diagonal say nothing about the lens
    assert_eq!(focal_px(26.0, 2100, 3000), None);
    assert_eq!(focal_px(0.0, 4000, 3000), None);
    assert_eq!(focal_px(f32::NAN, 4000, 3000), None);
    // a zoom of 1, or none, is no zoom; without a focal length a zoom means nothing
    assert_eq!(Focal::from_35mm(Some(26.0), Some(1.0), 4000, 3000), Focal { px: Some(f), zoom: None });
    assert_eq!(Focal::from_35mm(Some(26.0), Some(2.0), 4000, 3000), Focal { px: Some(f), zoom: Some(2.0) });
    assert_eq!(Focal::from_35mm(None, Some(2.0), 4000, 3000), Focal::default());
}

/// Cameras differ on whether the focal length they write includes the digital zoom. Where the
/// corners fix the focal length they settle it; where they cannot, the zoom is applied.
#[test]
fn a_digital_zoom_is_applied_unless_the_corners_show_it_already_is() {
    let mut rng = Rng(12);
    let sqrt2 = std::f64::consts::SQRT_2;
    // taken at 2x: the true focal length is 1.5 of the longer side
    let quad = Quad::new(view(sqrt2, 1.5, (30.0, 25.0, 10.0), 0.5, &mut rng)).unwrap();
    for written in [0.75, 1.5] {
        // the file says 0.75 (lens) or 1.5 (lens with the zoom), and a zoom of 2 either way
        let p = proportions(&quad, PHOTO.0, PHOTO.1, Focal { px: Some(written * 4000.0), zoom: Some(2.0) }, false);
        assert!(p.source == AspectSource::KnownFocal && !p.focal_mismatch && error(p.ratio, sqrt2) < 0.005, "written {written}: {p:?}");
    }
    // tilted one way only, the corners cannot tell: the standard's reading (lens times zoom)
    let quad = Quad::new(view(sqrt2, 1.5, (30.0, 0.0, 0.0), 0.0, &mut rng)).unwrap();
    let p = proportions(&quad, PHOTO.0, PHOTO.1, Focal { px: Some(0.75 * 4000.0), zoom: Some(2.0) }, false);
    assert!(error(p.ratio, sqrt2) < 1e-6, "{p:?}");
}

/// A TIFF block with an EXIF directory holding the two tags, in either byte order.
fn exif_block(little: bool, focal: u16, zoom: (u32, u32)) -> Vec<u8> {
    let u16b = |v: u16| if little { v.to_le_bytes() } else { v.to_be_bytes() };
    let u32b = |v: u32| if little { v.to_le_bytes() } else { v.to_be_bytes() };
    let mut b = if little { b"II\x2a\0".to_vec() } else { b"MM\0\x2a".to_vec() };
    b.extend(u32b(8));
    // IFD0 at 8: one entry, the EXIF directory at 26
    b.extend(u16b(1));
    b.extend(u16b(0x8769));
    b.extend(u16b(4));
    b.extend(u32b(1));
    b.extend(u32b(26));
    b.extend(u32b(0));
    // EXIF directory at 26: two entries, then the rational at 56
    b.extend(u16b(2));
    b.extend(u16b(0xA404));
    b.extend(u16b(5));
    b.extend(u32b(1));
    b.extend(u32b(56));
    b.extend(u16b(0xA405));
    b.extend(u16b(3));
    b.extend(u32b(1));
    b.extend(u16b(focal));
    b.extend(u16b(0));
    b.extend(u32b(0));
    assert_eq!(b.len(), 56);
    b.extend(u32b(zoom.0));
    b.extend(u32b(zoom.1));
    b
}

#[test]
fn lens_data_is_read_from_exif_in_both_byte_orders() {
    for little in [true, false] {
        let block = exif_block(little, 26, (3, 2));
        assert_eq!(camera(&block), Camera { focal_35mm: Some(26.0), digital_zoom: Some(1.5) });
        let mut prefixed = b"Exif\0\0".to_vec();
        prefixed.extend(&block);
        assert_eq!(camera(&prefixed).focal_35mm, Some(26.0));
        // "not used" values and damage give nothing, never a panic
        assert_eq!(camera(&exif_block(little, 0, (0, 1))), Camera::default());
        for cut in 0..block.len() {
            let _ = camera(&block[..cut]);
        }
        let mut wild = block.clone();
        wild[4..8].copy_from_slice(&[0xff; 4]);
        assert_eq!(camera(&wild), Camera::default());
    }
    assert_eq!(camera(b"not exif"), Camera::default());
}

#[test]
fn the_resolution_is_written_into_both_kinds_of_file() {
    let grey = vec![200u8; 40 * 30];
    let page = Raster { pixels: &grey, grey: true, width: 40, height: 30, dpi: 291 };
    let jpeg = encode_jpeg(&page, 90).unwrap();
    // JFIF APP0: marker, length, "JFIF\0", version, unit (1 = per inch), x and y density
    assert_eq!(&jpeg[2..4], [0xff, 0xe0]);
    assert_eq!(&jpeg[6..11], b"JFIF\0");
    assert_eq!((jpeg[13], u16::from_be_bytes([jpeg[14], jpeg[15]]), u16::from_be_bytes([jpeg[16], jpeg[17]])), (1, 291, 291));
    let png_bytes = encode_png_grey(&page).unwrap();
    let reader = png::Decoder::new(std::io::Cursor::new(&png_bytes)).read_info().unwrap();
    let dims = reader.info().pixel_dims.unwrap();
    assert_eq!((dims.xppu, dims.yppu, dims.unit), (11457, 11457, png::Unit::Meter));
    assert_eq!((reader.info().width, reader.info().height, reader.info().color_type), (40, 30, png::ColorType::Grayscale));
}

fn jpeg_of(photo: &crate::Photo) -> Vec<u8> {
    encode_jpeg(&Raster { pixels: &photo.rgb, grey: false, width: photo.width, height: photo.height, dpi: 72 }, 95).unwrap()
}

/// `jpeg` with an EXIF block after the start marker, as a camera writes it.
fn with_exif(jpeg: &[u8], tiff: &[u8]) -> Vec<u8> {
    let mut out = jpeg[..2].to_vec();
    out.extend([0xff, 0xe1]);
    out.extend(u16::try_from(tiff.len() + 8).unwrap().to_be_bytes());
    out.extend(b"Exif\0\0");
    out.extend(tiff);
    out.extend(&jpeg[2..]);
    out
}

/// A4 photographed at 35 degrees: the page that comes back is A4, where the longest sides
/// would have made it a tenth too short.
#[test]
fn a_tilted_a4_page_comes_back_as_a4() {
    use crate::{Look, PageRequest, process_page};
    let page = crate::tests::test_page(1000, 1414);
    let mut rng = Rng(13);
    let (w, h) = (2000u32, 1500u32);
    // 26 mm equivalent: 0.751 of the longer side
    let corners = view(1000.0 / 1414.0, 26.0 * 1.25 / 43.27, (35.0, 8.0, 4.0), 0.0, &mut rng).map(|[x, y]| [x / 2.0, y / 2.0]);
    let photo = crate::tests::photograph(&page, w, h, corners);
    let shares: [f32; 8] = std::array::from_fn(|i| (corners[i / 2][i % 2] / f64::from(if i % 2 == 0 { w } else { h })) as f32);
    let jpeg = jpeg_of(&photo);

    let quad = Quad::new(corners).unwrap();
    let [top, right, bottom, left] = quad.side_lengths();
    let old = top.max(bottom) / left.max(right);
    println!("longest sides: {old:.4} ({:+.1} % from A4)", 100.0 * (old * std::f64::consts::SQRT_2 - 1.0));
    assert!(error(old, std::f64::consts::SQRT_2) > 0.05, "the old rule was already right: {old}");

    let a4 = |r: &crate::PageResult| {
        let points = |px: u32| f64::from(px) * 72.0 / f64::from(r.dpi);
        (points(r.width) - 595.28).abs() <= 1.0 && (points(r.height) - 841.89).abs() <= 1.0
    };
    // the corners alone
    let request = PageRequest { look: Look::Original, ..PageRequest::new(&jpeg, shares) };
    let r = process_page(&request).unwrap();
    assert!(r.paper == Paper::A4 && a4(&r), "{:?} {} x {} at {} dpi from {:?}", r.paper, r.width, r.height, r.dpi, r.aspect);
    assert_ne!(r.aspect, AspectSource::KnownFocal);
    // the page really is the page: its pixels match the original laid over it
    let back = crate::Photo::decode(&r.bytes).unwrap();
    let truth = crate::warp::rectify_with_threads(
        &page,
        &Quad::new([[0.0, 0.0], [1000.0, 0.0], [1000.0, 1414.0], [0.0, 1414.0]]).unwrap(),
        back.width,
        back.height,
        1,
    )
    .unwrap();
    let off = back.rgb.iter().zip(&truth.data).filter(|(a, b)| a.abs_diff(**b) > 96).count() as f64 / back.rgb.len() as f64;
    assert!(off < 0.02, "{:.2} % of the page is somewhere else", 100.0 * off);

    // the camera's own focal length, from the file
    let r = process_page(&PageRequest { photo: &with_exif(&jpeg, &exif_block(true, 26, (1, 1))), ..request.clone() }).unwrap();
    assert!(r.aspect == AspectSource::KnownFocal && r.paper == Paper::A4 && a4(&r) && !r.focal_mismatch, "{:?} {:?}", r.aspect, r.paper);
    // the caller's, when it knows better than the file
    let r = process_page(&PageRequest { focal_35mm: Some(26.0), ..request.clone() }).unwrap();
    assert_eq!((r.aspect, r.paper), (AspectSource::KnownFocal, Paper::A4));
    // without snapping the measured shape stays, close to A4 but not forced onto it
    let r = process_page(&PageRequest { snap_paper: false, focal_35mm: Some(26.0), ..request }).unwrap();
    let ratio = f64::from(r.height) / f64::from(r.width);
    assert!(r.paper == Paper::Custom && error(ratio, std::f64::consts::SQRT_2) < 0.01, "{ratio}");
}

/// Treating a cropped photo as a whole one puts the assumed centre in the wrong place. This
/// records what that costs at about 30 degrees of tilt: a few percent, half of what the side
/// lengths are off by on the same photos, so a cropped photo is still measured from its corners.
#[test]
fn a_misplaced_centre_costs_a_few_percent() {
    let mut rng = Rng(14);
    let sqrt2 = std::f64::consts::SQRT_2;
    let (mut worst, mut worst_sides): (f64, f64) = (0.0, 0.0);
    for tilt in [(30.0, 20.0, 0.0), (20.0, -30.0, 40.0), (30.0, 30.0, 75.0)] {
        let corners = view(sqrt2, 0.75, tilt, 0.0, &mut rng);
        for shift in [-0.05, 0.05] {
            let quad = Quad::new(corners.map(|[x, y]| [x + shift * f64::from(PHOTO.0), y])).unwrap();
            worst = worst.max(error(proportions(&quad, PHOTO.0, PHOTO.1, None, false).ratio, sqrt2));
            let [top, right, bottom, left] = quad.side_lengths();
            worst_sides = worst_sides.max(error((top + bottom) / (left + right), sqrt2));
        }
    }
    println!("centre off by 5 % of the width: ratio off by at most {:.2} % (side lengths: {:.1} %)", 100.0 * worst, 100.0 * worst_sides);
    assert!(worst < 0.05 && worst < worst_sides / 2.0, "{worst} against {worst_sides}");
}

#[test]
fn a_resolution_is_stamped_into_a_jpeg_without_touching_its_pixels() {
    use crate::encode::stamp_jpeg_density;
    let photo = crate::tests::test_page(64, 48);
    let jfif = jpeg_of(&photo);
    assert_eq!(&jfif[2..4], [0xff, 0xe0], "the encoder writes a JFIF header");
    // a camera's file: EXIF and no JFIF header
    let camera_file = with_exif(&[&jfif[..2], &jfif[20..]].concat(), &exif_block(false, 26, (1, 1)));
    let density = |b: &[u8]| {
        (b[2..4] == [0xff, 0xe0] && b[6..11] == *b"JFIF\0").then(|| (b[13], u16::from_be_bytes([b[14], b[15]]), u16::from_be_bytes([b[16], b[17]])))
    };
    assert_eq!(density(&camera_file), None);
    for file in [&jfif, &camera_file] {
        let stamped = stamp_jpeg_density(file, 342).unwrap();
        assert_eq!(density(&stamped), Some((1, 342, 342)));
        assert_eq!(crate::Photo::decode(&stamped).unwrap(), crate::Photo::decode(file).unwrap(), "the pixels changed");
        // everything after the header is the same bytes
        assert!(stamped.ends_with(&file[20.min(file.len())..]) || stamped.ends_with(&file[2..]));
        // the lens data is still there
        assert_eq!(crate::Photo::decode_with_camera(&stamped).unwrap().1, crate::Photo::decode_with_camera(file).unwrap().1);
    }
    assert_eq!(stamp_jpeg_density(b"\x89PNG\r\n\x1a\n", 300), None);
    assert_eq!(stamp_jpeg_density(&[], 300), None);
    assert!(stamp_jpeg_density(&[0xff, 0xd8], 300).is_some());
}

/// A whole photo in Original is the camera's own file with a size: nothing is decoded and
/// encoded again.
#[test]
fn a_whole_photo_in_original_keeps_its_bytes() {
    use crate::page::WHOLE_PHOTO;
    use crate::{Format, Look, PageRequest, process_page};
    let photo = crate::tests::test_page(800, 600);
    let jpeg = jpeg_of(&photo);
    let r = process_page(&PageRequest { look: Look::Original, ..PageRequest::new(&jpeg, WHOLE_PHOTO) }).unwrap();
    // 800 pixels as long as A4: 68 dots per inch would be under the least a file may say
    assert_eq!((r.width, r.height, r.format, r.dpi, r.paper), (800, 600, Format::Jpeg, 72.0, Paper::Custom));
    assert_eq!(r.bytes.len(), jpeg.len());
    assert_eq!(&r.bytes[18..], &jpeg[18..]);
    assert_eq!(r.timings.len(), 4);
    // any other look, or a page inside the photo, goes through the pipeline
    let looked = process_page(&PageRequest { look: Look::Greyscale, ..PageRequest::new(&jpeg, WHOLE_PHOTO) }).unwrap();
    assert_ne!(&looked.bytes[18..], &jpeg[18..]);
    let inside = process_page(&PageRequest { look: Look::Original, ..PageRequest::new(&jpeg, [0.1, 0.1, 0.9, 0.1, 0.9, 0.9, 0.1, 0.9]) }).unwrap();
    assert!(inside.width < 800);
    // a 4000-pixel photo is 342 dots per inch
    assert_eq!(crate::aspect::dpi_without_paper(4000.0), 342);
}
