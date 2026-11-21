//! The saved PDF follows the reviewed pages (#785): pages are made as Save makes them, with the
//! edits a review leaves on them (a new order, a page taken again, a turn by hand, another look,
//! borders moved), put in a PDF by the same `doc_create` call, written to a file, and that file is
//! opened again in a session of its own and drawn at the stored pages' own size (in tiles, as the
//! viewer draws a page zoomed in). Checked there: the number of pages and their order, each page's
//! size in points, and its pixels against the stored page.
//!
//! Drawn pages in code, on a computer: not the owner's photos and not a phone. The file is read
//! back by this app's own renderer, not by a second one.

use std::path::{Path, PathBuf};

use crate::scan::tests::request;
use crate::scan::{ScanFormat, ScanLook, ScanPageRequest, ScanPageResult, process_in};

/// The photo's size, and the sheet in it (left, top, width, height): an upright A4 sheet seen
/// from straight above, at about 254 dpi, so Small (200 dpi at most) stores it smaller than Best.
const PHOTO: (u32, u32) = (2400, 3300);
const SHEET: (u32, u32, u32, u32) = (150, 160, 2100, 2970);

/// Where a page keeps its number: `n` dark squares out of four places along its top, as shares
/// of the sheet (left, top, right, bottom).
fn number_place(k: u32) -> (f64, f64, f64, f64) {
    let left = 0.10 + 0.14 * f64::from(k);
    (left, 0.06, left + 0.08, 0.12)
}

/// The red block near the sheet's bottom right corner: it says which way up the page is and
/// whether the page kept its colour.
const RED: (f64, f64, f64, f64) = (0.62, 0.84, 0.90, 0.94);

/// A photo of sheet number `n` (1 to 4) on a dark desk: rows of print, the number's squares and
/// the red block. `shade` darkens the paper a little, so a page taken again is another photo.
fn photo(dir: &Path, name: &str, n: u32, shade: u8) -> PathBuf {
    let (sx, sy, sw, sh) = SHEET;
    let inside = |x: u32, y: u32, (l, t, r, b): (f64, f64, f64, f64)| {
        let (u, v) = ((f64::from(x) - f64::from(sx)) / f64::from(sw), (f64::from(y) - f64::from(sy)) / f64::from(sh));
        u >= l && u < r && v >= t && v < b
    };
    let shot = dir.join(name);
    image::RgbImage::from_fn(PHOTO.0, PHOTO.1, |x, y| {
        if x < sx || x >= sx + sw || y < sy || y >= sy + sh {
            return image::Rgb([52, 44, 40]);
        }
        if (0..n).any(|k| inside(x, y, number_place(k))) {
            return image::Rgb([30, 30, 34]);
        }
        if inside(x, y, RED) {
            return image::Rgb([200, 40, 36]);
        }
        let print = inside(x, y, (0.10, 0.20, 0.90, 0.78)) && ((y - sy) / 27) % 3 == 0 && ((x - sx) / 15 + (y - sy) / 81 * 7) % 6 != 0;
        if print { image::Rgb([40, 40, 44]) } else { image::Rgb([238 - shade, 236 - shade, 230 - shade]) }
    })
    .save(&shot)
    .unwrap();
    shot
}

/// The sheet's corners as shares of the photo, pulled in by `inset` of the sheet on every side.
fn corners(inset: f32) -> Vec<f32> {
    let (sx, sy, sw, sh) = (SHEET.0 as f32, SHEET.1 as f32, SHEET.2 as f32, SHEET.3 as f32);
    let (l, t, r, b) = (sx + inset * sw, sy + inset * sh, sx + (1.0 - inset) * sw, sy + (1.0 - inset) * sh);
    let (w, h) = (PHOTO.0 as f32, PHOTO.1 as f32);
    vec![l / w, t / h, r / w, t / h, r / w, b / h, l / w, b / h]
}

/// Pixels as RGB, row by row.
struct Picture {
    width: u32,
    height: u32,
    rgb: Vec<u8>,
}

impl Picture {
    fn stored(page: &ScanPageResult) -> Picture {
        let decoded = image::load_from_memory(&std::fs::read(&page.path).unwrap()).unwrap().to_rgb8();
        Picture { width: decoded.width(), height: decoded.height(), rgb: decoded.into_raw() }
    }

