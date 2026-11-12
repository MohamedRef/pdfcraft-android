//! Synthetic scan scenes with exact ground truth (#288): a page drawn from primitives, placed in
//! a photo by a real pinhole camera (so the true focal length and proportions are known), lit
//! unevenly, blurred, noised and JPEG-compressed. Deterministic: the same seed gives the same
//! bytes (a seeded xorshift, no `rand`).
//!
//! Compiled for the crate's tests and behind the `synth` feature, never in the app. For the
//! scan-eval runner and the CI gate (#288); `docs/ANDROID_SCAN_MEASURING.md` describes it.

use image::ImageEncoder;
use pdfcraft_geom::perspective::Homography;

/// What a page pixel is, in [`Scene::labels`].
pub mod label {
    pub const PAPER: u8 = 0;
    pub const INK: u8 = 1;
    pub const STAMP: u8 = 2;
    pub const PICTURE: u8 = 3;
    pub const SIGNATURE: u8 = 4;
    pub const PENCIL: u8 = 5;
}

/// The kind of scene, as the dataset's `kind` field names it.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum Kind {
    Desk,
    Light,
    Receipt,
    Book,
    Shadow,
    Lowlight,
    Whiteboard,
    Screen,
    Other,
}

impl Kind {
    pub const ALL: [Kind; 9] =
        [Kind::Desk, Kind::Light, Kind::Receipt, Kind::Book, Kind::Shadow, Kind::Lowlight, Kind::Whiteboard, Kind::Screen, Kind::Other];
    /// The kinds the generator makes, in turn.
    pub const SYNTHETIC: [Kind; 6] = [Kind::Desk, Kind::Light, Kind::Receipt, Kind::Book, Kind::Shadow, Kind::Lowlight];

    pub fn name(self) -> &'static str {
        match self {
            Kind::Desk => "desk",
            Kind::Light => "light",
            Kind::Receipt => "receipt",
            Kind::Book => "book",
            Kind::Shadow => "shadow",
            Kind::Lowlight => "lowlight",
            Kind::Whiteboard => "whiteboard",
            Kind::Screen => "screen",
            Kind::Other => "other",
        }
    }

    pub fn from_name(name: &str) -> Kind {
        Kind::ALL.into_iter().find(|k| k.name() == name).unwrap_or(Kind::Other)
    }
}

/// A deterministic xorshift64* generator.
#[derive(Clone, Debug)]
pub struct Rng(u64);

impl Rng {
    pub fn new(seed: u64) -> Rng {
        // splitmix64 so that nearby seeds start far apart; never zero
        let mut z = seed.wrapping_add(0x9e37_79b9_7f4a_7c15);
        z = (z ^ (z >> 30)).wrapping_mul(0xbf58_476d_1ce4_e5b9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94d0_49bb_1331_11eb);
        Rng((z ^ (z >> 31)) | 1)
    }

    pub fn next_u64(&mut self) -> u64 {
        let mut x = self.0;
        x ^= x >> 12;
        x ^= x << 25;
        x ^= x >> 27;
        self.0 = x;
        x.wrapping_mul(0x2545_f491_4f6c_dd1d)
    }

    /// Uniform in [0, 1).
    pub fn unit(&mut self) -> f64 {
        (self.next_u64() >> 11) as f64 / (1u64 << 53) as f64
    }

    /// Uniform in [lo, hi).
    pub fn range(&mut self, lo: f64, hi: f64) -> f64 {
        lo + (hi - lo) * self.unit()
    }

    /// Roughly standard normal (the sum of four uniforms, scaled).
    pub fn normal(&mut self) -> f64 {
        ((0..4).map(|_| self.unit()).sum::<f64>() - 2.0) * 3.0f64.sqrt()
    }

    pub fn chance(&mut self, p: f64) -> bool {
        self.unit() < p
    }
}

/// What to generate.
#[derive(Clone, Copy, Debug)]
pub struct SceneSpec {
    pub seed: u64,
    pub kind: Kind,
    pub width: u32,
    pub height: u32,
    /// The same scene with even light, no colour cast, no blur and no noise (JPEG quality 97):
    /// the page and where it lies are those of the full scene with this seed, so a test can tell
    /// a fault of the geometry from one of the light.
    pub plain: bool,
}

