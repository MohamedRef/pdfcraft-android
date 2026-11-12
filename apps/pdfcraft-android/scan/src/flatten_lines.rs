//! Flatten a curled page from its lines of text, without a model (#303, part B).
//!
//! The flattening network of #302 was trained on single sheets. When it is not installed, or its
//! result was not straighter than the plain perspective warp, a book page that curls into the
//! gutter is still left curled. This is the classical fallback of Matt Zucker's page dewarping
//! (2016, <https://mzucker.github.io/2016/08/15/page-dewarping>): text lines that should be
//! straight and parallel tell the shape of a page that curls in one direction.
//!
//! The model, on the page already straightened by its four corners:
//! - The page is a rectangle in 3D whose height across its width is a cubic with zero value at
//!   both ends and slopes `alpha` and `beta` at the ends: `z = w · g(x / w)` with
//!   `g(u) = (alpha + beta)·u³ − (2·alpha + beta)·u² + alpha·u`.
//! - It has a pose (rotation vector 3, translation 3) before a pinhole camera of focal length
//!   1.2 in units of the page's longer side (Zucker's value: the page is already straightened, so
//!   the photo's own focal length no longer applies).
//! - Each line of text has an unknown height `y_i` on the page, and each point found on it an
//!   unknown position `x_ij` across it.
//!
//! The squared distance between where the model puts each point and where it was found is made
//! as small as it goes with Levenberg–Marquardt. Each point depends on the 8 shared numbers, its
//! line's height and its own position, so the per-point positions are taken out of the normal
//! equations first (a Schur complement) and only a system of 8 + lines unknowns is solved. The
//! derivatives are forward differences. The page is then drawn from the fitted surface, spaced by
//! arc length across its width so that text where the page is steep is not squeezed.
//!
//! Lines of text are found by the same strip tracker as the straightness measure (#288), in 32
//! strips, not by the OCR detection model the issue suggests: the scanner crate has no OCR
//! dependency, and the tracker needs no model. A page needs at least 6 lines that each run across
//! 30 % of its width; otherwise nothing is fitted.

use pdfcraft_geom::perspective::{Homography, Point};

use crate::flatten::FlattenGrid;
use crate::straightness::text_lines;
use crate::{Luma, Quad};

/// Strips the page is cut into to find its lines: a point every 1/32 of the width.
pub const LINE_STRIPS: usize = 32;
/// Fewest lines a page needs before it is fitted (the issue's 6).
pub const MIN_LINES: usize = 6;
/// Shortest line that is used, as a share of the page's width (the issue's 30 %).
pub const MIN_SPAN: f64 = 0.30;
/// The camera's focal length in units of the straightened page's longer side (Zucker's 1.2).
const FOCAL: f64 = 1.2;
/// Most Levenberg–Marquardt steps (the issue's 30).
const MAX_STEPS: u32 = 30;
/// A step that lowers the error by less than this share ends the fit (the issue's 0.1 %).
const MIN_GAIN: f64 = 0.001;
/// Shared numbers: rotation 3, translation 3, alpha, beta.
const GLOBALS: usize = 8;
const ALPHA: usize = 6;
const BETA: usize = 7;
/// Steeper end slopes than this are not a page that curls; the fit is not used.
const MAX_SLOPE: f64 = 1.5;
/// The grid the page is drawn along: the same size as the network's (#302).
pub const GRID_COLS: usize = 31;
pub const GRID_ROWS: usize = 45;
/// Forward-difference step for the derivatives, in the model's units (about 1/1000 of a pixel
/// on a 1000-pixel page).
const STEP: f64 = 1e-6;
/// Samples of the curve across the width for the arc-length spacing.
const ARC_SAMPLES: usize = 512;
/// Largest number of damping retries in one step before the fit stops.
const MAX_RETRIES: u32 = 10;

/// Why no surface was fitted, as written in the diagnostics log and the kept-scan page file.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum GaveUp {
    /// Fewer than [`MIN_LINES`] usable lines of text.
    FewLines,
    /// The fit did not settle on a usable surface (slopes too steep, numbers not finite).
    NoFit,
    /// The surface folds the page over itself when drawn.
    Folds,
}

impl GaveUp {
    pub fn id(self) -> &'static str {
        match self {
            GaveUp::FewLines => "few_lines",
            GaveUp::NoFit => "no_fit",
            GaveUp::Folds => "folds",
        }
    }
}

