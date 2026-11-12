//! Tests for the capture side's calls (#296, #297): sharpness from a camera file and the merge of
//! a burst. Every photo is drawn in code and written as a PNG or JPEG file's bytes, as the camera
//! would hand them over; the measured numbers are printed (`cargo test -- --nocapture`).

use crate::capture::{MAX_MERGE_PIXELS, MergeFrame, MergeRequest, folded, merge_frames, photo_sharpness};
use crate::merge::moved;
use crate::sharpness::BLURRED_BELOW;
use crate::warp::rectify_with_threads;
use crate::{Format, Photo, Quad, Rgb, ScanError};

/// Photo size.
const PW: u32 = 700;
const PH: u32 = 900;
/// The page in the photo, seen square on: left, top, right, bottom in photo pixels.
const PAGE: (u32, u32, u32, u32) = (90, 100, 610, 800);
const PAPER: [u8; 3] = [222, 216, 204];
const INK: [u8; 3] = [42, 40, 48];
const TABLE: [u8; 3] = [70, 58, 50];

/// xorshift64*, so the tests need no crate.
struct Rng(u64);

impl Rng {
    fn next_u64(&mut self) -> u64 {
        self.0 ^= self.0 >> 12;
        self.0 ^= self.0 << 25;
        self.0 ^= self.0 >> 27;
        self.0.wrapping_mul(0x2545_F491_4F6C_DD1D)
    }

    fn unit(&mut self) -> f64 {
        ((self.next_u64() >> 11) as f64 + 0.5) / (1u64 << 53) as f64
    }

    fn gauss(&mut self) -> f64 {
        let (u1, u2) = (self.unit(), self.unit());
        (-2.0 * u1.ln()).sqrt() * (std::f64::consts::TAU * u2).cos()
    }
}

/// A sheet with rows of words on a table, the page square on: the clean scene every frame of a
/// burst is a noisy copy of.
fn scene() -> Rgb {
    let (l, t, r, b) = PAGE;
    let mut rng = Rng(0x9E37_79B9_7F4A_7C15);
    let mut ink = vec![false; (PW * PH) as usize];
    for line in 0..24 {
        let top = t + 50 + line * 25;
        let mut x = l + 40;
        while x + 30 < r - 40 {
            let len = 12 + (rng.next_u64() % 40) as u32;
            let gap = 6 + (rng.next_u64() % 10) as u32;
            for y in top..top + 9 {
                for xx in x..(x + len).min(r - 40) {
                    ink[(y * PW + xx) as usize] = true;
                }
            }
            x += len + gap;
        }
    }
    let mut data = Vec::with_capacity((PW * PH * 3) as usize);
    for y in 0..PH {
        for x in 0..PW {
            let on_page = (l..r).contains(&x) && (t..b).contains(&y);
            data.extend_from_slice(&match (on_page, ink[(y * PW + x) as usize]) {
                (true, true) => INK,
                (true, false) => PAPER,
                _ => TABLE,
            });
        }
    }
    Rgb { width: PW, height: PH, data }
}

/// `clean` with independent Gaussian noise of `sigma` on every byte.
fn noisy(clean: &Rgb, sigma: f64, seed: u64) -> Rgb {
    let mut rng = Rng(seed);
    let data = clean.data.iter().map(|&v| (f64::from(v) + sigma * rng.gauss()).round().clamp(0.0, 255.0) as u8).collect();
    Rgb { width: clean.width, height: clean.height, data }
}

/// `picture` blurred by a box of side 2·`radius` + 1, `passes` times (three passes are close to a
/// Gaussian of sigma about `radius`).
fn blurred(picture: &Rgb, radius: i64, passes: usize) -> Rgb {
    let (w, h) = (i64::from(picture.width), i64::from(picture.height));
    let mut data = picture.data.clone();
    for _ in 0..passes {
        for horizontal in [true, false] {
            let src = data.clone();
            for y in 0..h {
                for x in 0..w {
                    for c in 0..3 {
                        let mut sum = 0u32;
                        for k in -radius..=radius {
                            let (sx, sy) = if horizontal { ((x + k).clamp(0, w - 1), y) } else { (x, (y + k).clamp(0, h - 1)) };
                            sum += u32::from(src[((sy * w + sx) * 3 + c) as usize]);
                        }
                        data[((y * w + x) * 3 + c) as usize] = (sum / (2 * radius as u32 + 1)) as u8;
                    }
                }
            }
        }
    }
    Rgb { width: picture.width, height: picture.height, data }
}

fn png(picture: &Rgb) -> Vec<u8> {
    let mut out = Vec::new();
    let img = image::RgbImage::from_raw(picture.width, picture.height, picture.data.clone()).unwrap();
    img.write_to(&mut std::io::Cursor::new(&mut out), image::ImageFormat::Png).unwrap();
    out
}

