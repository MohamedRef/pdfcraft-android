//! The Auto look on synthetic pages whose truth is known, and the filters it is built from.
//! (How close it comes to a reference scanner on real photos is measured outside the repository:
//! `tools/scan-eval/reference_pairs.py`, numbers in #298.)

use super::background::stroke_width;
use super::plane::{Mask, Plane, area, area_rows, close_disk, components, dilate_disk, grow, median3, percentile, shrink, shrink_rgb, wide_blur};
use super::tone::Tone;
use super::{AutoOutput, AutoParams, PaperMode, auto, enlargement};
use crate::Rgb;

const W: usize = 800;
const H: usize = 1000;
const PAPER: f32 = 0.93;
const INK: f32 = 0.12;

/// A small deterministic random source.
struct Rng(u64);

impl Rng {
    fn unit(&mut self) -> f32 {
        self.0 = self.0.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        ((self.0 >> 40) as f32) / (1u64 << 24) as f32
    }

    fn between(&mut self, a: usize, b: usize) -> usize {
        a + (self.unit() * (b - a) as f32) as usize
    }

    /// Roughly normal, standard deviation 1.
    fn normal(&mut self) -> f32 {
        ((0..12).map(|_| self.unit()).sum::<f32>()) - 6.0
    }
}

/// A page as it is (reflectance per channel), before any light falls on it.
struct Page {
    rgb: Vec<[f32; 3]>,
    /// Blank paper (nothing printed, drawn or lying there).
    blank: Vec<bool>,
}

impl Page {
    fn empty(paper: [f32; 3]) -> Page {
        Page { rgb: vec![paper; W * H], blank: vec![true; W * H] }
    }

    /// Rows of dark bars like lines of text, leaving the bottom quarter free.
    fn text() -> Page {
        let mut page = Page::empty([PAPER; 3]);
        let mut rng = Rng(7);
        for y in (60..H * 3 / 4).step_by(16) {
            let mut x = 50;
            while x < W - 90 {
                let len = rng.between(10, 36);
                page.rect((x, y), (x + len, y + 5), [INK; 3]);
                x += len + rng.between(5, 9);
            }
        }
        page
    }

    fn rect(&mut self, from: (usize, usize), to: (usize, usize), colour: [f32; 3]) {
        for y in from.1..to.1 {
            for x in from.0..to.0 {
                self.rgb[y * W + x] = colour;
                self.blank[y * W + x] = false;
            }
        }
    }

    /// The photo: each pixel times the light at it, with sensor noise.
    fn photo(&self, light: impl Fn(usize, usize) -> [f32; 3]) -> Rgb {
        let mut rng = Rng(3);
        let mut data = Vec::with_capacity(W * H * 3);
        for (i, px) in self.rgb.iter().enumerate() {
            let l = light(i % W, i / W);
            for c in 0..3 {
                data.push(((px[c] * l[c] + 0.012 * rng.normal()).clamp(0.0, 1.0) * 255.0).round() as u8);
            }
        }
        Rgb { width: W as u32, height: H as u32, data }
    }
}

fn run(photo: &Rgb) -> Rgb {
    auto(photo, &AutoParams::default()).unwrap().rgb
}

fn px(img: &Rgb, i: usize) -> [f32; 3] {
    [f32::from(img.data[i * 3]), f32::from(img.data[i * 3 + 1]), f32::from(img.data[i * 3 + 2])]
}

fn lum(img: &Rgb, i: usize) -> f32 {
    let p = px(img, i);
    0.299 * p[0] + 0.587 * p[1] + 0.114 * p[2]
}

/// Luminance of the pixels chosen by `pick(x, y, index)`.
fn lums(img: &Rgb, pick: impl Fn(usize, usize, usize) -> bool) -> Vec<f32> {
    (0..W * H).filter(|&i| pick(i % W, i / W, i)).map(|i| lum(img, i)).collect()
}

fn mean(v: &[f32]) -> f32 {
    v.iter().sum::<f32>() / v.len().max(1) as f32
}

fn share(v: &[f32], f: impl Fn(f32) -> bool) -> f32 {
    v.iter().filter(|&&x| f(x)).count() as f32 / v.len().max(1) as f32
}

