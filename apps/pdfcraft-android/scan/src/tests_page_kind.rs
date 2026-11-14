//! Document types in the page pipeline (#299): the kind a straightened page is taken for, and
//! that its settings reach the Clean scan look. Pages are drawn in code or come from
//! `tests/data/` (drawn by `tools/scan-eval/text_pages.py`).

use crate::aspect::{AspectSource, Paper};
use crate::look::{Look, Pixels};
use crate::page::{PageBase, PageRequest, kind_sharpen, page_look, page_look_kind};
use crate::page_kind::{Curve, PresetChoice, page_features, page_kind, preset_from_id};
use crate::preset::{Preset, params};
use crate::warp::Rgb;

fn drawn(png: &[u8]) -> Rgb {
    let picture = image::load_from_memory(png).unwrap().to_rgb8();
    Rgb { width: picture.width(), height: picture.height(), data: picture.into_raw() }
}

/// A slip of paper three times as long as it is wide, with rows of thin grey print.
fn receipt() -> Rgb {
    receipt_printed(90)
}

/// A receipt whose print has the grey level `ink`.
fn receipt_printed(ink: u8) -> Rgb {
    let (w, h) = (400u32, 1200u32);
    let mut data = Vec::with_capacity((w * h * 3) as usize);
    for y in 0..h {
        for x in 0..w {
            let print = (y % 40) < 4 && (40..360).contains(&x) && (x / 6) % 3 != 0;
            let v: u8 = if print { ink } else { 238 };
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
        flatten_check: None,
        trim: None,
        line_flatten: None,
        flatten_decision: None,
        flatten_reused: false,
        timings: Vec::new(),
        measured: std::sync::OnceLock::new(),
        look_kept: crate::enhance::Prepared::new(),
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
    let kind = page_kind(&page.page, page.dpi, page.paper, Curve::default(), PresetChoice::Auto).unwrap();
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
    let kind = page_kind(&english, dpi, Paper::A4, Curve::default(), PresetChoice::Auto).unwrap();
    assert_eq!(kind.preset, Preset::Document, "{:?}", kind.features);
    assert!(kind.features.photo_share < 0.15 && !kind.whole_photo, "{:?}", kind.features);
    assert!((kind.features.short_mm.unwrap() - 210.0).abs() < 1.0);
    // two opposite curved sides make it a book page; one does not (#442)
    let opposite = Curve { sides: 2, opposite: true, book_mode: false };
    assert_eq!(page_kind(&english, dpi, Paper::A4, opposite, PresetChoice::Auto).unwrap().preset, Preset::BookPage);
    assert_eq!(page_kind(&english, dpi, Paper::A4, Curve::sides(1), PresetChoice::Auto).unwrap().preset, Preset::Document);
    assert_eq!(page_kind(&english, dpi, Paper::IdCard, Curve::default(), PresetChoice::Auto).unwrap().preset, Preset::IdCard);
    // the same page gives the same features every time
    assert_eq!(page_features(&english, dpi, Paper::A4, Curve::default()), page_features(&english, dpi, Paper::A4, Curve::default()));
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
    let kind = page_kind(&picture_page(), 150, Paper::Custom, Curve::default(), PresetChoice::Auto).unwrap();
    assert_eq!(kind.preset, Preset::Photo, "{:?}", kind.features);
    assert!(kind.whole_photo);
}

#[test]
fn a_page_too_small_or_malformed_has_no_kind() {
    let bad = Rgb { width: 10, height: 10, data: vec![0; 5] };
    assert!(page_kind(&bad, 200, Paper::Custom, Curve::default(), PresetChoice::Auto).is_none());
    let empty = Rgb { width: 0, height: 0, data: Vec::new() };
    assert!(page_kind(&empty, 200, Paper::Custom, Curve::default(), PresetChoice::Auto).is_none());
}

/// The mean luminance of `pixels` where `print` marks the page's print.
fn ink_level(pixels: &[u8], print: &[bool]) -> f64 {
    let (sum, n) = pixels
        .as_chunks::<3>()
        .0
        .iter()
        .zip(print)
        .filter(|(_, p)| **p)
        .fold((0.0, 0usize), |(s, n), (px, _)| (s + f64::from(crate::luma(*px)), n + 1));
    sum / n.max(1) as f64
}

#[test]
fn a_receipts_extra_gamma_darkens_its_faint_print_when_the_kinds_tone_is_asked_for() {
    // #299, section 5: a receipt's thermal print is faint, and its kind adds 0.3 to the gamma
    let page = base(receipt_printed(170), 200, Paper::Custom);
    let print: Vec<bool> = page.page.data.as_chunks::<3>().0.iter().map(|p| p[0] < 200).collect();
    let (plain, kind) = page_look_kind(&page, &request(PresetChoice::Auto)).unwrap();
    assert_eq!(kind.map(|k| k.preset), Some(Preset::Receipt));
    let (toned, _) = page_look_kind(&page, &PageRequest { kind_tone: true, ..request(PresetChoice::Auto) }).unwrap();
    let (plain, toned) = (rgb(plain.pixels), rgb(toned.pixels));
    let (before, after) = (ink_level(&plain, &print), ink_level(&toned, &print));
    println!("receipt print: {before:.1} without the kind's tone, {after:.1} with it");
    assert!(after < before - 2.0, "{before} -> {after}");
    // a document's shape changes nothing: the kind's tone is the same page as before
    let english = drawn(include_bytes!("../tests/data/upright-english.png"));
    let doc = base(english, 81, Paper::A4);
    let a = page_look(&doc, &request(PresetChoice::Auto)).unwrap();
    let b = page_look(&doc, &PageRequest { kind_tone: true, ..request(PresetChoice::Auto) }).unwrap();
    assert!(rgb(a.pixels) == rgb(b.pixels), "a document has no tone of its own");
}

#[test]
fn a_whiteboard_gets_its_s_curve_and_stronger_marker_colour() {
    // the S-curve (Zhang and He) keeps 0 and 1, rises steadily, and is steeper than the line
    // in the middle
    let s = crate::enhance::s_curve_for_tests;
    assert!(s(0.0).abs() < 1e-6 && (s(1.0) - 1.0).abs() < 1e-6);
    assert!((0..100).all(|i| s(i as f32 / 100.0) <= s((i + 1) as f32 / 100.0)));
    assert!(s(0.6) - s(0.4) > 0.2);
    // a pale board with thick blue marker
    let (w, h) = (600u32, 450u32);
    let mut data = Vec::with_capacity((w * h * 3) as usize);
    for y in 0..h {
        for x in 0..w {
            let stroke = (y % 60) < 10 && (60..540).contains(&x) && (x / 30) % 4 != 0;
            data.extend(if stroke { [70, 90, 190] } else { [225, 228, 226] });
        }
    }
    let page = base(Rgb { width: w, height: h, data }, 100, Paper::Custom);
    let print: Vec<bool> = page.page.data.as_chunks::<3>().0.iter().map(|p| i32::from(p[2]) > i32::from(p[0]) + 50).collect();
    let ask = request(PresetChoice::Fixed(Preset::Whiteboard));
    let plain = rgb(page_look(&page, &ask).unwrap().pixels);
    let toned = rgb(page_look(&page, &PageRequest { kind_tone: true, ..ask }).unwrap().pixels);
    let chroma = |v: &[u8]| {
        let (s, n) = v
            .as_chunks::<3>()
            .0
            .iter()
            .zip(&print)
            .filter(|(_, p)| **p)
            .fold((0.0, 0usize), |(s, n), (px, _)| (s + f64::from(px.iter().max().unwrap() - px.iter().min().unwrap()), n + 1));
        s / n.max(1) as f64
    };
    println!("marker colour: {:.1} without the kind's tone, {:.1} with it", chroma(&plain), chroma(&toned));
    assert!(plain != toned);
    assert!(chroma(&toned) > chroma(&plain) + 5.0);
}

#[test]
fn photographs_on_a_page_are_sharpened_gently_and_print_as_before() {
    // #299, section 4: inside the block photo mask, sharpening is 30 % of what print gets
    // the picture over the top 40 % of the page, rows of print under it
    let mut drawn = picture_page();
    let w = drawn.width as usize;
    for y in 384..960 {
        for x in 0..w {
            let print = y >= 420 && (y % 24) < 7 && (60..660).contains(&x) && (x / 9) % 4 != 0;
            let v = if print { 25 } else { 245 };
            drawn.data[(y * w + x) * 3..(y * w + x) * 3 + 3].copy_from_slice(&[v, v, v]);
        }
    }
    let page = base(drawn, 150, Paper::Custom);
    let ask = PageRequest { sharpen: 100, preset: PresetChoice::Fixed(Preset::PhotoRich), fixed_strength: true, ..request(PresetChoice::Off) };
    let (sharp, kind) = page_look_kind(&page, &ask).unwrap();
    let photos = kind.as_ref().and_then(|k| k.photos.as_ref()).expect("the page has a photo mask");
    println!("photo share {:.2}, whole page {}", photos.share, photos.whole_page);
    assert!(!photos.whole_page && photos.share > 0.2, "{} {}", photos.share, photos.whole_page);
    let (gentle, _) = page_look_kind(&page, &PageRequest { photo_sharpen: true, ..ask }).unwrap();
    let unsharp = rgb(page_look(&page, &PageRequest { sharpen: 0, ..ask }).unwrap().pixels);
    let (sharp, gentle) = (rgb(sharp.pixels), rgb(gentle.pixels));
    // how far sharpening moved the picture (rows 40..300) and the print under it (rows 500..950, clear of the mask's soft edge)
    let moved = |v: &[u8], rows: std::ops::Range<usize>| {
        rows.flat_map(|y| (0..w * 3).map(move |i| y * w * 3 + i)).map(|i| f64::from(v[i].abs_diff(unsharp[i]))).sum::<f64>()
    };
    let (picture_full, picture_gentle) = (moved(&sharp, 40..300), moved(&gentle, 40..300));
    println!("sharpening moved the picture by {picture_full:.0} without the mask, {picture_gentle:.0} with it");
    assert!(picture_gentle < picture_full * 0.6, "{picture_gentle} against {picture_full}");
    assert_eq!(moved(&sharp, 500..950), moved(&gentle, 500..950), "the print is sharpened as before");
}

/// The drawn A4 page with a flat dark band down its left side: what the first gutter rule took
/// for a book's gutter (#427).
fn english_with_band() -> (Rgb, u16) {
    let mut page = drawn(include_bytes!("../tests/data/upright-english.png"));
    let dpi = (f64::from(page.width.min(page.height)) / 210.0 * 25.4).round() as u16;
    let band = (f64::from(page.width) * 0.06).round() as u32;
    for y in 0..page.height {
        for x in 0..band {
            let i = ((y * page.width + x) * 3) as usize;
            page.data[i..i + 3].copy_from_slice(&[120, 120, 120]);
        }
    }
    (page, dpi)
}

#[test]
fn a_flat_dark_band_is_a_book_page_only_under_the_first_gutter_rule() {
    let (page, dpi) = english_with_band();
    let page = base(page, dpi, Paper::A4);
    let strict = request(PresetChoice::Auto);
    let loose = PageRequest { strict_gutter: false, ..request(PresetChoice::Auto) };
    let (_, with_strict) = page_look_kind(&page, &strict).unwrap();
    let (_, with_loose) = page_look_kind(&page, &loose).unwrap();
    let (with_strict, with_loose) = (with_strict.unwrap(), with_loose.unwrap());
    println!("strict: {:?} {:?}\nloose: {:?}", with_strict.preset, with_strict.features, with_loose.preset);
    assert_eq!(with_strict.preset, Preset::Document);
    assert_eq!(with_loose.preset, Preset::BookPage);
    // both rules' readings are in the features, whichever is in force
    assert!(with_strict.features.gutter_loose && !with_strict.features.gutter_band);
    assert!(with_loose.features.gutter_loose && with_loose.features.gutter_band);
    // a curved side with the strict gutter band is a book page (#442); the band here is not one
    let curved = PageRequest { curved_sides: 1, ..request(PresetChoice::Auto) };
    assert_eq!(page_look_kind(&page, &curved).unwrap().1.unwrap().preset, Preset::Document);
    let curved_loose = PageRequest { curved_sides: 1, strict_gutter: false, ..request(PresetChoice::Auto) };
    assert_eq!(page_look_kind(&page, &curved_loose).unwrap().1.unwrap().preset, Preset::BookPage);
}

/// #442: a plain sheet whose edge detection found one curved side (held in the hand, curling at
/// one edge), with no gutter, is a Document and still gets the Flatten suggestion. Two opposite
/// curved sides, or one in book mode, make it a book page; the first rule took any curved side.
#[test]
fn one_curved_side_on_a_plain_sheet_suggests_flattening_but_is_not_a_book_page() {
    let english = drawn(include_bytes!("../tests/data/upright-english.png"));
    let dpi = (f64::from(english.width.min(english.height)) / 210.0 * 25.4).round() as u16;
    let page = base(english, dpi, Paper::A4);
    let kind = |r: PageRequest<'static>| page_look_kind(&page, &r).unwrap().1.unwrap();

    let flat = kind(request(PresetChoice::Auto));
    assert_eq!(flat.preset, Preset::Document);
    assert!(!flat.params.suggest_flatten && !flat.book_loose);

    let one = kind(PageRequest { curved_sides: 1, ..request(PresetChoice::Auto) });
    println!("one curved side: {:?}, suggest_flatten {}, book_loose {}", one.preset, one.params.suggest_flatten, one.book_loose);
    assert_eq!(one.preset, Preset::Document);
    assert!(one.params.suggest_flatten, "a curved side still suggests flattening");
    assert!(one.book_loose && !one.features.gutter_band, "the first rule would have called it a book page");
    // the kind's other settings are the Document's: only the suggestion changes
    assert_eq!(one.params.strength, flat.params.strength);

    let opposite = kind(PageRequest { curved_sides: 2, curved_opposite: true, ..request(PresetChoice::Auto) });
    assert_eq!(opposite.preset, Preset::BookPage);
    let book = kind(PageRequest { curved_sides: 1, book_mode: true, ..request(PresetChoice::Auto) });
    assert_eq!(book.preset, Preset::BookPage);
    let adjacent = kind(PageRequest { curved_sides: 2, ..request(PresetChoice::Auto) });
    assert_eq!((adjacent.preset, adjacent.params.suggest_flatten), (Preset::Document, true));

    // the testing option puts the first rule back exactly: book page, its own suggestion
    let first = kind(PageRequest { curved_sides: 1, strict_book: false, ..request(PresetChoice::Auto) });
    assert_eq!((first.preset, first.params.suggest_flatten), (Preset::BookPage, true));
    let first_flat = kind(PageRequest { strict_book: false, ..request(PresetChoice::Auto) });
    assert_eq!(first_flat, flat);

    // a kind the person chose keeps its own name, and a curved side still suggests flattening
    let chosen = kind(PageRequest { curved_sides: 1, ..request(PresetChoice::Fixed(Preset::Receipt)) });
    assert_eq!((chosen.preset, chosen.params.suggest_flatten), (Preset::Receipt, true));
}

/// A label as a small photo shows it: a few lines of print and a large barcode whose thin bars
/// have gone grey, with paper between them (#427: on the reference photos the first photo rule
/// read such a label as 15 % photograph, a hair over the photo-rich limit).
fn label_with_barcode() -> Rgb {
    let (w, h) = (720usize, 960usize);
    let mut data = vec![245u8; w * h * 3];
    let mut put = |x: usize, y: usize, v: u8| data[(y * w + x) * 3..(y * w + x) * 3 + 3].copy_from_slice(&[v, v, v]);
    // print: lines of words
    for y in (60..300).filter(|y| y % 24 < 6) {
        for x in (60..660).filter(|x| (x / 11) % 4 != 0) {
            put(x, y, 30);
        }
    }
    // the barcode, a quarter of the page: bars 1 or 2 pixels wide, 5 to 7 apart
    let mut x = 120usize;
    let mut k = 0usize;
    while x < 600 {
        let bar = 1 + k % 2;
        for y in 400..800 {
            for dx in 0..bar {
                put(x + dx, y, 60);
            }
        }
        x += bar + 5 + (k * 7) % 3;
        k += 1;
    }
    // the photo's softness: a 3 by 3 mean
    let src = data.clone();
    for y in 1..h - 1 {
        for x in 1..w - 1 {
            let mut sum = 0u32;
            for dy in 0..3 {
                for dx in 0..3 {
                    sum += u32::from(src[((y + dy - 1) * w + x + dx - 1) * 3]);
                }
            }
            let v = (sum / 9) as u8;
            data[(y * w + x) * 3..(y * w + x) * 3 + 3].copy_from_slice(&[v, v, v]);
        }
    }
    Rgb { width: w as u32, height: h as u32, data }
}

#[test]
fn a_label_with_a_barcode_is_photo_rich_only_under_the_first_photo_rule() {
    let page = base(label_with_barcode(), 150, Paper::Custom);
    let strict = request(PresetChoice::Auto);
    let loose = PageRequest { strict_photo: false, ..request(PresetChoice::Auto) };
    let (_, with_strict) = page_look_kind(&page, &strict).unwrap();
    let (_, with_loose) = page_look_kind(&page, &loose).unwrap();
    let (with_strict, with_loose) = (with_strict.unwrap(), with_loose.unwrap());
    println!("in force: {:?} {:?}\nfirst rule: {:?} {:?}", with_strict.preset, with_strict.features, with_loose.preset, with_loose.features);
    assert_eq!(with_strict.preset, Preset::Document);
    assert_eq!(with_loose.preset, Preset::PhotoRich);
    // both rules' readings are in the features, whichever is in force
    assert!(with_strict.features.photo_share < 0.01 && with_strict.features.photo_loose > 0.15, "{:?}", with_strict.features);
    assert_eq!(with_loose.features.photo_share, with_loose.features.photo_loose);
    assert_eq!(with_strict.features.photo_loose, with_loose.features.photo_loose);
    // the page was measured once for both
    assert!(page.measured.get().is_some_and(|m| m.is_some()));
    // and a page that is a photograph is one under both rules
    let picture = base(picture_page(), 150, Paper::Custom);
    for rule in [true, false] {
        let (_, kind) = page_look_kind(&picture, &PageRequest { strict_photo: rule, ..request(PresetChoice::Auto) }).unwrap();
        let kind = kind.unwrap();
        assert_eq!(kind.preset, Preset::Photo, "{:?}", kind.features);
        assert!(kind.whole_photo);
    }
}

#[test]
fn a_page_base_measures_its_page_once_and_every_look_gets_the_same_kind() {
    let (page, dpi) = english_with_band();
    let fresh = base(page.clone(), dpi, Paper::A4);
    assert!(fresh.measured.get().is_none());
    let (first, kind) = page_look_kind(&fresh, &request(PresetChoice::Auto)).unwrap();
    // kept after the first look
    assert!(fresh.measured.get().is_some_and(|m| m.is_some()));
    let kept = fresh.measured.get().cloned();
    // a second look, a strength change and the other rule reuse it
    let (again, kind_again) = page_look_kind(&fresh, &request(PresetChoice::Auto)).unwrap();
    let _ = page_look_kind(&fresh, &PageRequest { strength: 20, strict_gutter: false, ..request(PresetChoice::Auto) }).unwrap();
    assert_eq!(fresh.measured.get().cloned(), kept);
    assert_eq!(rgb(first.pixels), rgb(again.pixels.clone()));
    assert_eq!(kind, kind_again);
    // and what it kept is what a page measured afresh gives
    assert_eq!(kind.unwrap(), page_kind(&page, dpi, Paper::A4, Curve::default(), PresetChoice::Auto).unwrap());
    let unmeasured = base(page, dpi, Paper::A4);
    assert_eq!(rgb(page_look_kind(&unmeasured, &request(PresetChoice::Auto)).unwrap().0.pixels), rgb(again.pixels));
    // presets off and the other looks do not measure at all
    let off = base(Rgb { width: 4, height: 4, data: vec![200; 48] }, 200, Paper::Custom);
    let _ = page_look_kind(&off, &request(PresetChoice::Off)).unwrap();
    let _ = page_look_kind(&off, &PageRequest { look: Look::Colour, ..request(PresetChoice::Auto) }).unwrap();
    assert!(off.measured.get().is_none());
}

#[test]
fn with_document_types_on_a_strength_set_on_the_page_changes_the_page() {
    // #424: the slider's strength counts as set by hand, so 20 and 100 give different pages
    let page = base(receipt(), 200, Paper::Custom);
    let at = |strength: u8, fixed_strength: bool| {
        let (made, found) = page_look_kind(&page, &PageRequest { strength, fixed_strength, ..request(PresetChoice::Auto) }).unwrap();
        assert_eq!(found.map(|k| k.preset), Some(Preset::Receipt));
        rgb(made.pixels)
    };
    assert!(at(20, true) != at(100, true), "the page's strength did nothing with document types on");
    // a page with no strength of its own still gets its kind's, whatever the look's own level is
    assert!(at(20, false) == at(100, false));
    let receipt = params(Preset::Receipt);
    let by_hand = page_look(&page, &PageRequest { strength: receipt.strength, sharpen: receipt.sharpen, ..request(PresetChoice::Off) }).unwrap();
    assert!(at(20, false) == rgb(by_hand.pixels));
}

#[test]
fn the_sharpening_option_scales_the_kinds_sharpening() {
    // #424: before, 30, 60 and 100 all gave the kind's own sharpening
    assert_eq!(kind_sharpen(40, 30), 40);
    assert_eq!(kind_sharpen(40, 0), 0);
    assert_eq!(kind_sharpen(40, 60), 80);
    assert_eq!(kind_sharpen(40, 100), 100);
    assert_eq!(kind_sharpen(100, 100), 100);
    // a printed page, whose letters' edges sharpening can move
    let english = drawn(include_bytes!("../tests/data/upright-english.png"));
    let dpi = (f64::from(english.width.min(english.height)) / 210.0 * 25.4).round() as u16;
    let page = base(english, dpi, Paper::A4);
    let at = |sharpen: u8| rgb(page_look_kind(&page, &PageRequest { sharpen, ..request(PresetChoice::Auto) }).unwrap().0.pixels);
    let (none, default, more) = (at(0), at(30), at(60));
    assert!(none != default, "sharpening did nothing on this page");
    assert!(default != more, "sharpening 60 made the same page as 30");
}
