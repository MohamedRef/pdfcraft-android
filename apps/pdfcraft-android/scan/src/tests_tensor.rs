//! Tests for the shared tensor helpers (#289, section 2), and a check that the detector reads its
//! maps exactly as it did before the helpers were taken out of it.

use crate::detect::{CELLS, SIDE, read};
use crate::tensor::{ResizeMode, argmax, argmax_2d, planes, resize_rgb, rgb_to_nchw_f32, sigmoid, soft_argmax_window};

/// A repeatable sequence of logits in about -8..8, with ties now and then.
fn logits(seed: u64, n: usize) -> Vec<f32> {
    let mut s = seed.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
    (0..n)
        .map(|_| {
            s = s.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
            // coarse steps, so equal values (ties for the peak) happen
            (((s >> 33) % 64) as f32 - 32.0) / 4.0
        })
        .collect()
}

/// `detect::read`'s corner reading as it was written before #289's `tensor.rs`, kept here as the
/// reference (word for word, so the lint it raised then is allowed here).
#[allow(clippy::chunks_exact_to_as_chunks)]
fn corners_before(heat: &[f32]) -> ([[f64; 2]; 4], f32) {
    let cell = (SIDE / CELLS) as f64;
    let mut corners = [[0.0; 2]; 4];
    let mut surest_less = 1.0f32;
    for (k, map) in heat.chunks_exact(CELLS * CELLS).enumerate().take(4) {
        let peak = map.iter().enumerate().max_by(|a, b| a.1.total_cmp(b.1)).map_or(0, |(i, _)| i);
        let (px, py) = (peak % CELLS, peak / CELLS);
        surest_less = surest_less.min(sigmoid(map[peak]));
        let (mut sum, mut sx, mut sy) = (0.0f64, 0.0f64, 0.0f64);
        for y in py.saturating_sub(2)..=(py + 2).min(CELLS - 1) {
            for x in px.saturating_sub(2)..=(px + 2).min(CELLS - 1) {
                let p = f64::from(sigmoid(map[y * CELLS + x]));
                sum += p;
                sx += p * x as f64;
                sy += p * y as f64;
            }
        }
        corners[k] = [(sx / sum.max(1e-12) + 0.5) * cell, (sy / sum.max(1e-12) + 0.5) * cell];
    }
    (corners, surest_less)
}

#[test]
fn the_detector_reads_every_map_bit_for_bit_as_before() {
    let page = vec![1.0f32; CELLS * CELLS];
    for seed in 0..200 {
        let mut heat = logits(seed, 4 * CELLS * CELLS);
        // peaks on the borders and in the corners of the map too
        if seed % 4 == 1 {
            heat[0] = 50.0;
        }
        if seed % 4 == 2 {
            heat[CELLS * CELLS + CELLS * CELLS - 1] = 50.0;
        }
        if seed % 4 == 3 {
            heat[2 * CELLS * CELLS + CELLS - 1] = 50.0;
        }
        let (corners, surest) = corners_before(&heat);
        let now = read(&heat, &page);
        for (k, (a, b)) in now.corners.iter().zip(&corners).enumerate() {
            assert_eq!(a[0].to_bits(), b[0].to_bits(), "seed {seed}, corner {k}");
            assert_eq!(a[1].to_bits(), b[1].to_bits(), "seed {seed}, corner {k}");
        }
        assert_eq!(now.surest_less.to_bits(), surest.to_bits(), "seed {seed}");
    }
}

#[test]
fn argmax_takes_the_last_of_equal_peaks_and_reads_cells() {
    assert_eq!(argmax(&[1.0, 3.0, 2.0, 3.0]), 3);
    assert_eq!(argmax(&[]), 0);
    assert_eq!(argmax_2d(&[0.0, 0.0, 0.0, 0.0, 0.0, 9.0], 3), (2, 1));
}

#[test]
fn soft_argmax_finds_the_centre_and_stays_in_the_map() {
    // a symmetric bump around (2, 2) in a 5 × 5 map
    let mut map = vec![-10.0f32; 25];
    for (x, y, v) in [(2, 2, 4.0), (1, 2, 1.0), (3, 2, 1.0), (2, 1, 1.0), (2, 3, 1.0)] {
        map[y * 5 + x] = v;
    }
    let [cx, cy] = soft_argmax_window(&map, 5, 5, (2, 2), 2);
    assert!((cx - 2.0).abs() < 1e-9 && (cy - 2.0).abs() < 1e-9, "{cx} {cy}");
    // at the corner the window is cut by the map's edge, never read outside it
    let [cx, cy] = soft_argmax_window(&map, 5, 5, (0, 0), 2);
    assert!((0.0..=2.0).contains(&cx) && (0.0..=2.0).contains(&cy));
    // a short map gives no panic
    let [cx, _] = soft_argmax_window(&[0.0; 3], 5, 5, (4, 4), 2);
    assert!(cx.is_finite());
}

