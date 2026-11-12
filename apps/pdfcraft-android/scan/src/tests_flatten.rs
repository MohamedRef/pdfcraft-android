//! Page flattening (#302): the sampling along a grid on pages bent in a known way, the
//! conventions of the network's answer, and the network itself on drawn curled pages.

use pdfcraft_geom::perspective::{Homography, Point};

use crate::flatten::{BENT, FlattenGrid, View, flat_grid, grid_in_photo, predict_grid, unwarp, unwarp_trimmed};
use crate::flatten_guard::{Trim, border_trim};
use crate::warp::rectify_with_threads;
use crate::{Photo, Quad};

const COLS: usize = 31;
const ROWS: usize = 45;

/// A page in a photo: where each point of the flat page (shares 0–1 of its width and height) is
/// in the photo. A perspective view, with the page bowed by `bow` photo pixels along its height
/// (as if curled about a vertical axis) and sagging by `sag` along its width.
struct Bent {
    view: Homography,
    bow: f64,
    sag: f64,
}

impl Bent {
    fn new(quad: [Point; 4], bow: f64, sag: f64) -> Bent {
        Bent { view: Homography::from_points([[0.0, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 1.0]], quad).unwrap(), bow, sag }
    }

    fn to_photo(&self, [u, v]: Point) -> Point {
        let [x, y] = self.view.apply([u, v]).unwrap();
        // the middle of the page comes towards the camera: lines of text arch, edges bulge
        [x + self.sag * (std::f64::consts::PI * v).sin() * (u - 0.5), y - self.bow * (std::f64::consts::PI * u).sin()]
    }

    /// The exact grid for this page.
    fn grid(&self, cols: usize, rows: usize) -> FlattenGrid {
        let xy = (0..rows)
            .flat_map(|r| (0..cols).map(move |c| (c, r)))
            .flat_map(|(c, r)| self.to_photo([c as f64 / (cols - 1) as f64, r as f64 / (rows - 1) as f64]).map(|v| v as f32))
            .collect();
        FlattenGrid { cols, rows, xy }
    }

    /// The page point seen at a photo point (the bend is small against the page, so stepping back
    /// through the perspective view and correcting converges in a few rounds).
    fn to_page(&self, p: Point, back: &Homography) -> Point {
        let mut uv = back.apply(p).unwrap_or([0.5, 0.5]);
        for _ in 0..8 {
            let at = self.to_photo(uv);
            let flat = self.view.apply(uv).unwrap_or(p);
            uv = back.apply([flat[0] + p[0] - at[0], flat[1] + p[1] - at[1]]).unwrap_or(uv);
        }
        uv
    }

    /// A photo of `page` lying like this on a dark table (each pixel 3 × 3 samples).
    fn photograph(&self, page: &Photo, w: u32, h: u32) -> Photo {
        let back = Homography::from_points(
            self.view
                .apply([0.0, 0.0])
                .into_iter()
                .chain(self.view.apply([1.0, 0.0]))
                .chain(self.view.apply([1.0, 1.0]))
                .chain(self.view.apply([0.0, 1.0]))
                .collect::<Vec<_>>()
                .try_into()
                .unwrap(),
            [[0.0, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 1.0]],
        )
        .unwrap();
        let (pw, ph) = (f64::from(page.width), f64::from(page.height));
        let mut rgb = Vec::with_capacity((w * h * 3) as usize);
        for y in 0..h {
            for x in 0..w {
                let mut sum = [0.0f64; 3];
                for k in 0..9 {
                    let [u, v] = self.to_page([f64::from(x) + (f64::from(k % 3) + 0.5) / 3.0, f64::from(y) + (f64::from(k / 3) + 0.5) / 3.0], &back);
                    let c = if (0.0..1.0).contains(&u) && (0.0..1.0).contains(&v) {
                        let i = ((v * ph) as usize * page.width as usize + (u * pw) as usize) * 3;
                        [f64::from(page.rgb[i]), f64::from(page.rgb[i + 1]), f64::from(page.rgb[i + 2])]
                    } else {
                        [70.0 + f64::from((x / 31 + y / 23) % 2) * 6.0, 58.0, 48.0]
                    };
                    for (s, c) in sum.iter_mut().zip(c) {
                        *s += c;
                    }
                }
                rgb.extend(sum.map(|v| (v / 9.0).round() as u8));
            }
        }
        Photo { width: w, height: h, rgb }
    }
}

