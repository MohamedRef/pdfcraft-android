//! Tests for `book.rs` (issue #303, spec S17 part A). Every spread is drawn in code.

use crate::book::{Gutter, find_gutter, split};
use crate::{Luma, Quad};
use pdfcraft_geom::perspective::Point;

/// Brightness of the blank paper (0–255).
const PAPER: f64 = 228.0;
/// How much darker a text bar is than the paper.
const INK: f64 = 90.0;
/// Text bars per height of the spread.
const LINES: f64 = 40.0;
/// Blank margin at each outer edge, as a share of the width.
const MARGIN: f64 = 0.06;
/// Half-width of the gutter's soft valley, as a share of the width.
const VALLEY_SIGMA: f64 = 0.012;

/// Light pages with text bars, and a soft dark valley of relative `depth` (0 for none) along
/// x = `gutter(y)`, where `y` is a share of the height. A `gutter` outside 0–1 means no gutter.
fn book(gutter: impl Fn(f64) -> f64, depth: f64) -> impl Fn(f64, f64) -> f64 {
    move |x, y| {
        let g = gutter(y);
        // Text runs right up to the fold, as it does in a real book.
        let in_text = x > MARGIN && x < 1.0 - MARGIN;
        let ink = in_text && (y * LINES).fract() < 0.3;
        let paper = if ink { PAPER - INK } else { PAPER };
        let d = (x - g) / VALLEY_SIGMA;
        paper * (1.0 - depth * (-0.5 * d * d).exp())
    }
}

/// A grey picture of `w` × `h` pixels, pixel `(col, row)` taking `value` at its centre's share
/// of the width and height.
fn picture(w: u32, h: u32, value: impl Fn(f64, f64) -> f64) -> Luma {
    let mut data = Vec::with_capacity(w as usize * h as usize);
    for row in 0..h {
        for col in 0..w {
            let x = (f64::from(col) + 0.5) / f64::from(w);
            let y = (f64::from(row) + 0.5) / f64::from(h);
            data.push(value(x, y).round().clamp(0.0, 255.0) as u8);
        }
    }
    Luma { width: w, height: h, data }
}

/// A spread of two A-series pages side by side: 800 × 566 pixels (ratio 1.414).
fn a_spread(value: impl Fn(f64, f64) -> f64) -> Luma {
    picture(800, 566, value)
}

/// A square outline of `side` pixels, corners in reading order.
fn square(side: f64) -> Quad {
    Quad([[0.0, 0.0], [side, 0.0], [side, side], [0.0, side]])
}

/// A gutter with the given top and bottom shares.
fn gutter(top: f64, bottom: f64) -> Gutter {
    Gutter { top, bottom, confidence: 1.0, found: true }
}

