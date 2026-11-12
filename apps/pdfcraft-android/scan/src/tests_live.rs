//! The page in a camera frame (#295), on drawn 640 × 480 frames (the detector's model file is in
//! `assets/models`).

use pdfcraft_geom::perspective::Point;

use crate::detect::{QuadSource, detect, one_look};
use crate::live::{find, upright_frame};
use crate::models::{EDGES, load};
use crate::tests_detect::photo;
use crate::{LiveQuad, MAX_FRAME_PIXELS, Photo, Quad, ScanError, detect_frame};

/// `photo` turned a quarter counter-clockwise: what the sensor holds when a clockwise quarter
/// turn makes it upright.
fn turn_back(photo: &Photo) -> Photo {
    let (w, h) = (photo.width as usize, photo.height as usize);
    let mut rgb = Vec::with_capacity(photo.rgb.len());
    for y in 0..w {
        for x in 0..h {
            // the new (x, y) shows the old (w - 1 - y, x)
            let i = (x * w + (w - 1 - y)) * 3;
            rgb.extend_from_slice(&photo.rgb[i..i + 3]);
        }
    }
    Photo { width: h as u32, height: w as u32, rgb }
}

fn rgba(photo: &Photo) -> Vec<u8> {
    photo.rgb.as_chunks::<3>().0.iter().flat_map(|p| [p[0], p[1], p[2], 255]).collect()
}

/// A frame as the sensor delivers it for an upright scene `upright` and a clockwise turn of
/// `rotation` degrees.
fn sensor(upright: &Photo, rotation: u32) -> Photo {
    (0..rotation / 90).fold(upright.clone(), |p, _| turn_back(&p))
}

/// The largest distance between matching corners, as a share of the frame's diagonal.
fn off(found: &LiveQuad, truth: [Point; 4], w: u32, h: u32) -> f64 {
    let truth = Quad::new(truth).unwrap();
    let (fw, fh) = (f64::from(w), f64::from(h));
    (0..4)
        .map(|i| (f64::from(found.points[2 * i]) * fw - truth.0[i][0]).hypot(f64::from(found.points[2 * i + 1]) * fh - truth.0[i][1]))
        .fold(0.0, f64::max)
        / fw.hypot(fh)
}

#[test]
fn a_frame_is_turned_upright_pixel_by_pixel() {
    // a 3 × 2 frame whose red value is its pixel's number
    //   0 1 2
    //   3 4 5
    let frame: Vec<u8> = (0..6u8).flat_map(|i| [i, 0, 0, 255]).collect();
    let red = |p: &Photo| p.rgb.as_chunks::<3>().0.iter().map(|c| c[0]).collect::<Vec<_>>();
    let none = upright_frame(&frame, 3, 2, 0).unwrap();
    assert_eq!((none.width, none.height, red(&none)), (3, 2, vec![0, 1, 2, 3, 4, 5]));
    // a quarter turn clockwise: the left column, read upwards, becomes the top row
    let quarter = upright_frame(&frame, 3, 2, 90).unwrap();
    assert_eq!((quarter.width, quarter.height, red(&quarter)), (2, 3, vec![3, 0, 4, 1, 5, 2]));
    let half = upright_frame(&frame, 3, 2, 180).unwrap();
    assert_eq!((half.width, half.height, red(&half)), (3, 2, vec![5, 4, 3, 2, 1, 0]));
    let three = upright_frame(&frame, 3, 2, 270).unwrap();
    assert_eq!((three.width, three.height, red(&three)), (2, 3, vec![2, 5, 1, 4, 0, 3]));
    // the test's own turn back undoes each
    let scene = Photo { width: 3, height: 2, rgb: (0..6u8).flat_map(|i| [i, 0, 0]).collect() };
    for rotation in [0, 90, 180, 270] {
        let held = sensor(&scene, rotation);
        assert_eq!(upright_frame(&rgba(&held), held.width, held.height, rotation).unwrap(), scene, "{rotation}");
    }
}

/// A sheet on a dark grainy table in a 640 × 480 frame, at each of the camera's four turns: found
/// in one look, its corners in shares of the upright frame.
#[test]
fn the_page_is_found_in_a_frame_at_each_turn() {
    // the upright scene: landscape for 0 and 180, portrait for 90 and 270
    let wide = [[210.0, 50.0], [440.0, 72.0], [455.0, 430.0], [188.0, 414.0]];
    let tall = [[82.0, 122.0], [392.0, 100.0], [412.0, 520.0], [60.0, 542.0]];
    for rotation in [0u32, 90, 180, 270] {
        let (uw, uh, truth) = if rotation % 180 == 0 { (640, 480, wide) } else { (480, 640, tall) };
        let frame = sensor(&photo(uw, uh, truth), rotation);
        assert_eq!((frame.width, frame.height), (640, 480));
        let found = detect_frame(&rgba(&frame), 640, 480, rotation).unwrap();
        let error = off(&found, truth, uw, uh);
        println!(
            "rotation {rotation}: {:?} confidence {:.2}, {} sides refined, off by {:.2} % of the diagonal ({:.1} px), luma {:.1}",
            found.source,
            found.confidence,
            found.refined_sides,
            error * 100.0,
            error * 800.0,
            found.mean_luma
        );
        assert!(found.found && found.source == QuadSource::Model && !found.cut_off, "{rotation}: {found:?}");
        assert!(found.confidence >= 0.45, "{rotation}: {found:?}");
        assert!(error < 0.01, "{rotation}: off by {error}: {found:?}");
        assert!(found.points.iter().all(|v| (0.0..=1.0).contains(v)));
    }
}

