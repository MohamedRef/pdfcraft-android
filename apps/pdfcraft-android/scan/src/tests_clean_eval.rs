//! The Clean scan look on the drawn pages of #784 (`clean_eval`): what it keeps of print, pale
//! marks, colour and photographs must stay at least as good as it was when these limits were
//! written, and a photograph on a page of print keeps its own tone.
//!
//! Each limit is a little looser than what the look measured on the page of seed 0 of its group
//! when the limit was written (the measured value is beside it). A change that makes a measure
//! better should tighten its limit in the same pull request; one that makes it worse fails here.
//! `docs/ANDROID_SCAN_MEASURING.md` has the figures over four seeds a group.

use crate::clean_eval::{CleanMeasured, Run, made, measure, measure_into};
use crate::clean_pages::{CleanPage, Group, generate, mark};
use crate::metrics_marks::grown;
use crate::page::{PageRequest, photo_weights};
use crate::photo_mask::PhotoMask;

const SEED: u64 = 0;

/// (group, measure, at least (true) or at most, bound).
type Limit = (Group, &'static str, bool, f64);

/// What every group must keep: white paper, dark print to the edge, thin strokes and dots.
const EVERYWHERE: [(&str, bool, f64); 8] = [
    ("paper_white", true, 0.97),    // 0.990 (shadow) to 1.000
    ("paper_specks", false, 0.01),  // 0.000 to 0.004 (shadow)
    ("ink_contrast", true, 200.0),  // 214 (low light) to 255
    ("edge_contrast", true, 220.0), // 234 (low light) to 255
    ("edge_kept", true, 0.999),     // 1.000
    ("thin_kept", true, 0.995),     // 0.9998 (low light) to 1.000
    ("dot_kept", true, 0.995),      // 1.000
    ("illum_spread", false, 0.05),  // 0.000 to 0.024 (shadow)
];

const LIMITS: [Limit; 33] = [
    (Group::Text, "thin_contrast", true, 215.0),            // 234
    (Group::Text, "ink_weight", false, 1.10),               // 1.006: strokes no fatter than drawn
    (Group::Text, "thin_weight", false, 1.20),              // 1.051
    (Group::Receipt, "faint_contrast", true, 140.0),        // 165, from 73 in the photo
    (Group::Receipt, "faint_kept", true, 0.995),            // 1.000
    (Group::Receipt, "stamp_chroma_kept", true, 0.80),      // 0.887
    (Group::Receipt, "stamp_hue", false, 12.0),             // 9.97 degrees: the receipt's darker curve
    (Group::Pencil, "pencil_contrast", true, 85.0),         // 99, from 47 in the photo
    (Group::Pencil, "pencil_kept", true, 0.995),            // 1.000
    (Group::Pencil, "faint_contrast", true, 95.0),          // 109
    (Group::Colour, "pen_chroma_kept", true, 0.75),         // 0.829
    (Group::Colour, "pen_hue", false, 7.0),                 // 5.11 degrees
    (Group::Colour, "highlight_chroma_kept", true, 0.90),   // 0.980
    (Group::Colour, "highlight_hue", false, 4.5),           // 2.67 degrees
    (Group::Colour, "highlight_ink_contrast", true, 170.0), // 186
    (Group::Colour, "stamp_chroma_kept", true, 0.85),       // 0.913
    (Group::Colour, "stamp_hue", false, 6.5),               // 4.78 degrees
    (Group::Colour, "pale_stamp_chroma_kept", true, 0.90),  // 1.059
    (Group::Colour, "pale_stamp_kept", true, 0.98),         // 0.994
    (Group::Colour, "paper_tint", false, 0.5),              // 0.026 grey levels
    (Group::Shadow, "pen_chroma_kept", true, 0.78),         // 0.886
    (Group::Shadow, "highlight_chroma_kept", true, 0.90),   // 0.980
    (Group::Shadow, "pale_stamp_kept", true, 0.98),         // 0.992
    (Group::Shadow, "stamp_hue", false, 7.0),               // 5.45 degrees
    (Group::Lowlight, "pencil_kept", true, 0.97),           // 0.996, from 0.44 in the photo
    (Group::Lowlight, "faint_kept", true, 0.98),            // 0.997, from 0.53 in the photo
    (Group::Lowlight, "pencil_contrast", true, 55.0),       // 67, from 25 in the photo
    (Group::Photo, "photo_dark_detail", true, 0.40),        // 0.537 (0.000 with one curve)
    (Group::Photo, "photo_light_detail", true, 0.90),       // 1.178 (0.241 with one curve)
    (Group::Photo, "photo_white_clipped", false, 0.01),     // 0.000 (0.083 with one curve)
    (Group::Photo, "photo_black_clipped", false, 0.10),     // 0.083 (0.117 with one curve)
    (Group::Photo, "photo_ssim", true, 0.85),               // 0.888 (0.787 with one curve)
    (Group::Photo, "photo_edge_paper", true, 0.90),         // 0.922: paper beside a photograph stays white
];

fn broken(measured: &[CleanMeasured]) -> Vec<(Group, &'static str, f64)> {
    let mut out = Vec::new();
    let mut check = |m: &CleanMeasured, name: &'static str, at_least: bool, bound: f64| {
        let v = m.get(name);
        if !(v.is_finite() && if at_least { v >= bound } else { v <= bound }) {
            out.push((m.group, name, v));
        }
    };
    for m in measured {
        for (name, at_least, bound) in EVERYWHERE {
            check(m, name, at_least, bound);
        }
        for (group, name, at_least, bound) in LIMITS {
            if group == m.group {
                check(m, name, at_least, bound);
            }
        }
    }
    out
}

