use image::ImageEncoder;
use pdfcraft_geom::perspective::Homography;

use crate::look::{Pixels, apply};
use crate::warp::{output_size, rectify_with_threads};
use crate::{Look, PageRequest, Photo, Quad, ScanError, process_page};

const WHITE: [u8; 3] = [255, 255, 255];
const BLUE: [u8; 3] = [20, 40, 220];

/// A test page: black bars, a 1 px checker patch, a red block and a blue square at each corner.
fn test_page(w: u32, h: u32) -> Photo {
    let mut rgb = Vec::with_capacity((w * h * 3) as usize);
    for y in 0..h {
        for x in 0..w {
            let corner = (x < 24 || x >= w - 24) && (y < 24 || y >= h - 24);
            let bar = (200..1500).contains(&y) && (y / 40) % 3 == 0 && (100..w - 100).contains(&x);
            let checker = (900..940).contains(&x) && (1550..1590).contains(&y);
            let red = (100..400).contains(&x) && (1550..1650).contains(&y);
            let p = if corner {
                BLUE
            } else if checker {
                if (x + y) % 2 == 0 { [0, 0, 0] } else { WHITE }
            } else if bar {
                [10, 10, 10]
            } else if red {
                [200, 30, 30]
            } else {
                WHITE
            };
            rgb.extend_from_slice(&p);
        }
    }
    Photo { width: w, height: h, rgb }
}

/// `page` photographed into a `w` × `h` photo at `quad` on a grey table (each photo pixel the
/// mean of 3 × 3 bilinear samples of the page, as a camera's pixels integrate light).
fn photograph(page: &Photo, w: u32, h: u32, quad: [[f64; 2]; 4]) -> Photo {
    let (pw, ph) = (f64::from(page.width), f64::from(page.height));
    let to_page = Homography::from_points(quad, [[0.0, 0.0], [pw, 0.0], [pw, ph], [0.0, ph]]).unwrap();
    let px = |x: i64, y: i64| {
        let i = ((y.clamp(0, i64::from(page.height) - 1) * i64::from(page.width) + x.clamp(0, i64::from(page.width) - 1)) * 3) as usize;
        [page.rgb[i] as f64, page.rgb[i + 1] as f64, page.rgb[i + 2] as f64]
    };
    let mut rgb = Vec::with_capacity((w * h * 3) as usize);
    for y in 0..h {
        for x in 0..w {
            let mut sum = [0.0; 3];
            for j in 0..3 {
                for i in 0..3 {
                    let p = [x as f64 + (i as f64 + 0.5) / 3.0, y as f64 + (j as f64 + 0.5) / 3.0];
                    let [sx, sy] = to_page.apply(p).unwrap();
                    let c = if sx < 0.0 || sy < 0.0 || sx >= pw || sy >= ph {
                        [90.0, 85.0, 80.0]
                    } else {
                        let (fx, fy) = (sx - 0.5, sy - 0.5);
                        let (x0, y0) = (fx.floor(), fy.floor());
                        let (ax, ay) = (fx - x0, fy - y0);
                        let (x0, y0) = (x0 as i64, y0 as i64);
                        let (a, b, c, d) = (px(x0, y0), px(x0 + 1, y0), px(x0, y0 + 1), px(x0 + 1, y0 + 1));
                        std::array::from_fn(|k| (a[k] * (1.0 - ax) + b[k] * ax) * (1.0 - ay) + (c[k] * (1.0 - ax) + d[k] * ax) * ay)
                    };
                    for k in 0..3 {
                        sum[k] += c[k];
                    }
                }
            }
            rgb.extend(sum.map(|v| (v / 9.0).round() as u8));
        }
    }
    Photo { width: w, height: h, rgb }
}

fn psnr(a: &[u8], b: &[u8]) -> f64 {
    assert_eq!(a.len(), b.len());
    let mse = a.iter().zip(b).map(|(&x, &y)| (f64::from(x) - f64::from(y)).powi(2)).sum::<f64>() / a.len() as f64;
    10.0 * (255.0f64 * 255.0 / mse.max(1e-12)).log10()
}

