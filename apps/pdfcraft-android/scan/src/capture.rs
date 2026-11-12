//! The capture side's two calls, from a camera file's bytes (#296, #297): how sharp the page in a
//! photo is, and the low-light merge of a burst of photos of one page into one straightened page.
//!
//! Both only wire in blocks that exist and are tested on their own: [`crate::sharpness`] for the
//! score and [`crate::merge`] for choosing, lining up and averaging the frames. What this module
//! adds is the plumbing the blocks leave to their caller: decoding (with the crate's limits),
//! straightening every frame to the reference's size with the move found by the alignment folded
//! into its perspective transform, so a frame is resampled from the photo once, and writing the
//! result.
//!
//! **What is not known**: both were measured on drawn pages only. `sharpness` does not tell
//! motion blur apart (`docs/ANDROID_SCAN_BLOCKS.md`), and its threshold was set on drawn pages, so
//! [`PhotoSharpness::sharpness`]'s `blurred` is for logs and kept scans, not for a retake prompt.

use std::time::Instant;

use pdfcraft_geom::perspective::Homography;

use crate::aspect::{Focal, proportions};
use crate::encode::{Format, JPEG_QUALITY, Raster, encode_jpeg, encode_png_rgb};
use crate::merge::{Merger, align, choose, noise_between};
use crate::sharpness::{Sharpness, sharpness};
use crate::warp::rectify_with_threads;
use crate::{Luma, Photo, Quad, Rgb, ScanError, luma};

/// The stage names timings are recorded under (fixed: diagnostics never hold document data).
pub const STAGE_DECODE: &str = "scan_decode";
/// Measuring sharpness (all frames together, for a merge).
pub const STAGE_SHARPNESS: &str = "scan_sharpness";
/// Straightening the frames of a merge, the reference's included.
pub const STAGE_RECTIFY: &str = "scan_rectify";
/// Finding each frame's move against the reference, and its noise.
pub const STAGE_ALIGN: &str = "scan_merge_align";
/// Folding the frames into the average and finishing it.
pub const STAGE_MERGE: &str = "scan_merge";
pub const STAGE_ENCODE: &str = "scan_encode";

/// The fewest photos a merge takes.
pub const MIN_MERGE_FRAMES: usize = 2;
/// The most photos a merge takes (the burst of #297 is four).
pub const MAX_MERGE_FRAMES: usize = 4;
/// The most pixels a merged page has (#297, step 6): the reference, a sum and a count of this
/// many samples are what the merge holds.
pub const MAX_MERGE_PIXELS: f64 = 12_000_000.0;

/// A frame whose mean difference from the reference after its move exceeds this many times its
/// noise is not this page as it was (#297, step 3)...
const RESIDUAL_SIGMAS: f32 = 3.0;
/// ... plus this many grey levels, so that frames with no measurable noise (drawn pages; a
/// camera's own denoising) are not refused for the half level that resampling alone leaves.
/// The same floor `merge::Merger` adds to its own limit.
const RESIDUAL_FLOOR: f32 = 2.0;

/// The sharpness of the page in one photo.
#[derive(Clone, Debug, PartialEq)]
pub struct PhotoSharpness {
    /// `sharpness::sharpness`'s verdict. Its `blurred` is set on drawn pages only and misses
    /// motion blur: log it and keep it, do not ask for a retake from it.
    pub sharpness: Sharpness,
    /// (stage, milliseconds): decoding, then measuring.
    pub timings: Vec<(&'static str, u32)>,
}

/// Measure how sharp the page inside `corners` (8 shares, 0–1, of the upright photo, as every
/// scan call takes them) is in the photo `photo` (JPEG or PNG bytes, at most 64 megapixels).
/// The block measures on a half-size copy itself, so the photo is given at full size.
pub fn photo_sharpness(photo: &[u8], corners: [f32; 8]) -> Result<PhotoSharpness, ScanError> {
    let mut clock = Clock::new();
    let decoded = Photo::decode(photo)?;
    clock.lap(STAGE_DECODE);
    let quad = Quad::from_shares(&corners, decoded.width, decoded.height)?;
    let grey = decoded.luma();
    drop(decoded);
    let found = sharpness(&grey, &quad);
    clock.lap(STAGE_SHARPNESS);
    Ok(PhotoSharpness { sharpness: found, timings: clock.timings })
}

/// One photo of a burst and the page's corners in it.
#[derive(Clone, Copy, Debug)]
pub struct MergeFrame<'a> {
    /// The camera file's bytes (JPEG or PNG).
    pub photo: &'a [u8],
    /// 8 shares (0–1) of the upright photo: x1, y1 … x4, y4.
    pub corners: [f32; 8],
}

