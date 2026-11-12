//! Turning pages upright (#339): the turn itself, how tiles are chosen, how their answers are
//! put together, and the network on drawn pages of print turned four ways. The pages in
//! `tests/data/` were drawn by `tools/scan-eval/text_pages.py`.

use crate::models::{ModelState, UPRIGHT, state};
use crate::page::{PageRequest, STAGE_UPRIGHT, process_page};
use crate::upright::{TILE, Upright, tiles, turned, upright, vote};
use crate::warp::Rgb;
use crate::{Look, Photo};

fn page(png: &[u8]) -> Rgb {
    let picture = image::load_from_memory(png).unwrap().to_rgb8();
    Rgb { width: picture.width(), height: picture.height(), data: picture.into_raw() }
}

fn pages() -> [(&'static str, Rgb); 3] {
    [
        ("english", page(include_bytes!("../tests/data/upright-english.png"))),
        ("arabic", page(include_bytes!("../tests/data/upright-arabic.png"))),
        ("form", page(include_bytes!("../tests/data/upright-form.png"))),
    ]
}

/// A quarter turn clockwise puts the top-left corner at the top right, and so on round; the
/// sides swap for odd turns, and nothing is resampled.
#[test]
fn a_turn_moves_every_pixel_to_its_place() {
    // 3 wide, 2 high, every pixel its own colour: (x, y) is [10 x, 10 y, 7]
    let (w, h) = (3u32, 2u32);
    let data: Vec<u8> = (0..h).flat_map(|y| (0..w).flat_map(move |x| [10 * x as u8, 10 * y as u8, 7])).collect();
    let page = Rgb { width: w, height: h, data };
    let at = |p: &Rgb, x: u32, y: u32| {
        let i = ((y * p.width + x) * 3) as usize;
        (p.data[i] / 10, p.data[i + 1] / 10)
    };
    let once = turned(&page, 1);
    assert_eq!((once.width, once.height), (2, 3));
    // the page's top-left is now top right, its bottom-left top left
    assert_eq!((at(&once, 1, 0), at(&once, 0, 0), at(&once, 0, 2), at(&once, 1, 2)), ((0, 0), (0, 1), (2, 1), (2, 0)));
    let twice = turned(&page, 2);
    assert_eq!((twice.width, twice.height), (3, 2));
    assert_eq!((at(&twice, 2, 1), at(&twice, 0, 0)), ((0, 0), (2, 1)));
    let thrice = turned(&page, 3);
    assert_eq!((thrice.width, thrice.height), (2, 3));
    // the page's top-left is now bottom left, its top-right top left
    assert_eq!((at(&thrice, 0, 2), at(&thrice, 0, 0), at(&thrice, 1, 0)), ((0, 0), (2, 0), (2, 1)));
    // turns add up, a full turn is no turn, and a turn is undone by the rest of the full turn
    assert_eq!(turned(&once, 1), twice);
    assert_eq!(turned(&twice, 1), thrice);
    assert_eq!(turned(&thrice, 1), page);
    assert_eq!((turned(&page, 0), turned(&page, 4), turned(&page, 7)), (page.clone(), page.clone(), thrice));
    for k in 0..4 {
        assert_eq!(turned(&turned(&page, k), (4 - k) % 4), page);
    }
    // a picture whose bytes are not its size is handed back as it is
    let odd = Rgb { width: 3, height: 2, data: vec![0; 5] };
    assert_eq!(turned(&odd, 1), odd);
}

/// The tiles' answers put together: the turn that undoes what most of them saw, and sure only
/// when the vote is clear and at least half of the tiles gave that answer themselves.
#[test]
fn tiles_vote_and_a_split_vote_is_not_acted_on() {
    let says = |k: usize, p: f32| {
        let mut row = [(1.0 - p) / 3.0; 4];
        row[k] = p;
        row
    };
    // nothing to go by
    assert_eq!(vote(&[]), Upright { quarter_turns: 0, confidence: 0.0, agreement: 0.0, tiles: 0, sure: false });
    // every tile sees a page turned clockwise once: three more quarter turns put it right
    let clear = vote(&[says(1, 0.85); 9]);
    assert_eq!((clear.quarter_turns, clear.tiles, clear.sure), (3, 9, true));
    assert!((clear.confidence - 0.85).abs() < 1e-3 && clear.agreement == 1.0, "{clear:?}");
    for k in 0..4 {
        assert_eq!(vote(&[says(k, 0.8); 4]).quarter_turns as usize, (4 - k) % 4);
    }
    // an upright page is left alone and sure of it
    assert_eq!((vote(&[says(0, 0.85); 5]).quarter_turns, vote(&[says(0, 0.85); 5]).sure), (0, true));
    // two against one: the two win, with less of the vote
    let two = vote(&[says(2, 0.85), says(2, 0.85), says(0, 0.85)]);
    assert_eq!((two.quarter_turns, two.sure), (2, true));
    assert!(two.confidence < 0.85 && (two.agreement - 2.0 / 3.0).abs() < 1e-6, "{two:?}");
    // an even split, and tiles that have no idea: nobody is sure, and the page stays as it is
    let split = vote(&[says(2, 0.85), says(0, 0.85)]);
    assert!(!split.sure && split.quarter_turns == 0 && split.confidence <= 0.5, "{split:?}");
    let lost = vote(&[[0.25; 4]; 6]);
    assert!(!lost.sure && lost.quarter_turns == 0, "{lost:?}");
    // one tile sure of itself against several that lean the other way a little: it rules its
    // turn in, and the vote is no longer clear enough to act on
    let mut mixed = vec![says(1, 0.4); 5];
    mixed.push(says(3, 0.999));
    assert!(!vote(&mixed).sure, "{:?}", vote(&mixed));
    // a likelihood of nothing does not break the sum
    assert!(vote(&[[1.0, 0.0, 0.0, 0.0]; 3]).confidence.is_finite());
}

/// Only tiles with something in them have a say, the busiest nine at most, and the choice is
/// the same every time.
#[test]
fn empty_paper_has_no_say() {
    // empty paper, with the grain a camera gives it
    let blank = Rgb { width: 1400, height: 1000, data: (0..1400 * 1000 * 3).map(|i| 236 + (i % 3) as u8).collect() };
    assert!(tiles(&blank).is_empty());
    // too small to cut a tile from
    let small = Rgb { width: 200, height: 3000, data: vec![40; 200 * 3000 * 3] };
    assert!(tiles(&small).is_empty());
    assert!(tiles(&Rgb { width: 0, height: 0, data: Vec::new() }).is_empty());
    assert!(tiles(&Rgb { width: 300, height: 300, data: vec![0; 7] }).is_empty());
    for (name, page) in pages() {
        let chosen = tiles(&page);
        // 672 x 896 is three tiles by four; the margins and the empty foot of the page stay out
        assert!((4..=9).contains(&chosen.len()), "{name}: {} tiles", chosen.len());
        assert!(chosen.iter().all(|t| t.len() == 3 * TILE * TILE));
        assert_eq!(chosen, tiles(&page), "{name}");
        // paper is near white: about (0.98 - 0.45) / 0.22 in every plane
        let lightest = chosen[0].iter().copied().fold(f32::MIN, f32::max);
        assert!((2.2..2.7).contains(&lightest), "{name}: {lightest}");
    }
    // a large page is shrunk first: no more tiles than five by four, nine of them looked at
    let (name, page) = &pages()[0];
    let large = image::imageops::resize(
        &image::RgbImage::from_raw(page.width, page.height, page.data.clone()).unwrap(),
        page.width * 3,
        page.height * 3,
        image::imageops::FilterType::Triangle,
    );
    let large = Rgb { width: large.width(), height: large.height(), data: large.into_raw() };
    assert_eq!(tiles(&large).len(), 9, "{name}");
}

/// The network on pages of English print, Arabic print and a form, each turned four ways: it
/// says which way, is sure, and the page turned by its answer is the page that was drawn.
#[test]
fn printed_pages_turned_four_ways_come_back_upright() {
    assert_eq!(state(UPRIGHT), ModelState::Ready, "assets/models/scan-upright.onnx is missing or is not the pinned file");
    for (name, page) in pages() {
        for k in 0..4u8 {
            let shown = turned(&page, k);
            let found = upright(&shown).expect("the model runs");
            println!("{name} turned {k}: {found:?}");
            assert_eq!(found.quarter_turns, (4 - k) % 4, "{name} turned {k}: {found:?}");
            // print this plain is settled by the four busiest tiles
            assert!(found.sure && found.confidence >= 0.75 && found.agreement == 1.0 && found.tiles == 4, "{name} turned {k}: {found:?}");
            assert_eq!(turned(&shown, found.quarter_turns), page, "{name} turned {k}");
            // the same answer every time
            assert_eq!(upright(&shown), Some(found));
        }
    }
    // an empty page: an answer, and nobody sure of it
    let blank = Rgb { width: 900, height: 1200, data: vec![240; 900 * 1200 * 3] };
    assert_eq!(upright(&blank), Some(Upright { quarter_turns: 0, confidence: 0.0, agreement: 0.0, tiles: 0, sure: false }));
}

/// Through the whole pipeline: a photo of a page lying sideways or upside down comes out
/// upright, with its sides swapped where they should be, and says how it was turned.
#[test]
fn a_page_photographed_sideways_is_saved_upright() {
    let (_, drawn) = &pages()[1];
    // the page a little inside the photo, so it is cut out and not handed through
    let corners = [0.01, 0.01, 0.99, 0.01, 0.99, 0.99, 0.01, 0.99];
    for k in 0..4u8 {
        let shown = turned(drawn, k);
        let mut photo = Vec::new();
        image::RgbImage::from_raw(shown.width, shown.height, shown.data.clone())
            .unwrap()
            .write_to(&mut std::io::Cursor::new(&mut photo), image::ImageFormat::Png)
            .unwrap();
        let request = PageRequest { look: Look::Original, snap_paper: false, ..PageRequest::new(&photo, corners) };
        let plain = process_page(&request).unwrap();
        assert_eq!((plain.quarter_turns, plain.orientation), (0, None));
        assert!(!plain.timings.iter().any(|(stage, _)| *stage == STAGE_UPRIGHT));
        let upright = process_page(&PageRequest { upright: true, ..request }).unwrap();
        let found = upright.orientation.expect("the model runs");
        assert_eq!((upright.quarter_turns, found.quarter_turns, found.sure), ((4 - k) % 4, (4 - k) % 4, true), "turned {k}: {found:?}");
        let stages: Vec<_> = upright.timings.iter().map(|(stage, _)| *stage).collect();
        assert_eq!(stages, ["scan_decode", "scan_rectify", "scan_upright", "scan_look", "scan_encode"]);
        // the sides swap for a quarter turn, and the page reads as it was drawn
        let (w, h) = if k % 2 == 1 { (plain.height, plain.width) } else { (plain.width, plain.height) };
        assert_eq!((upright.width, upright.height, upright.dpi), (w, h, plain.dpi), "turned {k}");
        assert!(upright.width < upright.height, "turned {k}: the page is upright, so taller than wide");
        let saved = Photo::decode(&upright.bytes).unwrap();
        let again = turned(&Rgb { width: saved.width, height: saved.height, data: saved.rgb }, k);
        let before = Photo::decode(&plain.bytes).unwrap();
        let apart: u64 = again.data.iter().zip(&before.rgb).map(|(a, b)| u64::from(a.abs_diff(*b))).sum();
        let mean = apart as f64 / before.rgb.len() as f64;
        assert!((again.width, again.height) == (before.width, before.height) && mean < 2.0, "turned {k}: {mean:.2} grey levels apart");
    }
}