#[test]
fn clean_scan_keeps_print_pale_marks_colour_and_photographs_on_the_drawn_pages() {
    let started = std::time::Instant::now();
    let mut measured: Vec<CleanMeasured> = Vec::new();
    for chunk in Group::ALL.chunks(4) {
        let done: Vec<Option<CleanMeasured>> = std::thread::scope(|s| {
            let handles: Vec<_> = chunk.iter().map(|&group| s.spawn(move || measure(&generate(group, SEED), Run::App, 1))).collect();
            handles.into_iter().map(|h| h.join().ok().flatten()).collect()
        });
        measured.extend(done.into_iter().flatten());
    }
    assert_eq!(measured.len(), Group::ALL.len(), "a page could not be made");
    // the kinds of document the look took the pages for
    for m in &measured {
        assert_eq!(m.preset, if m.group == Group::Receipt { "receipt" } else { "document" }, "{:?}", m.group);
        // a drawn page has no finger on it
        assert_eq!(m.fingers, (0, 0), "{:?}", m.group);
    }
    let failed = broken(&measured);
    assert!(failed.is_empty(), "Clean scan got worse on the drawn pages (#784): {failed:?}; took {:?}", started.elapsed());
}

#[test]
fn the_limits_catch_a_page_left_as_the_camera_took_it() {
    // the control: the photo as taken breaks the paper and print limits, so they measure the look
    let page = generate(Group::Pencil, SEED);
    let original = measure(&page, Run::Original, 1).unwrap();
    let failed: Vec<&str> = broken(&[original]).into_iter().map(|(_, name, _)| name).collect();
    for name in ["paper_white", "ink_contrast", "pencil_contrast", "illum_spread"] {
        assert!(failed.contains(&name), "{name} did not tell the photo from the cleaned page: {failed:?}");
    }
}

fn one_curve(request: &mut PageRequest<'_>) {
    request.photo_tone = false;
}

fn weak(request: &mut PageRequest<'_>) {
    request.strength = 20;
    request.fixed_strength = true;
}

fn same_outside(a: &[u8], b: &[u8], page: &CleanPage, away: usize) -> bool {
    let (w, h) = (page.width as usize, page.height as usize);
    let photo: Vec<bool> = page.marks.iter().map(|&t| matches!(t, mark::PHOTO | mark::PHOTO_DARK | mark::PHOTO_LIGHT)).collect();
    let near = grown(&photo, w, h, away);
    (0..w * h).filter(|&i| !near[i]).all(|i| a[i * 3..i * 3 + 3] == b[i * 3..i * 3 + 3])
}

