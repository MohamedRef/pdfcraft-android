//! A page's first picture: the page straightened by its corners and given its look, made from a
//! small copy of the photo in a fraction of the time the whole page takes, to show while the page
//! itself is being made.
//!
//! It has the shape the page will have (the same proportions, worked out from the photo's own size
//! and lens as [`crate::page_base`] does) and the look asked for, and leaves out everything that is
//! slow and seldom changes what is seen at a glance: decoding the photo at full size (when the
//! caller hands a small copy), the flattening network with its check and the text lines, the
//! orientation classifier, the document type, and the finger search. It is only ever shown: the
//! page that is saved is made by [`crate::process_page`] as before.

use std::time::Instant;

use crate::look::Pixels;
use crate::page::{PageBase, PageRequest, page_look, page_scale, page_shape};
use crate::page_kind::PresetChoice;
use crate::upright::turned;
use crate::upright_pages::total_turn;
use crate::warp::rectify_with_threads;
use crate::{Paper, Photo, Quad, ScanError};

/// Reading the photo's size and lens from its header.
pub const STAGE_FIRST_HEADER: &str = "scan_first_header";
/// Decoding the photo; only there when no small copy was given, or the one given was not the photo's shape.
pub const STAGE_FIRST_DECODE: &str = "scan_first_decode";
/// Straightening the page by its corners, and the person's own turn.
pub const STAGE_FIRST_RECTIFY: &str = "scan_first_rectify";
pub const STAGE_FIRST_LOOK: &str = "scan_first_look";

/// The longer side of a first picture when the request names none: half the scan screen's preview.
pub const FIRST_SIDE: u32 = 640;

/// How far a small copy's width over height may be from the photo's (a copy made by keeping every
/// second, fourth or eighth pixel rounds each side by itself).
const SHAPE_TOLERANCE: f64 = 0.01;

/// A page's first picture.
#[derive(Clone, Debug, PartialEq)]
pub struct FirstPage {
    pub pixels: Pixels,
    pub width: u32,
    pub height: u32,
    /// The quarter turns clockwise it was given: the person's own only (the classifier is not asked).
    pub turns: u8,
    /// The paper the page was matched to, as the page itself will be.
    pub paper: Paper,
    /// It was made from the small copy, without decoding the photo.
    pub from_small: bool,
    /// (stage, milliseconds), in order.
    pub timings: Vec<(&'static str, u32)>,
}

/// Whether `small` is a copy of a `width` × `height` photo: the same way up and the same shape.
fn copy_of(small: &Photo, width: u32, height: u32) -> bool {
    let (sw, sh) = (small.width as usize, small.height as usize);
    if sw < 16 || sh < 16 || sw.checked_mul(sh).and_then(|n| n.checked_mul(3)) != Some(small.rgb.len()) {
        return false;
    }
    let (copy, photo) = (sw as f64 / sh as f64, f64::from(width) / f64::from(height.max(1)));
    (copy / photo - 1.0).abs() <= SHAPE_TOLERANCE
}

/// The first picture of the page `request` describes, its longer side `request.max_side` pixels at
/// most ([`FIRST_SIDE`] when it names none). `small` is an upright copy of the photo at a smaller
/// size (the thumbnail the app already holds); with it the photo's file is only read for its
/// header. Without it, or when it is not the photo's shape, the photo is decoded.
///
/// Of the request, the corners, the proportions' choices, the person's turn, the look with its
/// strength, sharpening, smoothing and paper colour, and the marks made by hand are followed.
/// Flattening, the classifier's turn, the document type and the finger search are not.
pub fn first_page(request: &PageRequest<'_>, small: Option<&Photo>) -> Result<FirstPage, ScanError> {
    let mut timings = Vec::with_capacity(4);
    let mut clock = Instant::now();
    let mut lap = |stage: &'static str, timings: &mut Vec<(&'static str, u32)>| {
        timings.push((stage, u32::try_from(clock.elapsed().as_millis()).unwrap_or(u32::MAX)));
        clock = Instant::now();
    };
    let (width, height, lens) = Photo::upright_size_with_camera(request.photo)?;
    // the page's shape from the photo's own size, as the page itself is made
    let quad = Quad::from_shares(&request.corners, width, height)?;
    let shape = page_shape(request, &quad, width, height, lens);
    let sized = PageRequest { max_side: Some(request.max_side.unwrap_or(FIRST_SIDE)), ..request.clone() };
    let (w, h, dpi) = shape.output(page_scale(&sized, &shape)?)?;
    lap(STAGE_FIRST_HEADER, &mut timings);

    let small = small.filter(|s| copy_of(s, width, height));
    let decoded;
    let source = match small {
        Some(small) => small,
        None => {
            decoded = Photo::decode(request.photo)?;
            lap(STAGE_FIRST_DECODE, &mut timings);
            &decoded
        }
    };
    let quad = Quad::from_shares(&request.corners, source.width, source.height)?;
    let page = rectify_with_threads(source, &quad, w, h, request.threads)?;
    // the person's own turn only: the classifier needs print it can read (on the owner's twelve
    // photos it gave a page this size another turn than the page itself got on three of ten)
    let turns = total_turn(0, i32::from(request.turns));
    let page = if turns == 0 { page } else { turned(&page, turns) };
    lap(STAGE_FIRST_RECTIFY, &mut timings);

    let base = PageBase {
        page,
        dpi,
        paper: shape.paper,
        aspect: shape.source,
        focal_mismatch: shape.focal_mismatch,
        flattened: false,
        bend: 0.0,
        quarter_turns: 0,
        orientation: None,
        turns,
        flatten_check: None,
        trim: None,
        line_flatten: None,
        flatten_decision: None,
        flatten_reused: false,
        flatten_skipped_flat: false,
        timings: Vec::new(),
        measured: std::sync::OnceLock::new(),
        look_kept: crate::enhance::Prepared::new(),
    };
    let asked = PageRequest { preset: PresetChoice::Off, remove_fingers: false, reuse_look: false, ..sized };
    let applied = page_look(&base, &asked)?;
    lap(STAGE_FIRST_LOOK, &mut timings);
    Ok(FirstPage {
        pixels: applied.pixels,
        width: base.page.width,
        height: base.page.height,
        turns,
        paper: shape.paper,
        from_small: small.is_some(),
        timings,
    })
}

/// `photo` with every `by`-th pixel of every `by`-th row kept, each the average of its `by` × `by`
/// block: the small copy a phone makes when it reads a photo at a fraction of its size. For tests
/// and measuring; the app hands its own.
pub fn shrunk(photo: &Photo, by: u32) -> Photo {
    let by = by.max(1) as usize;
    let (pw, ph) = (photo.width as usize, photo.height as usize);
    let (w, h) = ((pw / by).max(1), (ph / by).max(1));
    let mut rgb = Vec::with_capacity(w * h * 3);
    for y in 0..h {
        for x in 0..w {
            let mut sum = [0u32; 3];
            let mut n = 0u32;
            for sy in (y * by)..((y + 1) * by).min(ph) {
                for sx in (x * by)..((x + 1) * by).min(pw) {
                    if let Some(p) = photo.rgb.get((sy * pw + sx) * 3..(sy * pw + sx) * 3 + 3) {
                        sum[0] += u32::from(p[0]);
                        sum[1] += u32::from(p[1]);
                        sum[2] += u32::from(p[2]);
                        n += 1;
                    }
                }
            }
            let n = n.max(1);
            rgb.extend(sum.map(|v| (v / n) as u8));
        }
    }
    Photo { width: w as u32, height: h as u32, rgb }
}
