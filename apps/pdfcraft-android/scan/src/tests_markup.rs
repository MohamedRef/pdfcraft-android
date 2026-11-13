//! Marks made by hand on a scanned page (#495): the eraser paints the paper around what it rubs
//! (on tinted and shaded paper too), the pen and highlighter draw where they were drawn, text is
//! drawn from its mask, and every mark turns with the page.

use crate::enhance::Bitmap1;
use crate::look::Pixels;
use crate::markup::{Markup, Stroke, Text, Tool, apply, turn_point};

const W: usize = 400;
const H: usize = 600;

/// A page of tinted paper whose light falls off from left to right (as an Original photo's), with
/// a little grain and a block of "print" lines across its middle.
fn tinted_page() -> Vec<u8> {
    let mut seed = 0x2545_f491_4f6c_dd1du64;
    let mut data = Vec::with_capacity(W * H * 3);
    for y in 0..H {
        for x in 0..W {
            let light = 1.0 - 0.3 * x as f32 / W as f32;
            let print = (250..350).contains(&y) && y % 12 < 3 && (40..360).contains(&x);
            for warm in [0.98f32, 0.93, 0.80] {
                seed ^= seed << 13;
                seed ^= seed >> 7;
                seed ^= seed << 17;
                let g = (seed % 5) as f32 - 2.0;
                let v = if print { 30.0 } else { 245.0 * light * warm + g };
                data.push(v.round().clamp(0.0, 255.0) as u8);
            }
        }
    }
    data
}

fn px(data: &[u8], x: usize, y: usize) -> [f32; 3] {
    let i = (y * W + x) * 3;
    [f32::from(data[i]), f32::from(data[i + 1]), f32::from(data[i + 2])]
}

fn stroke(tool: Tool, colour: [u8; 3], width: f32, points: &[(f32, f32)]) -> Stroke {
    Stroke { tool, colour, width, points: points.iter().map(|&(x, y)| [x, y, 1.0]).collect() }
}

fn marks(strokes: Vec<Stroke>) -> Markup {
    Markup { turns: 0, strokes, texts: Vec::new() }
}

/// A dark blot (a finger's shadow, a stain) on the tinted page, rubbed out with the eraser, is
/// paper again: close to the paper beside it on both sides of the light's fall-off, not a flat
/// patch of one colour, and the print outside the brush is untouched.
#[test]
fn the_eraser_paints_the_paper_around_it() {
    let clean = tinted_page();
    let mut blotted = clean.clone();
    // a blot from x 60 to 340 at y 100–140: it crosses the whole light fall-off
    for y in 100..140 {
        for x in 60..340 {
            let i = (y * W + x) * 3;
            blotted[i..i + 3].copy_from_slice(&[90, 70, 60]);
        }
    }
    let mut pixels = Pixels::Rgb(blotted);
    // a brush 50 px wide (an eighth of the long side, 600) rubbed along the blot
    let rub = stroke(Tool::Erase, [0; 3], 50.0 / 600.0, &[(50.0 / W as f32, 120.0 / H as f32), (350.0 / W as f32, 120.0 / H as f32)]);
    let done = apply(&mut pixels, W as u32, H as u32, 0, &marks(vec![rub]));
    let Pixels::Rgb(out) = pixels else { panic!("still colour") };
    assert_eq!(done.erase, 1);
    assert!(done.erased_share > 0.04 && done.erased_share < 0.08, "{}", done.erased_share);
    // inside the blot, every pixel is within a few levels of the clean paper at the same place
    let mut worst = 0f32;
    for y in 104..136 {
        for x in 64..336 {
            let (a, b) = (px(&out, x, y), px(&clean, x, y));
            for c in 0..3 {
                worst = worst.max((a[c] - b[c]).abs());
            }
        }
    }
    assert!(worst < 8.0, "the fill is {worst} levels off the paper");
    // it follows the light: the left of the fill is brighter than its right, as the paper is
    let (left, right) = (px(&out, 80, 120)[0], px(&out, 320, 120)[0]);
    assert!(left - right > 30.0, "left {left} right {right}");
    // and it has grain, like the paper (not one flat value)
    let row: Vec<f32> = (150..170).map(|x| px(&out, x, 120)[1]).collect();
    let mean = row.iter().sum::<f32>() / row.len() as f32;
    let spread = (row.iter().map(|v| (v - mean).powi(2)).sum::<f32>() / row.len() as f32).sqrt();
    assert!(spread > 0.3, "flat fill, spread {spread}");
    // the print lines below the brush are as they were
    for y in 250..350 {
        for x in 0..W {
            assert_eq!(px(&out, x, y), px(&clean, x, y), "({x}, {y}) changed");
        }
    }
}