#[test]
fn a_photograph_on_a_page_of_print_keeps_its_shadows_and_highlights() {
    let page = generate(Group::Photo, SEED);
    let (after, mut with) = made(&page, Run::App, 2).unwrap();
    let (before, mut without) = made(&page, Run::With(&one_curve), 2).unwrap();
    measure_into(&mut with, &page, &after);
    measure_into(&mut without, &page, &before);
    println!(
        "photographs with their own curve, with one curve: dark detail {:.3}, {:.3}; light detail {:.3}, {:.3}; white clipped {:.3}, {:.3}; ssim {:.3}, {:.3}",
        with.get("photo_dark_detail"),
        without.get("photo_dark_detail"),
        with.get("photo_light_detail"),
        without.get("photo_light_detail"),
        with.get("photo_white_clipped"),
        without.get("photo_white_clipped"),
        with.get("photo_ssim"),
        without.get("photo_ssim"),
    );
    // the fault this is about: one curve takes the shadows' detail to black and the highlights' to white
    assert!(without.get("photo_dark_detail") < 0.05 && without.get("photo_light_detail") < 0.40, "{without:?}");
    assert!(without.get("photo_white_clipped") > 0.05, "{without:?}");
    assert!(with.get("photo_dark_detail") > 0.40 && with.get("photo_light_detail") > 0.90, "{with:?}");
    assert!(with.get("photo_white_clipped") < 0.01 && with.get("photo_ssim") > without.get("photo_ssim") + 0.05, "{with:?}");
    // the print beside the photographs is as dark, the paper as white
    assert!((with.get("ink_contrast") - without.get("ink_contrast")).abs() <= 2.0, "{} {}", with.get("ink_contrast"), without.get("ink_contrast"));
    assert!((with.get("thin_contrast") - without.get("thin_contrast")).abs() <= 4.0);
    assert_eq!(with.get("paper_white"), without.get("paper_white"));
    // a quarter of the page took some of it, and nothing changed away from the photographs
    assert!((0.15..0.40).contains(&with.photo_tone), "{}", with.photo_tone);
    assert_eq!(without.photo_tone, 0.0);
    assert!(same_outside(&after.data, &before.data, &page, 200));
    assert_ne!(after.data, before.data);
    // the same page twice, and on another number of threads: the same bytes
    assert_eq!(made(&page, Run::App, 1).unwrap().0.data, after.data);
}

#[test]
fn a_page_without_photographs_or_at_a_weak_strength_is_as_before() {
    let text = generate(Group::Text, SEED);
    let (with, m) = made(&text, Run::App, 2).unwrap();
    assert_eq!(m.photo_tone, 0.0);
    assert_eq!(with.data, made(&text, Run::With(&one_curve), 2).unwrap().0.data);
    // a strength no stronger than a photograph's own: one curve
    let photo = generate(Group::Photo, SEED);
    let (weak_page, m) = made(&photo, Run::With(&weak), 2).unwrap();
    assert_eq!(m.photo_tone, 0.0);
    let both = |request: &mut PageRequest<'_>| {
        weak(request);
        one_curve(request);
    };
    assert_eq!(weak_page.data, made(&photo, Run::With(&both), 2).unwrap().0.data);
}

#[test]
fn what_the_finger_step_filled_is_left_out_of_the_photographs() {
    // a 6 × 4 grid over a 60 × 40 page: one group of blocks top left, one bottom right
    let (cols, rows, w, h) = (6usize, 4usize, 60usize, 40usize);
    let mut weight = vec![0.0f32; cols * rows];
    for i in [0, 1, 6, 7] {
        weight[i] = 1.0;
    }
    for i in [16, 17, 22, 23] {
        weight[i] = 0.5;
    }
    let mask = PhotoMask { cols, rows, block: 10, weight: weight.clone(), share: 0.3, whole_page: false };
    let before = vec![200u8; w * h * 3];
    // nothing filled: the mask's own weights
    assert_eq!(photo_weights(&mask, None, &before, w, h), (weight.clone(), 0));
    assert_eq!(photo_weights(&mask, Some(&before), &before, w, h), (weight.clone(), 0));
    // a pixel changed in the block at column 1, row 1: its whole group goes, the other stays
    let mut after = before.clone();
    after[(15 * w + 15) * 3] = 255;
    let (left, out) = photo_weights(&mask, Some(&before), &after, w, h);
    assert_eq!(out, 1);
    assert!([0, 1, 6, 7].iter().all(|&i| left[i] == 0.0));
    assert!([16, 17, 22, 23].iter().all(|&i| left[i] == 0.5));
    // a change where the mask has no weight leaves it alone
    let mut elsewhere = before.clone();
    elsewhere[(5 * w + 45) * 3] = 0;
    assert_eq!(photo_weights(&mask, Some(&before), &elsewhere, w, h), (weight.clone(), 0));
    // buffers that do not fit: the mask's own weights
    assert_eq!(photo_weights(&mask, Some(&before[..30]), &after, w, h), (weight, 0));
}
