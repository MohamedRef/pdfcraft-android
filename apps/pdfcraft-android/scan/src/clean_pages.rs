//! Drawn pages for measuring the Clean scan look (#784): a page that is already straight and
//! cropped, with a mark per pixel saying what was drawn there, seen through a camera's faults
//! (uneven light, a shadow, a colour cast, blur, grain, the JPEG round trip). The geometry is left
//! out on purpose, so that a fault of the look cannot be mistaken for one of the edges.
//!
//! What a page holds is chosen for the questions of #784: print of three stroke widths with dots
//! over the letters, the same print 1.5 to 5 mm from each edge, faint thermal print, pencil,
//! coloured pen, highlighter over print, a dark and a pale ink stamp, and photographs with detail
//! in their shadows and their highlights. Strokes are not letters: nothing here can be read by
//! text recognition.
//!
//! Compiled for the crate's tests and behind the `synth` feature, never in the app.
//! Deterministic: the same group and seed give the same bytes.

use image::ImageEncoder;

use crate::synth::{Rng, blur};

/// What a page pixel is, in [`CleanPage::marks`].
pub mod mark {
    pub const PAPER: u8 = 0;
    /// Black print with strokes 2 or 3 pixels wide.
    pub const INK: u8 = 1;
    /// Black print with strokes 1 pixel wide (an eighth of a millimetre).
    pub const THIN: u8 = 2;
    /// A dot over or under a letter, 2 to 4 pixels square.
    pub const DOT: u8 = 3;
    /// Print like [`INK`], 1.5 to 5 mm from an edge of the page.
    pub const EDGE: u8 = 4;
    /// Faint grey print, as a thermal receipt's.
    pub const FAINT: u8 = 5;
    pub const PENCIL: u8 = 6;
    /// Blue, red or green pen.
    pub const PEN: u8 = 7;
    /// Highlighter over blank paper.
    pub const HIGHLIGHT: u8 = 8;
    /// Print under highlighter.
    pub const HIGHLIGHT_INK: u8 = 9;
    /// A dark ink stamp, blue or red.
    pub const STAMP: u8 = 10;
    /// A pale ink stamp: the same colours at about a third of their depth.
    pub const STAMP_PALE: u8 = 11;
    /// A photograph's middle tones.
    pub const PHOTO: u8 = 12;
    /// A patch of a photograph's shadows with a fine pattern in it.
    pub const PHOTO_DARK: u8 = 13;
    /// A patch of a photograph's highlights with a fine pattern in it.
    pub const PHOTO_LIGHT: u8 = 14;
}

/// The groups of pages, named for what each is there to show.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum Group {
    /// Everyday print in good light.
    Text,
    /// A long thermal receipt: faint print, a dark heading and a stamp.
    Receipt,
    /// Print with pencil writing and faint print beside it.
    Pencil,
    /// Print with pen, highlighter and stamps.
    Colour,
    /// Print beside a colour photograph and a grey one.
    Photo,
    /// The [`Group::Colour`] page under a hard shadow, strongly uneven light and a colour cast.
    Shadow,
    /// The [`Group::Pencil`] page in dim light: dark, grainy and soft.
    Lowlight,
}

impl Group {
    pub const ALL: [Group; 7] = [Group::Text, Group::Receipt, Group::Pencil, Group::Colour, Group::Photo, Group::Shadow, Group::Lowlight];

    pub fn name(self) -> &'static str {
        match self {
            Group::Text => "text",
            Group::Receipt => "receipt",
            Group::Pencil => "pencil",
            Group::Colour => "colour",
            Group::Photo => "photo",
            Group::Shadow => "shadow",
            Group::Lowlight => "lowlight",
        }
    }

    pub fn from_name(name: &str) -> Option<Group> {
        Group::ALL.into_iter().find(|g| g.name() == name)
    }
}

/// A4 at 200 dpi: large enough that the look does not enlarge the page, so it comes out pixel for
/// pixel where it went in and the marks need no resampling.
pub const A4_SIZE: (u32, u32) = (1654, 2339);
/// A receipt 2.8 times as long as it is wide, with as many pixels as the A4 page.
pub const RECEIPT_SIZE: (u32, u32) = (1150, 3220);
/// The resolution the A4 page is drawn at.
pub const DPI: f64 = 200.0;

