//! Tests for the document-type presets (issue #299). Pages and features are built in code.

use super::preset::{
    InkFeatures, PageFeatures, Preset, PresetParams, classify, classify_with, curve_suggests_flatten, gutter_band, gutter_reading, ink_features,
    params,
};
use crate::{Luma, Rgb};

/// A plain A4 page with nothing on it: every rule's measurement at its neutral value.
fn a4_document() -> PageFeatures {
    PageFeatures {
        ratio: 1.41,
        short_mm: Some(210.0),
        id_card: false,
        ink_share: 0.10,
        colour_ink_share: 0.0,
        photo_share: 0.02,
        photo_loose: 0.02,
        stroke_mm: Some(0.5),
        curved_sides: 0,
        curved_opposite: false,
        book_mode: false,
        gutter_band: false,
        gutter_loose: false,
        gutter_cover: 0.0,
        bright_share: 0.0,
    }
}

/// A white RGB page of `w` × `h` pixels.
fn white_page(w: u32, h: u32) -> Rgb {
    let n = (w * h) as usize;
    Rgb { width: w, height: h, data: vec![255; n * 3] }
}

/// Paints the pixels with `x0 <= x < x1` and `y0 <= y < y1` to `colour`.
fn paint(page: &mut Rgb, (x0, y0, x1, y1): (u32, u32, u32, u32), colour: [u8; 3]) {
    for y in y0..y1 {
        for x in x0..x1 {
            let i = ((y * page.width + x) * 3) as usize;
            page.data[i..i + 3].copy_from_slice(&colour);
        }
    }
}

/// A grey luminance page of `w` × `h` pixels, every pixel `level`.
fn grey_page(w: u32, h: u32, level: u8) -> Luma {
    Luma { width: w, height: h, data: vec![level; (w * h) as usize] }
}

/// Paints a rectangle of a luminance page to `level`.
fn paint_grey(page: &mut Luma, (x0, y0, x1, y1): (u32, u32, u32, u32), level: u8) {
    for y in y0..y1 {
        for x in x0..x1 {
            let i = (y * page.width + x) as usize;
            page.data[i] = level;
        }
    }
}

/// A small seeded generator (xorshift64), so the random feature sets are the same on every run.
struct Seeded(u64);

impl Seeded {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        self.0
    }

    /// A number in [0, 1).
    fn unit(&mut self) -> f64 {
        (self.next() >> 11) as f64 / (1u64 << 53) as f64
    }

    fn flag(&mut self) -> bool {
        self.next().is_multiple_of(2)
    }
}

#[test]
fn a_page_with_a_photo_share_over_0_6_is_a_photo() {
    let f = PageFeatures { photo_share: 0.61, ..a4_document() };
    println!("photo_share 0.61 -> {:?}", classify(&f));
    assert_eq!(classify(&f), Preset::Photo);
}

#[test]
fn a_photo_share_of_exactly_0_6_is_not_a_photo() {
    let f = PageFeatures { photo_share: 0.6, ..a4_document() };
    println!("photo_share 0.6 -> {:?}", classify(&f));
    assert_ne!(classify(&f), Preset::Photo);
    assert_eq!(classify(&f), Preset::PhotoRich);
}

#[test]
fn a_page_matched_to_an_id_card_size_is_an_id_card() {
    let f = PageFeatures { id_card: true, ratio: 1.59, short_mm: Some(54.0), ..a4_document() };
    println!("id_card -> {:?}", classify(&f));
    assert_eq!(classify(&f), Preset::IdCard);
}

#[test]
fn a_page_not_matched_to_an_id_card_size_is_not_an_id_card() {
    let f = PageFeatures { id_card: false, ratio: 1.59, short_mm: Some(54.0), ..a4_document() };
    println!("no id_card flag, ratio 1.59 and 54 mm short -> {:?}", classify(&f));
    assert_ne!(classify(&f), Preset::IdCard);
}