/// A page of ruled lines of print: rows of dark dashes, a frame, a heading bar.
fn printed(w: u32, h: u32) -> Photo {
    let mut rgb = vec![238u8; (w * h * 3) as usize];
    for y in 0..h {
        for x in 0..w {
            let frame = (x >= 24 && x < w - 24 && y >= 24 && y < h - 24) && (x < 28 || x >= w - 28 || y < 28 || y >= h - 28);
            let heading = (60..90).contains(&y) && (60..w * 2 / 3).contains(&x);
            let line = y >= 130 && y < h - 60 && (y - 130) % 26 < 9 && x >= 60 && x < w - 60 && ((x / 17) * 5 + (y / 26) * 3) % 7 != 0;
            if frame || heading || line {
                let i = ((y * w + x) * 3) as usize;
                rgb[i..i + 3].copy_from_slice(&[35, 35, 42]);
            }
        }
    }
    Photo { width: w, height: h, rgb }
}

fn psnr(a: &[u8], b: &[u8]) -> f64 {
    let mse = a.iter().zip(b).map(|(x, y)| (f64::from(*x) - f64::from(*y)).powi(2)).sum::<f64>() / a.len() as f64;
    10.0 * (255.0 * 255.0 / mse.max(1e-9)).log10()
}

/// How far the page's printed frame is from straight in a flattened page: the spread (largest
/// minus smallest) of the height of its top line across nine columns, and of its bottom line,
/// averaged, in pixels. A strip of table left along an edge is stepped over.
fn waviness(page: &crate::warp::Rgb) -> f64 {
    let (w, h) = (page.width as usize, page.height as usize);
    let dark = |x: usize, y: usize| page.data[(y * w + x) * 3] < 128;
    // from one end: past any table, then the first print met
    let first = |x: usize, rows: &mut dyn Iterator<Item = usize>| rows.skip_while(|&y| dark(x, y)).find(|&y| dark(x, y)).map(|y| y as f64);
    let columns: Vec<usize> = (1..10).map(|k| w * k / 10).collect();
    let tops: Vec<f64> = columns.iter().filter_map(|&x| first(x, &mut (0..h / 3))).collect();
    let bottoms: Vec<f64> = columns.iter().filter_map(|&x| first(x, &mut (h * 2 / 3..h).rev())).collect();
    let spread = |v: &[f64]| v.iter().copied().fold(f64::MIN, f64::max) - v.iter().copied().fold(f64::MAX, f64::min);
    if tops.len() < 9 || bottoms.len() < 9 {
        return f64::MAX;
    }
    (spread(&tops) + spread(&bottoms)) / 2.0
}

const QUAD: [Point; 4] = [[190.0, 210.0], [1010.0, 250.0], [1060.0, 1420.0], [150.0, 1380.0]];

#[test]
fn a_page_bent_in_a_known_way_comes_back_flat_from_its_exact_grid() {
    let page = printed(600, 848);
    let bent = Bent::new(QUAD, 28.0, 22.0);
    let photo = bent.photograph(&page, 1200, 1600);
    let grid = bent.grid(COLS, ROWS);
    let flat = unwarp(&photo, &grid, 600, 848, 2).unwrap();
    let db = psnr(&flat.data, &page.rgb);
    println!("exact grid: PSNR {db:.1} dB, frame waviness {:.2} px; bend {:.2} %", waviness(&flat), 100.0 * grid.bend());
    assert!(db >= 22.0 && waviness(&flat) <= 1.0, "PSNR {db}, waviness {}", waviness(&flat));
    // the perspective warp alone leaves the lines bent
    let plain = rectify_with_threads(&photo, &Quad::new(QUAD).unwrap(), 600, 848, 2).unwrap();
    println!("perspective only: PSNR {:.1} dB, frame waviness {:.2} px", psnr(&plain.data, &page.rgb), waviness(&plain));
    assert!(waviness(&plain) > 5.0 && psnr(&plain.data, &page.rgb) < db - 3.0);
    // the same pixels on any number of threads
    assert_eq!(unwarp(&photo, &grid, 600, 848, 1).unwrap(), flat);
    assert_eq!(unwarp(&photo, &grid, 600, 848, 4).unwrap(), flat);
}

