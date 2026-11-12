//! Drawn test photos with exact answers (issue #288, spec S02.md section 2).
//!
//! The scanner's tests need photos of pages whose right answers are known exactly: where the
//! corners are, what the clean page looks like, where the paper, ink, stamp and photograph are,
//! and how the light fell. This module draws them. Everything is a pure function of a seed: the
//! same seed and options give the same bytes, with no clock and no outside randomness.

/// The generator behind every drawn photo: xorshift64 (Marsaglia, 2003). It is seeded through a
/// fixed mixing constant and warmed up, so nearby seeds give unrelated streams. No dependency.
/// Tests of the scanner may use it too, which is why it is public.
#[derive(Clone, Debug)]
pub struct Rng {
    state: u64,
}

/// Mixed into every seed. xorshift must never hold zero, and this stops seed 0 from being one.
const SEED_MIX: u64 = 0x9E37_79B9_7F4A_7C15;
/// Rounds thrown away after seeding, so that seeds 1, 2, 3 ... do not start with similar values.
const WARM_UP_ROUNDS: usize = 8;
/// Scale that turns 53 random bits into a number in [0, 1).
const UNIT_SCALE: f64 = 1.0 / (1u64 << 53) as f64;

impl Rng {
    /// A generator for `seed`. The same seed always gives the same sequence.
    pub fn new(seed: u64) -> Self {
        let mut state = seed ^ SEED_MIX;
        if state == 0 {
            state = SEED_MIX;
        }
        let mut rng = Rng { state };
        for _ in 0..WARM_UP_ROUNDS {
            rng.next_u64();
        }
        rng
    }

    /// The next 64 random bits.
    pub fn next_u64(&mut self) -> u64 {
        let mut x = self.state;
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        self.state = x;
        x
    }

    /// A number in [0, 1).
    pub fn unit(&mut self) -> f64 {
        (self.next_u64() >> 11) as f64 * UNIT_SCALE
    }

    /// A number in [lo, hi).
    pub fn range(&mut self, lo: f64, hi: f64) -> f64 {
        lo + (hi - lo) * self.unit()
    }

    /// An index in 0..n. Returns 0 when n is 0, so a caller with an empty range gets a valid
    /// (if meaningless) index rather than a panic.
    pub fn below(&mut self, n: usize) -> usize {
        ((self.unit() * n as f64) as usize).min(n.saturating_sub(1))
    }

    /// A standard normal number (Box-Muller). The first uniform is taken from (0, 1] so that its
    /// logarithm is always finite.
    pub fn gauss(&mut self) -> f64 {
        let u1 = 1.0 - self.unit();
        let u2 = self.unit();
        (-2.0 * u1.ln()).sqrt() * (std::f64::consts::TAU * u2).cos()
    }
}

use crate::Rgb;