#[test]
fn a_ratio_over_2_2_is_a_receipt() {
    let f = PageFeatures { ratio: 2.21, short_mm: None, ..a4_document() };
    println!("ratio 2.21 -> {:?}", classify(&f));
    assert_eq!(classify(&f), Preset::Receipt);
}

#[test]
fn a_ratio_of_exactly_2_2_is_not_a_receipt() {
    let f = PageFeatures { ratio: 2.2, short_mm: None, ..a4_document() };
    println!("ratio 2.2 -> {:?}", classify(&f));
    assert_eq!(classify(&f), Preset::Document);
}

#[test]
fn a_short_side_under_90_mm_is_a_receipt() {
    let f = PageFeatures { ratio: 1.5, short_mm: Some(89.9), ..a4_document() };
    println!("short 89.9 mm -> {:?}", classify(&f));
    assert_eq!(classify(&f), Preset::Receipt);
}

#[test]
fn a_short_side_of_exactly_90_mm_is_not_a_receipt() {
    let f = PageFeatures { ratio: 1.5, short_mm: Some(90.0), ..a4_document() };
    println!("short 90.0 mm -> {:?}", classify(&f));
    assert_eq!(classify(&f), Preset::Document);
}

#[test]
fn thick_strokes_over_1_2_mm_with_little_ink_are_a_whiteboard() {
    let f = PageFeatures { stroke_mm: Some(1.21), ink_share: 0.059, ..a4_document() };
    println!("stroke 1.21 mm, ink 0.059 -> {:?}", classify(&f));
    assert_eq!(classify(&f), Preset::Whiteboard);
}

#[test]
fn a_stroke_of_exactly_1_2_mm_is_not_a_whiteboard() {
    let f = PageFeatures { stroke_mm: Some(1.2), ink_share: 0.05, ..a4_document() };
    println!("stroke 1.2 mm, ink 0.05 -> {:?}", classify(&f));
    assert_eq!(classify(&f), Preset::Document);
}

#[test]
fn an_ink_share_of_exactly_0_06_is_not_a_whiteboard() {
    let f = PageFeatures { stroke_mm: Some(1.5), ink_share: 0.06, ..a4_document() };
    println!("stroke 1.5 mm, ink 0.06 -> {:?}", classify(&f));
    assert_eq!(classify(&f), Preset::Document);
}

#[test]
fn a_missing_stroke_is_not_a_whiteboard() {
    let f = PageFeatures { stroke_mm: None, ink_share: 0.01, ..a4_document() };
    println!("no stroke, ink 0.01 -> {:?}", classify(&f));
    assert_eq!(classify(&f), Preset::Document);
}

#[test]
fn one_curved_side_is_a_document_that_suggests_flattening() {
    // #442: a sheet held in the hand, or curling at one edge, has one curved side and no gutter
    let f = PageFeatures { curved_sides: 1, ..a4_document() };
    println!("curved_sides 1 -> {:?} (first rule {:?})", classify(&f), classify_with(&f, false));
    assert_eq!(classify(&f), Preset::Document);
    assert!(curve_suggests_flatten(&f));
    // the first rule (#299), a testing choice, took it for a book
    assert_eq!(classify_with(&f, false), Preset::BookPage);
}

#[test]
fn two_curved_sides_are_a_book_page_only_when_opposite() {
    let adjacent = PageFeatures { curved_sides: 2, ..a4_document() };
    let opposite = PageFeatures { curved_sides: 2, curved_opposite: true, ..a4_document() };
    println!("two adjacent -> {:?}, two opposite -> {:?}", classify(&adjacent), classify(&opposite));
    assert_eq!(classify(&adjacent), Preset::Document);
    assert_eq!(classify(&opposite), Preset::BookPage);
    assert!(curve_suggests_flatten(&adjacent) && curve_suggests_flatten(&opposite));
}

