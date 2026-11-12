//! Measure the scan pipeline on photos whose answers are known (#288, sections 3 and 4): drawn
//! ones from [`crate::synth`], or a folder of test set entries (a kept-scans export from the
//! phone is one). Shared by the `scan-eval` runner and the CI gate.
//!
//! Each photo goes through the app's own stages, in order: decode, find the page
//! ([`detect_with`]), refine its edges ([`refine`]), straighten ([`page_base`]), the look
//! ([`page_look`]) and the encoder. The measures are the blocks of `metrics_geometry`,
//! `metrics_tone`, `metrics_compare` and `straightness`; nothing here computes a measure itself.
//!
//! The clean-up measures (paper, light, ink, stamp, PSNR, SSIM) are taken on the page
//! straightened with the *true* corners, so they judge the look and not the edges. The geometry
//! measures, the proportions and the line straightness are taken on the page the app would make
//! from the corners it found.

use std::time::Instant;

use crate::aspect::FRAME_35MM_DIAGONAL;
use crate::detect::{Finder, detect_with};
use crate::encode::{JPEG_QUALITY, Raster, encode_jpeg, encode_png_bilevel};
use crate::look::{Look, Pixels};
use crate::metrics_compare::{psnr, ssim};
use crate::metrics_geometry::{aspect_err, corner_err, iou, no_touch};
use crate::metrics_tone::{hue_shift, illum_spread, ink_contrast, paper_white, sharpness};
use crate::page::{PageRequest, page_base, page_look};
use crate::refine::{Pyramid, RefineOptions, refine};
use crate::straightness::line_straightness;
use crate::synth::{Kind, Scene, label};
use crate::{Luma, Photo, Quad, Rgb, luma};

/// The look the pages are measured in: the scan screen's default, Clean scan.
pub const EVAL_LOOK: Look = Look::Auto;
/// The strength the scan screen starts at.
pub const EVAL_STRENGTH: u8 = 50;

/// A page's ground truth in page space: the clean page and a [`label`] per pixel.
#[derive(Clone, Debug)]
pub struct PageTruth {
    pub width: u32,
    pub height: u32,
    /// RGB8.
    pub clean: Vec<u8>,
    pub labels: Vec<u8>,
}

/// One photo and what is known about it.
#[derive(Clone, Debug)]
pub struct Entry {
    pub name: String,
    pub kind: Kind,
    /// The camera file.
    pub photo: Vec<u8>,
    /// The page's true corners as shares of the upright photo (TL, TR, BR, BL).
    pub corners: Option<[f32; 8]>,
    /// The page's true width-to-height ratio.
    pub aspect: Option<f64>,
    /// The camera's focal length as a 35 mm equivalent, when the photo's own file does not say
    /// it (a drawn photo has no EXIF block).
    pub focal_35mm: Option<f32>,
    pub page: Option<PageTruth>,
}

impl Entry {
    /// A drawn scene as an entry.
    pub fn from_scene(name: String, s: &Scene) -> Entry {
        let diagonal = f64::from(s.width).hypot(f64::from(s.height));
        Entry {
            name,
            kind: s.kind,
            photo: s.jpeg.clone(),
            corners: Some(s.shares()),
            aspect: Some(s.aspect),
            focal_35mm: Some((s.focal * FRAME_35MM_DIAGONAL / diagonal) as f32),
            page: Some(PageTruth { width: s.page_width, height: s.page_height, clean: s.page.clone(), labels: s.labels.clone() }),
        }
    }
}

/// The width-to-height ratio of a `paper` name of the test set format (portrait).
pub fn paper_aspect(paper: &str) -> Option<f64> {
    Some(match paper {
        "a4" => 210.0 / 297.0,
        "letter" => 8.5 / 11.0,
        "legal" => 8.5 / 14.0,
        "card" => 53.98 / 85.6,
        _ => return None,
    })
}

/// Every measure of one entry; `NAN` where it does not apply or could not be taken.
#[derive(Clone, Debug)]
pub struct Measured {
    pub name: String,
    pub kind: Kind,
    /// The detector returned a page (the whole photo is used otherwise).
    pub detected: bool,
    pub values: [f64; METRICS.len()],
    /// (stage, milliseconds), in pipeline order.
    pub timings: Vec<(&'static str, f64)>,
    /// The corners the app ended with, and the true ones, in photo pixels.
    pub found: Option<Quad>,
    pub truth: Option<Quad>,
}

impl Measured {
    pub fn get(&self, metric: &str) -> f64 {
        METRICS.iter().position(|m| m.name == metric).map_or(f64::NAN, |i| self.values[i])
    }