/// Distance from `p` to the segment `a`–`b`.
fn distance_to_segment(p: Point, a: Point, b: Point) -> f64 {
    let (dx, dy) = (b[0] - a[0], b[1] - a[1]);
    let len2 = dx * dx + dy * dy;
    let t = (((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / len2).clamp(0.0, 1.0);
    (p[0] - (a[0] + t * dx)).hypot(p[1] - (a[1] + t * dy))
}

/// True when the four points, in order, form a convex quadrilateral (checked independently of the
/// module under test: every corner turns the same way).
fn convex(q: &Quad) -> bool {
    let p = q.0;
    let cross = |i: usize| {
        let (a, b, c) = (p[i], p[(i + 1) % 4], p[(i + 2) % 4]);
        (b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0])
    };
    let turns = [cross(0), cross(1), cross(2), cross(3)];
    turns.iter().all(|&t| t > 0.0) || turns.iter().all(|&t| t < 0.0)
}

#[test]
fn a_gutter_at_the_centre_is_found_within_half_a_percent() {
    let spread = a_spread(book(|_| 0.50, 0.35));
    let g = find_gutter(&spread).unwrap();
    println!("centre: top={:.4} bottom={:.4} confidence={:.3} found={}", g.top, g.bottom, g.confidence, g.found);
    assert!(g.found);
    assert!((g.top - 0.50).abs() < 0.005, "top {}", g.top);
    assert!((g.bottom - 0.50).abs() < 0.005, "bottom {}", g.bottom);
    assert!(g.confidence > 0.5, "confidence {}", g.confidence);
}

#[test]
fn a_gutter_at_46_percent_is_found_within_half_a_percent() {
    let spread = a_spread(book(|_| 0.46, 0.35));
    let g = find_gutter(&spread).unwrap();
    println!("46 %: top={:.4} bottom={:.4} confidence={:.3} found={}", g.top, g.bottom, g.confidence, g.found);
    assert!(g.found);
    assert!((g.top - 0.46).abs() < 0.005, "top {}", g.top);
    assert!((g.bottom - 0.46).abs() < 0.005, "bottom {}", g.bottom);
}

#[test]
fn a_leaning_gutter_gives_its_top_and_bottom_within_0_8_percent() {
    // The spine runs from 0.47 of the width at the top to 0.53 at the bottom.
    let spread = a_spread(book(|y| 0.47 + 0.06 * y, 0.35));
    let g = find_gutter(&spread).unwrap();
    println!("leaning: top={:.4} bottom={:.4} confidence={:.3} found={}", g.top, g.bottom, g.confidence, g.found);
    assert!(g.found);
    assert!((g.top - 0.47).abs() < 0.008, "top {}", g.top);
    assert!((g.bottom - 0.53).abs() < 0.008, "bottom {}", g.bottom);
}

#[test]
fn a_faint_valley_is_found_with_a_low_confidence() {
    let spread = a_spread(book(|_| 0.50, 0.12));
    let g = find_gutter(&spread).unwrap();
    println!("faint: top={:.4} bottom={:.4} confidence={:.3} found={}", g.top, g.bottom, g.confidence, g.found);
    assert!(g.found);
    assert!((g.top - 0.50).abs() < 0.005, "top {}", g.top);
    assert!(g.confidence < 0.5, "confidence {}", g.confidence);
}

#[test]
fn a_dark_photograph_on_one_page_away_from_the_middle_is_not_taken_for_the_gutter() {
    // A dark picture (brightness 40) on the left page, and another on the right page.
    let photos = |x: f64, y: f64| {
        let left = (0.10..0.30).contains(&x) && (0.20..0.60).contains(&y);
        let right = (0.72..0.90).contains(&x) && (0.30..0.80).contains(&y);
        if left || right { Some(40.0) } else { None }
    };
    let spread = a_spread(move |x, y| photos(x, y).unwrap_or_else(|| book(|_| 2.0, 0.0)(x, y)));
    let g = find_gutter(&spread).unwrap();
    println!("photograph: top={:.4} bottom={:.4} confidence={:.3} found={}", g.top, g.bottom, g.confidence, g.found);
    assert!(!g.found);
    assert!((g.top - 0.5).abs() < 1e-12 && (g.bottom - 0.5).abs() < 1e-12);
}

#[test]
fn a_spread_without_a_valley_of_two_page_shape_gets_the_centre_line() {
    let spread = a_spread(book(|_| 2.0, 0.0));
    let g = find_gutter(&spread).unwrap();
    println!("A-series, no valley: top={} bottom={} confidence={} found={}", g.top, g.bottom, g.confidence, g.found);
    assert!(!g.found);
    assert_eq!((g.top, g.bottom, g.confidence), (0.5, 0.5, 0.0));

    // Two Letter pages side by side: 880 × 570 pixels (ratio 1.544).
    let letter = picture(880, 570, book(|_| 2.0, 0.0));
    let g = find_gutter(&letter).unwrap();
    println!("Letter, no valley: top={} bottom={} found={}", g.top, g.bottom, g.found);
    assert!(!g.found);
    assert_eq!((g.top, g.bottom), (0.5, 0.5));
}

#[test]
fn a_single_page_without_a_valley_gives_none() {
    // One portrait page: 566 × 800 pixels (ratio 0.707), with text bars and no fold.
    let page = picture(566, 800, book(|_| 2.0, 0.0));
    println!("single page: {:?}", find_gutter(&page));
    assert_eq!(find_gutter(&page), None);
}

#[test]
fn a_picture_whose_data_does_not_match_its_size_gives_none() {
    let short = Luma { width: 800, height: 566, data: vec![200; 10] };
    let tiny = Luma { width: 4, height: 2, data: vec![200; 8] };
    assert_eq!(find_gutter(&short), None);
    assert_eq!(find_gutter(&tiny), None);
}

#[test]
fn the_same_picture_gives_the_same_gutter_twice() {
    let spread = a_spread(book(|y| 0.47 + 0.06 * y, 0.35));
    assert_eq!(find_gutter(&spread), find_gutter(&spread));
}

#[test]
fn a_square_quad_splits_into_halves_whose_areas_add_to_99_percent() {
    let quad = square(100.0);
    let s = split(&quad, &gutter(0.5, 0.5)).unwrap();
    let (l, r) = (s.left.area(), s.right.area());
    println!("square split: left={l:.3} right={r:.3} sum/whole={:.6}", (l + r) / quad.area());
    assert!(((l + r) / quad.area() - 0.99).abs() < 1e-9);
    assert!((l - r).abs() < 1e-9, "halves differ: {l} {r}");
    assert_eq!(s.gutter_confidence, 1.0);
}

#[test]
fn a_perspective_quad_puts_the_gutter_ends_on_its_top_and_bottom_sides() {
    let quad = Quad([[120.0, 40.0], [700.0, 70.0], [740.0, 520.0], [90.0, 500.0]]);
    let [tl, tr, br, bl] = quad.0;
    let diagonal = (br[0] - tl[0]).hypot(br[1] - tl[1]);
    let s = split(&quad, &gutter(0.46, 0.54)).unwrap();
    let top_left_end = s.left.0[1];
    let top_right_end = s.right.0[0];
    let bottom_left_end = s.left.0[2];
    let bottom_right_end = s.right.0[3];
    let d_top = distance_to_segment(top_left_end, tl, tr).max(distance_to_segment(top_right_end, tl, tr));
    let d_bottom = distance_to_segment(bottom_left_end, bl, br).max(distance_to_segment(bottom_right_end, bl, br));
    println!("perspective: diagonal={diagonal:.3} top distance={d_top:e} bottom distance={d_bottom:e}");
    assert!(d_top < 1e-6 * diagonal, "top {d_top}");
    assert!(d_bottom < 1e-6 * diagonal, "bottom {d_bottom}");
    assert!(convex(&s.left) && convex(&s.right), "halves not convex: {:?} {:?}", s.left, s.right);
}

#[test]
fn a_gutter_at_zero_or_one_or_a_non_finite_share_gives_none() {
    let quad = square(100.0);
    assert_eq!(split(&quad, &gutter(0.0, 0.5)), None);
    assert_eq!(split(&quad, &gutter(0.5, 0.0)), None);
    assert_eq!(split(&quad, &gutter(1.0, 0.5)), None);
    assert_eq!(split(&quad, &gutter(0.5, 1.0)), None);
    assert_eq!(split(&quad, &gutter(0.004, 0.5)), None);
    assert_eq!(split(&quad, &gutter(0.5, f64::NAN)), None);
}

#[test]
fn a_gutter_found_in_a_drawn_spread_cuts_the_outline_where_the_valley_is() {
    let spread = a_spread(book(|_| 0.40, 0.35));
    let g = find_gutter(&spread).unwrap();
    let s = split(&square(100.0), &g).unwrap();
    println!("found 0.40 then split: left area={:.3} right area={:.3}", s.left.area(), s.right.area());
    // The left page is 40 % of the width less the 0.5 % overlap on its right side.
    assert!((s.left.area() - 3950.0).abs() < 60.0, "left {}", s.left.area());
}
