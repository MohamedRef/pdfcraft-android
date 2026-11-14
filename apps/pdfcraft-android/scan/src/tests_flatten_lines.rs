//! Flattening a curled page from its lines of text (#303, part B): the fit on exact points, and
//! the whole path on drawn photos of pages curled as a cylinder.

use pdfcraft_geom::perspective::{Homography, Point};

use crate::flatten::unwarp;
use crate::flatten_lines::{GaveUp, MIN_LINES, fit_lines, fit_page};
use crate::page::compare_flattened;
use crate::straightness::{line_straightness, text_lines};
use crate::warp::{Rgb, rectify_with_threads};
use crate::{Luma, Photo, Quad};

/// A camera in photo pixels and a page curled across its width, in the page's own units.
struct Scene {
    /// Photo size and focal length, pixels.
    photo: (u32, u32),
    focal: f64,
    /// Page width and height.
    page: (f64, f64),
    /// End slopes of the curl (the model's alpha and beta).
    alpha: f64,
    beta: f64,
    /// Rotation vector and translation of the page before the camera.
    turn: [f64; 3],
    shift: [f64; 3],
}

fn rotate(r: [f64; 3], v: [f64; 3]) -> [f64; 3] {
    let angle = (r[0] * r[0] + r[1] * r[1] + r[2] * r[2]).sqrt();
    if angle < 1e-12 {
        return v;
    }
    let k = [r[0] / angle, r[1] / angle, r[2] / angle];
    let (s, c) = angle.sin_cos();
    let cross = [k[1] * v[2] - k[2] * v[1], k[2] * v[0] - k[0] * v[2], k[0] * v[1] - k[1] * v[0]];
    let dot = k[0] * v[0] + k[1] * v[1] + k[2] * v[2];
    [0, 1, 2].map(|i| v[i] * c + cross[i] * s + k[i] * dot * (1.0 - c))
}

impl Scene {
    fn height(&self, x: f64) -> f64 {
        let u = x / self.page.0;
        let (a, b, c) = (self.alpha + self.beta, -2.0 * self.alpha - self.beta, self.alpha);
        self.page.0 * ((a * u + b) * u + c) * u
    }

    fn camera(&self, [x, y]: Point) -> [f64; 3] {
        let p = rotate(self.turn, [x, y, self.height(x)]);
        [p[0] + self.shift[0], p[1] + self.shift[1], p[2] + self.shift[2]]
    }

    fn to_photo(&self, q: Point) -> Point {
        let c = self.camera(q);
        let (w, h) = (f64::from(self.photo.0), f64::from(self.photo.1));
        [self.focal * c[0] / c[2] + w / 2.0, self.focal * c[1] / c[2] + h / 2.0]
    }

    /// The page point seen through photo pixel (`px`, `py`): the ray meets the curled surface.
    fn to_page(&self, px: f64, py: f64) -> Point {
        let (w, h) = (f64::from(self.photo.0), f64::from(self.photo.1));
        let d = [(px - w / 2.0) / self.focal, (py - h / 2.0) / self.focal, 1.0];
        let back = [-self.turn[0], -self.turn[1], -self.turn[2]];
        let dir = rotate(back, d);
        let origin = rotate(back, [-self.shift[0], -self.shift[1], -self.shift[2]]);
        let at = |l: f64| [origin[0] + l * dir[0], origin[1] + l * dir[1], origin[2] + l * dir[2]];
        let gap = |l: f64| {
            let q = at(l);
            q[2] - self.height(q[0])
        };
        let mut l = -origin[2] / dir[2];
        for _ in 0..12 {
            let g = gap(l);
            let slope = (gap(l + 1e-6) - g) / 1e-6;
            if slope.abs() < 1e-12 {
                break;
            }
            l -= g / slope;
        }
        let q = at(l);
        [q[0], q[1]]
    }

    fn corners(&self) -> Quad {
        let (pw, ph) = self.page;
        Quad::new([[0.0, 0.0], [pw, 0.0], [pw, ph], [0.0, ph]].map(|p| self.to_photo(p))).unwrap()
    }