/// The tone curve at the default strength, in grey levels, for a reflectance on white paper.
fn through_curve(reflectance: f32) -> f32 {
    let t = Tone::at(super::DEFAULT_STRENGTH);
    (((reflectance / PAPER - t.black) / (t.white - t.black)).clamp(0.0, 1.0)).powf(t.gamma) * 255.0
}

#[test]
fn a_dim_page_comes_out_white_with_dark_ink() {
    let page = Page::text();
    let out = run(&page.photo(|_, _| [0.7; 3]));
    let paper = lums(&out, |_, _, i| page.blank[i]);
    let mut ink = lums(&out, |_, _, i| page.rgb[i][0] == INK);
    assert!(share(&paper, |v| v >= 245.0) >= 0.97, "white paper share {}", share(&paper, |v| v >= 245.0));
    assert!(percentile(&mut ink, 50.0) <= 30.0, "ink median {}", percentile(&mut ink, 50.0));
}

/// A 2:1 lighting gradient and a hard diagonal shadow (×0.6, 6 px soft edge): the scene the
/// issue's numbers are quoted for.
#[test]
fn a_gradient_and_a_hard_shadow_are_removed() {
    let page = Page::text();
    let side = |x: usize, y: usize| 0.6 * x as f32 + 0.8 * y as f32 - 640.0;
    let out = run(&page.photo(|x, y| {
        let shade = 0.6 + 0.4 * ((side(x, y) + 3.0) / 6.0).clamp(0.0, 1.0);
        [(0.5 + 0.4 * x as f32 / W as f32 + 0.1 * y as f32 / H as f32) * shade; 3]
    }));
    let near = |lo: f32, hi: f32| lums(&out, |x, y, i| page.blank[i] && (lo..hi).contains(&side(x, y)));
    let step = (mean(&near(-80.0, -20.0)) - mean(&near(20.0, 80.0))).abs();
    let mut all = lums(&out, |_, _, i| page.blank[i]);
    let white = share(&all, |v| v >= 245.0);
    let paper = percentile(&mut all, 50.0);
    let halo =
        mean(&lums(&out, |x, y, i| page.blank[i] && (12.0..40.0).contains(&side(x, y).abs())).iter().map(|v| (v - paper).abs()).collect::<Vec<_>>());
    let mut close: Vec<f32> = near(-40.0, 40.0).iter().map(|v| (v - paper).abs()).collect();
    let p95 = percentile(&mut close, 95.0);
    assert!(step <= 4.0, "step across the edge {step}");
    assert!(halo <= 3.0, "halo beside the edge {halo}");
    assert!(p95 <= 12.0, "95 % of the paper near the edge within {p95} levels");
    assert!(white >= 0.95, "white paper share {white}");
}

#[test]
fn printed_dark_areas_keep_their_tone() {
    let mut page = Page::text();
    let mut rng = Rng(11);
    for y in 800..940 {
        for x in 450..650 {
            page.rect((x, y), (x + 1, y + 1), [0.35 + 0.2 * rng.unit(); 3]);
        }
    }
    page.rect((80, 820), (380, 838), [0.08; 3]);
    page.rect((80, 870), (380, 888), [0.08; 3]);
    let out = run(&page.photo(|x, _| [0.6 + 0.3 * x as f32 / W as f32; 3]));
    let inside = |x: usize, y: usize| (460..640).contains(&x) && (810..930).contains(&y);
    let got = mean(&lums(&out, |x, y, _| inside(x, y)));
    let want = mean(&(0..W * H).filter(|&i| inside(i % W, i / W)).map(|i| through_curve(page.rgb[i][0])).collect::<Vec<_>>());
    assert!((got - want).abs() <= 0.15 * want.max(20.0), "box tone {got}, true tone through the curve {want}");
    let bars = lums(&out, |x, y, _| (90..370).contains(&x) && ((823..835).contains(&y) || (873..885).contains(&y)));
    assert!(mean(&bars) <= 60.0, "black bars {}", mean(&bars));
}

#[test]
fn a_pale_pencil_line_stays_visible() {
    let mut page = Page::text();
    page.rect((100, 850), (700, 852), [0.70; 3]);
    let out = run(&page.photo(|_, y| [0.9 - 0.3 * y as f32 / H as f32; 3]));
    let mut line = lums(&out, |x, y, _| (110..690).contains(&x) && (850..852).contains(&y));
    let mut paper = lums(&out, |_, _, i| page.blank[i]);
    let (line, paper) = (percentile(&mut line, 50.0), percentile(&mut paper, 50.0));
    assert!(paper - line >= 40.0, "pencil {line} on paper {paper}");
}

