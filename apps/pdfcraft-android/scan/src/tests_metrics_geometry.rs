//! Tests for `metrics_geometry` (issue #288). Each input is built here, and each test asserts
//! measured numbers and prints them.

use crate::Quad;
use crate::metrics_geometry::{CornerError, aspect_err, corner_err, iou, no_touch};

/// Tolerance for results computed in floating point.
const EPS: f64 = 1e-12;

fn rect(x0: f64, y0: f64, x1: f64, y1: f64) -> Quad {
    Quad([[x0, y0], [x1, y0], [x1, y1], [x0, y1]])
}

fn unit_square() -> Quad {
    rect(0.0, 0.0, 1.0, 1.0)
}

#[test]
fn the_same_quad_has_an_overlap_of_exactly_one() {
    let q = Quad([[10.0, 12.0], [200.0, 5.0], [210.0, 300.0], [0.0, 290.0]]);
    let v = iou(&q, &q);
    println!("iou(q, q) = {v}");
    assert!((v - 1.0).abs() < EPS, "{v}");
}

#[test]
fn quads_that_do_not_touch_have_an_overlap_of_zero() {
    let v = iou(&unit_square(), &rect(5.0, 5.0, 6.0, 6.0));
    println!("iou of apart squares = {v}");
    assert_eq!(v, 0.0);
}

#[test]
fn a_unit_square_moved_half_its_width_has_an_overlap_of_one_third() {
    // Overlap 0.5, union 1.5.
    let v = iou(&unit_square(), &rect(0.5, 0.0, 1.5, 1.0));
    println!("iou of unit square moved by 0.5 = {v}");
    assert!((v - 1.0 / 3.0).abs() < EPS, "{v}");
}

#[test]
fn a_square_turned_forty_five_degrees_about_its_centre_matches_the_octagon_formula() {
    // Side 2 square against itself turned 45 degrees: the overlap is an octagon of area
    // 8(sqrt2 - 1), the union is 8 - 8(sqrt2 - 1).
    let s2 = 2f64.sqrt();
    let square = Quad([[-1.0, -1.0], [1.0, -1.0], [1.0, 1.0], [-1.0, 1.0]]);
    let turned = Quad([[0.0, -s2], [s2, 0.0], [0.0, s2], [-s2, 0.0]]);
    let overlap = 8.0 * (s2 - 1.0);
    let expected = overlap / (8.0 - overlap);
    let v = iou(&square, &turned);
    println!("iou of square and its 45 degree turn = {v} (formula {expected})");
    assert!((v - expected).abs() < 1e-12, "{v} vs {expected}");
    assert!((v - std::f64::consts::FRAC_1_SQRT_2).abs() < 1e-12, "{v}");
}

#[test]
fn overlap_does_not_depend_on_which_quad_comes_first() {
    let a = Quad([[0.0, 0.0], [10.0, 1.0], [9.0, 8.0], [1.0, 7.0]]);
    let b = rect(4.0, 2.0, 14.0, 9.0);
    let (ab, ba) = (iou(&a, &b), iou(&b, &a));
    println!("iou(a, b) = {ab}, iou(b, a) = {ba}");
    assert!((ab - ba).abs() < EPS, "{ab} vs {ba}");
    assert!(ab > 0.0 && ab < 1.0, "{ab}");
}

#[test]
fn starting_the_corner_list_elsewhere_or_reversing_it_changes_nothing() {
    let a = Quad([[0.0, 0.0], [10.0, 1.0], [9.0, 8.0], [1.0, 7.0]]);
    let b = rect(4.0, 2.0, 14.0, 9.0);
    let reference = iou(&a, &b);
    let shifts = [Quad([a.0[1], a.0[2], a.0[3], a.0[0]]), Quad([a.0[2], a.0[3], a.0[0], a.0[1]]), Quad([a.0[3], a.0[0], a.0[1], a.0[2]])];
    for s in &shifts {
        let v = iou(s, &b);
        assert!((v - reference).abs() < EPS, "{v} vs {reference}");
    }
    let reversed = Quad([a.0[0], a.0[3], a.0[2], a.0[1]]);
    let v = iou(&reversed, &b);
    println!("iou with another start = {v}, reversed winding = {v}, reference = {reference}");
    assert!((v - reference).abs() < EPS, "{v} vs {reference}");
}

#[test]
fn a_quad_inside_another_has_an_overlap_equal_to_the_ratio_of_their_areas() {
    // Inner area 4, outer area 16: overlap 4 / union 16.
    let v = iou(&rect(1.0, 1.0, 3.0, 3.0), &rect(0.0, 0.0, 4.0, 4.0));
    println!("iou of inner square = {v}");
    assert!((v - 0.25).abs() < EPS, "{v}");
}