/// The camera's faults on one page.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Faults {
    /// How much darker the far side of the page is than the near one (0 for even light).
    pub falloff: f64,
    /// The light inside the shadow as a share of the light outside it; `None` for no shadow.
    pub shadow: Option<f64>,
    /// The whole page's light (1 for full; under 1 in dim light).
    pub dim: f64,
    /// The light's colour as factors on red and blue.
    pub cast: [f64; 2],
    /// Gaussian blur, sigma in pixels.
    pub blur: f64,
    /// Sensor noise, standard deviation in grey levels.
    pub noise: f64,
    /// JPEG quality.
    pub quality: u8,
}

/// A drawn page and its ground truth.
#[derive(Clone, Debug)]
pub struct CleanPage {
    pub group: Group,
    pub seed: u64,
    pub width: u32,
    pub height: u32,
    /// The page as it is on paper (RGB8): the truth for colour and for a photograph's tones.
    pub clean: Vec<u8>,
    /// One [`mark`] per pixel.
    pub marks: Vec<u8>,
    /// The page as the camera gives it (a JPEG file of the same size).
    pub jpeg: Vec<u8>,
    pub faults: Faults,
}

const PAPER: [u8; 3] = [250, 250, 248];
const BLACK: [u8; 3] = [24, 24, 28];

struct Canvas {
    rgb: Vec<u8>,
    marks: Vec<u8>,
    w: usize,
    h: usize,
}

impl Canvas {
    fn new(w: usize, h: usize) -> Canvas {
        Canvas { rgb: std::iter::repeat_n(PAPER, w * h).flatten().collect(), marks: vec![mark::PAPER; w * h], w, h }
    }

    fn set(&mut self, x: i64, y: i64, colour: [u8; 3], tag: u8) {
        if x < 0 || y < 0 || x >= self.w as i64 || y >= self.h as i64 {
            return;
        }
        let i = y as usize * self.w + x as usize;
        self.rgb[i * 3..i * 3 + 3].copy_from_slice(&colour);
        self.marks[i] = tag;
    }

    fn fill(&mut self, [x0, y0, x1, y1]: [i64; 4], colour: [u8; 3], tag: u8) {
        for y in y0..y1 {
            for x in x0..x1 {
                self.set(x, y, colour, tag);
            }
        }
    }
}

/// How a run of print is drawn.
#[derive(Clone, Copy)]
struct Print {
    /// Height of a letter in pixels.
    height: i64,
    /// Width of a stroke in pixels.
    stroke: i64,
    colour: [u8; 3],
    tag: u8,
    /// The tag of the dots over and under letters; `None` for no dots.
    dots: Option<u8>,
}

/// One letter-like shape in the box whose top-left corner is (x, y): two to four of a left stem,
/// a right stem, a top, middle and bottom bar and a diagonal. Returns the width used.
fn letter(c: &mut Canvas, rng: &mut Rng, x: i64, y: i64, p: Print) -> i64 {
    let (h, s) = (p.height, p.stroke);
    let w = (h * 3 / 5).max(s * 3);
    let mut bits = rng.next_u64() & 0x3f;
    if bits.count_ones() < 2 {
        bits |= 0b00_0101;
    }
    if bits & 1 != 0 {
        c.fill([x, y, x + s, y + h], p.colour, p.tag);
    }
    if bits & 2 != 0 {
        c.fill([x + w - s, y, x + w, y + h], p.colour, p.tag);
    }
    if bits & 4 != 0 {
        c.fill([x, y, x + w, y + s], p.colour, p.tag);
    }
    if bits & 8 != 0 {
        c.fill([x, y + (h - s) / 2, x + w, y + (h - s) / 2 + s], p.colour, p.tag);
    }
    if bits & 16 != 0 {
        c.fill([x, y + h - s, x + w, y + h], p.colour, p.tag);
    }
    if bits & 32 != 0 {
        for k in 0..h {
            let dx = k * (w - s) / h.max(1);
            c.fill([x + dx, y + k, x + dx + s, y + k + 1], p.colour, p.tag);
        }
    }
    if let Some(tag) = p.dots.filter(|_| rng.chance(0.3)) {
        let d = (s + 1).max(2);
        let (dx, dy) = (x + (w - d) / 2, if rng.chance(0.5) { y - h * 2 / 5 - d } else { y + h + h / 4 });
        c.fill([dx, dy, dx + d, dy + d], p.colour, tag);
    }
    w
}

