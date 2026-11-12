//! Document types in the page pipeline (#299): the kind a straightened page is taken for, and
//! that its settings reach the Clean scan look. Pages are drawn in code or come from
//! `tests/data/` (drawn by `tools/scan-eval/text_pages.py`).

use crate::aspect::{AspectSource, Paper};
use crate::look::{Look, Pixels};
use crate::page::{PageBase, PageRequest, page_look, page_look_kind};
use crate::page_kind::{PresetChoice, page_features, page_kind, preset_from_id};
use crate::preset::{Preset, params};
use crate::warp::Rgb;

fn drawn(png: &[u8]) -> Rgb {
    let picture = image::load_from_memory(png).unwrap().to_rgb8();
    Rgb { width: picture.width(), height: picture.height(), data: picture.into_raw() }
}

/// A slip of paper three times as long as it is wide, with rows of thin grey print.
fn receipt() -> Rgb {
    let (w, h) = (400u32, 1200u32);
    let mut data = Vec::with_capacity((w * h * 3) as usize);
    for y in 0..h {
        for x in 0..w {
            let print = (y % 40) < 4 && (40..360).contains(&x) && (x / 6) % 3 != 0;
            let v: u8 = if print { 90 } else { 238 };
            data.extend([v, v, v.saturating_sub(6)]);
        }
    }
    Rgb { width: w, height: h, data }
}

fn base(page: Rgb, dpi: u16, paper: Paper) -> PageBase {
    PageBase {
        page,
        dpi,
        paper,
        aspect: AspectSource::SideLengths,
        focal_mismatch: false,
        flattened: false,
        bend: 0.0,
        quarter_turns: 0,
        orientation: None,
        turns: 0,
        timings: Vec::new(),
    }
}

fn request(preset: PresetChoice) -> PageRequest<'static> {
    PageRequest { look: Look::Auto, strength: 60, sharpen: 30, preset, ..PageRequest::new(&[], [0.0; 8]) }
}

fn rgb(pixels: Pixels) -> Vec<u8> {
    match pixels {
        Pixels::Rgb(v) => v,
        _ => panic!("the Auto look gives colour"),
    }
}

#[test]
fn every_preset_has_a_word_that_names_it() {
    for p in Preset::ALL {
        assert_eq!(preset_from_id(p.id()), Some(p));
    }
    assert_eq!(preset_from_id("magic"), None);
    assert_eq!(preset_from_id(""), None);
}

#[test]
fn a_long_narrow_slip_is_a_receipt_and_gets_the_receipts_settings() {
    let page = base(receipt(), 200, Paper::Custom);
    let kind = page_kind(&page.page, page.dpi, page.paper, 0, PresetChoice::Auto).unwrap();
    assert_eq!((kind.preset, kind.chosen), (Preset::Receipt, false));
    assert!((kind.features.ratio - 3.0).abs() < 1e-9, "{:?}", kind.features);
    assert_eq!(kind.params, params(Preset::Receipt));
    // the look is made with the receipt's strength and sharpening, not the request's
    let (made, found) = page_look_kind(&page, &request(PresetChoice::Auto)).unwrap();
    assert_eq!(found.map(|k| k.preset), Some(Preset::Receipt));
    let receipt = params(Preset::Receipt);
    let by_hand = page_look(&page, &PageRequest { strength: receipt.strength, sharpen: receipt.sharpen, ..request(PresetChoice::Off) }).unwrap();
    assert!(rgb(made.pixels) == rgb(by_hand.pixels), "the receipt's settings did not reach the look");
    let plain = page_look(&page, &request(PresetChoice::Off)).unwrap();
    let auto = page_look(&page, &request(PresetChoice::Auto)).unwrap();
    assert!(rgb(plain.pixels) != rgb(auto.pixels), "document types changed nothing");
}

#[test]
fn a_strength_set_by_hand_and_sharpening_switched_off_stay_as_they_are() {
    let page = base(receipt(), 200, Paper::Custom);
    let ask = PageRequest { fixed_strength: true, sharpen: 0, ..request(PresetChoice::Auto) };
    let (made, found) = page_look_kind(&page, &ask).unwrap();
    assert_eq!(found.map(|k| k.preset), Some(Preset::Receipt));
    let same = page_look(&page, &PageRequest { sharpen: 0, ..request(PresetChoice::Off) }).unwrap();
    assert!(rgb(made.pixels) == rgb(same.pixels));
}