    /// The photo: `ink(u, v)` on the page (shares of its width and height), a dark table around
    /// it, each pixel 2 × 2 samples.
    fn photograph(&self, ink: &dyn Fn(f64, f64) -> bool) -> Photo {
        let (w, h) = self.photo;
        let mut rgb = Vec::with_capacity((w * h * 3) as usize);
        for y in 0..h {
            for x in 0..w {
                let mut sum = 0.0f64;
                for k in 0..4 {
                    let [qx, qy] = self.to_page(f64::from(x) + 0.25 + 0.5 * f64::from(k % 2), f64::from(y) + 0.25 + 0.5 * f64::from(k / 2));
                    let (u, v) = (qx / self.page.0, qy / self.page.1);
                    sum += if !(0.0..1.0).contains(&u) || !(0.0..1.0).contains(&v) {
                        60.0
                    } else if ink(u, v) {
                        35.0
                    } else {
                        236.0
                    };
                }
                let g = (sum / 4.0).round() as u8;
                rgb.extend([g, g, g]);
            }
        }
        Photo { width: w, height: h, rgb }
    }
}

/// Lines of words: `lines` rows between 8 % and 92 % of the height, words between 8 % and 92 %
/// of the width.
fn text(lines: usize) -> impl Fn(f64, f64) -> bool {
    move |u: f64, v: f64| {
        if !(0.08..0.92).contains(&u) || !(0.08..0.92).contains(&v) {
            return false;
        }
        let row = (v - 0.08) / 0.84 * lines as f64;
        let line = row.floor();
        let in_bar = row - line < 0.32;
        let word = (u * 11.0 + line * 0.37).fract() < 0.8;
        in_bar && word
    }
}

fn grey(page: &Rgb) -> Luma {
    let data = page.data.chunks_exact(3).map(|p| crate::luma([p[0], p[1], p[2]])).collect();
    Luma { width: page.width, height: page.height, data }
}

/// A book page curled into the gutter on its left, seen from above and a little to the side.
fn curled(alpha: f64, beta: f64, turn: [f64; 3]) -> Scene {
    Scene { photo: (900, 1100), focal: 1300.0, page: (1.0, 1.4), alpha, beta, turn, shift: [-0.5, -0.7, 2.2] }
}

/// The page out of the photo straightened by its corners, and the same page flattened from its
/// lines of text, with the fit.
fn both(scene: &Scene, lines: usize) -> (Rgb, Result<(Rgb, crate::flatten_lines::LineFit), GaveUp>) {
    let photo = scene.photograph(&text(lines));
    let quad = scene.corners();
    let (w, h) = (700, 980);
    let plain = rectify_with_threads(&photo, &quad, w, h, 1).unwrap();
    let flat = fit_page(&grey(&plain)).and_then(|fit| {
        let grid = fit.grid(&quad)?;
        Ok((unwarp(&photo, &grid, w, h, 1).unwrap(), fit))
    });
    (plain, flat)
}

/// Points along `lines` lines of a page curled like `scene`, seen in a 1000 × 1400 straightened
/// page: the scene's photo pixels stand in for the straightened page's.
fn exact_points(scene: &Scene, lines: usize) -> Vec<Vec<Point>> {
    (0..lines)
        .map(|i| {
            let y = scene.page.1 * (0.1 + 0.8 * i as f64 / (lines - 1) as f64);
            (0..24).map(|k| scene.to_photo([scene.page.0 * (0.05 + 0.9 * f64::from(k) / 23.0), y])).collect()
        })
        .collect()
}

/// The model's own camera on a 1000 × 1400 page: focal 1.2 × 1400 pixels, page units of 1/1400.
fn model_scene(alpha: f64, beta: f64, turn: [f64; 3]) -> Scene {
    let (pw, ph) = (1000.0 / 1400.0, 1.0);
    Scene { photo: (1000, 1400), focal: 1.2 * 1400.0, page: (pw, ph), alpha, beta, turn, shift: [-pw / 2.0, -ph / 2.0, 1.2] }
}

#[test]
fn the_fit_finds_the_curl_of_exact_points_within_15_percent() {
    for (alpha, beta) in [(0.35, -0.15), (-0.25, 0.3), (0.5, 0.1)] {
        let scene = model_scene(alpha, beta, [0.0, 0.0, 0.0]);
        let fit = fit_lines(&exact_points(&scene, 12), 1000.0, 1400.0).unwrap();
        assert!((fit.alpha - alpha).abs() <= 0.15 * alpha.abs(), "alpha {} for {alpha}", fit.alpha);
        assert!((fit.beta - beta).abs() <= 0.15 * beta.abs(), "beta {} for {beta}", fit.beta);
        assert!(fit.rms_after < 0.05, "rms after {}", fit.rms_after);
    }
}

