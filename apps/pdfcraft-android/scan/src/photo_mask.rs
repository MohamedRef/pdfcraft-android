//! Where the photographs are on a page (#299, S13 section 4).
//!
//! The Clean filter makes paper white and ink dark. Applied to a photograph printed on the page
//! (a face on an ID card, a product picture) it bleaches the photograph and crushes its shadows.
//! This module finds those regions so the filter can leave them alone or go easy on them.
//!
//! The page is cut into blocks of 24 by 24 pixels. Three measures per block decide whether it is
//! a photograph: the share of mid-tone pixels (text is mostly paper and ink, a photograph is
//! mostly tone in between), the mean colour, and the spread of gradient directions (text has a
//! few, a photograph has all of them). The photo blocks are then cleaned up into regions with a
//! soft edge. The thresholds are the starting values of S13 section 4; the tests print the
//! measured values. Not done here: the union with the object mask of #298, which the caller does.

use crate::{Rgb, luma};
use std::f32::consts::PI;

/// Side of a block in pixels of the working page (#299). 24 px cuts a page of about 1000 px on
/// its long side into roughly 40 by 24 blocks: a photograph's edge is placed to a block.
const BLOCK: usize = 24;
/// A page needs at least this many blocks along each side to get a mask. Smaller pages give
/// `None`: there is too little of them to tell a photograph from print (#299).
const MIN_BLOCKS_PER_SIDE: usize = 3;
/// Luminance window for "mid tone", as a share 0–1 of 255. Paper sits above it, ink below (#299).
const MID_LOW: f32 = 0.30;
/// Upper end of the mid-tone window: paper that is slightly grey still counts as paper (#299).
const MID_HIGH: f32 = 0.85;
/// A block with more mid-tone pixels than this is a photograph on its own (#299).
const MID_PHOTO: f32 = 0.45;
/// A block needs at least this mid-tone share before colour or texture can make it a photograph.
const MID_TEXTURED: f32 = 0.30;
/// Mean chroma of a block above this (max minus min of RGB, as a share of 255) marks a coloured
/// picture. Coloured ink on paper stays under it when it is dark (#299).
const CHROMA_PHOTO: f32 = 0.10;
/// Gradient-direction entropy above this many bits (of at most 3 for 8 bins) marks a photograph.
const ORI_PHOTO: f32 = 2.6;
/// A gradient is strong when its Sobel magnitude, on a 0–255 scale, is above this (#299).
const STRONG_GRADIENT: f32 = 32.0;
/// Number of gradient-direction bins over 180 degrees (#299).
const ORI_BINS: usize = 8;
/// A block with fewer strong-gradient pixels than this has entropy 0: a few pixels say nothing
/// about the spread of directions. Added to the spec, which does not say what a flat block gives.
const MIN_STRONG_PIXELS: usize = 16;
/// Photo blocks after clean-up above this share of the page make the page a picture (#299).
const WHOLE_PAGE_SHARE: f64 = 0.60;
/// A group of photo blocks is kept only when its bounding box is this many blocks wide and high.
const MIN_COMPONENT_BLOCKS: usize = 3;

/// Where the photographs are on a page, one weight per block (#299, S13 section 4).
#[derive(Clone, Debug, PartialEq)]
pub struct PhotoMask {
    /// Blocks across the page.
    pub cols: usize,
    /// Blocks down the page.
    pub rows: usize,
    /// Side of a block in pixels of the page the mask was made from.
    pub block: u32,
    /// Per block, row by row: 0.0 text and paper … 1.0 photograph, with a soft edge.
    pub weight: Vec<f32>,
    /// Share of the page's blocks that are photograph, before the soft edge and the grow step.
    pub share: f64,
    /// More than 60 % is photograph: the page is a picture, and every weight is 1.0.
    pub whole_page: bool,
}

/// The three measures of one block (#299, S13 section 4). Crate-visible for the tests.
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct Measures {
    /// Share of pixels with corrected luminance between `MID_LOW` and `MID_HIGH`.
    pub mid: f32,
    /// Mean chroma of the block's pixels, 0–1.
    pub chroma: f32,
    /// Entropy of the gradient-direction histogram, in bits.
    pub ori: f32,
}

