//! The drawn test photos of #288 (`synth`): the same seed gives the same bytes, and the answers
//! they come with are exact (the corners, the proportions, the focal length, the page itself).

use crate::aspect::{Focal, proportions};
use crate::metrics_compare::psnr;
use crate::synth::{Kind, Rng, Scene, SceneSpec, generate, label};
use crate::warp::rectify;
use crate::{Luma, Photo, Quad, luma};

const W: u32 = 600;
const H: u32 = 800;

fn scene(seed: u64, kind: Kind, plain: bool) -> Scene {
    generate(SceneSpec { plain, ..SceneSpec::new(seed, kind, W, H) })
}

fn grey(rgb: &[u8], width: u32, height: u32) -> Luma {
    Luma { width, height, data: rgb.as_chunks::<3>().0.iter().map(|p| luma(*p)).collect() }
}

/// The photo straightened to the page's own size with `quad`, against the clean page.
fn psnr_with(s: &Scene, quad: [[f64; 2]; 4]) -> f64 {
    let photo = Photo::decode(&s.jpeg).unwrap_or_else(|e| panic!("decode: {e:?}"));
    let quad = Quad::new(quad).unwrap_or_else(|e| panic!("quad: {e:?}"));
    let page = rectify(&photo, &quad, s.page_width, s.page_height).unwrap_or_else(|e| panic!("rectify: {e:?}"));
    let a = grey(&page.data, page.width, page.height);
    let b = grey(&s.page, s.page_width, s.page_height);
    psnr(&a, &b).unwrap_or(f64::INFINITY)
}

#[test]
fn the_same_seed_gives_the_same_bytes_and_another_seed_others() {
    for kind in Kind::SYNTHETIC {
        let (a, b) = (scene(7, kind, false), scene(7, kind, false));
        assert_eq!(a.jpeg, b.jpeg, "{kind:?}");
        assert_eq!(a.quad, b.quad, "{kind:?}");
        assert_eq!(a.labels, b.labels, "{kind:?}");
        assert_ne!(a.jpeg, scene(8, kind, false).jpeg, "{kind:?}");
    }
}

#[test]
fn the_random_numbers_repeat_and_stay_in_range() {
    let (mut a, mut b) = (Rng::new(1), Rng::new(1));
    for _ in 0..1000 {
        let v = a.range(-2.0, 3.0);
        assert_eq!(v.to_bits(), b.range(-2.0, 3.0).to_bits());
        assert!((-2.0..3.0).contains(&v));
    }
    // nearby seeds start far apart
    assert_ne!(Rng::new(1).next_u64(), Rng::new(2).next_u64());
}

#[test]
fn the_page_lies_inside_the_photo_at_the_size_the_issue_asks() {
    for seed in 0..12 {
        for kind in Kind::SYNTHETIC {
            let s = scene(seed, kind, false);
            assert_eq!((s.width, s.height), (W, H));
            assert!(s.quad.iter().all(|p| p[0] >= 1.0 && p[1] >= 1.0 && p[0] <= f64::from(W) - 1.0 && p[1] <= f64::from(H) - 1.0), "{seed} {kind:?}");
            let q = Quad::new(s.quad).unwrap_or_else(|e| panic!("{seed} {kind:?}: {e:?}"));
            // already in reading order: TL, TR, BR, BL
            assert_eq!(q.0, s.quad, "{seed} {kind:?}");
            let area = (0..4).map(|i| s.quad[i][0] * s.quad[(i + 1) % 4][1] - s.quad[(i + 1) % 4][0] * s.quad[i][1]).sum::<f64>().abs() / 2.0;
            let share = area / f64::from(W * H);
            // 30–85 % of the frame, made smaller only when the tilted page would not fit
            assert!((0.10..=0.86).contains(&share), "{seed} {kind:?}: {share}");
            let shares = s.shares();
            assert!(shares.iter().all(|v| (0.0..=1.0).contains(v)));
        }
    }
}