/// `lines` straightened as the app does before fitting: the page's four corners in the photo
/// taken to the corners of a 1000 × 1400 page.
fn straightened(scene: &Scene, lines: Vec<Vec<Point>>) -> Vec<Vec<Point>> {
    let (pw, ph) = scene.page;
    let corners = [[0.0, 0.0], [pw, 0.0], [pw, ph], [0.0, ph]].map(|p| scene.to_photo(p));
    let to_page = Homography::from_points(corners, [[0.0, 0.0], [1000.0, 0.0], [1000.0, 1400.0], [0.0, 1400.0]]).unwrap();
    lines.into_iter().map(|l| l.into_iter().map(|p| to_page.apply(p).unwrap()).collect()).collect()
}

#[test]
fn a_page_tilted_25_degrees_is_fitted_with_its_bends_the_right_way() {
    // Once a tilted page is straightened by its corners, the camera's distance from it in the
    // straightened view is unknown, and the size of the curl trades against it: the fit finds
    // slopes from 0.6 to 2 times the truth here. What is fixed is which way each end bends and
    // how the two ends compare, and that the model then lies on the points.
    let tilt = 25f64.to_radians();
    for turn in [[tilt, 0.0, 0.0], [-tilt, 0.0, 0.0], [0.0, tilt, 0.0], [0.0, -tilt, 0.0], [tilt * 0.7, tilt * 0.7, 0.05]] {
        let scene = model_scene(0.4, -0.2, turn);
        let fit = fit_lines(&straightened(&scene, exact_points(&scene, 12)), 1000.0, 1400.0).unwrap();
        let ratio = fit.beta / fit.alpha;
        assert!(fit.alpha > 0.1 && (ratio + 0.5).abs() <= 0.125, "alpha {} beta {} for turn {turn:?}", fit.alpha, fit.beta);
        assert!(fit.rms_after < 0.1 * fit.rms_before, "rms {} -> {}", fit.rms_before, fit.rms_after);
    }
}

#[test]
fn the_fit_never_ends_worse_than_it_starts() {
    // a strong curl from the flat start, and points with a little noise
    let scene = model_scene(0.9, -0.6, [0.2, -0.3, 0.1]);
    let mut lines = exact_points(&scene, 8);
    let mut seed = 7u64;
    for p in lines.iter_mut().flatten() {
        seed ^= seed << 13;
        seed ^= seed >> 7;
        seed ^= seed << 17;
        p[1] += (seed % 1000) as f64 / 1000.0 - 0.5;
    }
    match fit_lines(&lines, 1000.0, 1400.0) {
        Ok(fit) => assert!(fit.rms_after <= fit.rms_before && fit.rms_after < 1.0, "{} -> {}", fit.rms_before, fit.rms_after),
        Err(why) => assert_eq!(why, GaveUp::NoFit),
    }
}

#[test]
fn a_flat_page_fits_with_near_zero_slopes() {
    let scene = model_scene(0.0, 0.0, [0.0, 0.0, 0.0]);
    let fit = fit_lines(&exact_points(&scene, 10), 1000.0, 1400.0).unwrap();
    assert!(fit.alpha.abs() < 0.01 && fit.beta.abs() < 0.01, "alpha {} beta {}", fit.alpha, fit.beta);
}

#[test]
fn fewer_than_six_lines_give_up() {
    let scene = model_scene(0.3, -0.2, [0.0, 0.0, 0.0]);
    assert_eq!(fit_lines(&exact_points(&scene, MIN_LINES - 1), 1000.0, 1400.0), Err(GaveUp::FewLines));
    assert_eq!(fit_lines(&[], 1000.0, 1400.0), Err(GaveUp::FewLines));
}

#[test]
fn a_page_with_three_lines_of_text_gives_up_cleanly() {
    let (_, flat) = both(&curled(0.4, -0.1, [0.15, 0.0, 0.0]), 3);
    assert_eq!(flat.err(), Some(GaveUp::FewLines));
}

