//! Marks made by hand on a scanned page (#495): an eraser that paints the paper over what is
//! rubbed, a pen, a highlighter and text boxes, drawn on the made page after its look (and after
//! the automatic finger step), so changing the look keeps them.
//!
//! Marks are data, not pixels: points as shares of the page as it was shown when they were drawn,
//! widths as shares of the page's longer side, and the quarter turns the page had then. A later
//! quarter turn of the page moves them exactly; nothing else does, so the app clears a page's
//! marks when its borders, its flattening or its book split change.
//!
//! The eraser takes the paper from around what it rubs: the page is cut into blocks, each block's
//! paper is the mean of its lighter half (print is the darker part), blocks under the eraser take
//! their neighbours' paper, and the fill follows those blocks smoothly, with the paper's own grain
//! added back and a soft edge, so it does not show as a flat patch on tinted or shaded paper. On a
//! black-and-white page the rubbed pixels become paper, pen and text are drawn black, and a
//! highlighter is left out (it is counted in [`Applied::skipped`]).
//!
//! The eraser has two more fills (#503): `EraseColour` paints a colour the person picked (from a
//! palette or with the eyedropper from the page), opaque with the eraser's soft edge, and
//! `EraseEdge` carries the colours at the brush's edge inward smoothly (a membrane between them,
//! not the lighter half of each block), then adds the grain, so a shaded band, a tinted area or the
//! desk beside a page cropped wide is continued instead of becoming paper.
//!
//! Text is drawn by the app (it has the fonts) into an alpha mask; this module scales the mask
//! into its box and turns it with the page.

use crate::enhance::Bitmap1;
use crate::look::Pixels;

/// What a stroke does.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Tool {
    /// Paints the paper around it over what it rubs.
    Erase,
    /// The eraser painting the stroke's colour, opaque, with the eraser's soft edge (#503).
    EraseColour,
    /// The eraser carrying the colours at its edge inward, with the grain added back (#503).
    EraseEdge,
    /// Opaque ink, its width following the pen's pressure.
    Pen,
    /// See-through ink that multiplies, so print under it stays readable.
    Highlighter,
}

/// One stroke.
#[derive(Clone, Debug, PartialEq)]
pub struct Stroke {
    pub tool: Tool,
    /// Red, green, blue (not used by the eraser's paper and edge fills).
    pub colour: [u8; 3],
    /// The stroke's full width at full pressure, as a share of the page's longer side.
    pub width: f32,
    /// (x, y, pressure): x and y as shares of the page's width and height as it was shown when the
    /// stroke was drawn, pressure 0–1 (1 for a finger).
    pub points: Vec<[f32; 3]>,
}

/// A text box: the app draws the text into `mask` (one byte of coverage per pixel, row by row).
#[derive(Clone, Debug, PartialEq)]
pub struct Text {
    /// The box's centre as shares of the page's width and height as shown when it was placed.
    pub centre: [f32; 2],
    /// The box's width and height as shares of the page's longer side.
    pub size: [f32; 2],
    pub colour: [u8; 3],
    pub mask_width: u32,
    pub mask_height: u32,
    pub mask: Vec<u8>,
}

/// Every mark of one page.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Markup {
    /// The quarter turns clockwise the page had in all (classifier and person) when the marks were
    /// made: the page made now may have others, and the marks turn with it.
    pub turns: u8,
    /// In the order they were drawn.
    pub strokes: Vec<Stroke>,
    /// Drawn over every stroke.
    pub texts: Vec<Text>,
}

impl Markup {
    pub fn is_empty(&self) -> bool {
        self.strokes.is_empty() && self.texts.is_empty()
    }
}

/// What drawing the marks did.
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct Applied {
    /// Pen strokes drawn.
    pub pen: u32,
    /// Highlighter strokes drawn.
    pub highlighter: u32,
    /// Eraser strokes applied, whatever their fill.
    pub erase: u32,
    /// Of those, the ones that painted a picked colour (#503).
    pub erase_colour: u32,
    /// Of those, the ones that carried the colours at their edge inward (#503).
    pub erase_edge: u32,
    /// Text boxes drawn.
    pub text: u32,
    /// Marks left out: a highlighter on a black-and-white page, or a text box whose mask does not
    /// match its size.
    pub skipped: u32,
    /// The share of the page the eraser painted (its soft edge counted by weight), 0–1.
    pub erased_share: f32,
    pub ms: u32,
}

/// The longest stroke read (points); a longer one is cut there. A finger moving for a minute at
/// 120 events a second is under 8 000.
pub const MAX_POINTS: usize = 20_000;
/// The most strokes and text boxes drawn on one page.
pub const MAX_MARKS: usize = 2_000;
/// A text mask's largest side, in pixels.
pub const MAX_MASK_SIDE: u32 = 4096;
/// How much of its colour a highlighter lays down where it fully covers.
pub const HIGHLIGHT_STRENGTH: f32 = 0.85;
/// A pen at no pressure is this share of its full width.
pub const PEN_MIN_WIDTH: f32 = 0.35;

