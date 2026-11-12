//! Things on a page that are darker or more colourful than paper without being shadows: printed
//! bars, logos, photographs, and fingers holding the page. The light must not be estimated from
//! them, or they would be "corrected" to white (#298, step 3).
//!
//! The thresholds are measured: #298 lists every component of ten real photos with the values
//! below and what a reference scanner did with it.

use super::plane::{Component, Cutout, Mask, Plane, components, gauss, gradient, grow, median, percentile, shrink};

/// A dark area is looked at when the closed image is below this share of the large-scale light.
const LOW: f32 = 0.8;
/// Darker than half the paper around it: print, whatever its edges look like.
const VERY_DARK: f32 = 0.5;
/// Steepness (1/px, measured on the closed copy where print is gone) from which an area's edge
/// is an object's, not a shadow's penumbra. On the ten photos objects measured 0.15–0.20 and
/// shadows 0.01–0.09.
const SHARP: f32 = 0.12;
/// A steep edge is not enough for an area that touches the page border, where hard shadows come
/// in from outside: it must also differ in colour from the paper around it by this much.
const BORDER_COLOUR: f32 = 0.2;
/// Warm areas: red/blue ratio above the lit paper's by this factor, and this much more saturated.
const WARM_RATIO: f32 = 1.22;
const WARM_SATURATION: f32 = 0.10;
/// ...with at least this step in ln(R/B) at the boundary, this steep.
const WARM_JUMP: f32 = 0.25;
const WARM_SHARP: f32 = 0.08;

/// The working-size images the detectors read.
pub(crate) struct Scene<'a> {
    /// The working copy (R, G, B).
    pub small: &'a [Plane; 3],
    /// Its luminance.
    pub y: &'a Plane,
    /// The closed copy: thin dark things removed.
    pub b0: &'a [Plane; 3],
    /// Luminance of `b0`.
    pub yb: &'a Plane,
    /// The large-scale light.
    pub bl: &'a Plane,
}

fn values(p: &Plane, at: impl Iterator<Item = usize>) -> Vec<f32> {
    at.map(|i| p.d[i]).collect()
}

fn median_at(p: &Plane, pixels: &[usize]) -> f32 {
    median(&mut values(p, pixels.iter().copied()))
}

/// The band within `half` pixels of a component's boundary, and the part of it outside the
/// component, as image indices.
fn boundary(c: &Component, w: usize, h: usize, half: usize) -> (Vec<usize>, Vec<usize>) {
    let cut = Cutout::of(c, w, h, half + 1);
    let (outer, inner) = (grow(&cut.mask, half), shrink(&cut.mask, half));
    let ring = Mask { width: outer.width, height: outer.height, data: outer.data.iter().zip(&inner.data).map(|(&o, &i)| o && !i).collect() };
    let outside = Mask { width: ring.width, height: ring.height, data: ring.data.iter().zip(&cut.mask.data).map(|(&r, &m)| r && !m).collect() };
    (cut.indices(&ring, w).collect(), cut.indices(&outside, w).collect())
}

fn touches_border(c: &Component, w: usize, h: usize) -> bool {
    c.x0 == 0 || c.y0 == 0 || c.x1 + 1 >= w || c.y1 + 1 >= h
}

/// The largest difference, over the channels, between two colours each scaled to its own maximum.
fn colour_step(a: [f32; 3], b: [f32; 3]) -> f32 {
    let (ma, mb) = (a.iter().copied().fold(1e-3, f32::max), b.iter().copied().fold(1e-3, f32::max));
    (0..3).map(|c| (a[c] / ma - b[c] / mb).abs()).fold(0.0, f32::max)
}

fn is_dark_object(c: &Component, s: &Scene<'_>, depth: &Plane, grad: &Plane) -> bool {
    let (w, h) = (s.y.w, s.y.h);
    let d50 = median_at(depth, &c.pixels);
    if d50 < VERY_DARK {
        return true;
    }
    let (ring, outside) = boundary(c, w, h, 2);
    let drop = (median_at(s.bl, &c.pixels) - median_at(s.yb, &c.pixels)).max(0.02);
    let sharp = percentile(&mut values(grad, ring.iter().copied()), 75.0) / drop;
    if sharp < SHARP {
        return false;
    }
    if !touches_border(c, w, h) {
        return true;
    }
    let inside = [0, 1, 2].map(|k| median_at(&s.small[k], &c.pixels));
    let around = if outside.is_empty() { inside } else { [0, 1, 2].map(|k| median_at(&s.small[k], &outside)) };
    colour_step(inside, around) > BORDER_COLOUR
}

