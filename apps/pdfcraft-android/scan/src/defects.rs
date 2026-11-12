//! Fingers at the page's edge, glare spots and stains (#306, research): find each, and fix it
//! only where that cannot change what the page says.
//!
//! The issue's principle holds throughout: **text is never made up.** A finger or a glare spot
//! is filled with paper only when the ring around it is blank paper; over print it is left and
//! the region is flagged (`covers_ink`), so the app can say "retake". A stain is not filled at
//! all: its colour and darkness are divided out like a coloured shadow, so print under it keeps
//! its contrast.
//!
//! [`find`] reads the straightened page with its lighting evened out (the Auto look's output,
//! where paper is near white) and, for glare, the straightened page before that correction:
//! glare is clipped light, which the correction would hide. It works on a copy at most
//! [`WORK_SIDE`] pixels long; the masks it returns are at that size, and [`fill_blank`] and
//! [`normalise_stains`] scale them up.
//!
//! Nothing in the app calls this yet. Every threshold is the issue's starting value except those
//! marked as added here; `docs/ANDROID_SCAN_DEFECTS.md` lists them.

use crate::warp::Rgb;

/// The longer side of the working copy.
pub const WORK_SIDE: usize = 800;
/// A finger is 1 to 12 % of the page's area...
pub const FINGER_AREA: (f32, f32) = (0.01, 0.12);
/// ...where it meets the border, 4 to 14 % of the page's short side wide...
pub const FINGER_WIDTH: (f32, f32) = (0.04, 0.14);
/// ...and reaches into the page at least this share of that width (added here: "elongated
/// toward the interior").
pub const FINGER_DEPTH: f32 = 0.7;
/// The ring looked at around a region, as shares of the page's width.
pub const RING: (f32, f32) = (0.02, 0.06);
/// The ring holds print when more than this share of it is ink.
pub const RING_INK: f32 = 0.02;
/// Glare: at least two channels this bright in the uncorrected page...
pub const GLARE_LEVEL: u8 = 250;
/// ...over at least this share of the page...
pub const GLARE_AREA: f32 = 0.002;
/// ...with paper around it at least this much darker than its core (a halo, not white paper).
pub const GLARE_HALO: f32 = 0.10;
/// Stain: chroma off the paper's by more than this (of 255)...
pub const STAIN_CHROMA: f32 = 0.05;
/// ...or a luminance drop of more than this share...
pub const STAIN_DROP: f32 = 0.08;
/// ...over 0.5 to 25 % of the page.
pub const STAIN_AREA: (f32, f32) = (0.005, 0.25);
/// A stain is smooth: the paper in a block of it varies by less than this (grey levels; added
/// here so a photograph or a coloured drawing is not taken for one).
pub const STAIN_TEXTURE: f32 = 8.0;
/// Darker than this share of the paper is ink (added here; a plain share, since the page given
/// is evenly lit).
pub const INK_SHARE: f32 = 0.6;
/// Blocks, in working pixels, that stains are looked for in.
const STAIN_BLOCK: usize = 8;
/// Feather of a fill's edge, in pixels of the full page.
const FEATHER: f32 = 6.0;

/// One region found.
#[derive(Clone, Debug, PartialEq)]
pub struct Region {
    /// Working-copy pixels inside the region, as indices into the working copy.
    pub pixels: Vec<usize>,
    /// Share of the page.
    pub area: f32,
    /// The ring around it holds print: a fill would hide or invent text, so none is made.
    pub covers_ink: bool,
}

/// Everything [`find`] saw.
#[derive(Clone, Debug, PartialEq)]
pub struct Defects {
    /// The working copy's size.
    pub work_width: usize,
    pub work_height: usize,
    pub fingers: Vec<Region>,
    pub glare: Vec<Region>,
    pub stains: Vec<Region>,
}

impl Defects {
    /// Regions over print, which are left as they are (the page should be taken again).
    pub fn flagged(&self) -> usize {
        self.fingers.iter().chain(&self.glare).filter(|r| r.covers_ink).count()
    }
}

/// A working copy: three planes of floats, box-averaged down.
struct Work {
    w: usize,
    h: usize,
    rgb: Vec<[f32; 3]>,
}

