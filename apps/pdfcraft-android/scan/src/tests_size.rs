//! The JPEG encoder made for each page and the size choices (#505).

use crate::encode::{JPEG_QUALITY, Raster, encode_jpeg};
use crate::jpeg::{Chroma, Samples, encode};
use crate::look::{Look, Pixels};
use crate::size::{Encoding, Made, SizeChoice, coloured_share, resize_area, scaled_size, store};

/// A page of grey "print": rows of dark bars of varying length on near-white paper, with a little
/// grain, and a red mark when `red`.
fn page(w: u32, h: u32, red: bool) -> Vec<u8> {
    let mut state = 0x2545_f491_4f6c_dd1du64;
    let mut noise = || {
        state ^= state << 13;
        state ^= state >> 7;
        state ^= state << 17;
        (state % 7) as i32 - 3
    };
    let mut rgb = Vec::with_capacity((w * h * 3) as usize);
    for y in 0..h {
        for x in 0..w {
            let line = y % 24;
            let word = (x / 37 + y / 24) % 5 != 0;
            let ink = (6..14).contains(&line) && word && x % 37 < 31 && x > 20 && x < w - 20;
            let v = if ink { 30 } else { 246 };
            let n = noise();
            let mark = red && (40..80).contains(&x) && (40..80).contains(&y);
            let px = if mark { [200, 30, 30] } else { [v, v, v] };
            rgb.extend(px.map(|c: i32| (c + n).clamp(0, 255) as u8));
        }
    }
    rgb
}

fn decode(bytes: &[u8]) -> image::DynamicImage {
    image::load_from_memory_with_format(bytes, image::ImageFormat::Jpeg).expect("a JPEG every reader opens")
}

fn psnr(a: &[u8], b: &[u8]) -> f64 {
    let mse = a.iter().zip(b).map(|(&x, &y)| (f64::from(x) - f64::from(y)).powi(2)).sum::<f64>() / a.len() as f64;
    10.0 * (255.0f64.powi(2) / mse.max(1e-9)).log10()
}

#[test]
fn the_encoder_writes_a_jpeg_any_decoder_reads_back_close_to_the_page() {
    // sizes that are not whole blocks or whole 2 × 2 macroblocks
    for (w, h) in [(203, 151), (64, 64), (17, 9), (1, 1)] {
        let rgb = page(w, h, true);
        for chroma in [Chroma::Full, Chroma::Half] {
            let jpeg = encode(Samples::Rgb(&rgb, chroma), w, h, 92, 300).unwrap();
            let back = decode(&jpeg).to_rgb8();
            assert_eq!((back.width(), back.height()), (w, h));
            if w > 8 {
                let db = psnr(&rgb, back.as_raw());
                assert!(db > 30.0, "{w}×{h} {chroma:?}: {db:.1} dB");
            }
        }
        let grey: Vec<u8> = rgb.as_chunks::<3>().0.iter().map(|c| c[1]).collect();
        let jpeg = encode(Samples::Grey(&grey), w, h, 92, 300).unwrap();
        let back = decode(&jpeg).to_luma8();
        assert_eq!((back.width(), back.height()), (w, h));
        if w > 8 {
            assert!(psnr(&grey, back.as_raw()) > 34.0);
        }
    }
}

#[test]
fn page_tables_store_the_same_page_in_fewer_bytes_than_the_standard_ones() {
    let (w, h) = (640, 480);
    let rgb = page(w, h, true);
    let before = encode_jpeg(&Raster { pixels: &rgb, grey: false, width: w, height: h, dpi: 300 }, JPEG_QUALITY).unwrap();
    let now = encode(Samples::Rgb(&rgb, Chroma::Full), w, h, JPEG_QUALITY, 300).unwrap();
    // the same quantisation (within the float and integer DCTs' rounding), tables made for the page
    assert!(now.len() < before.len(), "{} ≥ {}", now.len(), before.len());
    let (a, b) = (decode(&before).to_rgb8(), decode(&now).to_rgb8());
    let (da, db) = (psnr(&rgb, a.as_raw()), psnr(&rgb, b.as_raw()));
    assert!((da - db).abs() < 0.5, "before {da:.2} dB, now {db:.2} dB");
}