#[test]
fn a_curved_side_with_the_gutter_or_in_book_mode_is_a_book_page() {
    let gutter = PageFeatures { curved_sides: 1, gutter_band: true, ..a4_document() };
    let book = PageFeatures { curved_sides: 1, book_mode: true, ..a4_document() };
    let flat_book = PageFeatures { curved_sides: 0, book_mode: true, ..a4_document() };
    println!(
        "curved + gutter -> {:?}, curved + book mode -> {:?}, flat in book mode -> {:?}",
        classify(&gutter),
        classify(&book),
        classify(&flat_book)
    );
    assert_eq!(classify(&gutter), Preset::BookPage);
    assert_eq!(classify(&book), Preset::BookPage);
    // book mode alone does not make a page curved
    assert_eq!(classify(&flat_book), Preset::Document);
    assert!(!curve_suggests_flatten(&flat_book));
}

#[test]
fn no_curved_side_and_no_gutter_is_not_a_book_page() {
    let f = PageFeatures { curved_sides: 0, gutter_band: false, ..a4_document() };
    println!("curved_sides 0, no gutter -> {:?}", classify(&f));
    assert_eq!(classify(&f), Preset::Document);
}

#[test]
fn a_gutter_band_is_a_book_page() {
    let f = PageFeatures { gutter_band: true, ..a4_document() };
    println!("gutter_band -> {:?}", classify(&f));
    assert_eq!(classify(&f), Preset::BookPage);
}

#[test]
fn a_photo_share_over_0_15_is_a_photo_rich_document() {
    let f = PageFeatures { photo_share: 0.16, ..a4_document() };
    println!("photo_share 0.16 -> {:?}", classify(&f));
    assert_eq!(classify(&f), Preset::PhotoRich);
}

#[test]
fn a_photo_share_of_exactly_0_15_is_a_document() {
    let f = PageFeatures { photo_share: 0.15, ..a4_document() };
    println!("photo_share 0.15 -> {:?}", classify(&f));
    assert_eq!(classify(&f), Preset::Document);
}

#[test]
fn a_plain_a4_page_is_a_document() {
    let f = a4_document();
    println!("plain A4 -> {:?}", classify(&f));
    assert_eq!(classify(&f), Preset::Document);
}

#[test]
fn non_finite_measurements_never_match_a_rule() {
    let nan_photo = PageFeatures { photo_share: f64::NAN, ..a4_document() };
    let inf_ratio = PageFeatures { ratio: f64::INFINITY, short_mm: Some(f64::NAN), ..a4_document() };
    let nan_stroke = PageFeatures { stroke_mm: Some(f64::INFINITY), ink_share: f64::NAN, ..a4_document() };
    println!("NaN photo, inf ratio, inf stroke -> {:?} {:?} {:?}", classify(&nan_photo), classify(&inf_ratio), classify(&nan_stroke));
    assert_eq!(classify(&nan_photo), Preset::Document);
    assert_eq!(classify(&inf_ratio), Preset::Document);
    assert_eq!(classify(&nan_stroke), Preset::Document);
}

#[test]
fn the_rules_are_checked_in_the_spec_order() {
    let photo_and_receipt = PageFeatures { photo_share: 0.7, ratio: 3.0, ..a4_document() };
    let id_and_receipt = PageFeatures { id_card: true, ratio: 3.0, ..a4_document() };
    let receipt_and_whiteboard = PageFeatures { ratio: 3.0, stroke_mm: Some(2.0), ink_share: 0.01, ..a4_document() };
    let whiteboard_and_book = PageFeatures { stroke_mm: Some(2.0), ink_share: 0.01, curved_sides: 2, ..a4_document() };
    let book_and_photo_rich = PageFeatures { curved_sides: 2, curved_opposite: true, photo_share: 0.2, ..a4_document() };
    let table = [
        (photo_and_receipt, Preset::Photo),
        (id_and_receipt, Preset::IdCard),
        (receipt_and_whiteboard, Preset::Receipt),
        (whiteboard_and_book, Preset::Whiteboard),
        (book_and_photo_rich, Preset::BookPage),
    ];
    for (features, expected) in table {
        println!("order: {:?} expected {:?}", classify(&features), expected);
        assert_eq!(classify(&features), expected);
    }
}