/// Smallest and largest side of a page or photo the generator draws, in pixels. Requests outside
/// this range are clamped, which keeps memory bounded by these numbers.
const SIDE_MIN: u32 = 16;
/// See `SIDE_MIN`.
const SIDE_MAX: u32 = 1600;
/// Ink for printed text and ruled lines: a near-black with a little blue.
const INK: [u8; 3] = [35, 35, 40];
/// Pencil ink: grey 170, the brief's low-contrast paragraph (on paper at 245).
const PENCIL: [u8; 3] = [170, 170, 170];
/// Blue of a rubber stamp.
const STAMP_BLUE: [u8; 3] = [30, 70, 190];
/// Red of a signature.
const SIGNATURE_RED: [u8; 3] = [180, 30, 35];
/// The text line pitch is the page height over this, and never less than `MIN_LINE_PX`.
const LINE_DIVISOR: usize = 40;
/// See `LINE_DIVISOR`.
const MIN_LINE_PX: usize = 6;
/// The left and right margins are the page width over this, and never less than one pixel.
const MARGIN_DIVISOR: usize = 10;
/// A paragraph has this many lines plus a random 0 to `PARAGRAPH_LINES_SPAN - 1`.
const PARAGRAPH_LINES_MIN: usize = 3;
/// See `PARAGRAPH_LINES_MIN`.
const PARAGRAPH_LINES_SPAN: usize = 4;
/// Each text line runs over a random share of the text width, from this to the full width.
const LINE_FILL_MIN: f64 = 0.6;
/// A word bar is this many line pitches long at least and at most (random between the two).
const WORD_MIN_LINES: f64 = 1.2;
/// See `WORD_MIN_LINES`.
const WORD_MAX_LINES: f64 = 3.5;
/// The gap between word bars, in line pitches, at least and at most.
const GAP_MIN_LINES: f64 = 0.4;
/// See `GAP_MIN_LINES`.
const GAP_MAX_LINES: f64 = 0.8;
/// The ruled table: its top and bottom as shares of the page height, its rows and its columns.
const TABLE_TOP: f64 = 0.55;
/// See `TABLE_TOP`.
const TABLE_BOTTOM: f64 = 0.80;
/// Rows of the table, each closed by a rule (so there are one more rules than rows).
const TABLE_ROWS: usize = 5;
/// Columns of the table, drawn as vertical rules between them.
const TABLE_COLUMNS: usize = 3;
/// The photo region's left, right, top and bottom as shares of the page.
const PHOTO_LEFT: f64 = 0.52;
/// See `PHOTO_LEFT`.
const PHOTO_RIGHT: f64 = 0.90;
/// See `PHOTO_LEFT`.
const PHOTO_TOP: f64 = 0.10;
/// See `PHOTO_LEFT`.
const PHOTO_BOTTOM: f64 = 0.34;
/// The smooth noise in the photo region comes from random values on a grid of this many points a
/// side, interpolated bilinearly.
const LATTICE: usize = 5;
/// Lowest and highest value of each channel on the photo region's grid.
const PHOTO_VALUE_MIN: f64 = 40.0;
/// See `PHOTO_VALUE_MIN`.
const PHOTO_VALUE_MAX: f64 = 220.0;
/// The signature runs from this x share to this x share, with its baseline at this y share.
const SIGNATURE_X0: f64 = 0.55;
/// See `SIGNATURE_X0`.
const SIGNATURE_X1: f64 = 0.90;
/// See `SIGNATURE_X0`.
const SIGNATURE_BASE: f64 = 0.90;
/// The signature's wave height as a share of the page height.
const SIGNATURE_WAVE: f64 = 0.02;
/// The stamp's centre (x and y shares of the page) and its outer radius as a share of the shorter side.
const STAMP_X: f64 = 0.80;
/// See `STAMP_X`.
const STAMP_Y: f64 = 0.86;
/// See `STAMP_X`.
const STAMP_RADIUS: f64 = 0.11;
/// The stamp's ring is this many times thinner than its radius (thickness = radius over this).
const STAMP_RING_DIVISOR: f64 = 6.0;
/// The stamp's two bars sit at this share of the radius above and below the centre.
const STAMP_BAR_SHARE: f64 = 1.0 / 3.0;

/// Options for a drawn page. Sizes are clamped to 16 to 1600 pixels.
#[derive(Clone, Debug)]
pub struct PageOptions {
    /// Width of the page in pixels.
    pub width: u32,
    /// Height of the page in pixels.
    pub height: u32,
    /// Colour of the blank paper, RGB.
    pub tint: [u8; 3],
    /// Ruled lines in the lower middle of the page.
    pub table: bool,
    /// A blue rubber stamp near the bottom right.
    pub stamp: bool,
    /// A red curved signature near the bottom.
    pub signature: bool,
    /// A rectangle of smooth noise (a photograph printed on the page) in the upper right.
    pub photo_region: bool,
    /// An extra paragraph in grey 170 ink, low contrast.
    pub pencil: bool,
}

/// A flat page and what is where on it. Each mask has one bool per pixel, row by row, and the four
/// masks partition the page: every pixel is paper, ink, stamp or photo region, and only one.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Page {
    /// The page as it would scan with no light, blur or noise, RGB8.
    pub clean: Rgb,
    /// Blank paper in the tint colour.
    pub paper: Vec<bool>,
    /// Printed text, rules, signature and pencil.
    pub ink: Vec<bool>,
    /// The stamp: its ring and its bars.
    pub stamp: Vec<bool>,
    /// The photograph region.
    pub photo_region: Vec<bool>,
}

/// What a pixel of the page is. The classes are exclusive, which is what makes the masks partition
/// the page.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Class {
    Paper,
    Ink,
    Stamp,
    Photo,
}

/// A colour together with the class of pixel it paints.
#[derive(Clone, Copy, Debug)]
struct Ink {
    class: Class,
    colour: [u8; 3],
}

/// A rectangle of pixels, `x0..x1` by `y0..y1`, with exclusive ends.
#[derive(Clone, Copy, Debug)]
struct Rect {
    x0: usize,
    y0: usize,
    x1: usize,
    y1: usize,
}

/// Layout numbers for a page of a given size, all in pixels.
#[derive(Clone, Copy, Debug)]
struct Metrics {
    /// Distance between text lines.
    line: usize,
    /// Height of a word bar.
    bar: usize,
    /// Left margin, and the top margin too.
    margin: usize,
    /// The x where the right margin starts.
    right: usize,
    /// The page height.
    height: usize,
}

impl Metrics {
    fn new(width: usize, height: usize) -> Self {
        let line = (height / LINE_DIVISOR).max(MIN_LINE_PX);
        let margin = (width / MARGIN_DIVISOR).max(1);
        Metrics { line, bar: (line * 2 / 5).max(2), margin, right: width.saturating_sub(margin), height }
    }
}

