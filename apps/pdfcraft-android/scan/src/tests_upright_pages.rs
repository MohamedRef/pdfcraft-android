//! Tests for `upright_pages`: the neighbour rule of a multi-page scan, and the corner arithmetic
//! of a quarter turn. The turn's direction is checked against geometry, not against the
//! arithmetic it was written with.

use crate::upright_pages::{NEIGHBOUR_CONFIDENCE, PageTurn, followed, settle, total_turn, turn_back, turn_corners};
use pdfcraft_geom::perspective::{Homography, Point};

/// A page the network was sure of.
fn sure(quarter_turns: u8, confidence: f32) -> Option<PageTurn> {
    Some(PageTurn { quarter_turns, confidence, sure: true })
}

/// A page the network was not sure of.
fn unsure(quarter_turns: u8, confidence: f32) -> Option<PageTurn> {
    Some(PageTurn { quarter_turns, confidence, sure: false })
}

fn close(a: Point, b: Point) -> bool {
    (a[0] - b[0]).abs() < 1e-6 && (a[1] - b[1]).abs() < 1e-6
}

fn to_points(c: [f32; 8]) -> [Point; 4] {
    std::array::from_fn(|i| [f64::from(c[2 * i]), f64::from(c[2 * i + 1])])
}

#[test]
fn an_unsure_page_between_two_confident_agreeing_neighbours_takes_their_turn() {
    let pages = [sure(1, 0.97), unsure(3, 0.4), sure(1, 0.97)];
    let turns = settle(&pages);
    let follows = followed(&pages);
    println!("settle {turns:?}, followed {follows:?}");
    assert_eq!(turns, vec![1, 1, 1]);
    assert_eq!(follows, vec![false, true, false]);
}

#[test]
fn an_unsure_page_does_not_follow_a_neighbour_just_below_the_confidence_bar() {
    let pages = [sure(1, 0.94), unsure(3, 0.4), sure(1, 0.97)];
    let turns = settle(&pages);
    let follows = followed(&pages);
    println!("bar {NEIGHBOUR_CONFIDENCE}: settle {turns:?}, followed {follows:?}");
    assert_eq!(turns, vec![1, 0, 1]);
    assert_eq!(follows, vec![false, false, false]);
}

#[test]
fn a_neighbour_exactly_at_the_confidence_bar_is_confident_enough() {
    let pages = [sure(2, NEIGHBOUR_CONFIDENCE), unsure(0, 0.1), sure(2, 1.0)];
    assert_eq!(settle(&pages), vec![2, 2, 2]);
    assert_eq!(followed(&pages), vec![false, true, false]);
}

#[test]
fn neighbours_that_disagree_leave_the_unsure_page_at_zero() {
    let pages = [sure(1, 0.99), unsure(3, 0.4), sure(2, 0.99)];
    let turns = settle(&pages);
    println!("disagree: settle {turns:?}");
    assert_eq!(turns, vec![1, 0, 2]);
    assert_eq!(followed(&pages), vec![false, false, false]);
}

#[test]
fn two_unsure_pages_in_a_row_both_stay_at_zero() {
    let pages = [sure(1, 0.97), unsure(3, 0.4), unsure(3, 0.4), sure(1, 0.97)];
    assert_eq!(settle(&pages), vec![1, 0, 0, 1]);
    assert_eq!(followed(&pages), vec![false, false, false, false]);
}

#[test]
fn a_page_that_was_not_asked_about_gets_zero_and_does_not_break_the_list() {
    let pages = [sure(1, 0.97), None, sure(1, 0.97)];
    assert_eq!(settle(&pages), vec![1, 0, 1]);
    assert_eq!(followed(&pages), vec![false, false, false]);

    // An unsure page next to a page that was not asked about has no second neighbour to follow.
    let pages = [sure(1, 0.97), unsure(3, 0.4), None, sure(1, 0.97)];
    assert_eq!(settle(&pages), vec![1, 0, 0, 1]);
    assert_eq!(followed(&pages), vec![false, false, false, false]);
}

#[test]
fn the_first_and_last_page_never_follow_because_they_have_one_neighbour() {
    let pages = [unsure(1, 0.4), sure(1, 0.97), sure(1, 0.97)];
    assert_eq!(settle(&pages), vec![0, 1, 1]);
    assert_eq!(followed(&pages), vec![false, false, false]);

    let pages = [sure(1, 0.97), sure(1, 0.97), unsure(1, 0.4)];
    assert_eq!(settle(&pages), vec![1, 1, 0]);
    assert_eq!(followed(&pages), vec![false, false, false]);
}

#[test]
fn a_sure_page_keeps_its_own_turn_even_when_its_neighbours_are_unsure() {
    let pages = [unsure(3, 0.4), sure(2, 0.5), unsure(1, 0.4)];
    assert_eq!(settle(&pages), vec![0, 2, 0]);
    assert_eq!(followed(&pages), vec![false, false, false]);
}

#[test]
fn a_neighbour_with_nan_confidence_is_not_confident_enough() {
    let pages = [sure(1, f32::NAN), unsure(3, 0.4), sure(1, 0.97)];
    assert_eq!(settle(&pages), vec![1, 0, 1]);
    assert_eq!(followed(&pages), vec![false, false, false]);
}

#[test]
fn an_empty_scan_and_a_scan_of_one_page_settle_to_their_own_length() {
    assert_eq!(settle(&[]), Vec::<u8>::new());
    assert_eq!(followed(&[]), Vec::<bool>::new());
    assert_eq!(settle(&[sure(2, 0.99)]), vec![2]);
    assert_eq!(settle(&[unsure(2, 0.4)]), vec![0]);
    assert_eq!(settle(&[None]), vec![0]);
    assert_eq!(followed(&[unsure(2, 0.4)]), vec![false]);
}