impl Work {
    fn new(page: &Rgb) -> Option<Work> {
        let (pw, ph) = (page.width as usize, page.height as usize);
        if pw == 0 || ph == 0 || page.data.len() != pw * ph * 3 {
            return None;
        }
        let step = pw.max(ph).div_ceil(WORK_SIDE).max(1);
        let (w, h) = (pw.div_ceil(step), ph.div_ceil(step));
        let mut rgb = vec![[0f32; 3]; w * h];
        for (y, row) in rgb.chunks_mut(w).enumerate() {
            for (x, out) in row.iter_mut().enumerate() {
                let (mut sum, mut n) = ([0f32; 3], 0f32);
                for sy in y * step..((y + 1) * step).min(ph) {
                    for sx in x * step..((x + 1) * step).min(pw) {
                        let i = (sy * pw + sx) * 3;
                        for (c, s) in sum.iter_mut().enumerate() {
                            *s += f32::from(page.data[i + c]);
                        }
                        n += 1.0;
                    }
                }
                *out = sum.map(|s| s / n);
            }
        }
        Some(Work { w, h, rgb })
    }
}

fn luma(c: [f32; 3]) -> f32 {
    0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]
}

/// (Cb, Cr) offsets from grey, BT.601.
fn chroma(c: [f32; 3]) -> (f32, f32) {
    let y = luma(c);
    (0.564 * (c[2] - y), 0.713 * (c[0] - y))
}

/// The paper's colour: the median of the brighter half of the page, per channel.
fn paper(work: &Work) -> [f32; 3] {
    let mut lumas: Vec<f32> = work.rgb.iter().map(|&c| luma(c)).collect();
    let mid = lumas.len() / 2;
    lumas.select_nth_unstable_by(mid, f32::total_cmp);
    let cut = lumas[mid];
    let mut out = [0f32; 3];
    for (ch, o) in out.iter_mut().enumerate() {
        let mut values: Vec<f32> = work.rgb.iter().filter(|&&c| luma(c) >= cut).map(|c| c[ch]).collect();
        if values.is_empty() {
            continue;
        }
        let m = values.len() / 2;
        values.select_nth_unstable_by(m, f32::total_cmp);
        *o = values[m];
    }
    out
}

/// Find fingers, glare and stains. `page` is the straightened page with its lighting evened out;
/// `before` the same page before that (for glare), or `None` to skip glare. `None` when a buffer
/// does not match its size or the two differ in size.
pub fn find(page: &Rgb, before: Option<&Rgb>) -> Option<Defects> {
    let work = Work::new(page)?;
    let raw = match before {
        Some(b) if (b.width, b.height) != (page.width, page.height) => return None,
        Some(b) => Some(Work::new(b)?),
        None => None,
    };
    let paper = paper(&work);
    let paper_luma = luma(paper).max(1.0);
    let ink: Vec<bool> = work.rgb.iter().map(|&c| luma(c) < INK_SHARE * paper_luma).collect();
    let fingers = fingers(&work, paper, &ink);
    let mut taken = vec![false; work.w * work.h];
    for r in &fingers {
        for &i in &r.pixels {
            taken[i] = true;
        }
    }
    let glare = raw.as_ref().map(|raw| glare(raw, &ink, &taken, work.w)).unwrap_or_default();
    for r in &glare {
        for &i in &r.pixels {
            taken[i] = true;
        }
    }
    let stains = stains(&work, paper, &ink, &taken);
    Some(Defects { work_width: work.w, work_height: work.h, fingers, glare, stains })
}

/// Skin relative to the paper's white point: warmer (Cr up, Cb down), neither ink-dark nor
/// paper-bright.
fn skin(c: [f32; 3], paper: [f32; 3], paper_luma: f32) -> bool {
    let (cb, cr) = chroma(c);
    let (pb, pr) = chroma(paper);
    let l = luma(c) / paper_luma;
    let (db, dr) = (cb - pb, cr - pr);
    (8.0..=60.0).contains(&dr) && (-45.0..=-3.0).contains(&db) && (0.25..=0.97).contains(&l)
}