    /// The mean colour of a part of the picture given as shares of it.
    fn mean(&self, (l, t, r, b): (f64, f64, f64, f64)) -> [f64; 3] {
        let (w, h) = (f64::from(self.width), f64::from(self.height));
        let (x0, y0, x1, y1) = ((l * w) as usize, (t * h) as usize, ((r * w) as usize).max(1), ((b * h) as usize).max(1));
        let mut sum = [0f64; 3];
        for y in y0..y1 {
            for x in x0..x1 {
                let i = (y * self.width as usize + x) * 3;
                for (c, total) in sum.iter_mut().enumerate() {
                    *total += f64::from(self.rgb[i + c]);
                }
            }
        }
        sum.map(|s| s / ((x1 - x0) * (y1 - y0)).max(1) as f64)
    }

    /// The mean difference per channel from `other`, in grey levels; the two must be one size.
    fn off(&self, other: &Picture) -> f64 {
        assert_eq!((self.width, self.height), (other.width, other.height));
        self.rgb.iter().zip(&other.rgb).map(|(a, b)| f64::from(a.abs_diff(*b))).sum::<f64>() / self.rgb.len().max(1) as f64
    }
}

/// Page `page` of `doc` drawn at `dpi`, `width` × `height` pixels: in tiles, as the viewer draws a
/// page zoomed in, since a whole page is drawn at 4 megapixels at most.
fn drawn(session: &crate::MobileSession, doc: u64, page: u32, dpi: f32, (width, height): (u32, u32)) -> Picture {
    const TILE: u32 = 512;
    let mut rgb = vec![0u8; width as usize * height as usize * 3];
    for y in (0..height).step_by(TILE as usize) {
        for x in (0..width).step_by(TILE as usize) {
            let (w, h) = (TILE.min(width - x), TILE.min(height - y));
            let tile = session.render_page(doc, page, dpi / 72.0, Some(crate::TileRect { x, y, width: w, height: h })).unwrap();
            assert_eq!((tile.width, tile.height), (w, h), "the tile at {x}, {y} of page {page}");
            for row in 0..h as usize {
                let from = &tile.rgba[row * w as usize * 4..(row + 1) * w as usize * 4];
                let to = ((y as usize + row) * width as usize + x as usize) * 3;
                for (i, p) in from.as_chunks::<4>().0.iter().enumerate() {
                    rgb[to + i * 3..to + i * 3 + 3].copy_from_slice(&p[..3]);
                }
            }
        }
    }
    Picture { width, height, rgb }
}

/// A part of the sheet as it lies on a page turned `turns` quarter turns clockwise.
fn turned((l, t, r, b): (f64, f64, f64, f64), turns: u8) -> (f64, f64, f64, f64) {
    (0..turns).fold((l, t, r, b), |(l, t, r, b), _| (1.0 - b, l, 1.0 - t, r))
}

/// The number a page shows: its dark squares, counted in a picture of the page turned `turns`
/// quarter turns clockwise. A part of the sheet cut `inset` off on every side is looked for where
/// the rest of the sheet puts it.
fn number_shown(picture: &Picture, turns: u8, inset: f64) -> u32 {
    let on_page = |(l, t, r, b): (f64, f64, f64, f64)| {
        let s = |v: f64| (v - inset) / (1.0 - 2.0 * inset);
        // the middle half of the place: its edges are blurred by the page's making
        let (l, t, r, b) = (s(l), s(t), s(r), s(b));
        let (mx, my) = ((r - l) / 4.0, (b - t) / 4.0);
        turned((l + mx, t + my, r - mx, b - my), turns)
    };
    (0..4).filter(|k| picture.mean(on_page(number_place(*k))).iter().sum::<f64>() / 3.0 < 110.0).count() as u32
}

/// One page as the review left it.
struct Reviewed {
    /// The number drawn on its sheet.
    number: u32,
    turns: u8,
    inset: f32,
    look: ScanLook,
    made: ScanPageResult,
}