#[test]
fn a_frame_without_a_page_gives_nothing() {
    let table = photo(640, 480, [[-900.0, -900.0], [-800.0, -900.0], [-800.0, -800.0], [-900.0, -800.0]]);
    let found = detect_frame(&rgba(&table), 640, 480, 90).unwrap();
    println!("table: confidence {:.2}", found.confidence);
    assert!(!found.found && found.source == QuadSource::None && !found.cut_off, "{found:?}");
    assert_eq!(found.points, [0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0]);
    // the same answer every time
    assert_eq!(detect_frame(&rgba(&table), 640, 480, 90).unwrap(), found);
    // one look, where the photo search turns the photo and looks six times more
    let model = load(EDGES).unwrap();
    let once = one_look(&model, &table).unwrap();
    assert_eq!((once.quad, once.source, once.passes), (None, QuadSource::None, 1));
    assert_eq!(detect(&table).passes, 7);
}

#[test]
fn the_mean_luma_is_the_frames() {
    // BT.601 luma of (120, 130, 140) is 128.1, rounded down to 128 per pixel
    let grey: Vec<u8> = [120u8, 130, 140, 255].repeat(640 * 480);
    let found = detect_frame(&grey, 640, 480, 0).unwrap();
    assert_eq!(found.mean_luma, 128.0);
    assert!(!found.found, "{found:?}");
    // half black, half white: 127.5, at any turn
    let halves: Vec<u8> = (0..640 * 480).flat_map(|i| if i % 640 < 320 { [0, 0, 0, 255] } else { [255; 4] }).collect();
    for rotation in [0, 90, 180, 270] {
        assert_eq!(detect_frame(&halves, 640, 480, rotation).unwrap().mean_luma, 127.5);
    }
}

#[test]
fn a_corner_at_the_frames_edge_is_cut_off() {
    // the sheet's bottom-right corner 4 pixels above the frame's bottom edge (0.8 %)
    let truth = [[200.0, 30.0], [440.0, 20.0], [455.0, 476.0], [188.0, 450.0]];
    let found = detect_frame(&rgba(&photo(640, 480, truth)), 640, 480, 0).unwrap();
    assert!(found.found && found.cut_off && off(&found, truth, 640, 480) < 0.01, "{found:?}");
    // the same sheet well inside the frame is not
    let inside = [[200.0, 50.0], [440.0, 40.0], [455.0, 440.0], [188.0, 420.0]];
    let found = detect_frame(&rgba(&photo(640, 480, inside)), 640, 480, 0).unwrap();
    assert!(found.found && !found.cut_off, "{found:?}");
}

#[test]
fn without_the_model_the_line_detector_is_asked() {
    let truth = [[210.0, 50.0], [440.0, 72.0], [455.0, 430.0], [188.0, 414.0]];
    let found = find(&photo(640, 480, truth), None);
    assert!(found.found && found.source == QuadSource::Lines && found.confidence == 0.5, "{found:?}");
    assert!(off(&found, truth, 640, 480) < 0.01, "{found:?}");
    // with the model the network answers
    assert_eq!(find(&photo(640, 480, truth), load(EDGES)).source, QuadSource::Model);
}

#[test]
fn a_bad_frame_is_refused() {
    let frame = vec![0u8; 640 * 480 * 4];
    let refused = |r: Result<LiveQuad, ScanError>| matches!(r, Err(ScanError::BadFrame(_)));
    assert!(refused(detect_frame(&frame[..frame.len() - 1], 640, 480, 0)));
    assert!(refused(detect_frame(&frame, 0, 480, 0)));
    assert!(refused(detect_frame(&frame, 640, 0, 0)));
    assert!(refused(detect_frame(&[], 0, 0, 0)));
    for rotation in [45, 360, 1, u32::MAX] {
        assert!(refused(detect_frame(&frame, 640, 480, rotation)), "{rotation}");
    }
    // more than 2 MP, whatever the buffer
    let side = (MAX_FRAME_PIXELS as f64).sqrt() as u32 + 1;
    assert!(refused(detect_frame(&frame, side, side, 0)));
    assert!(refused(detect_frame(&frame, u32::MAX, u32::MAX, 0)));
    // a longer buffer is fine: the bytes past the frame are not read
    let mut longer = frame.clone();
    longer.extend_from_slice(&[7; 100]);
    assert!(detect_frame(&longer, 640, 480, 0).is_ok());
}

/// The time of one call on a 640 × 480 frame. Printed, not checked: run with `--release` and
/// `--nocapture` to read it. On whatever machine runs the tests, not a phone.
#[test]
fn time_of_one_frame() {
    let tall = [[82.0, 122.0], [392.0, 100.0], [412.0, 520.0], [60.0, 542.0]];
    let frame = rgba(&sensor(&photo(480, 640, tall), 90));
    // the first call loads the model
    let start = std::time::Instant::now();
    assert!(detect_frame(&frame, 640, 480, 90).unwrap().found);
    let first = start.elapsed();
    let mut times: Vec<f64> = (0..10)
        .map(|_| {
            let start = std::time::Instant::now();
            let found = detect_frame(&frame, 640, 480, 90).unwrap();
            assert!(found.found);
            start.elapsed().as_secs_f64() * 1000.0
        })
        .collect();
    times.sort_by(f64::total_cmp);
    println!(
        "one 640 x 480 frame: first call {:.1} ms (model load included), then median {:.1} ms, min {:.1}, max {:.1} over 10",
        first.as_secs_f64() * 1000.0,
        times[5],
        times[0],
        times[9]
    );
}