/// A pen line is drawn in its colour where it was drawn; a highlighter over print leaves the print
/// dark and the paper tinted with its colour.
#[test]
fn pen_and_highlighter_draw_where_they_were_drawn() {
    let clean = tinted_page();
    let mut pixels = Pixels::Rgb(clean.clone());
    let pen = stroke(Tool::Pen, [200, 0, 0], 6.0 / 600.0, &[(0.1, 0.1), (0.9, 0.1)]);
    let mark = stroke(Tool::Highlighter, [255, 235, 59], 20.0 / 600.0, &[(0.1, 252.0 / H as f32), (0.9, 252.0 / H as f32)]);
    let done = apply(&mut pixels, W as u32, H as u32, 0, &marks(vec![pen, mark]));
    let Pixels::Rgb(out) = pixels else { panic!("still colour") };
    assert_eq!((done.pen, done.highlighter, done.erase, done.skipped), (1, 1, 0, 0));
    let red = px(&out, W / 2, 60);
    assert!(red[0] > 180.0 && red[1] < 20.0 && red[2] < 20.0, "{red:?}");
    // away from the line, nothing changed
    assert_eq!(px(&out, W / 2, 80), px(&clean, W / 2, 80));
    // the print line at y 252 under the highlighter: still dark
    let print = px(&out, W / 2, 253);
    assert!(print.iter().all(|&v| v < 40.0), "{print:?}");
    // the paper beside it under the highlighter: yellow (blue pulled down)
    let paper = px(&out, W / 2, 258);
    let before = px(&clean, W / 2, 258);
    assert!(paper[2] < before[2] * 0.4 && paper[0] > before[0] * 0.95, "{paper:?} from {before:?}");
}

/// A pen stroke that crosses itself is laid down once: a highlighter's crossing is no darker than
/// the rest of it.
#[test]
fn a_stroke_crossing_itself_is_laid_down_once() {
    let mut pixels = Pixels::Rgb(vec![240; W * H * 3]);
    let mark = stroke(Tool::Highlighter, [255, 235, 59], 20.0 / 600.0, &[(0.2, 0.5), (0.8, 0.5), (0.5, 0.3), (0.5, 0.7)]);
    apply(&mut pixels, W as u32, H as u32, 0, &marks(vec![mark]));
    let Pixels::Rgb(out) = pixels else { panic!("still colour") };
    assert_eq!(px(&out, 200, 300), px(&out, 120, 300));
}

/// Marks made with the page shown at one turn land on the same spot of the page when it is made
/// at another: a dot drawn at the top left of the page as shown, then the page turned a quarter
/// clockwise, is at its top right.
#[test]
fn marks_turn_with_the_page() {
    assert_eq!(turn_point(0.1, 0.2, 1), (0.8, 0.1));
    assert_eq!(turn_point(0.1, 0.2, 4), (0.1, 0.2));
    // the page as made now is H wide and W tall (turned a quarter from when the dot was drawn)
    let (w, h) = (H, W);
    let mut pixels = Pixels::Grey(vec![250; w * h]);
    let dot = stroke(Tool::Pen, [0, 0, 0], 20.0 / 600.0, &[(0.1, 0.1)]);
    apply(&mut pixels, w as u32, h as u32, 1, &marks(vec![dot]));
    let Pixels::Grey(out) = pixels else { panic!("still grey") };
    // drawn at (0.1, 0.1) of a W × H page → (0.9, 0.1) of the turned one
    let (x, y) = ((0.9 * w as f32) as usize, (0.1 * h as f32) as usize);
    assert!(out[y * w + x] < 20, "{}", out[y * w + x]);
    assert_eq!(out[y * w + (0.1 * w as f32) as usize], 250);
}

