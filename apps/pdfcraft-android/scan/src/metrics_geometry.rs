//! Geometry metrics for the document scanner (issue #288, spec S02 section 3). They score how
//! well the app found a page's four corners against corners a person checked: how much the two
//! quadrilaterals overlap, how far each corner is from its true place, and how far the page's
//! width-to-height ratio is from the true one. Every function is pure and deterministic, and
//! none of them panics: bad input gives a neutral value (0 for overlap, infinity for errors).

use crate::Quad;
use pdfcraft_geom::perspective::Point;

/// A corner that is this much past a straight line (a turn below zero by this amount, in
/// normalised units where the quadrilateral's longer side is 1) still counts as convex. It
/// forgives rounding noise on a corner that is straight to within floating-point error.
const CONVEX_TURN_TOLERANCE: f64 = 1e-12;

/// A quadrilateral whose area is below this, in normalised units (its longer side is 1), has no
/// area: it is a sliver or a line, and its overlap is not meaningful.
const MIN_AREA: f64 = 1e-12;

/// The corner error a person would not correct: half a per cent of the photo's diagonal. A
/// corner this close needs no adjustment by hand (spec S02 section 3, `no_touch`).
const NO_TOUCH_LIMIT: f64 = 0.005;

/// Corner distances of a found quadrilateral against the true one, as shares of the photo's
/// diagonal, so that the numbers mean the same on any photo size.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct CornerError {
    /// The average corner distance, over the four corners, as a share of the diagonal.
    pub mean: f64,
    /// The largest corner distance, as a share of the diagonal.
    pub max: f64,
}

/// Area of overlap over area of union of the two quadrilaterals, 0 to 1. Exact: the overlap is
/// found by polygon clipping (Sutherland-Hodgman), not by sampling pixels, so the issue #288
/// tests can check it against hand-computed values. The corner order and the winding of each
/// quadrilateral do not change the result. A quadrilateral that is not convex, has no area, or
/// has a value that is not finite gives 0.
pub fn iou(found: &Quad, truth: &Quad) -> f64 {
    let Some((a, b)) = normalised(found, truth) else { return 0.0 };
    let (Some(a), Some(b)) = (counter_clockwise(a), counter_clockwise(b)) else { return 0.0 };
    let overlap = signed_area(&clip(&a, &b)).abs();
    let union = signed_area(&a).abs() + signed_area(&b).abs() - overlap;
    if union.is_finite() && union > 0.0 { (overlap / union).clamp(0.0, 1.0) } else { 0.0 }
}

/// The distance of each found corner from its true corner, as shares of the photo's diagonal.
/// Issue #288. The two lists may start at different corners, so the function tries the four
/// cyclic shifts of the found list and keeps the one with the smallest total distance. A photo
/// with a zero width or height, or any corner that is not finite, gives infinity for both
/// `mean` and `max`.
pub fn corner_err(found: &Quad, truth: &Quad, width: u32, height: u32) -> CornerError {
    let neutral = CornerError { mean: f64::INFINITY, max: f64::INFINITY };
    let all_finite = found.0.iter().chain(truth.0.iter()).flatten().all(|v| v.is_finite());
    if width == 0 || height == 0 || !all_finite {
        return neutral;
    }
    let diagonal = f64::from(width).hypot(f64::from(height));
    let distances: [[f64; 4]; 4] = std::array::from_fn(|shift| std::array::from_fn(|i| distance(found.0[i], truth.0[(i + shift) % 4])));
    let totals: [f64; 4] = std::array::from_fn(|shift| distances[shift].iter().sum());
    let best = (0..4).min_by(|&a, &b| totals[a].total_cmp(&totals[b])).unwrap_or(0);
    let max = distances[best].iter().copied().fold(0.0, f64::max);
    CornerError { mean: totals[best] / 4.0 / diagonal, max: max / diagonal }
}

/// True when no corner would have to be moved by the person: `error.max <= 0.005` (issue #288).
/// A non-finite maximum gives false.
pub fn no_touch(error: &CornerError) -> bool {
    error.max <= NO_TOUCH_LIMIT
}

/// The relative error of an estimated width-to-height ratio: |estimated - truth| / truth
/// (issue #288). Infinity when the true ratio is not finite and positive, or the estimate is not
/// finite.
pub fn aspect_err(estimated: f64, truth: f64) -> f64 {
    if !truth.is_finite() || truth <= 0.0 || !estimated.is_finite() {
        return f64::INFINITY;
    }
    (estimated - truth).abs() / truth
}