impl SceneSpec {
    /// A full scene of `kind`, `width` × `height`.
    pub fn new(seed: u64, kind: Kind, width: u32, height: u32) -> SceneSpec {
        SceneSpec { seed, kind, width, height, plain: false }
    }
}

/// A generated photo and its ground truth.
#[derive(Clone, Debug)]
pub struct Scene {
    pub kind: Kind,
    /// The camera file (JPEG).
    pub jpeg: Vec<u8>,
    pub width: u32,
    pub height: u32,
    /// The page's corners in photo pixels, sub-pixel: top-left, top-right, bottom-right,
    /// bottom-left of the page.
    pub quad: [[f64; 2]; 4],
    /// The clean page (RGB8) and its size in page pixels.
    pub page: Vec<u8>,
    pub page_width: u32,
    pub page_height: u32,
    /// One [`label`] per page pixel.
    pub labels: Vec<u8>,
    /// The page's true width-to-height ratio.
    pub aspect: f64,
    /// The camera's focal length in photo pixels.
    pub focal: f64,
    /// The light falling on each photo pixel (1 = full), before the colour cast.
    pub illumination: Vec<f32>,
    /// Amplitude of the page's bow, in page pixels (0 for a flat page): flat page point (x, y)
    /// shows where the flat page's (x, y + curl × sin(π x / width)) would.
    pub curl: f64,
    /// `paper` as the dataset's `paper` field names it.
    pub paper: &'static str,
}

impl Scene {
    /// The true corners as shares (0–1) of the photo.
    pub fn shares(&self) -> [f32; 8] {
        let (w, h) = (f64::from(self.width), f64::from(self.height));
        let mut out = [0.0f32; 8];
        for (i, [x, y]) in self.quad.iter().enumerate() {
            out[2 * i] = (x / w) as f32;
            out[2 * i + 1] = (y / h) as f32;
        }
        out
    }
}

/// Generate one scene.
pub fn generate(spec: SceneSpec) -> Scene {
    let mut rng = Rng::new(spec.seed.wrapping_mul(31).wrapping_add(spec.kind as u64));
    let (w, h) = (spec.width.max(64), spec.height.max(64));
    let (paper, aspect) = match spec.kind {
        Kind::Receipt => ("receipt", 1.0 / rng.range(2.4, 3.4)),
        _ if rng.chance(0.7) => ("a4", 210.0 / 297.0),
        _ => ("letter", 8.5 / 11.0),
    };
    let camera = place(&mut rng, spec.kind, w, h, aspect);
    // The page drawn at about 1.4 page pixels per photo pixel of its longest side.
    let long = side_lengths(&camera.quad).into_iter().fold(0.0f64, f64::max);
    let ph = (long * 1.4).clamp(200.0, 4000.0).round() as u32;
    let pw = ((f64::from(ph) * aspect).round() as u32).max(40);
    let (page, labels) = draw_page(&mut rng, spec.kind, pw, ph);
    let curl = if spec.kind == Kind::Book { rng.range(0.01, 0.04) * f64::from(ph) } else { 0.0 };

    let (bg, mut illumination, mut cast) = scene_light(&mut rng, spec.kind, w, h);
    if spec.plain {
        illumination.fill(1.0);
        cast = [1.0; 3];
    }
    let rect = [[0.0, 0.0], [f64::from(pw), 0.0], [f64::from(pw), f64::from(ph)], [0.0, f64::from(ph)]];
    let mut rgb = render(&Render { page: &page, pw, ph, curl, bg: &bg, w, h, quad: camera.quad, rect });
    for (i, px) in rgb.as_chunks_mut::<3>().0.iter_mut().enumerate() {
        let l = f64::from(illumination[i]);
        for (c, v) in px.iter_mut().enumerate() {
            *v = (f64::from(*v) * l * cast[c]).round().clamp(0.0, 255.0) as u8;
        }
    }
    let sigma = match spec.kind {
        Kind::Lowlight => rng.range(0.8, 2.5),
        _ => rng.range(0.0, 1.4),
    };
    let noise = match spec.kind {
        Kind::Lowlight => rng.range(6.0, 12.0),
        _ => rng.range(0.0, 5.0),
    };
    let quality = rng.range(85.0, 97.0).round() as u8;
    // The random draws above are made in plain scenes too, so both keep the same page and place.
    let (sigma, noise, quality) = if spec.plain { (0.0, 0.0, 97) } else { (sigma, noise, quality) };
    blur(&mut rgb, w, h, sigma);
    if noise > 0.0 {
        for v in &mut rgb {
            *v = (f64::from(*v) + rng.normal() * noise).round().clamp(0.0, 255.0) as u8;
        }
    }
    let mut jpeg = Vec::new();
    // In memory, with a size that matches the buffer: cannot fail.
    let _ = image::codecs::jpeg::JpegEncoder::new_with_quality(&mut jpeg, quality).write_image(&rgb, w, h, image::ExtendedColorType::Rgb8);
    Scene {
        kind: spec.kind,
        jpeg,
        width: w,
        height: h,
        quad: camera.quad,
        page,
        page_width: pw,
        page_height: ph,
        labels,
        aspect,
        focal: camera.focal,
        illumination,
        curl,
        paper,
    }
}

