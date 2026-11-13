//! Clean scan keeps what it measures of a straightened page independently of the strength and the
//! paper (#446): the light estimate, the grain and the page smoothed and sharpened. Every look
//! made with what was kept has exactly the bytes of a look measured from nothing, whatever was
//! asked before it, and a strength or paper change runs the tone curve alone.

use crate::aspect::{AspectSource, Paper};
use crate::enhance::{
    AutoParams, PaperMode, Prepared, STAGE_DENOISE, STAGE_LIGHT, STAGE_NOISE, STAGE_SHARPEN, STAGE_TONE, Shape, auto_prepared, auto_with_photos,
};
use crate::look::{Look, Pixels};
use crate::page::{PageBase, PageRequest, page_look_kind};
use crate::page_kind::PresetChoice;
use crate::photo_mask::photo_mask;
use crate::warp::Rgb;

/// A drawn page of print under light that falls off across it, with grain when `grain` is more
/// than 0 (enough, at 9, for the look to smooth it).
fn photographed(grain: i32) -> Rgb {
    let drawn = image::load_from_memory(include_bytes!("../tests/data/upright-english.png")).unwrap().to_rgb8();
    let page = image::imageops::resize(&drawn, 600, 800, image::imageops::FilterType::Triangle);
    let (w, h) = (page.width(), page.height());
    let mut seed = 0x2545_f491_4f6c_dd1du64;
    let mut data = Vec::with_capacity((w * h * 3) as usize);
    for (x, y, p) in page.enumerate_pixels() {
        let light = 1.0 - 0.4 * (x as f32 / w as f32 + y as f32 / h as f32) / 2.0;
        for (c, warm) in [1.0f32, 0.95, 0.85].into_iter().enumerate() {
            seed ^= seed << 13;
            seed ^= seed >> 7;
            seed ^= seed << 17;
            let g = if grain > 0 { (seed % (2 * grain as u64 + 1)) as i32 - grain } else { 0 };
            data.push((f32::from(p.0[c]) * light * warm + g as f32).round().clamp(0.0, 255.0) as u8);
        }
    }
    Rgb { width: w, height: h, data }
}

fn base(page: Rgb) -> PageBase {
    PageBase {
        page,
        dpi: 150,
        paper: Paper::Custom,
        aspect: AspectSource::SideLengths,
        focal_mismatch: false,
        flattened: false,
        bend: 0.0,
        quarter_turns: 0,
        orientation: None,
        turns: 0,
        flatten_check: None,
        trim: None,
        line_flatten: None,
        timings: Vec::new(),
        measured: std::sync::OnceLock::new(),
        look_kept: Prepared::new(),
    }
}

fn rgb(pixels: &Pixels) -> &[u8] {
    match pixels {
        Pixels::Rgb(v) => v,
        _ => panic!("Clean scan gives colour"),
    }
}