/// On a black-and-white page the eraser makes paper, the pen is black whatever its colour, and a
/// highlighter is left out and counted.
#[test]
fn a_black_and_white_page_gets_black_marks_and_no_highlighter() {
    let stride = W.div_ceil(8);
    // all ink
    let bits = Bitmap1 { width: W as u32, height: H as u32, data: vec![0; stride * H] };
    let mut pixels = Pixels::Bilevel(bits);
    let rub = stroke(Tool::Erase, [0; 3], 40.0 / 600.0, &[(0.5, 0.5)]);
    let mark = stroke(Tool::Highlighter, [255, 235, 59], 20.0 / 600.0, &[(0.1, 0.1), (0.9, 0.1)]);
    let done = apply(&mut pixels, W as u32, H as u32, 0, &marks(vec![rub, mark]));
    assert_eq!((done.erase, done.highlighter, done.skipped), (1, 0, 1));
    let Pixels::Bilevel(out) = pixels else { panic!("still black and white") };
    assert!(!out.is_black(W as u32 / 2, H as u32 / 2), "the rubbed middle is paper");
    assert!(out.is_black(5, 5));
    // a red pen on white paper is black
    let mut white = Pixels::Bilevel(Bitmap1 { width: W as u32, height: H as u32, data: vec![0xFF; stride * H] });
    apply(&mut white, W as u32, H as u32, 0, &marks(vec![stroke(Tool::Pen, [220, 0, 0], 8.0 / 600.0, &[(0.2, 0.5), (0.8, 0.5)])]));
    let Pixels::Bilevel(out) = white else { panic!("still black and white") };
    assert!(out.is_black(W as u32 / 2, H as u32 / 2));
}

/// A text box is drawn from its mask in its colour, inside its box; a mask that does not match
/// its size is left out.
#[test]
fn text_is_drawn_from_its_mask() {
    let mut pixels = Pixels::Rgb(vec![250; W * H * 3]);
    // a box 120 × 30 px centred at (200, 300): its left half is "ink"
    let mask: Vec<u8> = (0..20).flat_map(|_| (0..80).map(|x| if x < 40 { 255 } else { 0 })).collect();
    let text = Text { centre: [0.5, 0.5], size: [120.0 / 600.0, 30.0 / 600.0], colour: [0, 0, 200], mask_width: 80, mask_height: 20, mask };
    let broken = Text { mask: vec![1, 2, 3], ..text.clone() };
    let done = apply(&mut pixels, W as u32, H as u32, 0, &Markup { turns: 0, strokes: Vec::new(), texts: vec![text, broken] });
    assert_eq!((done.text, done.skipped), (1, 1));
    let Pixels::Rgb(out) = pixels else { panic!("still colour") };
    assert_eq!(px(&out, 170, 300), [0.0, 0.0, 200.0]);
    assert_eq!(px(&out, 230, 300), [250.0; 3]);
    assert_eq!(px(&out, 170, 330), [250.0; 3]);
}

/// No marks change nothing, and bad input (no points, a width of nothing, numbers that are not
/// numbers) is ignored rather than drawn or panicking.
#[test]
fn nothing_and_nonsense_change_nothing() {
    let clean = tinted_page();
    let mut pixels = Pixels::Rgb(clean.clone());
    let done = apply(&mut pixels, W as u32, H as u32, 0, &Markup::default());
    assert_eq!(done.pen + done.erase + done.text, 0);
    let bad = vec![
        stroke(Tool::Pen, [0; 3], 0.01, &[]),
        stroke(Tool::Pen, [0; 3], 0.0, &[(0.5, 0.5)]),
        stroke(Tool::Erase, [0; 3], f32::NAN, &[(0.5, 0.5)]),
        Stroke { tool: Tool::Pen, colour: [0; 3], width: 0.01, points: vec![[f32::NAN, 0.5, 1.0]] },
        // far off the page
        stroke(Tool::Pen, [0; 3], 0.01, &[(1.4, 1.4)]),
    ];
    apply(&mut pixels, W as u32, H as u32, 0, &marks(bad));
    let Pixels::Rgb(out) = pixels else { panic!("still colour") };
    assert_eq!(out, clean);
}

/// The same marks on the same page give the same bytes every time (the eraser's grain is seeded).
#[test]
fn marks_are_deterministic() {
    let make = || {
        let mut pixels = Pixels::Rgb(tinted_page());
        let m = marks(vec![
            stroke(Tool::Erase, [0; 3], 0.05, &[(0.2, 0.2), (0.7, 0.3)]),
            stroke(Tool::Pen, [10, 20, 200], 0.01, &[(0.1, 0.8), (0.9, 0.85)]),
        ]);
        apply(&mut pixels, W as u32, H as u32, 2, &m);
        pixels
    };
    assert_eq!(make(), make());
}

/// A page whose paper is shaded across a band: 240 above y 100, falling evenly to 100 at y 220,
/// then 100 (the desk beside a page cropped wide), tinted warm, with a little grain.
fn shaded_page() -> Vec<u8> {
    let mut seed = 0x9e37_79b9_7f4a_7c15u64;
    let mut data = Vec::with_capacity(W * H * 3);
    for y in 0..H {
        let v = match y {
            0..100 => 240.0,
            100..220 => 240.0 - 140.0 * (y - 100) as f32 / 120.0,
            _ => 100.0,
        };
        for _ in 0..W {
            for warm in [1.0f32, 0.95, 0.85] {
                seed ^= seed << 13;
                seed ^= seed >> 7;
                seed ^= seed << 17;
                let g = (seed % 5) as f32 - 2.0;
                data.push((v * warm + g).round().clamp(0.0, 255.0) as u8);
            }
        }
    }
    data
}

