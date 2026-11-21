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

use crate::aspect::{SheetFit, sheet_fit};
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
    /// Which places of the network's corner maps the answer that was kept is made of.
    pub pick: CornerPick,
    /// How many of the network's looks had the four surest places refused for their shape.
    pub refused: u8,
    /// How far the kept answer is from a rectangular sheet seen by a camera, in degrees
    /// ([`crate::aspect::SheetFit::skew`]); 0 when it was not measured (no page, the line
    /// detector's page, a corner on the photo's border, or the check off).
    pub skew: f32,
    /// What the order of the network's turned looks went by.
    pub order: LooksBy,
}

impl Detection {
    const NOTHING: Detection = Detection {
        quad: None,
        confidence: 0.0,
        source: QuadSource::None,
        angle: 0,
        passes: 0,
        pick: CornerPick::None,
        refused: 0,
        skew: 0.0,
        order: LooksBy::Fixed,
    };
}

/// The order the network's turned looks are made in (#785).
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub enum LookOrder {
    /// Always the same order ([`ANGLES`]): as before.
    #[default]
    Fixed,
    /// The looks that would show the page most nearly upright first. How the page lies is taken
    /// from the outline given (the one the camera showed when the photo was taken) or, without
    /// one, from the unturned look's own corners, however unsure. The same looks are made and
    /// the same rules end the search. A search with no sure look makes them all and has the same
    /// answer; one that ends on a sure look ends on the first sure look of this order, which is
    /// another look than the fixed order's when two turned looks are sure.
    Upright(Option<Quad>),
}

/// What the order of a search's turned looks went by.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum LooksBy {
    /// The fixed order: not asked otherwise, no turned look was needed, or nothing said how the
    /// page lies.
    #[default]
    Fixed,
    /// The outline given with the photo.
    Outline,
    /// The unturned look's own corners.
    FirstLook,
}

/// Which places of the corner maps a page is made of.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum CornerPick {
    /// No page from the network (nothing found, or the line detector's page).
    #[default]
    None,
    /// Each corner map's surest place: the network's answer as it always was read.
    First,
    /// At least one corner is a less sure place of its map: the surest four made no sheet of
    /// paper (corners of different sheets of a pile), and these do.
    Other,
}

/// How the network's corner maps are read.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum CornerRule {
    /// Four corners that cannot be one sheet of paper are refused, and each map's next surest
    /// places are tried in their stead.
    #[default]
    Sheet,
    /// Each map's surest place, whatever shape the four make: as before the check (a testing
    /// choice).
    Surest,
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
/// Four corners further than this from a rectangular sheet seen by a camera, in degrees
/// ([`crate::aspect::SheetFit::skew`]), are not one sheet. On the owner's photos of 2026-11-19
/// the pages found right measured 1.1 at most and the 40 drawn photos of the gate 1.7, before
/// their sides were refined; the three piles whose corners came from different sheets measured
/// 11.5, 11.5 and 13.9, and the line detector's page across a pile 18.
pub const SKEW_MOST: f64 = 7.0;
/// A corner within this share of the photo's width or height of its border may be a corner the
/// frame cut off, put where the sheet's side leaves the photo: such four corners are no
/// rectangle's and are not judged by their shape.
const BORDER: f64 = 0.02;
/// How many places of a corner map are tried: the surest and the next two.
const PLACES: usize = 3;
/// A further place of a corner map must be this sure (the surest is taken whatever it is).
const PLACE_LEAST: f32 = 0.1;
/// ...and this many cells from every surer place of the map (either way).
const PLACE_APART: usize = 4;
/// A quad made with a further place must lean away from the camera by no more than this, in
/// degrees ([`crate::aspect::SheetFit::tilt`]): seen steeply enough, almost any four points pass
/// for a rectangle. The surest four are not held to it.
const OTHER_TILT_MOST: f64 = 50.0;
/// The further angles, in the order their authors found them to rescue a search most often
/// (with the quarter turn the other way added).
const ANGLES: [i16; 6] = [90, -90, -30, 30, -60, 60];
/// Leans are counted in steps of this many degrees when the turned looks are ordered (to the
/// nearest step), so that looks which show the page about equally upright keep the fixed order
/// between them: a page within 3.75 degrees of straight is searched in the fixed order.
const LEAN_STEP: f64 = 7.5;
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