struct Camera {
    quad: [[f64; 2]; 4],
    focal: f64,
}

fn side_lengths(q: &[[f64; 2]; 4]) -> [f64; 4] {
    std::array::from_fn(|i| {
        let (a, b) = (q[i], q[(i + 1) % 4]);
        (a[0] - b[0]).hypot(a[1] - b[1])
    })
}

fn area(q: &[[f64; 2]; 4]) -> f64 {
    (0..4).map(|i| q[i][0] * q[(i + 1) % 4][1] - q[(i + 1) % 4][0] * q[i][1]).sum::<f64>().abs() / 2.0
}

/// Project a page of proportions `aspect` (width / height) with a pinhole camera: tilted up to
/// 45°, turned up to ±20°, covering 30–85% of the frame, wholly inside it.
fn place(rng: &mut Rng, kind: Kind, w: u32, h: u32, aspect: f64) -> Camera {
    let (fw, fh) = (f64::from(w), f64::from(h));
    let focal = fw.max(fh) * rng.range(0.75, 1.15);
    let max_tilt: f64 = if kind == Kind::Desk || kind == Kind::Light { 45.0 } else { 30.0 };
    let tilt = rng.range(0.0, max_tilt).to_radians();
    let axis = rng.range(0.0, std::f64::consts::TAU);
    let roll = rng.range(-20.0, 20.0).to_radians();
    let mut target = rng.range(0.30, 0.85);
    // A page 1 unit high; its 3D corners turned by roll, then tilted about `axis`.
    let (pw, ph) = (aspect, 1.0);
    let corners3 = [[-pw / 2.0, -ph / 2.0], [pw / 2.0, -ph / 2.0], [pw / 2.0, ph / 2.0], [-pw / 2.0, ph / 2.0]].map(|[x, y]| {
        let (x, y) = (x * roll.cos() - y * roll.sin(), x * roll.sin() + y * roll.cos());
        // Rodrigues about the in-plane axis (cos a, sin a, 0)
        let (ux, uy) = (axis.cos(), axis.sin());
        let (c, s) = (tilt.cos(), tilt.sin());
        let dot = ux * x + uy * y;
        // v cos t + (u × v) sin t + u (u · v)(1 − cos t), with v = (x, y, 0)
        let cross = [uy * 0.0 - 0.0 * y, 0.0 * x - ux * 0.0, ux * y - uy * x];
        [x * c + cross[0] * s + ux * dot * (1.0 - c), y * c + cross[1] * s + uy * dot * (1.0 - c), cross[2] * s]
    });
    for _ in 0..12 {
        let project =
            |z: f64, tx: f64, ty: f64| corners3.map(|[x, y, d]| [focal * (x + tx) / (z + d) + fw / 2.0, focal * (y + ty) / (z + d) + fh / 2.0]);
        // Distance that gives the target area (area scales with 1/z²), refined twice.
        let mut z = 3.0;
        for _ in 0..3 {
            let a = area(&project(z, 0.0, 0.0)) / (fw * fh);
            z *= (a / target).sqrt();
        }
        let q = project(z, 0.0, 0.0);
        let margin = 0.02 * fw.min(fh);
        let (minx, maxx) = q.iter().fold((f64::MAX, f64::MIN), |(lo, hi), p| (lo.min(p[0]), hi.max(p[0])));
        let (miny, maxy) = q.iter().fold((f64::MAX, f64::MIN), |(lo, hi), p| (lo.min(p[1]), hi.max(p[1])));
        let (slack_x, slack_y) = ((fw - 2.0 * margin) - (maxx - minx), (fh - 2.0 * margin) - (maxy - miny));
        if slack_x <= 0.0 || slack_y <= 0.0 {
            target *= 0.85;
            continue;
        }
        // Shift in the image so the page stays inside, then turn the shift into 3D at depth z.
        let dx = margin - minx + rng.range(0.0, slack_x);
        let dy = margin - miny + rng.range(0.0, slack_y);
        let quad = project(z, dx * z / focal, dy * z / focal);
        let inside = quad.iter().all(|p| p[0] >= 1.0 && p[1] >= 1.0 && p[0] <= fw - 1.0 && p[1] <= fh - 1.0);
        if inside {
            return Camera { quad, focal };
        }
        target *= 0.9;
    }
    // Frontal, centred, half the frame: always fits.
    let (qh, qw) = (fh * 0.6, fh * 0.6 * aspect);
    let (x0, y0) = ((fw - qw) / 2.0, (fh - qh) / 2.0);
    Camera { quad: [[x0, y0], [x0 + qw, y0], [x0 + qw, y0 + qh], [x0, y0 + qh]], focal }
}