/// Draw `markup` on a page of `width` × `height` whose quarter turns in all are `page_turns`.
pub fn apply(pixels: &mut Pixels, width: u32, height: u32, page_turns: u8, markup: &Markup) -> Applied {
    let clock = std::time::Instant::now();
    let mut out = Applied::default();
    let (w, h) = (width as usize, height as usize);
    if w == 0 || h == 0 || markup.is_empty() {
        return out;
    }
    let turn = (page_turns % 4 + 4 - markup.turns % 4) % 4;
    let long = w.max(h) as f32;
    let mut canvas = match Canvas::of(pixels, w, h) {
        Some(c) => c,
        None => return out,
    };
    let strokes = &markup.strokes[..markup.strokes.len().min(MAX_MARKS)];
    let mut erased = 0f64;
    let mut at = 0;
    while at < strokes.len() {
        // consecutive eraser strokes of one fill are one fill: the paper (or the edge) is measured
        // once, around all of them; a colour fill is one per colour
        let tool = strokes[at].tool;
        if matches!(tool, Tool::Erase | Tool::EraseColour | Tool::EraseEdge) {
            let colour = strokes[at].colour;
            let same = |s: &Stroke| s.tool == tool && (tool != Tool::EraseColour || s.colour == colour);
            let end = strokes[at..].iter().position(|s| !same(s)).map_or(strokes.len(), |n| at + n);
            let group = &strokes[at..end];
            let paths: Vec<Path> = group.iter().filter_map(|s| Path::of(s, turn, w, h, long)).collect();
            let n = u32::try_from(paths.len()).unwrap_or(u32::MAX);
            match tool {
                Tool::EraseColour => {
                    erased += erase_colour(&mut canvas, &paths, colour);
                    out.erase_colour += n;
                }
                Tool::EraseEdge => {
                    erased += erase_edge(&mut canvas, &paths);
                    out.erase_edge += n;
                }
                _ => erased += erase(&mut canvas, &paths),
            }
            out.erase += n;
            at = end;
            continue;
        }
        let stroke = &strokes[at];
        at += 1;
        let Some(path) = Path::of(stroke, turn, w, h, long) else { continue };
        match stroke.tool {
            Tool::Erase | Tool::EraseColour | Tool::EraseEdge => {}
            Tool::Highlighter if canvas.is_bilevel() => out.skipped += 1,
            Tool::Highlighter => {
                draw(&mut canvas, &path, stroke.colour, true);
                out.highlighter += 1;
            }
            _ => {
                draw(&mut canvas, &path, stroke.colour, false);
                out.pen += 1;
            }
        }
    }
    for text in markup.texts.iter().take(MAX_MARKS) {
        if draw_text(&mut canvas, text, turn, long) {
            out.text += 1;
        } else {
            out.skipped += 1;
        }
    }
    canvas.finish(pixels);
    out.erased_share = (erased / (w * h) as f64).clamp(0.0, 1.0) as f32;
    out.ms = u32::try_from(clock.elapsed().as_millis()).unwrap_or(u32::MAX);
    out
}

/// A share point of the page as drawn, moved by `turn` quarter turns clockwise.
pub fn turn_point(x: f32, y: f32, turn: u8) -> (f32, f32) {
    let (mut x, mut y) = (x, y);
    for _ in 0..turn % 4 {
        (x, y) = (1.0 - y, x);
    }
    (x, y)
}

/// The pixels being drawn on: colour or grey bytes, or a black-and-white page's bits unpacked
/// to one byte each (0 ink, 255 paper) and packed again at the end.
struct Canvas {
    w: usize,
    h: usize,
    channels: usize,
    data: Vec<u8>,
    bilevel: bool,
}

impl Canvas {
    fn of(pixels: &mut Pixels, w: usize, h: usize) -> Option<Canvas> {
        let (channels, data, bilevel) = match pixels {
            Pixels::Rgb(d) => (3, std::mem::take(d), false),
            Pixels::Grey(d) => (1, std::mem::take(d), false),
            Pixels::Bilevel(b) => {
                if (b.width as usize, b.height as usize) != (w, h) {
                    return None;
                }
                (1, b.to_grey(), true)
            }
        };
        if data.len() != w * h * channels {
            // give the pixels back untouched
            match pixels {
                Pixels::Rgb(d) | Pixels::Grey(d) => *d = data,
                Pixels::Bilevel(_) => {}
            }
            return None;
        }
        Some(Canvas { w, h, channels, data, bilevel })
    }

    fn is_bilevel(&self) -> bool {
        self.bilevel
    }

