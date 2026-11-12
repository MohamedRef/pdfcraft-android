//! The colour of blank paper under the light that falls on each part of the page (#298, steps
//! 1–4). Dividing the photo by it removes shadows, uneven lighting and the light's colour cast in
//! one go. Computed on a working copy at most [`WORK`] pixels long.

use crate::Rgb;

use super::objects::{Scene, objects};
use super::plane::{
    Mask, Plane, area, area_rows, bilinear, box_mean, close_disk, gauss, grow, grow_disk, luma3, median, median3, percentile, shrink_rgb, wide_blur,
};

/// The long side of the working copy.
pub(crate) const WORK: usize = 1000;
/// The closing radius is this many stroke widths, within these bounds (working pixels).
const RADIUS_PER_STROKE: f64 = 2.5;
const RADIUS_MIN: f64 = 4.0;
const RADIUS_MAX: f64 = 24.0;
/// Darker than this share of the closed image: ink, not paper.
const INK: f32 = 0.88;

/// What the tone step needs from the working copy.
pub(crate) struct Background {
    /// Paper colour under the local light (R, G, B), working size.
    pub light: [Plane; 3],
    /// Things that are not paper and not shadow (working size).
    pub objects: Mask,
    /// The closing radius used, in working pixels.
    pub radius: usize,
}

/// `f` for each of the three colour channels, on three threads when `parallel`. The channels do
/// not depend on each other, so the planes are the same either way.
fn per_channel(parallel: bool, f: impl Fn(usize) -> Plane + Sync) -> [Plane; 3] {
    if !parallel {
        return [0, 1, 2].map(&f);
    }
    std::thread::scope(|scope| {
        let f = &f;
        let handles = [0, 1, 2].map(|c| (c, scope.spawn(move || f(c))));
        // a channel whose thread died is simply computed here
        handles.map(|(c, h)| h.join().unwrap_or_else(|_| f(c)))
    })
}

/// The working copy's size for a `w` × `h` page (never larger than the page).
pub(crate) fn work_size(w: usize, h: usize) -> (usize, usize) {
    let s = (WORK as f64 / w.max(h) as f64).min(1.0);
    let side = |v: usize| ((v as f64 * s).round() as usize).clamp(1, v);
    (side(w), side(h))
}

/// The page shrunk to working size by area averaging, lightly smoothed, as three planes.
fn working_copy(page: &Rgb, parallel: bool) -> [Plane; 3] {
    let (w, h) = (page.width as usize, page.height as usize);
    let (sw, sh) = work_size(w, h);
    // a whole-number shrink first (cheap integer sums), then the exact area average for the rest
    let k = (w.max(h) / WORK.max(1)).max(1);
    let (coarse, cw, ch) = shrink_rgb(&page.data, w, h, k);
    let (sw, sh) = (sw.min(cw), sh.min(ch));
    let rows = area_rows((cw, ch), 3, (sw, sh), |y, row| row.copy_from_slice(&coarse[y * cw * 3..(y + 1) * cw * 3]));
    per_channel(parallel, |c| gauss(&Plane { w: sw, h: sh, d: rows.iter().skip(c).step_by(3).copied().collect() }, 0.6))
}

/// The 75th percentile of the lengths of horizontal dark runs on every 6th row: about one stroke.
pub(crate) fn stroke_width(y: &Plane) -> f32 {
    let mean = box_mean(y, 15);
    let mut runs = Vec::new();
    for row in (0..y.h).step_by(6) {
        let mut len = 0usize;
        let dark = |x: usize| y.d[row * y.w + x] < mean.d[row * y.w + x] * 0.8;
        for x in 0..=y.w {
            if x < y.w && dark(x) {
                len += 1;
                continue;
            }
            if (1..60).contains(&len) {
                runs.push(len as f32);
            }
            len = 0;
        }
    }
    if runs.len() > 50 { percentile(&mut runs, 75.0) } else { 3.0 }
}

/// First pass: the copy with everything dark and thinner than about `2r` closed away.
fn closed(small: &[Plane; 3], r: usize, parallel: bool) -> [Plane; 3] {
    per_channel(parallel, |c| gauss(&close_disk(&median3(&small[c]), r), 1.5))
}

/// The light at a scale far larger than any print: a closing of 12 % of the working size.
fn large_scale(yb: &Plane) -> Plane {
    let (qw, qh) = ((yb.w / 4).max(1), (yb.h / 4).max(1));
    let quarter = area(yb, qw, qh);
    bilinear(&gauss(&close_disk(&quarter, WORK * 12 / 100 / 4), 3.0), yb.w, yb.h)
}