fn fingers(work: &Work, paper: [f32; 3], ink: &[bool]) -> Vec<Region> {
    let (w, h) = (work.w, work.h);
    let paper_luma = luma(paper).max(1.0);
    let mask: Vec<bool> = work.rgb.iter().map(|&c| skin(c, paper, paper_luma)).collect();
    let short = w.min(h) as f32;
    let total = (w * h) as f32;
    let mut out = Vec::new();
    for pixels in components(&mask, w, h) {
        let area = pixels.len() as f32 / total;
        if area < FINGER_AREA.0 || area > FINGER_AREA.1 {
            continue;
        }
        let Some((width, depth)) = border_contact(&pixels, w, h) else { continue };
        let width_share = width as f32 / short;
        if width_share < FINGER_WIDTH.0 || width_share > FINGER_WIDTH.1 || (depth as f32) < FINGER_DEPTH * width as f32 {
            continue;
        }
        let covers_ink = ring_has_ink(&pixels, w, h, ink, &mask);
        out.push(Region { pixels, area, covers_ink });
    }
    out
}

/// Where a component touches the border most: how wide the contact is, and how far the
/// component reaches from that border. `None` when it touches no border.
fn border_contact(pixels: &[usize], w: usize, h: usize) -> Option<(usize, usize)> {
    // left, right, top, bottom: (pixels on that border, deepest reach from it)
    let mut sides = [(0usize, 0usize); 4];
    for &i in pixels {
        let (x, y) = (i % w, i / w);
        sides[0].1 = sides[0].1.max(x);
        sides[1].1 = sides[1].1.max(w - 1 - x);
        sides[2].1 = sides[2].1.max(y);
        sides[3].1 = sides[3].1.max(h - 1 - y);
        if x == 0 {
            sides[0].0 += 1;
        }
        if x == w - 1 {
            sides[1].0 += 1;
        }
        if y == 0 {
            sides[2].0 += 1;
        }
        if y == h - 1 {
            sides[3].0 += 1;
        }
    }
    sides.iter().copied().filter(|&(n, _)| n > 0).max_by_key(|&(n, _)| n).map(|(n, reach)| (n, reach + 1))
}

/// The ring [`RING`] around a region holds print. Pixels of `exclude` (the region's own kind)
/// are not counted.
fn ring_has_ink(pixels: &[usize], w: usize, h: usize, ink: &[bool], exclude: &[bool]) -> bool {
    let near = dilate(pixels, w, h, (RING.0 * w as f32).round() as usize);
    let far = dilate(pixels, w, h, (RING.1 * w as f32).round().max(1.0) as usize);
    let (mut count, mut inked) = (0usize, 0usize);
    for i in 0..w * h {
        if far[i] && !near[i] && !exclude[i] {
            count += 1;
            inked += usize::from(ink[i]);
        }
    }
    count > 0 && inked as f32 > RING_INK * count as f32
}

fn glare(raw: &Work, ink: &[bool], taken: &[bool], w: usize) -> Vec<Region> {
    let h = raw.h;
    let level = f32::from(GLARE_LEVEL);
    let mask: Vec<bool> = raw.rgb.iter().zip(taken).map(|(c, &t)| !t && c.iter().filter(|&&v| v >= level).count() >= 2).collect();
    let total = (w * h) as f32;
    let mut out = Vec::new();
    for pixels in components(&mask, w, h) {
        let area = pixels.len() as f32 / total;
        if area < GLARE_AREA {
            continue;
        }
        // the halo: paper just around the core is clearly darker than the core
        let core = pixels.iter().map(|&i| luma(raw.rgb[i])).sum::<f32>() / pixels.len() as f32;
        // paper beyond the spot's own soft edge: 3 to 8 % of the page's width away
        let near = dilate(&pixels, w, h, (0.03 * w as f32).round().max(1.0) as usize);
        let ring = dilate(&pixels, w, h, (0.08 * w as f32).round().max(2.0) as usize);
        let around: Vec<f32> = (0..w * h).filter(|&i| ring[i] && !near[i] && !ink[i]).map(|i| luma(raw.rgb[i])).collect();
        if around.is_empty() {
            continue;
        }
        let around_mean = around.iter().sum::<f32>() / around.len() as f32;
        if around_mean > (1.0 - GLARE_HALO) * core {
            continue;
        }
        let covers_ink = ring_has_ink(&pixels, w, h, ink, &mask);
        out.push(Region { pixels, area, covers_ink });
    }
    out
}