/// The pages of a scan after its review, in the order the person left them: page 3 first, page 1
/// turned a quarter by hand, page 4 taken again and in black and white, page 2 in grey with its
/// borders pulled in. Made at `size` as Save makes them.
fn reviewed(root: &Path, size: &str) -> Vec<Reviewed> {
    let shots: Vec<PathBuf> = (1..=4).map(|n| photo(root, &format!("shot-{n}.png"), n, 0)).collect();
    let again = photo(root, "shot-4-again.png", 4, 14);
    let edits: [(u32, &Path, u8, f32, ScanLook); 4] = [
        (3, &shots[2], 0, 0.0, ScanLook::Colour),
        (1, &shots[0], 1, 0.0, ScanLook::Colour),
        (4, &again, 0, 0.0, ScanLook::BlackWhite),
        (2, &shots[1], 0, 0.03, ScanLook::Greyscale),
    ];
    edits
        .into_iter()
        .enumerate()
        .map(|(i, (number, shot, turns, inset, look))| {
            let ask = ScanPageRequest {
                corners: corners(inset),
                look,
                turns,
                size: size.into(),
                auto_encode: true,
                ..request(shot, &root.join(format!("{size}-page-{i}")))
            };
            Reviewed { number, turns, inset, look, made: process_in(root, &ask).unwrap() }
        })
        .collect()
}

/// The PDF of `pages` in their order, as Save asks for it, written to `out`.
fn save_pdf(pages: &[Reviewed], out: &Path) {
    let session = crate::MobileSession::new();
    let paths: Vec<&str> = pages.iter().map(|p| p.made.path.as_str()).collect();
    let args = serde_json::json!({ "from": "images", "paths": paths, "name": "Scan" });
    let created: serde_json::Value = serde_json::from_str(&session.execute("doc_create".into(), args.to_string()).unwrap()).unwrap();
    session.execute("doc_save".into(), serde_json::json!({ "doc": created["doc"], "path": out.to_string_lossy() }).to_string()).unwrap();
}

fn temp(name: &str) -> PathBuf {
    let d = std::env::temp_dir().join(format!("pdfcraft-scan-export-{name}-{}", std::process::id()));
    std::fs::create_dir_all(&d).unwrap();
    d.canonicalize().unwrap()
}

