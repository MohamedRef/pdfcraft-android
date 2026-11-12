//! Tests of the photo mask (#299). Every page is drawn in code, then given the soft edges of a
//! real scan (a 3 by 3 blur), so that the edges of print carry mid-tone pixels as they do on paper.

use crate::Rgb;
use crate::photo_mask::{PhotoMask, is_photo, is_photo_loose, measure_blocks, photo_mask, photo_masks};

/// A white page of `w` by `h` pixels.
fn blank(w: u32, h: u32) -> Rgb {
    Rgb { width: w, height: h, data: vec![255; w as usize * h as usize * 3] }
}

/// Fills the pixels `x0..x1` by `y0..y1` with `rgb`.
fn fill(p: &mut Rgb, x0: usize, y0: usize, x1: usize, y1: usize, rgb: [u8; 3]) {
    let w = p.width as usize;
    for y in y0..y1 {
        for x in x0..x1 {
            let i = (y * w + x) * 3;
            p.data[i..i + 3].copy_from_slice(&rgb);
        }
    }
}

/// A deterministic pseudo-random number in 0..1.
struct Lcg(u64);

impl Lcg {
    fn next(&mut self) -> f32 {
        self.0 = self.0.wrapping_mul(6_364_136_223_846_793_005).wrapping_add(1_442_695_040_888_963_407);
        (self.0 >> 33) as f32 / (1u64 << 31) as f32
    }
}

/// Paints a smooth random picture over `x0..x1` by `y0..y1`: random values on a 16 px grid,
/// bilinear between them, mapped from `lo` to `hi` per channel. No sharp edges, mid tones.
fn paint_photo(p: &mut Rgb, rect: (usize, usize, usize, usize), seed: u64, lo: [f32; 3], hi: [f32; 3]) {
    const STEP: usize = 16;
    let (x0, y0, x1, y1) = rect;
    let mut rng = Lcg(seed);
    let nodes_x = (p.width as usize) / STEP + 2;
    let nodes_y = (p.height as usize) / STEP + 2;
    let grid: Vec<f32> = (0..nodes_x * nodes_y).map(|_| rng.next()).collect();
    let w = p.width as usize;
    for y in y0..y1 {
        for x in x0..x1 {
            let (gx, gy) = (x / STEP, y / STEP);
            let (fx, fy) = ((x % STEP) as f32 / STEP as f32, (y % STEP) as f32 / STEP as f32);
            let g = |c: usize, r: usize| grid[r * nodes_x + c];
            let v = (g(gx, gy) * (1.0 - fx) + g(gx + 1, gy) * fx) * (1.0 - fy) + (g(gx, gy + 1) * (1.0 - fx) + g(gx + 1, gy + 1) * fx) * fy;
            let i = (y * w + x) * 3;
            for c in 0..3 {
                p.data[i + c] = (lo[c] + (hi[c] - lo[c]) * v).round() as u8;
            }
        }
    }
}

/// Text lines: black words (ink 20) on 16 px pitch, with words of random length and gaps.
fn paint_text(p: &mut Rgb, rect: (usize, usize, usize, usize), seed: u64) {
    let (x0, y0, x1, y1) = rect;
    let mut rng = Lcg(seed);
    let mut y = y0;
    while y + 7 <= y1 {
        let mut x = x0;
        while x < x1 {
            let len = 8 + (rng.next() * 22.0) as usize;
            fill(p, x, y, (x + len).min(x1), y + 7, [20, 20, 20]);
            x += len + 5 + (rng.next() * 4.0) as usize;
        }
        y += 16;
    }
}

/// Mean of the 3 by 3 box around (x, y) for one channel, edge pixels clamped.
fn box_mean(src: &[u8], w: usize, h: usize, x: usize, y: usize, c: usize) -> u8 {
    let mut sum = 0u32;
    for dy in -1i32..=1 {
        for dx in -1i32..=1 {
            let xx = (x as i32 + dx).clamp(0, w as i32 - 1) as usize;
            let yy = (y as i32 + dy).clamp(0, h as i32 - 1) as usize;
            sum += u32::from(src[(yy * w + xx) * 3 + c]);
        }
    }
    (sum / 9) as u8
}

/// The soft edges of a scan: a 3 by 3 blur over the whole page.
fn finish(p: &mut Rgb) {
    let (w, h) = (p.width as usize, p.height as usize);
    let src = p.data.clone();
    for y in 0..h {
        for x in 0..w {
            for c in 0..3 {
                p.data[(y * w + x) * 3 + c] = box_mean(&src, w, h, x, y, c);
            }
        }
    }
}