#[test]
fn a_plain_scene_straightened_with_its_corners_is_the_clean_page() {
    // A plain scene has the full scene's page and place, without light, blur and noise, so the
    // true corners must give back the drawn page; corners off by 1 % of the photo must not.
    for seed in 0..4 {
        for kind in [Kind::Desk, Kind::Light, Kind::Receipt, Kind::Shadow] {
            let s = scene(seed, kind, true);
            assert_eq!(s.quad, scene(seed, kind, false).quad, "plain and full scenes share the place");
            assert!(s.illumination.iter().all(|&l| l == 1.0));
            let exact = psnr_with(&s, s.quad);
            let mut off = s.quad;
            off[0][0] += 0.01 * f64::from(W);
            off[2][1] -= 0.01 * f64::from(H);
            let moved = psnr_with(&s, off);
            // Measured when this was written: 21.4–24.6 dB with the true corners (the page is
            // drawn at 1.4 times the photo's resolution, so fine print is lost on the way),
            // 7.1–10.3 dB with corners 1 % off.
            assert!(exact > 20.0, "{seed} {kind:?}: {exact:.2} dB with the true corners");
            assert!(exact > moved + 8.0, "{seed} {kind:?}: true {exact:.2} dB, moved {moved:.2} dB");
            // and the true corners are right to within a pixel: the whole outline one pixel
            // to either side matches worse
            for dx in [-1.0, 1.0] {
                let shifted = psnr_with(&s, s.quad.map(|[x, y]| [x + dx, y]));
                assert!(shifted < exact, "{seed} {kind:?}: {dx} px gives {shifted:.2} dB, true {exact:.2} dB");
            }
        }
    }
}

#[test]
fn the_true_focal_length_gives_the_true_proportions() {
    // The page is placed by a pinhole camera, so its corners and the focal length the scene
    // states must give back the paper's width-to-height ratio.
    for seed in 0..12 {
        for kind in [Kind::Desk, Kind::Light, Kind::Receipt, Kind::Shadow] {
            let s = scene(seed, kind, false);
            let q = Quad::new(s.quad).unwrap_or_else(|e| panic!("{e:?}"));
            let p = proportions(&q, W, H, Focal { px: Some(s.focal), zoom: None }, false);
            let err = (p.ratio - s.aspect).abs() / s.aspect;
            assert!(err < 0.01, "{seed} {kind:?}: {:.4} against {:.4}", p.ratio, s.aspect);
        }
    }
}

#[test]
fn the_labels_say_what_was_drawn() {
    let s = scene(3, Kind::Desk, true);
    let n = (s.page_width * s.page_height) as usize;
    assert_eq!(s.labels.len(), n);
    assert_eq!(s.page.len(), n * 3);
    let mean = |want: u8| {
        let (sum, count) = s
            .labels
            .iter()
            .zip(s.page.as_chunks::<3>().0)
            .filter(|(l, _)| **l == want)
            .fold((0u64, 0u64), |(a, c), (_, p)| (a + u64::from(luma(*p)), c + 1));
        assert!(count > 0, "label {want} drawn");
        sum as f64 / count as f64
    };
    assert!(mean(label::PAPER) > 220.0);
    assert!(mean(label::INK) < 60.0);
    // the stamp is blue
    let stamp = s.labels.iter().zip(s.page.as_chunks::<3>().0).find(|(l, _)| **l == label::STAMP).map(|(_, p)| *p);
    assert!(stamp.is_some_and(|[r, _, b]| b > r + 100));
}

#[test]
fn the_kinds_change_the_scene_the_way_their_names_say() {
    let min = |s: &Scene| s.illumination.iter().copied().fold(f32::MAX, f32::min);
    let shadowed = (0..6).filter(|&seed| min(&scene(seed, Kind::Shadow, false)) < 0.75).count();
    assert!(shadowed >= 5, "a shadow scene has a shadow: {shadowed} of 6");
    assert!((0..6).all(|seed| scene(seed, Kind::Book, false).curl > 0.0));
    assert!((0..6).all(|seed| scene(seed, Kind::Desk, false).curl == 0.0));
    assert!((0..6).all(|seed| scene(seed, Kind::Receipt, false).aspect < 0.45));
    assert!((0..6).all(|seed| scene(seed, Kind::Lowlight, false).illumination.iter().all(|&l| l <= 0.71)));
    assert_eq!(Kind::from_name("receipt"), Kind::Receipt);
    assert_eq!(Kind::from_name("nonsense"), Kind::Other);
}