fn stains(work: &Work, paper: [f32; 3], ink: &[bool], taken: &[bool]) -> Vec<Region> {
    let (w, h) = (work.w, work.h);
    let (bw, bh) = (w.div_ceil(STAIN_BLOCK), h.div_ceil(STAIN_BLOCK));
    let paper_luma = luma(paper).max(1.0);
    let (pb, pr) = chroma(paper);
    let mut stained = vec![false; bw * bh];
    for by in 0..bh {
        for bx in 0..bw {
            let mut cells: Vec<[f32; 3]> = Vec::new();
            for y in by * STAIN_BLOCK..((by + 1) * STAIN_BLOCK).min(h) {
                for x in bx * STAIN_BLOCK..((bx + 1) * STAIN_BLOCK).min(w) {
                    let i = y * w + x;
                    if !ink[i] && !taken[i] {
                        cells.push(work.rgb[i]);
                    }
                }
            }
            // a block that is mostly print or another region's says nothing about the paper
            if cells.len() * 2 < STAIN_BLOCK * STAIN_BLOCK {
                continue;
            }
            let mut ls: Vec<f32> = cells.iter().map(|&c| luma(c)).collect();
            let m = ls.len() / 2;
            ls.select_nth_unstable_by(m, f32::total_cmp);
            let median_luma = ls[m];
            let mean = cells.iter().fold([0f32; 3], |a, c| [a[0] + c[0], a[1] + c[1], a[2] + c[2]]).map(|s| s / cells.len() as f32);
            // the median absolute deviation, scaled to a standard deviation: the soft edges of
            // print in the working copy are a few cells and must not count as texture
            let mut dev: Vec<f32> = ls.iter().map(|l| (l - median_luma).abs()).collect();
            dev.select_nth_unstable_by(m, f32::total_cmp);
            let spread = 1.4826 * dev[m];
            let (cb, cr) = chroma(mean);
            let off_colour = (cb - pb).hypot(cr - pr) / 255.0 > STAIN_CHROMA;
            let darker = median_luma < (1.0 - STAIN_DROP) * paper_luma;
            stained[by * bw + bx] = (off_colour || darker) && spread < STAIN_TEXTURE;
        }
    }
    let total = (bw * bh) as f32;
    let mut out = Vec::new();
    for blocks in components(&stained, bw, bh) {
        let area = blocks.len() as f32 / total;
        if area < STAIN_AREA.0 || area > STAIN_AREA.1 {
            continue;
        }
        let mut pixels = Vec::new();
        for b in blocks {
            let (bx, by) = (b % bw, b / bw);
            for y in by * STAIN_BLOCK..((by + 1) * STAIN_BLOCK).min(h) {
                for x in bx * STAIN_BLOCK..((bx + 1) * STAIN_BLOCK).min(w) {
                    pixels.push(y * w + x);
                }
            }
        }
        pixels.sort_unstable();
        out.push(Region { pixels, area, covers_ink: false });
    }
    out
}

/// Fill the fingers and glare spots that lie on blank paper with the paper's colour, its edge
/// feathered and the paper's own grain added back so the patch does not look flat. Regions over
/// print are left alone. Returns how many regions were filled.
pub fn fill_blank(page: &mut Rgb, found: &Defects) -> usize {
    let Some(work) = Work::new(page) else { return 0 };
    if (work.w, work.h) != (found.work_width, found.work_height) {
        return 0;
    }
    let paper = paper(&work);
    let grain = paper_grain(page, paper);
    let regions: Vec<&Region> = found.fingers.iter().chain(&found.glare).filter(|r| !r.covers_ink).collect();
    if regions.is_empty() {
        return 0;
    }
    let mut mask = vec![false; work.w * work.h];
    for r in &regions {
        for &i in &r.pixels {
            mask[i] = true;
        }
    }
    // grown by a working pixel so the region's soft edge (a finger's shadow line) goes too
    let grown = dilate(&(0..mask.len()).filter(|&i| mask[i]).collect::<Vec<_>>(), work.w, work.h, 1);
    let weight = full_weight(&grown, work.w, work.h, page.width as usize, page.height as usize, FEATHER);
    let mut seed = 0x9E37_79B9_7F4A_7C15u64;
    for (i, &k) in weight.iter().enumerate() {
        if k <= 0.0 {
            continue;
        }
        seed = seed.wrapping_mul(6_364_136_223_846_793_005).wrapping_add(1_442_695_040_888_963_407);
        // a triangular grain of the paper's spread, the same for the three channels
        let noise = (((seed >> 40) & 0xFFFF) as f32 / 65_535.0 + ((seed >> 20) & 0xFFFF) as f32 / 65_535.0 - 1.0) * grain * 1.22;
        for (out, white) in page.data[i * 3..i * 3 + 3].iter_mut().zip(paper) {
            let v = f32::from(*out);
            let target = white + noise;
            *out = (v + k * (target - v)).round().clamp(0.0, 255.0) as u8;
        }
    }
    regions.len()
}

