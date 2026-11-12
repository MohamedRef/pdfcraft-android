//! The scanner's CI gate (#288, section 5): 40 drawn photos with fixed seeds go through the
//! pipeline, and the measures must stay at least as good as they were when the gate was set.
//!
//! Each limit is a little looser than what the pipeline measured on these 40 photos when the
//! limit was written (the measured value is beside it). A later change that makes a measure
//! better should tighten its limit in the same pull request; one that makes it worse fails here.
//! `docs/ANDROID_SCAN_MEASURING.md` has the same table.

use crate::detect::Finder;
use crate::eval::{Entry, Measured, Options, measure, summarise, synthetic};

const PHOTOS: usize = 40;
const FIRST_SEED: u64 = 288;
const WIDTH: u32 = 600;
const HEIGHT: u32 = 800;

/// How a summary of one measure over the 40 photos is checked.
#[derive(Clone, Copy, Debug)]
enum Stat {
    Median,
    Mean,
}

#[derive(Clone, Copy, Debug)]
struct Limit {
    metric: &'static str,
    stat: Stat,
    /// `true`: the value must be at least `bound`; `false`: at most.
    at_least: bool,
    bound: f64,
}

const fn at_least(metric: &'static str, stat: Stat, bound: f64) -> Limit {
    Limit { metric, stat, at_least: true, bound }
}

const fn at_most(metric: &'static str, stat: Stat, bound: f64) -> Limit {
    Limit { metric, stat, at_least: false, bound }
}

/// Measured on these 40 photos when the gate was written, beside each limit. Geometry is judged
/// by medians and the `no_touch` share (one page lost moves a mean a lot); clean-up by means.
const LIMITS: [Limit; 11] = [
    at_least("iou", Stat::Median, 0.995),            // 0.99976
    at_least("no_touch", Stat::Mean, 0.80),          // 0.900: 36 of 40 need no corner moved
    at_most("corner_err_max", Stat::Median, 0.002),  // 0.00010 of the diagonal
    at_most("aspect_err", Stat::Median, 0.005),      // 0.00009
    at_least("paper_white", Stat::Mean, 0.79),       // 0.820
    at_most("illum_spread", Stat::Mean, 0.15),       // 0.133
    at_least("ink_contrast", Stat::Mean, 240.0),     // 250.0
    at_most("hue_shift", Stat::Mean, 8.0),           // 6.17 degrees
    at_least("psnr", Stat::Mean, 15.3),              // 15.90 dB
    at_least("ssim", Stat::Mean, 0.66),              // 0.679
    at_most("line_straightness", Stat::Median, 3.5), // 2.66 px
];

/// The geometry limits, which a detector that finds nothing must break.
const GEOMETRY: [&str; 4] = ["iou", "no_touch", "corner_err_max", "aspect_err"];

fn entries() -> Vec<Entry> {
    synthetic(PHOTOS, FIRST_SEED, WIDTH, HEIGHT).collect()
}

/// Every entry measured, four at a time (the measures are the same on any number of threads).
fn run(entries: &[Entry], finder: Finder) -> Vec<Measured> {
    // the found page's look is left out: the clean-up measures use the true corners' page
    let options = Options { finder, threads: 1, look_found_page: false, ..Options::default() };
    let mut out: Vec<Measured> = Vec::with_capacity(entries.len());
    for chunk in entries.chunks(4) {
        let done: Vec<Measured> = std::thread::scope(|s| {
            let handles: Vec<_> = chunk.iter().map(|e| s.spawn(move || measure(e, &options))).collect();
            handles.into_iter().filter_map(|h| h.join().ok()).collect()
        });
        out.extend(done);
    }
    out
}

/// The limits the measures break, each with the value that broke it. A measure taken on fewer
/// than 90 % of the photos breaks its limit too.
fn broken(measured: &[Measured]) -> Vec<(&'static str, f64)> {
    LIMITS
        .iter()
        .filter_map(|limit| {
            let s = summarise(measured.iter().map(|m| m.get(limit.metric)));
            let value = match limit.stat {
                Stat::Median => s.median,
                Stat::Mean => s.mean,
            };
            let within = if limit.at_least { value >= limit.bound } else { value <= limit.bound };
            let ok = value.is_finite() && s.n * 10 >= PHOTOS * 9 && within;
            (!ok).then_some((limit.metric, value))
        })
        .collect()
}

#[test]
fn the_pipeline_meets_the_gate_on_40_drawn_photos() {
    let entries = entries();
    let started = std::time::Instant::now();
    let measured = run(&entries, Finder::Auto);
    assert_eq!(measured.len(), PHOTOS);
    let failed = broken(&measured);
    assert!(failed.is_empty(), "the scanner got worse on the drawn photos (#288 gate): {failed:?}; took {:?}", started.elapsed());
}

#[test]
fn the_gate_fails_when_the_detector_returns_the_whole_photo() {
    // Without the clean pages only the geometry is measured, which is what this is about.
    let entries: Vec<Entry> = entries().into_iter().map(|e| Entry { page: None, ..e }).collect();
    let measured = run(&entries, Finder::Off);
    let failed: Vec<&str> = broken(&measured).into_iter().map(|(metric, _)| metric).collect();
    for metric in GEOMETRY {
        assert!(failed.contains(&metric), "{metric} did not catch a detector that finds nothing: {failed:?}");
    }
}
