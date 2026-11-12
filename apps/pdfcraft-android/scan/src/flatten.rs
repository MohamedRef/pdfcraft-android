//! Flatten a curved or creased page (#302).
//!
//! A flat page seen at an angle is undone by a perspective warp. A page that curls, sags between
//! two hands or has been folded is not flat, and no perspective warp makes its lines straight.
//! UVDoc (Verhoeven, Magne and Sorkine-Hornung, 2023; MIT) is a small network that looks at the
//! page in 488 × 712 pixels and answers with a 31 × 45 grid: for each point of the flat page,
//! where it is in the photo. The page is then drawn by sampling the photo along that grid,
//! interpolated smoothly between its points.
//!
//! The network runs when the page's shape has to be decided; the sampling runs once, at full
//! size, in place of the perspective warp.

use std::sync::{Mutex, PoisonError};

use pdfcraft_geom::perspective::{Homography, Point};
use rten_tensor::NdTensor;
use rten_tensor::prelude::*;

use crate::flatten_guard::Trim;
use crate::models::{FLATTEN, load};
use crate::warp::{MAX_OUTPUT_PIXELS, MAX_THREADS, Rgb, ToPhoto, resample};
use crate::{Photo, Quad, ScanError};

/// The network's input, and the grid it answers with.
const INPUT: (usize, usize) = (488, 712);
const COLS: usize = 31;
const ROWS: usize = 45;
/// How much of the photo around the page the network is shown, as a share of the page's size on
/// each side: it was trained on pages with some background around them.
const MARGIN: f64 = 0.08;
/// A grid further than this from any perspective view of a flat page (95th percentile over its
/// points, as a share of the page's diagonal) is a page that is not flat. On the owner's ten
/// reference pairs a reference scanner's results were 0.10 % and 0.25 % from a perspective crop
/// for the two truly flat pages and 0.54 % or more for the others (#302).
pub const BENT: f64 = 0.003;

/// Where each point of a regular grid on the flat page is in the photo.
#[derive(Clone, Debug, PartialEq)]
pub struct FlattenGrid {
    pub cols: usize,
    pub rows: usize,
    /// Photo pixels (pixel edges at integers), row by row from the page's top left: x then y.
    pub xy: Vec<f32>,
}

impl FlattenGrid {
    fn at(&self, col: usize, row: usize) -> Point {
        let i = (row * self.cols + col) * 2;
        [f64::from(self.xy[i]), f64::from(self.xy[i + 1])]
    }