#[test]
fn coloured_form_never_comes_out_of_classify_for_10000_seeded_pages() {
    let mut rng = Seeded(0x5EED_0299);
    let mut counts = [0usize; 8];
    for _ in 0..10_000 {
        let mut f = PageFeatures {
            ratio: rng.unit() * 5.0,
            short_mm: if rng.flag() { Some(rng.unit() * 400.0) } else { None },
            id_card: rng.flag(),
            ink_share: rng.unit(),
            colour_ink_share: rng.unit(),
            photo_share: rng.unit() * 1.2,
            photo_loose: rng.unit() * 1.2,
            stroke_mm: if rng.flag() { Some(rng.unit() * 3.0) } else { None },
            curved_sides: (rng.next() % 4) as u8,
            curved_opposite: rng.flag(),
            book_mode: rng.flag(),
            gutter_band: rng.flag(),
            gutter_loose: rng.flag(),
            gutter_cover: rng.unit(),
            bright_share: rng.unit(),
        };
        if rng.next().is_multiple_of(50) {
            f.photo_share = f64::NAN;
        }
        if rng.next().is_multiple_of(50) {
            f.ratio = f64::INFINITY;
        }
        let preset = classify(&f);
        assert_ne!(preset, Preset::ColouredForm);
        if let Some(i) = Preset::ALL.iter().position(|p| *p == preset) {
            counts[i] += 1;
        }
    }
    println!("10000 seeded pages, counts per preset in ALL order: {counts:?}");
    assert_eq!(counts.iter().sum::<usize>(), 10_000);
}

#[test]
fn the_parameters_of_each_preset_equal_the_spec_table() {
    let document = PresetParams {
        strength: 60,
        sharpen: 30,
        keep_paper: false,
        suggest_flatten: false,
        suggest_greyscale: false,
        photo_mask: false,
        extra_gamma: 0.0,
        s_curve: false,
        colour_factor: 1.0,
    };
    let expected = [
        (Preset::Photo, PresetParams { strength: 25, sharpen: 15, keep_paper: true, ..document }),
        (Preset::IdCard, PresetParams { strength: 30, sharpen: 25, keep_paper: true, ..document }),
        (Preset::Receipt, PresetParams { strength: 80, sharpen: 35, extra_gamma: 0.3, suggest_greyscale: true, ..document }),
        (Preset::Whiteboard, PresetParams { strength: 90, sharpen: 20, s_curve: true, colour_factor: 1.5, ..document }),
        (Preset::BookPage, PresetParams { strength: 60, suggest_flatten: true, ..document }),
        (Preset::ColouredForm, PresetParams { strength: 55, keep_paper: true, ..document }),
        (Preset::PhotoRich, PresetParams { strength: 50, photo_mask: true, ..document }),
        (Preset::Document, document),
    ];
    for (preset, want) in expected {
        println!("{} -> {:?}", preset.id(), params(preset));
        assert_eq!(params(preset), want, "{preset:?}");
    }
}

#[test]
fn every_preset_id_is_different_lowercase_and_has_no_space() {
    let ids: Vec<&str> = Preset::ALL.iter().map(|p| p.id()).collect();
    println!("ids: {ids:?}");
    assert_eq!(Preset::ALL.len(), 8);
    for (i, id) in ids.iter().enumerate() {
        assert!(!id.is_empty());
        assert!(id.chars().all(|c| c.is_ascii_lowercase() || c == '_'), "{id}");
        assert!(!id.contains(' '));
        assert!(!ids[..i].contains(id), "{id} is used twice");
    }
}

#[test]
fn the_document_preset_has_the_spec_default_strength_and_sharpen() {
    let d = params(Preset::Document);
    println!("document strength {} sharpen {}", d.strength, d.sharpen);
    assert_eq!((d.strength, d.sharpen), (60, 30));
}

