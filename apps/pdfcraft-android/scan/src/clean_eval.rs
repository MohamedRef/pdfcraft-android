//! Measure the Clean scan look on drawn pages whose marks are known (#784): each page of
//! [`crate::clean_pages`] goes through the app's own page pipeline ([`page_base`], then
//! [`page_look_kind`]) with its corners at the photo's corners, and the result is measured mark
//! by mark with the blocks of `metrics_marks` and `metrics_tone`. Shared by `scan-eval --clean`
//! and the tests that hold the figures (`tests_clean_eval`).
//!
//! Three ways of making the page are compared: the photo as taken ([`Run::Original`], the
//! control), Clean scan as the app runs it by default ([`Run::App`]: the document types, their
//! tone, the gentle sharpening of photographs and the finger step all on), and Clean scan as the
//! older evaluator measures it ([`Run::Plain`]: strength 50, no document types). A fourth,
//! [`Run::With`], takes any change to the request, for trying one option at a time.

use crate::clean_pages::{CleanPage, Group, mark};
use crate::eval::{Better, Metric, Summary, summarise};
use crate::look::{Look, Pixels};
use crate::metrics_compare::ssim;
use crate::metrics_marks::{PaperLevels, chroma_kept, clipped, grown, mark_contrast, mark_kept, mean_chroma, stroke_weight, tone_kept};
use crate::metrics_tone::{hue_shift, illum_spread, paper_white};
use crate::page::{PageRequest, WHOLE_PHOTO, page_base, page_look_kind};
use crate::page_kind::PresetChoice;
use crate::{Luma, Rgb, luma};

const fn metric(name: &'static str, better: Better, tolerance: f64) -> Metric {
    Metric { name, better, tolerance }
}

/// The measures, in report order. Contrasts are grey levels (0–255) under the paper beside the
/// mark; `_kept` is a share of the mark's pixels still visible, or of its colour or tone (0–1,
/// more for a gain); `_hue` is degrees; `_weight` is 1 for strokes as drawn. The tolerance is how
/// much worse a mean may get against a baseline before `scan-eval --clean --baseline` fails.
pub const CLEAN_METRICS: [Metric; 34] = [
    // paper
    metric("paper_white", Better::Higher, 0.02),
    metric("paper_tint", Better::Lower, 1.0),
    metric("paper_specks", Better::Lower, 0.002),
    metric("illum_spread", Better::Lower, 0.01),
    // print
    metric("ink_contrast", Better::Higher, 5.0),
    metric("ink_weight", Better::Higher, f64::INFINITY),
    metric("thin_contrast", Better::Higher, 5.0),
    metric("thin_kept", Better::Higher, 0.02),
    metric("thin_weight", Better::Higher, f64::INFINITY),
    metric("dot_kept", Better::Higher, 0.02),
    metric("edge_contrast", Better::Higher, 5.0),
    metric("edge_kept", Better::Higher, 0.02),
    // pale marks
    metric("faint_contrast", Better::Higher, 5.0),
    metric("faint_kept", Better::Higher, 0.02),
    metric("pencil_contrast", Better::Higher, 5.0),
    metric("pencil_kept", Better::Higher, 0.02),
    // colour
    metric("pen_chroma_kept", Better::Higher, 0.05),
    metric("pen_hue", Better::Lower, 2.0),
    metric("highlight_chroma_kept", Better::Higher, 0.05),
    metric("highlight_hue", Better::Lower, 2.0),
    metric("highlight_ink_contrast", Better::Higher, 5.0),
    metric("stamp_chroma_kept", Better::Higher, 0.05),
    metric("stamp_hue", Better::Lower, 2.0),
    metric("pale_stamp_chroma_kept", Better::Higher, 0.05),
    metric("pale_stamp_kept", Better::Higher, 0.02),
    metric("pale_stamp_hue", Better::Lower, 2.0),
    // photographs
    metric("photo_tone_kept", Better::Higher, 0.05),
    metric("photo_dark_detail", Better::Higher, 0.05),
    metric("photo_light_detail", Better::Higher, 0.05),
    metric("photo_black_clipped", Better::Lower, 0.02),
    metric("photo_white_clipped", Better::Lower, 0.02),
    metric("photo_chroma_kept", Better::Higher, 0.05),
    metric("photo_ssim", Better::Higher, 0.02),
    metric("photo_edge_paper", Better::Higher, 0.02),
];

