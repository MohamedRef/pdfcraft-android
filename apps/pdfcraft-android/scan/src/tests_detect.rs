//! The document detector (#290): its parts on values worked out by hand, and the network itself
//! on drawn photos (the model file is in `assets/models`).

use pdfcraft_geom::perspective::Point;

use crate::detect::{Placement, QuadSource, SIDE, area_weights, detect, read, shrink, square, with_lines};
use crate::models::{EDGES, MODELS, ModelState, load, release, state};
use crate::{Photo, Quad};

/// A photo of a sheet with rows of print at `quad` on a dark, grainy table.
fn photo(w: u32, h: u32, quad: [Point; 4]) -> Photo {
    let mut page = crate::tests::test_page(600, 800);
    // rows of print between the test page's own marks
    for y in (120..760).step_by(22) {
        for x in 60..540 {
            if (x / 13 + y / 22) % 4 != 0 && y % 22 < 7 {
                let i = ((y * 600 + x) * 3) as usize;
                page.rgb[i..i + 3].copy_from_slice(&[40, 40, 45]);
            }
        }
    }
    let mut shot = crate::tests::photograph(&page, w, h, quad);
    // the table: darker, with grain (the photograph helper paints a flat grey)
    let mut seed = 99u64;
    for (i, px) in shot.rgb.chunks_exact_mut(3).enumerate() {
        if px == [90, 85, 80] {
            seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
            let grain = ((seed >> 56) as i32 - 128) / 10 + (((i as u32 % w) / 37 + (i as u32 / w) / 29) % 2) as i32 * 6;
            px.copy_from_slice(&[(70 + grain).clamp(0, 255) as u8, (55 + grain).clamp(0, 255) as u8, (45 + grain).clamp(0, 255) as u8]);
        }
    }
    shot
}

/// The largest distance between matching corners, as a share of the photo's diagonal.
fn off(found: &Quad, truth: [Point; 4], w: u32, h: u32) -> f64 {
    let truth = Quad::new(truth).unwrap();
    (0..4).map(|i| (found.0[i][0] - truth.0[i][0]).hypot(found.0[i][1] - truth.0[i][1])).fold(0.0, f64::max) / f64::from(w).hypot(f64::from(h))
}

#[test]
fn shrinking_averages_exactly_what_each_pixel_covers() {
    for (n_in, n_out) in [(10usize, 4usize), (7, 7), (1000, 256), (3, 1)] {
        let weights = area_weights(n_in, n_out);
        assert_eq!(weights.len(), n_out);
        for (first, w) in &weights {
            assert!((w.iter().sum::<f32>() - 1.0).abs() < 1e-5 && first + w.len() <= n_in, "{n_in} to {n_out}");
        }
        // every source value is used once in all
        let mut used = vec![0f32; n_in];
        for (first, w) in &weights {
            for (i, v) in w.iter().enumerate() {
                used[first + i] += v * n_in as f32 / n_out as f32;
            }
        }
        assert!(used.iter().all(|u| (u - 1.0).abs() < 1e-4), "{n_in} to {n_out}: {used:?}");
    }
    // a 4 x 2 photo of two colours, halved: each new pixel the mean of its 2 x 2 block
    let rgb: Vec<u8> = [[0u8; 3], [100; 3], [200; 3], [200; 3], [0; 3], [100; 3], [200; 3], [200; 3]].concat();
    let (small, scale) = shrink(&Photo { width: 4, height: 2, rgb }, 2);
    assert_eq!((small.w, small.h, scale), (2, 1, 0.5));
    assert!((small.rgb[0] - 50.0 / 255.0).abs() < 1e-6 && (small.rgb[3] - 200.0 / 255.0).abs() < 1e-6);
    // a photo that fits is not enlarged
    assert_eq!(shrink(&Photo { width: 100, height: 60, rgb: vec![7; 18_000] }, 256).1, 1.0);
}

