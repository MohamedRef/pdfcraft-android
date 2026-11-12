//! Sub-pixel refinement of the page's sides on the full-resolution photo (#291).
//!
//! A detector sees a small copy of the photo, so its corners are several pixels off at full
//! size. The page's boundary is a long straight edge in the photo: looking for it only in a
//! narrow band around each detected side is both exact and cheap. Each side is searched coarse
//! to fine on a luminance pyramid: scan lines across the side, the steps along each, the line
//! most scan lines agree on (the outermost of similar ones, since the paper's edge lies outside
//! its text and ruled lines), and the corners from the lines' intersections.

use pdfcraft_geom::perspective::Point;

use crate::{Luma, Quad};

/// What was found along one side.
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct SideFit {
    /// The side was moved onto an edge found in the photo (`false`: it is as it was given).
    pub refined: bool,
    /// The share of scan lines that agreed with the fitted line.
    pub inlier_ratio: f32,
    /// The distance of the agreeing points from the line (root mean square, full-size pixels).
    pub rms_px: f32,
    /// The edge bends: the page is not flat.
    pub curved: bool,
    /// How far the edge bows away from a straight line, in full-size pixels.
    pub bow_px: f32,
}

/// The refined corners, and what was found along the top, right, bottom and left sides.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Refined {
    pub quad: Quad,
    pub sides: [SideFit; 4],
}

/// How to refine.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct RefineOptions {
    /// How far from each given side to look, in full-size pixels (`None`: 1.5 % of the photo's
    /// diagonal, at least 8).
    pub search_px: Option<f32>,
    /// Which sides to refine: top, right, bottom, left.
    pub sides: [bool; 4],
}

impl Default for RefineOptions {
    fn default() -> Self {
        RefineOptions { search_px: None, sides: [true; 4] }
    }
}

/// The coarsest level's longer side is at most this.
const COARSE_SIDE: u32 = 1000;
/// The band is never narrower than this many pixels of the level it is searched on.
const MIN_BAND: f64 = 3.0;
/// The ends of a side left out: rounded or dog-eared corners, and the neighbouring side's edge.
const END_MARGIN: f64 = 0.08;
/// Samples per pixel along a scan line.
const STEPS: f64 = 2.0;
/// A step weaker than this share of the side's typical one is not a candidate.
const WEAK: f32 = 0.25;
/// Nor is one weaker than this many grey levels per pixel.
const FAINT: f32 = 0.5;
/// The steps kept per scan line.
const PER_LINE: usize = 4;
/// A line with at least this share of the best line's support counts as much as it.
const SIMILAR: f32 = 0.7;
const RANSAC_ROUNDS: usize = 200;
/// How far (in pixels of the level) a point may be from a line it agrees with.
const INLIER: f64 = 1.0;
/// A fitted line needs this share of the scan lines behind it.
const MIN_INLIERS: f64 = 0.4;
/// A fitted line may turn the side by less than this many degrees.
const MAX_TURN: f64 = 6.0;
/// A bow above this share of the side's length, which a parabola explains this much better than
/// a straight line, is a bent edge.
const BOW_SHARE: f64 = 0.004;
const BOW_GAIN: f64 = 0.7;

/// The photo's luminance at full size and halved until its longer side is at most
/// [`COARSE_SIDE`]. Built once per photo and reused for every refinement of it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Pyramid {
    /// Full size first, each next level half the one before.
    levels: Vec<Luma>,
}

impl Pyramid {
    pub fn new(full: Luma) -> Pyramid {
        let mut levels = vec![full];
        while let Some(last) = levels.last()
            && last.width.max(last.height) > COARSE_SIDE
            && last.width.min(last.height) >= 2
        {
            levels.push(halve(last));
        }
        Pyramid { levels }
    }

    /// The full-size photo's width and height.
    pub fn size(&self) -> (u32, u32) {
        (self.levels[0].width, self.levels[0].height)
    }