    fn finish(self, pixels: &mut Pixels) {
        match pixels {
            Pixels::Rgb(d) | Pixels::Grey(d) => *d = self.data,
            Pixels::Bilevel(b) => {
                let stride = b.stride();
                let mut bits = vec![0u8; stride * self.h];
                for y in 0..self.h {
                    for x in 0..self.w {
                        if self.data[y * self.w + x] >= 128 {
                            bits[y * stride + x / 8] |= 0x80 >> (x % 8);
                        }
                    }
                }
                *b = Bitmap1 { width: b.width, height: b.height, data: bits };
            }
        }
    }

    /// Blend pixel `i` toward `target` (one value per channel) by `k`.
    fn blend(&mut self, i: usize, target: [f32; 3], k: f32) {
        if self.bilevel {
            if k >= 0.5 {
                self.data[i] = if grey_of(target) >= 128.0 { 255 } else { 0 };
            }
            return;
        }
        if self.channels == 1 {
            let v = f32::from(self.data[i]);
            self.data[i] = (v + k * (grey_of(target) - v)).round().clamp(0.0, 255.0) as u8;
            return;
        }
        for (c, t) in target.iter().enumerate() {
            let v = f32::from(self.data[i * 3 + c]);
            self.data[i * 3 + c] = (v + k * (t - v)).round().clamp(0.0, 255.0) as u8;
        }
    }

    /// Multiply pixel `i` by `colour` (0–1 per channel) with weight `k`.
    fn multiply(&mut self, i: usize, colour: [f32; 3], k: f32) {
        if self.channels == 1 {
            let f = 1.0 - k + k * grey_of(colour.map(|c| c * 255.0)) / 255.0;
            self.data[i] = (f32::from(self.data[i]) * f).round().clamp(0.0, 255.0) as u8;
            return;
        }
        for (c, col) in colour.iter().enumerate() {
            let f = 1.0 - k + k * col;
            self.data[i * 3 + c] = (f32::from(self.data[i * 3 + c]) * f).round().clamp(0.0, 255.0) as u8;
        }
    }

    fn colour_at(&self, i: usize) -> [f32; 3] {
        if self.channels == 1 {
            let v = f32::from(self.data[i]);
            [v, v, v]
        } else {
            [f32::from(self.data[i * 3]), f32::from(self.data[i * 3 + 1]), f32::from(self.data[i * 3 + 2])]
        }
    }
}

fn grey_of(c: [f32; 3]) -> f32 {
    0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]
}

/// A stroke in the page's pixels: each point with its radius.
struct Path {
    points: Vec<(f32, f32, f32)>,
}

impl Path {
    fn of(stroke: &Stroke, turn: u8, w: usize, h: usize, long: f32) -> Option<Path> {
        if !stroke.width.is_finite() || stroke.width <= 0.0 {
            return None;
        }
        let full = (stroke.width.min(1.0) * long / 2.0).max(0.5);
        let points: Vec<(f32, f32, f32)> = stroke
            .points
            .iter()
            .take(MAX_POINTS)
            .filter(|p| p.iter().all(|v| v.is_finite()))
            .map(|p| {
                let (x, y) = turn_point(p[0].clamp(-0.5, 1.5), p[1].clamp(-0.5, 1.5), turn);
                let r = match stroke.tool {
                    Tool::Pen => full * (PEN_MIN_WIDTH + (1.0 - PEN_MIN_WIDTH) * p[2].clamp(0.0, 1.0)),
                    _ => full,
                };
                (x * w as f32, y * h as f32, r.max(0.5))
            })
            .collect();
        (!points.is_empty()).then_some(Path { points })
    }

    /// The pixels the path can touch, grown by `extra`, cut to the page: (x0, y0, x1, y1), ends
    /// excluded.
    fn bounds(&self, extra: f32, w: usize, h: usize) -> Option<(usize, usize, usize, usize)> {
        let (mut x0, mut y0, mut x1, mut y1) = (f32::MAX, f32::MAX, f32::MIN, f32::MIN);
        for &(x, y, r) in &self.points {
            x0 = x0.min(x - r - extra);
            y0 = y0.min(y - r - extra);
            x1 = x1.max(x + r + extra);
            y1 = y1.max(y + r + extra);
        }
        let x0 = x0.floor().max(0.0) as usize;
        let y0 = y0.floor().max(0.0) as usize;
        let x1 = (x1.ceil().max(0.0) as usize).min(w);
        let y1 = (y1.ceil().max(0.0) as usize).min(h);
        (x0 < x1 && y0 < y1).then_some((x0, y0, x1, y1))
    }