/// How well a quad of the square and the page map (`64 × 64` logits) agree: the cells in both
/// over the cells in either.
fn agreement(corners: &[Point; 4], page: &[f32]) -> f32 {
    let cell = (SIDE / CELLS) as f64;
    let (mut both, mut either) = (0usize, 0usize);
    for (i, &logit) in page.iter().enumerate().take(CELLS * CELLS) {
        let centre = [((i % CELLS) as f64 + 0.5) * cell, ((i / CELLS) as f64 + 0.5) * cell];
        let (in_quad, in_page) = (inside(corners, centre), logit > 0.0);
        both += usize::from(in_quad && in_page);
        either += usize::from(in_quad || in_page);
    }
    both as f32 / either.max(1) as f32
}

/// A place of a corner map as a point of the square: the centre of the probability within two
/// cells of the peak.
fn place_of(map: &[f32], (px, py): (usize, usize)) -> Point {
    let cell = (SIDE / CELLS) as f64;
    let [cx, cy] = soft_argmax_window(map, CELLS, CELLS, (px, py), 2);
    [(cx + 0.5) * cell, (cy + 0.5) * cell]
}

/// Read the corner maps (`4 × 64 × 64` logits) and the page map (`64 × 64` logits): each map's
/// surest place.
pub(crate) fn read(heat: &[f32], page: &[f32]) -> Reading {
    let mut corners = [[0.0; 2]; 4];
    let mut surest_less = 1.0f32;
    for (k, map) in heat.chunks_exact(CELLS * CELLS).enumerate().take(4) {
        let (px, py) = argmax_2d(map, CELLS);
        surest_less = surest_less.min(sigmoid(map[py * CELLS + px]));
        corners[k] = place_of(map, (px, py));
    }
    let agreement = if convex(&corners) { agreement(&corners, page) } else { 0.0 };
    Reading { corners, surest_less, agreement }
}

/// The places of one corner map that are tried: the surest (as [`read`] takes it), then the
/// peaks of the map that are at least [`PLACE_LEAST`] sure and [`PLACE_APART`] cells from every
/// surer one, surest first, [`PLACES`] in all at most. Each as a point of the square and how
/// sure it is.
fn places(map: &[f32]) -> Vec<(Point, f32)> {
    let first = argmax_2d(map, CELLS);
    let at = |x: usize, y: usize| map[y * CELLS + x];
    let least = -((1.0 / PLACE_LEAST - 1.0).ln());
    // a peak: no cell beside it is higher
    let mut peaks: Vec<(usize, usize)> = (0..CELLS * CELLS)
        .map(|i| (i % CELLS, i / CELLS))
        .filter(|&(x, y)| {
            let here = at(x, y);
            here >= least
                && (y.saturating_sub(1)..=(y + 1).min(CELLS - 1)).all(|v| (x.saturating_sub(1)..=(x + 1).min(CELLS - 1)).all(|u| at(u, v) <= here))
        })
        .collect();
    peaks.sort_by(|a, b| at(b.0, b.1).total_cmp(&at(a.0, a.1)).then(a.1.cmp(&b.1)).then(a.0.cmp(&b.0)));
    let mut kept = vec![first];
    for peak in peaks {
        if kept.len() == PLACES {
            break;
        }
        if kept.iter().all(|k| k.0.abs_diff(peak.0).max(k.1.abs_diff(peak.1)) >= PLACE_APART) {
            kept.push(peak);
        }
    }
    kept.into_iter().map(|(x, y)| (place_of(map, (x, y)), sigmoid(at(x, y)))).collect()
}

