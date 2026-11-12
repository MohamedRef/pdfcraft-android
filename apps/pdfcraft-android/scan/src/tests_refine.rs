//! Edge refinement (#291) on synthetic photos whose page boundary is known to a fraction of a
//! pixel: every boundary pixel holds the exact share of page it covers (8 × 8 samples).

use pdfcraft_geom::perspective::Point;

use crate::refine::{Pyramid, RefineOptions, Refined, refine};
use crate::{Luma, Quad};

struct Rng(u64);

impl Rng {
    fn unit(&mut self) -> f64 {
        self.0 = self.0.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        (self.0 >> 11) as f64 / (1u64 << 53) as f64
    }

    fn between(&mut self, a: f64, b: f64) -> f64 {
        a + self.unit() * (b - a)
    }

    fn normal(&mut self) -> f64 {
        (0..12).map(|_| self.unit()).sum::<f64>() - 6.0
    }
}

/// How far inside the quad a point is (negative outside), in pixels.
fn inside_quad(q: [Point; 4]) -> impl Fn(f64, f64) -> f64 {
    let centre = [q.iter().map(|p| p[0]).sum::<f64>() / 4.0, q.iter().map(|p| p[1]).sum::<f64>() / 4.0];
    let edges: [(Point, Point); 4] = std::array::from_fn(|i| {
        let (a, b) = (q[i], q[(i + 1) % 4]);
        let len = (b[0] - a[0]).hypot(b[1] - a[1]);
        let mut n = [(b[1] - a[1]) / len, -(b[0] - a[0]) / len];
        if n[0] * (centre[0] - a[0]) + n[1] * (centre[1] - a[1]) < 0.0 {
            n = [-n[0], -n[1]];
        }
        (a, n)
    });
    move |x, y| edges.iter().map(|(a, n)| (x - a[0]) * n[0] + (y - a[1]) * n[1]).fold(f64::INFINITY, f64::min)
}

/// A photo of a page: `inside` says how far inside the page a point is, `page` and `back` what is
/// seen on the page and around it. Then blurred by `blur` pixels and with `noise` grey levels of
/// noise.
fn photo(
    w: u32,
    h: u32,
    inside: &dyn Fn(f64, f64) -> f64,
    page: &dyn Fn(f64, f64) -> f64,
    back: &dyn Fn(f64, f64) -> f64,
    blur: f64,
    noise: f64,
) -> Luma {
    let (wu, hu) = (w as usize, h as usize);
    let mut v = vec![0.0f32; wu * hu];
    for y in 0..hu {
        for x in 0..wu {
            let (cx, cy) = (x as f64 + 0.5, y as f64 + 0.5);
            let d = inside(cx, cy);
            v[y * wu + x] = if d > 1.0 {
                page(cx, cy) as f32
            } else if d < -1.0 {
                back(cx, cy) as f32
            } else {
                let mut sum = 0.0;
                for j in 0..8 {
                    for i in 0..8 {
                        let (sx, sy) = (x as f64 + (i as f64 + 0.5) / 8.0, y as f64 + (j as f64 + 0.5) / 8.0);
                        sum += if inside(sx, sy) > 0.0 { page(sx, sy) } else { back(sx, sy) };
                    }
                }
                (sum / 64.0) as f32
            };
        }
    }
    if blur > 0.0 {
        let radius = (3.0 * blur).ceil() as isize;
        let kernel: Vec<f32> = (-radius..=radius).map(|k| (-(k * k) as f64 / (2.0 * blur * blur)).exp() as f32).collect();
        let total: f32 = kernel.iter().sum();
        let pass = |src: &[f32], horizontal: bool| {
            let mut out = vec![0.0f32; src.len()];
            for y in 0..hu as isize {
                for x in 0..wu as isize {
                    let mut sum = 0.0;
                    for (k, weight) in kernel.iter().enumerate() {
                        let o = k as isize - radius;
                        let (sx, sy) = if horizontal { ((x + o).clamp(0, wu as isize - 1), y) } else { (x, (y + o).clamp(0, hu as isize - 1)) };
                        sum += weight * src[sy as usize * wu + sx as usize];
                    }
                    out[y as usize * wu + x as usize] = sum / total;
                }
            }
            out
        };
        v = pass(&pass(&v, true), false);
    }
    let mut rng = Rng(77);
    let data = v.iter().map(|&p| (f64::from(p) + if noise > 0.0 { noise * rng.normal() } else { 0.0 }).round().clamp(0.0, 255.0) as u8).collect();
    Luma { width: w, height: h, data }
}