/// A burst to merge into one straightened page.
#[derive(Clone, Copy, Debug)]
pub struct MergeRequest<'a> {
    /// 2 to 4 photos of the same page.
    pub frames: &'a [MergeFrame<'a>],
    /// How the merged page is written.
    pub format: Format,
    /// Threads to straighten with (1–4); the bytes are the same for every value.
    pub threads: usize,
    /// The camera's focal length as a 35 mm equivalent (`None`: the reference photo's EXIF block,
    /// else the corners), as [`crate::PageRequest::focal_35mm`].
    pub focal_35mm: Option<f32>,
    /// Match the page to standard paper sizes, as [`crate::PageRequest::snap_paper`].
    pub snap_paper: bool,
}

/// The merged page and what the merge did with the frames.
#[derive(Clone, Debug, PartialEq)]
pub struct MergeOutcome {
    /// The page, encoded as asked.
    pub bytes: Vec<u8>,
    pub format: Format,
    pub width: u32,
    pub height: u32,
    /// The resolution written into the file.
    pub dpi: u16,
    /// The index of the frame the others were lined up on: the sharpest.
    pub reference: u8,
    /// Every frame's sharpness score, in the order given.
    pub scores: Vec<f32>,
    /// Frames folded into the average, the reference included.
    pub used: u8,
    /// Frames left out for scoring under 70 % of the reference.
    pub rejected_blur: u8,
    /// Frames left out because they could not be lined up on the reference (moved more than
    /// 2.5 pixels, or too different after the move).
    pub rejected_align: u8,
    /// 10·log10 of the mean number of values averaged per sample: the theoretical noise gain in
    /// dB, not a measurement.
    pub psnr_gain_estimate: f32,
    /// For each frame in the order given, the move (dx, dy) in pixels it was given to lie on the
    /// reference; `None` for the reference and for frames not lined up.
    pub moves: Vec<Option<[f32; 2]>>,
    /// (stage, milliseconds), each stage summed over the frames.
    pub timings: Vec<(&'static str, u32)>,
}

/// Merge a burst of photos of one page (#297): score every frame's sharpness, take the sharpest
/// as the reference and drop frames under 70 % of its score (`merge::choose`), straighten the
/// reference to its own proportions (at most 12 megapixels), straighten each other frame to the
/// same size, find its move (`merge::align`), straighten it again from the photo with the move
/// folded into the transform, estimate its noise (`merge::noise_between`), and fold it into the
/// robust average (`merge::Merger`). The result is the straightened page with no look.
///
/// Memory: one decoded photo at a time, the reference twice (the merger keeps its own copy), its
/// luminance, the merger's sum and count, and one straightened frame. Every photo is decoded
/// twice (once to score it, once to straighten it): slower, but four photos are never held.
pub fn merge_frames(request: &MergeRequest<'_>) -> Result<MergeOutcome, ScanError> {
    let count = request.frames.len();
    if !(MIN_MERGE_FRAMES..=MAX_MERGE_FRAMES).contains(&count) {
        return Err(ScanError::FrameCount);
    }
    let mut clock = Clock::new();
    let scores = request.frames.iter().map(|frame| score(frame, &mut clock)).collect::<Result<Vec<f32>, ScanError>>()?;
    let (reference, keep) = choose(&scores).ok_or(ScanError::Unreadable)?;
    let base = reference_page(request, reference, &mut clock)?;
    let mut merging = Merging::new(base);
    let mut moves = vec![None; count];
    for &i in keep.iter().filter(|&&i| i != reference) {
        let frame = request.frames.get(i).ok_or(ScanError::Unreadable)?;
        if let Some(found) = merging.add(frame, request.threads, &mut clock)?
            && let Some(slot) = moves.get_mut(i)
        {
            *slot = Some(found);
        }
    }
    let Merging { base, merger, rejected_align, .. } = merging;
    let merged = merger.finish();
    clock.lap(STAGE_MERGE);
    let raster = Raster { pixels: &merged.rgb.data, grey: false, width: merged.rgb.width, height: merged.rgb.height, dpi: base.dpi };
    let bytes = match request.format {
        Format::Jpeg => encode_jpeg(&raster, JPEG_QUALITY)?,
        Format::Png => encode_png_rgb(&raster)?,
    };
    clock.lap(STAGE_ENCODE);
    Ok(MergeOutcome {
        bytes,
        format: request.format,
        width: merged.rgb.width,
        height: merged.rgb.height,
        dpi: base.dpi,
        reference: small(reference),
        scores,
        used: merged.used,
        rejected_blur: small(count - keep.len()),
        rejected_align,
        psnr_gain_estimate: merged.psnr_gain_estimate,
        moves,
        timings: clock.summed(),
    })
}

/// One frame's sharpness score (0 for a page that cannot be measured).
fn score(frame: &MergeFrame<'_>, clock: &mut Clock) -> Result<f32, ScanError> {
    let photo = Photo::decode(frame.photo)?;
    clock.lap(STAGE_DECODE);
    let quad = Quad::from_shares(&frame.corners, photo.width, photo.height)?;
    let grey = photo.luma();
    drop(photo);
    let found = sharpness(&grey, &quad).score;
    clock.lap(STAGE_SHARPNESS);
    Ok(found)
}

/// The reference frame straightened, and what every other frame is straightened to.
struct Base {
    page: Rgb,
    grey: Luma,
    width: u32,
    height: u32,
    dpi: u16,
}

/// The reference straightened to its proportions, as `page_base` does for a page (#293), shrunk
/// to at most [`MAX_MERGE_PIXELS`] and never enlarged.
fn reference_page(request: &MergeRequest<'_>, reference: usize, clock: &mut Clock) -> Result<Base, ScanError> {
    let frame = request.frames.get(reference).ok_or(ScanError::Unreadable)?;
    let (photo, lens) = Photo::decode_with_camera(frame.photo)?;
    clock.lap(STAGE_DECODE);
    let quad = Quad::from_shares(&frame.corners, photo.width, photo.height)?;
    let camera = match request.focal_35mm {
        Some(f) => Focal::from_35mm(Some(f), None, photo.width, photo.height),
        None => Focal::from_35mm(lens.focal_35mm, lens.digital_zoom, photo.width, photo.height),
    };
    let shape = proportions(&quad, photo.width, photo.height, camera, request.snap_paper);
    let mut scale = (MAX_MERGE_PIXELS / (shape.width * shape.height).max(1.0)).sqrt().min(1.0);
    let (mut width, mut height, mut dpi) = shape.output(scale)?;
    // a standard paper's exact size at a whole resolution can land a little over the cap
    while f64::from(width) * f64::from(height) > MAX_MERGE_PIXELS {
        scale *= 0.99;
        (width, height, dpi) = shape.output(scale)?;
    }
    let page = rectify_with_threads(&photo, &quad, width, height, request.threads)?;
    drop(photo);
    clock.lap(STAGE_RECTIFY);
    let grey = luma_of(&page);
    Ok(Base { page, grey, width, height, dpi })
}

/// The merge under way.
struct Merging {
    base: Base,
    merger: Merger,
    rejected_align: u8,
}

impl Merging {
    fn new(base: Base) -> Merging {
        let merger = Merger::new(base.page.clone());
        Merging { base, merger, rejected_align: 0 }
    }