/// A rectangle of pixels: `x0..x1` by `y0..y1`.
#[derive(Clone, Copy)]
struct Span {
    x0: usize,
    y0: usize,
    x1: usize,
    y1: usize,
}

/// Finds the photographs on a straightened page (#299, S13 section 4).
///
/// `page` is the page after lighting correction (paper near white), at working size (longer side
/// about 1000 pixels). Returns `None` when the page is smaller than 3 by 3 blocks, or when its
/// buffer does not hold `width * height * 3` bytes. The mask is deterministic for the same bytes.
pub fn photo_mask(page: &Rgb) -> Option<PhotoMask> {
    let (cols, rows, measures) = measure_blocks(page)?;
    let photo: Vec<bool> = measures.iter().map(|&m| is_photo(m)).collect();
    let core = close_holes(&keep_large(&photo, cols, rows), cols, rows);
    let n = cols * rows;
    let share = core.iter().filter(|&&b| b).count() as f64 / n as f64;
    let whole_page = share > WHOLE_PAGE_SHARE;
    let weight = if whole_page { vec![1.0; n] } else { soft_edge(&core, cols, rows) };
    Some(PhotoMask { cols, rows, block: BLOCK as u32, weight, share, whole_page })
}

impl PhotoMask {
    /// The weight at a pixel of the page the mask was made from: bilinear between block centres,
    /// so 1.0 well inside a photograph and 0.0 well away from one. A pixel beyond the grid reads
    /// the nearest block. Returns 0.0 for a mask with no blocks or no block size.
    pub fn at(&self, x: u32, y: u32) -> f32 {
        if self.block == 0 || self.cols == 0 || self.rows == 0 {
            return 0.0;
        }
        let b = self.block as f32;
        let u = ((x as f32 + 0.5) / b - 0.5).clamp(0.0, (self.cols - 1) as f32);
        let v = ((y as f32 + 0.5) / b - 0.5).clamp(0.0, (self.rows - 1) as f32);
        let (c0, r0) = (u.floor() as usize, v.floor() as usize);
        let (c1, r1) = ((c0 + 1).min(self.cols - 1), (r0 + 1).min(self.rows - 1));
        let (fu, fv) = (u - c0 as f32, v - r0 as f32);
        let g = |c: usize, r: usize| self.weight.get(r * self.cols + c).copied().unwrap_or(0.0);
        let top = g(c0, r0) * (1.0 - fu) + g(c1, r0) * fu;
        let bottom = g(c0, r1) * (1.0 - fu) + g(c1, r1) * fu;
        top * (1.0 - fv) + bottom * fv
    }
}

/// The block grid of a `width` by `height` page, or `None` when it is too small.
fn grid_size(width: usize, height: usize) -> Option<(usize, usize)> {
    let least = MIN_BLOCKS_PER_SIDE * BLOCK;
    if width < least || height < least {
        return None;
    }
    Some((width.div_ceil(BLOCK), height.div_ceil(BLOCK)))
}

/// The measures of every block, row by row, with the grid size. `None` for a page that is too
/// small or whose buffer is the wrong length. Crate-visible so the tests can print the measures.
pub(crate) fn measure_blocks(page: &Rgb) -> Option<(usize, usize, Vec<Measures>)> {
    let (w, h) = (page.width as usize, page.height as usize);
    let (cols, rows) = grid_size(w, h)?;
    if page.data.len() != w.checked_mul(h)?.checked_mul(3)? {
        return None;
    }
    let lum: Vec<u8> = page.data.chunks_exact(3).map(|p| luma([p[0], p[1], p[2]])).collect();
    let bins = orientation_bins(&lum, w, h);
    let mut out = Vec::with_capacity(cols * rows);
    for row in 0..rows {
        for col in 0..cols {
            let span = Span { x0: col * BLOCK, y0: row * BLOCK, x1: ((col + 1) * BLOCK).min(w), y1: ((row + 1) * BLOCK).min(h) };
            out.push(block_measures(page, &lum, &bins, span));
        }
    }
    Some((cols, rows, out))
}

