//! Find the page in a photo (#290).
//!
//! A small network (DocQuadNet-256 from the MakeACopy project, Apache-2.0) looks at the photo
//! shrunk into 256 × 256 pixels and answers with a map per corner of where that corner is, and a
//! map of where the page is. The corners are read off the first, checked against the second, and
//! the agreement of the two is the confidence. The network has seen pages roughly upright only:
//! when it is unsure, the photo is shown to it again turned by a few fixed angles, as its authors
//! do. The corners are precise to a few photo pixels at best; [`crate::refine`] makes them exact.
//!
//! The preprocessing follows MakeACopy's `DocQuadDetector` (letterbox on black, RGB 0–1, planar)
//! and the turning its `RotatingDocQuadDetector` (the angles and the thresholds). Both are
//! Copyright 2026 Christian Kierdorf, Apache-2.0; nothing of their code is copied here.
//!
//! Without the model file the search is the engine's line detector, as before.

use std::sync::{Mutex, PoisonError};

use pdfcraft_geom::perspective::Point;
use rten_tensor::NdTensor;
use rten_tensor::prelude::*;

use crate::models::{EDGES, load};
pub(crate) use crate::tensor::{Picture, shrink_rgb};
use crate::tensor::{argmax_2d, sigmoid, soft_argmax_window};
use crate::{Photo, Quad};

/// What found the page.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum QuadSource {
    /// The network.
    Model,
    /// The engine's line detector (the model file is not there).
    Lines,
    /// Nothing convincing was found.
    None,
}

/// Which detector is asked (#288). The scanner's own choice is [`Finder::Auto`]; the others
/// are for testing, so a page found badly can be pinned on the detector that found it.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum Finder {
    /// The network when its model file is there, else the line detector.
    #[default]
    Auto,
    /// The network alone: nothing is found without its model file.
    Network,
    /// The engine's line detector alone, as before the network (#290).
    Lines,
    /// Nothing is looked for: the page is the whole photo.
    Off,
}

/// The page found in a photo.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Detection {
    /// The page's corners in photo pixels, or `None`.
    pub quad: Option<Quad>,
    /// 0–1: for the network, its surest-less corner times how well the corners and its page map
    /// agree.
    pub confidence: f32,
    pub source: QuadSource,
    /// The angle (degrees, clockwise) the photo was turned by for the answer that was kept.
    pub angle: i16,
    /// How many times the network looked.
    pub passes: u8,
}

impl Detection {
    const NOTHING: Detection = Detection { quad: None, confidence: 0.0, source: QuadSource::None, angle: 0, passes: 0 };
}

/// The network's input side, and the side of its maps.
pub(crate) const SIDE: usize = 256;
pub(crate) const CELLS: usize = 64;
/// An unturned answer this sure is taken as it is.
const ACCEPT: f32 = 0.5;
/// Any answer this sure ends the search.
const SURE: f32 = 0.7;
/// A turned answer must be this much surer than the unturned one to replace it: with weak
/// answers all round, a turned one is no better a guess.
const MARGIN: f32 = 0.1;
/// Below this nothing was found.
const LEAST: f32 = 0.45;
/// The page must be at least this share of the photo.
const LEAST_AREA: f64 = 0.05;
/// The further angles, in the order their authors found them to rescue a search most often
/// (with the quarter turn the other way added).
const ANGLES: [i16; 6] = [90, -90, -30, 30, -60, 60];
/// The line detector looks at a copy this long (as the engine's own photo search does).
const LINES_SIDE: usize = 1200;

/// `photo` shrunk so its longer side is `side` at most (each pixel the average of what it
/// covers), and the scale it was shrunk by.
pub(crate) fn shrink(photo: &Photo, side: usize) -> (Picture, f64) {
    shrink_rgb(&photo.rgb, photo.width as usize, photo.height as usize, side)
}

/// How the small picture sits in the network's square: turned by an angle about its centre and
/// shrunk a little more if the turned picture would not fit.
#[derive(Clone, Copy)]
pub(crate) struct Placement {
    cos: f64,
    sin: f64,
    /// Square pixels per small-picture pixel.
    zoom: f64,
    /// The small picture's centre.
    centre: Point,
}

impl Placement {
    pub(crate) fn new(small: &Picture, degrees: i16) -> Placement {
        let (sin, cos) = f64::from(degrees).to_radians().sin_cos();
        let (w, h) = (small.w as f64, small.h as f64);
        let (wide, high) = ((w * cos).abs() + (h * sin).abs(), (w * sin).abs() + (h * cos).abs());
        Placement { cos, sin, zoom: (SIDE as f64 / wide.max(high)).min(1.0), centre: [w / 2.0, h / 2.0] }
    }