#[test]
fn coloured_light_leaves_white_paper_and_neutral_black() {
    let page = Page::text();
    for cast in [[1.0, 0.85, 0.62], [0.6, 0.75, 0.95], [0.95, 0.7, 0.85]] {
        let out = run(&page.photo(|_, _| cast.map(|c: f32| c * 0.85)));
        let blank: Vec<[f32; 3]> = (0..W * H).filter(|&i| page.blank[i]).map(|i| px(&out, i)).collect();
        let tint = mean(&blank.iter().map(|p| (p[0] - p[2]).abs()).collect::<Vec<_>>());
        let ink: Vec<f32> =
            (0..W * H).filter(|&i| page.rgb[i][0] == INK).map(|i| px(&out, i)).map(|p| p[0].max(p[1]).max(p[2]) - p[0].min(p[1]).min(p[2])).collect();
        assert!(tint <= 4.0, "{cast:?}: paper red-blue difference {tint}");
        assert!(mean(&ink) <= 15.0, "{cast:?}: black ink chroma {}", mean(&ink));
    }
}

#[test]
fn pen_and_stamp_keep_their_colour() {
    let mut page = Page::text();
    page.rect((100, 800), (500, 806), [0.15, 0.20, 0.75]);
    page.rect((520, 800), (700, 880), [0.80, 0.15, 0.15]);
    page.rect((530, 810), (690, 870), [PAPER; 3]);
    let out = run(&page.photo(|x, _| [0.55 + 0.35 * x as f32 / W as f32; 3]));
    let colour = |x0: usize, x1: usize, y0: usize, y1: usize| -> [f32; 3] {
        let v: Vec<[f32; 3]> = (0..W * H).filter(|&i| (x0..x1).contains(&(i % W)) && (y0..y1).contains(&(i / W))).map(|i| px(&out, i)).collect();
        [0, 1, 2].map(|c| mean(&v.iter().map(|p| p[c]).collect::<Vec<_>>()))
    };
    let (pen, stamp) = (colour(110, 490, 801, 805), colour(522, 528, 812, 868));
    assert!(pen[2] - pen[0] >= 80.0, "blue pen came out {pen:?}");
    assert!(stamp[0] - stamp[2] >= 80.0, "red stamp came out {stamp:?}");
}

#[test]
fn a_finger_at_the_edge_stays_skin_coloured() {
    let mut page = Page::text();
    page.rect((0, 400), (90, 560), [0.80, 0.58, 0.47]);
    let photo = page.photo(|_, _| [0.8, 0.82, 0.9]);
    let result = auto(&photo, &AutoParams::default()).unwrap();
    let v: Vec<[f32; 3]> = (0..W * H).filter(|&i| i % W < 80 && (410..550).contains(&(i / W))).map(|i| px(&result.rgb, i)).collect();
    let c = [0, 1, 2].map(|k| mean(&v.iter().map(|p| p[k]).collect::<Vec<_>>()));
    assert!(c[0] - c[2] >= 40.0, "the finger lost its colour: {c:?}");
    assert!(0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2] <= 220.0, "the finger was bleached: {c:?}");
    assert!(result.objects.share() > 0.005, "the finger is not in the object mask ({})", result.objects.share());
}

/// A deep shadow filled by warm room light is warmer than the lit paper, like skin, but changes
/// gradually: it is lighting and must go.
#[test]
fn a_soft_warm_shadow_is_removed_not_kept() {
    let page = Page::text();
    let photo = page.photo(|_, y| {
        let t = ((y as f32 - 600.0) / 150.0).clamp(0.0, 1.0);
        [0.9 - 0.30 * t, 0.9 - 0.40 * t, 0.9 - 0.52 * t]
    });
    let result = auto(&photo, &AutoParams::default()).unwrap();
    let shade = lums(&result.rgb, |_, y, i| page.blank[i] && y > 780);
    assert!(share(&shade, |v| v >= 240.0) >= 0.9, "white paper share in the shade {}", share(&shade, |v| v >= 240.0));
    assert!(result.objects.share() < 0.005, "the shadow was taken for an object ({})", result.objects.share());
}