    /// Lay the path's coverage into `buf`, which covers `area` (x0, y0, x1, y1) of a page `w`
    /// pixels wide: `soft` turns how far inside the path a pixel's centre is (its radius there
    /// minus its distance, positive inside) and that radius into a weight 0–1. Each segment is
    /// looked at only over its own bounds, and a pixel keeps the most any segment gives it, so a
    /// stroke that crosses itself is not laid down twice.
    fn paint(&self, buf: &mut [u8], area: (usize, usize, usize, usize), reach: f32, soft: &dyn Fn(f32, f32) -> f32) {
        let (ax0, ay0, ax1, ay1) = area;
        let aw = ax1 - ax0;
        let pts = &self.points;
        let single = [pts[0], pts[0]];
        let pairs: Box<dyn Iterator<Item = &[(f32, f32, f32)]>> = if pts.len() == 1 { Box::new(std::iter::once(&single[..])) } else { Box::new(pts.windows(2)) };
        for pair in pairs {
            let ((ax, ay, ar), (bx, by, br)) = (pair[0], pair[1]);
            let r = ar.max(br) + reach;
            let x0 = ((ax.min(bx) - r).floor().max(ax0 as f32) as usize).max(ax0);
            let y0 = ((ay.min(by) - r).floor().max(ay0 as f32) as usize).max(ay0);
            let x1 = ((ax.max(bx) + r).ceil().max(0.0) as usize).min(ax1);
            let y1 = ((ay.max(by) + r).ceil().max(0.0) as usize).min(ay1);
            let (dx, dy) = (bx - ax, by - ay);
            let len2 = dx * dx + dy * dy;
            for y in y0..y1 {
                let py = y as f32 + 0.5;
                for x in x0..x1 {
                    let px = x as f32 + 0.5;
                    let t = if len2 > 0.0 { (((px - ax) * dx + (py - ay) * dy) / len2).clamp(0.0, 1.0) } else { 0.0 };
                    let (cx, cy) = (ax + t * dx, ay + t * dy);
                    let rr = ar + t * (br - ar);
                    let d = rr - ((px - cx).powi(2) + (py - cy).powi(2)).sqrt();
                    let k = (soft(d, rr).clamp(0.0, 1.0) * 255.0).round() as u8;
                    let at = (y - ay0) * aw + (x - ax0);
                    if k > buf[at] {
                        buf[at] = k;
                    }
                }
            }
        }
    }
}

/// A pen or highlighter stroke: its coverage (anti-aliased over one pixel) is found once for the
/// whole stroke, then laid down once.
fn draw(canvas: &mut Canvas, path: &Path, colour: [u8; 3], highlighter: bool) {
    let Some(area) = path.bounds(1.0, canvas.w, canvas.h) else { return };
    let (x0, y0, x1, y1) = area;
    let aw = x1 - x0;
    let mut cover = vec![0u8; aw * (y1 - y0)];
    path.paint(&mut cover, area, 1.0, &|d, _| d + 0.5);
    let target = colour.map(f32::from);
    let factor = colour.map(|c| f32::from(c) / 255.0);
    for y in y0..y1 {
        for x in x0..x1 {
            let k = f32::from(cover[(y - y0) * aw + (x - x0)]) / 255.0;
            if k <= 0.0 {
                continue;
            }
            let i = y * canvas.w + x;
            if highlighter {
                canvas.multiply(i, factor, k * HIGHLIGHT_STRENGTH);
            } else if canvas.bilevel {
                // a black-and-white page has only black ink
                canvas.blend(i, [0.0; 3], k);
            } else {
                canvas.blend(i, target, k);
            }
        }
    }
}

/// The eraser's soft edge for a brush of radius `r`: a quarter of it, at least a pixel and a half.
fn feather(r: f32) -> f32 {
    (r * 0.25).max(1.5)
}

/// The eraser's weights over the pixels `paths` touch: the area (x0, y0, x1, y1) and one byte a
/// pixel, 255 inside the brush falling to 0 across its soft edge.
fn eraser_cover(paths: &[Path], w: usize, h: usize) -> Option<((usize, usize, usize, usize), Vec<u8>)> {
    let reach = paths.iter().flat_map(|p| p.points.iter().map(|&(_, _, r)| feather(r))).fold(1.5f32, f32::max);
    let mut area: Option<(usize, usize, usize, usize)> = None;
    for p in paths {
        if let Some(b) = p.bounds(reach + 1.0, w, h) {
            area = Some(match area {
                None => b,
                Some(a) => (a.0.min(b.0), a.1.min(b.1), a.2.max(b.2), a.3.max(b.3)),
            });
        }
    }
    let (x0, y0, x1, y1) = area?;
    let mut cover = vec![0u8; (x1 - x0) * (y1 - y0)];
    for p in paths {
        p.paint(&mut cover, (x0, y0, x1, y1), reach, &|d, r| {
            let f = feather(r);
            let k = ((d + f) / (2.0 * f)).clamp(0.0, 1.0);
            k * k * (3.0 - 2.0 * k)
        });
    }
    Some(((x0, y0, x1, y1), cover))
}