#[test]
fn a_kind_the_person_chose_is_used_and_said_to_be_theirs() {
    let page = base(receipt(), 200, Paper::Custom);
    let (made, found) = page_look_kind(&page, &request(PresetChoice::Fixed(Preset::Whiteboard))).unwrap();
    let found = found.unwrap();
    assert_eq!((found.preset, found.chosen), (Preset::Whiteboard, true));
    let photo = params(Preset::Whiteboard);
    let by_hand = page_look(&page, &PageRequest { strength: photo.strength, sharpen: photo.sharpen, ..request(PresetChoice::Off) }).unwrap();
    assert!(rgb(made.pixels) == rgb(by_hand.pixels));
    // a coloured form keeps its paper's colour: not the same page as the whitened one
    let mut yellow = receipt();
    yellow.data.as_chunks_mut::<3>().0.iter_mut().filter(|p| p[0] > 200).for_each(|p| *p = [240, 222, 160]);
    let page = base(yellow, 200, Paper::Custom);
    let (form, _) = page_look_kind(&page, &request(PresetChoice::Fixed(Preset::ColouredForm))).unwrap();
    let coloured = params(Preset::ColouredForm);
    let whitened = page_look(&page, &PageRequest { strength: coloured.strength, sharpen: coloured.sharpen, ..request(PresetChoice::Off) }).unwrap();
    assert!(rgb(form.pixels) != rgb(whitened.pixels), "Coloured form did not keep the paper");
}

#[test]
fn only_the_clean_scan_look_has_a_kind_and_off_is_as_before() {
    let page = base(receipt(), 200, Paper::Custom);
    for look in [Look::Original, Look::Colour, Look::Greyscale, Look::BlackWhite] {
        let (_, found) = page_look_kind(&page, &PageRequest { look, ..request(PresetChoice::Auto) }).unwrap();
        assert!(found.is_none(), "{look:?}");
    }
    assert!(page_look_kind(&page, &request(PresetChoice::Off)).unwrap().1.is_none());
}

#[test]
fn a_printed_a4_page_is_a_document_and_a_card_sized_one_an_id_card() {
    let english = drawn(include_bytes!("../tests/data/upright-english.png"));
    let dpi = (f64::from(english.width.min(english.height)) / 210.0 * 25.4).round() as u16;
    let kind = page_kind(&english, dpi, Paper::A4, 0, PresetChoice::Auto).unwrap();
    assert_eq!(kind.preset, Preset::Document, "{:?}", kind.features);
    assert!(kind.features.photo_share < 0.15 && !kind.whole_photo, "{:?}", kind.features);
    assert!((kind.features.short_mm.unwrap() - 210.0).abs() < 1.0);
    // two curved sides make it a book page
    assert_eq!(page_kind(&english, dpi, Paper::A4, 2, PresetChoice::Auto).unwrap().preset, Preset::BookPage);
    assert_eq!(page_kind(&english, dpi, Paper::IdCard, 0, PresetChoice::Auto).unwrap().preset, Preset::IdCard);
    // the same page gives the same features every time
    assert_eq!(page_features(&english, dpi, Paper::A4, 0), page_features(&english, dpi, Paper::A4, 0));
}

/// A smooth random picture over the top nine tenths of a white page, mid tones only (as in
/// `tests_photo_mask.rs`), with a strip of black print under it.
fn picture_page() -> Rgb {
    const STEP: usize = 16;
    let (w, h) = (720usize, 960usize);
    let mut seed = 13u64;
    let mut next = || {
        seed = seed.wrapping_mul(6_364_136_223_846_793_005).wrapping_add(1_442_695_040_888_963_407);
        (seed >> 33) as f32 / (1u64 << 31) as f32
    };
    let (nx, ny) = (w / STEP + 2, h / STEP + 2);
    let grid: Vec<f32> = (0..nx * ny).map(|_| next()).collect();
    let mut data = vec![250u8; w * h * 3];
    for y in 0..h {
        for x in 0..w {
            let v = if y < 864 {
                let (gx, gy, fx, fy) = (x / STEP, y / STEP, (x % STEP) as f32 / STEP as f32, (y % STEP) as f32 / STEP as f32);
                let g = |c: usize, r: usize| grid[r * nx + c];
                let t = (g(gx, gy) * (1.0 - fx) + g(gx + 1, gy) * fx) * (1.0 - fy) + (g(gx, gy + 1) * (1.0 - fx) + g(gx + 1, gy + 1) * fx) * fy;
                (80.0 + 140.0 * t).round() as u8
            } else if (880..950).contains(&y) && (60..660).contains(&x) && (y % 16) < 6 && (x / 9) % 4 != 0 {
                20
            } else {
                250
            };
            data[(y * w + x) * 3..(y * w + x) * 3 + 3].copy_from_slice(&[v, v, v]);
        }
    }
    Rgb { width: w as u32, height: h as u32, data }
}

#[test]
fn a_page_that_is_mostly_a_photograph_is_a_photo() {
    let kind = page_kind(&picture_page(), 150, Paper::Custom, 0, PresetChoice::Auto).unwrap();
    assert_eq!(kind.preset, Preset::Photo, "{:?}", kind.features);
    assert!(kind.whole_photo);
}

#[test]
fn a_page_too_small_or_malformed_has_no_kind() {
    let bad = Rgb { width: 10, height: 10, data: vec![0; 5] };
    assert!(page_kind(&bad, 200, Paper::Custom, 0, PresetChoice::Auto).is_none());
    let empty = Rgb { width: 0, height: 0, data: Vec::new() };
    assert!(page_kind(&empty, 200, Paper::Custom, 0, PresetChoice::Auto).is_none());
}