/// The fitted surface and what the fit found, for the log.
#[derive(Clone, Debug, PartialEq)]
pub struct LineFit {
    /// Lines of text used.
    pub lines: u32,
    /// Points on them.
    pub points: u32,
    /// The surface's slopes at the left and right ends of the page.
    pub alpha: f64,
    pub beta: f64,
    /// Root mean square distance between the points and the model, in page pixels, before the
    /// fit (a flat page facing the camera) and after it.
    pub rms_before: f64,
    pub rms_after: f64,
    /// Levenberg–Marquardt steps taken.
    pub steps: u32,
    /// All shared numbers: rotation 3, translation 3, alpha, beta.
    pub pose: [f64; GLOBALS],
    /// The page's width and height in the model's units (pixels over the longer side).
    pub size: [f64; 2],
    /// The straightened page's size in pixels and the scale of the model's units.
    frame: Frame,
}

/// How the straightened page's pixels map to the model's units: centred, over the longer side.
#[derive(Clone, Copy, Debug, PartialEq)]
struct Frame {
    width: f64,
    height: f64,
    scale: f64,
}

impl Frame {
    fn new(width: f64, height: f64) -> Frame {
        Frame { width, height, scale: width.max(height).max(1.0) }
    }

    fn to_model(self, [x, y]: Point) -> Point {
        [(x - self.width / 2.0) / self.scale, (y - self.height / 2.0) / self.scale]
    }

    fn to_page(self, [x, y]: Point) -> Point {
        [x * self.scale + self.width / 2.0, y * self.scale + self.height / 2.0]
    }
}

/// The height profile `g(u)` and its slope at `u` (0 at the left end, 1 at the right).
fn profile(alpha: f64, beta: f64, u: f64) -> (f64, f64) {
    let (a, b, c) = (alpha + beta, -2.0 * alpha - beta, alpha);
    (((a * u + b) * u + c) * u, (3.0 * a * u + 2.0 * b) * u + c)
}

/// Where the model puts the page point (`x`, `y`) in the model's image units. A point behind the
/// camera is put far away, so that a step that sends it there is refused.
fn project(pose: &[f64; GLOBALS], width: f64, x: f64, y: f64) -> Point {
    let (g, _) = profile(pose[ALPHA], pose[BETA], x / width.max(1e-9));
    let p = rotate([pose[0], pose[1], pose[2]], [x, y, width * g]);
    let c = [p[0] + pose[3], p[1] + pose[4], p[2] + pose[5]];
    if c[2] <= 1e-6 || !c[2].is_finite() {
        return [1e6, 1e6];
    }
    [FOCAL * c[0] / c[2], FOCAL * c[1] / c[2]]
}

/// `v` turned by the rotation vector `r` (Rodrigues).
fn rotate(r: [f64; 3], v: [f64; 3]) -> [f64; 3] {
    let angle = (r[0] * r[0] + r[1] * r[1] + r[2] * r[2]).sqrt();
    if angle < 1e-12 {
        // first order: v + r × v
        return [v[0] + r[1] * v[2] - r[2] * v[1], v[1] + r[2] * v[0] - r[0] * v[2], v[2] + r[0] * v[1] - r[1] * v[0]];
    }
    let k = [r[0] / angle, r[1] / angle, r[2] / angle];
    let (s, c) = angle.sin_cos();
    let cross = [k[1] * v[2] - k[2] * v[1], k[2] * v[0] - k[0] * v[2], k[0] * v[1] - k[1] * v[0]];
    let dot = k[0] * v[0] + k[1] * v[1] + k[2] * v[2];
    [0, 1, 2].map(|i| v[i] * c + cross[i] * s + k[i] * dot * (1.0 - c))
}

/// The fit's unknowns: the shared numbers, a height per line, a position per point.
#[derive(Clone, Debug)]
struct Unknowns {
    pose: [f64; GLOBALS],
    heights: Vec<f64>,
    across: Vec<f64>,
}

/// The points to fit, in the model's units, each with its line.
struct Points {
    seen: Vec<Point>,
    line: Vec<usize>,
    lines: usize,
    width: f64,
}

impl Points {
    fn residual(&self, u: &Unknowns, k: usize) -> [f64; 2] {
        let p = project(&u.pose, self.width, u.across[k], u.heights[self.line[k]]);
        [p[0] - self.seen[k][0], p[1] - self.seen[k][1]]
    }