/// A page a little turned and seen a little from the side, filling most of a `w` × `h` photo.
fn page_quad(w: u32, h: u32) -> [Point; 4] {
    let (w, h) = (f64::from(w), f64::from(h));
    [[0.2137 * w, 0.1512 * h], [0.7861 * w, 0.1241 * h], [0.8312 * w, 0.8723 * h], [0.1689 * w, 0.8518 * h]]
}

/// `quad` with every corner moved by up to `by` pixels each way.
fn nudged(quad: [Point; 4], by: f64, rng: &mut Rng) -> Quad {
    Quad::new(quad.map(|[x, y]| [x + rng.between(-by, by), y + rng.between(-by, by)])).unwrap()
}

/// The distance of each refined corner from the true one.
fn errors(found: &Refined, truth: [Point; 4]) -> [f64; 4] {
    std::array::from_fn(|i| (found.quad.0[i][0] - truth[i][0]).hypot(found.quad.0[i][1] - truth[i][1]))
}

/// The mean and 95th percentile of the corner errors over `trials` refinements, each from a
/// start up to `off` pixels wrong per corner.
fn accuracy(pyramid: &Pyramid, truth: [Point; 4], off: f64, trials: usize, seed: u64) -> (f64, f64, usize) {
    let mut rng = Rng(seed);
    let mut all = Vec::new();
    let mut unrefined = 0;
    for _ in 0..trials {
        let found = refine(pyramid, &nudged(truth, off, &mut rng), &RefineOptions::default());
        unrefined += found.sides.iter().filter(|s| !s.refined).count();
        all.extend(errors(&found, truth));
    }
    all.sort_by(f64::total_cmp);
    (all.iter().sum::<f64>() / all.len() as f64, all[all.len() * 95 / 100], unrefined)
}

const DESK: f64 = 45.0;
const PAPER: f64 = 232.0;

#[test]
fn a_clean_page_is_found_to_a_fraction_of_a_pixel_at_12_megapixels() {
    let (w, h) = (4000, 3000);
    let truth = page_quad(w, h);
    let inside = inside_quad(truth);
    let luma = photo(w, h, &inside, &|_, _| PAPER, &|_, _| DESK, 0.0, 0.0);
    let clock = std::time::Instant::now();
    let pyramid = Pyramid::new(luma);
    let built = clock.elapsed();
    let clock = std::time::Instant::now();
    let (mean, p95, unrefined) = accuracy(&pyramid, truth, 25.0, 24, 1);
    println!("clean, 12 MP, start off by up to 25 px: mean {mean:.3} px, p95 {p95:.3} px");
    println!(
        "12 MP on this machine: pyramid {:.1} ms, one refinement {:.1} ms, {} KiB held",
        built.as_secs_f64() * 1e3,
        clock.elapsed().as_secs_f64() * 1e3 / 24.0,
        pyramid.bytes() / 1024
    );
    assert!(mean <= 0.3 && p95 <= 0.8 && unrefined == 0, "mean {mean}, p95 {p95}, {unrefined} sides not refined");
    // a start that is already exact stays
    let exact = refine(&pyramid, &Quad(truth), &RefineOptions::default());
    let drift = errors(&exact, truth).into_iter().fold(0.0, f64::max);
    println!("exact start: drift {drift:.3} px");
    assert!(drift < 0.2, "{drift}");
    // and refining the result changes nothing more
    let mut rng = Rng(2);
    let once = refine(&pyramid, &nudged(truth, 25.0, &mut rng), &RefineOptions::default());
    let twice = refine(&pyramid, &once.quad, &RefineOptions::default());
    assert!(errors(&twice, once.quad.0).into_iter().all(|e| e < 0.3));
    // the same input, the same answer
    assert_eq!(refine(&pyramid, &once.quad, &RefineOptions::default()), twice);
}

#[test]
fn blur_and_noise_cost_half_a_pixel() {
    let (w, h) = (4000, 3000);
    let truth = page_quad(w, h);
    let inside = inside_quad(truth);
    let pyramid = Pyramid::new(photo(w, h, &inside, &|_, _| PAPER, &|_, _| DESK, 1.5, 6.0));
    let (mean, p95, unrefined) = accuracy(&pyramid, truth, 25.0, 24, 3);
    println!("blur 1.5 px, noise 6: mean {mean:.3} px, p95 {p95:.3} px");
    assert!(mean <= 0.6 && p95 <= 1.5 && unrefined == 0, "mean {mean}, p95 {p95}, {unrefined} sides not refined");
}