/// A photograph's pixels at or under this are black, at or over [`PHOTO_WHITE`] white.
pub const PHOTO_BLACK: u8 = 8;
pub const PHOTO_WHITE: u8 = 250;

/// How a page is made for measuring.
#[derive(Clone, Copy)]
pub enum Run<'a> {
    /// The photo as taken: the control.
    Original,
    /// Clean scan with the app's defaults (ScanLab's): document types worked out from the page,
    /// their tone curve, photographs sharpened gently, fingers removed.
    App,
    /// Clean scan at strength 50 with no document types: what `eval` and the CI gate measure.
    Plain,
    /// [`Run::App`] with a change to the request.
    With(&'a (dyn Fn(&mut PageRequest<'_>) + Sync)),
}

/// One page made one way, and every measure of it; `NAN` where the page has no such mark.
#[derive(Clone, Debug)]
pub struct CleanMeasured {
    pub group: Group,
    pub seed: u64,
    /// The document type the look took the page for ("off" without document types).
    pub preset: &'static str,
    /// The level from which paper was drawn white (1 for the original).
    pub white_point: f32,
    /// The page's grain as the look measured it.
    pub noise: f32,
    /// Fingers the finger step found and filled (a drawn page has none).
    pub fingers: (u32, u32),
    /// The share of the page that took the photographs' own tone curve (0 when none did).
    pub photo_tone: f32,
    pub values: [f64; CLEAN_METRICS.len()],
    /// Milliseconds of the look.
    pub look_ms: f64,
}

impl CleanMeasured {
    pub fn get(&self, name: &str) -> f64 {
        CLEAN_METRICS.iter().position(|m| m.name == name).map_or(f64::NAN, |i| self.values[i])
    }

    fn set(&mut self, name: &str, value: Option<f64>) {
        if let Some(i) = CLEAN_METRICS.iter().position(|m| m.name == name) {
            self.values[i] = value.filter(|v| v.is_finite()).unwrap_or(f64::NAN);
        }
    }
}

fn grey(page: &Rgb) -> Luma {
    Luma { width: page.width, height: page.height, data: page.data.as_chunks::<3>().0.iter().map(|p| luma(*p)).collect() }
}

/// The request for `page` made as `run` asks.
pub fn request<'a>(page: &'a CleanPage, run: Run<'_>, threads: usize) -> PageRequest<'a> {
    let mut req = PageRequest { look: Look::Auto, threads, ..PageRequest::new(&page.jpeg, WHOLE_PHOTO) };
    match run {
        Run::Original => req.look = Look::Original,
        Run::Plain => {}
        Run::App | Run::With(_) => {
            req.preset = PresetChoice::Auto;
            req.kind_tone = true;
            req.photo_sharpen = true;
            req.remove_fingers = true;
            req.photo_tone = true;
        }
    }
    if let Run::With(change) = run {
        change(&mut req);
    }
    req
}

/// `page` made as `run` asks: its pixels (RGB8, the page's own size) and what the look said.
/// `None` when the pipeline failed or gave another size than the page's.
pub fn made(page: &CleanPage, run: Run<'_>, threads: usize) -> Option<(Rgb, CleanMeasured)> {
    let req = request(page, run, threads);
    let base = page_base(&req).ok()?;
    let clock = std::time::Instant::now();
    let (applied, kind) = page_look_kind(&base, &req).ok()?;
    let look_ms = clock.elapsed().as_secs_f64() * 1000.0;
    let Pixels::Rgb(data) = applied.pixels else { return None };
    if (base.page.width, base.page.height) != (page.width, page.height) {
        return None;
    }
    let m = CleanMeasured {
        group: page.group,
        seed: page.seed,
        preset: kind.map_or("off", |k| k.preset.id()),
        white_point: applied.white_point.unwrap_or(1.0),
        noise: applied.noise.unwrap_or(0.0),
        fingers: applied.fingers.map_or((0, 0), |f| (f.found, f.filled)),
        photo_tone: applied.photo_tone.map_or(0.0, |t| t.share),
        values: [f64::NAN; CLEAN_METRICS.len()],
        look_ms,
    };
    Some((Rgb { width: page.width, height: page.height, data }, m))
}

