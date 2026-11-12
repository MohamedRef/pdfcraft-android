//! Which way up a page is (#339).
//!
//! A phone held flat above a desk does not know which way is up, so pages come out sideways or
//! upside down. A small classifier answers with one of four turns: PP-LCNet x1.0 as the
//! PaddlePaddle authors trained it to tell the orientation of an image with text in it
//! (Apache-2.0).
//!
//! The network looks at 224 × 224 pixels. A whole page shrunk into that has no letters left in
//! it, only a layout, and a layout looks much the same upside down: shown the whole page, the
//! network was right on 32 of 40 turned reference pages. So it is shown **tiles** of the page
//! instead, at a size where a line of print is about a dozen pixels high: the page shrunk to
//! 1120 pixels along its longer side, cut into 224-pixel squares, and of those the nine with
//! the most detail in them. Each tile gives its answer and the answers are put together. That
//! was right on 40 of 40, on the page as it is straightened and before any look. When the four
//! busiest tiles agree outright, the other five are not looked at.
//!
//! What the network is given (read from the `config.yaml` of the `rapid-orientation` package the
//! model file comes from, which is PaddleClas's inference configuration for this model): RGB,
//! each value over 255, less the mean `[0.485, 0.456, 0.406]`, over the deviation
//! `[0.229, 0.224, 0.225]`, one plane per colour. What it answers: the likelihood of each of
//! `0`, `90`, `180`, `270` (the model file's `character` entry, in that order). Class `k` is a
//! page turned **clockwise** by `k` quarter turns, which pages turned four ways settled
//! (`tests_upright.rs`); it is put right by turning it clockwise by `4 − k`.

use std::sync::{Mutex, PoisonError};

use rten_tensor::NdTensor;
use rten_tensor::prelude::*;

use crate::models::{UPRIGHT, load};
use crate::tensor::shrink_rgb;
use crate::warp::Rgb;

/// The side of the square the network looks at.
pub(crate) const TILE: usize = 224;
/// The page's longer side when it is cut into tiles: five tiles along it, so a line of 10-point
/// print on an A4 page is about 13 pixels high.
const LONG_SIDE: usize = 1120;
/// The most tiles looked at: the ones with the most detail.
const MOST: usize = 9;
/// A tile with less detail than this (mean absolute Laplacian of its brightness, of 255) is
/// empty paper and has no say.
const EMPTY: f32 = 3.0;
/// Nor has a tile with under this share of the detail of the busiest one.
const FAINT: f32 = 0.25;
/// When the busiest tiles, this many, all give one answer …
const FIRST: usize = 4;
/// … with at least this share of the vote, the others are not looked at.
const CLEAR: f32 = 0.75;
/// The answer is acted on when it has at least this share of the vote …
const SURE: f32 = 0.5;
/// … and at least this share of the tiles gave it themselves.
const AGREE: f32 = 0.5;

const MEAN: [f32; 3] = [0.485, 0.456, 0.406];
const DEVIATION: [f32; 3] = [0.229, 0.224, 0.225];

/// Which way up a page was found to be.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Upright {
    /// The quarter turns clockwise that put the page upright (0–3).
    pub quarter_turns: u8,
    /// The share of the vote this answer got (0–1; a clean printed page gives about 0.85).
    pub confidence: f32,
    /// The share of the tiles that gave this answer themselves.
    pub agreement: f32,
    /// How many tiles had a say (0 for an empty page).
    pub tiles: u8,
    /// Sure enough to act on.
    pub sure: bool,
}

impl Upright {
    /// Nothing to go by: an empty page, or one too small to cut a tile from.
    const UNKNOWN: Upright = Upright { quarter_turns: 0, confidence: 0.0, agreement: 0.0, tiles: 0, sure: false };
}

/// The tiles of `page` that have a say, each as the network's three planes, busiest first.
pub(crate) fn tiles(page: &Rgb) -> Vec<Vec<f32>> {
    let (w, h) = (page.width as usize, page.height as usize);
    if w == 0 || h == 0 || page.data.len() != w * h * 3 {
        return Vec::new();
    }
    let (small, _) = shrink_rgb(&page.data, w, h, LONG_SIDE);
    let (nx, ny) = (small.w / TILE, small.h / TILE);
    // centred, so what is cut off is the page's margin
    let (x0, y0) = ((small.w - nx * TILE) / 2, (small.h - ny * TILE) / 2);
    let at = |x: usize, y: usize| (y * small.w + x) * 3;
    let grey = |x: usize, y: usize| {
        let p = &small.rgb[at(x, y)..at(x, y) + 3];
        255.0 * (0.299 * p[0] + 0.587 * p[1] + 0.114 * p[2])
    };
    let mut found: Vec<(f32, usize, usize)> = Vec::with_capacity(nx * ny);
    for ty in 0..ny {
        for tx in 0..nx {
            let (left, top) = (x0 + tx * TILE, y0 + ty * TILE);
            let mut detail = 0.0f32;
            for y in top + 1..top + TILE - 1 {
                for x in left + 1..left + TILE - 1 {
                    detail += (4.0 * grey(x, y) - grey(x - 1, y) - grey(x + 1, y) - grey(x, y - 1) - grey(x, y + 1)).abs();
                }
            }
            found.push((detail / ((TILE - 2) * (TILE - 2)) as f32, left, top));
        }
    }
    // busiest first; equal ones in reading order, so the choice never depends on the sort
    found.sort_by(|a, b| b.0.total_cmp(&a.0).then(a.2.cmp(&b.2)).then(a.1.cmp(&b.1)));
    let busiest = found.first().map_or(0.0, |t| t.0);
    found
        .into_iter()
        .take(MOST)
        .filter(|&(detail, ..)| detail >= EMPTY && detail >= FAINT * busiest)
        .map(|(_, left, top)| {
            let mut planes = vec![0f32; 3 * TILE * TILE];
            for y in 0..TILE {
                for x in 0..TILE {
                    let p = &small.rgb[at(left + x, top + y)..at(left + x, top + y) + 3];
                    for c in 0..3 {
                        planes[c * TILE * TILE + y * TILE + x] = (p[c] - MEAN[c]) / DEVIATION[c];
                    }
                }
            }
            planes
        })
        .collect()
}