/// What [`read_sheet`] settled on.
pub(crate) struct Choice {
    pub reading: Reading,
    /// [`CornerPick::None`] when no four places make a sheet: the reading is then each map's
    /// surest place with an agreement of 0.
    pub pick: CornerPick,
    /// The surest four made a quad and it was refused for its shape.
    pub refused: bool,
    /// The skew of the quad taken, 0 when it was not measured.
    pub skew: f32,
}

/// How a quad of the square looks as a sheet in the photo: `None` when it is no page at all there
/// (not convex, too small); `Some(None)` when its shape cannot be judged (a corner on the photo's
/// border); else its fit.
pub(crate) type Judged = Option<Option<SheetFit>>;

/// Read the maps as [`read`] does, and check that the four corners can be one sheet of paper:
/// `judge` says how a quad of the square looks in the photo. The surest four are kept when they
/// can (or cannot be judged): exactly [`read`]'s answer. When they cannot (they are corners of
/// different sheets), or make no page, every other choice of [`places`] is tried, and of those
/// that are a sheet, seen no more steeply than [`OTHER_TILT_MOST`], the one whose least sure
/// corner times its agreement with the page map is highest is taken.
pub(crate) fn read_sheet(heat: &[f32], page: &[f32], judge: impl Fn(&[Point; 4]) -> Judged) -> Choice {
    let first = read(heat, page);
    let mut refused = false;
    if first.agreement > 0.0 {
        match judge(&first.corners) {
            Some(None) => return Choice { reading: first, pick: CornerPick::First, refused: false, skew: 0.0 },
            Some(Some(fit)) if fit.skew <= SKEW_MOST => {
                return Choice { reading: first, pick: CornerPick::First, refused: false, skew: fit.skew as f32 };
            }
            Some(Some(_)) => refused = true,
            None => {}
        }
    }
    let map = CELLS * CELLS;
    let tried: Vec<Vec<(Point, f32)>> = (0..4).filter_map(|k| heat.get(k * map..(k + 1) * map)).map(places).collect();
    let mut best: Option<(f32, Reading, f32)> = None;
    if tried.len() == 4 {
        for i in 1..tried.iter().map(Vec::len).product::<usize>() {
            // the places of this choice: map 3's change fastest (0 is the surest four, done above)
            let mut rest = i;
            let mut of = [0usize; 4];
            for k in (0..4).rev() {
                of[k] = rest % tried[k].len();
                rest /= tried[k].len();
            }
            let corners: [Point; 4] = std::array::from_fn(|k| tried[k][of[k]].0);
            let surest_less = (0..4).map(|k| tried[k][of[k]].1).fold(1.0f32, f32::min);
            // no choice with a less sure corner than the best's score can beat it
            if !convex(&corners) || best.as_ref().is_some_and(|(score, ..)| surest_less <= *score) {
                continue;
            }
            let Some(Some(fit)) = judge(&corners) else { continue };
            if fit.skew > SKEW_MOST || fit.tilt > OTHER_TILT_MOST {
                continue;
            }
            let agreement = agreement(&corners, page);
            let score = surest_less * agreement;
            if best.as_ref().is_none_or(|(most, ..)| score > *most) {
                best = Some((score, Reading { corners, surest_less, agreement }, fit.skew as f32));
            }
        }
    }
    match best {
        Some((_, reading, skew)) => Choice { reading, pick: CornerPick::Other, refused, skew },
        None => Choice { reading: Reading { agreement: 0.0, ..first }, pick: CornerPick::None, refused, skew: 0.0 },
    }
}

/// The network's maps for the square: the four corner maps and the page map, as logits. `None`
/// when the model cannot be run.
pub(crate) fn maps(model: &rten::Model, planes: Vec<f32>) -> Option<(Vec<f32>, Vec<f32>)> {
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
    Some((heat.to_vec(), page.to_vec()))
}