/// A raster the page is drawn on: a class and an RGB value for each pixel.
struct Canvas {
    width: usize,
    height: usize,
    class: Vec<Class>,
    rgb: Vec<u8>,
}

impl Canvas {
    /// A blank canvas of the tint colour.
    fn new(width: usize, height: usize, tint: [u8; 3]) -> Self {
        let n = width * height;
        Canvas {
            width,
            height,
            class: vec![Class::Paper; n],
            rgb: tint.iter().copied().cycle().take(n * 3).collect(),
        }
    }

    /// Paints one pixel. Pixels outside the canvas are ignored.
    fn paint(&mut self, x: usize, y: usize, ink: Ink) {
        if x >= self.width || y >= self.height {
            return;
        }
        let i = y * self.width + x;
        if let Some(c) = self.class.get_mut(i) {
            *c = ink.class;
        }
        if let Some(px) = self.rgb.get_mut(3 * i..3 * i + 3) {
            px.copy_from_slice(&ink.colour);
        }
    }

    /// Paints a rectangle, clipped to the canvas.
    fn fill(&mut self, r: Rect, ink: Ink) {
        for y in r.y0..r.y1.min(self.height) {
            for x in r.x0..r.x1.min(self.width) {
                self.paint(x, y, ink);
            }
        }
    }

    /// The mask of one class: one bool per pixel.
    fn mask(&self, class: Class) -> Vec<bool> {
        self.class.iter().map(|&c| c == class).collect()
    }

    /// The finished page.
    fn into_page(self) -> Page {
        Page {
            paper: self.mask(Class::Paper),
            ink: self.mask(Class::Ink),
            stamp: self.mask(Class::Stamp),
            photo_region: self.mask(Class::Photo),
            clean: Rgb { width: self.width as u32, height: self.height as u32, data: self.rgb },
        }
    }
}

/// `n` times the share `s`, rounded down.
fn share(n: usize, s: f64) -> usize {
    (n as f64 * s) as usize
}

/// A side length clamped to the range the generator draws.
fn clamp_side(side: u32) -> usize {
    side.clamp(SIDE_MIN, SIDE_MAX) as usize
}

/// Draws one paragraph: `lines` rows of word bars with gaps between the words, each row a random
/// share of the text width. Returns the row just below the paragraph.
fn paragraph(canvas: &mut Canvas, rng: &mut Rng, m: Metrics, top: usize, lines: usize, ink: Ink) -> usize {
    let mut y = top;
    for _ in 0..lines {
        let span = m.right.saturating_sub(m.margin) as f64;
        let end = m.margin + (span * rng.range(LINE_FILL_MIN, 1.0)) as usize;
        let mut x = m.margin;
        while x < end {
            let word = (m.line as f64 * rng.range(WORD_MIN_LINES, WORD_MAX_LINES)) as usize;
            let stop = (x + word.max(1)).min(end);
            canvas.fill(Rect { x0: x, y0: y, x1: stop, y1: y + m.bar }, ink);
            let gap = (m.line as f64 * rng.range(GAP_MIN_LINES, GAP_MAX_LINES)) as usize;
            x = stop + gap.max(1);
        }
        y += m.line;
    }
    y
}

/// Draws a paragraph of random length, with the given ink, and returns the row below it.
fn random_paragraph(canvas: &mut Canvas, rng: &mut Rng, m: Metrics, top: usize, ink: Ink) -> usize {
    let lines = PARAGRAPH_LINES_MIN + rng.below(PARAGRAPH_LINES_SPAN);
    paragraph(canvas, rng, m, top, lines, ink)
}

/// Draws the ruled table: horizontal rules (one more than the rows) and vertical rules.
fn draw_table(canvas: &mut Canvas, m: Metrics, ink: Ink) {
    let rule = (canvas.width / 400).max(1);
    let top = share(m.height, TABLE_TOP);
    let bottom = share(m.height, TABLE_BOTTOM).max(top);
    for k in 0..=TABLE_ROWS {
        let y = top + (bottom - top) * k / TABLE_ROWS;
        canvas.fill(Rect { x0: m.margin, y0: y, x1: m.right, y1: y + rule }, ink);
    }
    for k in 1..=TABLE_COLUMNS {
        let x = m.margin + m.right.saturating_sub(m.margin) * k / (TABLE_COLUMNS + 1);
        canvas.fill(Rect { x0: x, y0: top, x1: x + rule, y1: bottom + rule }, ink);
    }
}

