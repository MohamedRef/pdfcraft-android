//! A page's first picture (`first.rs`): it has the shape the page itself will have, shows the
//! same page, needs no decoding when a small copy of the photo is handed in, and follows of the
//! request only what it says it does.

use crate::first::{FIRST_SIDE, STAGE_FIRST_DECODE, STAGE_FIRST_HEADER, STAGE_FIRST_LOOK, STAGE_FIRST_RECTIFY, first_page, shrunk};
use crate::look::{Look, Pixels};
use crate::page::{PageRequest, page_base, page_look};
use crate::page_kind::PresetChoice;
use crate::synth::{Kind, SceneSpec, generate};
use crate::tests_proportions::{exif_block, with_exif};
use crate::{Paper, Photo};

/// The scan screen's preview size.
const PREVIEW: u32 = 1280;

fn rgb(p: &Pixels) -> &[u8] {
    match p {
        Pixels::Rgb(v) | Pixels::Grey(v) => v,
        Pixels::Bilevel(_) => &[],
    }
}

/// The mean difference in grey levels between two RGB pictures, the first shrunk to the second's size.
fn apart(a: &[u8], (aw, ah): (u32, u32), b: &[u8], (bw, bh): (u32, u32)) -> f64 {
    let a = image::RgbImage::from_raw(aw, ah, a.to_vec()).unwrap();
    let a = image::imageops::resize(&a, bw, bh, image::imageops::FilterType::Triangle);
    let sum: u64 = a.as_raw().iter().zip(b).map(|(x, y)| u64::from(x.abs_diff(*y))).sum();
    sum as f64 / b.len() as f64
}

/// Whether a first picture of `first` pixels has the shape of a page of `page` pixels, and is
/// `side` pixels long: a standard paper is made at a whole resolution, so each is within a
/// hundredth of what was asked.
fn shaped(first: (u32, u32), page: (u32, u32), side: u32) -> bool {
    let ratio = |(w, h): (u32, u32)| f64::from(w) / f64::from(h);
    let long = f64::from(first.0.max(first.1));
    (ratio(first) / ratio(page) - 1.0).abs() < 0.005 && (long / f64::from(side) - 1.0).abs() < 0.01
}

/// A drawn photo of a page on a desk, 1600 × 2000, and its page's corners.
fn desk() -> (Vec<u8>, [f32; 8]) {
    let scene = generate(SceneSpec::new(7, Kind::Desk, 1600, 2000));
    let corners = scene.shares();
    (scene.jpeg, corners)
}

fn clean(photo: &[u8], corners: [f32; 8]) -> PageRequest<'_> {
    PageRequest { look: Look::Auto, strength: 60, ..PageRequest::new(photo, corners) }
}

/// The first picture is the preview's page at half the size: the same proportions to the pixel's
/// rounding, the same paper, and the same picture when the preview is shrunk onto it.
#[test]
fn the_first_picture_has_the_shape_and_the_content_of_the_preview() {
    let (jpeg, corners) = desk();
    let small = shrunk(&Photo::decode(&jpeg).unwrap(), 4);
    for look in [Look::Auto, Look::Colour, Look::Greyscale] {
        let ask = PageRequest { look, ..clean(&jpeg, corners) };
        let preview = PageRequest { max_side: Some(PREVIEW), ..ask.clone() };
        let base = page_base(&preview).unwrap();
        let page = page_look(&base, &preview).unwrap();
        let first = first_page(&ask, Some(&small)).unwrap();
        assert!(first.from_small);
        assert!(shaped((first.width, first.height), (base.page.width, base.page.height), FIRST_SIDE), "{look:?}: {} x {}", first.width, first.height);
        assert_eq!(first.paper, base.paper);
        assert_eq!(first.turns, 0);
        let channels = if look == Look::Greyscale { 1 } else { 3 };
        assert_eq!(rgb(&first.pixels).len(), (first.width * first.height) as usize * channels);
        if channels == 3 {
            let d = apart(rgb(&page.pixels), (base.page.width, base.page.height), rgb(&first.pixels), (first.width, first.height));
            assert!(d < 6.0, "{look:?}: the first picture is {d:.1} grey levels from the preview");
        }
    }
}