/// Soft blobs of bluish shade down to 0.6 of the light. (An island darker than half the paper
/// around it is taken for print whatever its edges look like: that is the rule's stated limit.)
#[test]
fn dappled_shade_is_removed() {
    let page = Page::text();
    let mut rng = Rng(5);
    let blobs: Vec<(f32, f32, f32, f32)> =
        (0..24).map(|_| (rng.unit() * W as f32, rng.unit() * H as f32, 18.0 + 30.0 * rng.unit(), 0.15 + 0.2 * rng.unit())).collect();
    let out = run(&page.photo(|x, y| {
        let dark: f32 =
            blobs.iter().map(|&(bx, by, s, depth)| depth * (-((x as f32 - bx).powi(2) + (y as f32 - by).powi(2)) / (2.0 * s * s)).exp()).sum();
        let l = 0.9 * (1.0 - dark.min(0.4));
        [l * 0.92, l * 0.97, l]
    }));
    let paper = lums(&out, |_, _, i| page.blank[i]);
    assert!(share(&paper, |v| v >= 245.0) >= 0.9, "white paper share {}", share(&paper, |v| v >= 245.0));
    assert!(share(&paper, |v| v < 200.0) <= 0.01, "paper left dark {}", share(&paper, |v| v < 200.0));
}

#[test]
fn strength_zero_is_the_page_itself_and_low_strengths_blend() {
    let page = Page::text();
    let photo = page.photo(|x, _| [0.6 + 0.3 * x as f32 / W as f32; 3]);
    let at = |strength| auto(&photo, &AutoParams { strength, ..AutoParams::default() }).unwrap().rgb;
    assert_eq!(at(0), photo);
    let (ten, twenty) = (at(10), at(20));
    for ((&a, &b), &c) in photo.data.iter().zip(&ten.data).zip(&twenty.data) {
        assert!((a.min(c)..=a.max(c)).contains(&b), "strength 10 gave {b}, outside {a}..{c}");
    }
    assert_ne!(ten, twenty);
}

#[test]
fn a_blank_page_stays_blank() {
    let out = run(&Page::empty([PAPER; 3]).photo(|x, y| [0.5 + 0.3 * x as f32 / W as f32 + 0.1 * y as f32 / H as f32; 3]));
    let v = lums(&out, |_, _, _| true);
    let m = mean(&v);
    let sd = (v.iter().map(|x| (x - m) * (x - m)).sum::<f32>() / v.len() as f32).sqrt();
    assert!(m >= 250.0 && sd <= 2.0, "blank page: mean {m}, standard deviation {sd}");
}

#[test]
fn odd_pages_do_no_harm() {
    // nearly black: nothing to divide by
    let black = Rgb { width: 300, height: 200, data: vec![3; 300 * 200 * 3] };
    assert_eq!(run(&black).data.len(), black.data.len());
    // too small to tell paper from ink: left alone
    let tiny = Rgb { width: 10, height: 12, data: (0..10 * 12 * 3).map(|i| (i * 7 % 256) as u8).collect() };
    assert_eq!(run(&tiny), tiny);
    // a buffer that does not match its size
    assert!(auto(&Rgb { width: 50, height: 50, data: vec![0; 10] }, &AutoParams::default()).is_none());
    // one very long, thin page
    let strip = Rgb { width: 2000, height: 20, data: vec![200; 2000 * 20 * 3] };
    assert_eq!(run(&strip).data.len(), strip.data.len());
}

#[test]
fn the_bytes_do_not_depend_on_the_number_of_threads() {
    let mut page = Page::text();
    page.rect((0, 400), (90, 560), [0.80, 0.58, 0.47]);
    let photo = page.photo(|x, y| [0.5 + 0.4 * x as f32 / W as f32, 0.55 + 0.3 * y as f32 / H as f32, 0.8]);
    let at = |threads| {
        // the parts' times differ from run to run (#446)
        let made = auto(&photo, &AutoParams { threads, ..AutoParams::default() }).unwrap();
        AutoOutput { stages: Vec::new(), ..made }
    };
    assert_eq!(at(1), at(4));
    assert_eq!(at(1), at(3));
}