/// Divide each stain's colour and darkness out: inside the stain (grown a little), every channel
/// is scaled by the paper's value over the local paper's (the lightest value nearby, smoothed),
/// so print keeps its contrast and the paper comes back to the page's paper. Returns how many
/// stains were treated.
pub fn normalise_stains(page: &mut Rgb, found: &Defects) -> usize {
    let Some(work) = Work::new(page) else { return 0 };
    if (work.w, work.h) != (found.work_width, found.work_height) || found.stains.is_empty() {
        return 0;
    }
    let (w, h) = (work.w, work.h);
    let (pw, ph) = (page.width as usize, page.height as usize);
    let paper = paper(&work);
    // the local paper at working size: lightest within a radius of 1.5 % of the page, then a box
    // blur of the same size (a smaller closing than the page-wide light, as the issue asks)
    let radius = ((0.015 * w.max(h) as f32).round() as usize).max(2);
    let mut local = [vec![0f32; w * h], vec![0f32; w * h], vec![0f32; w * h]];
    for (c, plane) in local.iter_mut().enumerate() {
        let channel: Vec<f32> = work.rgb.iter().map(|v| v[c]).collect();
        *plane = box_blur(&lightest(&channel, w, h, radius), w, h, radius);
    }
    let mut mask = Vec::new();
    for r in &found.stains {
        mask.extend_from_slice(&r.pixels);
    }
    let grown = dilate(&mask, w, h, STAIN_BLOCK / 2);
    let weight = full_weight(&grown, w, h, pw, ph, FEATHER * 2.0);
    let (sx, sy) = (w as f32 / pw as f32, h as f32 / ph as f32);
    for (i, &k) in weight.iter().enumerate() {
        if k <= 0.0 {
            continue;
        }
        let (x, y) = (i % pw, i / pw);
        let (wx, wy) = (((x as f32 + 0.5) * sx - 0.5).max(0.0), ((y as f32 + 0.5) * sy - 0.5).max(0.0));
        for c in 0..3 {
            let b = bilinear(&local[c], w, h, wx, wy).max(1.0);
            let gain = (paper[c] / b).clamp(0.5, 3.0);
            let v = f32::from(page.data[i * 3 + c]);
            page.data[i * 3 + c] = (v * (1.0 + k * (gain - 1.0))).round().clamp(0.0, 255.0) as u8;
        }
    }
    found.stains.len()
}

/// The spread of the paper's grey levels in the full page (pixels near the paper's colour).
fn paper_grain(page: &Rgb, paper: [f32; 3]) -> f32 {
    let pl = luma(paper);
    let (mut sum, mut sq, mut n) = (0f64, 0f64, 0f64);
    for px in page.data.as_chunks::<3>().0.iter().step_by(7) {
        let l = luma([f32::from(px[0]), f32::from(px[1]), f32::from(px[2])]);
        if (l - pl).abs() < 12.0 {
            sum += f64::from(l);
            sq += f64::from(l) * f64::from(l);
            n += 1.0;
        }
    }
    if n < 2.0 {
        return 0.0;
    }
    let mean = sum / n;
    ((sq / n - mean * mean).max(0.0).sqrt()) as f32
}

/// A working-size mask as a weight per full-size pixel: 1 inside, falling to 0 over `feather`
/// full-size pixels outside it.
fn full_weight(mask: &[bool], w: usize, h: usize, pw: usize, ph: usize, feather: f32) -> Vec<f32> {
    let soft: Vec<f32> = mask.iter().map(|&m| if m { 1.0 } else { 0.0 }).collect();
    let scale = pw as f32 / w as f32;
    let reach = ((feather / scale).ceil() as usize).max(1);
    let soft = box_blur(&box_blur(&soft, w, h, reach), w, h, reach.div_ceil(2));
    let (sx, sy) = (w as f32 / pw as f32, h as f32 / ph as f32);
    let mut out = vec![0f32; pw * ph];
    for y in 0..ph {
        let wy = ((y as f32 + 0.5) * sy - 0.5).max(0.0);
        for x in 0..pw {
            let wx = ((x as f32 + 0.5) * sx - 0.5).max(0.0);
            let inside = mask[(wy.round() as usize).min(h - 1) * w + (wx.round() as usize).min(w - 1)];
            let k = bilinear(&soft, w, h, wx, wy);
            out[y * pw + x] = if inside { 1.0 } else { (k * 2.0).min(1.0) };
        }
    }
    out
}