/// Paint the paper around `paths` over them; returns the painted area in pixels (by weight).
fn erase(canvas: &mut Canvas, paths: &[Path]) -> f64 {
    let (w, h) = (canvas.w, canvas.h);
    let Some(((x0, y0, x1, y1), cover)) = eraser_cover(paths, w, h) else { return 0.0 };
    let bw = x1 - x0;
    let weight = |x: usize, y: usize| f32::from(cover[(y - y0) * bw + (x - x0)]) / 255.0;
    if canvas.bilevel {
        let mut total = 0f64;
        for y in y0..y1 {
            for x in x0..x1 {
                if weight(x, y) >= 0.5 {
                    canvas.data[y * w + x] = 255;
                    total += 1.0;
                }
            }
        }
        return total;
    }
    let paper = PaperMap::around(canvas, (x0, y0, x1, y1), &cover);
    let mut seed = 0x9E37_79B9_7F4A_7C15u64 ^ (((x0 as u64) << 32) | y0 as u64);
    let mut total = 0f64;
    for y in y0..y1 {
        for x in x0..x1 {
            let k = weight(x, y);
            if k <= 0.0 {
                continue;
            }
            total += f64::from(k);
            seed = seed.wrapping_mul(6_364_136_223_846_793_005).wrapping_add(1_442_695_040_888_963_407);
            // a triangular grain of the paper's spread, the same for every channel (as #466's fill)
            let noise = (((seed >> 40) & 0xFFFF) as f32 / 65_535.0 + ((seed >> 20) & 0xFFFF) as f32 / 65_535.0 - 1.0) * paper.grain * 1.22;
            let target = paper.at(x as f32 + 0.5, y as f32 + 0.5).map(|c| c + noise);
            canvas.blend(y * w + x, target, k);
        }
    }
    total
}

/// Paint `colour` over `paths`, opaque, with the eraser's soft edge (#503); on a black-and-white page
/// the colour is ink or paper, whichever it is nearer. Returns the painted area in pixels (by weight).
fn erase_colour(canvas: &mut Canvas, paths: &[Path], colour: [u8; 3]) -> f64 {
    let (w, h) = (canvas.w, canvas.h);
    let Some(((x0, y0, x1, y1), cover)) = eraser_cover(paths, w, h) else { return 0.0 };
    let bw = x1 - x0;
    let target = colour.map(f32::from);
    let mut total = 0f64;
    for y in y0..y1 {
        for x in x0..x1 {
            let k = f32::from(cover[(y - y0) * bw + (x - x0)]) / 255.0;
            if k <= 0.0 {
                continue;
            }
            total += f64::from(if canvas.bilevel { if k >= 0.5 { 1.0 } else { 0.0 } } else { k });
            canvas.blend(y * w + x, target, k);
        }
    }
    total
}

/// Carry the colours at the edge of `paths` inward over them (#503): returns the painted area in
/// pixels (by weight).
fn erase_edge(canvas: &mut Canvas, paths: &[Path]) -> f64 {
    let (w, h) = (canvas.w, canvas.h);
    let Some(((x0, y0, x1, y1), cover)) = eraser_cover(paths, w, h) else { return 0.0 };
    let bw = x1 - x0;
    let radius = paths.iter().flat_map(|p| p.points.iter().map(|&(_, _, r)| r)).fold(f32::MAX, f32::min);
    let edge = PaperMap::from_edge(canvas, (x0, y0, x1, y1), &cover, radius);
    let mut seed = 0x2545_F491_4F6C_DD1Du64 ^ (((x0 as u64) << 32) | y0 as u64);
    let mut total = 0f64;
    for y in y0..y1 {
        for x in x0..x1 {
            let k = f32::from(cover[(y - y0) * bw + (x - x0)]) / 255.0;
            if k <= 0.0 {
                continue;
            }
            total += f64::from(if canvas.bilevel { if k >= 0.5 { 1.0 } else { 0.0 } } else { k });
            seed = seed.wrapping_mul(6_364_136_223_846_793_005).wrapping_add(1_442_695_040_888_963_407);
            // a triangular grain with the spread measured around the brush (sqrt 6: its spread is 1)
            let noise = (((seed >> 40) & 0xFFFF) as f32 / 65_535.0 + ((seed >> 20) & 0xFFFF) as f32 / 65_535.0 - 1.0) * edge.grain * 2.449;
            let target = edge.at(x as f32 + 0.5, y as f32 + 0.5).map(|c| c + noise);
            canvas.blend(y * w + x, target, k);
        }
    }
    total
}

/// The paper's colour over part of a page, block by block, with what the eraser covers filled in
/// from the blocks around it.
struct PaperMap {
    /// The first block's top left, in page pixels, and a block's side.
    x0: usize,
    y0: usize,
    side: usize,
    cols: usize,
    rows: usize,
    colours: Vec<[f32; 3]>,
    /// The paper's grain: the spread of its lighter half's brightness, in grey levels.
    grain: f32,
}