#[test]
fn a_flat_pages_grid_is_the_perspective_warp() {
    let page = printed(600, 848);
    let flat_page = Bent::new(QUAD, 0.0, 0.0);
    let photo = flat_page.photograph(&page, 1200, 1600);
    let quad = Quad::new(QUAD).unwrap();
    let grid = flat_grid(&quad, COLS, ROWS).unwrap();
    assert!(grid.bend() < 1e-4, "{}", grid.bend());
    let (ours, plain) = (unwarp(&photo, &grid, 600, 848, 2).unwrap(), rectify_with_threads(&photo, &quad, 600, 848, 2).unwrap());
    let mean = ours.data.iter().zip(&plain.data).map(|(a, b)| f64::from(a.abs_diff(*b))).sum::<f64>() / ours.data.len() as f64;
    println!("flat grid against the perspective warp: mean difference {mean:.2} grey levels");
    assert!(mean <= 1.5, "{mean}");
    // how bent a grid is: nothing for any perspective view, and what was put in for a bent page
    let tilted = flat_grid(&Quad::new([[300.0, 100.0], [900.0, 220.0], [1100.0, 1500.0], [80.0, 1100.0]]).unwrap(), COLS, ROWS).unwrap();
    assert!(tilted.bend() < 1e-4);
    let bent = Bent::new(QUAD, 28.0, 0.0).grid(COLS, ROWS);
    // a bow of 28 px against a diagonal of about 1460 px: under 2 %, and well over the threshold
    assert!(bent.bend() > BENT && bent.bend() < 0.02, "{}", bent.bend());
}

/// The share of a page's edge column or row (`side`: 0 left, 1 top, 2 right, 3 bottom) that is
/// dark like the table.
fn dark_edge(page: &crate::warp::Rgb, side: u8) -> f64 {
    let (w, h) = (page.width as usize, page.height as usize);
    let px: Vec<usize> = match side {
        0 => (0..h).map(|y| y * w).collect(),
        1 => (0..w).collect(),
        2 => (0..h).map(|y| y * w + w - 1).collect(),
        _ => (0..w).map(|x| (h - 1) * w + x).collect(),
    };
    px.iter().filter(|&&i| page.data[i * 3] < 128).count() as f64 / px.len() as f64
}

#[test]
fn a_strip_of_table_along_a_flattened_pages_edge_is_trimmed_and_the_page_keeps_its_size() {
    // #302, section 3: a grid that reaches a little past the page, as the network's can, leaves
    // a strip of table along every edge
    let page = printed(600, 848);
    let bent = Bent::new(QUAD, 20.0, 0.0);
    let photo = bent.photograph(&page, 1200, 1600);
    let centre = [QUAD.iter().map(|p| p[0]).sum::<f64>() / 4.0, QUAD.iter().map(|p| p[1]).sum::<f64>() / 4.0];
    let wide = QUAD.map(|[x, y]| [centre[0] + (x - centre[0]) * 1.025, centre[1] + (y - centre[1]) * 1.025]);
    let grid = Bent::new(wide, 20.0, 0.0).grid(COLS, ROWS);
    let flat = unwarp(&photo, &grid, 600, 848, 2).unwrap();
    for side in 0..4 {
        assert!(dark_edge(&flat, side) > 0.9, "side {side}: the strip of table is there before the trim ({})", dark_edge(&flat, side));
    }
    let trim = border_trim(&flat);
    println!("trim {trim:?} on a 600 × 848 page");
    // a little over 1 % of each side is table; never more than 2 % is cut
    for (px, of) in [(trim.left, 600), (trim.top, 848), (trim.right, 600), (trim.bottom, 848)] {
        assert!(px > 0 && f64::from(px) <= 0.02 * f64::from(of) + 1.0, "{trim:?}");
    }
    let trimmed = unwarp_trimmed(&photo, &grid, 600, 848, trim, 2).unwrap();
    assert_eq!((trimmed.width, trimmed.height), (600, 848), "drawn again at the page's own size");
    for side in 0..4 {
        assert!(dark_edge(&trimmed, side) < 0.2, "side {side}: still table after the trim ({})", dark_edge(&trimmed, side));
    }
    // the print stays straight, and the same pixels come out on any number of threads
    assert!(waviness(&trimmed) <= 1.5, "{}", waviness(&trimmed));
    assert_eq!(unwarp_trimmed(&photo, &grid, 600, 848, trim, 1).unwrap(), trimmed);
    assert_eq!(unwarp_trimmed(&photo, &grid, 600, 848, trim, 4).unwrap(), trimmed);
    // no trim is the page as unwarp makes it; a trim of half a side or more is refused
    assert_eq!(unwarp_trimmed(&photo, &grid, 600, 848, Trim::default(), 2).unwrap(), flat);
    assert!(unwarp_trimmed(&photo, &grid, 600, 848, Trim { left: 200, right: 120, ..Trim::default() }, 2).is_err());
    // the exact grid of the page has no strip, and nothing is trimmed from it
    let exact = unwarp(&photo, &bent.grid(COLS, ROWS), 600, 848, 2).unwrap();
    let none = border_trim(&exact);
    assert!(none.left + none.top + none.right + none.bottom <= 4, "{none:?}");
}