#[test]
fn a_thin_bar_three_pixels_thick_at_300_dpi_has_a_stroke_near_0_25_mm() {
    let mut page = white_page(300, 300);
    for x in (20..280).step_by(40) {
        paint(&mut page, (x, 0, x + 3, 300), [0, 0, 0]);
    }
    let ink = ink_features(&page, 300.0).unwrap();
    let stroke = ink.stroke_mm.unwrap();
    println!("vertical 3 px bars at 300 dpi: stroke {stroke:.4} mm (3 px = {:.4} mm)", 3.0 * 25.4 / 300.0);
    assert!((stroke - 0.254).abs() < 0.03, "{stroke}");
}

#[test]
fn a_horizontal_bar_three_pixels_thick_at_300_dpi_has_a_stroke_near_0_25_mm() {
    let mut page = white_page(300, 300);
    for y in (20..280).step_by(40) {
        paint(&mut page, (0, y, 300, y + 3), [0, 0, 0]);
    }
    let stroke = ink_features(&page, 300.0).unwrap().stroke_mm.unwrap();
    println!("horizontal 3 px bars at 300 dpi: stroke {stroke:.4} mm");
    assert!((stroke - 0.254).abs() < 0.03, "{stroke}");
}

#[test]
fn a_marker_bar_eighteen_pixels_thick_at_300_dpi_has_a_stroke_near_1_5_mm() {
    let mut page = white_page(400, 400);
    for x in (30..360).step_by(90) {
        paint(&mut page, (x, 0, x + 18, 400), [0, 0, 0]);
    }
    let stroke = ink_features(&page, 300.0).unwrap().stroke_mm.unwrap();
    println!("vertical 18 px bars at 300 dpi: stroke {stroke:.4} mm (18 px = {:.4} mm)", 18.0 * 25.4 / 300.0);
    assert!((stroke - 1.524).abs() < 0.1, "{stroke}");
}

#[test]
fn a_page_with_half_blue_ink_and_half_black_ink_has_a_colour_ink_share_of_one_half() {
    let mut page = white_page(200, 200);
    paint(&mut page, (0, 0, 100, 100), [0, 0, 255]);
    paint(&mut page, (100, 0, 200, 100), [0, 0, 0]);
    let ink = ink_features(&page, 300.0).unwrap();
    println!("blue + black: ink {:.4}, colour share of ink {:.4}", ink.ink_share, ink.colour_ink_share);
    assert!((ink.ink_share - 0.5).abs() < 1e-9);
    assert!((ink.colour_ink_share - 0.5).abs() < 0.02, "{}", ink.colour_ink_share);
}

#[test]
fn a_page_of_only_blue_ink_has_a_colour_ink_share_of_one() {
    let mut page = white_page(120, 120);
    paint(&mut page, (10, 10, 110, 110), [20, 40, 230]);
    let ink = ink_features(&page, 300.0).unwrap();
    println!("all blue: colour share of ink {:.4}", ink.colour_ink_share);
    assert!((ink.colour_ink_share - 1.0).abs() < 1e-9);
}

#[test]
fn a_blank_page_has_no_stroke_and_no_ink() {
    let page = white_page(150, 150);
    let ink: InkFeatures = ink_features(&page, 300.0).unwrap();
    println!("blank: ink {} colour {} stroke {:?} bright {}", ink.ink_share, ink.colour_ink_share, ink.stroke_mm, ink.bright_share);
    assert_eq!(ink.stroke_mm, None);
    assert_eq!(ink.ink_share, 0.0);
    assert_eq!(ink.colour_ink_share, 0.0);
}

#[test]
fn glare_is_the_plain_share_of_saturated_pixels() {
    let mut page = Rgb { width: 100, height: 100, data: vec![128; 100 * 100 * 3] };
    paint(&mut page, (0, 0, 100, 50), [255, 255, 255]);
    let glare = ink_features(&page, 300.0).unwrap().bright_share;
    println!("half white, half grey: glare {glare}");
    assert!((glare - 0.5).abs() < 1e-9);
}