impl PaperMap {
    /// The paper around `area` (whose eraser weights are `weight`), from a margin of a few blocks
    /// around it.
    fn around(canvas: &Canvas, area: (usize, usize, usize, usize), cover: &[u8]) -> PaperMap {
        let (w, h) = (canvas.w, canvas.h);
        let (ax0, ay0, ax1, ay1) = area;
        let aw = ax1 - ax0;
        // a block is about a 64th of the page's longer side: smaller than the shading on a photo,
        // larger than a word
        let side = (w.max(h) / 64).clamp(6, 96);
        let margin = side * 3;
        let x0 = ax0.saturating_sub(margin) / side * side;
        let y0 = ay0.saturating_sub(margin) / side * side;
        let x1 = (ax1 + margin).min(w);
        let y1 = (ay1 + margin).min(h);
        let cols = (x1 - x0).div_ceil(side).max(1);
        let rows = (y1 - y0).div_ceil(side).max(1);
        let erased = |x: usize, y: usize| x >= ax0 && x < ax1 && y >= ay0 && y < ay1 && cover[(y - ay0) * aw + (x - ax0)] > 0;
        let step = (side / 12).max(1);
        let mut colours = vec![[0f32; 3]; cols * rows];
        let mut known = vec![false; cols * rows];
        let mut spreads = Vec::new();
        let mut samples: Vec<(f32, [f32; 3])> = Vec::new();
        for by in 0..rows {
            for bx in 0..cols {
                samples.clear();
                let (sx0, sy0) = (x0 + bx * side, y0 + by * side);
                for y in (sy0..(sy0 + side).min(h)).step_by(step) {
                    for x in (sx0..(sx0 + side).min(w)).step_by(step) {
                        if erased(x, y) {
                            continue;
                        }
                        let c = canvas.colour_at(y * w + x);
                        samples.push((grey_of(c), c));
                    }
                }
                // a block mostly under the eraser says little about the paper
                if samples.len() < 8 {
                    continue;
                }
                // the lighter half is paper; print, stains and the stroke's shadow are darker
                samples.sort_unstable_by(|a, b| b.0.total_cmp(&a.0));
                let half = &samples[..samples.len().div_ceil(2)];
                let n = half.len() as f32;
                let mut sum = [0f32; 3];
                let mut lsum = 0f32;
                for (l, c) in half {
                    for k in 0..3 {
                        sum[k] += c[k];
                    }
                    lsum += l;
                }
                let mean = lsum / n;
                let var = half.iter().map(|(l, _)| (l - mean).powi(2)).sum::<f32>() / n;
                spreads.push(var.sqrt());
                colours[by * cols + bx] = sum.map(|s| s / n);
                known[by * cols + bx] = true;
            }
        }
        fill_unknown(&mut colours, &mut known, cols, rows);
        spreads.sort_unstable_by(f32::total_cmp);
        let grain = spreads.get(spreads.len() / 2).copied().unwrap_or(0.0).min(12.0);
        PaperMap { x0, y0, side, cols, rows, colours, grain }
    }