#[test]
fn the_photo_sits_centred_in_the_square_and_points_map_back() {
    let (small, _) =
        shrink(&Photo { width: 512, height: 256, rgb: (0..512 * 256).flat_map(|i| [(i % 512 / 2) as u8, (i / 512) as u8, 9]).collect() }, SIDE);
    assert_eq!((small.w, small.h), (256, 128));
    // unturned: black bands above and below, the picture itself in between, untouched
    let place = Placement::new(&small, 0);
    let planes = square(&small, &place);
    let at = |c: usize, u: usize, v: usize| planes[c * SIDE * SIDE + v * SIDE + u];
    assert_eq!((at(0, 100, 10), at(0, 100, 250)), (0.0, 0.0));
    assert!((at(0, 100, 64 + 20) - small.rgb[(20 * 256 + 100) * 3]).abs() < 1e-6);
    assert!((at(1, 30, 64 + 100) - small.rgb[(100 * 256 + 30) * 3 + 1]).abs() < 1e-6);
    assert_eq!(place.in_small([128.0, 128.0]), [128.0, 64.0]);
    // a quarter turn clockwise: the picture's top-left corner goes to the square's top right
    let turned = Placement::new(&small, 90);
    let [x, y] = turned.in_small([128.0 + 64.0, 0.0]);
    assert!(x.abs() < 1e-9 && y.abs() < 1e-9, "({x}, {y})");
    // at 30 degrees the picture is shrunk to fit and every corner is still inside the square
    let tilted = Placement::new(&small, 30);
    // (its corner maps further from the centre of the square than it would unshrunk)
    let corner = tilted.in_small([0.0, 0.0]);
    assert!((corner[0] - 128.0).hypot(corner[1] - 64.0) > 128.0 * 2f64.sqrt() + 1.0);
    let planes = square(&small, &tilted);
    assert!(planes.iter().all(|v| v.is_finite()) && planes.iter().any(|v| *v > 0.5));
}

#[test]
fn corners_are_read_off_the_maps_and_checked_against_the_page_map() {
    let cells = 64usize;
    // four sharp peaks: a quad from cell (10, 12) to (50, 40)
    let peaks = [(10usize, 12usize), (50, 12), (50, 40), (10, 40)];
    let mut heat = vec![-12.0f32; 4 * cells * cells];
    for (k, &(x, y)) in peaks.iter().enumerate() {
        heat[k * cells * cells + y * cells + x] = 6.0;
    }
    // the page map says the same rectangle
    let page: Vec<f32> =
        (0..cells * cells).map(|i| if (10..50).contains(&(i % cells)) && (12..40).contains(&(i / cells)) { 5.0 } else { -5.0 }).collect();
    let r = read(&heat, &page);
    for (found, &(x, y)) in r.corners.iter().zip(&peaks) {
        assert!((found[0] - (x as f64 + 0.5) * 4.0).abs() < 0.01 && (found[1] - (y as f64 + 0.5) * 4.0).abs() < 0.01, "{found:?}");
    }
    assert!(r.surest_less > 0.99 && r.agreement > 0.9, "{} {}", r.surest_less, r.agreement);
    // a peak between two cells is placed between them
    heat[12 * cells + 11] = 6.0;
    assert!((read(&heat, &page).corners[0][0] - 44.0).abs() < 0.1);
    // one unsure corner makes the whole answer unsure; a page map elsewhere makes it disagree
    heat[3 * cells * cells + 40 * cells + 10] = -1.0;
    assert!(read(&heat, &page).surest_less < 0.3);
    let elsewhere: Vec<f32> = (0..cells * cells).map(|i| if i % cells > 55 { 5.0 } else { -5.0 }).collect();
    assert!(read(&heat, &elsewhere).agreement < 0.05);
    // corners that cross make no quad
    let mut crossed = vec![-12.0f32; 4 * cells * cells];
    for (k, &(x, y)) in [(10usize, 12usize), (50, 40), (50, 12), (10, 40)].iter().enumerate() {
        crossed[k * cells * cells + y * cells + x] = 6.0;
    }
    assert_eq!(read(&crossed, &page).agreement, 0.0);
}

