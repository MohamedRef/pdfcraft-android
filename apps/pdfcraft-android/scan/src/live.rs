//! The page in one camera analysis frame, for the outline in the viewfinder (#295).
//!
//! The camera hands over small frames (about 640 × 480) many times a second, in the sensor's
//! orientation. One frame is turned upright, the detector network looks at it **once** (no turned
//! looks: the next frame is the next try), and its sides are refined lightly on the frame itself
//! (a narrow band, one level: no full-resolution work). When the network finds nothing, nothing is
//! returned: the line detector is asked only when the model file is not installed, because it
//! costs more and its answers jump from frame to frame.

use std::sync::Arc;

use pdfcraft_geom::perspective::Point;

use crate::detect::{Detection, QuadSource, one_look, with_lines};
use crate::models::{EDGES, load};
use crate::{Photo, Pyramid, Quad, RefineOptions, ScanError, refine};

/// The most pixels a camera frame may have (2 MP; the camera is asked for about 0.3).
pub const MAX_FRAME_PIXELS: u64 = 2_000_000;

/// How far from each detected side the refinement looks, in frame pixels. [`refine`] never looks
/// less than 8 pixels away, so this is 8 in effect.
const SEARCH_PX: f32 = 6.0;

/// A corner closer than this share of the frame's width or height to its edge is cut off (or
/// about to be).
const EDGE_SHARE: f32 = 0.015;

/// The corners of the whole frame, as shares.
const WHOLE: [f32; 8] = [0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0];

/// What was found in one camera frame.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct LiveQuad {
    /// x1, y1 … x4, y4 as shares (0–1) of the upright frame: top-left, top-right, bottom-right,
    /// bottom-left. The whole frame when nothing was found.
    pub points: [f32; 8],
    /// 0–1, how sure the detector was (0.5 for the line detector, as in [`crate::detect`]).
    pub confidence: f32,
    /// The mean BT.601 luma of the frame, 0–255.
    pub mean_luma: f32,
    pub found: bool,
    /// A corner lies within 1.5 % of the frame's edge (only when something was found).
    pub cut_off: bool,
    /// What found the page.
    pub source: QuadSource,
    /// How many of the four sides the refinement moved onto an edge in the frame.
    pub refined_sides: u8,
}

/// Find the page in one RGBA camera frame of `width` × `height` pixels, rows packed
/// (`width × 4` bytes each). `rotation_degrees` is the clockwise turn that makes the frame upright
/// (0, 90, 180 or 270: CameraX's `imageInfo.rotationDegrees`); the result is in shares of the
/// upright frame. Bytes past `width × height × 4` are ignored.
pub fn detect_frame(rgba: &[u8], width: u32, height: u32, rotation_degrees: u32) -> Result<LiveQuad, ScanError> {
    let photo = upright_frame(rgba, width, height, rotation_degrees)?;
    Ok(find(&photo, load(EDGES)))
}

/// The frame as an upright RGB photo, after checking what was given.
pub(crate) fn upright_frame(rgba: &[u8], width: u32, height: u32, rotation_degrees: u32) -> Result<Photo, ScanError> {
    if width == 0 || height == 0 {
        return Err(ScanError::BadFrame("its width and height must be more than 0"));
    }
    let pixels = u64::from(width) * u64::from(height);
    if pixels > MAX_FRAME_PIXELS {
        return Err(ScanError::BadFrame("it has more than 2 megapixels"));
    }
    if !matches!(rotation_degrees, 0 | 90 | 180 | 270) {
        return Err(ScanError::BadFrame("its turn must be 0, 90, 180 or 270 degrees"));
    }
    // at most 8 MB: fits in usize on every target
    let needed = usize::try_from(pixels * 4).map_err(|_| ScanError::BadFrame("it has more than 2 megapixels"))?;
    if rgba.len() < needed {
        return Err(ScanError::BadFrame("it has fewer bytes than width × height × 4"));
    }
    let (w, h) = (width as usize, height as usize);
    let src = &rgba[..needed];
    let turned = matches!(rotation_degrees, 90 | 270);
    let (uw, uh) = if turned { (h, w) } else { (w, h) };
    let mut rgb = Vec::with_capacity(w * h * 3);
    if rotation_degrees == 0 {
        rgb.extend(src.as_chunks::<4>().0.iter().flat_map(|p| [p[0], p[1], p[2]]));
    } else {
        // the frame pixel that each upright pixel shows, row by row of the upright frame
        for v in 0..uh {
            for u in 0..uw {
                let (x, y) = match rotation_degrees {
                    90 => (v, h - 1 - u),
                    180 => (w - 1 - u, h - 1 - v),
                    _ => (w - 1 - v, u),
                };
                let i = (y * w + x) * 4;
                rgb.extend_from_slice(&src[i..i + 3]);
            }
        }
    }
    Ok(Photo { width: uw as u32, height: uh as u32, rgb })
}

/// The page in an upright frame, with the detector network `model` (the line detector without
/// it).
pub(crate) fn find(photo: &Photo, model: Option<Arc<rten::Model>>) -> LiveQuad {
    let luma = photo.luma();
    let mean_luma = (luma.data.iter().map(|&v| u64::from(v)).sum::<u64>() as f64 / luma.data.len().max(1) as f64) as f32;
    let found = match model.as_deref().and_then(|model| one_look(model, photo)) {
        Some(found) => found,
        // the model file is not installed (or could not be run): the line detector
        None => with_lines(photo),
    };
    let nothing =
        LiveQuad { points: WHOLE, confidence: found.confidence, mean_luma, found: false, cut_off: false, source: QuadSource::None, refined_sides: 0 };
    let Detection { quad: Some(quad), confidence, source, .. } = found else { return nothing };
    let refined = refine(&Pyramid::new(luma), &quad, &RefineOptions { search_px: Some(SEARCH_PX), sides: [true; 4] });
    let points = refined.quad.to_shares(photo.width, photo.height);
    LiveQuad {
        points,
        confidence,
        mean_luma,
        found: true,
        cut_off: cut_off(&refined.quad, photo.width, photo.height),
        source,
        refined_sides: refined.sides.iter().filter(|s| s.refined).count() as u8,
    }
}

/// Whether a corner of `quad` is within [`EDGE_SHARE`] of the frame's edge.
fn cut_off(quad: &Quad, width: u32, height: u32) -> bool {
    let (w, h) = (f64::from(width), f64::from(height));
    let near = |[x, y]: Point| {
        let (sx, sy) = ((x / w) as f32, (y / h) as f32);
        sx < EDGE_SHARE || sx > 1.0 - EDGE_SHARE || sy < EDGE_SHARE || sy > 1.0 - EDGE_SHARE
    };
    quad.0.iter().any(|p| near(*p))
}