#[test]
fn no_kinks_between_the_grids_points() {
    // a straight line across a strongly bowed page: where the map takes it from in the photo,
    // pixel by pixel, bends smoothly across every border between grid cells (30 pixels wide here)
    let bent = Bent::new(QUAD, 40.0, 30.0);
    let grid = bent.grid(COLS, ROWS);
    let (w, h) = (900u32, 1272u32);
    let mut worst_jump: f64 = 0.0;
    let mut worst_off: f64 = 0.0;
    for row in [100.5, 400.5, 636.5, 1100.5] {
        let line: Vec<Point> = (0..w).map(|x| crate::flatten::photo_point(&grid, w, h, [f64::from(x) + 0.5, row])).collect();
        // the bend of the line (second difference) changes by little from one pixel to the next
        let bend: Vec<f64> = (1..line.len() - 1).map(|i| line[i - 1][1] - 2.0 * line[i][1] + line[i + 1][1]).collect();
        worst_jump = bend.windows(2).map(|b| (b[1] - b[0]).abs()).fold(worst_jump, f64::max);
        // and the map is the page's true shape between the grid's points too
        for (x, p) in line.iter().enumerate() {
            let truth = bent.to_photo([(x as f64 + 0.5) / f64::from(w), row / f64::from(h)]);
            worst_off = worst_off.max((p[0] - truth[0]).hypot(p[1] - truth[1]));
        }
    }
    println!("between grid points: largest jump in bend {worst_jump:.4} px, furthest from the true shape {worst_off:.3} px");
    assert!(worst_jump < 0.02 && worst_off < 0.2, "{worst_jump} {worst_off}");
}

#[test]
fn the_networks_answer_is_read_in_the_pages_own_orientation() {
    // an answer that says "the page is exactly what I was shown": x from −1 to 1 along each row
    let identity: Vec<f32> = (0..2 * ROWS * COLS)
        .map(|i| {
            let (plane, at) = (i / (ROWS * COLS), i % (ROWS * COLS));
            if plane == 0 { (at % COLS) as f32 / (COLS - 1) as f32 * 2.0 - 1.0 } else { (at / COLS) as f32 / (ROWS - 1) as f32 * 2.0 - 1.0 }
        })
        .collect();
    // an upright page: the view is the page with a margin of 8 % of its size all round
    let page = [[200.0, 100.0], [800.0, 100.0], [800.0, 1000.0], [200.0, 1000.0]];
    let upright = View::of(&Quad::new(page).unwrap()).unwrap();
    assert!(!upright.turned);
    let near = |a: Point, b: Point| (a[0] - b[0]).hypot(a[1] - b[1]) < 0.01;
    assert!(near(upright.to_photo([0.0, 0.0]), [200.0 - 48.0, 100.0 - 72.0]) && near(upright.to_photo([1.0, 1.0]), [800.0 + 48.0, 1000.0 + 72.0]));
    let grid = grid_in_photo(&identity, &upright);
    assert_eq!((grid.cols, grid.rows), (COLS, ROWS));
    // −1 and 1 are the centres of the network's first and last pixels
    let at = |g: &crate::FlattenGrid, c: usize, r: usize| [f64::from(g.xy[(r * g.cols + c) * 2]), f64::from(g.xy[(r * g.cols + c) * 2 + 1])];
    let (w, h) = (600.0 * 1.16, 900.0 * 1.16);
    assert!(near(at(&grid, 0, 0), [152.0 + 0.5 * w / 488.0, 28.0 + 0.5 * h / 712.0]), "{:?}", at(&grid, 0, 0));
    assert!(near(at(&grid, COLS - 1, ROWS - 1), [848.0 - 0.5 * w / 488.0, 1072.0 - 0.5 * h / 712.0]));
    // a page on its side is shown to the network turned a quarter clockwise, and its grid comes
    // back the page's own way round: 45 points along its width, the first at its top left
    let sideways = View::of(&Quad::new([[100.0, 200.0], [1000.0, 200.0], [1000.0, 800.0], [100.0, 800.0]]).unwrap()).unwrap();
    assert!(sideways.turned);
    let grid = grid_in_photo(&identity, &sideways);
    assert_eq!((grid.cols, grid.rows), (ROWS, COLS));
    let (left, top, right, bottom) = (100.0 - 72.0, 200.0 - 48.0, 1000.0 + 72.0, 800.0 + 48.0);
    let (tl, tr, bl) = (at(&grid, 0, 0), at(&grid, ROWS - 1, 0), at(&grid, 0, COLS - 1));
    assert!((tl[0] - left).abs() < 2.0 && (tl[1] - top).abs() < 2.0, "top left {tl:?}");
    assert!((tr[0] - right).abs() < 2.0 && (tr[1] - top).abs() < 2.0, "top right {tr:?}");
    assert!((bl[0] - left).abs() < 2.0 && (bl[1] - bottom).abs() < 2.0, "bottom left {bl:?}");
    assert!(grid.bend() < 1e-3);
    // a page lying at an angle is shown upright: the middle of the view is the middle of the page
    let tilted = View::of(&Quad::new(QUAD).unwrap()).unwrap();
    let middle = tilted.to_photo([0.5, 0.5]);
    let centre = Bent::new(QUAD, 0.0, 0.0).to_photo([0.5, 0.5]);
    assert!(near(middle, centre), "{middle:?} against {centre:?}");
}