#[test]
fn the_pinned_model_is_there_and_loads_once() {
    assert_eq!(state(EDGES), ModelState::Ready, "assets/models/{} is missing or is not the pinned file", MODELS[0].file);
    assert_eq!(state("no-such-model"), ModelState::Missing);
    assert!(load("no-such-model").is_none());
    let (a, b) = (load(EDGES).unwrap(), load(EDGES).unwrap());
    assert!(std::sync::Arc::ptr_eq(&a, &b));
    // every model file is there, is the pinned one, and is what assets/models/ATTRIBUTION.txt
    // says it is
    let notice = include_str!("../../../../assets/models/ATTRIBUTION.txt");
    for model in MODELS {
        assert_eq!(state(model.id), ModelState::Ready, "assets/models/{} is missing or is not the pinned file", model.file);
        assert!(
            notice.contains(model.file) && notice.contains(model.sha256) && notice.contains(model.licence),
            "{} is not in ATTRIBUTION.txt",
            model.file
        );
    }
}

/// A sheet on a table, seen upright, tilted and on its side: the network finds it to a few
/// percent of the photo's diagonal (the refinement, not tested here, makes it exact).
#[test]
fn the_network_finds_a_sheet_on_a_table() {
    let (w, h) = (1200u32, 1600u32);
    let upright = [[230.0, 260.0], [960.0, 300.0], [1010.0, 1330.0], [190.0, 1290.0]];
    let found = detect(&photo(w, h, upright));
    println!("upright: {:?} confidence {:.2}, turned {}, {} passes", found.source, found.confidence, found.angle, found.passes);
    assert_eq!((found.source, found.angle, found.passes), (QuadSource::Model, 0, 1));
    assert!(found.confidence >= 0.5 && off(&found.quad.unwrap(), upright, w, h) < 0.03, "{found:?}");

    // the same sheet turned by about 35 degrees in the photo
    let (sin, cos) = 35f64.to_radians().sin_cos();
    let tilted = upright
        .map(|[x, y]| [600.0 + (x - 600.0) * cos - (y - 800.0) * sin, 800.0 + (x - 600.0) * sin + (y - 800.0) * cos].map(|v: f64| v * 0.85 + 90.0));
    let found = detect(&photo(w, h, tilted));
    println!("tilted: {:?} confidence {:.2}, turned {}, {} passes", found.source, found.confidence, found.angle, found.passes);
    assert_eq!(found.source, QuadSource::Model);
    assert!(off(&found.quad.unwrap(), tilted, w, h) < 0.04, "{found:?}");

    // a photo with no sheet in it: nothing, and said so by the network, not by the line detector
    let table = photo(w, h, [[-900.0, -900.0], [-800.0, -900.0], [-800.0, -800.0], [-900.0, -800.0]]);
    let found = detect(&table);
    println!("no sheet: {:?} confidence {:.2}, {} passes", found.source, found.confidence, found.passes);
    assert!(found.quad.is_none() && found.source == QuadSource::None && found.passes >= 1, "{found:?}");

    // the same answer every time
    assert_eq!(detect(&table), found);
    release();
    assert_eq!(detect(&table), found);
}

#[test]
fn without_the_network_the_line_detector_is_asked() {
    let (w, h) = (1200u32, 1600u32);
    let truth = [[230.0, 260.0], [960.0, 300.0], [1010.0, 1330.0], [190.0, 1290.0]];
    let found = with_lines(&photo(w, h, truth));
    assert_eq!((found.source, found.confidence, found.passes), (QuadSource::Lines, 0.5, 0));
    assert!(off(&found.quad.unwrap(), truth, w, h) < 0.02, "{found:?}");
    // too small to hold a page
    assert!(detect(&Photo { width: 8, height: 8, rgb: vec![0; 192] }).quad.is_none());
}
