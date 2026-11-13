//! Tests for `flatten_guard` (#302, spec S16 sections 3 and 5). Pages are drawn in code.

use crate::Rgb;
use crate::flatten_guard::{GAIN, Kept, Trim, Why, border_trim, guard, trimmed};

const WHITE: [u8; 3] = [255, 255, 255];
const CREAM: [u8; 3] = [250, 245, 225];
const BLUE: [u8; 3] = [40, 70, 160];
const INK: [u8; 3] = [20, 20, 20];

/// A page of one colour.
fn solid(width: u32, height: u32, colour: [u8; 3]) -> Rgb {
    let n = width as usize * height as usize;
    Rgb { width, height, data: (0..n).flat_map(|_| colour).collect() }
}

/// Paints the rectangle `[x0, x1) × [y0, y1)` of `page` with `colour`.
fn paint(page: &mut Rgb, (x0, x1): (u32, u32), (y0, y1): (u32, u32), colour: [u8; 3]) {
    let w = page.width as usize;
    for y in y0..y1 {
        for x in x0..x1 {
            let i = (y as usize * w + x as usize) * 3;
            if let Some(s) = page.data.get_mut(i..i + 3) {
                s.copy_from_slice(&colour);
            }
        }
    }
}

/// The pixel at `(x, y)`, or 255 in every channel when out of range.
fn pixel(page: &Rgb, x: u32, y: u32) -> [u8; 3] {
    let i = (y as usize * page.width as usize + x as usize) * 3;
    match page.data.get(i..i + 3) {
        Some(p) => [p[0], p[1], p[2]],
        None => [255; 3],
    }
}

#[test]
fn a_flattened_page_at_4_4_px_against_5_0_px_is_kept() {
    let g = guard(Some(5.0), Some(4.4));
    println!("guard(5.0, 4.4) = {g:?}");
    assert_eq!(g.kept, Kept::Flattened);
    assert_eq!(g.why, Why::Straighter);
}

#[test]
fn a_flattened_page_at_4_6_px_against_5_0_px_is_not_kept() {
    let g = guard(Some(5.0), Some(4.6));
    println!("guard(5.0, 4.6) = {g:?}");
    assert_eq!(g.kept, Kept::Perspective);
    assert_eq!(g.why, Why::NotStraighter);
}

#[test]
fn a_flattened_page_at_exactly_the_gain_is_kept() {
    let g = guard(Some(5.0), Some(5.0 * GAIN));
    println!("guard(5.0, 5.0 * GAIN) = {g:?}");
    assert_eq!(g.kept, Kept::Flattened);
}

#[test]
fn a_missing_figure_on_either_side_keeps_the_perspective_page() {
    for g in [guard(None, Some(4.0)), guard(Some(5.0), None), guard(None, None)] {
        println!("missing figure: {g:?}");
        assert_eq!(g.kept, Kept::Perspective);
        assert_eq!(g.why, Why::Unmeasured);
    }
}

#[test]
fn nan_infinite_and_negative_figures_count_as_unmeasured() {
    let bad = [f64::NAN, f64::INFINITY, f64::NEG_INFINITY, -1.0];
    for v in bad {
        let a = guard(Some(v), Some(4.0));
        let b = guard(Some(5.0), Some(v));
        println!("bad figure {v}: {a:?} / {b:?}");
        assert_eq!((a.kept, a.why), (Kept::Perspective, Why::Unmeasured));
        assert_eq!((b.kept, b.why), (Kept::Perspective, Why::Unmeasured));
    }
}

#[test]
fn two_figures_under_half_a_pixel_keep_the_perspective_page() {
    let g = guard(Some(0.4), Some(0.1));
    println!("guard(0.4, 0.1) = {g:?}");
    assert_eq!(g.kept, Kept::Perspective);
    assert_eq!(g.why, Why::AlreadyStraight);
}

#[test]
fn one_figure_under_half_a_pixel_still_needs_the_gain() {
    let g = guard(Some(0.4), Some(0.6));
    println!("guard(0.4, 0.6) = {g:?}");
    assert_eq!(g.kept, Kept::Perspective);
    assert_eq!(g.why, Why::NotStraighter);
}

#[test]
fn the_reason_words_are_short_lowercase_and_distinct() {
    let ids = [Why::Straighter, Why::NotStraighter, Why::Unmeasured, Why::AlreadyStraight, Why::FewLines, Why::MuchStraighter].map(Why::id);
    println!("reason words: {ids:?}");
    for (i, a) in ids.iter().enumerate() {
        assert!(a.len() <= 12 && a.chars().all(|c| c.is_ascii_lowercase()));
        for b in &ids[i + 1..] {
            assert_ne!(a, b);
        }
    }
}