/// Both quadrilaterals moved so that their combined bounding box starts at 0 and its longer side
/// is 1. Clipping then works on numbers of order one, whatever the photo's size. `None` when a
/// value is not finite, or when the box has no extent or its extent overflows.
fn normalised(found: &Quad, truth: &Quad) -> Option<([Point; 4], [Point; 4])> {
    let all = || found.0.iter().chain(truth.0.iter());
    if all().flatten().any(|v| !v.is_finite()) {
        return None;
    }
    let min_x = all().map(|p| p[0]).fold(f64::INFINITY, f64::min);
    let min_y = all().map(|p| p[1]).fold(f64::INFINITY, f64::min);
    let max_x = all().map(|p| p[0]).fold(f64::NEG_INFINITY, f64::max);
    let max_y = all().map(|p| p[1]).fold(f64::NEG_INFINITY, f64::max);
    let extent = (max_x - min_x).max(max_y - min_y);
    if !extent.is_finite() || extent <= 0.0 {
        return None;
    }
    let map = |q: [Point; 4]| q.map(|[x, y]| [(x - min_x) / extent, (y - min_y) / extent]);
    Some((map(found.0), map(truth.0)))
}

/// The corners in counter-clockwise order (positive signed area), or `None` when the four are not
/// a convex quadrilateral with area. Reversing a clockwise list is what makes the winding of the
/// input irrelevant.
fn counter_clockwise(mut q: [Point; 4]) -> Option<[Point; 4]> {
    let signed = signed_area(&q);
    if !signed.is_finite() || signed.abs() <= MIN_AREA {
        return None;
    }
    if signed < 0.0 {
        q.reverse();
    }
    let turns: [f64; 4] = std::array::from_fn(|i| turn(q[i], q[(i + 1) % 4], q[(i + 2) % 4]));
    turns.iter().all(|&t| t >= -CONVEX_TURN_TOLERANCE).then_some(q)
}

/// The polygon left after keeping only the part of `subject` inside the convex, counter-clockwise
/// `clipper`. Each edge of the clipper is applied in turn.
fn clip(subject: &[Point], clipper: &[Point; 4]) -> Vec<Point> {
    let edges: [(Point, Point); 4] = std::array::from_fn(|i| (clipper[i], clipper[(i + 1) % 4]));
    edges.iter().fold(subject.to_vec(), |polygon, &(a, b)| clip_by_edge(&polygon, a, b))
}

/// The part of the polygon `input` on the inner side of the directed line from `a` to `b`
/// (Sutherland-Hodgman, one edge). Points on the line count as inside.
fn clip_by_edge(input: &[Point], a: Point, b: Point) -> Vec<Point> {
    let mut out = Vec::with_capacity(input.len() + 1);
    let Some(&last) = input.last() else { return out };
    let mut prev = last;
    for &cur in input {
        let (s_prev, s_cur) = (side(a, b, prev), side(a, b, cur));
        if (s_prev < 0.0) != (s_cur < 0.0) {
            out.push(crossing(prev, cur, s_prev, s_cur));
        }
        if s_cur >= 0.0 {
            out.push(cur);
        }
        prev = cur;
    }
    out
}

/// Signed distance-like value of `p` from the directed line `a` to `b`: positive on the left,
/// which is the inside of a counter-clockwise polygon.
fn side(a: Point, b: Point, p: Point) -> f64 {
    (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0])
}

/// The point where the segment from `p` to `q` crosses the line. `sp` and `sq` are the side values
/// of `p` and `q`, which have opposite signs, so the division is safe.
fn crossing(p: Point, q: Point, sp: f64, sq: f64) -> Point {
    let t = sp / (sp - sq);
    [p[0] + t * (q[0] - p[0]), p[1] + t * (q[1] - p[1])]
}

/// Shoelace area with sign: positive for a counter-clockwise polygon (in a y-up reading).
fn signed_area(points: &[Point]) -> f64 {
    points.iter().zip(points.iter().cycle().skip(1)).map(|(p, q)| p[0] * q[1] - q[0] * p[1]).sum::<f64>() / 2.0
}

/// Cross product of the edge a to b with the edge b to c: positive when the path turns left.
fn turn(a: Point, b: Point, c: Point) -> f64 {
    (b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0])
}

/// Straight-line distance between two points.
fn distance(p: Point, q: Point) -> f64 {
    (p[0] - q[0]).hypot(p[1] - q[1])
}