#[test]
fn faint_and_dark_pages_are_found_too() {
    let (w, h) = (2000, 1500);
    let truth = page_quad(w, h);
    let inside = inside_quad(truth);
    // white paper on a light table: 8 grey levels between them
    let pyramid = Pyramid::new(photo(w, h, &inside, &|_, _| 240.0, &|_, _| 232.0, 0.0, 0.0));
    let (mean, p95, unrefined) = accuracy(&pyramid, truth, 12.0, 24, 4);
    println!("8 grey levels of contrast: mean {mean:.3} px, p95 {p95:.3} px");
    assert!(mean <= 1.0 && unrefined == 0, "mean {mean}, p95 {p95}, {unrefined} sides not refined");
    // a dark page on a light table
    let pyramid = Pyramid::new(photo(w, h, &inside, &|_, _| 40.0, &|_, _| 215.0, 0.0, 0.0));
    let (mean, p95, unrefined) = accuracy(&pyramid, truth, 12.0, 24, 5);
    println!("dark page on a light table: mean {mean:.3} px, p95 {p95:.3} px");
    assert!(mean <= 0.3 && p95 <= 0.8 && unrefined == 0, "mean {mean}, p95 {p95}, {unrefined} sides not refined");
}

/// Print on the page: rows of words, and a ruled frame 12 pixels inside the paper's edge, which
/// is as strong an edge as the paper's own.
#[test]
fn the_papers_edge_wins_over_a_ruled_line_just_inside_it() {
    let (w, h) = (2000, 1500);
    let truth = page_quad(w, h);
    let inside = inside_quad(truth);
    let printed = |x: f64, y: f64| {
        let d = inside(x, y);
        let frame = (12.0..15.0).contains(&d);
        let (col, row) = ((x / 9.0).floor() as i64, (y / 34.0).floor() as i64);
        let word = d > 40.0 && (y / 34.0).fract() < 0.4 && (col * 7 + row * 13).rem_euclid(5) != 0;
        if frame || word { 25.0 } else { PAPER }
    };
    let pyramid = Pyramid::new(photo(w, h, &inside, &printed, &|_, _| DESK, 0.0, 0.0));
    let (mean, p95, unrefined) = accuracy(&pyramid, truth, 12.0, 24, 6);
    println!("ruled frame 12 px inside: mean {mean:.3} px, p95 {p95:.3} px");
    assert!(mean <= 0.3 && p95 <= 0.8 && unrefined == 0, "mean {mean}, p95 {p95}, {unrefined} sides not refined");
}

/// A finger over most of one side: that side is left alone or still right, the others are exact.
#[test]
fn a_finger_over_one_side_does_not_spoil_the_others() {
    let (w, h) = (2000, 1500);
    let truth = page_quad(w, h);
    let inside = inside_quad(truth);
    // an ellipse over 60 % of the bottom side
    let (a, b) = (truth[2], truth[3]);
    let (cx, cy, half) = ((a[0] + b[0]) / 2.0, (a[1] + b[1]) / 2.0, 0.3 * (a[0] - b[0]).hypot(a[1] - b[1]));
    let finger = |x: f64, y: f64| ((x - cx) / half).powi(2) + ((y - cy) / 130.0).powi(2) < 1.0;
    let page = |x: f64, y: f64| if finger(x, y) { 150.0 } else { PAPER };
    let back = |x: f64, y: f64| if finger(x, y) { 150.0 } else { DESK };
    let pyramid = Pyramid::new(photo(w, h, &inside, &page, &back, 0.0, 0.0));
    let mut rng = Rng(7);
    for _ in 0..12 {
        let found = refine(&pyramid, &nudged(truth, 12.0, &mut rng), &RefineOptions::default());
        let e = errors(&found, truth);
        // top-left and top-right touch only clean sides
        assert!(e[0] <= 0.8 && e[1] <= 0.8, "the clean corners: {e:?}");
        assert!(found.sides[0].refined && found.sides[1].refined && found.sides[3].refined);
        assert!(!found.sides[2].refined || (e[2] <= 2.0 && e[3] <= 2.0), "the covered side: {e:?} {:?}", found.sides[2]);
    }
}