#[test]
fn ink_features_refuses_a_page_whose_data_does_not_match_its_size() {
    let page = Rgb { width: 10, height: 10, data: vec![0; 10] };
    assert!(ink_features(&page, 300.0).is_none());
}

#[test]
fn ink_features_refuses_an_empty_page_or_a_bad_dpi() {
    let empty = Rgb { width: 0, height: 0, data: vec![] };
    let page = white_page(10, 10);
    println!("empty: {:?}, dpi 0: {:?}, dpi NaN: {:?}", ink_features(&empty, 300.0), ink_features(&page, 0.0), ink_features(&page, f32::NAN));
    assert!(ink_features(&empty, 300.0).is_none());
    assert!(ink_features(&page, 0.0).is_none());
    assert!(ink_features(&page, f32::NAN).is_none());
}

#[test]
fn a_shadow_down_one_long_side_is_a_gutter_band() {
    // Portrait 200 × 300: the long sides are the left and right edges.
    let mut page = grey_page(200, 300, 230);
    paint_grey(&mut page, (0, 0, 12, 300), 150);
    let found = gutter_band(&page);
    println!("shadow down the left long side: gutter {found}");
    assert!(found);
}

#[test]
fn a_shadow_down_the_right_long_side_is_a_gutter_band_too() {
    let mut page = grey_page(200, 300, 230);
    paint_grey(&mut page, (188, 0, 200, 300), 150);
    assert!(gutter_band(&page));
}

#[test]
fn a_shadow_along_a_bottom_long_side_of_a_landscape_page_is_a_gutter_band() {
    // Landscape 300 × 200: the long sides are the top and bottom edges.
    let mut page = grey_page(300, 200, 230);
    paint_grey(&mut page, (0, 188, 300, 200), 150);
    let found = gutter_band(&page);
    println!("shadow along the bottom long side: gutter {found}");
    assert!(found);
}

#[test]
fn an_evenly_lit_page_is_not_a_gutter_band() {
    let page = grey_page(200, 300, 230);
    let found = gutter_band(&page);
    println!("evenly lit: gutter {found}");
    assert!(!found);
}

#[test]
fn a_dark_band_on_a_short_side_is_not_a_gutter_band() {
    // Portrait 200 × 300: the top and bottom edges are the short sides.
    let mut page = grey_page(200, 300, 230);
    paint_grey(&mut page, (0, 0, 200, 12), 150);
    let found = gutter_band(&page);
    println!("dark band across the top short side: gutter {found}");
    assert!(!found);
}

#[test]
fn a_shadow_on_both_long_sides_is_not_a_gutter_band() {
    let mut page = grey_page(200, 300, 230);
    paint_grey(&mut page, (0, 0, 12, 300), 150);
    paint_grey(&mut page, (188, 0, 200, 300), 150);
    assert!(!gutter_band(&page));
}

#[test]
fn a_dark_strip_only_12_percent_under_the_median_is_not_a_gutter_band() {
    // Median 230, limit 230 × 0.88 = 202.4: a strip at 205 is not dark enough.
    let mut page = grey_page(200, 300, 230);
    paint_grey(&mut page, (0, 0, 12, 300), 205);
    assert!(!gutter_band(&page));
}

#[test]
fn gutter_band_refuses_a_square_page_and_a_mismatched_page() {
    let mut square = grey_page(200, 200, 230);
    paint_grey(&mut square, (0, 0, 12, 200), 150);
    let mismatched = Luma { width: 20, height: 20, data: vec![0; 10] };
    println!("square with shadow: {}, mismatched: {}", gutter_band(&square), gutter_band(&mismatched));
    assert!(!gutter_band(&square));
    assert!(!gutter_band(&mismatched));
}

// The strict gutter rule (#427). Portrait 200 × 300 pages at 230: the strip is 12 px, the limit
// 230 × 0.88 = 202.4.

