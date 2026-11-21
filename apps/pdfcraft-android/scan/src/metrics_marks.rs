//! Measures of what a look did to the marks on a page (#784): how dark print still is against the
//! paper beside it, how much of a thin or pale mark can still be seen, whether strokes got fatter
//! or thinner, how much colour a pen, a highlighter or a stamp kept, and how much of a
//! photograph's tones and fine detail is left. They need a mask per kind of mark, so they are
//! for pages whose marks are known (`clean_pages`).
//!
//! "Against the paper beside it" is what makes one measure serve both a cleaned page and the
//! photo it was made from: the paper's level is taken tile by tile ([`PaperLevels`]), so a page
//! in uneven light is not read as low in contrast where it is merely dark.
//!
//! Every function returns `None` rather than panicking when a length does not match the picture
//! or a mask has no pixel that counts. Time and memory are bounded by the pixel count.

use crate::{Luma, Rgb};

/// Side of a tile of [`PaperLevels`], in pixels: larger than a line of print is high, smaller
/// than a shadow's reach.
pub const TILE: usize = 64;
/// A tile needs this many paper pixels to have a level of its own.
const TILE_MIN_PAPER: usize = 32;
/// A mark pixel can be seen when it is at least this many grey levels (of 255) under the paper
/// beside it: a tenth of the range. A definition, not a tuned value: at 300 dpi a one-pixel
/// stroke a tenth darker than white paper is at the edge of what print shows.
pub const VISIBLE_DROP: f64 = 25.5;
/// A pixel has a colour of its own from this much chroma (max minus min channel), as in
/// `metrics_tone::hue_shift`.
pub const MIN_CHROMA: u8 = 16;

fn fits(len: usize, width: u32, height: u32) -> Option<(usize, usize)> {
    let (w, h) = (width as usize, height as usize);
    (w > 0 && h > 0 && w.checked_mul(h)? == len).then_some((w, h))
}

fn median_of(hist: &[u32; 256]) -> Option<u8> {
    crate::metrics_tone::percentile(hist, 50.0)
}

/// The paper's grey level in each [`TILE`] of a page: the median of the tile's paper pixels, or
/// the level of the nearest tile that has enough paper.
#[derive(Clone, Debug, PartialEq)]
pub struct PaperLevels {
    cols: usize,
    rows: usize,
    width: usize,
    level: Vec<f64>,
}

impl PaperLevels {
    /// `None` when the lengths do not match or no tile has enough paper.
    pub fn new(page: &Luma, paper: &[bool]) -> Option<PaperLevels> {
        let (w, h) = fits(page.data.len(), page.width, page.height)?;
        if paper.len() != w * h {
            return None;
        }
        let (cols, rows) = (w.div_ceil(TILE), h.div_ceil(TILE));
        let mut own: Vec<Option<f64>> = Vec::with_capacity(cols * rows);
        for ty in 0..rows {
            for tx in 0..cols {
                let mut hist = [0u32; 256];
                let mut n = 0usize;
                for y in ty * TILE..((ty + 1) * TILE).min(h) {
                    for x in tx * TILE..((tx + 1) * TILE).min(w) {
                        if paper[y * w + x] {
                            hist[usize::from(page.data[y * w + x])] += 1;
                            n += 1;
                        }
                    }
                }
                own.push(if n >= TILE_MIN_PAPER { median_of(&hist).map(f64::from) } else { None });
            }
        }
        if own.iter().all(Option::is_none) {
            return None;
        }
        // a tile without paper (inside a photograph, say) takes the nearest tile's level
        let level = (0..cols * rows)
            .map(|i| {
                own[i].unwrap_or_else(|| {
                    let (tx, ty) = ((i % cols) as i64, (i / cols) as i64);
                    let mut best = (i64::MAX, 255.0);
                    for (j, v) in own.iter().enumerate() {
                        if let Some(v) = v {
                            let d = ((j % cols) as i64 - tx).pow(2) + ((j / cols) as i64 - ty).pow(2);
                            if d < best.0 {
                                best = (d, *v);
                            }
                        }
                    }
                    best.1
                })
            })
            .collect();
        Some(PaperLevels { cols, rows, width: w, level })
    }