/// Draw a page: paragraphs of word bars, a ruled table, a stamp, a signature, and sometimes a
/// picture and faint pencil writing.
fn draw_page(rng: &mut Rng, kind: Kind, w: u32, h: u32) -> (Vec<u8>, Vec<u8>) {
    let tint = if rng.chance(0.3) { [rng.range(236.0, 250.0), rng.range(234.0, 248.0), rng.range(220.0, 240.0)] } else { [250.0, 250.0, 248.0] };
    let paper = tint.map(|v| v as u8);
    let (wu, hu) = (w as usize, h as usize);
    let mut canvas = Canvas { rgb: std::iter::repeat_n(paper, wu * hu).flatten().collect(), labels: vec![label::PAPER; wu * hu], w: wu, h: hu };
    let fill = |c: &mut Canvas, x0: f64, y0: f64, x1: f64, y1: f64, colour: [u8; 3], tag: u8| c.fill([x0, y0, x1, y1], colour, tag);
    let (fw, fh) = (f64::from(w), f64::from(h));
    let margin = fw * 0.08;
    let line_h = (fh * if kind == Kind::Receipt { 0.012 } else { 0.016 }).max(3.0);
    let ink = [rng.range(10.0, 40.0) as u8, rng.range(10.0, 40.0) as u8, rng.range(10.0, 50.0) as u8];
    // Body text: rows of words, with a gap for the table and the picture.
    let table_top = fh * rng.range(0.45, 0.55);
    let table_bottom = table_top + fh * 0.12;
    let picture = rng.chance(0.4) && kind != Kind::Receipt;
    let mut y = fh * 0.08;
    while y + line_h < fh * 0.80 {
        if y + line_h > table_top - line_h && y < table_bottom + line_h {
            y = table_bottom + 2.0 * line_h;
            continue;
        }
        let right = if picture && y < fh * 0.35 { fw * 0.55 } else { fw - margin };
        let mut x = margin;
        while x < right {
            let word = line_h * rng.range(1.0, 5.0);
            let end = (x + word).min(right);
            fill(&mut canvas, x, y + line_h * 0.15, end, y + line_h * 0.85, ink, label::INK);
            x = end + line_h * 0.6;
        }
        y += line_h * 1.9;
    }
    // A ruled table.
    let rule = (line_h * 0.12).max(1.0);
    for k in 0..=4 {
        let ty = table_top + (table_bottom - table_top) * f64::from(k) / 4.0;
        fill(&mut canvas, margin, ty, fw - margin, ty + rule, ink, label::INK);
    }
    for k in 0..=3 {
        let tx = margin + (fw - 2.0 * margin) * f64::from(k) / 3.0;
        fill(&mut canvas, tx, table_top, tx + rule, table_bottom + rule, ink, label::INK);
    }
    // A picture region of smooth colour noise.
    if picture {
        let (x0, y0, x1, y1) = (fw * 0.60, fh * 0.08, fw - margin, fh * 0.33);
        let (a, b, c) = (rng.range(0.01, 0.05), rng.range(0.01, 0.05), rng.range(0.0, 6.0));
        for py in y0 as usize..(y1 as usize).min(hu) {
            for px in x0 as usize..(x1 as usize).min(wu) {
                let (u, v) = (px as f64, py as f64);
                let r = 128.0 + 90.0 * (u * a + c).sin() * (v * b).cos();
                let g = 110.0 + 80.0 * (v * a * 1.3).sin();
                let bl = 140.0 + 70.0 * ((u + v) * b).cos();
                canvas.set(py * wu + px, [r as u8, g as u8, bl as u8], label::PICTURE);
            }
        }
    }
    // A blue stamp (a ring) and a red signature stroke.
    let (scx, scy, sr) = (fw * rng.range(0.6, 0.8), fh * 0.88, fw * 0.07);
    let stroke = (sr * 0.12).max(1.5);
    let sig = (fh * 0.86, fw * 0.12, fw * 0.45, fh * 0.012);
    for py in (fh * 0.80) as usize..hu {
        for px in 0..wu {
            let (u, v) = (px as f64 + 0.5, py as f64 + 0.5);
            let d = (u - scx).hypot(v - scy);
            let i = py * wu + px;
            if (d - sr).abs() < stroke || (d - sr * 0.7).abs() < stroke * 0.6 {
                canvas.set(i, [40, 70, 190], label::STAMP);
            } else if u > sig.1 && u < sig.2 && (v - (sig.0 + sig.3 * 3.0 * ((u - sig.1) / (fw * 0.05)).sin())).abs() < stroke * 0.8 {
                canvas.set(i, [190, 30, 40], label::SIGNATURE);
            }
        }
    }
    // Faint pencil writing near the bottom.
    if rng.chance(0.4) {
        let grey = [175, 175, 180];
        let mut x = margin;
        let py = fh * 0.93;
        while x < fw * 0.5 {
            let word = line_h * rng.range(1.0, 4.0);
            fill(&mut canvas, x, py, x + word, py + line_h * 0.5, grey, label::PENCIL);
            x += word + line_h * 0.6;
        }
    }
    (canvas.rgb, canvas.labels)
}