/// The quad the corners of the square `corners` make in the photo: clamped to its borders (a
/// corner the network puts just outside the photo is on its border), `None` when they make no
/// convex quad there or one too small to be a page.
fn in_photo(photo: &Photo, place: &Placement, scale: f64, corners: &[Point; 4]) -> Option<Quad> {
    let (w, h) = (f64::from(photo.width), f64::from(photo.height));
    let corners = corners.map(|p| place.in_small(p).map(|v| v / scale));
    Quad::new(corners.map(|[x, y]| [x.clamp(0.0, w), y.clamp(0.0, h)])).ok().filter(|q| q.area() >= LEAST_AREA * w * h)
}

/// How `quad` fits a rectangular sheet in a `width` × `height` photo, for the sheet check (a fit
/// with a skew over [`SKEW_MOST`] is no sheet): `None`
/// when a corner lies within [`BORDER`] of the photo's border (its shape is not judged).
pub fn sheet_of(quad: &Quad, width: u32, height: u32) -> Option<SheetFit> {
    let (w, h) = (f64::from(width), f64::from(height));
    let on_border = |[x, y]: Point| x < BORDER * w || x > (1.0 - BORDER) * w || y < BORDER * h || y > (1.0 - BORDER) * h;
    if quad.0.iter().any(|p| on_border(*p)) {
        return None;
    }
    // corners no rectangle gives at all are as far from a sheet as can be
    Some(sheet_fit(quad, width, height).unwrap_or(SheetFit { skew: 90.0, tilt: 90.0 }))
}

/// One look of the network: its answer for the photo turned by `degrees`.
#[derive(Clone, Copy, Debug)]
pub(crate) struct Look {
    /// The quad in photo pixels; `None` for none.
    pub quad: Option<Quad>,
    /// 0 without a quad.
    pub confidence: f32,
    pub pick: CornerPick,
    pub refused: bool,
    pub skew: f32,
}

/// The network's answer for the photo turned by `degrees`. `None` when the model cannot be run.
fn pass(model: &rten::Model, photo: &Photo, small: &Picture, scale: f64, degrees: i16, rule: CornerRule) -> Option<Look> {
    let place = Placement::new(small, degrees);
    let (heat, page) = maps(model, square(small, &place))?;
    let choice = match rule {
        CornerRule::Surest => Choice { reading: read(&heat, &page), pick: CornerPick::First, refused: false, skew: 0.0 },
        CornerRule::Sheet => {
            read_sheet(&heat, &page, |corners| in_photo(photo, &place, scale, corners).map(|quad| sheet_of(&quad, photo.width, photo.height)))
        }
    };
    let quad = in_photo(photo, &place, scale, &choice.reading.corners);
    let confidence = if quad.is_some() { choice.reading.surest_less * choice.reading.agreement } else { 0.0 };
    let pick = if confidence > 0.0 { choice.pick } else { CornerPick::None };
    Some(Look { quad, confidence, pick, refused: choice.refused, skew: if confidence > 0.0 { choice.skew } else { 0.0 } })
}

/// How far the page `quad` outlines is from lying upright or on its side when the photo is shown
/// turned by `degrees`: 0 to 45 degrees, from the direction its top and bottom sides run in.
pub(crate) fn lean(quad: &Quad, degrees: i16) -> f64 {
    let q = &quad.0;
    let across = [(q[1][0] - q[0][0]) + (q[2][0] - q[3][0]), (q[1][1] - q[0][1]) + (q[2][1] - q[3][1])];
    let turned = (across[1].atan2(across[0]).to_degrees() + f64::from(degrees)).rem_euclid(90.0);
    turned.min(90.0 - turned)
}

/// The turned looks in the order `order` asks, given the unturned look's own corners, and what
/// that order went by. Looks whose leans are the same number of [`LEAN_STEP`]s keep the fixed
/// order.
pub(crate) fn turned_looks(order: LookOrder, first: Option<&Quad>) -> ([i16; 6], LooksBy) {
    let (by, page) = match (order, first) {
        (LookOrder::Fixed, _) | (LookOrder::Upright(None), None) => return (ANGLES, LooksBy::Fixed),
        (LookOrder::Upright(Some(outline)), _) => (LooksBy::Outline, outline),
        (LookOrder::Upright(None), Some(first)) => (LooksBy::FirstLook, *first),
    };
    let mut angles = ANGLES;
    // a stable sort: equal steps stay in the fixed order
    angles.sort_by_key(|degrees| (lean(&page, *degrees) / LEAN_STEP).round() as i64);
    (angles, by)
}