fn jpeg(picture: &Rgb) -> Vec<u8> {
    let mut out = Vec::new();
    let img = image::RgbImage::from_raw(picture.width, picture.height, picture.data.clone()).unwrap();
    image::codecs::jpeg::JpegEncoder::new_with_quality(&mut out, 92).encode_image(&img).unwrap();
    out
}

/// The page's corners as shares, moved by (`dx`, `dy`) photo pixels: a corner estimate a little
/// off, as each frame of a real burst has.
fn corners(dx: f32, dy: f32) -> [f32; 8] {
    let (l, t, r, b) = PAGE;
    let (l, t, r, b) = (l as f32 + dx, t as f32 + dy, r as f32 + dx, b as f32 + dy);
    let (w, h) = (PW as f32, PH as f32);
    [l / w, t / h, r / w, t / h, r / w, b / h, l / w, b / h]
}

/// Root mean square difference over every byte inside a margin of `m` pixels.
fn rmse_inside(a: &Rgb, b: &Rgb, m: u32) -> f64 {
    assert_eq!((a.width, a.height), (b.width, b.height));
    let (mut sum, mut n) = (0.0f64, 0usize);
    for y in m..a.height - m {
        for x in m..a.width - m {
            for c in 0..3 {
                let i = ((y * a.width + x) * 3 + c) as usize;
                sum += (f64::from(a.data[i]) - f64::from(b.data[i])).powi(2);
                n += 1;
            }
        }
    }
    (sum / n as f64).sqrt()
}

fn decoded(bytes: &[u8]) -> Rgb {
    let p = Photo::decode(bytes).unwrap();
    Rgb { width: p.width, height: p.height, data: p.rgb }
}

fn as_photo(p: &Rgb) -> Photo {
    Photo { width: p.width, height: p.height, rgb: p.data.clone() }
}