#[test]
fn planes_are_normalised_and_split_by_channel() {
    let rgb = [255u8, 0, 51, 0, 255, 102];
    let out = rgb_to_nchw_f32(&rgb, 2, 1, [0.5, 0.5, 0.0], [0.5, 0.5, 1.0]);
    assert_eq!(out.len(), 6);
    // R plane, G plane, B plane
    assert_eq!(&out[..2], &[1.0, -1.0]);
    assert_eq!(&out[2..4], &[-1.0, 1.0]);
    assert!((out[4] - 0.2).abs() < 1e-6 && (out[5] - 0.4).abs() < 1e-6);
    assert_eq!(planes(&[0.25, 0.5, 0.75], 1, 1, [0.0; 3], [1.0; 3]), vec![0.25, 0.5, 0.75]);
}

fn grey(w: usize, h: usize, v: impl Fn(usize, usize) -> u8) -> Vec<u8> {
    let mut out = Vec::with_capacity(w * h * 3);
    for y in 0..h {
        for x in 0..w {
            out.extend_from_slice(&[v(x, y); 3]);
        }
    }
    out
}

#[test]
fn shrinking_averages_by_area_and_growing_interpolates() {
    // 4 × 2 of 0, 255 columns → 2 × 1: each output is the mean of a 0 and a 255
    let src = grey(4, 2, |x, _| if x % 2 == 0 { 0 } else { 255 });
    let (small, map) = resize_rgb(&src, 4, 2, 2, 1, ResizeMode::Stretch).unwrap();
    assert!(small.rgb.iter().all(|v| (v - 0.5).abs() < 1e-6), "{:?}", small.rgb);
    assert_eq!(map.scale, [0.5, 0.5]);
    // 2 × 1 of 0, 255 → 4 × 1: the ends keep their values, the middle is between
    let src = grey(2, 1, |x, _| if x == 0 { 0 } else { 255 });
    let (big, _) = resize_rgb(&src, 2, 1, 4, 1, ResizeMode::Stretch).unwrap();
    let row: Vec<f32> = big.rgb.iter().step_by(3).copied().collect();
    assert_eq!(row[0], 0.0);
    assert_eq!(row[3], 1.0);
    assert!(row[1] > 0.0 && row[1] < row[2] && row[2] < 1.0, "{row:?}");
}

#[test]
fn letterbox_keeps_the_shape_centres_it_and_maps_points_back() {
    // a white 4 × 2 picture into 8 × 8: 8 × 4 inside, two rows of black above and below
    let src = grey(4, 2, |_, _| 255);
    let (out, map) = resize_rgb(&src, 4, 2, 8, 8, ResizeMode::Letterbox).unwrap();
    assert_eq!((out.w, out.h), (8, 8));
    assert_eq!(map.offset, [0.0, 2.0]);
    let at = |x: usize, y: usize| out.rgb[(y * 8 + x) * 3];
    assert_eq!(at(3, 0), 0.0);
    assert_eq!(at(3, 7), 0.0);
    assert_eq!(at(3, 3), 1.0);
    // the source's far corner lands at the inner picture's far corner, and back
    assert_eq!(map.to_output([4.0, 2.0]), [8.0, 6.0]);
    assert_eq!(map.to_source([8.0, 6.0]), [4.0, 2.0]);
    // one axis grows while the other shrinks
    let (mixed, _) = resize_rgb(&grey(8, 2, |_, _| 255), 8, 2, 4, 4, ResizeMode::Stretch).unwrap();
    assert!(mixed.rgb.iter().all(|v| (v - 1.0).abs() < 1e-6));
    // bad input is refused, not a panic
    assert!(resize_rgb(&[0; 5], 2, 1, 4, 4, ResizeMode::Stretch).is_none());
    assert!(resize_rgb(&src, 4, 2, 0, 4, ResizeMode::Letterbox).is_none());
}