/// The worst difference from `clean`, in levels, over the pixels of rows `ys` and columns `xs`.
fn worst_off(out: &[u8], clean: &[u8], xs: std::ops::Range<usize>, ys: std::ops::Range<usize>) -> f32 {
    let mut worst = 0f32;
    for y in ys {
        for x in xs.clone() {
            let (a, b) = (px(out, x, y), px(clean, x, y));
            for c in 0..3 {
                worst = worst.max((a[c] - b[c]).abs());
            }
        }
    }
    worst
}

/// #503: a blot on paper shaded across a band, rubbed with the edge fill, is the shading again (the
/// exact answer is the clean page), where the paper fill, which takes each block's lighter half
/// and fills ring by ring, leaves a step. Measured here: paper fill and edge fill against the
/// clean page at every pixel inside the blot.
#[test]
fn the_edge_fill_continues_a_shaded_band() {
    let clean = shaded_page();
    let mut blotted = clean.clone();
    for y in 140..180 {
        for x in 60..340 {
            let i = (y * W + x) * 3;
            blotted[i..i + 3].copy_from_slice(&[40, 30, 30]);
        }
    }
    let run = |tool: Tool| {
        let mut pixels = Pixels::Rgb(blotted.clone());
        let rub = stroke(tool, [0; 3], 56.0 / 600.0, &[(50.0 / W as f32, 160.0 / H as f32), (350.0 / W as f32, 160.0 / H as f32)]);
        let done = apply(&mut pixels, W as u32, H as u32, 0, &marks(vec![rub]));
        let Pixels::Rgb(out) = pixels else { panic!("still colour") };
        (done, out)
    };
    let (paper_done, paper) = run(Tool::Erase);
    let (edge_done, edge) = run(Tool::EraseEdge);
    assert_eq!((paper_done.erase, paper_done.erase_edge), (1, 0));
    assert_eq!((edge_done.erase, edge_done.erase_edge), (1, 1));
    let paper_off = worst_off(&paper, &clean, 64..336, 142..178);
    let edge_off = worst_off(&edge, &clean, 64..336, 142..178);
    eprintln!("shaded band: paper fill {paper_off} levels off, edge fill {edge_off}");
    assert!(edge_off < 9.0, "the edge fill is {edge_off} levels off the shading");
    assert!(paper_off > 2.0 * edge_off, "paper {paper_off} edge {edge_off}");
    // it is shaded, not one value: the top of the fill is lighter than its bottom, as the band is
    let (top, bottom) = (px(&edge, 200, 143)[0], px(&edge, 200, 177)[0]);
    assert!(top - bottom > 30.0, "top {top} bottom {bottom}");
    // and it has grain
    let row: Vec<f32> = (150..170).map(|x| px(&edge, x, 160)[1]).collect();
    let mean = row.iter().sum::<f32>() / row.len() as f32;
    let spread = (row.iter().map(|v| (v - mean).powi(2)).sum::<f32>() / row.len() as f32).sqrt();
    assert!(spread > 0.3, "flat fill, spread {spread}");
}

/// #503: the edge fill on the tinted page whose light falls off is as good as the paper fill there
/// (the paper fill's own test, the same blot and brush).
#[test]
fn the_edge_fill_also_paints_plain_paper() {
    let clean = tinted_page();
    let mut blotted = clean.clone();
    for y in 100..140 {
        for x in 60..340 {
            let i = (y * W + x) * 3;
            blotted[i..i + 3].copy_from_slice(&[90, 70, 60]);
        }
    }
    let mut pixels = Pixels::Rgb(blotted);
    let rub = stroke(Tool::EraseEdge, [0; 3], 50.0 / 600.0, &[(50.0 / W as f32, 120.0 / H as f32), (350.0 / W as f32, 120.0 / H as f32)]);
    apply(&mut pixels, W as u32, H as u32, 0, &marks(vec![rub]));
    let Pixels::Rgb(out) = pixels else { panic!("still colour") };
    let off = worst_off(&out, &clean, 64..336, 104..136);
    eprintln!("tinted paper: edge fill {off} levels off");
    assert!(off < 8.0, "the edge fill is {off} levels off the paper");
    // the print outside the brush is untouched
    assert_eq!(worst_off(&out, &clean, 0..W, 250..350), 0.0);
}

