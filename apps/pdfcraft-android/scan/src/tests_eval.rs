//! The measuring core of `scan-eval` and the CI gate (#288): summaries, the baseline check,
//! and one drawn photo measured end to end with the metrics blocks.

use crate::detect::Finder;
use crate::eval::{Entry, METRICS, Options, Summary, measure, paper_aspect, regressions, summarise, summary_of, synthetic, timing_summary};
use crate::synth::{Kind, SceneSpec, generate};

fn close(a: f64, b: f64) -> bool {
    (a - b).abs() < 1e-9
}

#[test]
fn a_summary_is_median_mean_and_95th_percentile_without_the_gaps() {
    let s = summarise([4.0, 1.0, f64::NAN, 3.0, 2.0, f64::INFINITY].into_iter());
    assert_eq!(s.n, 4);
    assert!(close(s.median, 2.5) && close(s.mean, 2.5), "{s:?}");
    // 0.95 of the way from the first to the last of four sorted values
    assert!(close(s.p95, 3.85), "{s:?}");
    assert_eq!(summarise(std::iter::empty()).n, 0);
}

#[test]
fn a_regression_is_a_mean_worse_than_its_tolerance_in_the_measure_s_direction() {
    let now = |iou: f64, err: f64| {
        vec![("iou", Summary { median: iou, mean: iou, p95: iou, n: 1 }), ("corner_err_max", Summary { median: err, mean: err, p95: err, n: 1 })]
    };
    let base = vec![("iou".to_string(), 0.95), ("corner_err_max".to_string(), 0.010)];
    assert!(regressions(&now(0.95, 0.010), &base).is_empty());
    // better in both directions is no regression; within tolerance neither
    assert!(regressions(&now(0.99, 0.002), &base).is_empty());
    assert!(regressions(&now(0.945, 0.0115), &base).is_empty());
    let worse = regressions(&now(0.90, 0.020), &base);
    assert_eq!(worse.iter().map(|r| r.metric).collect::<Vec<_>>(), ["iou", "corner_err_max"]);
    // a measure the baseline has and this run lost
    let lost = regressions(&now(f64::NAN, 0.010), &base);
    assert_eq!(lost.len(), 1);
    assert_eq!(lost[0].metric, "iou");
    // every measure has a direction and a tolerance that is not negative
    assert!(METRICS.iter().all(|m| m.tolerance >= 0.0));
}

#[test]
fn paper_names_give_their_proportions() {
    assert!(paper_aspect("a4").is_some_and(|r| close(r, 210.0 / 297.0)));
    assert!(paper_aspect("unknown").is_none());
}

#[test]
fn a_drawn_photo_is_measured_end_to_end() {
    let scene = generate(SceneSpec::new(1, Kind::Light, 600, 800));
    let entry = Entry::from_scene("light".into(), &scene);
    let found = measure(&entry, &Options::default());
    assert!(found.detected);
    // every measure the drawn photo has an answer for is taken
    for metric in METRICS {
        assert!(found.get(metric.name).is_finite(), "{} not measured", metric.name);
    }
    assert!(found.get("iou") > 0.95, "iou {}", found.get("iou"));
    assert!(found.get("corner_err_max") < 0.01, "corner_err_max {}", found.get("corner_err_max"));
    assert!(found.get("aspect_err") < 0.02, "aspect_err {}", found.get("aspect_err"));
    assert!(found.get("ink_contrast") > 100.0, "ink_contrast {}", found.get("ink_contrast"));
    let stages: Vec<&str> = found.timings.iter().map(|(s, _)| *s).collect();
    assert_eq!(stages, ["decode", "detect", "refine", "scan_rectify", "scan_look", "scan_encode"]);

    // With nothing looked for, the page is the whole photo: the geometry measures say so, and
    // the clean-up measures (taken with the true corners) do not change.
    let whole = measure(&entry, &Options { finder: Finder::Off, ..Options::default() });
    assert!(!whole.detected);
    assert!(whole.get("iou") < 0.86, "iou {}", whole.get("iou"));
    assert_eq!(whole.get("no_touch"), 0.0);
    assert!(whole.get("corner_err_max") > 0.05);
    assert_eq!(whole.get("psnr").to_bits(), found.get("psnr").to_bits());
}

#[test]
fn a_test_set_entry_without_a_clean_page_has_geometry_measures_only() {
    let scene = generate(SceneSpec::new(2, Kind::Desk, 600, 800));
    let entry = Entry { page: None, focal_35mm: None, ..Entry::from_scene("desk".into(), &scene) };
    let m = measure(&entry, &Options::default());
    assert!(m.get("iou").is_finite() && m.get("aspect_err").is_finite());
    assert!(m.get("psnr").is_nan() && m.get("paper_white").is_nan());
}

#[test]
fn drawn_entries_take_the_kinds_in_turn_and_summaries_cover_them() {
    let entries: Vec<Entry> = synthetic(7, 10, 320, 420).collect();
    let kinds: Vec<Kind> = entries.iter().map(|e| e.kind).collect();
    assert_eq!(&kinds[..6], &Kind::SYNTHETIC);
    assert_eq!(kinds[6], Kind::Desk);
    assert_eq!(entries[0].name, "synth-0010-desk");
    let measured: Vec<_> = entries[..2].iter().map(|e| measure(e, &Options::default())).collect();
    let refs: Vec<_> = measured.iter().collect();
    let summary = summary_of(&refs);
    assert_eq!(summary.len(), METRICS.len());
    assert_eq!(summary[0].1.n, 2);
    assert_eq!(timing_summary(&refs)[0].0, "decode");
}