    /// How many of the grid's cells are turned over: a page that is mapped onto the photo without
    /// folding over itself has none. Counted, with the points that are not numbers, as folds.
    pub fn folds(&self) -> usize {
        if self.cols < 2 || self.rows < 2 || self.xy.len() != self.cols * self.rows * 2 {
            return usize::MAX;
        }
        // which way round the whole page lies decides which way round a cell must lie
        let side = |a: Point, b: Point, c: Point| (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
        let whole = side(self.at(0, 0), self.at(self.cols - 1, 0), self.at(0, self.rows - 1));
        (0..self.rows - 1)
            .flat_map(|r| (0..self.cols - 1).map(move |c| (c, r)))
            .filter(|&(c, r)| {
                // both halves of the cell: the corner here and the one across from it
                let here = side(self.at(c, r), self.at(c + 1, r), self.at(c, r + 1));
                let across = side(self.at(c + 1, r + 1), self.at(c, r + 1), self.at(c + 1, r));
                !(here * whole > 0.0 && across * whole > 0.0)
            })
            .count()
    }

    /// How far the grid is from the best perspective view of a flat page: the 95th percentile of
    /// its points' distances from where that view puts them, as a share of the page's diagonal in
    /// the photo. Near 0 for a flat page, whatever the angle it was photographed from.
    pub fn bend(&self) -> f64 {
        let flat = |c: usize, r: usize| [c as f64 / (self.cols - 1).max(1) as f64, r as f64 / (self.rows - 1).max(1) as f64];
        let points: Vec<(Point, Point)> =
            (0..self.rows).flat_map(|r| (0..self.cols).map(move |c| (c, r))).map(|(c, r)| (flat(c, r), self.at(c, r))).collect();
        let Some(view) = fit_perspective(&points) else { return 0.0 };
        let mut off: Vec<f64> = points.iter().map(|&(from, to)| view.apply(from).map_or(f64::MAX, |p| (p[0] - to[0]).hypot(p[1] - to[1]))).collect();
        off.sort_by(f64::total_cmp);
        let (a, b, c, d) = (self.at(0, 0), self.at(self.cols - 1, self.rows - 1), self.at(self.cols - 1, 0), self.at(0, self.rows - 1));
        let diagonal = ((a[0] - b[0]).hypot(a[1] - b[1]) + (c[0] - d[0]).hypot(c[1] - d[1])) / 2.0;
        off[off.len() * 95 / 100] / diagonal.max(1.0)
    }
}

/// The perspective map that takes the first point of each pair nearest to the second (least
/// squares on the eight coefficients). The first points must lie in the unit square or near it.
fn fit_perspective(pairs: &[(Point, Point)]) -> Option<Homography> {
    // u·(g·X + h·Y + 1) = a·X + b·Y + c, and the same for v: two rows per pair, eight unknowns
    let mut ata = [[0.0f64; 8]; 8];
    let mut atb = [0.0f64; 8];
    for &([x, y], [u, v]) in pairs {
        for (row, target) in [([x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y], u), ([0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y], v)] {
            for i in 0..8 {
                atb[i] += row[i] * target;
                for j in 0..8 {
                    ata[i][j] += row[i] * row[j];
                }
            }
        }
    }
    // Gaussian elimination with partial pivoting
    for col in 0..8 {
        let pivot = (col..8).max_by(|&a, &b| ata[a][col].abs().total_cmp(&ata[b][col].abs()))?;
        if ata[pivot][col].abs() < 1e-12 {
            return None;
        }
        ata.swap(col, pivot);
        atb.swap(col, pivot);
        for row in col + 1..8 {
            let k = ata[row][col] / ata[col][col];
            let above = ata[col];
            for (cell, above) in ata[row].iter_mut().zip(above).skip(col) {
                *cell -= k * above;
            }
            atb[row] -= k * atb[col];
        }
    }
    let mut h = [0.0f64; 9];
    for i in (0..8).rev() {
        h[i] = (atb[i] - (i + 1..8).map(|j| ata[i][j] * h[j]).sum::<f64>()) / ata[i][i];
    }
    h[8] = 1.0;
    h.iter().all(|v| v.is_finite()).then_some(Homography(h))
}

/// What the network is shown: the page as the perspective warp would straighten it, with a
/// margin of the photo around it, upright. Shown like this, a page lying at any angle or seen
/// from any side looks the same to the network, and what is left for it to find is only what a
/// perspective warp cannot undo.
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct View {
    /// From the flat page (shares 0–1 of its width and height) to the photo.
    pub plane: Homography,
    /// The page is wider than high: the network sees it turned a quarter clockwise.
    pub turned: bool,
}

impl View {
    pub(crate) fn of(quad: &Quad) -> Option<View> {
        let plane = Homography::from_points([[0.0, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 1.0]], quad.0)?;
        let [t, r, b, l] = quad.side_lengths();
        Some(View { plane, turned: t + b > l + r })
    }

    /// The point of the flat page (shares of its width and height; outside 0–1 in the margin)
    /// shown at a point of the network's input (shares 0–1 of its width and height).
    pub(crate) fn to_page(self, [s, t]: Point) -> Point {
        let spread = |v: f64| -MARGIN + v * (1.0 + 2.0 * MARGIN);
        // turned a quarter clockwise, the network's top edge is the page's left edge read upwards
        if self.turned { [spread(t), spread(1.0 - s)] } else { [spread(s), spread(t)] }
    }