#[test]
fn concave_flat_or_non_finite_quads_give_an_overlap_of_zero() {
    let good = unit_square();
    let dart = Quad([[0.0, 0.0], [100.0, 0.0], [20.0, 20.0], [0.0, 100.0]]);
    let bow_tie = Quad([[0.0, 0.0], [10.0, 10.0], [10.0, 0.0], [0.0, 10.0]]);
    let flat = Quad([[0.0, 0.0], [10.0, 0.0], [20.0, 0.0], [30.0, 0.0]]);
    let nan = Quad([[f64::NAN, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 1.0]]);
    let inf = Quad([[0.0, 0.0], [f64::INFINITY, 0.0], [1.0, 1.0], [0.0, 1.0]]);
    let huge = rect(-1e308, -1e308, 1e308, 1e308);
    for (name, q) in [("dart", dart), ("bow tie", bow_tie), ("flat", flat), ("nan", nan), ("inf", inf), ("overflow", huge)] {
        let v = iou(&good, &q);
        println!("iou with {name} = {v}");
        assert_eq!(v, 0.0, "{name}");
    }
}

#[test]
fn a_4000_by_3000_photo_with_one_corner_moved_25_px_has_the_stated_corner_error() {
    let truth = rect(100.0, 100.0, 3900.0, 2900.0);
    let mut found = truth;
    found.0[2][0] += 25.0;
    let e = corner_err(&found, &truth, 4000, 3000);
    println!("corner_err 25 px: mean = {} max = {} no_touch = {}", e.mean, e.max, no_touch(&e));
    assert!((e.max - 0.005).abs() < EPS, "{}", e.max);
    assert!((e.mean - 0.00125).abs() < EPS, "{}", e.mean);
    assert!(no_touch(&e));
}

#[test]
fn a_corner_moved_26_px_on_a_4000_by_3000_photo_needs_touching() {
    let truth = rect(100.0, 100.0, 3900.0, 2900.0);
    let mut found = truth;
    found.0[2][0] += 26.0;
    let e = corner_err(&found, &truth, 4000, 3000);
    println!("corner_err 26 px: mean = {} max = {} no_touch = {}", e.mean, e.max, no_touch(&e));
    assert!((e.max - 0.0052).abs() < EPS, "{}", e.max);
    assert!(!no_touch(&e));
}

#[test]
fn corner_error_pairs_each_corner_with_the_best_cyclic_shift() {
    // The same four corners, listed starting from another corner: the error is zero.
    let truth = rect(0.0, 0.0, 400.0, 300.0);
    let found = Quad([truth.0[2], truth.0[3], truth.0[0], truth.0[1]]);
    let e = corner_err(&found, &truth, 400, 300);
    println!("corner_err shifted list: mean = {} max = {}", e.mean, e.max);
    assert!(e.mean.abs() < EPS && e.max.abs() < EPS, "{e:?}");
}

#[test]
fn corner_error_on_a_zero_sized_photo_or_with_a_non_finite_corner_is_infinite() {
    let truth = unit_square();
    let found = rect(0.0, 0.0, 2.0, 2.0);
    for (w, h) in [(0, 300), (400, 0), (0, 0)] {
        let e = corner_err(&found, &truth, w, h);
        assert!(e.mean.is_infinite() && e.max.is_infinite(), "{w}x{h}: {e:?}");
    }
    let nan = Quad([[f64::NAN, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 1.0]]);
    let e = corner_err(&nan, &truth, 400, 300);
    println!("corner_err with a NaN corner: mean = {} max = {}", e.mean, e.max);
    assert!(e.mean.is_infinite() && e.max.is_infinite());
}

#[test]
fn no_touch_is_false_for_a_non_finite_maximum() {
    let e = CornerError { mean: f64::NAN, max: f64::NAN };
    assert!(!no_touch(&e));
}

#[test]
fn aspect_error_of_1_5_against_1_414_is_about_0_0608() {
    let v = aspect_err(1.5, 1.414);
    println!("aspect_err(1.5, 1.414) = {v}");
    assert!((v - 0.0608).abs() < 1e-4, "{v}");
    assert!((v - 0.086 / 1.414).abs() < EPS, "{v}");
}

#[test]
fn aspect_error_is_zero_for_a_perfect_ratio_and_infinite_for_a_bad_truth() {
    assert_eq!(aspect_err(1.0, 1.0), 0.0);
    for truth in [0.0, -1.0, f64::NAN, f64::INFINITY] {
        let v = aspect_err(1.0, truth);
        println!("aspect_err(1.0, {truth}) = {v}");
        assert!(v.is_infinite(), "{truth}: {v}");
    }
    assert!(aspect_err(f64::NAN, 1.0).is_infinite());
}