#[test]
fn turning_by_four_is_the_identity_and_turning_by_one_then_three_is_the_identity() {
    let c = [10.0f32, 12.0, 200.0, 5.0, 210.0, 300.0, 0.0, 290.0];
    for k in [0u8, 4, 8] {
        assert_eq!(turn_corners(c, k), c, "by {k}");
    }
    assert_eq!(turn_corners(turn_corners(c, 1), 3), c);
    assert_eq!(turn_corners(turn_corners(c, 3), 1), c);
    assert_eq!(turn_corners(turn_corners(c, 2), 2), c);
}

#[test]
fn one_clockwise_turn_puts_the_bottom_left_corner_at_top_left() {
    // TL (10,12), TR (200,5), BR (210,300), BL (0,290), as x1,y1 … x4,y4.
    let c = [10.0f32, 12.0, 200.0, 5.0, 210.0, 300.0, 0.0, 290.0];
    let once = turn_corners(c, 1);
    println!("one turn: {once:?}");
    // New TL = old BL, new TR = old TL, new BR = old TR, new BL = old BR.
    assert_eq!(once, [0.0, 290.0, 10.0, 12.0, 200.0, 5.0, 210.0, 300.0]);
}

#[test]
fn two_turns_swap_opposite_corners() {
    let c = [10.0f32, 12.0, 200.0, 5.0, 210.0, 300.0, 0.0, 290.0];
    // New TL = old BR, new TR = old BL, new BR = old TL, new BL = old TR.
    assert_eq!(turn_corners(c, 2), [210.0, 300.0, 0.0, 290.0, 10.0, 12.0, 200.0, 5.0]);
    assert_eq!(turn_corners(c, 6), turn_corners(c, 2));
}

#[test]
fn turns_are_taken_modulo_four() {
    let c = [10.0f32, 12.0, 200.0, 5.0, 210.0, 300.0, 0.0, 290.0];
    assert_eq!(turn_corners(c, 5), turn_corners(c, 1));
    assert_eq!(turn_corners(c, 7), turn_corners(c, 3));
    assert_eq!(turn_corners(c, 255), turn_corners(c, 3));
}

#[test]
fn a_clockwise_turn_moves_each_photo_point_as_a_clockwise_quarter_turn_of_the_picture_does() {
    // The page as photographed: its corners, and a page of w x h once straightened.
    let corners = [10.0f32, 20.0, 330.0, 35.0, 320.0, 250.0, 5.0, 240.0];
    let (w, h) = (300.0, 200.0);
    let base = Homography::from_points(to_points(corners), [[0.0, 0.0], [w, 0.0], [w, h], [0.0, h]]).unwrap();
    for probe in [[100.0, 90.0], [250.0, 60.0], [40.0, 200.0]] {
        let (x0, y0) = {
            let [x, y] = base.apply(probe).unwrap();
            (x, y)
        };
        // Once, by hand: the point at (x, y) lands at (h - y, x) in the h x w page after a
        // clockwise quarter turn.
        let turned = turn_corners(corners, 1);
        let rect_after = [[0.0, 0.0], [h, 0.0], [h, w], [0.0, w]];
        let after = Homography::from_points(to_points(turned), rect_after).unwrap().apply(probe).unwrap();
        println!("probe {probe:?}: before ({x0:.3}, {y0:.3}), after one turn ({:.3}, {:.3})", after[0], after[1]);
        assert!(close(after, [h - y0, x0]), "one turn: {after:?} should be {:?}", [h - y0, x0]);

        // Then four turns in a row, each turn applied to the picture as the last left it: the
        // same rule each time, with the width and height swapped on odd turns.
        let (mut x, mut y, mut cw, mut ch) = (x0, y0, w, h);
        let mut c = corners;
        for k in 0..4u8 {
            let rect = [[0.0, 0.0], [cw, 0.0], [cw, ch], [0.0, ch]];
            let got = Homography::from_points(to_points(c), rect).unwrap().apply(probe).unwrap();
            assert!(close(got, [x, y]), "after {k} turns: {got:?} should be {:?}", [x, y]);
            (x, y) = (ch - y, x);
            (cw, ch) = (ch, cw);
            c = turn_corners(c, 1);
        }
    }
}

#[test]
fn turn_back_undoes_each_turn() {
    assert_eq!(turn_back(0), 0);
    assert_eq!(turn_back(1), 3);
    assert_eq!(turn_back(2), 2);
    assert_eq!(turn_back(3), 1);
    assert_eq!(turn_back(4), 0);
    for k in 0..4u8 {
        assert_eq!(total_turn(k, i32::from(turn_back(k))), 0, "turn {k}");
    }
}

#[test]
fn a_network_turn_and_presses_of_rotate_add_up_modulo_four() {
    assert_eq!(total_turn(3, 2), 1);
    assert_eq!(total_turn(0, -1), 3);
    assert_eq!(total_turn(1, -5), 0);
    assert_eq!(total_turn(0, 0), 0);
    assert_eq!(total_turn(2, 4), 2);
}

#[test]
fn extreme_presses_of_rotate_cannot_overflow_the_turn() {
    for user in [i32::MIN, i32::MAX, -1, 1] {
        for auto in [0u8, 1, 3, 255] {
            let t = total_turn(auto, user);
            println!("auto {auto}, user {user}: {t}");
            assert!(t < 4);
        }
    }
}