/// Rows of words of `p` filling the box.
fn paragraph(c: &mut Canvas, rng: &mut Rng, [x0, y0, x1, y1]: [i64; 4], p: Print) {
    let (gap, space, pitch) = ((p.height / 4).max(p.stroke + 1), p.height * 4 / 5, p.height * 21 / 10);
    let mut y = y0 + p.height / 2;
    while y + p.height + p.height / 2 <= y1 {
        let mut x = x0;
        'row: loop {
            let letters = 2 + (rng.next_u64() % 6) as i64;
            for _ in 0..letters {
                if x + p.height > x1 {
                    break 'row;
                }
                x += letter(c, rng, x, y, p) + gap;
            }
            x += space;
        }
        y += pitch;
    }
}

/// One row of print along each edge of the page, 1.5 to 5 mm from it at 200 dpi.
fn edge_print(c: &mut Canvas, rng: &mut Rng) {
    let (w, h) = (c.w as i64, c.h as i64);
    let p = Print { height: 26, stroke: 3, colour: BLACK, tag: mark::EDGE, dots: None };
    let (near, corner) = (12, 60);
    // top and bottom rows
    for y in [near, h - near - p.height] {
        let mut x = corner;
        while x + p.height < w - corner {
            x += letter(c, rng, x, y, p) + 8;
            if rng.chance(0.2) {
                x += 22;
            }
        }
    }
    // left and right columns
    for x in [near, w - near - p.height * 3 / 5] {
        let mut y = corner;
        while y + p.height < h - corner {
            letter(c, rng, x, y, p);
            y += p.height + 10;
            if rng.chance(0.2) {
                y += 22;
            }
        }
    }
}

/// A wavy pen line from x0 to x1 about row `y`.
fn wavy(c: &mut Canvas, rng: &mut Rng, (x0, x1, y): (i64, i64, i64), stroke: i64, colour: [u8; 3], tag: u8) {
    let (amp, period, phase) = (rng.range(8.0, 18.0), rng.range(50.0, 110.0), rng.range(0.0, 6.0));
    for x in x0..x1 {
        let dy = (amp * ((x - x0) as f64 / period * std::f64::consts::TAU + phase).sin()).round() as i64;
        c.fill([x, y + dy, x + 1, y + dy + stroke], colour, tag);
    }
}

/// A highlighter band over what is already drawn in the box: paper takes the highlighter's
/// colour, print under it is multiplied by it.
fn highlight(c: &mut Canvas, [x0, y0, x1, y1]: [i64; 4], colour: [u8; 3]) {
    for y in y0.max(0)..y1.min(c.h as i64) {
        for x in x0.max(0)..x1.min(c.w as i64) {
            let i = y as usize * c.w + x as usize;
            match c.marks[i] {
                mark::PAPER => {
                    c.rgb[i * 3..i * 3 + 3].copy_from_slice(&colour);
                    c.marks[i] = mark::HIGHLIGHT;
                }
                mark::INK => {
                    for (v, &k) in c.rgb[i * 3..i * 3 + 3].iter_mut().zip(&colour) {
                        *v = (u32::from(*v) * u32::from(k) / 255) as u8;
                    }
                    c.marks[i] = mark::HIGHLIGHT_INK;
                }
                _ => {}
            }
        }
    }
}

/// A round stamp: two rings and a row of print across it.
fn stamp(c: &mut Canvas, rng: &mut Rng, (cx, cy, r): (i64, i64, i64), colour: [u8; 3], tag: u8) {
    for y in cy - r - 6..=cy + r + 6 {
        for x in cx - r - 6..=cx + r + 6 {
            let d = ((x - cx) as f64).hypot((y - cy) as f64);
            if (d - r as f64).abs() < 3.0 || (d - r as f64 * 0.78).abs() < 1.5 {
                c.set(x, y, colour, tag);
            }
        }
    }
    let p = Print { height: 28, stroke: 3, colour, tag, dots: None };
    let mut x = cx - r * 6 / 10;
    while x + p.height < cx + r * 6 / 10 {
        x += letter(c, rng, x, cy - p.height / 2, p) + 8;
    }
}