/// Block rectangle (first column, last column, first row, last row) of a pixel rectangle that is
/// aligned to whole blocks.
fn blocks_of(rect: (usize, usize, usize, usize)) -> (usize, usize, usize, usize) {
    let (x0, y0, x1, y1) = rect;
    (x0 / 24, x1 / 24 - 1, y0 / 24, y1 / 24 - 1)
}

/// How many blocks a block is away from a block rectangle: 0 inside it, 1 next to it, and so on.
fn block_distance(c: usize, r: usize, b: (usize, usize, usize, usize)) -> usize {
    let (c0, c1, r0, r1) = b;
    let dc = if c < c0 { c0 - c } else { c.saturating_sub(c1) };
    let dr = if r < r0 { r0 - r } else { r.saturating_sub(r1) };
    dc.max(dr)
}

/// The weight of the block at (c, r).
fn weight_at(m: &PhotoMask, c: usize, r: usize) -> f32 {
    m.weight[r * m.cols + c]
}

/// A 720 by 960 page: a quarter of it (360 by 480, block-aligned) is a smooth grey photograph.
fn quarter_photo_page() -> (Rgb, (usize, usize, usize, usize)) {
    let mut p = blank(720, 960);
    let rect = (120, 192, 480, 672);
    paint_photo(&mut p, rect, 7, [90.0; 3], [210.0; 3]);
    finish(&mut p);
    (p, rect)
}

#[test]
fn a_page_of_text_bars_has_no_photograph() {
    let mut p = blank(720, 960);
    paint_text(&mut p, (60, 60, 660, 900), 3);
    finish(&mut p);
    let m = photo_mask(&p).expect("a page of 720 by 960 has a mask");
    println!("text page: share {:.4}, whole_page {}", m.share, m.whole_page);
    assert_eq!(m.share, 0.0);
    assert!(!m.whole_page);
    assert!(m.weight.iter().all(|&w| w == 0.0));
}

#[test]
fn a_smooth_grey_photograph_covering_a_quarter_of_the_page_is_found() {
    let (p, rect) = quarter_photo_page();
    let m = photo_mask(&p).expect("mask");
    let b = blocks_of(rect);
    let (mut inside, mut inside_full) = (0usize, 0usize);
    let (mut far, mut far_zero, mut max_far) = (0usize, 0usize, 0.0f32);
    for r in 0..m.rows {
        for c in 0..m.cols {
            let d = block_distance(c, r, b);
            let w = weight_at(&m, c, r);
            if d == 0 {
                inside += 1;
                inside_full += usize::from(w == 1.0);
            }
            if d > 2 {
                far += 1;
                far_zero += usize::from(w == 0.0);
                max_far = max_far.max(w);
            }
        }
    }
    let found = inside_full as f64 / inside as f64;
    println!("grey photo: share {:.4} (true 0.2500), blocks inside at weight 1: {}/{} = {:.4}", m.share, inside_full, inside, found);
    println!("grey photo: blocks more than 2 away: {} of {} at weight 0, largest weight {:.4}", far_zero, far, max_far);
    assert!(found >= 0.85, "found {found}");
    assert_eq!(far_zero, far, "a block 3 or more blocks away must be 0");
    assert!((m.share - 0.25).abs() < 1e-9);
    assert!(!m.whole_page);
}

#[test]
fn a_smooth_coloured_picture_is_found() {
    let mut p = blank(720, 960);
    let rect = (120, 192, 480, 672);
    paint_photo(&mut p, rect, 11, [150.0, 100.0, 60.0], [230.0, 160.0, 110.0]);
    finish(&mut p);
    let m = photo_mask(&p).expect("mask");
    let b = blocks_of(rect);
    let (mut inside, mut inside_full) = (0usize, 0usize);
    let (mut chroma_sum, mut blocks) = (0.0f32, 0usize);
    let (cols, rows, measures) = measure_blocks(&p).expect("measures");
    for r in 0..rows {
        for c in 0..cols {
            let meas = measures[r * cols + c];
            if block_distance(c, r, b) == 0 {
                chroma_sum += meas.chroma;
                blocks += 1;
            }
        }
    }
    for r in b.2..=b.3 {
        for c in b.0..=b.1 {
            inside += 1;
            inside_full += usize::from(weight_at(&m, c, r) == 1.0);
        }
    }
    let found = inside_full as f64 / inside as f64;
    println!("coloured photo: share {:.4}, blocks at weight 1: {:.4}, mean chroma inside {:.4}", m.share, found, chroma_sum / blocks as f32);
    assert!(found >= 0.85, "found {found}");
}