    /// What is around `area` (whose eraser weights are `cover`), carried inward (#503): blocks of
    /// about half the brush's smallest radius `radius`; a block mostly outside the brush keeps the
    /// median of its pixels (thin print does not move a median), and the blocks under the brush
    /// take the smooth surface that meets all of those (each the mean of its four neighbours, a
    /// membrane), so a gradient across the brush is continued, not cut into rings. The grain is the
    /// spread of the pixels outside the brush around the mean of their four neighbours, which a
    /// smooth shading does not change.
    fn from_edge(canvas: &Canvas, area: (usize, usize, usize, usize), cover: &[u8], radius: f32) -> PaperMap {
        let (w, h) = (canvas.w, canvas.h);
        let (ax0, ay0, ax1, ay1) = area;
        let aw = ax1 - ax0;
        let side = ((radius / 2.0).round() as usize).clamp(3, 32);
        let margin = side * 2;
        let x0 = ax0.saturating_sub(margin) / side * side;
        let y0 = ay0.saturating_sub(margin) / side * side;
        let x1 = (ax1 + margin).min(w);
        let y1 = (ay1 + margin).min(h);
        let cols = (x1 - x0).div_ceil(side).max(1);
        let rows = (y1 - y0).div_ceil(side).max(1);
        let erased = |x: usize, y: usize| x >= ax0 && x < ax1 && y >= ay0 && y < ay1 && cover[(y - ay0) * aw + (x - ax0)] > 0;
        let step = (side / 8).max(1);
        let mut colours = vec![[0f32; 3]; cols * rows];
        let mut known = vec![false; cols * rows];
        let mut channel: [Vec<f32>; 3] = [Vec::new(), Vec::new(), Vec::new()];
        for by in 0..rows {
            for bx in 0..cols {
                channel.iter_mut().for_each(Vec::clear);
                let (sx0, sy0) = (x0 + bx * side, y0 + by * side);
                let mut looked = 0usize;
                for y in (sy0..(sy0 + side).min(h)).step_by(step) {
                    for x in (sx0..(sx0 + side).min(w)).step_by(step) {
                        looked += 1;
                        if erased(x, y) {
                            continue;
                        }
                        let c = canvas.colour_at(y * w + x);
                        for k in 0..3 {
                            channel[k].push(c[k]);
                        }
                    }
                }
                // a block a quarter under the brush is part of what is being replaced
                if looked == 0 || channel[0].len() * 4 < looked * 3 {
                    continue;
                }
                let mut c = [0f32; 3];
                for k in 0..3 {
                    let v = &mut channel[k];
                    let mid = v.len() / 2;
                    v.select_nth_unstable_by(mid, f32::total_cmp);
                    c[k] = v[mid];
                }
                colours[by * cols + bx] = c;
                known[by * cols + bx] = true;
            }
        }
        let fixed = known.clone();
        fill_unknown(&mut colours, &mut known, cols, rows);
        // the membrane: Gauss-Seidel until nothing moves by a twentieth of a level
        for _ in 0..2000 {
            let mut moved = 0f32;
            for by in 0..rows {
                for bx in 0..cols {
                    let i = by * cols + bx;
                    if fixed[i] {
                        continue;
                    }
                    let (mut sum, mut n) = ([0f32; 3], 0f32);
                    for (nx, ny) in [(bx.wrapping_sub(1), by), (bx + 1, by), (bx, by.wrapping_sub(1)), (bx, by + 1)] {
                        if nx < cols && ny < rows {
                            let c = colours[ny * cols + nx];
                            for k in 0..3 {
                                sum[k] += c[k];
                            }
                            n += 1.0;
                        }
                    }
                    let next = sum.map(|s| s / n);
                    for k in 0..3 {
                        moved = moved.max((next[k] - colours[i][k]).abs());
                    }
                    colours[i] = next;
                }
            }
            if moved < 0.05 {
                break;
            }
        }
        // the grain: pixels outside the brush against the mean of their four neighbours (a smooth
        // shading leaves nothing); for white noise of spread s that is s * sqrt(1.25)
        let mut residuals = Vec::new();
        let gstep = ((x1 - x0) * (y1 - y0) / 20_000).max(1);
        let mut at = 0usize;
        for y in y0.max(1)..y1.min(h - 1) {
            for x in x0.max(1)..x1.min(w - 1) {
                at += 1;
                if at % gstep != 0 || erased(x, y) || erased(x - 1, y) || erased(x + 1, y) || erased(x, y - 1) || erased(x, y + 1) {
                    continue;
                }
                let g = |x: usize, y: usize| grey_of(canvas.colour_at(y * w + x));
                let r = g(x, y) - (g(x - 1, y) + g(x + 1, y) + g(x, y - 1) + g(x, y + 1)) / 4.0;
                residuals.push(r.abs());
            }
        }
        let grain = if canvas.bilevel || residuals.is_empty() {
            0.0
        } else {
            let mid = residuals.len() / 2;
            residuals.select_nth_unstable_by(mid, f32::total_cmp);
            // the median of |x| is 0.6745 of the spread of a normal x
            (residuals[mid] / 0.6745 / 1.118).min(12.0)
        };
        PaperMap { x0, y0, side, cols, rows, colours, grain }
    }

    /// The paper's colour at a page point, between the centres of the blocks around it.
    fn at(&self, x: f32, y: f32) -> [f32; 3] {
        let s = self.side as f32;
        let gx = ((x - self.x0 as f32) / s - 0.5).clamp(0.0, (self.cols - 1) as f32);
        let gy = ((y - self.y0 as f32) / s - 0.5).clamp(0.0, (self.rows - 1) as f32);
        let (cx0, cy0) = (gx as usize, gy as usize);
        let (cx1, cy1) = ((cx0 + 1).min(self.cols - 1), (cy0 + 1).min(self.rows - 1));
        let (fx, fy) = (gx - cx0 as f32, gy - cy0 as f32);
        let c = |cx: usize, cy: usize| self.colours[cy * self.cols + cx];
        let mut out = [0f32; 3];
        for (k, o) in out.iter_mut().enumerate() {
            let top = c(cx0, cy0)[k] * (1.0 - fx) + c(cx1, cy0)[k] * fx;
            let bottom = c(cx0, cy1)[k] * (1.0 - fx) + c(cx1, cy1)[k] * fx;
            *o = top * (1.0 - fy) + bottom * fy;
        }
        out
    }
}