/// Paints a shadow down the left side from `y0` to `y1`: `edge` at x = 0, rising evenly to the
/// page's 230 at x = `width`.
fn shadow(page: &mut Luma, width: u32, edge: u8, (y0, y1): (u32, u32)) {
    for y in y0..y1 {
        for x in 0..width {
            let v = f64::from(edge) + (230.0 - f64::from(edge)) * f64::from(x) / f64::from(width);
            page.data[(y * page.width + x) as usize] = v.round() as u8;
        }
    }
}

#[test]
fn a_gutter_shadow_down_the_whole_side_that_fades_inward_passes_both_rules() {
    let mut page = grey_page(200, 300, 230);
    shadow(&mut page, 18, 120, (0, 300));
    let reading = gutter_reading(&page);
    println!("gutter shadow: {reading:?}");
    assert!(reading.loose && reading.strict, "{reading:?}");
    assert_eq!(reading.cover, 1.0);
    assert_eq!(reading.loose, gutter_band(&page));
    // the same on the right side and along the bottom of a landscape page
    let mirrored = Luma { data: page.data.chunks_exact(200).flat_map(|row| row.iter().rev().copied()).collect(), ..page.clone() };
    assert!(gutter_reading(&mirrored).strict);
    let mut landscape = grey_page(300, 200, 230);
    for y in 0..200u32 {
        for x in 0..300u32 {
            landscape.data[(y * 300 + x) as usize] = page.data[(x * 200 + (199 - y)) as usize];
        }
    }
    assert!(gutter_reading(&landscape).strict, "{:?}", gutter_reading(&landscape));
}

#[test]
fn a_flat_band_of_dark_surround_passes_the_first_rule_only() {
    // what the first rule fired on: a strip of dark table left along one side by the crop
    let mut page = grey_page(200, 300, 230);
    paint_grey(&mut page, (0, 0, 12, 300), 150);
    let reading = gutter_reading(&page);
    println!("flat band: {reading:?}");
    assert!(reading.loose && !reading.strict, "{reading:?}");
    assert_eq!(reading.loose, gutter_band(&page));
}

#[test]
fn a_thin_black_surround_passes_the_first_rule_only() {
    let mut page = grey_page(200, 300, 230);
    paint_grey(&mut page, (0, 0, 4, 300), 10);
    let reading = gutter_reading(&page);
    println!("thin surround: {reading:?}");
    assert!(reading.loose && !reading.strict, "{reading:?}");
}

#[test]
fn a_patch_of_shade_down_part_of_the_side_passes_the_first_rule_only() {
    // dappled shade: a deep shadow that fades inward, but over the top 40 % of the side only
    let mut page = grey_page(200, 300, 230);
    shadow(&mut page, 18, 0, (0, 120));
    let reading = gutter_reading(&page);
    println!("patch of shade: {reading:?}");
    assert!(reading.loose && !reading.strict, "{reading:?}");
    assert!((reading.cover - 0.4).abs() < 1e-9, "{reading:?}");
}

#[test]
fn a_broad_shadow_over_a_third_of_the_page_passes_the_first_rule_only() {
    let mut page = grey_page(200, 300, 230);
    shadow(&mut page, 60, 120, (0, 300));
    let reading = gutter_reading(&page);
    println!("broad shadow: {reading:?}");
    assert!(reading.loose && !reading.strict, "{reading:?}");
}

#[test]
fn the_strict_rule_reads_nothing_on_an_even_a_square_or_a_mismatched_page() {
    assert_eq!(gutter_reading(&grey_page(200, 300, 230)).strict, false);
    let mut square = grey_page(200, 200, 230);
    shadow(&mut square, 18, 120, (0, 200));
    assert!(!gutter_reading(&square).loose && !gutter_reading(&square).strict);
    let mismatched = Luma { width: 20, height: 20, data: vec![0; 10] };
    assert!(!gutter_reading(&mismatched).strict);
    // a shadow on both long sides is no gutter by either rule
    let mut both = grey_page(200, 300, 230);
    shadow(&mut both, 18, 120, (0, 300));
    paint_grey(&mut both, (188, 0, 200, 300), 150);
    assert!(!gutter_reading(&both).loose && !gutter_reading(&both).strict);
}