/// The centroid of the blue pixels inside the `size` × `size` square at (`x0`, `y0`).
fn blue_centroid(img: &crate::Rgb, x0: u32, y0: u32, size: u32) -> [f64; 2] {
    let (mut sx, mut sy, mut n) = (0.0, 0.0, 0.0);
    for y in y0..y0 + size {
        for x in x0..x0 + size {
            let i = ((y * img.width + x) * 3) as usize;
            let p = &img.data[i..i + 3];
            // How blue it is: 0 for white or grey, 1 for the marker.
            let k = ((f64::from(p[2]) - f64::from(p[0])) / 200.0).clamp(0.0, 1.0);
            sx += k * (x as f64 + 0.5);
            sy += k * (y as f64 + 0.5);
            n += k;
        }
    }
    [sx / n, sy / n]
}

#[test]
fn a_photographed_page_comes_back_sharp_and_in_place() {
    let page = test_page(1200, 1700);
    let quad = [[400.0, 300.0], [2650.0, 450.0], [2800.0, 3700.0], [250.0, 3550.0]];
    let photo = photograph(&page, 3000, 4000, quad);
    let out = rectify_with_threads(&photo, &Quad::new(quad).unwrap(), 1200, 1700, 4).unwrap();
    let db = psnr(&out.data, &page.rgb);
    assert!(db >= 30.0, "PSNR {db:.2} dB");
    // Each corner's blue square is where it was on the page.
    for (x0, y0) in [(0, 0), (1200 - 60, 0), (1200 - 60, 1700 - 60), (0, 1700 - 60)] {
        let want = blue_centroid(&crate::Rgb { width: 1200, height: 1700, data: page.rgb.clone() }, x0, y0, 60);
        let got = blue_centroid(&out, x0, y0, 60);
        assert!((got[0] - want[0]).abs() <= 0.5 && (got[1] - want[1]).abs() <= 0.5, "corner at ({x0}, {y0}): {got:?} vs {want:?}");
    }
}

/// A `w` × `w` photo of a 1 px black-and-white checker.
fn checker(w: u32) -> Photo {
    let rgb = (0..w * w).flat_map(|i| if (i % w + i / w) % 2 == 0 { [0u8; 3] } else { [255u8; 3] }).collect();
    Photo { width: w, height: w, rgb }
}

fn std_dev(v: &[u8]) -> f64 {
    let mean = v.iter().map(|&x| f64::from(x)).sum::<f64>() / v.len() as f64;
    (v.iter().map(|&x| (f64::from(x) - mean).powi(2)).sum::<f64>() / v.len() as f64).sqrt()
}

/// One bilinear sample per output pixel at its centre: the sampling of the engine's
/// `camera::warp`, which can only be made to shrink a picture from here.
fn bilinear_only(photo: &Photo, out: u32) -> Vec<u8> {
    let k = f64::from(photo.width) / f64::from(out);
    let w = i64::from(photo.width);
    let px = |x: i64, y: i64| f64::from(photo.rgb[((y.clamp(0, w - 1) * w + x.clamp(0, w - 1)) * 3) as usize]);
    let mut v = Vec::new();
    for y in 0..out {
        for x in 0..out {
            let (fx, fy) = ((x as f64 + 0.5) * k - 0.5, (y as f64 + 0.5) * k - 0.5);
            let (x0, y0) = (fx.floor(), fy.floor());
            let (ax, ay) = (fx - x0, fy - y0);
            let (x0, y0) = (x0 as i64, y0 as i64);
            let top = px(x0, y0) * (1.0 - ax) + px(x0 + 1, y0) * ax;
            let bottom = px(x0, y0 + 1) * (1.0 - ax) + px(x0 + 1, y0 + 1) * ax;
            v.push((top * (1.0 - ay) + bottom * ay).round() as u8);
        }
    }
    v
}

#[test]
fn fine_print_shrunk_threefold_is_grey_not_moire() {
    let photo = checker(900);
    let quad = Quad::new([[0.0, 0.0], [900.0, 0.0], [900.0, 900.0], [0.0, 900.0]]).unwrap();
    let out = rectify_with_threads(&photo, &quad, 300, 300, 2).unwrap();
    let grey: Vec<u8> = out.data.iter().step_by(3).copied().collect();
    let sd = std_dev(&grey);
    assert!(sd < 6.0, "standard deviation {sd:.2}");
    // The control: one bilinear sample per pixel keeps the checker (aliasing).
    let control = std_dev(&bilinear_only(&photo, 300));
    assert!(control >= 6.0, "control {control:.2}");
}