    fn cost(&self, u: &Unknowns) -> f64 {
        (0..self.seen.len()).map(|k| self.residual(u, k)).map(|r| r[0] * r[0] + r[1] * r[1]).sum()
    }
}

/// One point's derivatives: by the shared numbers and its line's height (9 columns of 2 rows),
/// and by its own position (2 rows), with its residual.
struct PointRows {
    shared: [[f64; GLOBALS + 1]; 2],
    own: [f64; 2],
    r: [f64; 2],
}

fn point_rows(points: &Points, u: &Unknowns, k: usize) -> PointRows {
    let r = points.residual(u, k);
    let mut shared = [[0.0; GLOBALS + 1]; 2];
    let mut moved = u.clone();
    for j in 0..GLOBALS {
        moved.pose[j] += STEP;
        let d = points.residual(&moved, k);
        moved.pose[j] = u.pose[j];
        shared[0][j] = (d[0] - r[0]) / STEP;
        shared[1][j] = (d[1] - r[1]) / STEP;
    }
    let line = points.line[k];
    moved.heights[line] += STEP;
    let d = points.residual(&moved, k);
    moved.heights[line] = u.heights[line];
    shared[0][GLOBALS] = (d[0] - r[0]) / STEP;
    shared[1][GLOBALS] = (d[1] - r[1]) / STEP;
    moved.across[k] += STEP;
    let d = points.residual(&moved, k);
    PointRows { shared, own: [(d[0] - r[0]) / STEP, (d[1] - r[1]) / STEP], r }
}

/// The step for damping `lambda`: the reduced system on the shared numbers and the line heights
/// (the per-point positions taken out by a Schur complement), solved, then the positions back.
fn damped_step(points: &Points, rows: &[PointRows], lambda: f64) -> Option<Unknowns> {
    let n = GLOBALS + points.lines;
    let index = |k: usize, j: usize| if j < GLOBALS { j } else { GLOBALS + points.line[k] };
    // J^T J over the shared block, its diagonal for the damping, and the Schur parts
    let mut normal = vec![0.0f64; n * n];
    let mut rhs = vec![0.0f64; n];
    for (k, row) in rows.iter().enumerate() {
        for a in 0..=GLOBALS {
            let ia = index(k, a);
            rhs[ia] -= row.shared[0][a] * row.r[0] + row.shared[1][a] * row.r[1];
            for b in 0..=GLOBALS {
                normal[ia * n + index(k, b)] += row.shared[0][a] * row.shared[0][b] + row.shared[1][a] * row.shared[1][b];
            }
        }
    }
    let diagonal: Vec<f64> = (0..n).map(|i| normal[i * n + i]).collect();
    for (i, d) in diagonal.iter().enumerate() {
        normal[i * n + i] += lambda * d + 1e-12;
    }
    let mut own_terms = Vec::with_capacity(rows.len());
    for (k, row) in rows.iter().enumerate() {
        let d = (row.own[0] * row.own[0] + row.own[1] * row.own[1]) * (1.0 + lambda) + 1e-12;
        let e: [f64; GLOBALS + 1] = std::array::from_fn(|a| row.shared[0][a] * row.own[0] + row.shared[1][a] * row.own[1]);
        let f = -(row.own[0] * row.r[0] + row.own[1] * row.r[1]);
        for a in 0..=GLOBALS {
            let ia = index(k, a);
            rhs[ia] -= e[a] * f / d;
            for b in 0..=GLOBALS {
                normal[ia * n + index(k, b)] -= e[a] * e[b] / d;
            }
        }
        own_terms.push((d, e, f));
    }
    let delta = solve(normal, rhs, n)?;
    let mut pose = [0.0; GLOBALS];
    for (j, p) in pose.iter_mut().enumerate() {
        *p = delta[j];
    }
    let heights: Vec<f64> = (0..points.lines).map(|i| delta[GLOBALS + i]).collect();
    let across = own_terms
        .iter()
        .enumerate()
        .map(|(k, (d, e, f))| {
            let shared: f64 = (0..=GLOBALS).map(|a| e[a] * delta[index(k, a)]).sum();
            (f - shared) / d
        })
        .collect();
    Some(Unknowns { pose, heights, across })
}