/// The three measures over the pixels of one block.
fn block_measures(page: &Rgb, lum: &[u8], bins: &[Option<u8>], span: Span) -> Measures {
    let w = page.width as usize;
    let (mut pixels, mut mid, mut chroma) = (0usize, 0usize, 0.0f32);
    let mut hist = [0usize; ORI_BINS];
    for y in span.y0..span.y1 {
        for x in span.x0..span.x1 {
            let i = y * w + x;
            pixels += 1;
            let l = f32::from(lum[i]) / 255.0;
            if (MID_LOW..=MID_HIGH).contains(&l) {
                mid += 1;
            }
            let p = &page.data[3 * i..3 * i + 3];
            chroma += chroma_of(p[0], p[1], p[2]);
            if let Some(bin) = bins[i] {
                hist[usize::from(bin)] += 1;
            }
        }
    }
    let n = pixels.max(1) as f32;
    Measures { mid: mid as f32 / n, chroma: chroma / n, ori: entropy_bits(&hist) }
}

/// Chroma of one pixel: the spread of its three channels, as a share of 255.
fn chroma_of(r: u8, g: u8, b: u8) -> f32 {
    let (max, min) = (r.max(g).max(b), r.min(g).min(b));
    f32::from(max - min) / 255.0
}

/// Entropy in bits of a direction histogram. Zero when it holds fewer than `MIN_STRONG_PIXELS`.
fn entropy_bits(hist: &[usize]) -> f32 {
    let total: usize = hist.iter().sum();
    if total < MIN_STRONG_PIXELS {
        return 0.0;
    }
    let t = total as f32;
    hist.iter()
        .filter(|&&c| c > 0)
        .map(|&c| {
            let p = c as f32 / t;
            -p * p.log2()
        })
        .sum::<f32>()
}

/// The gradient-direction bin of every pixel whose Sobel gradient is strong, or `None`. The
/// border pixels have no full 3×3 neighbourhood and stay `None`.
fn orientation_bins(lum: &[u8], w: usize, h: usize) -> Vec<Option<u8>> {
    let mut bins = vec![None; w * h];
    for y in 1..h.saturating_sub(1) {
        for x in 1..w.saturating_sub(1) {
            bins[y * w + x] = strong_bin(lum, w, x, y);
        }
    }
    bins
}

/// The direction bin of the Sobel gradient at (x, y), when it is strong. The kernels are divided
/// by 4 so that a step of height h gives a magnitude of h on the 0–255 scale. The direction is
/// taken modulo 180 degrees, so a line and its opposite edge share a bin.
fn strong_bin(lum: &[u8], w: usize, x: usize, y: usize) -> Option<u8> {
    let v = |xx: usize, yy: usize| f32::from(lum[yy * w + xx]);
    let gx = (v(x + 1, y - 1) + 2.0 * v(x + 1, y) + v(x + 1, y + 1) - v(x - 1, y - 1) - 2.0 * v(x - 1, y) - v(x - 1, y + 1)) / 4.0;
    let gy = (v(x - 1, y + 1) + 2.0 * v(x, y + 1) + v(x + 1, y + 1) - v(x - 1, y - 1) - 2.0 * v(x, y - 1) - v(x + 1, y - 1)) / 4.0;
    if gx.hypot(gy) <= STRONG_GRADIENT {
        return None;
    }
    let turn = gy.atan2(gx).rem_euclid(PI);
    let bin = (turn / PI * ORI_BINS as f32) as usize;
    Some(bin.min(ORI_BINS - 1) as u8)
}

/// The photo test of S13 section 4, on one block's measures.
pub(crate) fn is_photo(m: Measures) -> bool {
    m.mid > MID_PHOTO || (m.mid > MID_TEXTURED && (m.chroma > CHROMA_PHOTO || m.ori > ORI_PHOTO))
}