#[test]
fn a_dense_table_of_ruled_lines_is_not_a_photograph() {
    let mut p = blank(720, 960);
    for y in (40..920).step_by(12) {
        fill(&mut p, 40, y, 680, y + 2, [0, 0, 0]);
    }
    for x in (40..680).step_by(60) {
        fill(&mut p, x, 40, x + 2, 920, [0, 0, 0]);
    }
    finish(&mut p);
    let m = photo_mask(&p).expect("mask");
    let (cols, rows, measures) = measure_blocks(&p).expect("measures");
    let max_mid = measures.iter().map(|x| x.mid).fold(0.0f32, f32::max);
    let max_ori = measures.iter().map(|x| x.ori).fold(0.0f32, f32::max);
    println!("ruled table: {}x{} blocks, share {:.4}, largest mid {:.4}, largest ori {:.4} bits", cols, rows, m.share, max_mid, max_ori);
    assert_eq!(m.share, 0.0);
    assert!(m.weight.iter().all(|&w| w == 0.0));
}

#[test]
fn a_solid_black_heading_bar_is_not_a_photograph() {
    let mut p = blank(720, 960);
    fill(&mut p, 0, 0, 720, 96, [0, 0, 0]);
    paint_text(&mut p, (60, 140, 660, 900), 5);
    finish(&mut p);
    let m = photo_mask(&p).expect("mask");
    let (_, _, measures) = measure_blocks(&p).expect("measures");
    let bar = measures[0];
    println!("heading bar: share {:.4}, bar block mid {:.4} chroma {:.4} ori {:.4}", m.share, bar.mid, bar.chroma, bar.ori);
    assert_eq!(m.share, 0.0);
}

#[test]
fn a_blue_ink_stamp_over_text_is_not_a_photograph() {
    let mut p = blank(720, 960);
    paint_text(&mut p, (60, 60, 660, 900), 9);
    // A dark blue ring stamp, 130 px across with a 10 px stroke, over the text.
    let (cx, cy, r_out, r_in) = (400i32, 480i32, 65i32, 55i32);
    let w = p.width as usize;
    for y in (cy - r_out)..=(cy + r_out) {
        for x in (cx - r_out)..=(cx + r_out) {
            let d2 = (x - cx).pow(2) + (y - cy).pow(2);
            if d2 <= r_out * r_out && d2 >= r_in * r_in {
                let i = (y as usize * w + x as usize) * 3;
                p.data[i..i + 3].copy_from_slice(&[25, 50, 160]);
            }
        }
    }
    finish(&mut p);
    let m = photo_mask(&p).expect("mask");
    let (cols, _, measures) = measure_blocks(&p).expect("measures");
    let stamp_blocks: Vec<_> = (0..m.rows)
        .flat_map(|r| (0..m.cols).map(move |c| (c, r)))
        .filter(|&(c, r)| {
            let (bx, by) = (c * 24 + 12, r * 24 + 12);
            let d = ((bx as i32 - cx).pow(2) + (by as i32 - cy).pow(2)) as f32;
            d.sqrt() < (r_out + 20) as f32
        })
        .collect();
    let worst = stamp_blocks.iter().map(|&(c, r)| measures[r * cols + c]).filter(|m| is_photo(*m)).count();
    println!("stamp: share {:.4}, stamp-area blocks {}, of which photo before clean-up {}", m.share, stamp_blocks.len(), worst);
    assert_eq!(m.share, 0.0);
}

#[test]
fn a_page_that_is_eighty_percent_picture_is_a_whole_page_picture() {
    let mut p = blank(720, 960);
    paint_photo(&mut p, (0, 0, 720, 768), 13, [80.0; 3], [220.0; 3]);
    paint_text(&mut p, (60, 800, 660, 920), 17);
    finish(&mut p);
    let m = photo_mask(&p).expect("mask");
    println!("80 % picture: share {:.4}, whole_page {}, every weight 1.0: {}", m.share, m.whole_page, m.weight.iter().all(|&w| w == 1.0));
    assert!(m.whole_page);
    assert!(m.weight.iter().all(|&w| w == 1.0));
    assert!((m.share - 0.8).abs() < 1e-9);
}