    fn set(&mut self, metric: &str, value: Option<f64>) {
        if let Some(i) = METRICS.iter().position(|m| m.name == metric) {
            self.values[i] = value.filter(|v| v.is_finite()).unwrap_or(f64::NAN);
        }
    }
}

/// Which way is better for a measure.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Better {
    Higher,
    Lower,
}

/// A measure the report shows, and how a regression of it is judged.
#[derive(Clone, Copy, Debug)]
pub struct Metric {
    pub name: &'static str,
    pub better: Better,
    /// How much worse the mean may get against a baseline before `scan-eval --baseline` fails.
    pub tolerance: f64,
}

const fn metric(name: &'static str, better: Better, tolerance: f64) -> Metric {
    Metric { name, better, tolerance }
}

/// The measures, in report order (#288, section 3, without `cer`: drawn pages have no letters
/// and the scan crate does not run text recognition).
pub const METRICS: [Metric; 14] = [
    metric("iou", Better::Higher, 0.01),
    metric("corner_err_mean", Better::Lower, 0.001),
    metric("corner_err_max", Better::Lower, 0.002),
    metric("no_touch", Better::Higher, 0.05),
    metric("aspect_err", Better::Lower, 0.005),
    metric("sharpness", Better::Higher, f64::INFINITY),
    metric("paper_white", Better::Higher, 0.02),
    metric("illum_spread", Better::Lower, 0.01),
    metric("ink_contrast", Better::Higher, 3.0),
    metric("hue_shift", Better::Lower, 2.0),
    metric("psnr", Better::Higher, 0.5),
    metric("ssim", Better::Higher, 0.01),
    metric("line_straightness", Better::Lower, 0.3),
    metric("bowed_px", Better::Lower, 0.3),
];

/// How a run finds and straightens pages.
#[derive(Clone, Copy, Debug)]
pub struct Options {
    pub finder: Finder,
    /// Refine the found edges on the full photo, as the app does by default.
    pub refine: bool,
    pub threads: usize,
    /// Give the page made from the found corners its look and encode it, for `sharpness` and
    /// the time of those two stages. The CI gate leaves it out to stay fast: its clean-up
    /// measures come from the page made from the true corners either way.
    pub look_found_page: bool,
}

impl Default for Options {
    fn default() -> Self {
        Options { finder: Finder::Auto, refine: true, threads: 4, look_found_page: true }
    }
}

fn grey(page: &Rgb) -> Luma {
    Luma { width: page.width, height: page.height, data: page.data.as_chunks::<3>().0.iter().map(|p| luma(*p)).collect() }
}

fn as_rgb(pixels: &Pixels, width: u32, height: u32) -> Rgb {
    let data = match pixels {
        Pixels::Rgb(v) => v.clone(),
        Pixels::Grey(g) => g.iter().flat_map(|&v| [v, v, v]).collect(),
        Pixels::Bilevel(bits) => bits.to_grey().iter().flat_map(|&v| [v, v, v]).collect(),
    };
    Rgb { width, height, data }
}

/// Nearest-neighbour resize of `v` (`w` × `h`, `channels` bytes a pixel) to `ow` × `oh`.
fn resize_nearest(v: &[u8], (w, h): (u32, u32), (ow, oh): (u32, u32), channels: usize) -> Vec<u8> {
    let mut out = Vec::with_capacity(ow as usize * oh as usize * channels);
    for y in 0..oh {
        let sy = ((u64::from(y) * 2 + 1) * u64::from(h) / (u64::from(oh) * 2)).min(u64::from(h).saturating_sub(1)) as usize;
        for x in 0..ow {
            let sx = ((u64::from(x) * 2 + 1) * u64::from(w) / (u64::from(ow) * 2)).min(u64::from(w).saturating_sub(1)) as usize;
            let i = (sy * w as usize + sx) * channels;
            out.extend_from_slice(&v[i..i + channels]);
        }
    }
    out
}

fn request<'a>(entry: &'a Entry, corners: [f32; 8], options: &Options) -> PageRequest<'a> {
    PageRequest {
        look: EVAL_LOOK,
        strength: EVAL_STRENGTH,
        threads: options.threads,
        focal_35mm: entry.focal_35mm,
        // a drawn page's word bars have no reading direction, and a test set entry's corners
        // are already as the person wants the page
        upright: false,
        ..PageRequest::new(&entry.photo, corners)
    }
}