/// Bilinear value of the lattice at the fractional grid position (`fx`, `fy`), per channel, as
/// bytes. The lattice is `LATTICE` by `LATTICE` values, row by row.
fn lattice_value(lattice: &[[f64; 3]], fx: f64, fy: f64) -> [u8; 3] {
    let (x0, y0) = (fx.floor() as usize, fy.floor() as usize);
    let (x1, y1) = ((x0 + 1).min(LATTICE - 1), (y0 + 1).min(LATTICE - 1));
    let (tx, ty) = (fx - fx.floor(), fy - fy.floor());
    let at = |gx: usize, gy: usize| lattice.get(gy * LATTICE + gx).copied().unwrap_or([0.0; 3]);
    std::array::from_fn(|c| {
        let top = at(x0, y0)[c] + (at(x1, y0)[c] - at(x0, y0)[c]) * tx;
        let bottom = at(x0, y1)[c] + (at(x1, y1)[c] - at(x0, y1)[c]) * tx;
        (top + (bottom - top) * ty).round().clamp(0.0, 255.0) as u8
    })
}

/// Draws the photo region: a rectangle filled with smooth noise, random values on a coarse grid
/// interpolated bilinearly. Its pixels are class `Photo`.
fn draw_photo_region(canvas: &mut Canvas, rng: &mut Rng) {
    let (w, h) = (canvas.width, canvas.height);
    let rect = Rect { x0: share(w, PHOTO_LEFT), y0: share(h, PHOTO_TOP), x1: share(w, PHOTO_RIGHT), y1: share(h, PHOTO_BOTTOM) };
    let lattice: Vec<[f64; 3]> = (0..LATTICE * LATTICE)
        .map(|_| std::array::from_fn(|_| rng.range(PHOTO_VALUE_MIN, PHOTO_VALUE_MAX)))
        .collect();
    let span_x = (rect.x1.saturating_sub(rect.x0)).max(2) as f64 - 1.0;
    let span_y = (rect.y1.saturating_sub(rect.y0)).max(2) as f64 - 1.0;
    let grid = (LATTICE - 1) as f64;
    for y in rect.y0..rect.y1 {
        for x in rect.x0..rect.x1 {
            let fx = (x - rect.x0) as f64 / span_x * grid;
            let fy = (y - rect.y0) as f64 / span_y * grid;
            let colour = lattice_value(&lattice, fx, fy);
            canvas.paint(x, y, Ink { class: Class::Photo, colour });
        }
    }
}

/// Paints every pixel of the bounding box of a disc of radius `reach` about `centre` for which
/// `inside`, given the offset from the centre, is true.
fn paint_shape(canvas: &mut Canvas, centre: [f64; 2], reach: f64, inside: impl Fn(f64, f64) -> bool, ink: Ink) {
    let x0 = (centre[0] - reach).floor().max(0.0) as usize;
    let x1 = ((centre[0] + reach).ceil().max(0.0) as usize).min(canvas.width);
    let y0 = (centre[1] - reach).floor().max(0.0) as usize;
    let y1 = ((centre[1] + reach).ceil().max(0.0) as usize).min(canvas.height);
    for y in y0..y1 {
        for x in x0..x1 {
            let (dx, dy) = (x as f64 + 0.5 - centre[0], y as f64 + 0.5 - centre[1]);
            if inside(dx, dy) {
                canvas.paint(x, y, ink);
            }
        }
    }
}

/// Draws the red signature: one wave of a curved stroke, drawn as overlapping dots.
fn draw_signature(canvas: &mut Canvas, ink: Ink) {
    let (w, h) = (canvas.width, canvas.height);
    let (x0, x1) = (share(w, SIGNATURE_X0), share(w, SIGNATURE_X1));
    let base = share(h, SIGNATURE_BASE) as f64;
    let wave = h as f64 * SIGNATURE_WAVE;
    let radius = (w as f64 / 200.0).max(1.0);
    let steps = x1.saturating_sub(x0).max(1) * 2;
    for k in 0..=steps {
        let t = k as f64 / steps as f64;
        let x = x0 as f64 + (x1 - x0) as f64 * t;
        let y = base - wave * (std::f64::consts::TAU * t).sin();
        paint_shape(canvas, [x, y], radius, |dx, dy| dx * dx + dy * dy <= radius * radius, ink);
    }
}

/// Draws the stamp: a blue ring and two blue bars inside it, all of class `Stamp`.
fn draw_stamp(canvas: &mut Canvas) {
    let short = canvas.width.min(canvas.height) as f64;
    let centre = [canvas.width as f64 * STAMP_X, canvas.height as f64 * STAMP_Y];
    let radius = short * STAMP_RADIUS;
    let thick = (radius / STAMP_RING_DIVISOR).max(2.0);
    let ink = Ink { class: Class::Stamp, colour: STAMP_BLUE };
    paint_shape(canvas, centre, radius, |dx, dy| (radius - thick..=radius).contains(&dx.hypot(dy)), ink);
    for side in [-1.0, 1.0] {
        let bar_y = side * radius * STAMP_BAR_SHARE;
        let inside_bar = |dx: f64, dy: f64| dx.hypot(dy) <= radius - 2.0 * thick && (dy - bar_y).abs() <= thick / 2.0;
        paint_shape(canvas, centre, radius, inside_bar, ink);
    }
}