#[test]
fn yellow_paper_turns_white_unless_asked_to_keep_it() {
    let mut page = Page::empty([0.93, 0.88, 0.50]);
    page.rect((100, 100), (700, 110), [INK; 3]);
    page.rect((100, 300), (700, 310), [INK; 3]);
    let photo = page.photo(|x, _| [0.6 + 0.3 * x as f32 / W as f32; 3]);
    let paper = |paper| {
        let out = auto(&photo, &AutoParams { paper, ..AutoParams::default() }).unwrap().rgb;
        let v: Vec<[f32; 3]> = (0..W * H).filter(|&i| page.blank[i]).map(|i| px(&out, i)).collect();
        [0, 1, 2].map(|c| mean(&v.iter().map(|p| p[c]).collect::<Vec<_>>()))
    };
    let (white, kept) = (paper(PaperMode::White), paper(PaperMode::Keep));
    assert!((white[0] - white[2]).abs() <= 6.0 && white[2] >= 245.0, "White gave {white:?}");
    assert!(kept[0] - kept[2] >= 60.0, "Keep gave {kept:?}");
    // evenly lit all the same: the left and right thirds match
    let out = auto(&photo, &AutoParams { paper: PaperMode::Keep, ..AutoParams::default() }).unwrap().rgb;
    let (left, right) = (mean(&lums(&out, |x, _, i| page.blank[i] && x < W / 3)), mean(&lums(&out, |x, _, i| page.blank[i] && x > 2 * W / 3)));
    assert!((left - right).abs() <= 4.0, "kept paper is uneven: {left} and {right}");
}

#[test]
fn small_pages_are_enlarged_to_about_3_6_megapixels() {
    assert_eq!(enlargement(3000.0, 4000.0), 1.0);
    let u = enlargement(600.0, 800.0);
    assert!((600.0 * u * 800.0 * u - 3.6e6).abs() < 1.0, "{u}");
    assert_eq!(enlargement(100.0, 100.0), 4.0);
}

// ---- the filters ----

fn noise_plane(w: usize, h: usize, seed: u64) -> Plane {
    let mut rng = Rng(seed);
    Plane { w, h, d: (0..w * h).map(|_| rng.unit()).collect() }
}

#[test]
fn percentiles_interpolate_like_numpy() {
    assert_eq!(percentile(&mut [4.0, 1.0, 3.0, 2.0], 50.0), 2.5);
    assert_eq!(percentile(&mut [4.0, 1.0, 3.0, 2.0], 75.0), 3.25);
    assert_eq!(percentile(&mut [4.0, 1.0, 3.0, 2.0], 100.0), 4.0);
    assert_eq!(percentile(&mut [5.0], 30.0), 5.0);
    assert_eq!(percentile(&mut [], 50.0), 0.0);
}

#[test]
fn disk_dilation_and_closing_match_the_definition() {
    let src = noise_plane(41, 29, 1);
    for r in [1usize, 2, 5, 9] {
        let got = dilate_disk(&src, r);
        for y in 0..src.h as isize {
            for x in 0..src.w as isize {
                let mut want = f32::NEG_INFINITY;
                for dy in -(r as isize)..=r as isize {
                    let k = (r as f64 * (1.0 - (dy * dy) as f64 / (r * r) as f64).max(0.0).sqrt()).round() as isize;
                    for dx in -k..=k {
                        let (xx, yy) = (x + dx, y + dy);
                        if xx >= 0 && yy >= 0 && xx < src.w as isize && yy < src.h as isize {
                            want = want.max(src.d[yy as usize * src.w + xx as usize]);
                        }
                    }
                }
                assert_eq!(got.d[y as usize * src.w + x as usize], want, "r {r} at ({x}, {y})");
            }
        }
    }
    // closing removes a thin dark line and keeps a wide dark area
    let mut page = Plane::new(60, 60, 1.0);
    for y in 0..60 {
        page.d[y * 60 + 10] = 0.1;
        for x in 30..60 {
            page.d[y * 60 + x] = 0.3;
        }
    }
    let closed = close_disk(&page, 4);
    assert_eq!(closed.d[30 * 60 + 10], 1.0);
    assert_eq!(closed.d[30 * 60 + 45], 0.3);
    assert_eq!(closed.d[30 * 60 + 29], 1.0);
    assert_eq!(closed.d[30 * 60 + 30], 0.3);
}