#[test]
fn the_resolution_is_in_the_jfif_header() {
    let jpeg = encode(Samples::Grey(&[128; 64]), 8, 8, 80, 217).unwrap();
    assert_eq!(&jpeg[6..11], b"JFIF\0");
    assert_eq!(&jpeg[13..18], &[1, 0, 217, 0, 217]);
}

#[test]
fn a_colourless_page_is_found_colourless_and_a_red_mark_is_found() {
    assert!(coloured_share(&page(200, 200, false)) < crate::size::COLOUR_SHARE);
    assert!(coloured_share(&page(200, 200, true)) > crate::size::COLOUR_SHARE);
}

#[test]
fn area_resize_keeps_the_mean_and_the_size_is_never_enlarged() {
    let grey: Vec<u8> = (0..100 * 60).map(|i| (i % 251) as u8).collect();
    let out = resize_area(&grey, 100, 60, 1, 37, 22);
    assert_eq!(out.len(), 37 * 22);
    let mean = |v: &[u8]| v.iter().map(|&x| f64::from(x)).sum::<f64>() / v.len() as f64;
    assert!((mean(&grey) - mean(&out)).abs() < 2.0);
    assert_eq!(scaled_size(100, 60, 200, 300), (100, 60));
    assert_eq!(scaled_size(3000, 2000, 300, 150), (1500, 1000));
}

#[test]
fn with_the_automatic_encoding_off_every_choice_stores_the_page_as_before() {
    let (w, h) = (320, 240);
    let rgb = page(w, h, false);
    let before = encode_jpeg(&Raster { pixels: &rgb, grey: false, width: w, height: h, dpi: 300 }, JPEG_QUALITY).unwrap();
    for choice in SizeChoice::ALL {
        let s =
            store(&Made { pixels: &Pixels::Rgb(rgb.clone()), width: w, height: h, dpi: 300, look: Look::Auto, kind: None }, choice, false).unwrap();
        assert_eq!(s.bytes, before, "{choice:?}");
        assert_eq!(s.encoded.why, "as_before");
        assert_eq!(s.encoded.encoding, Encoding::JpegRgb);
    }
}

#[test]
fn automatic_encoding_stores_a_colourless_page_grey_and_never_larger_than_before() {
    let (w, h) = (900, 1200);
    for red in [false, true] {
        let rgb = page(w, h, red);
        let before = encode_jpeg(&Raster { pixels: &rgb, grey: false, width: w, height: h, dpi: 300 }, JPEG_QUALITY).unwrap().len() as u64;
        let mut last = u64::MAX;
        for choice in SizeChoice::ALL {
            let s = store(&Made { pixels: &Pixels::Rgb(rgb.clone()), width: w, height: h, dpi: 300, look: Look::Auto, kind: None }, choice, true)
                .unwrap();
            let e = &s.encoded;
            assert_eq!(e.encoding, if red { Encoding::JpegRgb } else { Encoding::JpegGrey }, "{choice:?} {}", e.why);
            assert!(e.bytes <= before, "{choice:?}: {} > {before}", e.bytes);
            assert!(e.bytes <= last, "{choice:?} is larger than the choice above it");
            assert_eq!(e.bytes, s.bytes.len() as u64);
            last = e.bytes;
            let back = decode(&s.bytes);
            assert_eq!((back.width(), back.height()), (e.width, e.height));
        }
    }
}

#[test]
fn best_keeps_the_size_and_quality_and_the_others_cap_the_resolution() {
    let (w, h) = (1240, 1754);
    let rgb = page(w, h, false);
    let best =
        store(&Made { pixels: &Pixels::Rgb(rgb.clone()), width: w, height: h, dpi: 600, look: Look::Auto, kind: None }, SizeChoice::Best, true)
            .unwrap()
            .encoded;
    assert_eq!((best.width, best.height, best.dpi, best.quality), (w, h, 600, JPEG_QUALITY));
    let small = store(&Made { pixels: &Pixels::Rgb(rgb), width: w, height: h, dpi: 600, look: Look::Auto, kind: None }, SizeChoice::Small, true)
        .unwrap()
        .encoded;
    assert!(small.dpi < 600 && small.width < w, "{small:?}");
}