/// `x` with `normal · x = rhs` (Gaussian elimination with partial pivoting on an `n` × `n`
/// matrix stored row by row). `None` when it is singular or a number is not finite.
fn solve(mut a: Vec<f64>, mut b: Vec<f64>, n: usize) -> Option<Vec<f64>> {
    for col in 0..n {
        let pivot = (col..n).max_by(|&p, &q| a[p * n + col].abs().total_cmp(&a[q * n + col].abs()))?;
        let p = a[pivot * n + col];
        if !p.is_finite() || p.abs() < 1e-18 {
            return None;
        }
        if pivot != col {
            for j in 0..n {
                a.swap(pivot * n + j, col * n + j);
            }
            b.swap(pivot, col);
        }
        for row in col + 1..n {
            let k = a[row * n + col] / a[col * n + col];
            if k == 0.0 {
                continue;
            }
            for j in col..n {
                a[row * n + j] -= k * a[col * n + j];
            }
            b[row] -= k * b[col];
        }
    }
    let mut x = vec![0.0; n];
    for row in (0..n).rev() {
        let s: f64 = (row + 1..n).map(|j| a[row * n + j] * x[j]).sum();
        x[row] = (b[row] - s) / a[row * n + row];
    }
    x.iter().all(|v| v.is_finite()).then_some(x)
}

fn add(u: &Unknowns, d: &Unknowns) -> Unknowns {
    Unknowns {
        pose: std::array::from_fn(|j| u.pose[j] + d.pose[j]),
        heights: u.heights.iter().zip(&d.heights).map(|(a, b)| a + b).collect(),
        across: u.across.iter().zip(&d.across).map(|(a, b)| a + b).collect(),
    }
}

/// Fits the curled-page model to lines of points given in the pixels of a `width` × `height`
/// straightened page. `None` when there are fewer than [`MIN_LINES`] lines or the fit gives no
/// usable surface. Deterministic: the same points always give the same numbers.
pub fn fit_lines(lines: &[Vec<Point>], width: f64, height: f64) -> Result<LineFit, GaveUp> {
    let lines: Vec<&Vec<Point>> = lines.iter().filter(|l| l.len() >= 2).collect();
    if lines.len() < MIN_LINES || !(width >= 2.0 && height >= 2.0) {
        return Err(GaveUp::FewLines);
    }
    let frame = Frame::new(width, height);
    let size = [width / frame.scale, height / frame.scale];
    let mut seen = Vec::new();
    let mut line = Vec::new();
    for (i, l) in lines.iter().enumerate() {
        for &p in l.iter() {
            seen.push(frame.to_model(p));
            line.push(i);
        }
    }
    let points = Points { seen, line, lines: lines.len(), width: size[0] };
    // a flat page facing the camera, put where the straightened page is
    let pose = [0.0, 0.0, 0.0, -size[0] / 2.0, -size[1] / 2.0, FOCAL, 0.0, 0.0];
    let heights = (0..points.lines)
        .map(|i| {
            let ys: Vec<f64> = (0..points.seen.len()).filter(|&k| points.line[k] == i).map(|k| points.seen[k][1]).collect();
            ys.iter().sum::<f64>() / ys.len().max(1) as f64 + size[1] / 2.0
        })
        .collect();
    let across = points.seen.iter().map(|p| p[0] + size[0] / 2.0).collect();
    let mut u = Unknowns { pose, heights, across };
    let count = points.seen.len().max(1) as f64;
    let first = points.cost(&u);
    let mut cost = first;
    let mut lambda = 1e-3;
    let mut steps = 0;
    'fit: while steps < MAX_STEPS {
        let rows: Vec<PointRows> = (0..points.seen.len()).map(|k| point_rows(&points, &u, k)).collect();
        let mut retries = 0;
        loop {
            let Some(delta) = damped_step(&points, &rows, lambda) else { break 'fit };
            let next = add(&u, &delta);
            let next_cost = points.cost(&next);
            if next_cost.is_finite() && next_cost < cost {
                let gain = (cost - next_cost) / cost.max(1e-300);
                u = next;
                cost = next_cost;
                lambda = (lambda / 10.0).max(1e-9);
                steps += 1;
                if gain < MIN_GAIN {
                    break 'fit;
                }
                break;
            }
            lambda *= 10.0;
            retries += 1;
            if retries > MAX_RETRIES {
                break 'fit;
            }
        }
    }
    let rms = |c: f64| (c / count).sqrt() * frame.scale;
    let (alpha, beta) = (u.pose[ALPHA], u.pose[BETA]);
    if !u.pose.iter().all(|v| v.is_finite()) || alpha.abs() > MAX_SLOPE || beta.abs() > MAX_SLOPE {
        return Err(GaveUp::NoFit);
    }
    Ok(LineFit {
        lines: u32::try_from(points.lines).unwrap_or(u32::MAX),
        points: u32::try_from(points.seen.len()).unwrap_or(u32::MAX),
        alpha,
        beta,
        rms_before: rms(first),
        rms_after: rms(cost),
        steps,
        pose: u.pose,
        size,
        frame,
    })
}

