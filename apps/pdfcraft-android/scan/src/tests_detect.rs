//! The document detector (#290): its parts on values worked out by hand, and the network itself
//! on drawn photos (the model file is in `assets/models`).

use pdfcraft_geom::perspective::Point;

use crate::detect::{Finder, Placement, QuadSource, SIDE, detect, detect_with, read, shrink, square, with_lines};
use crate::models::{EDGES, MODELS, ModelState, load, release, state};
use crate::tensor::area_weights;
use crate::{Photo, Quad};

/// A photo of a sheet with rows of print at `quad` on a dark, grainy table.
pub(crate) fn photo(w: u32, h: u32, quad: [Point; 4]) -> Photo {
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

/// The testing choice of detector (#288): each asks only what it names.
#[test]
fn a_named_detector_is_the_only_one_asked() {
    let (w, h) = (1200u32, 1600u32);
    let truth = [[230.0, 260.0], [960.0, 300.0], [1010.0, 1330.0], [190.0, 1290.0]];
    let sheet = photo(w, h, truth);
    assert_eq!(detect_with(&sheet, Finder::Auto), detect(&sheet));
    assert_eq!(detect_with(&sheet, Finder::Network).source, QuadSource::Model);
    let lines = detect_with(&sheet, Finder::Lines);
    assert_eq!(lines, with_lines(&sheet));
    assert_eq!(lines.source, QuadSource::Lines);
    let off = detect_with(&sheet, Finder::Off);
    assert!(off.quad.is_none() && off.source == QuadSource::None && off.passes == 0, "{off:?}");
    assert_eq!(Finder::default(), Finder::Auto);
}

/// The second look (#290): the line detector is asked only when the network was not convinced,
/// and its page taken only with enough of its sides on edges.
#[test]
fn the_line_detector_is_asked_only_when_the_network_is_not_convinced() {
    use crate::detect::{Detection, second_look_takes, second_look_wanted};
    let quad = Quad::new([[0.0, 0.0], [100.0, 0.0], [100.0, 100.0], [0.0, 100.0]]).unwrap();
    let network = |confidence: f32, found: bool| Detection {
        quad: found.then_some(quad),
        confidence,
        source: if found { QuadSource::Model } else { QuadSource::None },
        angle: 0,
        passes: 7,
        pick: if found { crate::detect::CornerPick::First } else { crate::detect::CornerPick::None },
        refused: 0,
        skew: 0.0,
        order: crate::detect::LooksBy::Fixed,
    };
    // nothing found (the owner's photos of 2026-11-19 and -20: 0.22 to 0.39)
    assert!(second_look_wanted(&network(0.39, false), 0));
    // unsure, with few sides on edges
    assert!(second_look_wanted(&network(0.63, true), 0));
    assert!(second_look_wanted(&network(0.56, true), 2));
    // unsure but three sides on edges: kept as it is
    assert!(!second_look_wanted(&network(0.58, true), 3));
    // sure: never
    assert!(!second_look_wanted(&network(0.88, true), 0));
    // no model file: the line detector already answered
    let lines = Detection { quad: Some(quad), source: QuadSource::Lines, passes: 0, ..network(0.5, false) };
    assert!(!second_look_wanted(&lines, 0));
    // over nothing, three sides on edges are enough; over a page, all four
    assert!(second_look_takes(false, 3) && second_look_takes(false, 4) && !second_look_takes(false, 2));
    assert!(second_look_takes(true, 4) && !second_look_takes(true, 3));
}

/// Corner maps with a peak of `logit` at each `(x, y, logit)` cell, map by map.
fn heat_of(peaks: [&[(usize, usize, f32)]; 4]) -> Vec<f32> {
    let cells = 64usize;
    let mut heat = vec![-12.0f32; 4 * cells * cells];
    for (k, map) in peaks.iter().enumerate() {
        for &(x, y, logit) in *map {
            heat[k * cells * cells + y * cells + x] = logit;
        }
    }
    heat
}

/// A page map that says paper in each of the `rects` (`x0, y0, x1, y1` in cells, the ends not in).
fn page_of(rects: &[[usize; 4]]) -> Vec<f32> {
    (0..64 * 64)
        .map(|i| if rects.iter().any(|r| (r[0]..r[2]).contains(&(i % 64)) && (r[1]..r[3]).contains(&(i / 64))) { 5.0 } else { -5.0 })
        .collect()
}

/// The square taken for the photo itself: how [`read_sheet`] is told a quad looks.
fn judge(corners: &[Point; 4]) -> crate::detect::Judged {
    Quad::new(*corners).ok().map(|quad| crate::detect::sheet_of(&quad, SIDE as u32, SIDE as u32))
}

/// The centre of a cell, in pixels of the square.
fn cell(x: usize, y: usize) -> Point {
    [(x as f64 + 0.5) * 4.0, (y as f64 + 0.5) * 4.0]
}

fn near(found: &[Point; 4], cells: [(usize, usize); 4]) -> bool {
    found.iter().zip(cells).all(|(p, (x, y))| (p[0] - cell(x, y)[0]).abs() < 0.01 && (p[1] - cell(x, y)[1]).abs() < 0.01)
}

/// A rectangular sheet seen by a camera, however obliquely, fits; four corners of two sheets do
/// not, though they make a convex quad.
#[test]
fn a_sheet_seen_at_a_slant_fits_and_corners_of_two_sheets_do_not() {
    use crate::aspect::sheet_fit;
    let (w, h) = (3000u32, 4000u32);
    // an A4 sheet 210 × 297 turned by `roll` in its plane, tilted away by `tilt` about the
    // photo's horizontal axis, seen by a camera of focal length `focal` × the longer side
    let seen = |tilt: f64, roll: f64, focal: f64, shift: [f64; 2]| -> [Point; 4] {
        let (f, distance) = (focal * 4000.0, 700.0);
        [[-105.0, -148.5], [105.0, -148.5], [105.0, 148.5], [-105.0, 148.5]].map(|[x, y]: [f64; 2]| {
            let (x, y) = (x * roll.to_radians().cos() - y * roll.to_radians().sin(), x * roll.to_radians().sin() + y * roll.to_radians().cos());
            let (y, z) = (y * tilt.to_radians().cos(), distance - y * tilt.to_radians().sin());
            [1500.0 + shift[0] + f * x / z, 2000.0 + shift[1] + f * y / z]
        })
    };
    let mut steepest: f64 = 0.0;
    for (tilt, roll, focal) in [(0.0, 0.0, 0.75), (30.0, 20.0, 0.75), (45.0, -35.0, 0.6), (60.0, 10.0, 0.75), (60.0, 50.0, 1.2), (50.0, 90.0, 2.0)] {
        let quad = Quad::new(seen(tilt, roll, focal, [40.0, -60.0])).unwrap();
        let [top, right, bottom, left] = quad.side_lengths();
        steepest = steepest.max((top / bottom).max(bottom / top)).max((left / right).max(right / left));
        let fit = sheet_fit(&quad, w, h).unwrap();
        assert!(fit.skew < 0.5, "tilt {tilt} roll {roll} focal {focal}: {fit:?}");
        // the lean is about the sheet's own, where the corners fix the focal length (leaning
        // about one of its own axes they do not, and the lean is then a guess)
        assert!(roll == 90.0 || (fit.tilt - tilt).abs() < 7.0, "tilt {tilt} roll {roll} focal {focal}: {fit:?}");
    }
    // these were no near-rectangles in the photo: one side 1.4 times its opposite
    assert!(steepest > 1.4, "{steepest}");
    // a long receipt (80 × 400) is a sheet like any other: the fit says nothing about how
    // narrow a page may be
    let receipt = |tilt: f64, roll: f64| -> [Point; 4] {
        [[-40.0, -200.0], [40.0, -200.0], [40.0, 200.0], [-40.0, 200.0]].map(|[x, y]: [f64; 2]| {
            let (x, y) = (x * roll.to_radians().cos() - y * roll.to_radians().sin(), x * roll.to_radians().sin() + y * roll.to_radians().cos());
            let (y, z) = (y * tilt.to_radians().cos(), 700.0 - y * tilt.to_radians().sin());
            [1500.0 + 3000.0 * x / z, 2000.0 + 3000.0 * y / z]
        })
    };
    for (tilt, roll) in [(0.0, 0.0), (40.0, 15.0), (50.0, -70.0)] {
        let fit = sheet_fit(&Quad::new(receipt(tilt, roll)).unwrap(), w, h).unwrap();
        assert!(fit.skew < 0.5, "receipt tilt {tilt} roll {roll}: {fit:?}");
    }
    // an ultra-wide camera and a 3× one are among the focal lengths tried (with 0.5 to 2.5 only,
    // the first of these measured 7.7 degrees and would have been refused)
    for focal in [0.38, 2.2, 3.6] {
        let fit = sheet_fit(&Quad::new(seen(45.0, 30.0, focal, [0.0, 0.0])).unwrap(), w, h).unwrap();
        assert!(fit.skew < 0.5, "focal {focal}: {fit:?}");
    }
    // a pile: three corners of the top sheet and the far corner of a sheet lying under it
    let top = seen(20.0, 30.0, 0.75, [-150.0, -300.0]);
    let under = seen(20.0, 10.0, 0.75, [400.0, 900.0]);
    let mixed = Quad::new([top[0], top[1], under[2], top[3]]).unwrap();
    let fit = sheet_fit(&mixed, w, h).unwrap();
    assert!(fit.skew > 3.0 * crate::detect::SKEW_MOST, "{fit:?}");
    // not every such mix is told apart: this one passes for a sheet leaning 70 degrees away from
    // an ultra-wide camera (the check refuses what cannot be a sheet, not all that is none)
    let (top2, under2) = (seen(20.0, -40.0, 0.75, [-150.0, -300.0]), seen(20.0, 5.0, 0.75, [250.0, 500.0]));
    let passes = sheet_fit(&Quad::new([top2[0], top2[1], under2[2], top2[3]]).unwrap(), w, h).unwrap();
    assert!(passes.skew < crate::detect::SKEW_MOST && passes.tilt > 60.0, "{passes:?}");
    // while each sheet by itself fits
    assert!(sheet_fit(&Quad::new(top).unwrap(), w, h).unwrap().skew < 0.5 && sheet_fit(&Quad::new(under).unwrap(), w, h).unwrap().skew < 0.5);
}

/// A pile: the corner maps' surest places are three corners of the top sheet and one of a sheet
/// under it. Read as before that is the answer; the check refuses it and takes the top sheet's
/// own fourth corner, a less sure place of its map.
#[test]
fn corners_of_different_sheets_are_refused_for_the_sheets_own() {
    use crate::detect::{CornerPick, read_sheet};
    // the top sheet, and the bottom-right corner of the sheet fanned out under it
    let sheet = [(20usize, 10usize), (44, 10), (44, 42), (20, 42)];
    let under = (56usize, 58usize);
    let heat = heat_of([&[(20, 10, 6.0)], &[(44, 10, 6.0)], &[(under.0, under.1, 6.0), (44, 42, 2.0)], &[(20, 42, 6.0)]]);
    let page = page_of(&[[20, 10, 44, 42], [30, 30, 56, 58]]);
    // as before: the quad across both sheets, and sure of it
    let before = read(&heat, &page);
    assert!(near(&before.corners, [sheet[0], sheet[1], under, sheet[3]]), "{:?}", before.corners);
    assert!(before.surest_less * before.agreement > 0.6, "{} {}", before.surest_less, before.agreement);
    // checked: refused, and the sheet's own corner taken
    let checked = read_sheet(&heat, &page, judge);
    assert!(checked.refused && checked.pick == CornerPick::Other, "{:?} {}", checked.pick, checked.refused);
    assert!(near(&checked.reading.corners, sheet), "{:?}", checked.reading.corners);
    assert!(checked.skew < 0.5 && (checked.reading.surest_less - 0.8808).abs() < 1e-3, "{} {}", checked.skew, checked.reading.surest_less);
    assert!(checked.reading.agreement > 0.5 && checked.reading.agreement < before.agreement + 0.2, "{}", checked.reading.agreement);

    // of two sheets that both fit, the one the page map agrees with more: the whole lower sheet
    // marked too, less surely, and the page map saying the top sheet alone
    let lower = [(32usize, 26usize), (56, 26), under, (32, 58)];
    let both = heat_of([
        &[(20, 10, 6.0), (lower[0].0, lower[0].1, 1.0)],
        &[(44, 10, 6.0), (lower[1].0, lower[1].1, 1.0)],
        &[(under.0, under.1, 6.0), (44, 42, 2.0)],
        &[(20, 42, 6.0), (lower[3].0, lower[3].1, 1.0)],
    ]);
    let top_only = page_of(&[[20, 10, 44, 42]]);
    let checked = read_sheet(&both, &top_only, judge);
    assert!(checked.refused && checked.pick == CornerPick::Other && near(&checked.reading.corners, sheet), "{:?}", checked.reading.corners);
    // ...and the lower sheet when the page map says that one
    let checked = read_sheet(&both, &page_of(&[[32, 26, 56, 58]]), judge);
    assert!(checked.refused && checked.pick == CornerPick::Other && near(&checked.reading.corners, lower), "{:?}", checked.reading.corners);

    // no other place marked: nothing is found in this look, rather than a quad that is no sheet
    let alone = heat_of([&[(20, 10, 6.0)], &[(44, 10, 6.0)], &[(under.0, under.1, 6.0)], &[(20, 42, 6.0)]]);
    let checked = read_sheet(&alone, &page, judge);
    assert!(checked.refused && checked.pick == CornerPick::None && checked.reading.agreement == 0.0, "{:?}", checked.pick);
    // a place too faint to count (under 0.1), or beside the surest one, is no other place
    let faint = heat_of([&[(20, 10, 6.0)], &[(44, 10, 6.0)], &[(under.0, under.1, 6.0), (44, 42, -2.5), (54, 56, 5.0)], &[(20, 42, 6.0)]]);
    assert_eq!(read_sheet(&faint, &page, judge).pick, CornerPick::None);
}

/// What was found right stays exactly as it was: a sheet seen square on or at a slant is each
/// map's surest place, with the numbers [`read`] gives, whatever else the maps mark.
#[test]
fn a_page_that_can_be_a_sheet_is_read_as_before() {
    use crate::detect::{CornerPick, read_sheet};
    let same = |heat: &[f32], page: &[f32]| {
        let (before, checked) = (read(heat, page), read_sheet(heat, page, judge));
        assert_eq!((checked.pick, checked.refused), (CornerPick::First, false));
        assert_eq!(
            (checked.reading.corners, checked.reading.surest_less, checked.reading.agreement),
            (before.corners, before.surest_less, before.agreement)
        );
        checked.skew
    };
    // square on, with a second sheet's corners marked almost as surely
    let heat =
        heat_of([&[(20, 10, 6.0), (30, 20, 5.0)], &[(44, 10, 6.0), (54, 20, 5.0)], &[(44, 42, 6.0), (54, 52, 5.0)], &[(20, 42, 6.0), (30, 52, 5.0)]]);
    assert!(same(&heat, &page_of(&[[20, 10, 44, 42]])) < 0.5);
    // leaning far back: the far side half as long as the near one, corners of 75 and 105 degrees
    let slant = heat_of([&[(22, 12, 6.0)], &[(41, 12, 6.0)], &[(51, 50, 6.0)], &[(12, 50, 6.0)]]);
    assert!(same(&slant, &page_of(&[[14, 12, 50, 50]])) < 2.0);
    // a rectangle inside the page (a table printed on it): a sheet by its shape, and as before
    // too unsure to be taken, because the page map says the whole page (under the 0.45 needed)
    let table = heat_of([&[(26, 20, 6.0)], &[(40, 20, 6.0)], &[(40, 34, 6.0)], &[(26, 34, 6.0)]]);
    let whole = page_of(&[[14, 8, 50, 56]]);
    assert!(same(&table, &whole) < 0.5);
    let nested = read_sheet(&table, &whole, judge).reading;
    assert!(nested.surest_less * nested.agreement < 0.2, "{} {}", nested.surest_less, nested.agreement);
    // a corner on the photo's border (the frame cut the sheet off): its shape is not judged
    let cut = heat_of([&[(20, 10, 6.0)], &[(44, 10, 6.0)], &[(63, 58, 6.0), (44, 42, 2.0)], &[(20, 42, 6.0)]]);
    assert_eq!(same(&cut, &page_of(&[[20, 10, 44, 42], [30, 30, 63, 58]])), 0.0);
}

/// With the real network: the check changes nothing for a single sheet, and the testing choice
/// reads the maps as before.
#[test]
fn the_check_leaves_a_single_sheet_as_it_was() {
    use crate::detect::{CornerPick, CornerRule, detect_by};
    let (w, h) = (1200u32, 1600u32);
    let sheet = photo(w, h, [[230.0, 260.0], [960.0, 300.0], [1010.0, 1330.0], [190.0, 1290.0]]);
    let (checked, before) = (detect(&sheet), detect_by(&sheet, Finder::Auto, CornerRule::Surest));
    assert_eq!((checked.quad, checked.confidence, checked.angle, checked.passes), (before.quad, before.confidence, before.angle, before.passes));
    assert_eq!((checked.pick, checked.refused, before.pick, before.refused), (CornerPick::First, 0, CornerPick::First, 0));
    // (the quad was set by hand, not by a camera: its skew is 3.6 degrees, a real sheet's about 1)
    assert!(checked.skew < crate::detect::SKEW_MOST as f32 && before.skew == 0.0, "{} {}", checked.skew, before.skew);
    assert_eq!(CornerRule::default(), CornerRule::Sheet);
}

/// The largest distance between the corners of two outlines of one page, in pixels, whichever
/// corner each outline starts at.
fn corners_apart(a: &Quad, b: &Quad) -> f64 {
    (0..4)
        .map(|shift| (0..4).map(|i| (a.0[i][0] - b.0[(i + shift) % 4][0]).hypot(a.0[i][1] - b.0[(i + shift) % 4][1])).fold(0.0, f64::max))
        .fold(f64::INFINITY, f64::min)
}

/// A sheet of 420 × 594 lying turned by `degrees` (clockwise on the screen) about the middle of a
/// 900 × 900 photo, its corners in the sheet's own order.
fn turned_sheet(degrees: f64) -> [Point; 4] {
    let (sin, cos) = degrees.to_radians().sin_cos();
    [[-210.0, -297.0], [210.0, -297.0], [210.0, 297.0], [-210.0, 297.0]].map(|[x, y]| [450.0 + x * cos - y * sin, 450.0 + x * sin + y * cos])
}

/// The looks that show a page most nearly upright come first (#785), and looks that show it
/// equally upright keep the fixed order between them.
#[test]
fn the_turned_looks_are_ordered_by_how_upright_they_show_the_page() {
    use crate::detect::{LookOrder, LooksBy, lean, turned_looks};
    let fixed = [90, -90, -30, 30, -60, 60];
    assert_eq!(turned_looks(LookOrder::Fixed, None), (fixed, LooksBy::Fixed));
    // nothing says how the page lies: the fixed order
    assert_eq!(turned_looks(LookOrder::Upright(None), None), (fixed, LooksBy::Fixed));
    let lying = |degrees: f64| Quad::new(turned_sheet(degrees)).unwrap();
    // an upright page: a quarter turn shows it as upright as no turn, so the fixed order stays
    assert_eq!(turned_looks(LookOrder::Upright(Some(lying(0.0))), None), (fixed, LooksBy::Outline));
    assert!(lean(&lying(0.0), 0) < 0.01 && lean(&lying(0.0), 90) < 0.01 && (lean(&lying(0.0), 30) - 30.0).abs() < 0.01);
    // a page turned 30 degrees clockwise is upright in the look turned 30 the other way, and on
    // its side in the one turned 60 on; those two first, in the fixed order
    let (angles, by) = turned_looks(LookOrder::Upright(None), Some(&lying(30.0)));
    assert_eq!((&angles[..2], by), (&[-30, 60][..], LooksBy::FirstLook));
    assert_eq!(&turned_looks(LookOrder::Upright(Some(lying(-30.0))), None).0[..2], [30, -60]);
    assert_eq!(&turned_looks(LookOrder::Upright(Some(lying(60.0))), None).0[..2], [30, -60]);
    // the outline given wins over the first look's corners
    assert_eq!(turned_looks(LookOrder::Upright(Some(lying(-30.0))), Some(&lying(30.0))).1, LooksBy::Outline);
    // a small lean changes nothing: every look is where it was
    assert_eq!(turned_looks(LookOrder::Upright(Some(lying(3.0))), None).0, fixed);
    assert_eq!(turned_looks(LookOrder::Upright(Some(lying(-3.0))), None).0, fixed);
    // every order is the same six looks
    for degrees in [-80.0, -45.0, -10.0, 17.0, 45.0, 73.0] {
        let mut angles = turned_looks(LookOrder::Upright(Some(lying(degrees))), None).0;
        angles.sort_unstable();
        assert_eq!(angles, [-90, -60, -30, 30, 60, 90]);
    }
}

/// The search over made-up answers: in any order the same looks are made and the same answer
/// kept when no turned look is sure, and a sure look ends the search where it stands in the order.
#[test]
fn ordering_the_looks_changes_the_search_only_where_a_sure_look_ends_it() {
    use crate::detect::{CornerPick, Look, LookOrder, search};
    let page = Quad::new(turned_sheet(30.0)).unwrap();
    let mut seed = 785u64;
    let mut next = || {
        seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        ((seed >> 40) as f32) / (1u64 << 24) as f32
    };
    let (mut unsure, mut sooner, mut later) = (0, 0, 0);
    for round in 0..4000 {
        // confidences for no turn and the six turns; in half the rounds none is sure
        let cap = if round % 2 == 0 { 0.69 } else { 0.95 };
        let table: Vec<(i16, f32)> = [0, 90, -90, -30, 30, -60, 60].into_iter().map(|d| (d, next() * cap)).collect();
        let run = |order: LookOrder| {
            let mut made = Vec::new();
            let found = search(
                |degrees| {
                    made.push(degrees);
                    let confidence = table.iter().find(|(d, _)| *d == degrees).map_or(0.0, |(_, c)| *c);
                    Some(Look { quad: Some(page), confidence, pick: CornerPick::First, refused: false, skew: 0.0 })
                },
                order,
            )
            .unwrap();
            (found, made)
        };
        let ((before, looks_before), (after, looks_after)) = (run(LookOrder::Fixed), run(LookOrder::Upright(None)));
        assert_eq!(usize::from(before.passes), looks_before.len());
        assert_eq!(usize::from(after.passes), looks_after.len());
        let sure: Vec<i16> = table.iter().skip(1).filter(|(_, c)| *c >= 0.7).map(|(d, _)| *d).collect();
        if table[0].1 >= 0.5 {
            assert_eq!((before.passes, after.passes, before.angle, after.angle), (1, 1, 0, 0));
        } else if sure.is_empty() {
            // every look is made either way, and the surest is kept
            unsure += 1;
            assert_eq!((before.passes, after.passes), (7, 7));
            assert_eq!((before.angle, before.confidence, before.quad), (after.angle, after.confidence, after.quad), "{table:?}");
        } else {
            // each search ends on the first sure look of its own order
            assert!(sure.contains(&before.angle) && sure.contains(&after.angle), "{table:?}");
            assert_eq!(Some(&after.angle), looks_after.last());
            sooner += usize::from(after.passes < before.passes);
            later += usize::from(after.passes > before.passes);
        }
    }
    assert!(unsure > 500 && sooner > 100, "{unsure} unsure, {sooner} sooner, {later} later");
}

/// Sheets lying at a slant, found by the network (#785): with the looks ordered, by the first
/// look's own corners or by the page's true outline, no search makes more looks, and the page is
/// the same to the pixel wherever the search ends on the same look. Where two turned looks are
/// sure the ordered search may end on the other one: the same sheet, its corners a little apart.
#[test]
fn a_slanted_sheet_is_found_the_same_in_fewer_looks() {
    use crate::detect::{CornerRule, LookOrder, detect_by, detect_in_order};
    let (mut fixed, mut by_first, mut by_outline, mut other_look) = (0u32, 0u32, 0u32, 0u32);
    for degrees in [0.0, 12.0, 30.0, -30.0, 45.0, 60.0, -60.0, 90.0, 150.0, -120.0] {
        let truth = turned_sheet(degrees);
        let shot = photo(900, 900, truth);
        let before = detect_by(&shot, Finder::Network, CornerRule::Sheet);
        let first = detect_in_order(&shot, Finder::Network, CornerRule::Sheet, LookOrder::Upright(None));
        let outline = detect_in_order(&shot, Finder::Network, CornerRule::Sheet, LookOrder::Upright(Quad::new(truth).ok()));
        println!(
            "sheet turned {degrees}: {} looks (at {}, {:.2}), {} by the first look's corners, {} by its outline",
            before.passes, before.angle, before.confidence, first.passes, outline.passes
        );
        assert!(before.quad.is_some(), "turned {degrees}: not found");
        for ordered in [&first, &outline] {
            if ordered.angle == before.angle {
                assert_eq!((ordered.quad, ordered.confidence), (before.quad, before.confidence), "turned {degrees}");
            } else {
                // another sure look of the same sheet
                let apart = corners_apart(&ordered.quad.unwrap(), &before.quad.unwrap());
                // put on the sheet's edges, as the app does next, the two are the same page
                let pyramid = crate::Pyramid::new(shot.luma());
                let on_edges = |quad: &Quad| crate::refine(&pyramid, quad, &crate::RefineOptions::default()).quad;
                let (edges, edges_before, sheet) = (on_edges(&ordered.quad.unwrap()), on_edges(&before.quad.unwrap()), Quad::new(truth).unwrap());
                let (refined_apart, off, off_before) =
                    (corners_apart(&edges, &edges_before), corners_apart(&edges, &sheet), corners_apart(&edges_before, &sheet));
                println!(
                    "  another sure look: at {} ({:.2}), corners {apart:.1} px apart; on the edges {refined_apart:.1} px apart, {off:.1} px from the sheet against {off_before:.1}",
                    ordered.angle, ordered.confidence
                );
                assert!(ordered.confidence >= 0.7 && before.confidence >= 0.7, "turned {degrees}");
                assert!(apart < 0.025 * 900.0, "turned {degrees}: {apart} px");
                assert!(refined_apart < 2.0 && off < 2.0, "turned {degrees}: {refined_apart} px apart, {off} px from the sheet");
                other_look += 1;
            }
            assert!(ordered.passes <= before.passes, "turned {degrees}");
        }
        fixed += u32::from(before.passes);
        by_first += u32::from(first.passes);
        by_outline += u32::from(outline.passes);
    }
    println!(
        "looks on the ten sheets: {fixed} in the fixed order, {by_first} by the first look's corners, {by_outline} by the outline; {other_look} ended on another sure look"
    );
    assert!(by_outline < fixed, "{by_outline} looks against {fixed}");
}