/// The saved file, opened again and drawn at each stored page's own size, is the reviewed pages:
/// their count, order, size on paper and pixels, with every edit in it. At Best quality and at
/// Small.
#[test]
fn a_saved_pdf_is_the_reviewed_pages_in_their_order_at_their_size() {
    let mut widths = Vec::new();
    for size in ["best", "small"] {
        let root = temp(size);
        let pages = reviewed(&root, size);
        // the edits are in the pages as made: the order's numbers, the turn, the looks, the borders
        let stored: Vec<Picture> = pages.iter().map(|p| Picture::stored(&p.made)).collect();
        for (page, picture) in pages.iter().zip(&stored) {
            assert_eq!((picture.width, picture.height), (page.made.width, page.made.height));
            assert_eq!(number_shown(picture, page.turns, f64::from(page.inset)), page.number, "{size}: the page made");
            assert_eq!(picture.width > picture.height, page.turns % 2 == 1, "{size}: page {} turned {}", page.number, page.turns);
        }
        assert_eq!(pages[2].made.format, ScanFormat::Png, "{size}: black and white is stored in two levels");
        widths.push(pages[0].made.width);

        let out = root.join("scan.pdf");
        save_pdf(&pages, &out);
        let session = crate::MobileSession::new();
        let opened = session.open(out.to_string_lossy().into_owned(), None).unwrap();
        assert_eq!(opened.pages.len(), pages.len(), "{size}: one PDF page per reviewed page");

        for (i, (page, picture)) in pages.iter().zip(&stored).enumerate() {
            let on_paper = &opened.pages[i];
            // its size on paper is its pixels at its own resolution, and no /Rotate stands in for the turn
            let points = |px: u32| f64::from(px) * 72.0 / f64::from(page.made.dpi);
            assert!(
                (f64::from(on_paper.width) - points(page.made.width)).abs() < 0.5
                    && (f64::from(on_paper.height) - points(page.made.height)).abs() < 0.5,
                "{size}: page {i} is {} x {} pt for {} x {} px at {} dpi",
                on_paper.width,
                on_paper.height,
                page.made.width,
                page.made.height,
                page.made.dpi
            );
            assert_eq!(on_paper.rotation, 0, "{size}: page {i}");
            // an uncut A4 sheet is A4 on paper whichever way it is turned and at every size
            if page.inset == 0.0 {
                let (short, long) = (on_paper.width.min(on_paper.height), on_paper.width.max(on_paper.height));
                assert!((short - 595.3).abs() < 1.0 && (long - 841.9).abs() < 1.0, "{size}: page {i} is {short} x {long} pt");
            }

            // drawn at the stored page's own size: one PDF pixel per stored pixel
            let drawn = drawn(&session, opened.id, i as u32, page.made.dpi, (picture.width, picture.height));
            // and the whole page drawn as the viewer first shows it has the page's proportions
            let whole = session.render_page(opened.id, i as u32, 1.0, None).unwrap();
            let (wide, high) = (f64::from(whole.width), f64::from(whole.height));
            assert!((wide / high - f64::from(picture.width) / f64::from(picture.height)).abs() < 0.005, "{size}: page {i} is drawn {wide} x {high}");
            let off = drawn.off(picture);
            println!("{size}: page {i} is {} x {} px at {} dpi, {off:.3} grey levels from its stored page", drawn.width, drawn.height, page.made.dpi);
            assert!(off < 1.0, "{size}: page {i} drawn from the PDF is {off:.2} grey levels from the stored page");
            // and from every other page of the same size it is far: no page stands in another's place
            for (j, other) in stored.iter().enumerate() {
                if j != i && (other.width, other.height) == (drawn.width, drawn.height) {
                    assert!(drawn.off(other) > 3.0, "{size}: page {i} drawn from the PDF could be page {j}");
                }
            }

            // the edits, read off the PDF's own pixels
            assert_eq!(number_shown(&drawn, page.turns, f64::from(page.inset)), page.number, "{size}: page {i} of the PDF");
            let s = |v: f64| (v - f64::from(page.inset)) / (1.0 - 2.0 * f64::from(page.inset));
            let (l, t, r, b) = (s(RED.0), s(RED.1), s(RED.2), s(RED.3));
            let [red, green, blue] = drawn.mean(turned((l + 0.03, t + 0.02, r - 0.03, b - 0.02), page.turns));
            match page.look {
                ScanLook::Colour => assert!(red > green + 80.0 && red > blue + 80.0, "{size}: page {i} lost its red block: {red} {green} {blue}"),
                _ => assert!((red - green).abs() < 2.0 && (red - blue).abs() < 2.0, "{size}: page {i} kept colour: {red} {green} {blue}"),
            }
        }
        std::fs::remove_dir_all(root).unwrap();
    }
    // the two sizes really are two sizes: Small stores fewer pixels for the same paper
    assert!(widths[1] * 10 < widths[0] * 9, "Small stored {} px across, Best {}", widths[1], widths[0]);
}

/// The same pages put in another order give the same pages in that order: nothing of a page
/// depends on its place, and the PDF takes the order it is given.
#[test]
fn a_new_order_moves_whole_pages_in_the_saved_pdf() {
    let root = temp("order");
    let mut pages = reviewed(&root, "best");
    let draw = |pages: &[Reviewed], name: &str| -> Vec<Picture> {
        let out = root.join(name);
        save_pdf(pages, &out);
        let session = crate::MobileSession::new();
        let opened = session.open(out.to_string_lossy().into_owned(), None).unwrap();
        assert_eq!(opened.pages.len(), pages.len());
        (0..pages.len()).map(|i| drawn(&session, opened.id, i as u32, pages[i].made.dpi, (pages[i].made.width, pages[i].made.height))).collect()
    };
    let before = draw(&pages, "before.pdf");
    // the last page dragged to the front, as the document screen's grid does
    pages.rotate_right(1);
    let after = draw(&pages, "after.pdf");
    for (i, now) in after.iter().enumerate() {
        let was = (i + pages.len() - 1) % pages.len();
        assert_eq!((now.width, now.height), (before[was].width, before[was].height), "page {i}");
        assert!(now.rgb == before[was].rgb, "page {i} is not the page that was at {was}, pixel for pixel");
    }
    std::fs::remove_dir_all(root).unwrap();
}