impl LineFit {
    /// Where the flat page's point (`u`, `v`), shares of its width and height, is on the
    /// straightened page, with the page's width spaced by arc length.
    fn page_point(&self, arc: &[(f64, f64)], u: f64, v: f64) -> Point {
        let x = across_at(arc, u) * self.size[0];
        self.frame.to_page(project(&self.pose, self.size[0], x, v * self.size[1]))
    }

    /// The fitted surface as a sampling grid in photo pixels: the straightened page (straightened
    /// by `quad`) is drawn from the surface, and the surface's four corners are kept on the
    /// straightened page's corners, which are the page's corners found in the photo.
    pub fn grid(&self, quad: &Quad) -> Result<FlattenGrid, GaveUp> {
        let arc = arc_table(self.alpha, self.beta);
        let corners = [[0.0, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 1.0]].map(|[u, v]| self.page_point(&arc, u, v));
        let (w, h) = (self.frame.width, self.frame.height);
        let pin = Homography::from_points(corners, [[0.0, 0.0], [w, 0.0], [w, h], [0.0, h]]).ok_or(GaveUp::NoFit)?;
        let view = Homography::from_points([[0.0, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 1.0]], quad.0).ok_or(GaveUp::NoFit)?;
        let mut xy = Vec::with_capacity(GRID_COLS * GRID_ROWS * 2);
        for row in 0..GRID_ROWS {
            for col in 0..GRID_COLS {
                let (u, v) = (col as f64 / (GRID_COLS - 1) as f64, row as f64 / (GRID_ROWS - 1) as f64);
                let [px, py] = pin.apply(self.page_point(&arc, u, v)).ok_or(GaveUp::NoFit)?;
                let [x, y] = view.apply([px / w, py / h]).ok_or(GaveUp::NoFit)?;
                if !(x.is_finite() && y.is_finite()) {
                    return Err(GaveUp::NoFit);
                }
                xy.extend([x as f32, y as f32]);
            }
        }
        let grid = FlattenGrid { cols: GRID_COLS, rows: GRID_ROWS, xy };
        if grid.folds() != 0 {
            return Err(GaveUp::Folds);
        }
        Ok(grid)
    }
}

/// The curve's arc length from the left end at evenly spaced points across the width, as
/// (share of the width, share of the whole length).
fn arc_table(alpha: f64, beta: f64) -> Vec<(f64, f64)> {
    let mut table = Vec::with_capacity(ARC_SAMPLES + 1);
    let mut length = 0.0;
    let mut last = profile(alpha, beta, 0.0).1.hypot(1.0);
    table.push((0.0, 0.0));
    for i in 1..=ARC_SAMPLES {
        let u = i as f64 / ARC_SAMPLES as f64;
        let here = profile(alpha, beta, u).1.hypot(1.0);
        length += (last + here) / 2.0 / ARC_SAMPLES as f64;
        last = here;
        table.push((u, length));
    }
    let total = length.max(1e-12);
    table.iter().map(|&(u, s)| (u, s / total)).collect()
}

/// The share of the width at which the share `s` of the arc length is reached.
fn across_at(table: &[(f64, f64)], s: f64) -> f64 {
    let i = table.partition_point(|&(_, l)| l < s).clamp(1, table.len() - 1);
    let ((u0, s0), (u1, s1)) = (table[i - 1], table[i]);
    if s1 > s0 { u0 + (u1 - u0) * ((s - s0) / (s1 - s0)).clamp(0.0, 1.0) } else { u0 }
}

/// Finds the lines of text on the straightened `page` and fits the curled-page model to them.
pub fn fit_page(page: &Luma) -> Result<LineFit, GaveUp> {
    let lines = text_lines(page, LINE_STRIPS, MIN_SPAN).ok_or(GaveUp::FewLines)?;
    fit_lines(&lines, f64::from(page.width), f64::from(page.height))
}