/// The tiles' answers put together: each row of `likelihoods` is one tile's four likelihoods,
/// for a page turned clockwise by 0, 1, 2 and 3 quarter turns.
pub(crate) fn vote(likelihoods: &[[f32; 4]]) -> Upright {
    if likelihoods.is_empty() {
        return Upright::UNKNOWN;
    }
    // the mean of the logarithms: a tile that rules a turn out counts for more than one that
    // merely prefers another
    let mut score = [0f64; 4];
    for tile in likelihoods {
        for (total, &p) in score.iter_mut().zip(tile) {
            *total += f64::from(p.clamp(1e-6, 1.0)).ln() / likelihoods.len() as f64;
        }
    }
    let best = score.iter().copied().fold(f64::MIN, f64::max);
    let shares = score.map(|s| (s - best).exp());
    let total: f64 = shares.iter().sum();
    let top = (0..4).max_by(|&a, &b| shares[a].total_cmp(&shares[b]).then(b.cmp(&a))).unwrap_or(0);
    let own = |tile: &[f32; 4]| (0..4).max_by(|&a, &b| tile[a].total_cmp(&tile[b]).then(b.cmp(&a))).unwrap_or(0);
    let confidence = (shares[top] / total) as f32;
    let agreement = likelihoods.iter().filter(|tile| own(tile) == top).count() as f32 / likelihoods.len() as f32;
    Upright {
        // turned clockwise by `top` quarter turns: put right by the rest of the full turn
        quarter_turns: ((4 - top) % 4) as u8,
        confidence,
        agreement,
        tiles: likelihoods.len().min(255) as u8,
        sure: confidence >= SURE && agreement >= AGREE,
    }
}

/// Which way up `page` is. `None` when the classifier's model file is not installed or cannot be
/// run; an empty page is an answer nobody is sure of.
pub fn upright(page: &Rgb) -> Option<Upright> {
    let model = load(UPRIGHT)?;
    let tiles = tiles(page);
    if tiles.is_empty() {
        return Some(Upright::UNKNOWN);
    }
    // one at a time: the runtime uses every core for a single run already
    static RUNNING: Mutex<()> = Mutex::new(());
    let _one = RUNNING.lock().unwrap_or_else(PoisonError::into_inner);
    let (input, output) = (*model.input_ids().first()?, *model.output_ids().first()?);
    let mut likelihoods = Vec::with_capacity(tiles.len());
    for (seen, planes) in tiles.into_iter().enumerate() {
        let tensor = NdTensor::from_data([1, 3, TILE, TILE], planes);
        let [answer] = model.run_n(vec![(input, tensor.view().into())], [output], None).ok()?;
        let answer: NdTensor<f32, 2> = answer.try_into().ok()?;
        let row: [f32; 4] = answer.to_vec().try_into().ok()?;
        if row.iter().any(|v| !v.is_finite()) {
            return None;
        }
        likelihoods.push(row);
        // print that is plain to read is settled by its busiest tiles; the rest would cost as
        // much again to say the same
        if seen + 1 == FIRST {
            let early = vote(&likelihoods);
            if early.agreement >= 1.0 && early.confidence >= CLEAR {
                return Some(early);
            }
        }
    }
    Some(vote(&likelihoods))
}

/// `page` turned clockwise by `quarter_turns` quarter turns: the same pixels in new places,
/// nothing resampled.
pub fn turned(page: &Rgb, quarter_turns: u8) -> Rgb {
    let (w, h) = (page.width as usize, page.height as usize);
    let turns = quarter_turns % 4;
    if turns == 0 || page.data.len() != w * h * 3 {
        return page.clone();
    }
    let (ow, oh) = if turns == 2 { (w, h) } else { (h, w) };
    let mut data = vec![0u8; page.data.len()];
    for (i, out) in data.chunks_exact_mut(3).enumerate() {
        let (x, y) = (i % ow, i / ow);
        // where this pixel of the turned page was in the page
        let (sx, sy) = match turns {
            1 => (y, h - 1 - x),
            2 => (w - 1 - x, h - 1 - y),
            _ => (w - 1 - y, x),
        };
        out.copy_from_slice(&page.data[(sy * w + sx) * 3..(sy * w + sx) * 3 + 3]);
    }
    Rgb { width: ow as u32, height: oh as u32, data }
}
