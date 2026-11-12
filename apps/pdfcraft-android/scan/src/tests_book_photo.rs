//! Tests for book mode on a whole photo (#303): `book::split_photo` and `Pyramid::straighten`.
//! Every photo is drawn in code: a spread of two pages with text bars and a soft dark gutter,
//! lying in a perspective outline on a dark table.

use crate::book::{SPREAD_SIDE, split_photo};
use crate::{Luma, Pyramid, Quad};
use pdfcraft_geom::perspective::{Homography, Point};

const PAPER: f64 = 228.0;
const INK: f64 = 90.0;
const TABLE: f64 = 50.0;
const LINES: f64 = 40.0;
const MARGIN: f64 = 0.06;
const VALLEY_SIGMA: f64 = 0.012;

/// The spread's brightness at (u, v), shares of its width and height, with a gutter of relative
/// `depth` along u = `gutter(v)`.
fn spread_value(u: f64, v: f64, gutter: &dyn Fn(f64) -> f64, depth: f64) -> f64 {
    let ink = u > MARGIN && u < 1.0 - MARGIN && (v * LINES).fract() < 0.3;
    let paper = if ink { PAPER - INK } else { PAPER };
    let d = (u - gutter(v)) / VALLEY_SIGMA;
    paper * (1.0 - depth * (-0.5 * d * d).exp())
}

/// A `w` × `h` photo with the spread inside `quad`, on a dark table.
fn photo(w: u32, h: u32, quad: [Point; 4], gutter: &dyn Fn(f64) -> f64, depth: f64) -> Luma {
    let unit = [[0.0, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 1.0]];
    let to_unit = Homography::from_points(quad, unit).expect("a drawable outline");
    let mut data = Vec::with_capacity(w as usize * h as usize);
    for row in 0..h {
        for col in 0..w {
            let [u, v] = to_unit.apply([f64::from(col) + 0.5, f64::from(row) + 0.5]).unwrap_or([-1.0, -1.0]);
            let value = if (0.0..=1.0).contains(&u) && (0.0..=1.0).contains(&v) { spread_value(u, v, gutter, depth) } else { TABLE };
            data.push(value.round().clamp(0.0, 255.0) as u8);
        }
    }
    Luma { width: w, height: h, data }
}

/// A spread of two A pages (ratio 1.414) seen at a slant: wider at the bottom.
fn slanted() -> [Point; 4] {
    [[300.0, 200.0], [1700.0, 230.0], [1760.0, 1240.0], [250.0, 1210.0]]
}

fn distance(a: Point, b: Point) -> f64 {
    (a[0] - b[0]).hypot(a[1] - b[1])
}

#[test]
fn straighten_maps_the_quad_onto_the_output() {
    // a picture whose left half is dark and right half light, the quad its middle 50 %
    let mut data = vec![0u8; 400 * 300];
    for (i, px) in data.iter_mut().enumerate() {
        *px = if i % 400 < 200 { 20 } else { 220 };
    }
    let pyramid = Pyramid::new(Luma { width: 400, height: 300, data });
    let quad = Quad([[100.0, 75.0], [300.0, 75.0], [300.0, 225.0], [100.0, 225.0]]);
    let out = pyramid.straighten(&quad, 40, 30).expect("a straightened picture");
    assert_eq!((out.width, out.height), (40, 30));
    let row: Vec<u8> = out.data[15 * 40..16 * 40].to_vec();
    assert!(row[..18].iter().all(|&v| v < 40), "left half {row:?}");
    assert!(row[22..].iter().all(|&v| v > 200), "right half {row:?}");
    assert_eq!(pyramid.straighten(&quad, 0, 30), None);
}