/// `colour` at `depth` (0–1) on paper.
fn pale(colour: [u8; 3], depth: f64) -> [u8; 3] {
    [0, 1, 2].map(|k| (f64::from(PAPER[k]) + (f64::from(colour[k]) - f64::from(PAPER[k])) * depth).round() as u8)
}

/// A photograph in the box: smooth tones over its whole range, a patch of shadow and a patch of
/// highlight each with a fine pattern 10 grey levels either way, in colour or in grey.
fn photograph(c: &mut Canvas, rng: &mut Rng, [x0, y0, x1, y1]: [i64; 4], colour: bool) {
    let (a, b, ph) = (rng.range(0.006, 0.014), rng.range(0.006, 0.014), rng.range(0.0, 6.0));
    let (bw, bh) = (x1 - x0, y1 - y0);
    // the patches: a fifth of the box each way, in opposite corners
    let dark = [x0 + bw / 12, y0 + bh * 6 / 10, x0 + bw / 12 + bw * 3 / 10, y0 + bh * 9 / 10];
    let light = [x1 - bw / 12 - bw * 3 / 10, y0 + bh / 10, x1 - bw / 12, y0 + bh * 4 / 10];
    let inside = |r: [i64; 4], x: i64, y: i64| x >= r[0] && x < r[2] && y >= r[1] && y < r[3];
    for y in y0..y1 {
        for x in x0..x1 {
            let (u, v) = ((x - x0) as f64, (y - y0) as f64);
            let fine = 10.0 * (u * std::f64::consts::TAU / 16.0).sin() * (v * std::f64::consts::TAU / 23.0).cos();
            let (rgb, tag) = if inside(dark, x, y) {
                let l = 30.0 + fine;
                (if colour { [l * 1.1, l, l * 0.8] } else { [l; 3] }, mark::PHOTO_DARK)
            } else if inside(light, x, y) {
                let l = 224.0 + fine;
                (if colour { [l, l * 0.99, l * 0.93] } else { [l; 3] }, mark::PHOTO_LIGHT)
            } else {
                let l = 128.0 + 78.0 * (u * a + ph).sin() * (v * b).cos() + 20.0 * ((u + v) * a * 2.0).sin();
                if colour {
                    ([l + 50.0 * (v * a * 1.3).sin(), l - 20.0 * (u * b).cos(), l + 45.0 * ((u - v) * b).cos()], mark::PHOTO)
                } else {
                    ([l; 3], mark::PHOTO)
                }
            };
            c.set(x, y, rgb.map(|v| v.round().clamp(0.0, 255.0) as u8), tag);
        }
    }
}

const fn print(height: i64, stroke: i64) -> Print {
    Print { height, stroke, colour: BLACK, tag: mark::INK, dots: Some(mark::DOT) }
}

