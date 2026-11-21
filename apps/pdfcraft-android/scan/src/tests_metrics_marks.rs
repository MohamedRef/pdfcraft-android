//! The measures of marks on a page (#784, `metrics_marks`), on small pictures whose answers can
//! be worked out by hand.

use crate::metrics_marks::{
    PaperLevels, TILE, VISIBLE_DROP, chroma_kept, clipped, grown, mark_contrast, mark_kept, mean_chroma, stroke_weight, tone_kept,
};
use crate::{Luma, Rgb};

const W: usize = 3 * TILE;
const H: usize = 2 * TILE;

/// A page with paper at `left` in its left half and `right` in its right half, and a vertical
/// stroke `drop` under the paper in each half, `width` pixels wide.
fn page(left: u8, right: u8, drop: u8, width: usize) -> (Luma, Vec<bool>, Vec<bool>) {
    let mut data = vec![0u8; W * H];
    let (mut paper, mut stroke) = (vec![true; W * H], vec![false; W * H]);
    for y in 0..H {
        for x in 0..W {
            let level = if x < W / 2 { left } else { right };
            let on = (20..20 + width).contains(&x) || (W - 40..W - 40 + width).contains(&x);
            data[y * W + x] = if on { level - drop } else { level };
            stroke[y * W + x] = on;
            paper[y * W + x] = !on;
        }
    }
    (Luma { width: W as u32, height: H as u32, data }, paper, stroke)
}

#[test]
fn contrast_is_taken_against_the_paper_beside_the_mark() {
    // the same stroke on bright and on shaded paper: 60 under both
    let (g, paper, stroke) = page(240, 120, 60, 3);
    let levels = PaperLevels::new(&g, &paper).unwrap();
    assert_eq!(mark_contrast(&g, &levels, &stroke), Some(60.0));
    assert_eq!(mark_kept(&g, &levels, &stroke), Some(1.0));
    // blank paper is clean
    assert_eq!(mark_kept(&g, &levels, &paper), Some(0.0));
}

#[test]
fn a_mark_under_a_tenth_of_the_range_is_not_kept() {
    let under = (VISIBLE_DROP - 1.5) as u8;
    let (g, paper, stroke) = page(250, 250, under, 2);
    let levels = PaperLevels::new(&g, &paper).unwrap();
    assert_eq!(mark_kept(&g, &levels, &stroke), Some(0.0));
    let (g, paper, stroke) = page(250, 250, under + 3, 2);
    let levels = PaperLevels::new(&g, &paper).unwrap();
    assert_eq!(mark_kept(&g, &levels, &stroke), Some(1.0));
    // half the stroke's pixels faded to paper: half kept
    let (mut g, paper, stroke) = page(250, 250, 100, 2);
    for y in 0..H / 2 {
        for x in 0..W {
            if stroke[y * W + x] {
                g.data[y * W + x] = 250;
            }
        }
    }
    let levels = PaperLevels::new(&g, &paper).unwrap();
    assert_eq!(mark_kept(&g, &levels, &stroke), Some(0.5));
}

#[test]
fn a_tile_without_paper_takes_its_neighbours_level() {
    // the middle tile of the top row is all mark
    let (mut g, mut paper, _) = page(200, 200, 0, 0);
    let mut block = vec![false; W * H];
    for y in 0..TILE {
        for x in TILE..2 * TILE {
            g.data[y * W + x] = 90;
            paper[y * W + x] = false;
            block[y * W + x] = true;
        }
    }
    let levels = PaperLevels::new(&g, &paper).unwrap();
    assert_eq!(mark_contrast(&g, &levels, &block), Some(110.0));
    // no paper anywhere: nothing to measure against
    assert!(PaperLevels::new(&g, &vec![false; W * H]).is_none());
}