/// Draws the page for `seed` and `options`. The same seed and options always give the same page.
/// The text is laid out from the top margin down: a paragraph, the pencil paragraph if asked, a
/// paragraph; then the table, photo region, signature and stamp in that order, so each later one
/// covers an earlier one where they meet.
pub fn page(seed: u64, options: &PageOptions) -> Page {
    let mut rng = Rng::new(seed);
    let (w, h) = (clamp_side(options.width), clamp_side(options.height));
    let m = Metrics::new(w, h);
    let mut canvas = Canvas::new(w, h, options.tint);
    let text = Ink { class: Class::Ink, colour: INK };
    let mut y = random_paragraph(&mut canvas, &mut rng, m, m.margin, text);
    if options.pencil {
        let pencil = Ink { class: Class::Ink, colour: PENCIL };
        y = random_paragraph(&mut canvas, &mut rng, m, y + m.line, pencil);
    }
    random_paragraph(&mut canvas, &mut rng, m, y + m.line, text);
    if options.table {
        draw_table(&mut canvas, m, text);
    }
    if options.photo_region {
        draw_photo_region(&mut canvas, &mut rng);
    }
    if options.signature {
        draw_signature(&mut canvas, Ink { class: Class::Ink, colour: SIGNATURE_RED });
    }
    if options.stamp {
        draw_stamp(&mut canvas);
    }
    canvas.into_page()
}

use crate::geom::{Homography, Point};
use crate::{Photo, Quad, ScanError};

/// Focal length as a share of the frame's longer side: a normal lens, neither wide nor tele.
const FOCAL_SHARE: f64 = 0.72;
/// Steps of the bisection that finds the depth of the page. Each halves the bracket, so 200 is
/// far finer than a pixel.
const BISECT_STEPS: usize = 200;
/// How far the camera may lean away from straight above, in degrees (the brief: 0 to 45).
const TILT_MAX_DEG: f32 = 45.0;
/// How far the page may be turned in its own plane, in degrees either way (the brief: plus or minus 20).
const TURN_MAX_DEG: f32 = 20.0;
/// The page's share of the frame's area (the brief: 0.30 to 0.85).
const SHARE_MIN: f32 = 0.30;
/// See `SHARE_MIN`.
const SHARE_MAX: f32 = 0.85;
/// Brightest over darkest light across the frame (the brief: 1.0 to 2.5).
const LIGHT_MIN: f32 = 1.0;
/// See `LIGHT_MIN`.
const LIGHT_MAX: f32 = 2.5;
/// Lowest and highest multiplier of the colour cast on any channel. The brief names no range, so
/// this one is chosen here: a cast from half to one and a half.
const CAST_MIN: f32 = 0.5;
/// See `CAST_MIN`.
const CAST_MAX: f32 = 1.5;
/// Largest Gaussian blur sigma, in photo pixels.
const BLUR_MAX: f32 = 8.0;
/// Largest noise sigma, in grey levels per channel.
const NOISE_MAX: f32 = 40.0;

/// What the frame shows behind the page.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Background {
    /// A flat dark grey, like a dark table.
    Dark,
    /// A flat light grey.
    Light,
    /// Brown wood grain: a sine of the diagonal position.
    Wood,
    /// A checkerboard of two greys.
    Checker,
    /// Random grey per pixel.
    Noise,
}

/// How the scene is taken: the frame, the page's pose and the light, then the camera's blur and noise.
#[derive(Clone, Debug)]
pub struct SceneOptions {
    /// Frame width in pixels, clamped to 16 to 1600.
    pub width: u32,
    /// Frame height in pixels, clamped to 16 to 1600.
    pub height: u32,
    /// What lies behind the page.
    pub background: Background,
    /// How far the camera leans away from straight above, 0 to 45 degrees.
    pub tilt_deg: f32,
    /// The page's turn in its own plane, plus or minus 20 degrees.
    pub rotation_deg: f32,
    /// The page's share of the frame's area, 0.30 to 0.85.
    pub page_share: f32,
    /// Brightest over darkest light across the frame, 1.0 (even) to 2.5, as a straight ramp.
    pub light_ratio: f32,
    /// Whether a soft shadow blob also falls across the frame.
    pub shadow: bool,
    /// Multiplier of the red, green and blue channels after the light.
    pub cast: [f32; 3],
    /// Sigma of the Gaussian blur, in photo pixels.
    pub blur_sigma: f32,
    /// Sigma of the additive Gaussian noise, in grey levels per channel.
    pub noise_sigma: f32,
}