    /// The photo point shown at a point of the network's input.
    pub(crate) fn to_photo(self, p: Point) -> Point {
        let page = self.to_page(p);
        self.plane.apply(page).unwrap_or(page)
    }
}

/// The network's input: the view at its size (each pixel the average of a grid of samples of the
/// photo, the photo's border repeated past its edge), three planes of 0–1.
fn input(photo: &Photo, view: &View) -> Vec<f32> {
    let (w, h) = INPUT;
    let (pw, ph) = (photo.width as usize, photo.height as usize);
    // enough samples per input pixel to see every photo pixel it covers along each side
    let (a, b) = (view.to_photo([0.5, 0.0]), view.to_photo([0.5, 1.0]));
    let long = (a[0] - b[0]).hypot(a[1] - b[1]);
    let n = ((long / h as f64).ceil() as usize).clamp(1, 6);
    let mut planes = vec![0f32; 3 * w * h];
    for y in 0..h {
        for x in 0..w {
            let mut sum = [0f32; 3];
            for j in 0..n {
                for i in 0..n {
                    let [px, py] =
                        view.to_photo([(x as f64 + (i as f64 + 0.5) / n as f64) / w as f64, (y as f64 + (j as f64 + 0.5) / n as f64) / h as f64]);
                    let at = ((py.max(0.0) as usize).min(ph - 1) * pw + (px.max(0.0) as usize).min(pw - 1)) * 3;
                    for (total, &v) in sum.iter_mut().zip(&photo.rgb[at..at + 3]) {
                        *total += f32::from(v);
                    }
                }
            }
            for c in 0..3 {
                planes[c * w * h + y * w + x] = sum[c] / (255.0 * (n * n) as f32);
            }
        }
    }
    planes
}

/// Ask the network where the flat page's points are in the photo. `None` when its model file is
/// not installed or cannot be run.
pub fn predict_grid(photo: &Photo, quad: &Quad) -> Option<FlattenGrid> {
    if photo.width < 16 || photo.height < 16 || photo.rgb.len() != photo.width as usize * photo.height as usize * 3 {
        return None;
    }
    let model = load(FLATTEN)?;
    let view = View::of(quad)?;
    let planes = input(photo, &view);
    // one at a time: the runtime uses every core for a single run already
    static RUNNING: Mutex<()> = Mutex::new(());
    let answer = {
        let _one = RUNNING.lock().unwrap_or_else(PoisonError::into_inner);
        let tensor = NdTensor::from_data([1, 3, INPUT.1, INPUT.0], planes);
        let [grid] = model.run_n(vec![(*model.input_ids().first()?, tensor.view().into())], [model.node_id("grid").ok()?], None).ok()?;
        let grid: NdTensor<f32, 4> = grid.try_into().ok()?;
        (grid.shape() == [1, 2, ROWS, COLS]).then(|| grid.to_vec())?
    };
    // an answer that folds the page over itself is no answer
    Some(grid_in_photo(&answer, &view)).filter(|grid| grid.folds() == 0)
}

/// The network's answer (x plane then y plane, each −1…1 over its input, corners aligned with
/// the centres of the border pixels) as a grid on the page as it lies in the photo.
pub(crate) fn grid_in_photo(answer: &[f32], view: &View) -> FlattenGrid {
    // −1 and 1 are the centres of the first and last input pixel
    let share = |v: f32, n: usize| (f64::from(v + 1.0) / 2.0 * (n - 1) as f64 + 0.5) / n as f64;
    let point =
        |col: usize, row: usize| view.to_photo([share(answer[row * COLS + col], INPUT.0), share(answer[ROWS * COLS + row * COLS + col], INPUT.1)]);
    let (cols, rows) = if view.turned { (ROWS, COLS) } else { (COLS, ROWS) };
    let mut xy = Vec::with_capacity(cols * rows * 2);
    for row in 0..rows {
        for col in 0..cols {
            // The network saw the page turned a quarter clockwise: the page's own top left is the
            // network's top right, and going right along the page is going down in its grid.
            let [x, y] = if view.turned { point(COLS - 1 - row, col) } else { point(col, row) };
            xy.extend([x as f32, y as f32]);
        }
    }
    FlattenGrid { cols, rows, xy }
}

/// The weights of the four grid points around a position `t` (0–1) past the second, for a curve
/// through the points with no kinks (Catmull-Rom).
fn smooth(t: f64) -> [f64; 4] {
    let (t2, t3) = (t * t, t * t * t);
    [-0.5 * t3 + t2 - 0.5 * t, 1.5 * t3 - 2.5 * t2 + 1.0, -1.5 * t3 + 2.0 * t2 + 0.5 * t, 0.5 * t3 - 0.5 * t2]
}

/// The grid as a map from the output page to the photo.
struct GridMap<'a> {
    grid: &'a FlattenGrid,
    /// Grid cells per output pixel.
    step: Point,
    /// The grid position of the output's top-left corner: 0, 0 for the whole page, more when the
    /// output is a part of it (a trimmed border, #302).
    origin: Point,
}