/// With a copy the photo is not decoded; without one, or with one of another shape, it is, and
/// the picture has the same size either way. The stages are named, in order.
#[test]
fn a_small_copy_saves_the_decoding_and_a_wrong_one_is_left_out() {
    let (jpeg, corners) = desk();
    let photo = Photo::decode(&jpeg).unwrap();
    let small = shrunk(&photo, 4);
    assert_eq!((small.width, small.height), (400, 500));
    let ask = clean(&jpeg, corners);
    let stages = |f: &crate::first::FirstPage| f.timings.iter().map(|t| t.0).collect::<Vec<_>>();

    let quick = first_page(&ask, Some(&small)).unwrap();
    assert!(quick.from_small);
    assert_eq!(stages(&quick), [STAGE_FIRST_HEADER, STAGE_FIRST_RECTIFY, STAGE_FIRST_LOOK]);

    let slow = first_page(&ask, None).unwrap();
    assert!(!slow.from_small);
    assert_eq!(stages(&slow), [STAGE_FIRST_HEADER, STAGE_FIRST_DECODE, STAGE_FIRST_RECTIFY, STAGE_FIRST_LOOK]);
    assert_eq!((slow.width, slow.height), (quick.width, quick.height));
    let d = apart(rgb(&slow.pixels), (slow.width, slow.height), rgb(&quick.pixels), (quick.width, quick.height));
    assert!(d < 6.0, "from the copy and from the photo are {d:.1} grey levels apart");

    // a copy lying on its side, one of another shape, and one whose bytes are not its size
    let sideways = Photo { width: small.height, height: small.width, rgb: small.rgb.clone() };
    let square = Photo { width: 400, height: 400, rgb: vec![200; 400 * 400 * 3] };
    let broken = Photo { width: 400, height: 500, rgb: vec![200; 100] };
    for wrong in [&sideways, &square, &broken] {
        let made = first_page(&ask, Some(wrong)).unwrap();
        assert!(!made.from_small);
        assert_eq!(rgb(&made.pixels), rgb(&slow.pixels));
    }
    // a copy that kept every eighth pixel, or every second, is the photo's shape too
    for by in [2, 8] {
        assert!(first_page(&ask, Some(&shrunk(&photo, by))).unwrap().from_small);
    }
}

/// The size asked for is followed, and a page smaller than it is not enlarged.
#[test]
fn the_size_is_the_one_asked_for_and_never_larger_than_the_page() {
    let (jpeg, corners) = desk();
    let small = shrunk(&Photo::decode(&jpeg).unwrap(), 4);
    let ask = clean(&jpeg, corners);
    let at = |side: u32| first_page(&PageRequest { max_side: Some(side), ..ask.clone() }, Some(&small)).unwrap();
    // a preview that large is the page at its size in the photo, not enlarged
    let whole = page_base(&PageRequest { max_side: Some(4096), ..ask.clone() }).unwrap();
    assert!(shaped((at(320).width, at(320).height), (whole.page.width, whole.page.height), 320));
    let large = at(4096);
    assert_eq!((large.width, large.height), (whole.page.width, whole.page.height));
}

/// Flattening, the classifier, the document type and the finger search are not run: asking for
/// them changes nothing, and the picture is the one made without them.
#[test]
fn what_the_first_picture_leaves_out_changes_nothing() {
    let (jpeg, corners) = desk();
    let small = shrunk(&Photo::decode(&jpeg).unwrap(), 4);
    let plain = first_page(&clean(&jpeg, corners), Some(&small)).unwrap();
    let asked = PageRequest {
        flatten: true,
        flatten_check: true,
        flatten_trim: true,
        line_flatten: true,
        skip_flat: true,
        upright: true,
        preset: PresetChoice::Auto,
        kind_tone: true,
        photo_sharpen: true,
        remove_fingers: true,
        ..clean(&jpeg, corners)
    };
    let full = first_page(&asked, Some(&small)).unwrap();
    assert_eq!(rgb(&full.pixels), rgb(&plain.pixels));
    assert_eq!((full.width, full.height, full.turns, full.paper), (plain.width, plain.height, 0, plain.paper));
    assert!(full.timings.iter().all(|t| t.0.starts_with("scan_first_")), "{:?}", full.timings);
}

