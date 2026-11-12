//! Fingers, glare and stains (#306), on pages drawn here: what is found, what is filled, and
//! that nothing over print is filled.

use crate::defects::{fill_blank, find, normalise_stains};
use crate::warp::Rgb;

const W: u32 = 1200;
const H: u32 = 1600;
const PAPER: [u8; 3] = [245, 243, 238];
const INK: [u8; 3] = [30, 30, 35];

fn sheet(colour: [u8; 3]) -> Rgb {
    Rgb { width: W, height: H, data: colour.iter().copied().cycle().take((W * H * 3) as usize).collect() }
}

fn set(page: &mut Rgb, x: u32, y: u32, c: [u8; 3]) {
    let i = ((y * page.width + x) * 3) as usize;
    page.data[i..i + 3].copy_from_slice(&c);
}

fn get(page: &Rgb, x: u32, y: u32) -> [u8; 3] {
    let i = ((y * page.width + x) * 3) as usize;
    [page.data[i], page.data[i + 1], page.data[i + 2]]
}

/// Lines of "print": bars 10 px high every 30 px, with gaps for words, inside the rectangle.
fn print(page: &mut Rgb, x0: u32, y0: u32, x1: u32, y1: u32) {
    for y in y0..y1 {
        if (y - y0) % 30 >= 10 {
            continue;
        }
        for x in x0..x1 {
            if (x - x0) % 70 < 58 {
                set(page, x, y, INK);
            }
        }
    }
}

/// A page with print in its middle and blank margins.
fn printed() -> Rgb {
    let mut page = sheet(PAPER);
    print(&mut page, 150, 200, 1050, 1100);
    page
}

/// A finger: a rounded bar `width` px wide reaching `length` px into the page from the bottom
/// edge, centred at `cx`, in a skin colour, with a soft shading across it.
fn finger_from_bottom(page: &mut Rgb, cx: u32, width: u32, length: u32) {
    let r = width as f32 / 2.0;
    for y in H - length..H {
        for x in cx - width / 2..cx + width / 2 {
            let dx = x as f32 - cx as f32;
            let tip = (H - length) as f32 + r;
            if (y as f32) < tip && dx.hypot(y as f32 - tip) > r {
                continue;
            }
            let shade = 1.0 - 0.15 * (dx / r).powi(2);
            set(page, x, y, [(212.0 * shade) as u8, (160.0 * shade) as u8, (128.0 * shade) as u8]);
        }
    }
}

/// The same from the left edge, at height `cy`.
fn finger_from_left(page: &mut Rgb, cy: u32, width: u32, length: u32) {
    let r = width as f32 / 2.0;
    for x in 0..length {
        for y in cy - width / 2..cy + width / 2 {
            let dy = y as f32 - cy as f32;
            let tip = length as f32 - r;
            if (x as f32) > tip && dy.hypot(x as f32 - tip) > r {
                continue;
            }
            set(page, x, y, [212, 160, 128]);
        }
    }
}

fn same(a: &Rgb, b: &Rgb) -> bool {
    a.data == b.data
}

/// The issue's first test, part one: a finger on a blank margin is found and filled with paper.
#[test]
fn a_finger_on_blank_margin_is_filled() {
    let mut page = printed();
    finger_from_bottom(&mut page, 300, 110, 260);
    let found = find(&page, None).unwrap();
    assert_eq!(found.fingers.len(), 1, "{:?}", found.fingers.iter().map(|r| r.area).collect::<Vec<_>>());
    assert!(!found.fingers[0].covers_ink);
    assert_eq!(found.flagged(), 0);
    let mut fixed = page.clone();
    assert_eq!(fill_blank(&mut fixed, &found), 1);
    // where the finger was is paper now, to within the grain
    for (x, y) in [(300, 1590), (300, 1450), (260, 1500), (340, 1500)] {
        let c = get(&fixed, x, y);
        for k in 0..3 {
            assert!(c[k].abs_diff(PAPER[k]) <= 6, "({x}, {y}): {c:?}");
        }
    }
    // and the print is untouched
    for (x, y) in [(150, 200), (500, 1095), (1040, 600)] {
        assert_eq!(get(&fixed, x, y), get(&page, x, y), "({x}, {y})");
    }
}