/// A drawn photo and the answers that go with it.
#[derive(Clone, Debug, PartialEq)]
pub struct Scene {
    /// The photo, RGB8, row-major.
    pub photo: Photo,
    /// The page's four corners in photo pixels: top left, top right, bottom right, bottom left.
    pub quad: Quad,
    /// The light each photo pixel got, 1.0 being full, row by row. It is the product of the ramp and
    /// the shadow, and it does not include the colour cast.
    pub illumination: Vec<f32>,
    /// The page's width over its height (the page raster's own ratio).
    pub ratio: f64,
    /// The camera's focal length in photo pixels.
    pub focal_px: f64,
}

/// Scene options after clamping, in the units the geometry uses (radians for angles).
#[derive(Clone, Copy, Debug)]
pub(crate) struct Settings {
    pub(crate) width: usize,
    pub(crate) height: usize,
    pub(crate) tilt: f64,
    pub(crate) turn: f64,
    pub(crate) share: f64,
    pub(crate) light: f64,
    pub(crate) shadow: bool,
    pub(crate) cast: [f32; 3],
    pub(crate) blur: f32,
    pub(crate) noise: f32,
}

/// Clamps `v` to `lo..=hi`. A value that is not finite counts as zero, so NaN gives a sane setting
/// rather than a panic.
fn clamped(v: f32, lo: f32, hi: f32) -> f32 {
    (if v.is_finite() { v } else { 0.0 }).clamp(lo, hi)
}

impl Settings {
    /// The clamped settings for `o`.
    pub(crate) fn new(o: &SceneOptions) -> Self {
        Settings {
            width: clamp_side(o.width),
            height: clamp_side(o.height),
            tilt: f64::from(clamped(o.tilt_deg, 0.0, TILT_MAX_DEG)).to_radians(),
            turn: f64::from(clamped(o.rotation_deg, -TURN_MAX_DEG, TURN_MAX_DEG)).to_radians(),
            share: f64::from(clamped(o.page_share, SHARE_MIN, SHARE_MAX)),
            light: f64::from(clamped(o.light_ratio, LIGHT_MIN, LIGHT_MAX)),
            shadow: o.shadow,
            cast: o.cast.map(|c| clamped(c, CAST_MIN, CAST_MAX)),
            blur: clamped(o.blur_sigma, 0.0, BLUR_MAX),
            noise: clamped(o.noise_sigma, 0.0, NOISE_MAX),
        }
    }
}

/// The pinhole camera: focal length and principal point (the frame centre), in photo pixels.
#[derive(Clone, Copy, Debug)]
struct Camera {
    focal: f64,
    cx: f64,
    cy: f64,
}

/// The page's turn in its plane and its lean, each as (cosine, sine).
#[derive(Clone, Copy, Debug)]
struct Pose {
    turn: (f64, f64),
    lean: (f64, f64),
}

impl Pose {
    fn new(turn: f64, lean: f64) -> Self {
        Pose { turn: (turn.cos(), turn.sin()), lean: (lean.cos(), lean.sin()) }
    }
}

/// Everything the depth search needs about one page, the camera and the frame.
struct Setup {
    corners: [[f64; 3]; 4],
    pose: Pose,
    cam: Camera,
    frame_area: f64,
    /// The smallest depth at which every corner is in front of the camera.
    near: f64,
    /// A depth at which the page is far too small to cover any share the brief allows.
    far: f64,
}

impl Setup {
    fn new(pw: f64, ph: f64, pose: Pose, cam: Camera, frame_area: f64) -> Self {
        // The page's corners, centred on its middle: top left, top right, bottom right, bottom left.
        let (hw, hh) = (pw / 2.0, ph / 2.0);
        let corners = [[-hw, -hh, 0.0], [hw, -hh, 0.0], [hw, hh, 0.0], [-hw, hh, 0.0]];
        let radius = hw.hypot(hh);
        let near = radius * pose.lean.1.abs() * (1.0 + 1e-9) + 1e-9;
        let far = cam.focal * pw.max(ph) * 1000.0 + near;
        Setup { corners, pose, cam, frame_area, near, far }
    }
}

/// The corner in camera space: turned in its plane, leaned about the horizontal axis, then pushed
/// `depth` away from the camera. The page starts flat in the plane z = 0 (its turn and lean are
/// applied about its centre), so a corner's depth before the push is its lean-offset.
fn place(corner: [f64; 3], pose: Pose, depth: f64) -> [f64; 3] {
    let [x, y, _] = corner;
    let (x1, y1) = (x * pose.turn.0 - y * pose.turn.1, x * pose.turn.1 + y * pose.turn.0);
    let (y2, z2) = (y1 * pose.lean.0, y1 * pose.lean.1);
    [x1, y2, z2 + depth]
}

/// The pixel where a camera-space point lands, or `None` when it is not in front of the camera.
fn project(p: [f64; 3], cam: Camera) -> Option<Point> {
    (p[2] > 0.0).then(|| [cam.cx + cam.focal * p[0] / p[2], cam.cy + cam.focal * p[1] / p[2]])
}