#[test]
fn the_3x3_median_matches_sorting() {
    let src = noise_plane(23, 17, 2);
    let got = median3(&src);
    for y in 0..src.h {
        for x in 0..src.w {
            let mut n = Vec::new();
            for yy in [y.saturating_sub(1), y, (y + 1).min(src.h - 1)] {
                for xx in [x.saturating_sub(1), x, (x + 1).min(src.w - 1)] {
                    n.push((src.d[yy * src.w + xx] * 255.0) as u8);
                }
            }
            n.sort_unstable();
            assert_eq!(got.d[y * src.w + x], f32::from(n[4]) / 255.0, "at ({x}, {y})");
        }
    }
}

#[test]
fn shrinking_averages_areas_exactly() {
    // a whole factor: block means
    let src = Plane { w: 4, h: 2, d: vec![1.0, 3.0, 5.0, 7.0, 1.0, 3.0, 5.0, 7.0] };
    assert_eq!(area(&src, 2, 1).d, vec![2.0, 6.0]);
    // a fraction: the mean is kept and a flat image stays flat
    let noisy = noise_plane(37, 23, 3);
    let small = area(&noisy, 10, 7);
    let m = |p: &Plane| p.d.iter().sum::<f32>() / p.d.len() as f32;
    assert!((m(&noisy) - m(&small)).abs() < 1e-4);
    assert!(area(&Plane::new(37, 23, 0.4), 10, 7).d.iter().all(|&v| (v - 0.4).abs() < 1e-5));
    // interleaved channels stay apart
    let rows = area_rows((2, 2), 3, (1, 1), |_, row| row.copy_from_slice(&[0.0, 0.5, 1.0, 0.2, 0.5, 0.6]));
    assert!((rows[0] - 0.1).abs() < 1e-6 && (rows[1] - 0.5).abs() < 1e-6 && (rows[2] - 0.8).abs() < 1e-6);
    // RGB8 by a whole factor, the remainder left out
    let (out, w, h) = shrink_rgb(&[0, 0, 0, 255, 255, 255, 9, 9, 9, 255, 255, 255, 0, 0, 0, 9, 9, 9], 3, 2, 2);
    assert_eq!((w, h), (1, 1));
    assert!(out.iter().all(|&v| (v - 0.5).abs() < 1e-6));
}

#[test]
fn the_wide_blur_is_close_to_a_gaussian() {
    let mut src = Plane::new(201, 201, 0.0);
    src.d[100 * 201 + 100] = 1.0;
    for sigma in [2.0f32, 6.0, 20.0] {
        let b = wide_blur(&src, sigma);
        let total: f32 = b.d.iter().sum();
        let var: f32 = (0..201).map(|x| b.row(100)[x] * (x as f32 - 100.0).powi(2)).sum::<f32>() / b.row(100).iter().sum::<f32>();
        assert!((total - 1.0).abs() < 1e-3, "sigma {sigma}: an impulse sums to {total}");
        assert!((var.sqrt() / sigma - 1.0).abs() < 0.1, "sigma {sigma}: measured {}", var.sqrt());
    }
}

#[test]
fn masks_grow_shrink_and_split_into_components() {
    let mut m = Mask::new(12, 9);
    m.data[4 * 12 + 5] = true;
    let grown = grow(&m, 1);
    assert_eq!(grown.data.iter().filter(|&&v| v).count(), 9);
    assert_eq!(shrink(&grown, 1), m);
    // pixels outside the image do not shrink a mask
    let full = Mask { width: 5, height: 4, data: vec![true; 20] };
    assert_eq!(shrink(&full, 2), full);
    // diagonal neighbours belong together; a far pixel is its own component
    let mut two = Mask::new(12, 9);
    for i in [0, 13, 26, 8 * 12 + 11] {
        two.data[i] = true;
    }
    let c = components(&two);
    assert_eq!(c.len(), 2);
    assert_eq!((c[0].pixels.len(), c[0].x1, c[0].y1), (3, 2, 2));
    assert_eq!((c[1].x0, c[1].y0), (11, 8));
}

#[test]
fn stroke_width_is_the_width_of_a_stroke() {
    let mut y = Plane::new(400, 300, 0.9);
    for row in 20..280 {
        for x in (10..390).step_by(11) {
            for dx in 0..3 {
                y.d[row * 400 + x + dx] = 0.1;
            }
        }
    }
    assert_eq!(stroke_width(&y), 3.0);
    assert_eq!(stroke_width(&Plane::new(400, 300, 0.9)), 3.0);
}