#[test]
fn grids_that_make_no_sense_are_refused() {
    let photo = Photo { width: 40, height: 30, rgb: vec![200; 3600] };
    let grid = flat_grid(&Quad::new([[5.0, 5.0], [35.0, 5.0], [35.0, 25.0], [5.0, 25.0]]).unwrap(), 4, 3).unwrap();
    assert!(unwarp(&photo, &grid, 30, 20, 1).is_ok());
    assert!(unwarp(&photo, &grid, 1, 20, 1).is_err());
    assert!(unwarp(&photo, &FlattenGrid { cols: 4, rows: 3, xy: vec![0.0; 5] }, 30, 20, 1).is_err());
    assert!(unwarp(&photo, &FlattenGrid { xy: grid.xy.iter().map(|_| f32::NAN).collect(), ..grid.clone() }, 30, 20, 1).is_err());
    assert!(unwarp(&Photo { width: 40, height: 30, rgb: vec![0; 7] }, &grid, 30, 20, 1).is_err());
    // one point of a page: no bend to speak of, and no panic
    assert_eq!(FlattenGrid { cols: 1, rows: 1, xy: vec![3.0, 4.0] }.bend(), 0.0);

    // a page mapped without folding has no fold, seen from the front or mirrored; one point
    // pulled across its neighbours folds the four cells around it
    assert_eq!(grid.folds(), 0);
    let mirrored = flat_grid(&Quad::new([[35.0, 5.0], [5.0, 5.0], [5.0, 25.0], [35.0, 25.0]]).unwrap(), 4, 3);
    assert_eq!(mirrored.map(|g| g.folds()), Some(0));
    let mut folded = grid.clone();
    folded.xy[(4 + 1) * 2] += 25.0;
    assert!(folded.folds() >= 2, "{}", folded.folds());
    assert_eq!(FlattenGrid { cols: 4, rows: 3, xy: vec![0.0; 5] }.folds(), usize::MAX);
    assert_eq!(FlattenGrid { xy: grid.xy.iter().map(|_| f32::NAN).collect(), ..grid.clone() }.folds(), 6);
}

/// The network on drawn pages: a curled one comes back with straighter lines than the
/// perspective warp gives, and a flat one is recognised as flat.
#[test]
fn the_network_flattens_a_curled_page_and_leaves_a_flat_one() {
    let page = printed(600, 848);
    let quad = Quad::new(QUAD).unwrap();
    for (bow, sag) in [(30.0, 0.0), (24.0, 20.0)] {
        let photo = Bent::new(QUAD, bow, sag).photograph(&page, 1200, 1600);
        let grid = predict_grid(&photo, &quad).expect("assets/models/scan-flatten.onnx is missing or is not the pinned file");
        assert_eq!((grid.cols, grid.rows), (COLS, ROWS));
        let (flat, plain) = (unwarp(&photo, &grid, 600, 848, 2).unwrap(), rectify_with_threads(&photo, &quad, 600, 848, 2).unwrap());
        println!(
            "bow {bow}, sag {sag}: bend {:.2} %, frame waviness {:.1} px flattened, {:.1} px by perspective alone",
            100.0 * grid.bend(),
            waviness(&flat),
            waviness(&plain)
        );
        assert!(grid.bend() > BENT, "the network saw no bend: {}", grid.bend());
        assert!(waviness(&flat) <= 0.5 * waviness(&plain), "{} against {}", waviness(&flat), waviness(&plain));
    }
    let photo = Bent::new(QUAD, 0.0, 0.0).photograph(&page, 1200, 1600);
    let grid = predict_grid(&photo, &quad).unwrap();
    println!("flat page: bend {:.2} %", 100.0 * grid.bend());
    assert!(grid.bend() < 2.0 * BENT, "{}", grid.bend());
    // the same grid every time
    assert_eq!(predict_grid(&photo, &quad).unwrap(), grid);
}