#[test]
fn a_clean_page_is_not_trimmed() {
    let t = border_trim(&solid(400, 300, WHITE));
    println!("clean page trim = {t:?}");
    assert_eq!(t, Trim::default());
}

#[test]
fn a_dark_strip_1_percent_wide_on_the_left_is_trimmed_by_its_width() {
    let mut page = solid(400, 300, WHITE);
    paint(&mut page, (0, 4), (0, 300), INK);
    let t = border_trim(&page);
    println!("1 % strip (4 px of 400) trim = {t:?}");
    assert!(t.left.abs_diff(4) <= 4);
    assert_eq!((t.top, t.right, t.bottom), (0, 0, 0));
}

#[test]
fn a_strip_5_percent_wide_is_trimmed_by_2_percent_at_most() {
    let mut page = solid(400, 300, WHITE);
    paint(&mut page, (0, 20), (0, 300), INK);
    let t = border_trim(&page);
    println!("5 % strip (20 px of 400) trim = {t:?} (2 % cap = 8 px)");
    assert!(t.left <= 8);
    assert_eq!(t.left, 8);
    assert_eq!((t.top, t.right, t.bottom), (0, 0, 0));
}

#[test]
fn text_reaching_the_edge_is_not_trimmed() {
    // Six full-width lines of ink, each 4 px tall: on the edge columns they cover about 8 % of
    // the reduced pixels, so the edge still looks like page.
    let mut page = solid(400, 300, WHITE);
    for y in [40, 80, 120, 160, 200, 240] {
        paint(&mut page, (0, 400), (y, y + 4), INK);
    }
    let t = border_trim(&page);
    println!("text reaching both edges trim = {t:?}");
    assert_eq!(t, Trim::default());
}

#[test]
fn a_cream_page_on_a_blue_table_strip_is_trimmed() {
    let mut page = solid(400, 300, CREAM);
    paint(&mut page, (0, 8), (0, 300), BLUE);
    let t = border_trim(&page);
    println!("cream page, blue strip 8 px on the left: trim = {t:?}");
    assert_eq!(t.left, 8);
    assert_eq!((t.top, t.right, t.bottom), (0, 0, 0));
}

#[test]
fn strips_on_all_four_sides_are_trimmed_at_once() {
    let mut page = solid(400, 300, CREAM);
    paint(&mut page, (0, 4), (0, 300), BLUE);
    paint(&mut page, (392, 400), (0, 300), BLUE);
    paint(&mut page, (0, 400), (0, 3), BLUE);
    paint(&mut page, (0, 400), (294, 300), BLUE);
    let t = border_trim(&page);
    println!("four strips (4 px left, 8 px right, 3 px top, 6 px bottom): trim = {t:?}");
    assert_eq!(t.left, 4);
    assert_eq!(t.right, 8);
    assert!(t.top.abs_diff(3) <= 4);
    assert_eq!(t.bottom, 6);
}

#[test]
fn trimmed_gives_the_right_size_and_pixels() {
    let mut page = solid(10, 6, WHITE);
    for y in 0..6u32 {
        for x in 0..10u32 {
            paint(&mut page, (x, x + 1), (y, y + 1), [(x * 20) as u8, (y * 30) as u8, (x + y) as u8]);
        }
    }
    let trim = Trim { left: 1, top: 2, right: 3, bottom: 1 };
    let out = trimmed(&page, trim);
    println!("trimmed 10x6 by {trim:?}: {}x{}, {} bytes", out.width, out.height, out.data.len());
    assert_eq!((out.width, out.height), (6, 3));
    assert_eq!(out.data.len(), 6 * 3 * 3);
    for y in 0..3 {
        for x in 0..6 {
            assert_eq!(pixel(&out, x, y), pixel(&page, x + 1, y + 2));
        }
    }
}

#[test]
fn trimming_nothing_or_everything_returns_the_page() {
    let page = solid(10, 6, CREAM);
    assert_eq!(trimmed(&page, Trim::default()), page);
    assert_eq!(trimmed(&page, Trim { left: 10, top: 0, right: 0, bottom: 0 }), page);
    assert_eq!(trimmed(&page, Trim { left: 6, top: 0, right: 5, bottom: 0 }), page);
    assert_eq!(trimmed(&page, Trim { left: 0, top: 3, right: 0, bottom: 3 }), page);
}