/// The person's own turn is in the picture; the look's strength and the paper choice are followed.
#[test]
fn the_persons_turn_and_the_look_are_followed() {
    let (jpeg, corners) = desk();
    let small = shrunk(&Photo::decode(&jpeg).unwrap(), 4);
    let ask = clean(&jpeg, corners);
    let upright = first_page(&ask, Some(&small)).unwrap();
    let quarter = first_page(&PageRequest { turns: 1, ..ask.clone() }, Some(&small)).unwrap();
    assert_eq!((quarter.width, quarter.height, quarter.turns), (upright.height, upright.width, 1));
    // the look is made of the turned page, so the bytes are near, not the same
    let page = crate::Rgb { width: upright.width, height: upright.height, data: rgb(&upright.pixels).to_vec() };
    let d = apart(&crate::turned(&page, 1).data, (quarter.width, quarter.height), rgb(&quarter.pixels), (quarter.width, quarter.height));
    assert!(d < 2.0, "turned after and before the look are {d:.1} grey levels apart");
    // and in the Original look, which changes no pixel, exactly the same
    let plain = PageRequest { look: Look::Original, ..ask.clone() };
    let (a, b) = (first_page(&plain, Some(&small)).unwrap(), first_page(&PageRequest { turns: 3, ..plain.clone() }, Some(&small)).unwrap());
    let page = crate::Rgb { width: a.width, height: a.height, data: rgb(&a.pixels).to_vec() };
    assert_eq!(rgb(&b.pixels), crate::turned(&page, 3).data);
    let over = first_page(&PageRequest { turns: 2, ..ask.clone() }, Some(&small)).unwrap();
    assert_eq!((over.width, over.height, over.turns), (upright.width, upright.height, 2));

    let weak = first_page(&PageRequest { strength: 10, ..ask.clone() }, Some(&small)).unwrap();
    assert_ne!(rgb(&weak.pixels), rgb(&upright.pixels));
    let kept = first_page(&PageRequest { keep_paper: Some(true), ..ask.clone() }, Some(&small)).unwrap();
    assert_ne!(rgb(&kept.pixels), rgb(&upright.pixels));
}

/// The page's shape comes from the photo's own size and lens, as the page's does: with a focal
/// length in the file, and with the scan's paper given, the first picture's paper is the page's.
#[test]
fn the_shape_follows_the_photos_lens_and_the_scans_paper() {
    let (jpeg, corners) = desk();
    let with_lens = with_exif(&jpeg, &exif_block(true, 26, (1, 1)));
    assert_eq!(Photo::upright_size_with_camera(&with_lens).unwrap().2, Photo::decode_with_camera(&with_lens).unwrap().1);
    assert_eq!(Photo::upright_size_with_camera(&with_lens).unwrap().2.focal_35mm, Some(26.0));
    assert_eq!(Photo::upright_size_with_camera(&jpeg).unwrap(), (1600, 2000, crate::exif::Camera::default()));
    let small = shrunk(&Photo::decode(&jpeg).unwrap(), 4);
    for file in [&jpeg, &with_lens] {
        for hint in [None, Some(Paper::A4), Some(Paper::Letter)] {
            for sides in [false, true] {
                let ask = PageRequest { paper_hint: hint, side_proportions: sides, ..clean(file, corners) };
                let base = page_base(&PageRequest { max_side: Some(PREVIEW), ..ask.clone() }).unwrap();
                let first = first_page(&ask, Some(&small)).unwrap();
                assert_eq!(first.paper, base.paper, "{hint:?} {sides}");
                assert!(
                    shaped((first.width, first.height), (base.page.width, base.page.height), FIRST_SIDE),
                    "{hint:?} {sides}: {} x {}",
                    first.width,
                    first.height
                );
            }
        }
    }
}

/// Bad requests are refused as the page's are.
#[test]
fn bad_photos_and_corners_are_refused() {
    let (jpeg, corners) = desk();
    assert!(first_page(&clean(b"not a photo", corners), None).is_err());
    assert!(first_page(&clean(&jpeg, [0.5; 8]), None).is_err());
    assert!(first_page(&clean(&jpeg, [f32::NAN; 8]), None).is_err());
}

/// A copy made by `shrunk` averages each block and keeps the photo's shape.
#[test]
fn shrinking_averages_blocks() {
    let photo = Photo { width: 4, height: 2, rgb: [[0u8; 3], [100; 3], [10; 3], [30; 3], [200; 3], [100; 3], [20; 3], [40; 3]].concat() };
    let half = shrunk(&photo, 2);
    assert_eq!((half.width, half.height), (2, 1));
    assert_eq!(half.rgb, [100, 100, 100, 25, 25, 25]);
    assert_eq!(shrunk(&photo, 1), photo);
    assert_eq!(shrunk(&photo, 0), photo);
}