/// A checked tablecloth under the page: its lines are as strong as the paper's edge, but none of
/// them runs along it.
#[test]
fn a_checked_tablecloth_does_not_pull_a_side_away() {
    let (w, h) = (2000, 1500);
    let truth = page_quad(w, h);
    let inside = inside_quad(truth);
    let cloth = |x: f64, y: f64| if ((x / 83.0).floor() + (y / 83.0).floor()) as i64 % 2 == 0 { 60.0 } else { 185.0 };
    let pyramid = Pyramid::new(photo(w, h, &inside, &|_, _| PAPER, &cloth, 0.0, 0.0));
    let mut rng = Rng(8);
    let mut worst: f64 = 0.0;
    for _ in 0..24 {
        let found = refine(&pyramid, &nudged(truth, 12.0, &mut rng), &RefineOptions::default());
        worst = errors(&found, truth).into_iter().fold(worst, f64::max);
    }
    println!("checked tablecloth: worst corner {worst:.3} px");
    assert!(worst <= 2.0, "{worst}");
}

/// A page curled like part of a cylinder: its top and bottom bow, its sides stay straight.
#[test]
fn a_curled_page_is_reported_as_bent() {
    let (w, h) = (2000, 1500);
    let (left, right, top, bottom) = (420.0, 1580.0, 240.0, 1260.0);
    // 1.5 % of the side's length
    let bow = 0.015 * (right - left);
    let arch = |x: f64| bow * (1.0 - ((x - (left + right) / 2.0) / ((right - left) / 2.0)).powi(2));
    let inside = |x: f64, y: f64| (x - left).min(right - x).min(y - (top - arch(x))).min((bottom + arch(x)) - y);
    let pyramid = Pyramid::new(photo(w, h, &inside, &|_, _| PAPER, &|_, _| DESK, 0.0, 0.0));
    let start = Quad::new([[left + 3.0, top - 6.0], [right - 4.0, top - 5.0], [right - 2.0, bottom + 7.0], [left + 5.0, bottom + 4.0]]).unwrap();
    let found = refine(&pyramid, &start, &RefineOptions::default());
    println!("curled: bows {:?} px (true {bow:.1})", found.sides.map(|s| s.bow_px));
    assert!(found.sides[0].curved && found.sides[2].curved, "{:?}", found.sides);
    assert!(!found.sides[1].curved && !found.sides[3].curved, "{:?}", found.sides);
    assert!((f64::from(found.sides[0].bow_px) / bow - 1.0).abs() < 0.25 && (f64::from(found.sides[2].bow_px) / bow - 1.0).abs() < 0.25);
    // a flat page is not
    let truth = page_quad(w, h);
    let flat = Pyramid::new(photo(w, h, &inside_quad(truth), &|_, _| PAPER, &|_, _| DESK, 0.0, 0.0));
    let mut rng = Rng(9);
    for _ in 0..8 {
        let found = refine(&flat, &nudged(truth, 12.0, &mut rng), &RefineOptions::default());
        assert!(found.sides.iter().all(|s| !s.curved && s.bow_px < 1.0), "{:?}", found.sides);
    }
}

#[test]
fn only_the_sides_asked_for_move() {
    let (w, h) = (2000, 1500);
    let truth = page_quad(w, h);
    let pyramid = Pyramid::new(photo(w, h, &inside_quad(truth), &|_, _| PAPER, &|_, _| DESK, 0.0, 0.0));
    // the top-right corner was dragged: the top and right sides are refined, the others kept
    let mut start = truth;
    start[1] = [truth[1][0] + 9.0, truth[1][1] - 7.0];
    start[3] = [truth[3][0] - 10.0, truth[3][1] + 10.0];
    let found = refine(&pyramid, &Quad(start), &RefineOptions { sides: [true, true, false, false], ..RefineOptions::default() });
    let e = errors(&found, truth);
    assert!(e[1] < 0.5, "the corner between the two refined sides: {e:?}");
    assert_eq!(found.sides.map(|s| s.refined), [true, true, false, false]);
    // the corner between the two kept sides stays where it was put
    assert!((found.quad.0[3][0] - start[3][0]).hypot(found.quad.0[3][1] - start[3][1]) < 1e-6);
}