/// Every measure of `after`, a making of `page`, into `m`.
pub fn measure_into(m: &mut CleanMeasured, page: &CleanPage, after: &Rgb) {
    let (w, h) = (page.width as usize, page.height as usize);
    if after.data.len() != w * h * 3 || page.marks.len() != w * h {
        return;
    }
    let is = |tags: &[u8]| -> Vec<bool> { page.marks.iter().map(|t| tags.contains(t)).collect() };
    let any = |mask: &[bool]| mask.contains(&true);
    let truth = Rgb { width: page.width, height: page.height, data: page.clean.clone() };
    let (g, tg) = (grey(after), grey(&truth));
    // paper at least 4 pixels from any mark is plain paper; 10 pixels away it is far from print
    let marked: Vec<bool> = page.marks.iter().map(|&t| t != mark::PAPER).collect();
    let paper: Vec<bool> = grown(&marked, w, h, 4).iter().map(|&near| !near).collect();
    let far: Vec<bool> = grown(&marked, w, h, 10).iter().map(|&near| !near).collect();
    let Some(levels) = PaperLevels::new(&g, &paper) else { return };

    m.set("paper_white", paper_white(&g, &paper));
    m.set("paper_tint", mean_chroma(after, &paper));
    m.set("paper_specks", mark_kept(&g, &levels, &far));
    m.set("illum_spread", illum_spread(&g, &paper));

    let around = |mask: &[bool]| grown(mask, w, h, 3);
    let (ink, thin) = (is(&[mark::INK]), is(&[mark::THIN]));
    m.set("ink_contrast", mark_contrast(&g, &levels, &ink));
    // the strokes' surroundings must hold no other mark, or a neighbour would count as weight
    let alone = |mask: &[bool]| -> Vec<bool> {
        around(mask).iter().zip(&page.marks).zip(mask).map(|((&a, &t), &own)| a && (own || t == mark::PAPER)).collect()
    };
    m.set("ink_weight", stroke_weight(&g, &levels, &ink, &alone(&ink)));
    for (name, tags) in [("thin", &[mark::THIN][..]), ("edge", &[mark::EDGE]), ("faint", &[mark::FAINT]), ("pencil", &[mark::PENCIL])] {
        let mask = is(tags);
        if any(&mask) {
            m.set(&format!("{name}_contrast"), mark_contrast(&g, &levels, &mask));
            m.set(&format!("{name}_kept"), mark_kept(&g, &levels, &mask));
        }
    }
    m.set("thin_weight", stroke_weight(&g, &levels, &thin, &alone(&thin)));
    m.set("dot_kept", mark_kept(&g, &levels, &is(&[mark::DOT])));

    for (name, tag) in [("pen", mark::PEN), ("highlight", mark::HIGHLIGHT), ("stamp", mark::STAMP), ("pale_stamp", mark::STAMP_PALE)] {
        let mask = is(&[tag]);
        if any(&mask) {
            m.set(&format!("{name}_chroma_kept"), chroma_kept(&truth, after, &mask));
            m.set(&format!("{name}_hue"), hue_shift(&truth, after, &mask));
        }
    }
    let (band, under) = (is(&[mark::HIGHLIGHT]), is(&[mark::HIGHLIGHT_INK]));
    if any(&band) && any(&under) {
        // print under highlighter against the highlighter beside it
        if let Some(levels) = PaperLevels::new(&g, &band) {
            m.set("highlight_ink_contrast", mark_contrast(&g, &levels, &under));
        }
    }
    let pale = is(&[mark::STAMP_PALE]);
    if any(&pale) {
        // a pale stamp is seen by its colour as much as its depth: the share that still has one
        let coloured = pale.iter().enumerate().filter(|(i, p)| **p && chroma_at(&after.data, *i) >= crate::metrics_marks::MIN_CHROMA).count();
        m.set("pale_stamp_kept", Some(coloured as f64 / pale.iter().filter(|p| **p).count() as f64));
    }

    let photo = is(&[mark::PHOTO, mark::PHOTO_DARK, mark::PHOTO_LIGHT]);
    if any(&photo) {
        m.set("photo_tone_kept", tone_kept(&tg, &g, &is(&[mark::PHOTO])));
        m.set("photo_dark_detail", tone_kept(&tg, &g, &is(&[mark::PHOTO_DARK])));
        m.set("photo_light_detail", tone_kept(&tg, &g, &is(&[mark::PHOTO_LIGHT])));
        // against what the picture itself has at each end, so a picture with black in it is not a fault
        let more = |level: u8, dark: bool| Some((clipped(&g, &photo, level, dark)? - clipped(&tg, &photo, level, dark)?).max(0.0));
        m.set("photo_black_clipped", more(PHOTO_BLACK, true));
        m.set("photo_white_clipped", more(PHOTO_WHITE, false));
        m.set("photo_chroma_kept", chroma_kept(&truth, after, &is(&[mark::PHOTO])));
        m.set("photo_ssim", photo_ssim(&tg, &g, &photo, w, h));
        // the paper within 12 pixels of a photograph: white there, or greyed by the photograph
        let ring: Vec<bool> = grown(&photo, w, h, 12).iter().zip(&page.marks).map(|(&near, &t)| near && t == mark::PAPER).collect();
        m.set("photo_edge_paper", paper_white(&g, &ring));
    }
}