/// A JPEG of `img` tagged with EXIF `orientation`.
fn jpeg_with_orientation(img: &image::RgbImage, orientation: u8) -> Vec<u8> {
    let mut jpeg = Vec::new();
    image::codecs::jpeg::JpegEncoder::new_with_quality(&mut jpeg, 100).encode_image(img).unwrap();
    let mut exif = b"Exif\0\0II\x2a\0\x08\0\0\0\x01\0\x12\x01\x03\0\x01\0\0\0".to_vec();
    exif.extend_from_slice(&[orientation, 0, 0, 0, 0, 0, 0, 0]);
    let mut out = jpeg[..2].to_vec();
    out.extend_from_slice(&[0xff, 0xe1]);
    out.extend_from_slice(&((exif.len() + 2) as u16).to_be_bytes());
    out.extend_from_slice(&exif);
    out.extend_from_slice(&jpeg[2..]);
    out
}

#[test]
fn every_exif_orientation_decodes_upright() {
    // Smooth, and different along each axis, so a wrong turn shows.
    let upright = image::RgbImage::from_fn(96, 64, |x, y| image::Rgb([(x * 2) as u8, (y * 3) as u8, 128]));
    let stored = |o: u8| match o {
        3 => image::imageops::rotate180(&upright),
        6 => image::imageops::rotate270(&upright),
        8 => image::imageops::rotate90(&upright),
        _ => upright.clone(),
    };
    let reference = Photo::decode(&jpeg_with_orientation(&upright, 1)).unwrap();
    assert_eq!((reference.width, reference.height), (96, 64));
    for o in [3, 6, 8] {
        let p = Photo::decode(&jpeg_with_orientation(&stored(o), o)).unwrap();
        assert_eq!((p.width, p.height), (96, 64), "orientation {o}");
        let worst = p.rgb.iter().zip(&reference.rgb).map(|(&a, &b)| a.abs_diff(b)).max().unwrap();
        assert!(worst <= 6, "orientation {o}: off by {worst}");
    }
}

/// The start of a PNG that says it is `w` × `h` (no pixel data).
fn png_header(w: u32, h: u32) -> Vec<u8> {
    let mut ihdr = b"IHDR".to_vec();
    ihdr.extend_from_slice(&w.to_be_bytes());
    ihdr.extend_from_slice(&h.to_be_bytes());
    ihdr.extend_from_slice(&[8, 2, 0, 0, 0]);
    let crc = crc32(&ihdr);
    let mut v = b"\x89PNG\r\n\x1a\n".to_vec();
    v.extend_from_slice(&13u32.to_be_bytes());
    v.extend_from_slice(&ihdr);
    v.extend_from_slice(&crc.to_be_bytes());
    // A few compressed bytes, as a file cut short would have, then the end.
    for (kind, data) in [(&b"IDAT"[..], &b"\x78\x9c\x63\x00\x00"[..]), (b"IEND", b"")] {
        let mut chunk = kind.to_vec();
        chunk.extend_from_slice(data);
        v.extend_from_slice(&(data.len() as u32).to_be_bytes());
        v.extend_from_slice(&chunk);
        v.extend_from_slice(&crc32(&chunk).to_be_bytes());
    }
    v
}

fn crc32(data: &[u8]) -> u32 {
    let mut c = 0xffff_ffffu32;
    for &b in data {
        c ^= u32::from(b);
        for _ in 0..8 {
            c = if c & 1 != 0 { 0xedb8_8320 ^ (c >> 1) } else { c >> 1 };
        }
    }
    !c
}

#[test]
fn limits_are_errors_not_panics_or_huge_allocations() {
    // 65 MP is refused from the header alone.
    assert_eq!(Photo::decode(&png_header(8125, 8000)), Err(ScanError::TooLarge(65)));
    assert_eq!(Photo::decode(b"not a picture"), Err(ScanError::Unreadable));
    assert_eq!(Photo::decode(&[]), Err(ScanError::Unreadable));
    // Corners in a line, or caving in, are refused.
    let line = [0.1, 0.1, 0.5, 0.5, 0.9, 0.9, 0.3, 0.3];
    assert_eq!(Quad::from_shares(&line, 100, 100), Err(ScanError::BadCorners));
    let dart = [0.0, 0.0, 1.0, 0.0, 0.3, 0.3, 0.0, 1.0];
    assert_eq!(Quad::from_shares(&dart, 100, 100), Err(ScanError::BadCorners));
    assert_eq!(Quad::from_shares(&[f32::NAN, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0], 100, 100), Err(ScanError::BadCorners));
    // The output never exceeds 40 MP, even for a whole 64 MP photo.
    let whole = Quad::from_shares(&[0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0], 8000, 8000).unwrap();
    let (w, h) = output_size(&whole).unwrap();
    assert!(f64::from(w) * f64::from(h) <= 40_000_000.0, "{w} × {h}");
    let photo = checker(10);
    assert!(rectify_with_threads(&photo, &whole, 8000, 8000, 1).is_err(), "64 MP output is refused");
    let bad = Photo { width: 11, height: 10, rgb: photo.rgb.clone() };
    assert!(rectify_with_threads(&bad, &whole, 10, 10, 1).is_err(), "size doesn't match the buffer");
}