    /// The bytes held.
    pub fn bytes(&self) -> usize {
        self.levels.iter().map(|l| l.data.len()).sum()
    }

    /// The part of the photo inside `quad` (full-size pixels) straightened to `out_w` × `out_h`
    /// grey pixels, sampled from the smallest level that still has at least as many pixels
    /// across the quad as the output, so a small output is not aliased from the full size.
    /// Outside the photo is white. `None` for an empty output or corners no homography maps.
    pub fn straighten(&self, quad: &Quad, out_w: u32, out_h: u32) -> Option<Luma> {
        if out_w == 0 || out_h == 0 {
            return None;
        }
        let [top, right, bottom, left] = quad.side_lengths();
        let need = (top.max(bottom) / f64::from(out_w)).min(left.max(right) / f64::from(out_h));
        // level k is 2^k times smaller; take the deepest one still at least as fine as the output
        let mut level = 0;
        while level + 1 < self.levels.len() && need >= f64::from(1u32 << (level + 1)) {
            level += 1;
        }
        let img = &self.levels[level];
        let (sx, sy) = (f64::from(img.width) / f64::from(self.levels[0].width), f64::from(img.height) / f64::from(self.levels[0].height));
        let target = [[0.0, 0.0], [f64::from(out_w), 0.0], [f64::from(out_w), f64::from(out_h)], [0.0, f64::from(out_h)]];
        let scaled = quad.0.map(|[x, y]| [x * sx, y * sy]);
        let map = pdfcraft_geom::perspective::Homography::from_points(target, scaled)?;
        let mut data = Vec::with_capacity(out_w as usize * out_h as usize);
        for row in 0..out_h {
            for col in 0..out_w {
                let v = map.apply([f64::from(col) + 0.5, f64::from(row) + 0.5]).and_then(|[x, y]| sample(img, x, y)).unwrap_or(255.0);
                data.push(v.round().clamp(0.0, 255.0) as u8);
            }
        }
        Some(Luma { width: out_w, height: out_h, data })
    }
}

/// Each pixel the mean of a 2 × 2 block.
fn halve(src: &Luma) -> Luma {
    let (w, h) = ((src.width / 2) as usize, (src.height / 2) as usize);
    let stride = src.width as usize;
    let mut data = Vec::with_capacity(w * h);
    for y in 0..h {
        let (top, bottom) = (&src.data[2 * y * stride..], &src.data[(2 * y + 1) * stride..]);
        data.extend(
            (0..w).map(|x| {
                ((u16::from(top[2 * x]) + u16::from(top[2 * x + 1]) + u16::from(bottom[2 * x]) + u16::from(bottom[2 * x + 1]) + 2) / 4) as u8
            }),
        );
    }
    Luma { width: w as u32, height: h as u32, data }
}

/// The luminance at a point (pixel centres at half-integers), or `None` outside the picture.
fn sample(img: &Luma, x: f64, y: f64) -> Option<f32> {
    let (fx, fy) = (x - 0.5, y - 0.5);
    let (w, h) = (img.width as usize, img.height as usize);
    if w == 0 || h == 0 || !(fx >= 0.0 && fy >= 0.0 && fx <= (w - 1) as f64 && fy <= (h - 1) as f64) {
        return None;
    }
    let (x0, y0) = (fx as usize, fy as usize);
    let (x1, y1) = ((x0 + 1).min(w - 1), (y0 + 1).min(h - 1));
    let (ax, ay) = ((fx - x0 as f64) as f32, (fy - y0 as f64) as f32);
    let px = |x: usize, y: usize| f32::from(img.data[y * w + x]);
    let (top, bottom) = (px(x0, y0) * (1.0 - ax) + px(x1, y0) * ax, px(x0, y1) * (1.0 - ax) + px(x1, y1) * ax);
    Some(top * (1.0 - ay) + bottom * ay)
}

/// A line as a point on it and its unit direction.
#[derive(Clone, Copy, Debug)]
struct Line {
    point: Point,
    dir: Point,
}