fn bilinear(plane: &[f32], w: usize, h: usize, x: f32, y: f32) -> f32 {
    let (x0, y0) = ((x as usize).min(w - 1), (y as usize).min(h - 1));
    let (x1, y1) = ((x0 + 1).min(w - 1), (y0 + 1).min(h - 1));
    let (fx, fy) = (x - x0 as f32, y - y0 as f32);
    let top = plane[y0 * w + x0] * (1.0 - fx) + plane[y0 * w + x1] * fx;
    let bottom = plane[y1 * w + x0] * (1.0 - fx) + plane[y1 * w + x1] * fx;
    top * (1.0 - fy) + bottom * fy
}

/// The lightest value within `r` pixels (a square), rows then columns.
fn lightest(src: &[f32], w: usize, h: usize, r: usize) -> Vec<f32> {
    let mut rows = vec![0f32; w * h];
    for y in 0..h {
        for x in 0..w {
            let (a, b) = (x.saturating_sub(r), (x + r).min(w - 1));
            rows[y * w + x] = src[y * w + a..=y * w + b].iter().copied().fold(f32::MIN, f32::max);
        }
    }
    let mut out = vec![0f32; w * h];
    for y in 0..h {
        let (a, b) = (y.saturating_sub(r), (y + r).min(h - 1));
        for x in 0..w {
            out[y * w + x] = (a..=b).map(|yy| rows[yy * w + x]).fold(f32::MIN, f32::max);
        }
    }
    out
}

/// A box blur of radius `r`, rows then columns, the box cut off at the borders.
fn box_blur(src: &[f32], w: usize, h: usize, r: usize) -> Vec<f32> {
    let pass = |src: &[f32], len: usize, count: usize, at: &dyn Fn(usize, usize) -> usize| -> Vec<f32> {
        let mut out = vec![0f32; src.len()];
        for line in 0..count {
            let mut prefix = vec![0f32; len + 1];
            for i in 0..len {
                prefix[i + 1] = prefix[i] + src[at(line, i)];
            }
            for i in 0..len {
                let (a, b) = (i.saturating_sub(r), (i + r).min(len - 1));
                out[at(line, i)] = (prefix[b + 1] - prefix[a]) / (b + 1 - a) as f32;
            }
        }
        out
    };
    let rows = pass(src, w, h, &|line, i| line * w + i);
    pass(&rows, h, w, &|line, i| i * w + line)
}

/// The pixels within `r` (a square) of any of `pixels`.
fn dilate(pixels: &[usize], w: usize, h: usize, r: usize) -> Vec<bool> {
    let mut seed = vec![0f32; w * h];
    for &i in pixels {
        seed[i] = 1.0;
    }
    if r == 0 {
        return seed.iter().map(|&v| v > 0.0).collect();
    }
    lightest(&seed, w, h, r).iter().map(|&v| v > 0.0).collect()
}

/// Connected regions of a mask (4-neighbours), each as sorted indices, in order of their first
/// pixel.
fn components(mask: &[bool], w: usize, h: usize) -> Vec<Vec<usize>> {
    let mut seen = vec![false; mask.len()];
    let mut out = Vec::new();
    let mut stack = Vec::new();
    for start in 0..mask.len() {
        if !mask[start] || seen[start] {
            continue;
        }
        seen[start] = true;
        stack.push(start);
        let mut region = Vec::new();
        while let Some(i) = stack.pop() {
            region.push(i);
            let (x, y) = (i % w, i / w);
            let mut visit = |j: usize| {
                if mask[j] && !seen[j] {
                    seen[j] = true;
                    stack.push(j);
                }
            };
            if x > 0 {
                visit(i - 1);
            }
            if x + 1 < w {
                visit(i + 1);
            }
            if y > 0 {
                visit(i - w);
            }
            if y + 1 < h {
                visit(i + w);
            }
        }
        region.sort_unstable();
        out.push(region);
    }
    out
}