/// The quadrilateral the page makes at `depth`, or `None` when a corner is behind the camera.
fn quad_at(setup: &Setup, depth: f64) -> Option<Quad> {
    let mut points = [[0.0; 2]; 4];
    for (out, corner) in points.iter_mut().zip(setup.corners.iter()) {
        *out = project(place(*corner, setup.pose, depth), setup.cam)?;
    }
    Some(Quad(points))
}

/// The page's quad at the depth where it covers `share` of the frame. The area falls as the page
/// moves away, so bisection finds the depth; `None` only if no depth fits.
fn fit_quad(setup: &Setup, share: f64) -> Option<Quad> {
    let (mut lo, mut hi) = (setup.near, setup.far);
    for _ in 0..BISECT_STEPS {
        let mid = 0.5 * (lo + hi);
        let too_big = match quad_at(setup, mid) {
            Some(q) => q.area() / setup.frame_area > share,
            None => true,
        };
        if too_big {
            lo = mid;
        } else {
            hi = mid;
        }
    }
    quad_at(setup, hi)
}

/// Where the page sits in the frame: the quad (photo pixels, top left first), the page's ratio and
/// the camera's focal length. `Err(BadCorners)` when any corner falls outside the frame, which is
/// the brief's "the page does not fit in the frame". `Err(Encode)` when the page's own pixel data
/// does not match its size, since no kind of error in `ScanError` is closer to that.
pub(crate) fn locate(page: &Page, s: &Settings) -> Result<Located, ScanError> {
    let (w, h) = (page.clean.width as usize, page.clean.height as usize);
    if w == 0 || h == 0 || page.clean.data.len() != w * h * 3 {
        return Err(ScanError::Encode);
    }
    let focal = FOCAL_SHARE * s.width.max(s.height) as f64;
    let cam = Camera { focal, cx: s.width as f64 / 2.0, cy: s.height as f64 / 2.0 };
    let (pw, ph) = (w as f64, h as f64);
    let setup = Setup::new(pw, ph, Pose::new(s.turn, s.tilt), cam, s.width as f64 * s.height as f64);
    let quad = fit_quad(&setup, s.share).ok_or(ScanError::BadCorners)?;
    let (fw, fh) = (s.width as f64, s.height as f64);
    let inside = quad.0.iter().all(|p| (0.0..=fw).contains(&p[0]) && (0.0..=fh).contains(&p[1]));
    if !inside {
        return Err(ScanError::BadCorners);
    }
    Ok(Located { quad, ratio: pw / ph, focal })
}

/// The result of `locate`.
#[derive(Clone, Copy, Debug)]
pub(crate) struct Located {
    pub(crate) quad: Quad,
    pub(crate) ratio: f64,
    pub(crate) focal: f64,
}

/// Flat dark grey behind the page (`Background::Dark`).
const BG_DARK: [f32; 3] = [40.0, 40.0, 44.0];
/// Flat light grey behind the page (`Background::Light`).
const BG_LIGHT: [f32; 3] = [205.0, 205.0, 200.0];
/// Mean colour of the wood (`Background::Wood`); the grain modulates it by ten percent.
const WOOD_BASE: [f32; 3] = [150.0, 100.0, 60.0];
/// Photo pixels across one grain period of the wood.
const WOOD_PERIOD: f32 = 23.0;
/// The two greys of the checkerboard (`Background::Checker`).
const CHECK_DARK: [f32; 3] = [90.0, 90.0, 90.0];
/// See `CHECK_DARK`.
const CHECK_LIGHT: [f32; 3] = [200.0, 200.0, 200.0];
/// Checker squares across the longer side of the frame.
const CHECK_CELLS: usize = 12;
/// The random grey of `Background::Noise` runs from this to `NOISE_HIGH`.
const NOISE_LOW: f64 = 60.0;
/// See `NOISE_LOW`.
const NOISE_HIGH: f64 = 200.0;
/// Mixed into the scene seed for the background's random values, so that it has its own stream.
const SALT_BACKGROUND: u64 = 0x5EED_BACC_0000_0001;

/// The value of an RGB8 raster at pixel (`ix`, `iy`), zero outside the data.
fn pixel(data: &[u8], width: usize, ix: usize, iy: usize) -> [f32; 3] {
    let i = 3 * (iy * width + ix);
    std::array::from_fn(|c| f32::from(data.get(i + c).copied().unwrap_or(0)))
}