/// A page being drawn: its colours and what each pixel is.
struct Canvas {
    rgb: Vec<u8>,
    labels: Vec<u8>,
    w: usize,
    h: usize,
}

impl Canvas {
    fn set(&mut self, i: usize, colour: [u8; 3], tag: u8) {
        self.rgb[i * 3..i * 3 + 3].copy_from_slice(&colour);
        self.labels[i] = tag;
    }

    /// Fill the box from (x0, y0) to (x1, y1), clipped to the page.
    fn fill(&mut self, [x0, y0, x1, y1]: [f64; 4], colour: [u8; 3], tag: u8) {
        let (xa, xb) = (x0.max(0.0) as usize, (x1.max(0.0) as usize).min(self.w));
        let (ya, yb) = (y0.max(0.0) as usize, (y1.max(0.0) as usize).min(self.h));
        for y in ya..yb {
            for x in xa..xb {
                self.set(y * self.w + x, colour, tag);
            }
        }
    }
}

/// The table under the page, the light on each photo pixel and the colour cast.
fn scene_light(rng: &mut Rng, kind: Kind, w: u32, h: u32) -> (Vec<u8>, Vec<f32>, [f64; 3]) {
    let (wu, hu) = (w as usize, h as usize);
    let style = match kind {
        Kind::Desk | Kind::Lowlight => 0,
        Kind::Light => 1 + (rng.next_u64() % 4) as u8,
        _ => (rng.next_u64() % 5) as u8,
    };
    let base = match style {
        0 => [rng.range(25.0, 80.0), rng.range(20.0, 70.0), rng.range(20.0, 65.0)],
        _ => [rng.range(170.0, 230.0), rng.range(165.0, 225.0), rng.range(150.0, 215.0)],
    };
    let (f1, f2) = (rng.range(0.02, 0.08), rng.range(0.003, 0.02));
    let mut bg = Vec::with_capacity(wu * hu * 3);
    for y in 0..hu {
        for x in 0..wu {
            let (u, v) = (x as f64, y as f64);
            let k = match style {
                // wood-like stripes
                2 => 0.85 + 0.15 * ((v * f2 + (u * f1 * 0.2).sin()) * 6.0).sin(),
                // checked cloth
                3 => {
                    if ((u * f1 * 0.5) as i64 + (v * f1 * 0.5) as i64) % 2 == 0 {
                        1.0
                    } else {
                        0.7
                    }
                }
                // noise texture
                4 => 0.8 + 0.2 * ((u * 12.9898 + v * 78.233).sin() * 43_758.545).fract().abs(),
                _ => 1.0,
            };
            bg.extend(base.map(|c| (c * k).clamp(0.0, 255.0) as u8));
        }
    }
    // Lighting: a gradient (up to 2.5:1), and for shadow scenes a hard-edged blob.
    let (fw, fh) = (f64::from(w), f64::from(h));
    let strength = match kind {
        Kind::Shadow | Kind::Lowlight => rng.range(0.25, 0.6),
        _ => rng.range(0.0, 0.3),
    };
    let radial = rng.chance(0.5);
    let dir = rng.range(0.0, std::f64::consts::TAU);
    let (cx, cy) = (rng.range(0.0, fw), rng.range(0.0, fh));
    let reach = fw.hypot(fh);
    let blob = (kind == Kind::Shadow || rng.chance(0.15)).then(|| {
        (rng.range(0.0, fw), rng.range(0.0, fh), rng.range(0.15, 0.4) * fw, rng.range(0.15, 0.4) * fh, rng.range(0.4, 0.8), rng.range(2.0, 20.0))
    });
    let dim = if kind == Kind::Lowlight { rng.range(0.45, 0.7) } else { 1.0 };
    let mut light = Vec::with_capacity(wu * hu);
    for y in 0..hu {
        for x in 0..wu {
            let (u, v) = (x as f64 + 0.5, y as f64 + 0.5);
            let t =
                if radial { (u - cx).hypot(v - cy) / reach } else { (((u - cx) * dir.cos() + (v - cy) * dir.sin()) / reach + 0.5).clamp(0.0, 1.0) };
            let mut l = 1.0 - strength * t;
            if let Some((bx, by, rx, ry, m, soft)) = blob {
                // signed distance to the ellipse's edge, roughly in pixels
                let r = ((u - bx) / rx).hypot((v - by) / ry);
                let d = (r - 1.0) * rx.min(ry);
                let k = (0.5 - d / soft).clamp(0.0, 1.0);
                let k = k * k * (3.0 - 2.0 * k);
                l *= 1.0 - (1.0 - m) * k;
            }
            light.push((l * dim) as f32);
        }
    }
    let cast = [rng.range(0.94, 1.06), 1.0, rng.range(0.9, 1.06)];
    (bg, light, cast)
}