/// The issue's first test, part two: the same finger over print is flagged and not filled.
#[test]
fn a_finger_over_print_is_flagged_not_filled() {
    let mut page = printed();
    finger_from_left(&mut page, 600, 110, 300);
    let found = find(&page, None).unwrap();
    assert_eq!(found.fingers.len(), 1);
    assert!(found.fingers[0].covers_ink);
    assert_eq!(found.flagged(), 1);
    let mut fixed = page.clone();
    assert_eq!(fill_blank(&mut fixed, &found), 0);
    assert!(same(&fixed, &page));
}

/// A glare spot: in the page before correction, a clipped core with a halo of light falling off
/// into darker paper. `over_print` puts it on the print.
fn glare_pair(over_print: bool) -> (Rgb, Rgb) {
    let mut after = printed();
    let mut before = sheet([222, 220, 214]);
    print(&mut before, 150, 200, 1050, 1100);
    let (cx, cy) = if over_print { (600.0, 600.0) } else { (600.0, 1350.0) };
    for y in 0..H {
        for x in 0..W {
            let d = (x as f32 - cx).hypot(y as f32 - cy);
            if d > 160.0 {
                continue;
            }
            // clipped inside 60 px, the halo fading to the paper by 120 px
            let lift = if d <= 60.0 { 1.0 } else { (1.0 - (d - 60.0) / 60.0).max(0.0) };
            for (page, base) in [(&mut before, [222f32, 220.0, 214.0]), (&mut after, [245.0, 243.0, 238.0])] {
                let c = get(page, x, y);
                if c == INK && d > 60.0 {
                    continue;
                }
                let v = base.map(|b| (b + (255.0 - b) * lift * 1.2).min(255.0) as u8);
                set(page, x, y, v);
            }
        }
    }
    (after, before)
}

/// The issue's glare test: a spot over blank paper is filled; over print it is flagged.
#[test]
fn glare_on_blank_paper_is_filled_and_over_print_flagged() {
    let (after, before) = glare_pair(false);
    let found = find(&after, Some(&before)).unwrap();
    assert_eq!(found.glare.len(), 1, "{found:?}");
    assert!(!found.glare[0].covers_ink);
    let mut fixed = after.clone();
    assert_eq!(fill_blank(&mut fixed, &found), 1);
    let c = get(&fixed, 600, 1350);
    assert!(c.iter().zip(PAPER).all(|(a, b)| a.abs_diff(b) <= 6), "{c:?}");

    let (after, before) = glare_pair(true);
    let found = find(&after, Some(&before)).unwrap();
    assert_eq!(found.glare.len(), 1);
    assert!(found.glare[0].covers_ink);
    let mut fixed = after.clone();
    assert_eq!(fill_blank(&mut fixed, &found), 0);
    assert!(same(&fixed, &after));
}

/// Plain white paper next to dark print is not glare: there is no halo.
#[test]
fn white_paper_is_not_glare() {
    let mut before = sheet([252, 252, 251]);
    print(&mut before, 150, 200, 1050, 1100);
    let found = find(&printed(), Some(&before)).unwrap();
    assert!(found.glare.is_empty(), "{found:?}");
}