/// Measure one entry.
pub fn measure(entry: &Entry, options: &Options) -> Measured {
    let mut m = Measured {
        name: entry.name.clone(),
        kind: entry.kind,
        detected: false,
        values: [f64::NAN; METRICS.len()],
        timings: Vec::new(),
        found: None,
        truth: None,
    };
    let mut clock = Instant::now();
    let mut lap = |stage: &'static str, m: &mut Measured| {
        m.timings.push((stage, clock.elapsed().as_secs_f64() * 1000.0));
        clock = Instant::now();
    };
    let Ok(photo) = Photo::decode(&entry.photo) else { return m };
    let (w, h) = (photo.width, photo.height);
    lap("decode", &mut m);
    let found = detect_with(&photo, options.finder);
    lap("detect", &mut m);
    m.detected = found.quad.is_some();
    let quad = match found.quad {
        Some(q) if options.refine => {
            let pyramid = Pyramid::new(photo.luma());
            let refined = refine(&pyramid, &q, &RefineOptions::default()).quad;
            lap("refine", &mut m);
            refined
        }
        Some(q) => q,
        None => match Quad::new([[0.0, 0.0], [f64::from(w), 0.0], [f64::from(w), f64::from(h)], [0.0, f64::from(h)]]) {
            Ok(q) => q,
            Err(_) => return m,
        },
    };
    drop(photo);
    m.found = Some(quad);
    m.truth = entry.corners.and_then(|c| Quad::from_shares(&c, w, h).ok());
    if let Some(truth) = m.truth {
        let err = corner_err(&quad, &truth, w, h);
        m.set("iou", Some(iou(&quad, &truth)));
        m.set("corner_err_mean", Some(err.mean));
        m.set("corner_err_max", Some(err.max));
        m.set("no_touch", Some(f64::from(u8::from(no_touch(&err)))));
    }

    // The page the app would make from the corners it found.
    let req = request(entry, quad.to_shares(w, h), options);
    let Ok(base) = page_base(&req) else { return m };
    m.timings.extend(base.timings.iter().filter(|(s, _)| *s != crate::page::STAGE_DECODE).map(|(s, ms)| (*s, f64::from(*ms))));
    if let Some(truth) = entry.aspect {
        m.set("aspect_err", Some(aspect_err(f64::from(base.page.width) / f64::from(base.page.height), truth)));
    }
    let found_grey = grey(&base.page);
    if let Some(s) = line_straightness(&found_grey) {
        m.set("line_straightness", Some(s.px));
        m.set("bowed_px", Some(s.bowed_px));
    }
    if options.look_found_page {
        look_and_encode(&mut m, &base, &req);
    }

    if let (Some(truth), Some(pt)) = (m.truth, &entry.page) {
        clean_up(&mut m, entry, truth, pt, options);
    }
    m
}

/// The found page's look and encoding, timed, and the sharpness of the result.
fn look_and_encode(m: &mut Measured, base: &crate::page::PageBase, req: &PageRequest<'_>) {
    let mut clock = Instant::now();
    let Ok(applied) = page_look(base, req) else { return };
    let (pw, ph) = (base.page.width, base.page.height);
    let looked_at = clock.elapsed().as_secs_f64() * 1000.0;
    m.timings.push((crate::page::STAGE_LOOK, looked_at));
    clock = Instant::now();
    let _ = match &applied.pixels {
        Pixels::Rgb(rgb) => encode_jpeg(&Raster { pixels: rgb, grey: false, width: pw, height: ph, dpi: base.dpi }, JPEG_QUALITY),
        Pixels::Grey(g) => encode_jpeg(&Raster { pixels: g, grey: true, width: pw, height: ph, dpi: base.dpi }, JPEG_QUALITY),
        Pixels::Bilevel(bits) => encode_png_bilevel(bits, base.dpi),
    };
    m.timings.push((crate::page::STAGE_ENCODE, clock.elapsed().as_secs_f64() * 1000.0));
    let looked = grey(&as_rgb(&applied.pixels, pw, ph));
    m.set("sharpness", sharpness(&looked, &vec![true; looked.data.len()]));
}

/// The clean-up measures on the page straightened with its true corners.
fn clean_up(m: &mut Measured, entry: &Entry, truth: Quad, pt: &PageTruth, options: &Options) {
    let Ok((pw, ph, _)) = Photo::upright_size(&entry.photo) else { return };
    let req = request(entry, truth.to_shares(pw, ph), options);
    let Ok(base) = page_base(&req) else { return };
    let Ok(applied) = page_look(&base, &req) else { return };
    let (w, h) = (base.page.width, base.page.height);
    let after = as_rgb(&applied.pixels, w, h);
    let labels = resize_nearest(&pt.labels, (pt.width, pt.height), (w, h), 1);
    let clean = Rgb { width: w, height: h, data: resize_nearest(&pt.clean, (pt.width, pt.height), (w, h), 3) };
    // A pixel within two of a different label is on an edge, where straightening blurs one into
    // the other: it counts as neither paper nor ink.
    let (wu, hu) = (w as usize, h as usize);
    let interior = |want: u8| -> Vec<bool> {
        (0..wu * hu)
            .map(|i| {
                let (x, y) = (i % wu, i / wu);
                labels[i] == want
                    && (y.saturating_sub(2)..(y + 3).min(hu)).all(|yy| (x.saturating_sub(2)..(x + 3).min(wu)).all(|xx| labels[yy * wu + xx] == want))
            })
            .collect()
    };
    let (paper, ink) = (interior(label::PAPER), interior(label::INK));
    let stamp: Vec<bool> = labels.iter().map(|&l| l == label::STAMP).collect();
    let g = grey(&after);
    m.set("paper_white", paper_white(&g, &paper));
    m.set("illum_spread", illum_spread(&g, &paper));
    m.set("ink_contrast", ink_contrast(&g, &paper, &ink));
    m.set("hue_shift", hue_shift(&clean, &after, &stamp));
    let cg = grey(&clean);
    m.set("psnr", psnr(&g, &cg));
    m.set("ssim", ssim(&g, &cg));
}