/// The middle of a page of `group`, in the box.
fn middle(c: &mut Canvas, rng: &mut Rng, group: Group, [x0, y0, x1, y1]: [i64; 4]) {
    let (bw, bh) = (x1 - x0, y1 - y0);
    match group {
        Group::Text => paragraph(c, rng, [x0, y0, x1, y1], print(22, 2)),
        Group::Pencil | Group::Lowlight => {
            let grey = rng.range(150.0, 180.0) as u8;
            let pencil = Print { height: 34, stroke: 2, colour: [grey, grey, grey.saturating_add(4)], tag: mark::PENCIL, dots: None };
            paragraph(c, rng, [x0, y0, x1, y0 + bh / 2], pencil);
            wavy(c, rng, (x0, x0 + bw / 2, y0 + bh * 55 / 100), 2, pencil.colour, mark::PENCIL);
            let faint = rng.range(150.0, 175.0) as u8;
            paragraph(c, rng, [x0, y0 + bh * 62 / 100, x1, y1], Print { height: 24, stroke: 2, colour: [faint; 3], tag: mark::FAINT, dots: None });
        }
        Group::Colour | Group::Shadow => {
            // print with three highlighter bands over it
            paragraph(c, rng, [x0, y0, x1, y0 + bh * 45 / 100], print(22, 2));
            let row = 22 * 21 / 10;
            for (k, colour) in [[255, 242, 100], [170, 250, 150], [255, 180, 212]].into_iter().enumerate() {
                let y = y0 + 11 - 8 + row * (1 + 2 * k as i64);
                highlight(c, [x0 + bw / 10 * (1 + k as i64), y, x0 + bw / 10 * (6 + k as i64), y + 22 + 16], colour);
            }
            // pen: three lines of writing and a signature each
            let pens = [[30, 60, 170], [190, 30, 40], [20, 120, 60]];
            for (k, colour) in pens.into_iter().enumerate() {
                let y = y0 + bh * 50 / 100 + k as i64 * 70;
                let pen = Print { height: 36, stroke: 3, colour, tag: mark::PEN, dots: None };
                let mut x = x0;
                while x + pen.height < x0 + bw * 45 / 100 {
                    x += letter(c, rng, x, y, pen) + 10;
                }
                wavy(c, rng, (x0 + bw / 2, x1, y + 18), 3, colour, mark::PEN);
            }
            // stamps: a dark and a pale one of each colour
            let r = bw / 12;
            let cy = y0 + bh * 88 / 100;
            for (k, (colour, depth)) in
                [([40, 70, 190], 1.0), ([200, 40, 50], 1.0), ([40, 70, 190], 0.35), ([200, 40, 50], 0.35)].into_iter().enumerate()
            {
                let tag = if depth < 1.0 { mark::STAMP_PALE } else { mark::STAMP };
                stamp(c, rng, (x0 + bw / 8 + k as i64 * bw / 4, cy, r), pale(colour, depth), tag);
            }
        }
        Group::Photo => {
            // a colour photograph with print beside it, then a grey one the other way round
            let half = bh / 2 - 20;
            photograph(c, rng, [x0, y0, x0 + bw * 55 / 100, y0 + half], true);
            paragraph(c, rng, [x0 + bw * 60 / 100, y0, x1, y0 + half], print(22, 2));
            paragraph(c, rng, [x0, y0 + half + 40, x0 + bw * 40 / 100, y1], print(22, 2));
            photograph(c, rng, [x0 + bw * 45 / 100, y0 + half + 40, x1, y1], false);
        }
        Group::Receipt => {}
    }
}

/// The clean page of `group` and its marks.
fn draw(rng: &mut Rng, group: Group, w: u32, h: u32) -> Canvas {
    let (wi, hi) = (i64::from(w), i64::from(h));
    let mut c = Canvas::new(w as usize, h as usize);
    let m = wi * 8 / 100;
    if group == Group::Receipt {
        // a dark heading, faint print down the page, a stamp over blank paper, a dark total
        paragraph(&mut c, rng, [m, hi * 4 / 100, wi - m, hi * 12 / 100], print(30, 3));
        let faint = rng.range(140.0, 170.0) as u8;
        let thermal = Print { height: 24, stroke: 2, colour: [faint; 3], tag: mark::FAINT, dots: None };
        paragraph(&mut c, rng, [m, hi * 13 / 100, wi - m, hi * 62 / 100], thermal);
        paragraph(&mut c, rng, [m, hi * 63 / 100, wi - m, hi * 70 / 100], Print { height: 14, stroke: 1, tag: mark::THIN, ..print(14, 1) });
        stamp(&mut c, rng, (wi / 2, hi * 78 / 100, wi / 7), [40, 70, 190], mark::STAMP);
        paragraph(&mut c, rng, [m, hi * 87 / 100, wi - m, hi * 95 / 100], print(30, 3));
    } else {
        paragraph(&mut c, rng, [m, hi * 6 / 100, wi - m, hi * 20 / 100], print(30, 3));
        paragraph(&mut c, rng, [m, hi * 21 / 100, wi - m, hi * 30 / 100], print(22, 2));
        paragraph(&mut c, rng, [m, hi * 31 / 100, wi - m, hi * 40 / 100], Print { height: 14, stroke: 1, tag: mark::THIN, ..print(14, 1) });
        middle(&mut c, rng, group, [m, hi * 42 / 100, wi - m, hi * 76 / 100]);
        paragraph(&mut c, rng, [m, hi * 78 / 100, wi - m, hi * 93 / 100], print(22, 2));
    }
    edge_print(&mut c, rng);
    c
}