    /// The point of the small picture that a point of the square shows.
    pub(crate) fn in_small(self, [u, v]: Point) -> Point {
        let (dx, dy) = ((u - SIDE as f64 / 2.0) / self.zoom, (v - SIDE as f64 / 2.0) / self.zoom);
        [self.centre[0] + dx * self.cos + dy * self.sin, self.centre[1] - dx * self.sin + dy * self.cos]
    }
}

/// The network's input: the small picture placed in the square on black, as three planes.
pub(crate) fn square(small: &Picture, place: &Placement) -> Vec<f32> {
    let mut planes = vec![0f32; 3 * SIDE * SIDE];
    let (w, h) = (small.w as f64, small.h as f64);
    for v in 0..SIDE {
        for u in 0..SIDE {
            let [x, y] = place.in_small([u as f64 + 0.5, v as f64 + 0.5]);
            // half a pixel outside is still the border pixel; beyond that, black
            if x < 0.0 || y < 0.0 || x >= w || y >= h {
                continue;
            }
            let (fx, fy) = ((x - 0.5).clamp(0.0, w - 1.0), (y - 0.5).clamp(0.0, h - 1.0));
            let (x0, y0) = (fx as usize, fy as usize);
            let (x1, y1) = ((x0 + 1).min(small.w - 1), (y0 + 1).min(small.h - 1));
            let (ax, ay) = ((fx - x0 as f64) as f32, (fy - y0 as f64) as f32);
            for c in 0..3 {
                let at = |x: usize, y: usize| small.rgb[(y * small.w + x) * 3 + c];
                let (top, bottom) = (at(x0, y0) + (at(x1, y0) - at(x0, y0)) * ax, at(x0, y1) + (at(x1, y1) - at(x0, y1)) * ax);
                planes[c * SIDE * SIDE + v * SIDE + u] = top + (bottom - top) * ay;
            }
        }
    }
    planes
}

/// What the network's maps say: the four corners in square pixels, how sure the least sure of
/// them is, and how well the quad they make and the page map agree (0 for corners that make no
/// convex quad).
pub(crate) struct Reading {
    pub corners: [Point; 4],
    pub surest_less: f32,
    pub agreement: f32,
}

/// Whether the corners, in the order given, turn the same way at each.
fn convex(q: &[Point; 4]) -> bool {
    let turn = |i: usize| {
        let (a, b, c) = (q[i], q[(i + 1) % 4], q[(i + 2) % 4]);
        (b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0])
    };
    let turns: [f64; 4] = std::array::from_fn(turn);
    turns.iter().all(|t| *t > 1e-9) || turns.iter().all(|t| *t < -1e-9)
}

fn inside(q: &[Point; 4], p: Point) -> bool {
    let side = |i: usize| {
        let (a, b) = (q[i], q[(i + 1) % 4]);
        (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0])
    };
    let sides: [f64; 4] = std::array::from_fn(side);
    sides.iter().all(|s| *s >= 0.0) || sides.iter().all(|s| *s <= 0.0)
}

/// Read the corner maps (`4 × 64 × 64` logits) and the page map (`64 × 64` logits).
pub(crate) fn read(heat: &[f32], page: &[f32]) -> Reading {
    let cell = (SIDE / CELLS) as f64;
    let mut corners = [[0.0; 2]; 4];
    let mut surest_less = 1.0f32;
    for (k, map) in heat.chunks_exact(CELLS * CELLS).enumerate().take(4) {
        let (px, py) = argmax_2d(map, CELLS);
        surest_less = surest_less.min(sigmoid(map[py * CELLS + px]));
        // the centre of the probability within two cells of the peak
        let [cx, cy] = soft_argmax_window(map, CELLS, CELLS, (px, py), 2);
        corners[k] = [(cx + 0.5) * cell, (cy + 0.5) * cell];
    }
    let mut agreement = 0.0;
    if convex(&corners) {
        let (mut both, mut either) = (0usize, 0usize);
        for (i, &logit) in page.iter().enumerate().take(CELLS * CELLS) {
            let centre = [((i % CELLS) as f64 + 0.5) * cell, ((i / CELLS) as f64 + 0.5) * cell];
            let (in_quad, in_page) = (inside(&corners, centre), logit > 0.0);
            both += usize::from(in_quad && in_page);
            either += usize::from(in_quad || in_page);
        }
        agreement = both as f32 / either.max(1) as f32;
    }
    Reading { corners, surest_less, agreement }
}

