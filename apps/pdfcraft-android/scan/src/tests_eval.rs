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

/// A photo with nothing on it, as the app's own second-look test draws it.
fn blank_entry() -> Entry {
    let mut png = Vec::new();
    image::RgbImage::from_fn(640, 480, |x, y| image::Rgb([90 + (x % 3) as u8, 90 + (y % 3) as u8, 90]))
        .write_to(&mut std::io::Cursor::new(&mut png), image::ImageFormat::Png)
        .unwrap();
    Entry { name: "blank".into(), kind: Kind::Desk, photo: png, corners: None, aspect: None, focal_35mm: None, page: None }
}

#[test]
fn the_second_look_is_the_app_s_and_is_off_unless_asked_for() {
    let second = Options { second_look: true, look_found_page: false, ..Options::default() };
    let first = Options { second_look: false, ..second };
    assert!(!Options::default().second_look, "the gate and older reports measure the path without it");

    // a page the network is sure of: the line detector is not asked, and nothing changes
    let scene = generate(SceneSpec::new(1, Kind::Light, 600, 800));
    let entry = Entry::from_scene("light".into(), &scene);
    let (without, with) = (measure(&entry, &first), measure(&entry, &second));
    assert_eq!((without.second_look, with.second_look), ("not_asked", "not_asked"));
    assert_eq!(without.found, with.found);
    assert!(!with.timings.iter().any(|(s, _)| *s == "second_look"));

    // nothing found: the line detector is asked, finds nothing either, and its time is counted
    let blank = blank_entry();
    let none = measure(&blank, &first);
    assert!(!none.detected && none.second_look == "not_asked");
    let looked = measure(&blank, &second);
    assert!(!looked.detected && looked.second_look == "kept", "{}", looked.second_look);
    assert!(looked.timings.iter().any(|(s, _)| *s == "second_look"));
    assert_eq!(looked.found, none.found, "the whole photo either way");

    // only with the detector on Auto and the closer look on, as in the app
    for other in [Options { finder: Finder::Network, ..second }, Options { refine: false, ..second }] {
        assert_eq!(measure(&blank, &other).second_look, "not_asked");
    }
}

#[test]
fn the_second_look_takes_the_line_detector_s_page_on_two_drawn_photos() {
    // Two of `scan-eval --synthetic 200` (800 × 1066, seeds from 0) where the app's default path
    // and the path without the second look part ways: measured for #779's baseline.
    let second = Options { second_look: true, look_found_page: false, ..Options::default() };
    let first = Options { second_look: false, ..second };
    let drawn = |seed, kind| Entry { page: None, ..Entry::from_scene("drawn".into(), &generate(SceneSpec::new(seed, kind, 800, 1066))) };

    // the network finds nothing on this receipt; the line detector's page has its sides on edges
    let receipt = drawn(74, Kind::Receipt);
    let (without, with) = (measure(&receipt, &first), measure(&receipt, &second));
    assert!(!without.detected && without.get("iou") < 0.5, "iou {}", without.get("iou"));
    assert!(with.detected && with.second_look == "taken" && with.get("iou") > 0.99, "{} iou {}", with.second_look, with.get("iou"));
    assert_eq!(with.get("no_touch"), 1.0);
    let stages: Vec<&str> = with.timings.iter().map(|(s, _)| *s).collect();
    assert_eq!(&stages[..4], ["decode", "detect", "second_look", "refine"]);

    // the network is unsure of this page and a corner is off; the line detector's page replaces it
    let desk = drawn(6, Kind::Desk);
    let (without, with) = (measure(&desk, &first), measure(&desk, &second));
    assert!(without.detected && without.get("no_touch") == 0.0, "iou {}", without.get("iou"));
    assert!(with.second_look == "taken" && with.get("no_touch") == 1.0 && with.get("iou") > without.get("iou"), "iou {}", with.get("iou"));
}