/// A JPEG photo of a tilted page with text-like bars on a dark table.
fn sample_photo() -> Vec<u8> {
    let page = test_page(1200, 1700);
    let small = image::imageops::thumbnail(&image::RgbImage::from_raw(1200, 1700, page.rgb).unwrap(), 300, 425);
    let page = Photo { width: 300, height: 425, rgb: small.into_raw() };
    let photo = photograph(&page, 600, 800, [[80.0, 60.0], [530.0, 90.0], [560.0, 740.0], [50.0, 710.0]]);
    let mut jpeg = Vec::new();
    image::codecs::jpeg::JpegEncoder::new_with_quality(&mut jpeg, 90)
        .write_image(&photo.rgb, photo.width, photo.height, image::ExtendedColorType::Rgb8)
        .unwrap();
    jpeg
}

const SAMPLE_CORNERS: [f32; 8] = [80.0 / 600.0, 60.0 / 800.0, 530.0 / 600.0, 90.0 / 800.0, 560.0 / 600.0, 740.0 / 800.0, 50.0 / 600.0, 710.0 / 800.0];

#[test]
fn the_same_photo_gives_the_same_bytes_on_any_number_of_threads() {
    let photo = sample_photo();
    for look in [Look::Original, Look::Colour, Look::Greyscale, Look::BlackWhite, Look::Auto] {
        let run = |threads| process_page(&PageRequest { photo: &photo, corners: SAMPLE_CORNERS, look, strength: 60, threads }).unwrap();
        let (a, b, c) = (run(1), run(1), run(4));
        assert_eq!(a.bytes, b.bytes, "{look:?}: twice");
        assert_eq!(a.bytes, c.bytes, "{look:?}: 1 and 4 threads");
        let stages: Vec<&str> = a.timings.iter().map(|t| t.0).collect();
        assert_eq!(stages, ["scan_decode", "scan_rectify", "scan_look", "scan_encode"]);
        let want = if look == Look::BlackWhite { crate::Format::Png } else { crate::Format::Jpeg };
        assert_eq!(a.format, want);
        assert!((72.0..=600.0).contains(&a.dpi));
    }
}

#[test]
fn each_look_is_the_engines_camera_clean_up() {
    let photo = Photo::decode(&sample_photo()).unwrap();
    let quad = Quad::from_shares(&SAMPLE_CORNERS, photo.width, photo.height).unwrap();
    let (w, h) = output_size(&quad).unwrap();
    let page = rectify_with_threads(&photo, &quad, w, h, 2).unwrap();
    use pdfcraft_engine::camera_clean as cc;
    for (look, engine) in [(Look::Original, cc::Look::Original), (Look::Colour, cc::Look::Colour), (Look::Greyscale, cc::Look::Greyscale), (Look::BlackWhite, cc::Look::BlackWhite)] {
        for strength in [0, 50, 100] {
            let ours = apply(&page, look, strength).unwrap();
            let theirs = match cc::apply(&page.data, w, h, engine, strength).unwrap() {
                cc::Pixels::Rgb(v) => Pixels::Rgb(v),
                cc::Pixels::Grey(v) => Pixels::Grey(v),
            };
            assert_eq!(ours, theirs, "{look:?} at {strength}");
        }
    }
}

#[test]
fn corners_round_trip_through_shares() {
    let shares = [0.1, 0.05, 0.9, 0.1, 0.95, 0.9, 0.05, 0.95];
    let q = Quad::from_shares(&shares, 400, 300).unwrap();
    assert!(q.is_convex());
    assert!(q.area() > 0.0);
    let back = q.to_shares(400, 300);
    for (a, b) in back.iter().zip(shares) {
        assert!((a - b).abs() < 1e-6);
    }
    // Any order in, reading order out.
    let shuffled = [shares[4], shares[5], shares[0], shares[1], shares[6], shares[7], shares[2], shares[3]];
    assert_eq!(Quad::from_shares(&shuffled, 400, 300).unwrap(), q);
}
