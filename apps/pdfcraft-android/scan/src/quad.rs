//! The page's four corners in a photo.

use pdfcraft_geom::perspective::{Point, order_quad};

use crate::ScanError;

/// How far outside the photo a corner may be, as a share of the photo's width or height (#291):
/// a page whose corner was cut off by the frame has it there, where the two sides meet. Whatever
/// of the page lies outside the photo comes out white.
pub const OUTSIDE: f32 = 0.15;

/// Four corners in photo pixels: top-left, top-right, bottom-right, bottom-left.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Quad(pub [Point; 4]);

impl Quad {
    /// Corners in any order, put in reading order. Refused unless they enclose a convex area.
    pub fn new(points: [Point; 4]) -> Result<Quad, ScanError> {
        order_quad(points).map(Quad).ok_or(ScanError::BadCorners)
    }

    /// Corners as shares of a `width` × `height` photo: x1, y1, … x4, y4. 0–1 is on the photo; a
    /// corner may be up to [`OUTSIDE`] beyond it (a corner the frame cut off), not farther.
    pub fn from_shares(shares: &[f32; 8], width: u32, height: u32) -> Result<Quad, ScanError> {
        let (lo, hi) = (-OUTSIDE - 0.01, 1.0 + OUTSIDE + 0.01);
        if shares.iter().any(|v| !v.is_finite() || !(lo..=hi).contains(v)) {
            return Err(ScanError::BadCorners);
        }
        let (w, h) = (f64::from(width), f64::from(height));
        let (lo, hi) = (-f64::from(OUTSIDE), 1.0 + f64::from(OUTSIDE));
        let p = |i: usize| [f64::from(shares[2 * i]).clamp(lo, hi) * w, f64::from(shares[2 * i + 1]).clamp(lo, hi) * h];
        Quad::new([p(0), p(1), p(2), p(3)])
    }

    /// The corners as shares (0–1) of a `width` × `height` photo, in this quad's order.
    pub fn to_shares(&self, width: u32, height: u32) -> [f32; 8] {
        let (w, h) = (f64::from(width).max(1.0), f64::from(height).max(1.0));
        let mut out = [0.0f32; 8];
        for (i, [x, y]) in self.0.iter().enumerate() {
            out[2 * i] = (x / w) as f32;
            out[2 * i + 1] = (y / h) as f32;
        }
        out
    }

    /// Whether every turn goes the same way (always true for a quad made by [`Quad::new`]).
    pub fn is_convex(&self) -> bool {
        let q = &self.0;
        let cross = |o: Point, a: Point, b: Point| (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
        (0..4).all(|i| cross(q[i], q[(i + 1) % 4], q[(i + 2) % 4]) > 0.0)
    }

    /// The enclosed area in square pixels.
    pub fn area(&self) -> f64 {
        let q = &self.0;
        (0..4).map(|i| q[i][0] * q[(i + 1) % 4][1] - q[(i + 1) % 4][0] * q[i][1]).sum::<f64>().abs() / 2.0
    }

    /// The lengths of the top, right, bottom and left sides.
    pub fn side_lengths(&self) -> [f64; 4] {
        let q = &self.0;
        std::array::from_fn(|i| {
            let (a, b) = (q[i], q[(i + 1) % 4]);
            (a[0] - b[0]).hypot(a[1] - b[1])
        })
    }
}