#[test]
fn a_1_by_1_page_does_not_panic() {
    let light = solid(1, 1, WHITE);
    let dark = solid(1, 1, INK);
    let t1 = border_trim(&light);
    let t2 = border_trim(&dark);
    let out = trimmed(&light, Trim { left: 1, top: 0, right: 0, bottom: 0 });
    println!("1x1: trims {t1:?} {t2:?}, trimmed {}x{}", out.width, out.height);
    assert_eq!(t1, Trim::default());
    assert_eq!(t2, Trim::default());
    assert_eq!(out, light);
}

#[test]
fn a_page_whose_data_does_not_match_its_size_is_left_alone() {
    let bad = Rgb { width: 4, height: 4, data: vec![0; 10] };
    let t = border_trim(&bad);
    let out = trimmed(&bad, Trim { left: 1, top: 1, right: 1, bottom: 1 });
    println!("bad data: trim {t:?}, trimmed length {}", out.data.len());
    assert_eq!(t, Trim::default());
    assert_eq!(out, bad);
}

// The floor on lines followed (#302): the review on the owner's reference photos found readings
// on 3 or 4 lines deciding a page.

#[test]
fn a_straighter_page_on_too_few_lines_is_not_kept() {
    use crate::flatten_guard::{MIN_LINES, guard_with_floor};
    // 10 → 5 px is clearly straighter: kept on 8 lines each, not on 3 (the review's weakest case)
    let enough = guard_with_floor(Some(10.0), Some(5.0), (Some(8), Some(8)), MIN_LINES);
    let few = guard_with_floor(Some(10.0), Some(5.0), (Some(3), Some(30)), MIN_LINES);
    let few_flat = guard_with_floor(Some(10.0), Some(6.0), (Some(30), Some(7)), MIN_LINES);
    println!("8/8: {enough:?}, 3/30: {few:?}, 30/7: {few_flat:?}");
    assert_eq!(enough.kept, Kept::Flattened);
    assert_eq!((few.kept, few.why), (Kept::Perspective, Why::FewLines));
    assert_eq!((few_flat.kept, few_flat.why), (Kept::Perspective, Why::FewLines));
    assert_eq!(Why::FewLines.id(), "fewlines");
    // no floor: the guard as before
    assert_eq!(guard_with_floor(Some(10.0), Some(5.0), (Some(3), Some(3)), 0), guard(Some(10.0), Some(5.0)));
    // a page the guard would not keep keeps its own reason
    assert_eq!(guard_with_floor(Some(10.0), Some(9.5), (Some(3), Some(3)), MIN_LINES).why, Why::NotStraighter);
    assert_eq!(guard_with_floor(None, Some(5.0), (None, Some(30)), MIN_LINES).why, Why::Unmeasured);
    // far straighter (half or less) on at least 4 lines each is kept under the floor (#466)
    use crate::flatten_guard::{FEW_GAIN, FEW_LINES};
    let much = guard_with_floor(Some(12.2), Some(4.4), (Some(6), Some(7)), MIN_LINES);
    assert_eq!((much.kept, much.why, much.why.id()), (Kept::Flattened, Why::MuchStraighter, "muchbetter"));
    assert_eq!(guard_with_floor(Some(10.0), Some(10.0 * FEW_GAIN), (Some(FEW_LINES), Some(FEW_LINES)), MIN_LINES).kept, Kept::Flattened);
    assert_eq!(guard_with_floor(Some(10.0), Some(10.0 * FEW_GAIN + 0.1), (Some(7), Some(7)), MIN_LINES).why, Why::FewLines);
    assert_eq!(guard_with_floor(Some(10.0), Some(2.0), (Some(FEW_LINES - 1), Some(30)), MIN_LINES).why, Why::FewLines);
    assert_eq!(guard_with_floor(Some(10.0), Some(2.0), (Some(30), Some(FEW_LINES - 1)), MIN_LINES).why, Why::FewLines);
    // with enough lines the ordinary reason is given, however large the gain
    assert_eq!(guard_with_floor(Some(10.0), Some(2.0), (Some(8), Some(8)), MIN_LINES).why, Why::Straighter);
    // unmeasured line counts never pass the floor
    assert_eq!(guard_with_floor(Some(10.0), Some(5.0), (None, Some(30)), MIN_LINES).why, Why::FewLines);
}

// The guard measures with the light evened out (#466): under a shadow the lines of text cannot
// be followed, and a page with a shadow is exactly the kind that is photographed bent.