impl Line {
    fn through(a: Point, b: Point) -> Option<Line> {
        let (dx, dy) = (b[0] - a[0], b[1] - a[1]);
        let len = dx.hypot(dy);
        (len > 1e-9).then(|| Line { point: a, dir: [dx / len, dy / len] })
    }

    /// The signed distance of `p` from the line (positive on the right of its direction).
    fn distance(&self, p: Point) -> f64 {
        (p[0] - self.point[0]) * self.dir[1] - (p[1] - self.point[1]) * self.dir[0]
    }

    /// How far along the line `p` is.
    fn along(&self, p: Point) -> f64 {
        (p[0] - self.point[0]) * self.dir[0] + (p[1] - self.point[1]) * self.dir[1]
    }

    fn meet(&self, other: &Line) -> Option<Point> {
        let det = self.dir[0] * other.dir[1] - self.dir[1] * other.dir[0];
        if det.abs() < 1e-9 {
            return None;
        }
        let (dx, dy) = (other.point[0] - self.point[0], other.point[1] - self.point[1]);
        let t = (dx * other.dir[1] - dy * other.dir[0]) / det;
        Some([self.point[0] + t * self.dir[0], self.point[1] + t * self.dir[1]])
    }
}

/// A step found on one scan line.
#[derive(Clone, Copy, Debug)]
struct Candidate {
    at: Point,
    weight: f64,
}

/// The derivative of the smoothed luminance along one scan line through `centre` in direction
/// `out`, at [`STEPS`] samples per pixel over `[-reach, reach]`. `None` when the scan line leaves
/// the picture.
fn profile(img: &Luma, centre: Point, out: Point, reach: f64, scratch: &mut Vec<f32>, derivative: &mut Vec<f32>) -> Option<()> {
    // Gaussian of 1 pixel (2 samples), 3 standard deviations each way
    const KERNEL: [f32; 13] = [
        0.002_216, 0.008_764, 0.026_995, 0.064_759, 0.120_985, 0.176_033, 0.199_471, 0.176_033, 0.120_985, 0.064_759, 0.026_995, 0.008_764, 0.002_216,
    ];
    let pad = KERNEL.len() / 2 + 1;
    let half = (reach * STEPS).ceil() as usize;
    let count = 2 * (half + pad) + 1;
    scratch.clear();
    for j in 0..count {
        let t = (j as f64 - (half + pad) as f64) / STEPS;
        scratch.push(sample(img, centre[0] + out[0] * t, centre[1] + out[1] * t)?);
    }
    // smoothed values at half + 1 samples each way, then the central difference per pixel
    let smooth = |j: usize| KERNEL.iter().enumerate().map(|(k, w)| w * scratch[j + k - KERNEL.len() / 2]).sum::<f32>();
    derivative.clear();
    let mut before = smooth(pad - 1);
    let mut here = smooth(pad);
    for j in 0..=2 * half {
        let after = smooth(pad + j + 1);
        derivative.push((after - before) * (STEPS as f32) / 2.0);
        before = here;
        here = after;
    }
    Some(())
}

/// How much lighter the outer quarter of a scan line is than its inner quarter, from the
/// derivative along it.
fn change(derivative: &[f32]) -> f32 {
    let quarter = (derivative.len() / 4).max(1);
    let (mut level, mut inner, mut outer) = (0.0f32, 0.0f32, 0.0f32);
    for (j, d) in derivative.iter().enumerate() {
        level += d;
        if j < quarter {
            inner += level;
        } else if j >= derivative.len() - quarter {
            outer += level;
        }
    }
    (outer - inner) / quarter as f32
}