/// The cells next to cell `i` in a `cols` by `rows` grid: 8 neighbours when `diagonal`, else 4.
fn neighbours(i: usize, cols: usize, rows: usize, diagonal: bool) -> Vec<usize> {
    let (c, r) = ((i % cols) as isize, (i / cols) as isize);
    let mut out = Vec::with_capacity(8);
    for dr in -1..=1isize {
        for dc in -1..=1isize {
            if (dr == 0 && dc == 0) || (!diagonal && dr != 0 && dc != 0) {
                continue;
            }
            let (nc, nr) = (c + dc, r + dr);
            if nc >= 0 && nr >= 0 && (nc as usize) < cols && (nr as usize) < rows {
                out.push(nr as usize * cols + nc as usize);
            }
        }
    }
    out
}

/// Groups of cells that touch (8-connected) among the flagged ones.
fn components(flags: &[bool], cols: usize, rows: usize) -> Vec<Vec<usize>> {
    let mut seen = vec![false; flags.len()];
    let mut groups = Vec::new();
    for start in 0..flags.len() {
        if !flags[start] || seen[start] {
            continue;
        }
        seen[start] = true;
        let mut stack = vec![start];
        let mut cells = Vec::new();
        while let Some(i) = stack.pop() {
            cells.push(i);
            for j in neighbours(i, cols, rows, true) {
                if flags[j] && !seen[j] {
                    seen[j] = true;
                    stack.push(j);
                }
            }
        }
        groups.push(cells);
    }
    groups
}

/// Keeps the groups whose bounding box is at least `MIN_COMPONENT_BLOCKS` blocks wide and high.
/// Smaller specks (a stamp dot, a bold capital) are print, not photographs.
fn keep_large(photo: &[bool], cols: usize, rows: usize) -> Vec<bool> {
    let mut keep = vec![false; photo.len()];
    for group in components(photo, cols, rows) {
        let (mut c0, mut c1, mut r0, mut r1) = (usize::MAX, 0, usize::MAX, 0);
        for &i in &group {
            let (c, r) = (i % cols, i / cols);
            c0 = c0.min(c);
            c1 = c1.max(c);
            r0 = r0.min(r);
            r1 = r1.max(r);
        }
        if c1 - c0 + 1 >= MIN_COMPONENT_BLOCKS && r1 - r0 + 1 >= MIN_COMPONENT_BLOCKS {
            for &i in &group {
                keep[i] = true;
            }
        }
    }
    keep
}

/// Fills the holes: every block not reachable from the page border through blocks outside the
/// mask (4-connected) becomes part of the mask. A paper window inside a photograph is closed.
fn close_holes(core: &[bool], cols: usize, rows: usize) -> Vec<bool> {
    let n = core.len();
    let mut outside = vec![false; n];
    let mut stack: Vec<usize> =
        (0..n).filter(|&i| !core[i] && (i % cols == 0 || i / cols == 0 || i % cols == cols - 1 || i / cols == rows - 1)).collect();
    for &i in &stack {
        outside[i] = true;
    }
    while let Some(i) = stack.pop() {
        for j in neighbours(i, cols, rows, false) {
            if !core[j] && !outside[j] {
                outside[j] = true;
                stack.push(j);
            }
        }
    }
    outside.iter().map(|&o| !o).collect()
}

/// The soft edge (S13 section 4: grow by one block, feather by one block). The mask grows by one
/// block; each block's weight is the share of its 3×3 neighbourhood that is grown. Blocks of the
/// mask get 1.0, the ring around them a value between, and nothing beyond two blocks.
fn soft_edge(core: &[bool], cols: usize, rows: usize) -> Vec<f32> {
    let n = core.len();
    let grown: Vec<bool> = (0..n).map(|i| core[i] || neighbours(i, cols, rows, true).into_iter().any(|j| core[j])).collect();
    (0..n)
        .map(|i| {
            let around = neighbours(i, cols, rows, true);
            let hits = around.iter().filter(|&&j| grown[j]).count() + usize::from(grown[i]);
            hits as f32 / (around.len() + 1) as f32
        })
        .collect()
}