/// The search over the network's looks: `look` answers for the photo turned by an angle (`None`
/// when the model cannot be run). The unturned look is taken when it is sure enough; else the
/// turned looks follow in `order`, the surest wins, and a sure one ends the search.
pub(crate) fn search(mut look: impl FnMut(i16) -> Option<Look>, order: LookOrder) -> Option<Detection> {
    let first = look(0)?;
    let mut refused = u8::from(first.refused);
    let mut best = Detection {
        quad: first.quad,
        confidence: first.confidence,
        source: QuadSource::Model,
        angle: 0,
        passes: 1,
        pick: first.pick,
        refused: 0,
        skew: first.skew,
        order: LooksBy::Fixed,
    };
    if best.confidence < ACCEPT {
        let (angles, by) = turned_looks(order, first.quad.as_ref());
        best.order = by;
        for angle in angles {
            let look = look(angle)?;
            best.passes += 1;
            refused += u8::from(look.refused);
            if look.confidence > best.confidence + if best.angle == 0 { MARGIN } else { 0.0 } {
                best = Detection { quad: look.quad, confidence: look.confidence, angle, pick: look.pick, skew: look.skew, ..best };
            }
            if best.confidence >= SURE {
                break;
            }
        }
    }
    if best.confidence < LEAST {
        return Some(Detection { passes: best.passes, confidence: best.confidence, refused, order: best.order, ..Detection::NOTHING });
    }
    Some(Detection { refused, ..best })
}

fn with_model(model: &rten::Model, photo: &Photo, rule: CornerRule, order: LookOrder) -> Option<Detection> {
    let (small, scale) = shrink(photo, SIDE);
    search(|degrees| pass(model, photo, &small, scale, degrees, rule), order)
}

/// What one look of the still search answered, for measuring the search (#785).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct LookFact {
    /// The turn the photo was shown at.
    pub degrees: i16,
    /// 0 without a quad.
    pub confidence: f32,
    /// The look's page in photo pixels, whatever its confidence.
    pub quad: Option<Quad>,
}

/// Every look the still search can make at `photo`: unturned, then the turned ones in the
/// search's own order, whether or not the search would have stopped before. For measuring how
/// many looks a search needs; the app never calls it. `None` without the model file.
pub fn every_look(photo: &Photo, rule: CornerRule) -> Option<Vec<LookFact>> {
    let model = load(EDGES)?;
    let (small, scale) = shrink(photo, SIDE);
    std::iter::once(0)
        .chain(ANGLES)
        .map(|degrees| pass(&model, photo, &small, scale, degrees, rule).map(|l| LookFact { degrees, confidence: l.confidence, quad: l.quad }))
        .collect()
}

/// The network's answer from one look at the photo as it is, with no turned looks when it is
/// unsure: for a camera frame (#295), where the next frame is the next try. Under the same least
/// confidence as [`detect`] nothing was found. `None` when the model cannot be run.
pub(crate) fn one_look(model: &rten::Model, photo: &Photo) -> Option<Detection> {
    one_look_at(model, photo, 0, CornerRule::Sheet)
}

/// [`one_look`] with the photo shown to the network turned by `degrees` (#295): a camera frame
/// can try another of the still search's turns each time, as the next frame is the next try.
/// The corners are read by `rule`.
pub(crate) fn one_look_at(model: &rten::Model, photo: &Photo, degrees: i16, rule: CornerRule) -> Option<Detection> {
    if photo.width < 16 || photo.height < 16 {
        return Some(Detection::NOTHING);
    }
    let (small, scale) = shrink(photo, SIDE);
    let look = pass(model, photo, &small, scale, degrees, rule)?;
    let refused = u8::from(look.refused);
    if look.confidence < LEAST {
        return Some(Detection { passes: 1, confidence: look.confidence, angle: degrees, refused, ..Detection::NOTHING });
    }
    Some(Detection {
        quad: look.quad,
        confidence: look.confidence,
        source: QuadSource::Model,
        angle: degrees,
        passes: 1,
        pick: look.pick,
        refused,
        skew: look.skew,
        order: LooksBy::Fixed,
    })
}