/// The camera's faults for a page of `group`.
fn faults(rng: &mut Rng, group: Group) -> Faults {
    let cast = |rng: &mut Rng, by: f64| [rng.range(1.0 - by, 1.0 + by), rng.range(1.0 - 1.6 * by, 1.0 + by)];
    match group {
        Group::Shadow => Faults {
            falloff: rng.range(0.25, 0.5),
            shadow: Some(rng.range(0.45, 0.75)),
            dim: 1.0,
            cast: cast(rng, 0.06),
            blur: rng.range(0.6, 1.0),
            noise: rng.range(1.0, 3.0),
            quality: 92,
        },
        Group::Lowlight => Faults {
            falloff: rng.range(0.25, 0.5),
            shadow: None,
            dim: rng.range(0.45, 0.7),
            cast: cast(rng, 0.06),
            blur: rng.range(0.8, 1.5),
            noise: rng.range(6.0, 10.0),
            quality: 90,
        },
        _ => Faults {
            falloff: rng.range(0.05, 0.25),
            shadow: None,
            dim: rng.range(0.85, 1.0),
            cast: cast(rng, 0.03),
            blur: rng.range(0.6, 1.0),
            noise: rng.range(1.0, 3.0),
            quality: 92,
        },
    }
}

/// The light on each pixel (1 = full): a fall-off across the page in a seeded direction, and a
/// shadow with a soft edge a few pixels wide when the faults have one.
fn light_map(rng: &mut Rng, f: &Faults, w: usize, h: usize) -> Vec<f32> {
    let (fw, fh) = (w as f64, h as f64);
    let dir = rng.range(0.0, std::f64::consts::TAU);
    let reach = fw.hypot(fh);
    let blob = (rng.range(0.2, 0.8) * fw, rng.range(0.2, 0.8) * fh, rng.range(0.3, 0.5) * fw, rng.range(0.2, 0.35) * fh, rng.range(4.0, 16.0));
    let mut light = Vec::with_capacity(w * h);
    for y in 0..h {
        for x in 0..w {
            let (u, v) = (x as f64 + 0.5, y as f64 + 0.5);
            let t = (((u - fw / 2.0) * dir.cos() + (v - fh / 2.0) * dir.sin()) / reach + 0.5).clamp(0.0, 1.0);
            let mut l = (1.0 - f.falloff * t) * f.dim;
            if let Some(inside) = f.shadow {
                let (bx, by, rx, ry, soft) = blob;
                let d = (((u - bx) / rx).hypot((v - by) / ry) - 1.0) * rx.min(ry);
                let k = (0.5 - d / soft).clamp(0.0, 1.0);
                l *= 1.0 - (1.0 - inside) * k * k * (3.0 - 2.0 * k);
            }
            light.push(l as f32);
        }
    }
    light
}

/// The page of `group` for `seed`.
pub fn generate(group: Group, seed: u64) -> CleanPage {
    // the shadow and low-light pages are the colour and pencil pages of the same seed, drawn
    // from the same numbers, so each pair differs in the camera's faults alone
    let drawn_as = match group {
        Group::Shadow => Group::Colour,
        Group::Lowlight => Group::Pencil,
        other => other,
    };
    let mut rng = Rng::new(seed.wrapping_mul(131).wrapping_add(784 + drawn_as as u64));
    let (w, h) = if group == Group::Receipt { RECEIPT_SIZE } else { A4_SIZE };
    let canvas = draw(&mut rng, group, w, h);
    let mut rng = Rng::new(seed.wrapping_mul(977).wrapping_add(7840 + group as u64));
    let faults = faults(&mut rng, group);
    let light = light_map(&mut rng, &faults, w as usize, h as usize);
    let mut seen = canvas.rgb.clone();
    for (px, &l) in seen.as_chunks_mut::<3>().0.iter_mut().zip(&light) {
        let l = f64::from(l);
        *px = [f64::from(px[0]) * l * faults.cast[0], f64::from(px[1]) * l, f64::from(px[2]) * l * faults.cast[1]]
            .map(|v| v.round().clamp(0.0, 255.0) as u8);
    }
    blur(&mut seen, w, h, faults.blur);
    for v in &mut seen {
        *v = (f64::from(*v) + rng.normal() * faults.noise).round().clamp(0.0, 255.0) as u8;
    }
    let mut jpeg = Vec::new();
    // In memory, with a size that matches the buffer: cannot fail.
    let _ = image::codecs::jpeg::JpegEncoder::new_with_quality(&mut jpeg, faults.quality).write_image(&seen, w, h, image::ExtendedColorType::Rgb8);
    CleanPage { group, seed, width: w, height: h, clean: canvas.rgb, marks: canvas.marks, jpeg, faults }
}