fn request<'a>(frames: &'a [MergeFrame<'a>], format: Format) -> MergeRequest<'a> {
    MergeRequest { frames, format, threads: 2, focal_35mm: None, snap_paper: false }
}

/// The move folded into the transform gives the page `merge::moved` gives, resampled once from
/// the photo instead of twice: the sign and size of the move are the block's own.
#[test]
fn a_folded_move_is_the_blocks_move() {
    // softened, so that the two interpolations (bicubic once, bilinear on top of it) differ little
    let photo = as_photo(&blurred(&scene(), 2, 3));
    // a page seen at an angle
    let quad = Quad::new([[110.0, 120.0], [600.0, 140.0], [620.0, 790.0], [80.0, 770.0]]).unwrap();
    let (w, h) = (500, 660);
    let plain = rectify_with_threads(&photo, &quad, w, h, 1).unwrap();
    for (dx, dy) in [(1.4f32, -0.6f32), (-2.2, 1.7), (0.3, 0.0)] {
        let by_block = moved(&plain, dx, dy);
        let once = rectify_with_threads(&photo, &folded(&quad, w, h, dx, dy).unwrap(), w, h, 1).unwrap();
        let apart = rmse_inside(&by_block, &once, 8);
        // the other way round is far off: the sign is right
        let wrong = rmse_inside(&moved(&plain, -dx, -dy), &once, 8);
        println!("move ({dx}, {dy}): folded against moved {apart:.2}, against the opposite move {wrong:.2}");
        assert!(apart < 2.0 && (dx.hypot(dy) < 0.5 || wrong > 2.0 * apart), "{apart} {wrong}");
    }
}

/// #297: three noisy frames, each with its corners a little off, merge into a page closer to the
/// clean page than the reference frame alone; the moves are found, nothing is left out.
#[test]
fn three_noisy_frames_merge_into_a_cleaner_page() {
    let clean = scene();
    let offsets = [(0.0f32, 0.0f32), (1.4, -0.7), (-0.9, 1.2)];
    let files: Vec<Vec<u8>> = (0..3).map(|i| png(&noisy(&clean, 10.0, 1000 + i))).collect();
    let frames: Vec<MergeFrame> = files.iter().zip(offsets).map(|(f, (dx, dy))| MergeFrame { photo: f, corners: corners(dx, dy) }).collect();
    let out = merge_frames(&request(&frames, Format::Png)).unwrap();
    assert_eq!((out.used, out.rejected_blur, out.rejected_align), (3, 0, 0), "{:?}", out.moves);
    let (r, (rx, ry)) = (out.reference as usize, offsets[out.reference as usize]);
    // the clean page as straightened from the reference's corners, and the reference alone
    let quad = Quad::from_shares(&corners(rx, ry), PW, PH).unwrap();
    let truth = rectify_with_threads(&as_photo(&clean), &quad, out.width, out.height, 1).unwrap();
    let single = rectify_with_threads(&as_photo(&decoded(&files[r])), &quad, out.width, out.height, 1).unwrap();
    let merged = decoded(&out.bytes);
    assert_eq!((merged.width, merged.height), (520, 700));
    let (e1, em) = (rmse_inside(&single, &truth, 6), rmse_inside(&merged, &truth, 6));
    let db = 20.0 * (e1 / em).log10();
    println!(
        "reference {r}, scores {:?}, moves {:?}; error {e1:.2} alone, {em:.2} merged ({db:.1} dB); estimate {:.1} dB; timings {:?}",
        out.scores, out.moves, out.psnr_gain_estimate, out.timings
    );
    assert!(em < 0.75 * e1, "merged {em} against one frame {e1}");
    // each move is the frame's corner error against the reference's, in output pixels (1 to 1 here)
    for (i, found) in out.moves.iter().enumerate() {
        match (i == r, found) {
            (true, None) => {}
            (false, Some([dx, dy])) => {
                let want = (offsets[i].0 - rx, offsets[i].1 - ry);
                assert!((dx - want.0).abs() < 0.3 && (dy - want.1).abs() < 0.3, "frame {i}: ({dx}, {dy}) against {want:?}");
            }
            other => panic!("frame {i}: {other:?}"),
        }
    }
    assert!(out.psnr_gain_estimate > 4.0, "{}", out.psnr_gain_estimate);
    let stages: Vec<_> = out.timings.iter().map(|t| t.0).collect();
    assert_eq!(stages, ["scan_decode", "scan_sharpness", "scan_rectify", "scan_merge_align", "scan_merge", "scan_encode"]);
    // the same bytes again, and on one thread
    let again = merge_frames(&MergeRequest { threads: 1, ..request(&frames, Format::Png) }).unwrap();
    assert_eq!(again.bytes, out.bytes);
}

/// #297: a blurred frame is left out for its sharpness, a frame 6 pixels off for its move.
#[test]
fn blurred_and_far_moved_frames_are_left_out() {
    let clean = scene();
    let sharp: Vec<Vec<u8>> = (0..2).map(|i| png(&noisy(&clean, 4.0, 2000 + i))).collect();
    let soft = png(&noisy(&blurred(&clean, 3, 3), 4.0, 2002));
    let frames = [
        MergeFrame { photo: &sharp[0], corners: corners(0.0, 0.0) },
        MergeFrame { photo: &soft, corners: corners(0.0, 0.0) },
        MergeFrame { photo: &sharp[1], corners: corners(6.0, 0.0) },
    ];
    let out = merge_frames(&request(&frames, Format::Jpeg)).unwrap();
    println!("scores {:?}, moves {:?}", out.scores, out.moves);
    assert!(out.scores[1] < 0.7 * out.scores[0].max(out.scores[2]), "{:?}", out.scores);
    assert_eq!((out.used, out.rejected_blur, out.rejected_align), (1, 1, 1));
    assert_eq!(out.psnr_gain_estimate, 0.0);
    assert_eq!(&out.bytes[..2], [0xff, 0xd8]);
}

/// Wrong requests are refused with an error, never a panic.
#[test]
fn bad_bursts_are_refused() {
    let file = png(&scene());
    let frame = MergeFrame { photo: &file, corners: corners(0.0, 0.0) };
    assert_eq!(merge_frames(&request(&[frame], Format::Png)), Err(ScanError::FrameCount));
    assert_eq!(merge_frames(&request(&[frame; 5], Format::Png)), Err(ScanError::FrameCount));
    let mut bad = frame;
    bad.corners = [0.5; 8];
    assert_eq!(merge_frames(&request(&[frame, bad], Format::Png)), Err(ScanError::BadCorners));
    let broken = MergeFrame { photo: b"not a photo", corners: corners(0.0, 0.0) };
    assert_eq!(merge_frames(&request(&[frame, broken], Format::Png)), Err(ScanError::Unreadable));
    assert_eq!(photo_sharpness(b"not a photo", corners(0.0, 0.0)), Err(ScanError::Unreadable));
    bad.corners[0] = f32::NAN;
    assert_eq!(photo_sharpness(&file, bad.corners), Err(ScanError::BadCorners));
    const { assert!(MAX_MERGE_PIXELS <= 12e6) };
}

/// #296: a sharp drawn page in a JPEG scores above the threshold, the same page blurred below it.
#[test]
fn a_blurred_photo_scores_lower_than_a_sharp_one() {
    let clean = scene();
    let sharp = photo_sharpness(&jpeg(&clean), corners(0.0, 0.0)).unwrap();
    let soft = photo_sharpness(&jpeg(&blurred(&clean, 3, 3)), corners(0.0, 0.0)).unwrap();
    println!("sharp {:?}, blurred {:?}", sharp, soft);
    assert!(sharp.sharpness.score >= BLURRED_BELOW && !sharp.sharpness.blurred);
    assert!(soft.sharpness.score < BLURRED_BELOW && soft.sharpness.blurred);
    assert_eq!(sharp.timings.iter().map(|t| t.0).collect::<Vec<_>>(), ["scan_decode", "scan_sharpness"]);
}