/// The steps found on each scan line across a side from `a` to `b` (at most [`PER_LINE`], the
/// strongest), with `out` the unit normal pointing away from the page. Scan lines that leave the
/// picture are left out, so the length of the result is the number that lay inside it.
fn candidates(img: &Luma, a: Point, b: Point, out: Point, reach: f64) -> Vec<Vec<Candidate>> {
    let length = (b[0] - a[0]).hypot(b[1] - a[1]);
    let lines = ((length / 4.0) as usize).clamp(32, 400);
    let half = (reach * STEPS).ceil() as usize;
    let (mut scratch, mut derivative) = (Vec::new(), Vec::new());
    let mut profiles: Vec<(Point, Vec<f32>)> = Vec::with_capacity(lines);
    for i in 0..lines {
        let s = END_MARGIN + (1.0 - 2.0 * END_MARGIN) * (i as f64 + 0.5) / lines as f64;
        let centre = [a[0] + (b[0] - a[0]) * s, a[1] + (b[1] - a[1]) * s];
        if profile(img, centre, out, reach, &mut scratch, &mut derivative).is_some() {
            profiles.push((centre, derivative.clone()));
        }
    }
    // The page is lighter or darker than what is around it. Which, is read from the two ends of
    // the band, not from the strongest step: a ruled line near the edge is a pair of steps as
    // strong as the paper's own, one of each sign, and the two cancel between the ends.
    let mut changes: Vec<f32> = profiles.iter().map(|(_, d)| change(d)).collect();
    changes.sort_by(f32::total_cmp);
    let sign = if changes.get(changes.len() / 2).copied().unwrap_or(0.0) < 0.0 { -1.0f32 } else { 1.0 };
    let mut peaks: Vec<f32> = profiles.iter().map(|(_, d)| d.iter().map(|v| v * sign).fold(0.0f32, f32::max)).collect();
    peaks.sort_by(f32::total_cmp);
    let typical = peaks.get(peaks.len() / 2).copied().unwrap_or(0.0);
    profiles
        .iter()
        .map(|(centre, d)| {
            let value = |j: usize| d[j] * sign;
            // local maxima of the right sign that are not weak, strongest first
            let mut maxima: Vec<usize> = (1..d.len().saturating_sub(1))
                .filter(|&j| value(j) >= (WEAK * typical).max(FAINT) && value(j) > value(j - 1) && value(j) >= value(j + 1))
                .collect();
            maxima.sort_by(|&x, &y| value(y).total_cmp(&value(x)));
            maxima.truncate(PER_LINE);
            maxima
                .into_iter()
                .map(|j| {
                    // the peak between samples, from the parabola through three of them
                    let (l, m, r) = (f64::from(value(j - 1)), f64::from(value(j)), f64::from(value(j + 1)));
                    let bend = l - 2.0 * m + r;
                    let shift = if bend < -1e-9 { (0.5 * (l - r) / bend).clamp(-0.5, 0.5) } else { 0.0 };
                    let t = (j as f64 + shift - half as f64) / STEPS;
                    Candidate { at: [centre[0] + out[0] * t, centre[1] + out[1] * t], weight: m }
                })
                .collect()
        })
        .collect()
}

/// A small fixed-seed generator: the same photo always gives the same corners.
struct Xorshift(u64);

impl Xorshift {
    fn below(&mut self, n: usize) -> usize {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        (self.0 % n.max(1) as u64) as usize
    }
}

/// The weighted total-least-squares line through `points` (their principal axis).
fn principal_line(points: &[Candidate], like: &Line) -> Option<Line> {
    let total: f64 = points.iter().map(|c| c.weight).sum();
    if points.len() < 2 || total <= 0.0 {
        return None;
    }
    let mean = [points.iter().map(|c| c.weight * c.at[0]).sum::<f64>() / total, points.iter().map(|c| c.weight * c.at[1]).sum::<f64>() / total];
    let (mut sxx, mut sxy, mut syy) = (0.0, 0.0, 0.0);
    for c in points {
        let (dx, dy) = (c.at[0] - mean[0], c.at[1] - mean[1]);
        sxx += c.weight * dx * dx;
        sxy += c.weight * dx * dy;
        syy += c.weight * dy * dy;
    }
    let angle = 0.5 * (2.0 * sxy).atan2(sxx - syy);
    let mut dir = [angle.cos(), angle.sin()];
    // keep the side's direction
    if dir[0] * like.dir[0] + dir[1] * like.dir[1] < 0.0 {
        dir = [-dir[0], -dir[1]];
    }
    Some(Line { point: mean, dir })
}