/// #503: the colour fill paints the picked colour, opaque, inside the brush, with a soft edge (a
/// pixel at the edge is between the colour and the page) and nothing beyond it.
#[test]
fn the_colour_fill_paints_the_picked_colour() {
    let clean = tinted_page();
    let mut pixels = Pixels::Rgb(clean.clone());
    // a brush 40 px wide along y 120
    let rub = stroke(Tool::EraseColour, [30, 90, 200], 40.0 / 600.0, &[(0.2, 120.0 / H as f32), (0.8, 120.0 / H as f32)]);
    let done = apply(&mut pixels, W as u32, H as u32, 0, &marks(vec![rub]));
    let Pixels::Rgb(out) = pixels else { panic!("still colour") };
    assert_eq!((done.erase, done.erase_colour, done.erase_edge), (1, 1, 0));
    assert_eq!(px(&out, 200, 120), [30.0, 90.0, 200.0]);
    assert_eq!(px(&out, 200, 112), [30.0, 90.0, 200.0]);
    // the radius is 20 and the soft edge 5 wide on each side of it: at 20 px off the line, half way
    let edge = px(&out, 200, 140);
    let page = px(&clean, 200, 140);
    assert!(edge[2] > page[2].min(200.0) && edge[0] < page[0] && edge[0] > 30.0, "{edge:?} page {page:?}");
    assert_eq!(px(&out, 200, 146), px(&clean, 200, 146));
}

/// #503: on a black-and-white page the colour fill is ink or paper, whichever the colour is
/// nearer, and the edge fill continues what is around it.
#[test]
fn a_black_and_white_page_gets_ink_or_paper_from_the_new_fills() {
    let stride = W.div_ceil(8);
    let white = || Pixels::Bilevel(Bitmap1 { width: W as u32, height: H as u32, data: vec![0xFF; stride * H] });
    let mut pixels = white();
    let dark = stroke(Tool::EraseColour, [40, 40, 120], 40.0 / 600.0, &[(0.5, 0.3)]);
    let light = stroke(Tool::EraseColour, [250, 240, 200], 40.0 / 600.0, &[(0.5, 0.7)]);
    let done = apply(&mut pixels, W as u32, H as u32, 0, &marks(vec![dark, light]));
    assert_eq!(done.erase_colour, 2);
    let Pixels::Bilevel(out) = pixels else { panic!("still black and white") };
    assert!(out.is_black(W as u32 / 2, (0.3 * H as f32) as u32));
    assert!(!out.is_black(W as u32 / 2, (0.7 * H as f32) as u32));
    // all ink with a white dot in the middle: the edge fill around the dot is ink
    let mut bits = vec![0u8; stride * H];
    bits[300 * stride + 25] = 0xFF;
    let mut pixels = Pixels::Bilevel(Bitmap1 { width: W as u32, height: H as u32, data: bits });
    apply(&mut pixels, W as u32, H as u32, 0, &marks(vec![stroke(Tool::EraseEdge, [0; 3], 40.0 / 600.0, &[(203.0 / W as f32, 0.5)])]));
    let Pixels::Bilevel(out) = pixels else { panic!("still black and white") };
    assert!(out.is_black(203, 300));
}

/// #503: strokes of the old tools draw exactly as before when new fills are in the same marks
/// (each fill is measured on its own run of strokes).
#[test]
fn the_new_fills_leave_the_others_alone() {
    let old = vec![stroke(Tool::Erase, [0; 3], 0.05, &[(0.2, 0.2), (0.7, 0.3)]), stroke(Tool::Pen, [10, 20, 200], 0.01, &[(0.1, 0.8), (0.9, 0.85)])];
    let mut a = Pixels::Rgb(tinted_page());
    apply(&mut a, W as u32, H as u32, 0, &marks(old.clone()));
    let mut b = Pixels::Rgb(tinted_page());
    let mut with_new = old;
    // far from the others, at the bottom of the page
    with_new.push(stroke(Tool::EraseColour, [200, 0, 0], 0.02, &[(0.5, 0.98)]));
    with_new.push(stroke(Tool::EraseEdge, [0; 3], 0.02, &[(0.1, 0.98)]));
    apply(&mut b, W as u32, H as u32, 0, &marks(with_new));
    let (Pixels::Rgb(a), Pixels::Rgb(b)) = (a, b) else { panic!("still colour") };
    assert_eq!(a[..W * 3 * 560], b[..W * 3 * 560]);
}