    /// The paper's level at pixel `i`.
    fn at(&self, i: usize) -> f64 {
        let (tx, ty) = (((i % self.width) / TILE).min(self.cols - 1), ((i / self.width) / TILE).min(self.rows - 1));
        self.level[ty * self.cols + tx]
    }
}

/// How far the pixels of `mask` are under the paper beside them, as a histogram of the drop
/// clamped to 0–255, and how many there are.
fn drops(page: &Luma, levels: &PaperLevels, mask: &[bool]) -> Option<([u32; 256], u64)> {
    let (w, h) = fits(page.data.len(), page.width, page.height)?;
    if mask.len() != w * h || levels.width != w {
        return None;
    }
    let mut hist = [0u32; 256];
    let mut n = 0u64;
    for (i, _) in mask.iter().enumerate().filter(|(_, m)| **m) {
        let drop = (levels.at(i) - f64::from(page.data[i])).round().clamp(0.0, 255.0) as usize;
        hist[drop] += 1;
        n += 1;
    }
    (n > 0).then_some((hist, n))
}

/// The median drop of the marked pixels under the paper beside them, in grey levels: 255 is
/// black on white, 0 is a mark that cannot be told from paper.
pub fn mark_contrast(page: &Luma, levels: &PaperLevels, mask: &[bool]) -> Option<f64> {
    let (hist, _) = drops(page, levels, mask)?;
    median_of(&hist).map(f64::from)
}

/// The share of the marked pixels that can still be seen: at least [`VISIBLE_DROP`] under the
/// paper beside them. With a mask of blank paper it is the share of the paper that is not clean.
pub fn mark_kept(page: &Luma, levels: &PaperLevels, mask: &[bool]) -> Option<f64> {
    let (hist, n) = drops(page, levels, mask)?;
    let seen: u64 = hist[VISIBLE_DROP.ceil() as usize..].iter().map(|&c| u64::from(c)).sum();
    Some(seen as f64 / n as f64)
}

/// How heavy the strokes of `mask` came out: the pixels of `around` (the strokes and the paper
/// within a few pixels of them) that are at least half as far under the paper as the strokes'
/// own median, over the strokes' own pixel count. 1 is the strokes as drawn; above it they are
/// fatter (blur, or a curve that darkens their soft rim), below it thinner or broken.
pub fn stroke_weight(page: &Luma, levels: &PaperLevels, mask: &[bool], around: &[bool]) -> Option<f64> {
    let contrast = mark_contrast(page, levels, mask)?;
    let (hist, _) = drops(page, levels, around)?;
    let drawn = mask.iter().filter(|m| **m).count();
    if contrast < 1.0 || drawn == 0 {
        return None;
    }
    let dark: u64 = hist[((contrast / 2.0).ceil() as usize).min(255)..].iter().map(|&c| u64::from(c)).sum();
    Some(dark as f64 / drawn as f64)
}

fn chroma_at(data: &[u8], i: usize) -> u8 {
    let px = &data[i * 3..i * 3 + 3];
    px[0].max(px[1]).max(px[2]) - px[0].min(px[1]).min(px[2])
}

fn median_chroma(page: &Rgb, mask: &[bool]) -> Option<f64> {
    let (w, h) = fits(page.data.len() / 3, page.width, page.height)?;
    if page.data.len() != w * h * 3 || mask.len() != w * h {
        return None;
    }
    let mut hist = [0u32; 256];
    for (i, _) in mask.iter().enumerate().filter(|(_, m)| **m) {
        hist[usize::from(chroma_at(&page.data, i))] += 1;
    }
    median_of(&hist).map(f64::from)
}

/// The median chroma (max minus min channel) of the marked pixels of `after` over that of the
/// same pixels of `truth`: 1 is the colour as drawn, 0 is grey. `None` when the truth has no
/// colour there.
pub fn chroma_kept(truth: &Rgb, after: &Rgb, mask: &[bool]) -> Option<f64> {
    let (was, now) = (median_chroma(truth, mask)?, median_chroma(after, mask)?);
    (was >= f64::from(MIN_CHROMA)).then(|| now / was)
}