/// The candidate of each scan line that lies on `line` (the nearest within [`INLIER`]).
fn agreeing<'a>(lines: &'a [Vec<Candidate>], line: &'a Line) -> impl Iterator<Item = Candidate> + 'a {
    lines.iter().filter_map(move |candidates| {
        candidates.iter().map(|c| (line.distance(c.at).abs(), c)).filter(|(d, _)| *d <= INLIER).min_by(|x, y| x.0.total_cmp(&y.0)).map(|(_, c)| *c)
    })
}

/// The line the scan lines agree on, and the candidates on it. Of the lines that are about as
/// well supported as the best one, the outermost wins: the paper's edge lies outside the text
/// and the ruled lines printed along it.
fn robust_line(lines: &[Vec<Candidate>], side: &Line, mid: Point, out: Point, seed: u64) -> Option<(Line, Vec<Candidate>)> {
    let all: Vec<(usize, Candidate)> = lines.iter().enumerate().flat_map(|(i, c)| c.iter().map(move |c| (i, *c))).collect();
    if lines.iter().filter(|c| !c.is_empty()).count() < 8 {
        return None;
    }
    let turn = |line: &Line| (line.dir[0] * side.dir[1] - line.dir[1] * side.dir[0]).abs();
    // how far out along the side's normal the line passes its middle
    let outward = |line: &Line| -line.distance(mid) / (out[0] * line.dir[1] - out[1] * line.dir[0]);
    let mut rng = Xorshift(seed | 1);
    let mut tried: Vec<(f64, Line)> = Vec::with_capacity(RANSAC_ROUNDS);
    for _ in 0..RANSAC_ROUNDS {
        let ((i, p), (j, q)) = (all[rng.below(all.len())], all[rng.below(all.len())]);
        let Some(line) = Line::through(p.at, q.at).filter(|_| i != j) else { continue };
        if turn(&line) < MAX_TURN.to_radians().sin() {
            tried.push((agreeing(lines, &line).map(|c| c.weight).sum(), line));
        }
    }
    let best = tried.iter().map(|t| t.0).fold(0.0, f64::max);
    let (_, mut line) = tried.into_iter().filter(|t| t.0 >= f64::from(SIMILAR) * best).max_by(|x, y| outward(&x.1).total_cmp(&outward(&y.1)))?;
    let mut inliers: Vec<Candidate> = agreeing(lines, &line).collect();
    // fit, gather again around the fit, fit again
    for _ in 0..2 {
        line = principal_line(&inliers, side)?;
        inliers = agreeing(lines, &line).collect();
    }
    Some((line, inliers))
}