/// The turns a look may be given: none, and the still search's own (`ANGLES`).
pub fn look_angle_allowed(degrees: i16) -> bool {
    degrees == 0 || ANGLES.contains(&degrees)
}

/// The engine's line detector on a copy of the photo.
pub(crate) fn with_lines(photo: &Photo) -> Detection {
    let (small, scale) = shrink(photo, LINES_SIDE);
    let rgba: Vec<u8> =
        small.rgb.chunks_exact(3).flat_map(|p| [(p[0] * 255.0 + 0.5) as u8, (p[1] * 255.0 + 0.5) as u8, (p[2] * 255.0 + 0.5) as u8, 255]).collect();
    let found = pdfcraft_engine::camera_edges::detect_document(&rgba, small.w as u32, small.h as u32);
    let quad = found.and_then(|q| Quad::new(q.map(|[x, y]| [x / scale, y / scale])).ok());
    match quad {
        Some(quad) => Detection { quad: Some(quad), confidence: 0.5, source: QuadSource::Lines, angle: 0, passes: 0, ..Detection::NOTHING },
        None => Detection::NOTHING,
    }
}

/// Find the page in `photo`. With the model file in place the network decides, and says so when
/// it finds nothing convincing; without it the engine's line detector is asked.
pub fn detect(photo: &Photo) -> Detection {
    detect_with(photo, Finder::Auto)
}

/// The second look (#290): an unsure network page with at most this many of its sides on edges of
/// the photo has the line detector asked too.
pub const SECOND_LOOK_MOST_SIDES: usize = 2;

/// Whether the line detector is asked after the network's answer `found`, whose page has
/// `network_sides` of its sides on edges of the photo (0 without a page): when the network found
/// nothing, or an unsure page (under 0.7) with at most [`SECOND_LOOK_MOST_SIDES`] sides on edges.
/// Not after the line detector's own answer (no model file).
pub fn second_look_wanted(found: &Detection, network_sides: usize) -> bool {
    found.source != QuadSource::Lines && (found.quad.is_none() || (found.confidence < SURE && network_sides <= SECOND_LOOK_MOST_SIDES))
}

/// Whether the line detector's page, with `lines_sides` of its sides on edges of the photo, is
/// taken: over nothing found, with 3 sides or more; over an unsure network page, with all 4. On
/// the owner's photos a right page had all four sides on edges, a wrong one at most three.
pub fn second_look_takes(network_found: bool, lines_sides: usize) -> bool {
    if network_found { lines_sides == 4 } else { lines_sides >= 3 }
}

/// Find the page in `photo` with the detector `finder` names.
pub fn detect_with(photo: &Photo, finder: Finder) -> Detection {
    detect_by(photo, finder, CornerRule::default())
}

/// [`detect_with`], the network's corner maps read by `rule`.
pub fn detect_by(photo: &Photo, finder: Finder, rule: CornerRule) -> Detection {
    detect_in_order(photo, finder, rule, LookOrder::Fixed)
}

/// [`detect_by`], the network's turned looks made in `order` (#785).
pub fn detect_in_order(photo: &Photo, finder: Finder, rule: CornerRule, order: LookOrder) -> Detection {
    if photo.width < 16 || photo.height < 16 {
        return Detection::NOTHING;
    }
    let network = || load(EDGES).and_then(|model| with_model(&model, photo, rule, order));
    match finder {
        Finder::Auto => network().unwrap_or_else(|| with_lines(photo)),
        Finder::Network => network().unwrap_or(Detection::NOTHING),
        Finder::Lines => with_lines(photo),
        Finder::Off => Detection::NOTHING,
    }
}