fn names(stages: &[(&'static str, u32)]) -> Vec<&'static str> {
    stages.iter().map(|s| s.0).collect()
}

/// Every combination the scan screen and the testing options can ask for, one after another on
/// the same kept page, against the same look made from nothing.
#[test]
fn a_look_from_what_was_kept_has_the_bytes_of_one_made_from_nothing() {
    for grain in [0, 9] {
        let page = photographed(grain);
        let photos = photo_mask(&page).unwrap();
        let kept = Prepared::new();
        let mut asked = 0;
        // sharpening and smoothing change back and forth, so a stale page before the curve would show
        for (sharpen, denoise, with_photos) in
            [(30, true, false), (0, true, false), (30, true, true), (60, false, false), (0, false, false), (30, true, false)]
        {
            for strength in [1, 19, 20, 45, 60, 100] {
                for paper in [PaperMode::White, PaperMode::Keep] {
                    for shape in [
                        Shape::default(),
                        Shape { extra_gamma: 0.3, s_curve: false, colour_factor: 1.0 },
                        Shape { extra_gamma: 0.0, s_curve: true, colour_factor: 1.5 },
                    ] {
                        let params = AutoParams { strength, paper, threads: 2, sharpen, denoise, dpi: 150, shape };
                        let photos = with_photos.then_some(&photos);
                        let made = auto_prepared(&page, &params, photos, &kept).unwrap();
                        let fresh = auto_with_photos(&page, &params, photos).unwrap();
                        assert!(made.rgb == fresh.rgb, "grain {grain}, {params:?}, photos {with_photos}: kept and fresh differ");
                        assert_eq!(
                            (made.objects, made.closing_radius, made.noise.to_bits()),
                            (fresh.objects, fresh.closing_radius, fresh.noise.to_bits())
                        );
                        assert_eq!(made.light_reused, asked > 0);
                        assert!(!fresh.light_reused && !fresh.detail_reused);
                        asked += 1;
                    }
                }
            }
        }
        // the grainy page was smoothed, so the kept smoothing was checked too
        if grain > 0 {
            let first = auto_with_photos(&page, &AutoParams::default(), None).unwrap();
            assert!(first.noise >= 2.0, "noise {}", first.noise);
            assert!(names(&first.stages).contains(&STAGE_DENOISE));
        }
    }
}

/// The first look measures the page; a strength or paper change after it only draws the tone
/// curve, and a change of sharpening sharpens again but keeps the light estimate.
#[test]
fn a_strength_or_paper_change_runs_the_tone_curve_alone() {
    let page = photographed(9);
    let kept = Prepared::new();
    let params = AutoParams { threads: 2, ..AutoParams::default() };
    let first = auto_prepared(&page, &params, None, &kept).unwrap();
    assert_eq!(names(&first.stages), [STAGE_LIGHT, STAGE_NOISE, STAGE_DENOISE, STAGE_SHARPEN, STAGE_TONE]);
    assert!(!first.light_reused && !first.detail_reused);
    for changed in [AutoParams { strength: 85, ..params }, AutoParams { paper: PaperMode::Keep, ..params }, AutoParams { strength: 10, ..params }] {
        let made = auto_prepared(&page, &changed, None, &kept).unwrap();
        assert_eq!(names(&made.stages), [STAGE_TONE], "{changed:?}");
        assert!(made.light_reused && made.detail_reused);
    }
    let sharper = auto_prepared(&page, &AutoParams { sharpen: 60, ..params }, None, &kept).unwrap();
    assert_eq!(names(&sharper.stages), [STAGE_DENOISE, STAGE_SHARPEN, STAGE_TONE]);
    assert!(sharper.light_reused && !sharper.detail_reused);
    // strength 0 is the page as it is: nothing measured, nothing listed
    let off = auto_prepared(&page, &AutoParams { strength: 0, ..params }, None, &kept).unwrap();
    assert!(off.stages.is_empty() && off.rgb == page);
}

/// Through the page pipeline: the preview's straightened page keeps the estimate for Clean scan
/// and Whiteboard, with document types on; the testing option off measures it every time, with the
/// same pixels.
#[test]
fn a_kept_page_reuses_its_light_unless_the_testing_option_is_off() {
    let page = base(photographed(0));
    let ask = |look, strength, keep_paper, reuse_look| PageRequest {
        look,
        strength,
        threads: 2,
        preset: PresetChoice::Auto,
        fixed_strength: true,
        keep_paper,
        reuse_look,
        ..PageRequest::new(&[], [0.0; 8])
    };
    let (first, _) = page_look_kind(&page, &ask(Look::Auto, 60, None, true)).unwrap();
    assert!(!first.light_reused && names(&first.stages).contains(&STAGE_LIGHT));
    for (look, strength, keep_paper) in
        [(Look::Auto, 30, None), (Look::Auto, 90, Some(true)), (Look::Whiteboard, 60, None), (Look::Auto, 60, Some(false))]
    {
        let (made, _) = page_look_kind(&page, &ask(look, strength, keep_paper, true)).unwrap();
        assert!(made.light_reused, "{look:?} {strength}");
        assert_eq!(names(&made.stages), [STAGE_TONE], "{look:?} {strength}");
        let (again, _) = page_look_kind(&page, &ask(look, strength, keep_paper, false)).unwrap();
        assert!(!again.light_reused && names(&again.stages).contains(&STAGE_LIGHT), "{look:?} {strength}");
        assert!(rgb(&made.pixels) == rgb(&again.pixels), "{look:?} {strength}: kept and measured again differ");
    }
    // the other looks have no parts and keep nothing
    let (colour, _) = page_look_kind(&page, &ask(Look::Colour, 50, None, true)).unwrap();
    assert!(colour.stages.is_empty() && !colour.light_reused);
    // a copy of the page keeps what was kept, and is still the same page
    let copy = page.clone();
    assert!(copy == page);
    let (from_copy, _) = page_look_kind(&copy, &ask(Look::Auto, 45, None, true)).unwrap();
    assert!(from_copy.light_reused);
}