#[test]
fn stroke_weight_is_one_as_drawn_and_follows_fatter_and_thinner_strokes() {
    let (g, paper, stroke) = page(250, 250, 200, 4);
    let levels = PaperLevels::new(&g, &paper).unwrap();
    let around = grown(&stroke, W, H, 3);
    assert_eq!(stroke_weight(&g, &levels, &stroke, &around), Some(1.0));
    // the picture's strokes are 6 wide where 4 were drawn (the same left edge)
    let (fat, paper6, _) = page(250, 250, 200, 6);
    let levels = PaperLevels::new(&fat, &paper6).unwrap();
    assert_eq!(stroke_weight(&fat, &levels, &stroke, &around), Some(1.5));
    // and 2 wide: half
    let (thin, paper2, _) = page(250, 250, 200, 2);
    let levels = PaperLevels::new(&thin, &paper2).unwrap();
    // the median of the drawn stroke's pixels is paper now, so there is no contrast to halve
    assert!(stroke_weight(&thin, &levels, &stroke, &around).is_none());
    let (three, paper3, _) = page(250, 250, 200, 3);
    let levels = PaperLevels::new(&three, &paper3).unwrap();
    assert_eq!(stroke_weight(&three, &levels, &stroke, &around), Some(0.75));
}

fn flat(colour: [u8; 3]) -> Rgb {
    Rgb { width: 8, height: 8, data: std::iter::repeat_n(colour, 64).flatten().collect() }
}

#[test]
fn colour_kept_is_the_chroma_left_of_the_truths() {
    let all = vec![true; 64];
    assert_eq!(chroma_kept(&flat([40, 70, 190]), &flat([40, 70, 190]), &all), Some(1.0));
    assert_eq!(chroma_kept(&flat([40, 70, 190]), &flat([100, 100, 175]), &all), Some(0.5));
    assert_eq!(chroma_kept(&flat([40, 70, 190]), &flat([90, 90, 90]), &all), Some(0.0));
    // a truth without colour has none to keep
    assert!(chroma_kept(&flat([90, 90, 95]), &flat([40, 70, 190]), &all).is_none());
    assert_eq!(mean_chroma(&flat([250, 240, 230]), &all), Some(20.0));
    assert!(mean_chroma(&flat([250, 240, 230]), &[false; 64]).is_none());
    assert!(mean_chroma(&flat([250, 240, 230]), &[true; 3]).is_none());
}

#[test]
fn tone_kept_is_the_slope_of_the_look_over_the_truth() {
    let ramp: Vec<u8> = (0..64).map(|i| 40 + 2 * i as u8).collect();
    let truth = Luma { width: 8, height: 8, data: ramp.clone() };
    let of = |f: &dyn Fn(u8) -> u8| Luma { width: 8, height: 8, data: ramp.iter().map(|&v| f(v)).collect() };
    let all = vec![true; 64];
    let close = |got: Option<f64>, want: f64| assert!(got.is_some_and(|g| (g - want).abs() < 0.02), "{got:?} for {want}");
    close(tone_kept(&truth, &truth, &all), 1.0);
    close(tone_kept(&truth, &of(&|v| v / 2 + 30), &all), 0.5);
    close(tone_kept(&truth, &of(&|_| 128), &all), 0.0);
    // noise that does not follow the truth leaves the slope where it was
    let noisy = Luma { width: 8, height: 8, data: ramp.iter().enumerate().map(|(i, &v)| if i % 2 == 0 { v + 6 } else { v - 6 }).collect() };
    close(tone_kept(&truth, &noisy, &all), 1.0);
    // a flat truth has no tone to keep
    assert!(tone_kept(&of(&|_| 90), &truth, &all).is_none());
    assert!(tone_kept(&truth, &truth, &[true; 5]).is_none());
}

#[test]
fn clipped_counts_each_end_of_the_range() {
    let g = Luma { width: 4, height: 1, data: vec![0, 8, 9, 255] };
    let all = vec![true; 4];
    assert_eq!(clipped(&g, &all, 8, true), Some(0.5));
    assert_eq!(clipped(&g, &all, 250, false), Some(0.25));
    assert!(clipped(&g, &[false; 4], 8, true).is_none());
}

#[test]
fn grown_reaches_r_pixels_each_way_and_no_further() {
    let (w, h) = (9, 7);
    let mut mask = vec![false; w * h];
    mask[3 * w + 4] = true;
    let out = grown(&mask, w, h, 2);
    for y in 0..h {
        for x in 0..w {
            assert_eq!(out[y * w + x], x.abs_diff(4) <= 2 && y.abs_diff(3) <= 2, "{x} {y}");
        }
    }
    assert_eq!(grown(&mask, w, h, 0), mask);
    // a mask that does not fit comes back as it is
    assert_eq!(grown(&[true, false], 3, 3, 1), vec![true, false]);
}