/// Printed dark areas wider than the closing, and dark things lying on the page.
fn dark_objects(s: &Scene<'_>, into: &mut Mask) {
    let (w, h) = (s.y.w, s.y.h);
    let depth = s.yb.zip(s.bl, |a, b| a / b.max(0.03));
    let low = Mask { width: w, height: h, data: depth.d.iter().map(|&v| v < LOW).collect() };
    // edges are measured on the closed copy: print is gone from it, so text crossing an area's
    // boundary does not make a soft shadow look sharp
    let grad = gradient(s.yb);
    for c in components(&low) {
        if c.pixels.len() < 12 || c.pixels.len() as f32 > 0.30 * (w * h) as f32 {
            continue;
        }
        if is_dark_object(&c, s, &depth, &grad) {
            c.pixels.iter().for_each(|&i| into.data[i] = true);
        }
    }
}

/// ln(R/B) of three planes.
fn warmth(c: &[Plane; 3]) -> Plane {
    c[0].zip(&c[2], |r, b| ((r + 1e-3) / (b + 1e-3)).ln())
}

fn is_warm_object(c: &Component, rbs: &Plane, grad: &Plane) -> bool {
    let (w, h) = (rbs.w, rbs.h);
    let cut = Cutout::of(c, w, h, 8);
    let (near, far, inner) = (grow(&cut.mask, 3), grow(&cut.mask, 7), shrink(&cut.mask, 3));
    let pick = |f: &dyn Fn(usize) -> bool| -> Vec<usize> {
        let m = Mask { width: near.width, height: near.height, data: (0..near.data.len()).map(f).collect() };
        cut.indices(&m, w).collect()
    };
    let ring = pick(&|i| near.data[i] && !inner.data[i]);
    let outside = pick(&|i| far.data[i] && !near.data[i]);
    if outside.is_empty() {
        return false;
    }
    let jump = median_at(rbs, &c.pixels) - median_at(rbs, &outside);
    let sharp = percentile(&mut values(grad, ring.iter().copied()), 75.0) / jump.abs().max(0.03);
    jump >= WARM_JUMP && sharp >= WARM_SHARP
}

/// Skin, wood, cardboard: warmer and more saturated than the lit paper, with a clear colour step
/// at the boundary. (A shadow lit by warm room light is warmer too, but changes gradually.)
fn warm_objects(s: &Scene<'_>, into: &mut Mask) {
    let (w, h) = (s.y.w, s.y.h);
    let rb = warmth(s.b0);
    let sat: Vec<f32> = (0..w * h)
        .map(|i| {
            let (r, g, b) = (s.b0[0].d[i], s.b0[1].d[i], s.b0[2].d[i]);
            (r.max(g).max(b) - r.min(g).min(b)) / r.max(g).max(b).max(1e-3)
        })
        .collect();
    let lit_from = percentile(&mut s.yb.d.clone(), 85.0);
    let lit = || (0..w * h).filter(|&i| s.yb.d[i] >= lit_from);
    let rb_lit = median(&mut lit().map(|i| rb.d[i]).collect::<Vec<_>>());
    let sat_lit = median(&mut lit().map(|i| sat[i]).collect::<Vec<_>>());
    let warm =
        Mask { width: w, height: h, data: (0..w * h).map(|i| rb.d[i] > WARM_RATIO.ln() + rb_lit && sat[i] > WARM_SATURATION + sat_lit).collect() };
    let warm = grow(&shrink(&warm, 1), 1);
    let candidates: Vec<Component> = components(&warm).into_iter().filter(|c| c.pixels.len() as f32 >= 0.001 * (w * h) as f32).collect();
    if candidates.is_empty() {
        return;
    }
    let soft = [gauss(&s.small[0], 1.0), s.small[1].clone(), gauss(&s.small[2], 1.0)];
    let rbs = warmth(&soft);
    let grad = gradient(&rbs);
    for c in candidates.iter().filter(|c| is_warm_object(c, &rbs, &grad)) {
        c.pixels.iter().for_each(|&i| into.data[i] = true);
    }
}

/// The object mask at working size.
pub(crate) fn objects(s: &Scene<'_>) -> Mask {
    let mut mask = Mask::new(s.y.w, s.y.h);
    dark_objects(s, &mut mask);
    warm_objects(s, &mut mask);
    mask
}