/// The issue's stain test: a semi-transparent brown stain over print. After normalising, the
/// paper inside is within 6 levels of the paper outside, and the print's contrast inside falls by
/// less than 15 %.
#[test]
fn a_stain_over_print_is_divided_out() {
    let mut page = printed();
    let (cx, cy, r) = (600.0f32, 650.0f32, 170.0f32);
    let tint = [0.86f32, 0.76, 0.60];
    for y in 0..H {
        for x in 0..W {
            if (x as f32 - cx).hypot(y as f32 - cy) <= r {
                let c = get(&page, x, y);
                set(&mut page, x, y, [0, 1, 2].map(|k| (f32::from(c[k]) * tint[k]).round() as u8));
            }
        }
    }
    let found = find(&page, None).unwrap();
    assert_eq!(found.stains.len(), 1, "{found:?}");
    assert!(found.fingers.is_empty() && found.glare.is_empty());
    let mut fixed = page.clone();
    assert_eq!(normalise_stains(&mut fixed, &found), 1);
    // paper between lines inside the stain, and outside it
    let (inside_paper, inside_ink) = (get(&fixed, 600, 635), get(&fixed, 600, 620));
    let (outside_paper, outside_ink) = (get(&fixed, 200, 635), get(&fixed, 200, 620));
    for k in 0..3 {
        assert!(inside_paper[k].abs_diff(outside_paper[k]) <= 6, "paper {inside_paper:?} against {outside_paper:?}");
    }
    let contrast = |p: [u8; 3], i: [u8; 3]| (0..3).map(|k| f32::from(p[k]) - f32::from(i[k])).sum::<f32>();
    let (c_in, c_out) = (contrast(inside_paper, inside_ink), contrast(outside_paper, outside_ink));
    assert!(c_in > 0.85 * c_out, "contrast {c_in} inside against {c_out} outside");
}

/// No detector fires on a clean page, on a stamp and a signature, on a photograph, or on
/// coloured paper.
#[test]
fn nothing_is_found_on_ordinary_pages() {
    let mut cases: Vec<(&str, Rgb)> = vec![("clean", printed())];

    let mut stamped = printed();
    // a blue stamp ring 4 px wide, and a signature: a dark wavy line
    for y in 1150..1450u32 {
        for x in 700..1000u32 {
            let d = (x as f32 - 850.0).hypot(y as f32 - 1300.0);
            if (110.0..114.0).contains(&d) || (90.0..92.0).contains(&d) {
                set(&mut stamped, x, y, [70, 90, 190]);
            }
        }
    }
    for x in 200..600u32 {
        let y = 1300.0 + 30.0 * (x as f32 / 25.0).sin();
        for t in 0..3 {
            set(&mut stamped, x, y as u32 + t, [25, 25, 60]);
        }
    }
    cases.push(("stamp and signature", stamped));

    let mut photo = printed();
    let mut seed = 12345u32;
    for y in 1150..1500u32 {
        for x in 300..900u32 {
            seed = seed.wrapping_mul(1_103_515_245).wrapping_add(12345);
            let n = (seed >> 16) as u8;
            let base = [120 + (x / 4 % 60) as u8, 90 + (y / 3 % 80) as u8, 140];
            set(&mut photo, x, y, base.map(|b| b.saturating_add(n / 3).saturating_sub(40)));
        }
    }
    cases.push(("photograph", photo));

    let mut yellow = sheet([250, 238, 180]);
    print(&mut yellow, 150, 200, 1050, 1100);
    cases.push(("coloured paper", yellow));

    for (name, page) in cases {
        let found = find(&page, Some(&page)).unwrap();
        assert!(found.fingers.is_empty() && found.glare.is_empty() && found.stains.is_empty(), "{name}: {found:?}");
    }
}

/// Bad buffers are refused; the same page gives the same answer and pixels every time.
#[test]
fn defects_are_bounded_and_deterministic() {
    let bad = Rgb { width: 10, height: 10, data: vec![0; 5] };
    assert!(find(&bad, None).is_none());
    let mut page = printed();
    finger_from_bottom(&mut page, 300, 110, 260);
    let other = Rgb { width: 20, height: 20, data: vec![0; 1200] };
    assert!(find(&page, Some(&other)).is_none());
    let (a, b) = (find(&page, None).unwrap(), find(&page, None).unwrap());
    assert_eq!(a, b);
    let (mut x, mut y) = (page.clone(), page.clone());
    fill_blank(&mut x, &a);
    fill_blank(&mut y, &b);
    assert!(same(&x, &y));
}