/// One look of the network at the square. `None` when the model cannot be run.
fn look(model: &rten::Model, planes: Vec<f32>) -> Option<Reading> {
    // one at a time: the runtime uses every core for a single run already
    static RUNNING: Mutex<()> = Mutex::new(());
    let _one = RUNNING.lock().unwrap_or_else(PoisonError::into_inner);
    let input = NdTensor::from_data([1, 3, SIDE, SIDE], planes);
    let ids = [model.node_id("corner_heatmaps").ok()?, model.node_id("mask_logits").ok()?];
    let [heat, page] = model.run_n(vec![(*model.input_ids().first()?, input.view().into())], ids, None).ok()?;
    let (heat, page): (NdTensor<f32, 4>, NdTensor<f32, 4>) = (heat.try_into().ok()?, page.try_into().ok()?);
    if heat.shape() != [1, 4, CELLS, CELLS] || page.shape() != [1, 1, CELLS, CELLS] {
        return None;
    }
    Some(read(&heat.to_vec(), &page.to_vec()))
}

/// The network's answer for the photo turned by `degrees`: the quad in photo pixels and its
/// confidence (0 without a quad).
fn pass(model: &rten::Model, photo: &Photo, small: &Picture, scale: f64, degrees: i16) -> Option<(Option<Quad>, f32)> {
    let place = Placement::new(small, degrees);
    let reading = look(model, square(small, &place))?;
    let corners = reading.corners.map(|p| place.in_small(p).map(|v| v / scale));
    let (w, h) = (f64::from(photo.width), f64::from(photo.height));
    // a corner the network puts just outside the photo is on its border
    let quad = Quad::new(corners.map(|[x, y]| [x.clamp(0.0, w), y.clamp(0.0, h)])).ok().filter(|q| q.area() >= LEAST_AREA * w * h);
    let confidence = if quad.is_some() { reading.surest_less * reading.agreement } else { 0.0 };
    Some((quad, confidence))
}

fn with_model(model: &rten::Model, photo: &Photo) -> Option<Detection> {
    let (small, scale) = shrink(photo, SIDE);
    let (quad, confidence) = pass(model, photo, &small, scale, 0)?;
    let mut best = Detection { quad, confidence, source: QuadSource::Model, angle: 0, passes: 1 };
    if best.confidence < ACCEPT {
        for angle in ANGLES {
            let (quad, confidence) = pass(model, photo, &small, scale, angle)?;
            best.passes += 1;
            if confidence > best.confidence + if best.angle == 0 { MARGIN } else { 0.0 } {
                best = Detection { quad, confidence, angle, ..best };
            }
            if best.confidence >= SURE {
                break;
            }
        }
    }
    if best.confidence < LEAST {
        return Some(Detection { passes: best.passes, confidence: best.confidence, ..Detection::NOTHING });
    }
    Some(best)
}

/// The network's answer from one look at the photo as it is, with no turned looks when it is
/// unsure: for a camera frame (#295), where the next frame is the next try. Under the same least
/// confidence as [`detect`] nothing was found. `None` when the model cannot be run.
pub(crate) fn one_look(model: &rten::Model, photo: &Photo) -> Option<Detection> {
    if photo.width < 16 || photo.height < 16 {
        return Some(Detection::NOTHING);
    }
    let (small, scale) = shrink(photo, SIDE);
    let (quad, confidence) = pass(model, photo, &small, scale, 0)?;
    if confidence < LEAST {
        return Some(Detection { passes: 1, confidence, ..Detection::NOTHING });
    }
    Some(Detection { quad, confidence, source: QuadSource::Model, angle: 0, passes: 1 })
}

/// The engine's line detector on a copy of the photo.
pub(crate) fn with_lines(photo: &Photo) -> Detection {
    let (small, scale) = shrink(photo, LINES_SIDE);
    let rgba: Vec<u8> =
        small.rgb.chunks_exact(3).flat_map(|p| [(p[0] * 255.0 + 0.5) as u8, (p[1] * 255.0 + 0.5) as u8, (p[2] * 255.0 + 0.5) as u8, 255]).collect();
    let found = pdfcraft_engine::camera_edges::detect_document(&rgba, small.w as u32, small.h as u32);
    let quad = found.and_then(|q| Quad::new(q.map(|[x, y]| [x / scale, y / scale])).ok());
    match quad {
        Some(quad) => Detection { quad: Some(quad), confidence: 0.5, source: QuadSource::Lines, angle: 0, passes: 0 },
        None => Detection::NOTHING,
    }
}

/// Find the page in `photo`. With the model file in place the network decides, and says so when
/// it finds nothing convincing; without it the engine's line detector is asked.
pub fn detect(photo: &Photo) -> Detection {
    detect_with(photo, Finder::Auto)
}

/// Find the page in `photo` with the detector `finder` names.
pub fn detect_with(photo: &Photo, finder: Finder) -> Detection {
    if photo.width < 16 || photo.height < 16 {
        return Detection::NOTHING;
    }
    let network = || load(EDGES).and_then(|model| with_model(&model, photo));
    match finder {
        Finder::Auto => network().unwrap_or_else(|| with_lines(photo)),
        Finder::Network => network().unwrap_or(Detection::NOTHING),
        Finder::Lines => with_lines(photo),
        Finder::Off => Detection::NOTHING,
    }
}