fn chroma_at(data: &[u8], i: usize) -> u8 {
    let px = &data[i * 3..i * 3 + 3];
    px[0].max(px[1]).max(px[2]) - px[0].min(px[1]).min(px[2])
}

/// SSIM against the truth over the box that holds the photographs' pixels.
fn photo_ssim(truth: &Luma, after: &Luma, photo: &[bool], w: usize, h: usize) -> Option<f64> {
    let (mut x0, mut y0, mut x1, mut y1) = (w, h, 0usize, 0usize);
    for (i, _) in photo.iter().enumerate().filter(|(_, p)| **p) {
        (x0, y0, x1, y1) = (x0.min(i % w), y0.min(i / w), x1.max(i % w + 1), y1.max(i / w + 1));
    }
    if x1 <= x0 || y1 <= y0 {
        return None;
    }
    let cut = |page: &Luma| Luma {
        width: (x1 - x0) as u32,
        height: (y1 - y0) as u32,
        data: (y0..y1).flat_map(|y| page.data[y * w + x0..y * w + x1].iter().copied()).collect(),
    };
    ssim(&cut(after), &cut(truth))
}

/// `page` made as `run` asks and measured. `None` when the pipeline failed.
pub fn measure(page: &CleanPage, run: Run<'_>, threads: usize) -> Option<CleanMeasured> {
    let (after, mut m) = made(page, run, threads)?;
    measure_into(&mut m, page, &after);
    Some(m)
}

/// The mean of each measure over `entries` (`NAN`s left out), in [`CLEAN_METRICS`] order.
pub fn summary_of(entries: &[&CleanMeasured]) -> Vec<(&'static str, Summary)> {
    CLEAN_METRICS.iter().enumerate().map(|(i, metric)| (metric.name, summarise(entries.iter().map(|m| m.values[i])))).collect()
}

/// The pages of every group for seeds `first_seed..first_seed + per_group`.
pub fn pages(per_group: usize, first_seed: u64) -> impl Iterator<Item = CleanPage> {
    Group::ALL.into_iter().flat_map(move |group| (0..per_group as u64).map(move |i| crate::clean_pages::generate(group, first_seed + i)))
}