#[test]
fn a_curled_page_comes_out_with_lines_at_least_70_percent_straighter() {
    for (alpha, beta, turn) in [(0.45, -0.1, [0.25, 0.0, 0.0]), (0.3, -0.3, [0.35, 0.2, 0.0]), (-0.1, 0.45, [0.3, -0.25, 0.03])] {
        let (plain, flat) = both(&curled(alpha, beta, turn), 18);
        let (flat, fit) = flat.unwrap();
        let before = line_straightness(&grey(&plain)).unwrap().px;
        let after = line_straightness(&grey(&flat)).unwrap().px;
        println!("alpha {alpha} beta {beta} turn {turn:?}: fit alpha {:.3} beta {:.3}, {before:.2} px -> {after:.2} px", fit.alpha, fit.beta);
        assert!(after <= 0.3 * before, "{before:.2} px -> {after:.2} px (alpha {:.3}, beta {:.3})", fit.alpha, fit.beta);
        assert!(compare_flattened(&plain, &flat, crate::flatten_guard::MIN_LINES).kept);
    }
}

#[test]
fn a_flat_page_is_left_to_the_perspective_warp_by_the_guard() {
    let (plain, flat) = both(&curled(0.0, 0.0, [0.3, 0.15, 0.0]), 18);
    let (flat, fit) = flat.unwrap();
    let check = compare_flattened(&plain, &flat, crate::flatten_guard::MIN_LINES);
    println!("flat page: alpha {:.3} beta {:.3}, {:?} -> {:?}", fit.alpha, fit.beta, check.straightened.map(|m| m.px), check.flattened.map(|m| m.px));
    assert!(fit.alpha.abs() < 0.05 && fit.beta.abs() < 0.05, "alpha {} beta {}", fit.alpha, fit.beta);
    assert!(!check.kept);
}

#[test]
fn the_same_page_gives_the_same_grid_twice() {
    let scene = curled(0.4, -0.2, [0.25, 0.1, 0.0]);
    let photo = scene.photograph(&text(16));
    let quad = scene.corners();
    let page = grey(&rectify_with_threads(&photo, &quad, 500, 700, 1).unwrap());
    let a = fit_page(&page).unwrap();
    let b = fit_page(&page).unwrap();
    assert_eq!(a, b);
    assert_eq!(a.grid(&quad).unwrap(), b.grid(&quad).unwrap());
}

#[test]
fn text_lines_follow_a_curled_line_across_the_page() {
    let scene = curled(0.45, -0.1, [0.25, 0.0, 0.0]);
    let photo = scene.photograph(&text(18));
    let page = grey(&rectify_with_threads(&photo, &scene.corners(), 700, 980, 1).unwrap());
    let lines = text_lines(&page, 32, 0.3).unwrap();
    assert!(lines.len() >= 15, "{} lines", lines.len());
    assert!(lines.iter().all(|l| l.len() >= 5));
}

fn png_of(photo: &Photo) -> Vec<u8> {
    let img = image::RgbImage::from_raw(photo.width, photo.height, photo.rgb.clone()).unwrap();
    let mut out = std::io::Cursor::new(Vec::new());
    img.write_to(&mut out, image::ImageFormat::Png).unwrap();
    out.into_inner()
}

#[test]
fn the_fallback_keeps_a_curled_page_flattened_from_its_lines() {
    let scene = curled(0.3, -0.3, [0.35, 0.2, 0.0]);
    let photo = scene.photograph(&text(18));
    let quad = scene.corners();
    let plain = rectify_with_threads(&photo, &quad, 700, 980, 1).unwrap();
    let (kept, outcome) = crate::page::flatten_by_lines(&photo, &quad, &plain, 1, crate::flatten_guard::MIN_LINES);
    assert!(kept.is_some() && outcome.gave_up.is_none());
    assert!(outcome.check.is_some_and(|c| c.kept && c.why == "straighter"), "{outcome:?}");
    assert!(outcome.fit.is_some_and(|f| f.lines >= 15 && f.rms_after < f.rms_before));
}

#[test]
fn the_page_pipeline_runs_the_fallback_only_when_asked_and_logs_why_it_gave_up() {
    let scene = curled(0.4, -0.1, [0.15, 0.0, 0.0]);
    let bytes = png_of(&scene.photograph(&text(2)));
    let corners = scene.corners().to_shares(scene.photo.0, scene.photo.1);
    let ask = |line_flatten| crate::PageRequest { flatten: true, flatten_check: true, line_flatten, ..crate::PageRequest::new(&bytes, corners) };
    let with = crate::page_base(&ask(true)).unwrap();
    // two lines: nothing to measure, so the network's page is not kept and the fallback runs
    assert!(!with.flattened);
    let lines = with.line_flatten.expect("the fallback ran");
    assert_eq!(lines.gave_up, Some("few_lines"));
    assert!(with.timings.iter().any(|&(stage, _)| stage == crate::page::STAGE_FLATTEN_LINES));
    let without = crate::page_base(&ask(false)).unwrap();
    assert_eq!(without.line_flatten, None);
    assert!(without.timings.iter().all(|&(stage, _)| stage != crate::page::STAGE_FLATTEN_LINES));
}