/// A 600 × 840 page of 16 lines of word bars, each line `lean` px lower at the right than at the
/// left, with the light falling to `shade` of its brightness over the page's lower right part,
/// across a diagonal edge 60 px soft (a shadow cast from some way off).
fn shaded_text(lean: f64, shade: f64) -> Rgb {
    let (w, h) = (600usize, 840usize);
    let mut data = vec![0u8; w * h * 3];
    for y in 0..h {
        for x in 0..w {
            let t = x as f64 / w as f64;
            let row = y as f64 - lean * t;
            let line = ((row - 60.0) / 45.0).floor();
            let in_line = (0.0..16.0).contains(&line) && (row - 60.0 - line * 45.0) < 14.0;
            let word = (40..w - 40).contains(&x) && (x + 13 * line as usize) % 80 < 62;
            let ink = in_line && word;
            let into = (((x as f64) * 0.9 + (y as f64) - 590.0) / 60.0).clamp(0.0, 1.0);
            let light = 1.0 + (shade - 1.0) * into * into * (3.0 - 2.0 * into);
            let v = (if ink { 40.0 } else { 236.0 } * light).round() as u8;
            data[(y * w + x) * 3..(y * w + x) * 3 + 3].copy_from_slice(&[v, v, v]);
        }
    }
    Rgb { width: w as u32, height: h as u32, data }
}

#[test]
fn lines_under_a_shadow_are_followed_once_the_light_is_evened() {
    use crate::page::evened_grey;
    use crate::straightness::line_straightness;
    let plain_grey = |page: &Rgb| crate::Luma { width: page.width, height: page.height, data: page.data.chunks_exact(3).map(|p| p[0]).collect() };
    let lit = shaded_text(0.0, 1.0);
    let shaded = shaded_text(0.0, 0.5);
    let all = line_straightness(&plain_grey(&lit)).expect("a plain page of text");
    assert_eq!(all.lines, 16);
    // at half the light the shadowed paper is darker than the level between the page's ink and
    // its lit paper, so the whole shadow counts as ink and the lines in it are lost
    let lost = line_straightness(&plain_grey(&shaded));
    println!("lit: {all:?}; shaded, plain grey: {lost:?}");
    assert!(lost.is_none_or(|m| m.lines < 12), "{lost:?}");
    let evened = line_straightness(&evened_grey(&shaded)).expect("the evened page");
    let lit_evened = line_straightness(&evened_grey(&lit)).expect("the lit page, evened");
    println!("shaded, evened: {evened:?}; lit, evened: {lit_evened:?}");
    assert!(evened.lines >= 15, "{evened:?}");
    assert!(evened.px < 1.5 && lit_evened.px < 1.0, "{evened:?} {lit_evened:?}");
    // evening does not hide a lean: 12 px across the page reads about 3.5 px, shadow or not
    let leaning = line_straightness(&evened_grey(&shaded_text(12.0, 0.5))).expect("the leaning page");
    println!("leaning 12 px, shaded, evened: {leaning:?}");
    assert!((2.5..4.5).contains(&leaning.px) && leaning.lines >= 15, "{leaning:?}");
}

#[test]
fn the_guard_chooses_between_two_shaded_pages_by_their_lines() {
    use crate::compare_flattened;
    use crate::flatten_guard::MIN_LINES;
    // the page straightened by its corners leans; the flattened one is level: kept, under the
    // same shadow that hid the lines from the plain measure
    let check = compare_flattened(&shaded_text(12.0, 0.5), &shaded_text(0.0, 0.5), MIN_LINES);
    println!("{check:?}");
    assert!(check.kept && check.why == "straighter", "{check:?}");
    assert!(check.straightened.is_some_and(|m| m.lines >= 15) && check.flattened.is_some_and(|m| m.lines >= 15));
    // and the other way round it is not
    let check = compare_flattened(&shaded_text(0.0, 0.5), &shaded_text(12.0, 0.5), MIN_LINES);
    assert!(!check.kept, "{check:?}");
    // what is far darker than a shadow is not evened: a table at a fifth of the paper's level
    // beside the page stays dark
    let mut beside = shaded_text(0.0, 1.0);
    for y in 0..840usize {
        for x in 480..600usize {
            beside.data[(y * 600 + x) * 3..(y * 600 + x) * 3 + 3].copy_from_slice(&[47, 47, 47]);
        }
    }
    let evened = crate::page::evened_grey(&beside);
    assert!(evened.data[400 * 600 + 560] < 120, "{}", evened.data[400 * 600 + 560]);
    // a tiny page comes back as plain grey and is measured as before
    let tiny = Rgb { width: 8, height: 8, data: vec![200; 8 * 8 * 3] };
    assert_eq!(crate::page::evened_grey(&tiny).data, vec![200u8; 64]);
}