/// Give every block with no paper of its own the mean of its known neighbours, ring by ring; with
/// none known at all, white.
fn fill_unknown(colours: &mut [[f32; 3]], known: &mut [bool], cols: usize, rows: usize) {
    if !known.iter().any(|&k| k) {
        colours.iter_mut().for_each(|c| *c = [255.0; 3]);
        return;
    }
    loop {
        let mut next: Vec<(usize, [f32; 3])> = Vec::new();
        for by in 0..rows {
            for bx in 0..cols {
                if known[by * cols + bx] {
                    continue;
                }
                let (mut sum, mut n) = ([0f32; 3], 0f32);
                for ny in by.saturating_sub(1)..=(by + 1).min(rows - 1) {
                    for nx in bx.saturating_sub(1)..=(bx + 1).min(cols - 1) {
                        if known[ny * cols + nx] {
                            let c = colours[ny * cols + nx];
                            for k in 0..3 {
                                sum[k] += c[k];
                            }
                            n += 1.0;
                        }
                    }
                }
                if n > 0.0 {
                    next.push((by * cols + bx, sum.map(|s| s / n)));
                }
            }
        }
        if next.is_empty() {
            return;
        }
        for (i, c) in next {
            colours[i] = c;
            known[i] = true;
        }
    }
}

/// Draw a text box; false when its mask does not match its size.
fn draw_text(canvas: &mut Canvas, text: &Text, turn: u8, long: f32) -> bool {
    let (mw, mh) = (text.mask_width as usize, text.mask_height as usize);
    if mw == 0 || mh == 0 || text.mask_width > MAX_MASK_SIDE || text.mask_height > MAX_MASK_SIDE || text.mask.len() != mw * mh {
        return false;
    }
    if !text.centre.iter().chain(&text.size).all(|v| v.is_finite()) || text.size.iter().any(|&s| s <= 0.0) {
        return false;
    }
    let (w, h) = (canvas.w, canvas.h);
    // the page as it was shown when the box was placed
    let (fw, fh) = if turn % 2 == 1 { (h, w) } else { (w, h) };
    let (bw, bh) = (text.size[0].min(2.0) * long, text.size[1].min(2.0) * long);
    let (left, top) = (text.centre[0] * fw as f32 - bw / 2.0, text.centre[1] * fh as f32 - bh / 2.0);
    // the box's corners on the page made now, for the pixels to look at
    let corners = [(left, top), (left + bw, top), (left, top + bh), (left + bw, top + bh)].map(|(x, y)| {
        let (sx, sy) = turn_point(x / fw as f32, y / fh as f32, turn);
        (sx * w as f32, sy * h as f32)
    });
    let x0 = corners.iter().map(|c| c.0).fold(f32::MAX, f32::min).floor().max(0.0) as usize;
    let y0 = corners.iter().map(|c| c.1).fold(f32::MAX, f32::min).floor().max(0.0) as usize;
    let x1 = (corners.iter().map(|c| c.0).fold(f32::MIN, f32::max).ceil().max(0.0) as usize).min(w);
    let y1 = (corners.iter().map(|c| c.1).fold(f32::MIN, f32::max).ceil().max(0.0) as usize).min(h);
    let target = if canvas.bilevel { [0.0; 3] } else { text.colour.map(f32::from) };
    let back = (4 - turn % 4) % 4;
    for y in y0..y1 {
        for x in x0..x1 {
            // back to the page as shown when the box was placed, then into the mask
            let (sx, sy) = turn_point((x as f32 + 0.5) / w as f32, (y as f32 + 0.5) / h as f32, back);
            let u = (sx * fw as f32 - left) / bw * mw as f32 - 0.5;
            let v = (sy * fh as f32 - top) / bh * mh as f32 - 0.5;
            if u < -0.5 || v < -0.5 || u > mw as f32 - 0.5 || v > mh as f32 - 0.5 {
                continue;
            }
            let k = sample(&text.mask, mw, mh, u, v) / 255.0;
            if k > 0.0 {
                canvas.blend(y * w + x, target, k);
            }
        }
    }
    true
}

fn sample(mask: &[u8], w: usize, h: usize, u: f32, v: f32) -> f32 {
    let (u, v) = (u.clamp(0.0, (w - 1) as f32), v.clamp(0.0, (h - 1) as f32));
    let (x0, y0) = (u as usize, v as usize);
    let (x1, y1) = ((x0 + 1).min(w - 1), (y0 + 1).min(h - 1));
    let (fx, fy) = (u - x0 as f32, v - y0 as f32);
    let m = |x: usize, y: usize| f32::from(mask[y * w + x]);
    let top = m(x0, y0) * (1.0 - fx) + m(x1, y0) * fx;
    let bottom = m(x0, y1) * (1.0 - fx) + m(x1, y1) * fx;
    top * (1.0 - fy) + bottom * fy
}