#[test]
fn nothing_convincing_leaves_the_corners_alone() {
    let (w, h) = (1200, 900);
    let quad = Quad(page_quad(w, h));
    // a blank photo, and noise only
    for (noise, level) in [(0.0, 128.0), (20.0, 128.0)] {
        let pyramid = Pyramid::new(photo(w, h, &|_, _| -10.0, &|_, _| level, &|_, _| level, 0.0, noise));
        let found = refine(&pyramid, &quad, &RefineOptions::default());
        assert_eq!(found.quad, quad, "noise {noise}");
        assert!(found.sides.iter().all(|s| !s.refined));
    }
    // corners no page has, and photos too small to look at: no panic, the input back
    let pyramid = Pyramid::new(photo(40, 30, &|_, _| -10.0, &|_, _| 128.0, &|_, _| 128.0, 0.0, 0.0));
    for corners in [
        [[5.0, 5.0], [5.0, 5.0], [30.0, 20.0], [6.0, 20.0]],
        [[0.0, 0.0], [10.0, 0.0], [20.0, 0.0], [30.0, 0.0]],
        [[0.0, 0.0], [40.0, 0.0], [40.0, 30.0], [0.0, 30.0]],
    ] {
        let quad = Quad(corners);
        assert_eq!(refine(&pyramid, &quad, &RefineOptions::default()).quad, quad);
    }
    let tiny = Pyramid::new(Luma { width: 1, height: 1, data: vec![0] });
    let _ = refine(&tiny, &Quad([[0.0, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 1.0]]), &RefineOptions::default());
    // a search distance that makes no sense falls back to the usual one
    let _ = refine(&tiny, &quad, &RefineOptions { search_px: Some(f32::NAN), ..RefineOptions::default() });
}

/// A side partly outside the photo is fitted from the part that is in it.
#[test]
fn a_side_running_out_of_the_photo_is_fitted_from_what_is_visible() {
    let (w, h) = (2000, 1500);
    // the right third of the bottom side is below the photo
    let truth = [[400.0, 220.0], [1560.0, 190.0], [1640.0, 1540.0], [330.0, 1380.0]];
    let pyramid = Pyramid::new(photo(w, h, &inside_quad(truth), &|_, _| PAPER, &|_, _| DESK, 0.0, 0.0));
    let mut rng = Rng(10);
    let mut start = truth.map(|[x, y]| [x + rng.between(-10.0, 10.0), y + rng.between(-10.0, 10.0)]);
    start[2][1] = start[2][1].min(1500.0);
    let found = refine(&pyramid, &Quad(start), &RefineOptions::default());
    let e = errors(&found, truth);
    assert!(e[0] < 0.8 && e[1] < 0.8 && e[3] < 0.8, "the corners in the photo: {e:?}");
    // the fourth is below the photo, where the two sides meet (#291)
    let [x, y] = found.quad.0[2];
    assert!(y > 1500.0 && e[2] < 3.0, "({x}, {y}), {} px from the corner outside", e[2]);
}

/// A corner 5 % outside the frame, put on the border by the detector, is found from the two sides
/// that run to it (#291's table: "recovered within 3 px from the two side fits").
#[test]
fn a_corner_outside_the_frame_is_where_its_sides_meet() {
    let (w, h) = (2000, 1500);
    // the top-right corner is 100 px (5 %) right of the photo and 30 px above it
    let truth = [[300.0, 160.0], [2100.0, -30.0], [1700.0, 1320.0], [260.0, 1290.0]];
    let pyramid = Pyramid::new(photo(w, h, &inside_quad(truth), &|_, _| PAPER, &|_, _| DESK, 0.6, 2.0));
    let mut start = truth;
    start[1] = [2000.0, 0.0];
    let found = refine(&pyramid, &Quad(start), &RefineOptions::default());
    let e = errors(&found, truth);
    assert!(e.iter().all(|v| *v < 3.0), "{e:?}: {:?}", found.quad.0);
    // never farther out than 15 % of the photo
    let far = [[300.0, 160.0], [2600.0, -400.0], [1700.0, 1320.0], [260.0, 1290.0]];
    let pyramid = Pyramid::new(photo(w, h, &inside_quad(far), &|_, _| PAPER, &|_, _| DESK, 0.0, 0.0));
    let found = refine(&pyramid, &Quad([[300.0, 160.0], [2000.0, 0.0], [1700.0, 1320.0], [260.0, 1290.0]]), &RefineOptions::default());
    for [x, y] in found.quad.0 {
        assert!((-300.0..=2300.0).contains(&x) && (-225.0..=1725.0).contains(&y), "({x}, {y})");
    }
}