#[test]
fn a_tiny_page_gives_no_mask() {
    let p = blank(60, 60);
    assert!(photo_mask(&p).is_none());
    assert!(photo_mask(&blank(0, 0)).is_none());
    assert!(photo_mask(&blank(500, 70)).is_none(), "70 px is under three blocks high");
}

#[test]
fn a_buffer_of_the_wrong_length_gives_no_mask() {
    let mut p = blank(720, 960);
    p.data.truncate(100);
    assert!(photo_mask(&p).is_none());
}

#[test]
fn a_photograph_narrower_than_three_blocks_is_dropped() {
    let mut p = blank(720, 960);
    paint_photo(&mut p, (288, 96, 336, 600), 19, [90.0; 3], [210.0; 3]);
    finish(&mut p);
    let m = photo_mask(&p).expect("mask");
    println!("two-block-wide strip: share {:.4}", m.share);
    assert_eq!(m.share, 0.0);
}

#[test]
fn a_hole_inside_a_photograph_is_closed() {
    let (mut p, rect) = quarter_photo_page();
    // A white window of 2 by 2 blocks inside the photograph (a paper hole).
    fill(&mut p, 240, 360, 288, 408, [255, 255, 255]);
    let m = photo_mask(&p).expect("mask");
    let (c, r) = (10usize, 15usize);
    println!("hole in photo: rect {:?}, weight at the hole's centre block {:.4}", rect, weight_at(&m, c, r));
    assert_eq!(weight_at(&m, c, r), 1.0);
}

#[test]
fn at_is_one_inside_a_photograph_zero_far_away_and_between_at_its_edge() {
    let (p, _) = quarter_photo_page();
    let m = photo_mask(&p).expect("mask");
    let inside = m.at(300, 430);
    let far = m.at(20, 20);
    let edge = m.at(108, 430);
    println!("at: inside {:.4}, far {:.4}, edge {:.4}", inside, far, edge);
    assert_eq!(inside, 1.0);
    assert_eq!(far, 0.0);
    assert!(edge > 0.0 && edge < 1.0, "edge {edge}");
}

#[test]
fn the_photo_rule_follows_the_thresholds_of_the_spec() {
    use crate::photo_mask::Measures;
    let m = |mid: f32, chroma: f32, ori: f32| Measures { mid, paper: 0.0, edges: 0.0, chroma, ori };
    assert!(is_photo(m(0.50, 0.0, 0.0)), "mid above 0.45 alone");
    assert!(!is_photo(m(0.44, 0.0, 0.0)), "mid 0.44 alone is not enough");
    assert!(is_photo(m(0.35, 0.20, 0.0)), "coloured and textured enough");
    assert!(is_photo(m(0.35, 0.0, 2.7)), "spread of directions");
    assert!(!is_photo(m(0.29, 0.50, 2.9)), "mid at or under 0.30 never gets the second clause");
    assert!(!is_photo(m(0.35, 0.10, 2.6)), "both limits are strict");
    // print on paper (#427): blank paper showing between many edges is not a photograph
    let print = |paper: f32, edges: f32| Measures { mid: 0.50, paper, edges, chroma: 0.0, ori: 0.0 };
    assert!(is_photo_loose(print(0.40, 0.50)) && !is_photo(print(0.40, 0.50)), "a barcode: half paper, half its pixels on an edge");
    assert!(is_photo(print(0.40, 0.05)), "the bright, smooth part of a photograph");
    assert!(is_photo(print(0.10, 0.50)), "a textured photograph that covers its paper");
    assert!(is_photo(print(0.24, 0.50)) && is_photo(print(0.40, 0.19)), "under either limit it stays a photograph");
    assert!(!is_photo(print(0.25, 0.20)), "both limits are reached");
}

/// A barcode as a small photo shows it: bars one or two pixels wide whose ink the blur lifts to
/// grey, with a few pixels of paper between them.
fn paint_barcode(p: &mut Rgb, rect: (usize, usize, usize, usize), seed: u64) {
    let (x0, y0, x1, y1) = rect;
    let mut rng = Lcg(seed);
    let mut x = x0;
    while x < x1 {
        let bar = 1 + (rng.next() * 2.0) as usize;
        fill(p, x, y0, (x + bar).min(x1), y1, [40, 40, 40]);
        x += bar + 4 + (rng.next() * 3.0) as usize;
    }
}