impl GridMap<'_> {
    /// A grid point, continued in a straight line past the border so the curve keeps its
    /// direction there.
    fn point(&self, col: i64, row: i64) -> Point {
        let (cols, rows) = (self.grid.cols as i64, self.grid.rows as i64);
        let inside = self.grid.at(col.clamp(0, cols - 1) as usize, row.clamp(0, rows - 1) as usize);
        if (0..cols).contains(&col) && (0..rows).contains(&row) {
            return inside;
        }
        // mirror through the border point: p(−1) = 2·p(0) − p(1)
        let (mc, mr) = (
            if col < 0 {
                -col
            } else if col >= cols {
                2 * (cols - 1) - col
            } else {
                col
            },
            if row < 0 {
                -row
            } else if row >= rows {
                2 * (rows - 1) - row
            } else {
                row
            },
        );
        let mirror = self.grid.at(mc.clamp(0, cols - 1) as usize, mr.clamp(0, rows - 1) as usize);
        [2.0 * inside[0] - mirror[0], 2.0 * inside[1] - mirror[1]]
    }
}

impl ToPhoto for GridMap<'_> {
    fn apply(&self, [x, y]: Point) -> Option<Point> {
        // the output's edges are the page's edges, the grid's first and last points
        let (gx, gy) = (
            (self.origin[0] + x * self.step[0]).clamp(0.0, (self.grid.cols - 1) as f64),
            (self.origin[1] + y * self.step[1]).clamp(0.0, (self.grid.rows - 1) as f64),
        );
        let (c0, r0) = (gx.floor().min((self.grid.cols - 2) as f64), gy.floor().min((self.grid.rows - 2) as f64));
        let (wx, wy) = (smooth(gx - c0), smooth(gy - r0));
        let mut p = [0.0; 2];
        for (j, wy) in wy.iter().enumerate() {
            for (i, wx) in wx.iter().enumerate() {
                let q = self.point(c0 as i64 - 1 + i as i64, r0 as i64 - 1 + j as i64);
                p[0] += wx * wy * q[0];
                p[1] += wx * wy * q[1];
            }
        }
        Some(p)
    }

    fn shrink(&self, [x, y]: Point) -> f64 {
        // how far the photo point moves for one output pixel to the right, and one down
        let (Some(a), Some(b), Some(c), Some(d)) =
            (self.apply([x - 0.5, y]), self.apply([x + 0.5, y]), self.apply([x, y - 0.5]), self.apply([x, y + 0.5]))
        else {
            return 1.0;
        };
        let (dux, dvx, duy, dvy) = (b[0] - a[0], b[1] - a[1], d[0] - c[0], d[1] - c[1]);
        let s = (dux * dvy - duy * dvx).abs().sqrt();
        if s.is_finite() && s > 0.0 { s } else { 1.0 }
    }
}