/// Median, mean and 95th percentile of one measure over some entries (`NAN`s left out), and how
/// many counted.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Summary {
    pub median: f64,
    pub mean: f64,
    pub p95: f64,
    pub n: usize,
}

/// The value below which a share `q` (0–1) of the sorted values lie, linearly interpolated.
fn quantile(sorted: &[f64], q: f64) -> f64 {
    if sorted.is_empty() {
        return f64::NAN;
    }
    let pos = q.clamp(0.0, 1.0) * (sorted.len() - 1) as f64;
    let (lo, hi) = (pos.floor() as usize, pos.ceil() as usize);
    sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo as f64)
}

pub fn summarise(values: impl Iterator<Item = f64>) -> Summary {
    let mut v: Vec<f64> = values.filter(|x| x.is_finite()).collect();
    if v.is_empty() {
        return Summary { median: f64::NAN, mean: f64::NAN, p95: f64::NAN, n: 0 };
    }
    v.sort_by(f64::total_cmp);
    Summary { median: quantile(&v, 0.5), mean: v.iter().sum::<f64>() / v.len() as f64, p95: quantile(&v, 0.95), n: v.len() }
}

/// Each measure's summary over `entries`, in [`METRICS`] order.
pub fn summary_of(entries: &[&Measured]) -> Vec<(&'static str, Summary)> {
    METRICS.iter().enumerate().map(|(i, metric)| (metric.name, summarise(entries.iter().map(|m| m.values[i])))).collect()
}

/// The stages in the order they first appear, each with its summary in milliseconds.
pub fn timing_summary(entries: &[&Measured]) -> Vec<(&'static str, Summary)> {
    let mut stages: Vec<&'static str> = Vec::new();
    for (stage, _) in entries.iter().flat_map(|m| &m.timings) {
        if !stages.contains(stage) {
            stages.push(stage);
        }
    }
    stages
        .into_iter()
        .map(|stage| (stage, summarise(entries.iter().flat_map(|m| m.timings.iter().filter(|(s, _)| *s == stage).map(|(_, ms)| *ms)))))
        .collect()
}

/// A measure that got worse than its tolerance allows against a baseline mean.
#[derive(Clone, Debug, PartialEq)]
pub struct Regression {
    pub metric: &'static str,
    pub baseline: f64,
    pub now: f64,
}

/// The measures whose mean is worse than `baseline` by more than their tolerance. A measure the
/// baseline has but this run lost (no value at all) is a regression too.
pub fn regressions(now: &[(&'static str, Summary)], baseline: &[(String, f64)]) -> Vec<Regression> {
    let mut out = Vec::new();
    for metric in METRICS {
        let Some((_, base)) = baseline.iter().find(|(name, v)| name == metric.name && v.is_finite()) else { continue };
        let current = now.iter().find(|(name, _)| *name == metric.name).map_or(f64::NAN, |(_, s)| s.mean);
        let worse = match metric.better {
            Better::Higher => base - current,
            Better::Lower => current - base,
        };
        if !current.is_finite() || worse > metric.tolerance {
            out.push(Regression { metric: metric.name, baseline: *base, now: current });
        }
    }
    out
}

/// Drawn scenes `0..n` as entries: the kinds in turn, seed `first_seed + i`.
pub fn synthetic(n: usize, first_seed: u64, width: u32, height: u32) -> impl Iterator<Item = Entry> {
    (0..n).map(move |i| {
        let kind = Kind::SYNTHETIC[i % Kind::SYNTHETIC.len()];
        let seed = first_seed + i as u64;
        let scene = crate::synth::generate(crate::synth::SceneSpec::new(seed, kind, width, height));
        Entry::from_scene(format!("synth-{seed:04}-{}", kind.name()), &scene)
    })
}