struct Render<'a> {
    page: &'a [u8],
    pw: u32,
    ph: u32,
    curl: f64,
    bg: &'a [u8],
    w: u32,
    h: u32,
    quad: [[f64; 2]; 4],
    rect: [[f64; 2]; 4],
}

/// The photo before lighting: each pixel the mean of 2 × 2 bilinear samples of the page (or
/// the table).
fn render(r: &Render<'_>) -> Vec<u8> {
    let to_page = Homography::from_points(r.quad, r.rect);
    let (pw, ph) = (f64::from(r.pw), f64::from(r.ph));
    let px = |x: i64, y: i64| {
        let i = ((y.clamp(0, i64::from(r.ph) - 1) * i64::from(r.pw) + x.clamp(0, i64::from(r.pw) - 1)) * 3) as usize;
        [f64::from(r.page[i]), f64::from(r.page[i + 1]), f64::from(r.page[i + 2])]
    };
    let mut out = Vec::with_capacity(r.w as usize * r.h as usize * 3);
    for y in 0..r.h {
        for x in 0..r.w {
            let bi = ((y * r.w + x) * 3) as usize;
            let back = [f64::from(r.bg[bi]), f64::from(r.bg[bi + 1]), f64::from(r.bg[bi + 2])];
            let mut sum = [0.0; 3];
            for (dx, dy) in [(0.25, 0.25), (0.75, 0.25), (0.25, 0.75), (0.75, 0.75)] {
                let s = to_page.and_then(|t| t.apply([f64::from(x) + dx, f64::from(y) + dy]));
                let c = match s {
                    Some([sx, sy]) if sx >= 0.0 && sx < pw && sy >= 0.0 && sy < ph => {
                        let sy = sy - r.curl * (std::f64::consts::PI * sx / pw).sin();
                        if sy < 0.0 {
                            back
                        } else {
                            let (fx, fy) = (sx - 0.5, sy - 0.5);
                            let (x0, y0) = (fx.floor(), fy.floor());
                            let (ax, ay) = (fx - x0, fy - y0);
                            let (x0, y0) = (x0 as i64, y0 as i64);
                            let (a, b, c, d) = (px(x0, y0), px(x0 + 1, y0), px(x0, y0 + 1), px(x0 + 1, y0 + 1));
                            std::array::from_fn(|k| (a[k] * (1.0 - ax) + b[k] * ax) * (1.0 - ay) + (c[k] * (1.0 - ax) + d[k] * ax) * ay)
                        }
                    }
                    _ => back,
                };
                for k in 0..3 {
                    sum[k] += c[k] / 4.0;
                }
            }
            out.extend(sum.map(|v| v.round().clamp(0.0, 255.0) as u8));
        }
    }
    out
}