/// The page drawn flat at `out_w` × `out_h` by sampling the photo along `grid`, on up to
/// `threads` threads (the same pixels for any number). Outside the photo is white.
pub fn unwarp(photo: &Photo, grid: &FlattenGrid, out_w: u32, out_h: u32, threads: usize) -> Result<Rgb, ScanError> {
    unwarp_part(photo, grid, (out_w, out_h), Trim::default(), threads)
}

/// The page drawn flat at `out_w` × `out_h` like [`unwarp`], without the strips `trim` names: the
/// trim is in pixels of the page [`unwarp`] makes at this same size, and what is left of it is
/// drawn at the full `out_w` × `out_h` straight from the photo (one resampling, not a crop and a
/// second one). The proportions stay the page's: the strips were background, not page (#302).
/// A trim that would leave less than half of either side is refused.
pub fn unwarp_trimmed(photo: &Photo, grid: &FlattenGrid, out_w: u32, out_h: u32, trim: Trim, threads: usize) -> Result<Rgb, ScanError> {
    unwarp_part(photo, grid, (out_w, out_h), trim, threads)
}

fn unwarp_part(photo: &Photo, grid: &FlattenGrid, (out_w, out_h): (u32, u32), trim: Trim, threads: usize) -> Result<Rgb, ScanError> {
    let (pw, ph) = (photo.width as usize, photo.height as usize);
    if pw == 0 || ph == 0 || pw.checked_mul(ph).and_then(|n| n.checked_mul(3)) != Some(photo.rgb.len()) {
        return Err(ScanError::Unreadable);
    }
    if out_w < 2
        || out_h < 2
        || f64::from(out_w) * f64::from(out_h) > MAX_OUTPUT_PIXELS
        || grid.cols < 2
        || grid.rows < 2
        || grid.xy.len() != grid.cols * grid.rows * 2
        || grid.xy.iter().any(|v| !v.is_finite())
    {
        return Err(ScanError::BadCorners);
    }
    let (kept_w, kept_h) =
        (f64::from(out_w) - f64::from(trim.left) - f64::from(trim.right), f64::from(out_h) - f64::from(trim.top) - f64::from(trim.bottom));
    if kept_w < f64::from(out_w) / 2.0 || kept_h < f64::from(out_h) / 2.0 {
        return Err(ScanError::BadCorners);
    }
    // grid cells per pixel of the untrimmed page, and the part of it kept stretched over the output
    let cell = [(grid.cols - 1) as f64 / f64::from(out_w), (grid.rows - 1) as f64 / f64::from(out_h)];
    let step = [cell[0] * kept_w / f64::from(out_w), cell[1] * kept_h / f64::from(out_h)];
    let origin = [cell[0] * f64::from(trim.left), cell[1] * f64::from(trim.top)];
    Ok(resample(photo, &GridMap { grid, step, origin }, out_w, out_h, threads.clamp(1, MAX_THREADS)))
}

/// Where [`unwarp`] takes a point of an `out_w` × `out_h` page from (for tests of the map
/// itself).
#[cfg(test)]
pub(crate) fn photo_point(grid: &FlattenGrid, out_w: u32, out_h: u32, p: Point) -> Point {
    let step = [(grid.cols - 1) as f64 / f64::from(out_w), (grid.rows - 1) as f64 / f64::from(out_h)];
    GridMap { grid, step, origin: [0.0, 0.0] }.apply(p).unwrap_or(p)
}

/// The grid a flat page at `quad` has: what [`unwarp`] turns into the plain perspective warp.
pub fn flat_grid(quad: &Quad, cols: usize, rows: usize) -> Option<FlattenGrid> {
    let view = Homography::from_points([[0.0, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 1.0]], quad.0)?;
    let mut xy = Vec::with_capacity(cols * rows * 2);
    for row in 0..rows {
        for col in 0..cols {
            let [x, y] = view.apply([col as f64 / (cols - 1).max(1) as f64, row as f64 / (rows - 1).max(1) as f64])?;
            xy.extend([x as f32, y as f32]);
        }
    }
    Some(FlattenGrid { cols, rows, xy })
}