/// The weighted average of `image` over the pixels where `weight` is 1, and how much weight was
/// near each pixel.
fn average_over(image: &[Plane; 3], weight: &Plane, sigma: f32, parallel: bool) -> ([Plane; 3], Plane) {
    let near = wide_blur(weight, sigma);
    let avg = per_channel(parallel, |c| wide_blur(&image[c].zip(weight, |v, w| v * w), sigma).zip(&near, |n, d| n / d.max(1e-4)));
    (avg, near)
}

/// Second pass: average the paper pixels only, at three scales, so the light is carried across
/// ink, dense text and objects from the paper around them.
fn paper_light(small: &[Plane; 3], paper: &Mask, r: usize, parallel: bool) -> [Plane; 3] {
    let weight = Plane { w: paper.width, h: paper.height, d: paper.data.iter().map(|&p| if p { 1.0 } else { 0.0 }).collect() };
    let sigma = (0.5 * r as f32).max(2.0);
    let (a1, d1) = average_over(small, &weight, sigma, parallel);
    // The two wide scales only fill where no paper is near (dense text, objects): a quarter-size
    // copy is plenty, and sixteen times cheaper.
    let (qw, qh) = ((weight.w / 4).max(1), (weight.h / 4).max(1));
    let quarter = [0, 1, 2].map(|c| area(&small[c].zip(&weight, |v, w| v * w), qw, qh));
    let quarter_weight = area(&weight, qw, qh);
    let wide = |s: f32| -> ([Plane; 3], Plane) {
        let near = wide_blur(&quarter_weight, s);
        let avg = [0, 1, 2].map(|c| bilinear(&wide_blur(&quarter[c], s).zip(&near, |n, d| n / d.max(1e-4)), weight.w, weight.h));
        (avg, bilinear(&near, weight.w, weight.h))
    };
    let (a2, d2) = wide(sigma);
    let (a3, d3) = wide(4.0 * sigma);
    let whole = [0, 1, 2].map(|c| {
        let mut v: Vec<f32> = small[c].d.iter().zip(&paper.data).filter(|&(_, &p)| p).map(|(&v, _)| v).collect();
        if v.is_empty() { 1.0 } else { median(&mut v) }
    });
    [0, 1, 2].map(|c| {
        let d = (0..weight.d.len())
            .map(|i| {
                if d1.d[i] > 0.15 {
                    a1[c].d[i]
                } else if d2.d[i] > 0.02 {
                    a2[c].d[i]
                } else if d3.d[i] > 0.003 {
                    a3[c].d[i]
                } else {
                    whole[c]
                }
            })
            .collect();
        Plane { w: weight.w, h: weight.h, d }
    })
}

/// The paper colour under the local light for `page`, with the object mask. With `parallel` the
/// three colour channels are worked on at once (same result).
pub(crate) fn background(page: &Rgb, parallel: bool) -> Background {
    let small = working_copy(page, parallel);
    let y = luma3(&small);
    let radius = (RADIUS_PER_STROKE * f64::from(stroke_width(&y))).round_ties_even().clamp(RADIUS_MIN, RADIUS_MAX) as usize;
    let b0 = closed(&small, radius, parallel);
    let yb = luma3(&b0);
    let bl = large_scale(&yb);
    let found = objects(&Scene { small: &small, y: &y, b0: &b0, yb: &yb, bl: &bl });
    let kept = if found.data.contains(&true) { grow_disk(&found, radius + 2) } else { found.clone() };
    let ink = grow(&Mask { width: y.w, height: y.h, data: y.d.iter().zip(&yb.d).map(|(&v, &b)| v / b.max(0.02) < INK).collect() }, 1);
    let paper = Mask { width: y.w, height: y.h, data: ink.data.iter().zip(&kept.data).map(|(&i, &k)| !i && !k).collect() };
    let mut light = paper_light(&small, &paper, radius, parallel);
    // The closing keeps a hard shadow edge exactly in place, the average smears it: taking the
    // lower of the two removes the dark line that would follow every hard edge on its shaded side.
    for (l, b) in light.iter_mut().zip(&b0) {
        for ((v, &closed), &k) in l.d.iter_mut().zip(&b.d).zip(&kept.data) {
            if !k {
                *v = v.min(closed);
            }
        }
    }
    Background { light, objects: found, radius }
}