/// How far a parabola through `points` bows away from the straight `line` over `length`, when a
/// parabola fits them clearly better than a straight line does: (bow, bent).
fn bow(points: &[Candidate], line: &Line, length: f64) -> (f64, bool) {
    // positions along the line scaled to -1..1, so the sums stay well conditioned
    let mid = points.iter().map(|c| line.along(c.at)).sum::<f64>() / points.len().max(1) as f64;
    let scale = (length / 2.0).max(1.0);
    let mut kept: Vec<(f64, f64)> = points.iter().map(|c| ((line.along(c.at) - mid) / scale, line.distance(c.at))).collect();
    let mut fit = [0.0; 3];
    // twice: fit, drop what is far from the fit (text, a finger), fit again
    for round in 0..3 {
        if kept.len() < 12 {
            return (0.0, false);
        }
        let mut s = [0.0f64; 5];
        let mut t = [0.0f64; 3];
        for &(u, d) in &kept {
            let powers = [1.0, u, u * u, u * u * u, u * u * u * u];
            for (k, p) in powers.iter().enumerate() {
                s[k] += p;
            }
            for k in 0..3 {
                t[k] += powers[k] * d;
            }
        }
        let Some(solved) = solve3([[s[0], s[1], s[2]], [s[1], s[2], s[3]], [s[2], s[3], s[4]]], t) else { return (0.0, false) };
        fit = solved;
        if round < 2 {
            let limit = (2.0 * INLIER).max(2.5 * rms(kept.iter().map(|&(u, d)| d - (fit[0] + fit[1] * u + fit[2] * u * u))));
            kept.retain(|&(u, d)| (d - (fit[0] + fit[1] * u + fit[2] * u * u)).abs() <= limit);
        }
    }
    // against a straight line through the same points
    let n = kept.len() as f64;
    let (su, sd) = (kept.iter().map(|p| p.0).sum::<f64>(), kept.iter().map(|p| p.1).sum::<f64>());
    let (suu, sud) = (kept.iter().map(|p| p.0 * p.0).sum::<f64>(), kept.iter().map(|p| p.0 * p.1).sum::<f64>());
    let slope = (n * sud - su * sd) / (n * suu - su * su).max(1e-12);
    let offset = (sd - slope * su) / n;
    let straight = rms(kept.iter().map(|&(u, d)| d - (offset + slope * u)));
    let bent = rms(kept.iter().map(|&(u, d)| d - (fit[0] + fit[1] * u + fit[2] * u * u)));
    // a parabola's largest distance from its chord over -1..1 is its second coefficient
    let bow = fit[2].abs();
    (bow, bow > BOW_SHARE * length && bent <= BOW_GAIN * straight)
}

fn rms(values: impl Iterator<Item = f64>) -> f64 {
    let (sum, n) = values.fold((0.0, 0usize), |(s, n), v| (s + v * v, n + 1));
    (sum / n.max(1) as f64).sqrt()
}

/// Solve a 3 × 3 system by Cramer's rule.
fn solve3(m: [[f64; 3]; 3], v: [f64; 3]) -> Option<[f64; 3]> {
    let det = |m: &[[f64; 3]; 3]| {
        m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
            + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])
    };
    let d = det(&m);
    if d.abs() < 1e-12 {
        return None;
    }
    Some(std::array::from_fn(|k| {
        let mut replaced = m;
        for row in 0..3 {
            replaced[row][k] = v[row];
        }
        det(&replaced) / d
    }))
}

/// One side on one level: the fitted line when one is accepted, and what was measured.
struct Found {
    line: Option<Line>,
    fit: SideFit,
}

fn refine_side(img: &Luma, a: Point, b: Point, centre: Point, reach: f64, seed: u64) -> Found {
    let none = Found { line: None, fit: SideFit::default() };
    let Some(side) = Line::through(a, b) else { return none };
    let length = (b[0] - a[0]).hypot(b[1] - a[1]);
    // the normal that points away from the page
    let mid = [(a[0] + b[0]) / 2.0, (a[1] + b[1]) / 2.0];
    let mut out = [side.dir[1], -side.dir[0]];
    if out[0] * (mid[0] - centre[0]) + out[1] * (mid[1] - centre[1]) < 0.0 {
        out = [-out[0], -out[1]];
    }
    let lines = candidates(img, a, b, out, reach);
    let Some((line, inliers)) = robust_line(&lines, &side, mid, out, seed) else { return none };
    let inlier_ratio = inliers.len() as f64 / lines.len().max(1) as f64;
    let turn = (line.dir[0] * side.dir[1] - line.dir[1] * side.dir[0]).abs().asin().to_degrees();
    // for the bow: each scan line's strongest step, wherever it is in the band
    let strongest: Vec<Candidate> = lines.iter().filter_map(|c| c.first().copied()).collect();
    let (bow_px, curved) = bow(&strongest, &line, length);
    let fit = SideFit {
        refined: false,
        inlier_ratio: inlier_ratio as f32,
        rms_px: rms(inliers.iter().map(|c| line.distance(c.at))) as f32,
        curved,
        bow_px: bow_px as f32,
    };
    if inlier_ratio < MIN_INLIERS || turn >= MAX_TURN {
        return Found { line: None, fit };
    }
    Found { line: Some(line), fit: SideFit { refined: true, ..fit } }
}