/// The mean chroma of the marked pixels, in levels of 255: on blank paper, how far it is from
/// neutral.
pub fn mean_chroma(page: &Rgb, mask: &[bool]) -> Option<f64> {
    let (w, h) = fits(page.data.len() / 3, page.width, page.height)?;
    if page.data.len() != w * h * 3 || mask.len() != w * h {
        return None;
    }
    let (mut sum, mut n) = (0u64, 0u64);
    for (i, _) in mask.iter().enumerate().filter(|(_, m)| **m) {
        sum += u64::from(chroma_at(&page.data, i));
        n += 1;
    }
    (n > 0).then(|| sum as f64 / n as f64)
}

/// How much of the truth's tone a look kept over the marked pixels: the slope of the line that
/// best gives `after` from `truth` there (covariance over the truth's variance). 1 keeps every
/// step of tone, 0 is one flat tone, above 1 is more contrast than the picture had. Grain does
/// not move it, since grain does not follow the truth. `None` when the truth is flat there.
pub fn tone_kept(truth: &Luma, after: &Luma, mask: &[bool]) -> Option<f64> {
    let (w, h) = fits(truth.data.len(), truth.width, truth.height)?;
    if after.data.len() != w * h || (after.width, after.height) != (truth.width, truth.height) || mask.len() != w * h {
        return None;
    }
    let (mut n, mut st, mut sa, mut stt, mut sta) = (0.0f64, 0.0f64, 0.0f64, 0.0f64, 0.0f64);
    for (i, _) in mask.iter().enumerate().filter(|(_, m)| **m) {
        let (t, a) = (f64::from(truth.data[i]), f64::from(after.data[i]));
        n += 1.0;
        st += t;
        sa += a;
        stt += t * t;
        sta += t * a;
    }
    if n < 2.0 {
        return None;
    }
    let var = stt / n - (st / n).powi(2);
    (var > 1.0).then(|| (sta / n - st / n * sa / n) / var)
}

/// The share of the marked pixels at one end of the range: at or under `level` when `dark`, at
/// or over it otherwise. For a photograph's shadows gone black and highlights gone white.
pub fn clipped(page: &Luma, mask: &[bool], level: u8, dark: bool) -> Option<f64> {
    let (w, h) = fits(page.data.len(), page.width, page.height)?;
    if mask.len() != w * h {
        return None;
    }
    let (mut hit, mut n) = (0u64, 0u64);
    for (i, _) in mask.iter().enumerate().filter(|(_, m)| **m) {
        n += 1;
        hit += u64::from(if dark { page.data[i] <= level } else { page.data[i] >= level });
    }
    (n > 0).then(|| hit as f64 / n as f64)
}

/// `mask` grown by `r` pixels each way (a square), for "within `r` of a mark".
pub fn grown(mask: &[bool], width: usize, height: usize, r: usize) -> Vec<bool> {
    if width == 0 || height == 0 || mask.len() != width * height {
        return mask.to_vec();
    }
    let mut rows = vec![false; mask.len()];
    for y in 0..height {
        let row = &mask[y * width..(y + 1) * width];
        // distance to the last set pixel going right, then going left
        let mut since = usize::MAX;
        for x in 0..width {
            since = if row[x] { 0 } else { since.saturating_add(1) };
            rows[y * width + x] = since <= r;
        }
        since = usize::MAX;
        for x in (0..width).rev() {
            since = if row[x] { 0 } else { since.saturating_add(1) };
            rows[y * width + x] |= since <= r;
        }
    }
    let mut out = vec![false; mask.len()];
    for x in 0..width {
        let mut since = usize::MAX;
        for y in 0..height {
            since = if rows[y * width + x] { 0 } else { since.saturating_add(1) };
            out[y * width + x] = since <= r;
        }
        since = usize::MAX;
        for y in (0..height).rev() {
            since = if rows[y * width + x] { 0 } else { since.saturating_add(1) };
            out[y * width + x] |= since <= r;
        }
    }
    out
}