    /// Straighten `frame` onto the reference and fold it in. `Some(move)` when it was folded
    /// in; `None` when it was left out (counted in `rejected_align`).
    fn add(&mut self, frame: &MergeFrame<'_>, threads: usize, clock: &mut Clock) -> Result<Option<[f32; 2]>, ScanError> {
        let photo = Photo::decode(frame.photo)?;
        clock.lap(STAGE_DECODE);
        let quad = Quad::from_shares(&frame.corners, photo.width, photo.height)?;
        let (w, h) = (self.base.width, self.base.height);
        let plain = rectify_with_threads(&photo, &quad, w, h, threads)?;
        clock.lap(STAGE_RECTIFY);
        let shift = align(&self.base.grey, &luma_of(&plain)).ok_or(ScanError::Unreadable)?;
        clock.lap(STAGE_ALIGN);
        if !shift.usable {
            self.rejected_align = self.rejected_align.saturating_add(1);
            return Ok(None);
        }
        let page = if shift.dx == 0.0 && shift.dy == 0.0 {
            plain
        } else {
            drop(plain);
            let moved = folded(&quad, w, h, shift.dx, shift.dy)?;
            rectify_with_threads(&photo, &moved, w, h, threads)?
        };
        drop(photo);
        clock.lap(STAGE_RECTIFY);
        let sigma = noise_between(&self.base.page, &page).ok_or(ScanError::Unreadable)?;
        // the residual is of luminance, whose noise is at most the channels' mean
        let limit = RESIDUAL_SIGMAS * (sigma.iter().sum::<f32>() / 3.0) + RESIDUAL_FLOOR;
        clock.lap(STAGE_ALIGN);
        if shift.residual > limit {
            self.rejected_align = self.rejected_align.saturating_add(1);
            return Ok(None);
        }
        let added = self.merger.add(&page, sigma);
        clock.lap(STAGE_MERGE);
        Ok(added.then_some([shift.dx, shift.dy]))
    }
}

/// The corners that straighten the page at `quad` already moved by (`dx`, `dy`) output pixels:
/// the page straightened from them is, at each output point (x, y), the page straightened from
/// `quad` at (x − dx, y − dy), as `merge::moved` would make it, but resampled once from the
/// photo. A translation followed by the perspective transform is a perspective transform, and
/// four points fix it: the output rectangle's corners moved back by the shift, sent through the
/// transform.
pub fn folded(quad: &Quad, width: u32, height: u32, dx: f32, dy: f32) -> Result<Quad, ScanError> {
    let (w, h) = (f64::from(width), f64::from(height));
    let rect = [[0.0, 0.0], [w, 0.0], [w, h], [0.0, h]];
    let to_photo = Homography::from_points(rect, quad.0).ok_or(ScanError::BadCorners)?;
    let (dx, dy) = (f64::from(dx), f64::from(dy));
    let mut corners = [[0.0; 2]; 4];
    for (out, [x, y]) in corners.iter_mut().zip(rect) {
        *out = to_photo.apply([x - dx, y - dy]).ok_or(ScanError::BadCorners)?;
    }
    // the corners stay in the rectangle's order, so the transform is the one meant
    let moved = Quad(corners);
    if moved.is_convex() { Ok(moved) } else { Err(ScanError::BadCorners) }
}

/// The luminance of a straightened page.
fn luma_of(page: &Rgb) -> Luma {
    let data = page.data.as_chunks::<3>().0.iter().map(|&p| luma(p)).collect();
    Luma { width: page.width, height: page.height, data }
}

/// A frame count or index as a byte (never over [`MAX_MERGE_FRAMES`]).
fn small(n: usize) -> u8 {
    u8::try_from(n).unwrap_or(u8::MAX)
}

/// Stage timings, one entry per lap.
struct Clock {
    started: Instant,
    timings: Vec<(&'static str, u32)>,
}

impl Clock {
    fn new() -> Clock {
        Clock { started: Instant::now(), timings: Vec::new() }
    }

    /// Record the time since the last lap under `stage`.
    fn lap(&mut self, stage: &'static str) {
        let ms = u32::try_from(self.started.elapsed().as_millis()).unwrap_or(u32::MAX);
        self.timings.push((stage, ms));
        self.started = Instant::now();
    }

    /// One entry per stage, in the order each was first seen, summed over its laps.
    fn summed(self) -> Vec<(&'static str, u32)> {
        let mut out: Vec<(&'static str, u32)> = Vec::new();
        for (stage, ms) in self.timings {
            match out.iter_mut().find(|(s, _)| *s == stage) {
                Some((_, total)) => *total = total.saturating_add(ms),
                None => out.push((stage, ms)),
            }
        }
        out
    }
}