/// The colour of an RGB8 raster at the continuous position `q`, where pixel (i, j) covers
/// `i..i+1` by `j..j+1` and so is centred on `(i + 0.5, j + 0.5)`. Bilinear, with the edge pixels
/// repeated beyond the raster. A raster with no pixels gives black.
pub(crate) fn sample(data: &[u8], width: usize, height: usize, q: Point) -> [f32; 3] {
    if width == 0 || height == 0 {
        return [0.0; 3];
    }
    let (x, y) = (q[0] - 0.5, q[1] - 0.5);
    let (x0, y0) = (x.floor(), y.floor());
    let (fx, fy) = ((x - x0) as f32, (y - y0) as f32);
    let clamp_index = |v: f64, n: usize| (v.max(0.0) as usize).min(n - 1);
    let (ix0, ix1) = (clamp_index(x0, width), clamp_index(x0 + 1.0, width));
    let (iy0, iy1) = (clamp_index(y0, height), clamp_index(y0 + 1.0, height));
    let top_left = pixel(data, width, ix0, iy0);
    let top_right = pixel(data, width, ix1, iy0);
    let bottom_left = pixel(data, width, ix0, iy1);
    let bottom_right = pixel(data, width, ix1, iy1);
    std::array::from_fn(|c| {
        let top = top_left[c] + (top_right[c] - top_left[c]) * fx;
        let bottom = bottom_left[c] + (bottom_right[c] - bottom_left[c]) * fx;
        top + (bottom - top) * fy
    })
}

/// The colour of the background at pixel (`x`, `y`). The random background draws from `rng`.
fn background_at(kind: Background, x: usize, y: usize, cell: usize, rng: &mut Rng) -> [f32; 3] {
    match kind {
        Background::Dark => BG_DARK,
        Background::Light => BG_LIGHT,
        Background::Wood => {
            let phase = (x as f32 + 0.3 * y as f32) * std::f32::consts::TAU / WOOD_PERIOD;
            let grain = 0.9 + 0.1 * phase.sin();
            WOOD_BASE.map(|c| c * grain)
        }
        Background::Checker => {
            if (x / cell + y / cell) % 2 == 0 {
                CHECK_DARK
            } else {
                CHECK_LIGHT
            }
        }
        Background::Noise => {
            let grey = (NOISE_LOW + (NOISE_HIGH - NOISE_LOW) * rng.unit()) as f32;
            [grey; 3]
        }
    }
}

/// The background for a `width` by `height` frame, one colour per pixel, row by row.
fn background_plane(kind: Background, width: usize, height: usize, seed: u64) -> Vec<[f32; 3]> {
    let mut rng = Rng::new(seed ^ SALT_BACKGROUND);
    let cell = (width.max(height) / CHECK_CELLS).max(4);
    (0..width * height)
        .map(|i| background_at(kind, i % width, i / width, cell, &mut rng))
        .collect()
}

/// The photo before light, blur and noise: each photo pixel is mapped back into the page's
/// rectangle with the homography from the quad to that rectangle, and sampled there if it lands
/// on the page; the background is used elsewhere. `None` if the quad admits no homography.
fn draw_photo(page: &Rgb, quad: &Quad, frame: (usize, usize), background: &[[f32; 3]]) -> Option<Vec<[f32; 3]>> {
    let (pw, ph) = (f64::from(page.width), f64::from(page.height));
    let rect = [[0.0, 0.0], [pw, 0.0], [pw, ph], [0.0, ph]];
    let to_page = Homography::from_points(quad.0, rect)?;
    let (width, height) = (page.width as usize, page.height as usize);
    let (w, h) = frame;
    let pixels = (0..w * h)
        .map(|i| {
            let centre = [(i % w) as f64 + 0.5, (i / w) as f64 + 0.5];
            match to_page.apply(centre) {
                Some(q) if (0.0..=pw).contains(&q[0]) && (0.0..=ph).contains(&q[1]) => {
                    sample(&page.data, width, height, q)
                }
                _ => background.get(i).copied().unwrap_or([0.0; 3]),
            }
        })
        .collect();
    Some(pixels)
}

/// Rounds and clamps each channel to a byte, row by row.
fn to_bytes(pixels: &[[f32; 3]]) -> Vec<u8> {
    pixels.iter().flatten().map(|&v| v.round().clamp(0.0, 255.0) as u8).collect()
}

/// Draws the scene for `seed` and `page` as `options` say. Pixels are computed in `f32` and rounded
/// to bytes once, at the end. `Err` when the page does not fit in the frame (`BadCorners`) or the
/// page's pixels do not match its size (`Encode`).
///
/// Left out on purpose: the JPEG round trip and the curled page. Those are the scanner's own
/// concerns and are not part of this generator.
pub fn scene(seed: u64, page: &Page, options: &SceneOptions) -> Result<Scene, ScanError> {
    let s = Settings::new(options);
    let located = locate(page, &s)?;
    let (w, h) = (s.width, s.height);
    let background = background_plane(options.background, w, h, seed);
    let pixels = draw_photo(&page.clean, &located.quad, (w, h), &background).ok_or(ScanError::BadCorners)?;
    let illumination = vec![1.0; w * h];
    Ok(Scene {
        photo: Photo { width: w as u32, height: h as u32, rgb: to_bytes(&pixels) },
        quad: located.quad,
        illumination,
        ratio: located.ratio,
        focal_px: located.focal,
    })
}