/// Soft print: lines of small words in grey ink, as thin strokes come out of a small photo.
fn paint_soft_print(p: &mut Rgb, rect: (usize, usize, usize, usize), seed: u64) {
    let (x0, y0, x1, y1) = rect;
    let mut rng = Lcg(seed);
    let mut y = y0;
    while y + 3 <= y1 {
        let mut x = x0;
        while x < x1 {
            let len = 3 + (rng.next() * 5.0) as usize;
            fill(p, x, y, (x + len).min(x1), y + 3, [110, 110, 110]);
            x += len + 3 + (rng.next() * 2.0) as usize;
        }
        y += 7;
    }
}

/// What the first rule took for photographs on real pages (#427): on the reference photos a
/// label's barcode and a stamped sheet read as 15 % and 13 % photograph. Print on paper is not
/// one, and the first rule's reading is still there to compare.
#[test]
fn a_barcode_and_soft_print_are_not_photographs() {
    for (name, paint) in [("barcode", paint_barcode as fn(&mut Rgb, (usize, usize, usize, usize), u64)), ("soft print", paint_soft_print)] {
        let mut page = blank(720, 960);
        paint_text(&mut page, (48, 48, 672, 240), 11);
        // 16 by 8 blocks of it: 128 of the page's 1200 blocks
        let rect = (168, 384, 552, 576);
        paint(&mut page, rect, 5);
        finish(&mut page);
        let masks = photo_masks(&page).unwrap();
        let (cols, _, measures) = measure_blocks(&page).unwrap();
        let (c0, c1, r0, r1) = blocks_of(rect);
        let inside: Vec<_> = (r0..=r1).flat_map(|r| (c0..=c1).map(move |c| (c, r))).map(|(c, r)| measures[r * cols + c]).collect();
        let mean = |f: fn(&crate::photo_mask::Measures) -> f32| inside.iter().map(f).sum::<f32>() / inside.len() as f32;
        println!(
            "{name}: first rule {:.4}, rule in force {:.4}; its blocks: mid {:.2}, paper {:.2}, edges {:.2}",
            masks.loose.share,
            masks.strict.share,
            mean(|m| m.mid),
            mean(|m| m.paper),
            mean(|m| m.edges)
        );
        assert!(masks.loose.share > 0.08, "{name}: the first rule takes it for a photograph ({})", masks.loose.share);
        assert_eq!(masks.strict.share, 0.0, "{name}");
        assert!(masks.strict.weight.iter().all(|&w| w == 0.0), "{name}");
        assert_eq!(photo_mask(&page).unwrap(), masks.strict);
    }
}

/// The light is evened before the mask is made, which turns a photograph's bright parts white.
/// They are smooth, so they stay part of the photograph: both rules read the same.
#[test]
fn a_photograph_with_bright_smooth_parts_is_read_alike_by_both_rules() {
    let mut page = blank(720, 960);
    let rect = (120, 240, 600, 720);
    // from white at its top down to dark, with the picture's own smooth tones over it
    paint_photo(&mut page, rect, 9, [150.0; 3], [255.0; 3]);
    let w = page.width as usize;
    for y in rect.1..rect.3 {
        let fade = 1.0 - 0.6 * (y - rect.1) as f32 / (rect.3 - rect.1) as f32;
        for x in rect.0..rect.2 {
            let i = (y * w + x) * 3;
            for c in 0..3 {
                page.data[i + c] = (f32::from(page.data[i + c]) * fade).round() as u8;
            }
        }
    }
    finish(&mut page);
    let masks = photo_masks(&page).unwrap();
    let (cols, _, measures) = measure_blocks(&page).unwrap();
    let (c0, c1, r0, r1) = blocks_of(rect);
    let papery = (r0..=r1).flat_map(|r| (c0..=c1).map(move |c| (c, r))).filter(|&(c, r)| measures[r * cols + c].paper >= 0.25).count();
    println!(
        "bright photograph: first rule {:.4}, rule in force {:.4}, blocks a quarter white or more {papery}",
        masks.loose.share, masks.strict.share
    );
    assert!(papery >= 10, "the picture has bright blocks ({papery})");
    assert!(masks.strict.share > 0.15, "{}", masks.strict.share);
    assert_eq!(masks.strict, masks.loose);
}