/// Gaussian blur of RGB8 in place (separable, kernel to 3σ); σ below 0.3 does nothing.
pub fn blur(rgb: &mut [u8], w: u32, h: u32, sigma: f64) {
    if sigma < 0.3 {
        return;
    }
    let r = (sigma * 3.0).ceil() as i64;
    let k: Vec<f64> = (-r..=r).map(|i| (-(i * i) as f64 / (2.0 * sigma * sigma)).exp()).collect();
    let total: f64 = k.iter().sum();
    let k: Vec<f64> = k.iter().map(|v| v / total).collect();
    let (wi, hi) = (i64::from(w), i64::from(h));
    let mut tmp = vec![0.0f64; rgb.len()];
    for y in 0..hi {
        for x in 0..wi {
            for c in 0..3 {
                let mut s = 0.0;
                for (j, kv) in k.iter().enumerate() {
                    let xx = (x + j as i64 - r).clamp(0, wi - 1);
                    s += kv * f64::from(rgb[((y * wi + xx) * 3 + c) as usize]);
                }
                tmp[((y * wi + x) * 3 + c) as usize] = s;
            }
        }
    }
    for y in 0..hi {
        for x in 0..wi {
            for c in 0..3 {
                let mut s = 0.0;
                for (j, kv) in k.iter().enumerate() {
                    let yy = (y + j as i64 - r).clamp(0, hi - 1);
                    s += kv * tmp[((yy * wi + x) * 3 + c) as usize];
                }
                rgb[((y * wi + x) * 3 + c) as usize] = s.round().clamp(0.0, 255.0) as u8;
            }
        }
    }
}