/// In the automatic mode (the check asked for) a page that is the whole photo is not given to
/// the network (#466); the lines of text may still flatten it, and Flatten page by hand still
/// asks the network.
#[test]
fn a_whole_photo_is_not_given_to_the_network_in_the_automatic_mode() {
    let scene = curled(0.3, -0.3, [0.35, 0.2, 0.0]);
    let bytes = png_of(&scene.photograph(&text(18)));
    let whole = crate::page::WHOLE_PHOTO;
    let automatic =
        crate::page_base(&crate::PageRequest { flatten: true, flatten_check: true, line_flatten: true, ..crate::PageRequest::new(&bytes, whole) })
            .unwrap();
    assert_eq!(automatic.flatten_check, None, "the network was asked about a whole photo");
    assert_eq!(automatic.bend, 0.0);
    assert!(automatic.line_flatten.is_some(), "the lines of text were not tried");
    // the page's own corners: the network is asked and its page is checked
    let corners = scene.corners().to_shares(scene.photo.0, scene.photo.1);
    let found =
        crate::page_base(&crate::PageRequest { flatten: true, flatten_check: true, line_flatten: true, ..crate::PageRequest::new(&bytes, corners) })
            .unwrap();
    assert!(found.flatten_check.is_some());
    // by hand, without the check: the network is asked even for the whole photo
    let by_hand = crate::page_base(&crate::PageRequest { flatten: true, ..crate::PageRequest::new(&bytes, whole) }).unwrap();
    assert!(by_hand.flattened && by_hand.bend > 0.0);
}

/// A preview's flattening decision holds at full size (#549): the page made from it is flattened
/// by the same grid without the network or the lines again, and a page the preview left straightened is
/// the page straightened by its corners, pixel for pixel.
#[test]
fn a_previews_flattening_decision_is_taken_over_at_full_size() {
    let scene = curled(0.4, -0.1, [0.15, 0.0, 0.0]);
    let bytes = png_of(&scene.photograph(&text(18)));
    let corners = scene.corners().to_shares(scene.photo.0, scene.photo.1);
    let ask = || crate::PageRequest { flatten: true, flatten_check: true, line_flatten: true, ..crate::PageRequest::new(&bytes, corners) };
    let preview = crate::page_base(&crate::PageRequest { max_side: Some(480), ..ask() }).unwrap();
    assert!(preview.flattened && !preview.flatten_reused);
    let known = preview.flatten_decision.clone().expect("a decision when flattening is asked");
    assert!(known.grid.is_some());
    let fresh = crate::page_base(&ask()).unwrap();
    let reused = crate::page_base(&crate::PageRequest { flatten_known: Some(&known), ..ask() }).unwrap();
    assert!(reused.flattened && reused.flatten_reused);
    assert_eq!(reused.line_flatten, preview.line_flatten);
    assert_eq!((reused.page.width, reused.page.height), (fresh.page.width, fresh.page.height));
    assert!(reused.timings.iter().all(|&(stage, _)| stage != crate::page::STAGE_FLATTEN_LINES && stage != crate::page::STAGE_FLATTEN));
    assert!(fresh.timings.iter().any(|&(stage, _)| stage == crate::page::STAGE_FLATTEN));
    assert_eq!(fresh.flattened, reused.flattened);

    let straight = crate::page::FlattenDecision { grid: None, network: false, bend: 0.0, check: None, line_flatten: None };
    let kept_straight = crate::page_base(&crate::PageRequest { flatten_known: Some(&straight), ..ask() }).unwrap();
    let plain = crate::page_base(&crate::PageRequest::new(&bytes, corners)).unwrap();
    assert!(!kept_straight.flattened && kept_straight.flatten_reused);
    assert_eq!(kept_straight.page, plain.page);
    // without flattening asked, a decision is not used
    let off = crate::page_base(&crate::PageRequest { flatten_known: Some(&known), ..crate::PageRequest::new(&bytes, corners) }).unwrap();
    assert!(!off.flattened && !off.flatten_reused && off.flatten_decision.is_none());
}