#[test]
fn a_photographed_spread_is_cut_along_its_gutter_within_half_a_percent() {
    let quad = slanted();
    let pyramid = Pyramid::new(photo(2000, 1500, quad, &|_| 0.46, 0.35));
    let book = split_photo(&pyramid, &Quad(quad), true).expect("two pages");
    println!("gutter {:?}, searched {:?}", book.gutter, book.searched);
    assert!(book.gutter.found);
    assert!((book.gutter.top - 0.46).abs() < 0.005 && (book.gutter.bottom - 0.46).abs() < 0.005, "{:?}", book.gutter);
    assert!(book.gutter.confidence > 0.5);
    assert_eq!(book.searched.0.max(book.searched.1), SPREAD_SIDE);
    // the cut meets the outline's top and bottom sides within 1 % of the width of where the
    // drawn gutter is (0.46 of the straightened spread, mapped into the photo)
    let unit = [[0.0, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 1.0]];
    let to_photo = Homography::from_points(unit, quad).expect("a map");
    let truth_top = to_photo.apply([0.46, 0.0]).expect("a point");
    let truth_bottom = to_photo.apply([0.46, 1.0]).expect("a point");
    let width = distance(quad[0], quad[1]);
    assert!(distance(book.spread.left.0[1], truth_top) < 0.01 * width, "left top {:?}", book.spread.left.0[1]);
    assert!(distance(book.spread.right.0[3], truth_bottom) < 0.01 * width, "right bottom {:?}", book.spread.right.0[3]);
    // the outer corners are the outline's own
    assert_eq!(book.spread.left.0[0], quad[0]);
    assert_eq!(book.spread.right.0[2], quad[2]);
}

#[test]
fn a_tilted_spine_is_followed() {
    let quad = slanted();
    let pyramid = Pyramid::new(photo(2000, 1500, quad, &|v| 0.48 + 0.06 * (v - 0.5), 0.35));
    let book = split_photo(&pyramid, &Quad(quad), true).expect("two pages");
    println!("tilted gutter {:?}", book.gutter);
    assert!((book.gutter.top - 0.45).abs() < 0.008 && (book.gutter.bottom - 0.51).abs() < 0.008, "{:?}", book.gutter);
}

#[test]
fn a_single_page_without_a_gutter_stays_one_page() {
    // one A page, portrait (ratio 0.707), no valley
    let quad = [[600.0, 150.0], [1400.0, 160.0], [1420.0, 1300.0], [580.0, 1290.0]];
    let pyramid = Pyramid::new(photo(2000, 1500, quad, &|_| 2.0, 0.0));
    assert_eq!(split_photo(&pyramid, &Quad(quad), true), None);
}

#[test]
fn a_spread_without_a_visible_gutter_is_cut_at_the_centre_with_no_confidence() {
    let quad = [[200.0, 200.0], [1614.0, 200.0], [1614.0, 1200.0], [200.0, 1200.0]];
    let pyramid = Pyramid::new(photo(2000, 1500, quad, &|_| 2.0, 0.0));
    let book = split_photo(&pyramid, &Quad(quad), true).expect("two pages by shape");
    assert!(!book.gutter.found);
    assert_eq!((book.gutter.top, book.gutter.bottom, book.gutter.confidence), (0.5, 0.5, 0.0));
}

#[test]
fn with_the_gutter_search_off_the_cut_is_the_centre_line() {
    let quad = slanted();
    let pyramid = Pyramid::new(photo(2000, 1500, quad, &|_| 0.40, 0.35));
    let book = split_photo(&pyramid, &Quad(quad), false).expect("two pages");
    assert!(!book.gutter.found);
    assert_eq!((book.gutter.top, book.gutter.bottom, book.gutter.confidence), (0.5, 0.5, 0.0));
}

#[test]
fn the_same_photo_gives_the_same_split_twice() {
    let quad = slanted();
    let pyramid = Pyramid::new(photo(2000, 1500, quad, &|_| 0.52, 0.2));
    assert_eq!(split_photo(&pyramid, &Quad(quad), true), split_photo(&pyramid, &Quad(quad), true));
}