/// Move the sides of `quad` onto the edges found near them in the photo. A side with no
/// convincing edge stays where it was; if the result would be far from the input (a corner moved
/// by more than twice the search distance, the area changed by a quarter) the input comes back.
pub fn refine(pyramid: &Pyramid, quad: &Quad, options: &RefineOptions) -> Refined {
    let (width, height) = pyramid.size();
    let diagonal = f64::from(width).hypot(f64::from(height));
    let search = options.search_px.map(f64::from).filter(|s| s.is_finite() && *s > 0.0).unwrap_or(0.015 * diagonal).max(8.0);
    let unchanged = Refined { quad: *quad, sides: [SideFit::default(); 4] };
    let coarsest = pyramid.levels.len() - 1;
    // the band in pixels of whichever level is searched: the same on each, so it halves in
    // full-size pixels from one level to the next finer one
    let reach = (search / f64::from(1u32 << coarsest)).max(MIN_BAND);
    let mut corners = quad.0;
    let mut sides = [SideFit::default(); 4];
    for level in (0..=coarsest).rev() {
        let scale = f64::from(1u32 << level);
        let at = corners.map(|[x, y]| [x / scale, y / scale]);
        let centre = [at.iter().map(|p| p[0]).sum::<f64>() / 4.0, at.iter().map(|p| p[1]).sum::<f64>() / 4.0];
        let mut lines: [Option<Line>; 4] = std::array::from_fn(|i| Line::through(at[i], at[(i + 1) % 4]));
        let mut moved = [false; 4];
        for i in (0..4).filter(|&i| options.sides[i]) {
            let found =
                refine_side(&pyramid.levels[level], at[i], at[(i + 1) % 4], centre, reach, 0x9e37_79b9_7f4a_7c15 ^ ((level as u64) << 8) ^ i as u64);
            // the coarsest level's band is the widest, so it is the one that sees a bow; lengths
            // and distances are reported in full-size pixels
            let (bow_px, curved) =
                if level == coarsest { (found.fit.bow_px * scale as f32, found.fit.curved) } else { (sides[i].bow_px, sides[i].curved) };
            // moved on any level, the side is no longer where it was given
            let refined = sides[i].refined || found.line.is_some();
            sides[i] = SideFit { refined, rms_px: found.fit.rms_px * scale as f32, bow_px, curved, ..found.fit };
            if let Some(line) = found.line {
                lines[i] = Some(line);
                moved[i] = true;
            }
        }
        // each corner is where the side before it meets the side after it; a corner between two
        // sides that stayed is not touched
        for i in (0..4).filter(|&i| moved[(i + 3) % 4] || moved[i]) {
            let (Some(before), Some(after)) = (&lines[(i + 3) % 4], &lines[i]) else { return unchanged };
            let Some(p) = before.meet(after) else { return unchanged };
            corners[i] = [p[0] * scale, p[1] * scale];
        }
    }
    if sides.iter().all(|s| !s.refined) {
        return Refined { sides, ..unchanged };
    }
    // inside the photo, as every corner of a page is taken to be
    let corners = corners.map(|[x, y]| [x.clamp(0.0, f64::from(width)), y.clamp(0.0, f64::from(height))]);
    let moved_far = corners.iter().zip(&quad.0).any(|(p, q)| (p[0] - q[0]).hypot(p[1] - q[1]) > 2.0 * search);
    let refined = Quad(corners);
    let area = refined.area() / quad.area().max(1e-9);
    if moved_far || !refined.is_convex() || !(0.8..=1.25).contains(&area) || corners.iter().flatten().any(|v| !v.is_finite()) {
        return unchanged;
    }
    Refined { quad: refined, sides }
}
