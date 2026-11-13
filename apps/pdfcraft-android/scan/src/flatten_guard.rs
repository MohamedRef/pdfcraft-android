//! Keep a flattened page only when its text lines are clearly straighter, and trim the strip of
//! table that a flattened page can keep along an edge (#302, spec S16 sections 3 and 5).
//!
//! The straightness figures come from `line_straightness`: pixels, lower is straighter, `None`
//! when the page has too few lines to tell. This module does not measure anything itself; it
//! takes the two figures and decides, and it finds the border strip on a page.

use crate::Rgb;

/// Flattened must be at most this share of the perspective page's figure to be kept (spec: 10 %
/// better). A learned grid adds small errors, so it has to win clearly to be used.
pub const GAIN: f64 = 0.9;

/// The fewest lines of text that must be followed on both pages before the guard may keep a
/// flattened page (#302's review on the owner's reference photos: on 4 of 10 pages only 3 or 4
/// lines were followed, and a 10 % difference between two such readings rests on very little).
pub const MIN_LINES: u32 = 8;

/// With fewer lines than `min_lines` on a page, but at least this many on both, a flattened page
/// is still kept when it reads at most [`FEW_GAIN`] of the perspective page's figure. Corners
/// that are a little off leave every line leaning, and the network takes most of that lean out:
/// a change that large is not chance even on a few lines (#466: the owner's handwritten note,
/// 6 lines, 12.2 px straightened and 3.7 px flattened, and closer to the reference by a third).
pub const FEW_LINES: u32 = 4;
/// See [`FEW_LINES`].
pub const FEW_GAIN: f64 = 0.5;

/// Both figures below this many pixels: the page is already straight, so there is nothing to gain.
const STRAIGHT_ENOUGH_PX: f64 = 0.5;
/// The border search runs on a copy reduced this many times in each direction (spec section 3: 4).
const REDUCE: usize = 4;
/// The most a side may be trimmed, as a share of the page's size across that side (spec: 2 %).
const MAX_TRIM_SHARE: f64 = 0.02;
/// Share of a line's pixels that must look like page for the line to count as page (spec: 90 %).
const PAGE_SHARE: f64 = 0.9;
/// Largest difference per channel from the page's median colour for a pixel to look like page
/// (spec: 40 grey levels).
const COLOUR_TOLERANCE: i32 = 40;

/// Which page to keep.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kept {
    /// The plain perspective-warped page.
    Perspective,
    /// The network-flattened page.
    Flattened,
}

/// Why the guard chose the page it did (for the diagnostics log, see `Why::id`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Why {
    /// The flattened page is at least 10 % straighter, so it is kept.
    Straighter,
    /// The flattened page is not clearly straighter, so the perspective page is kept.
    NotStraighter,
    /// One of the two figures could not be measured; the perspective page is the safe choice.
    Unmeasured,
    /// Both figures are under half a pixel: there is nothing to gain by flattening.
    AlreadyStraight,
    /// The flattened page read straighter, but too few lines were followed on one of the pages
    /// for the reading to count (`MIN_LINES`), so the perspective page is kept.
    FewLines,
    /// Fewer lines than `MIN_LINES` were followed, but at least [`FEW_LINES`] on both pages, and
    /// the flattened page is at most [`FEW_GAIN`] of the perspective page's figure: kept.
    MuchStraighter,
}

/// The guard's decision: the page to keep and the reason.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Guard {
    /// The page to keep.
    pub kept: Kept,
    /// The reason for that choice.
    pub why: Why,
}

impl Why {
    /// A short lowercase word for the diagnostics log. Distinct for each reason.
    pub fn id(self) -> &'static str {
        match self {
            Why::Straighter => "straighter",
            Why::NotStraighter => "notbetter",
            Why::Unmeasured => "unmeasured",
            Why::AlreadyStraight => "straight",
            Why::FewLines => "fewlines",
            Why::MuchStraighter => "muchbetter",
        }
    }
}

/// Decides between the perspective page and the flattened page from their straightness, in
/// pixels (lower is straighter). A figure that is `None`, NaN, infinite or negative counts as not
/// measured; when either side is not measured the perspective page is kept, since it is the safe
/// one. The flattened page is kept only when it is at most `GAIN` times the perspective figure.
pub fn guard(perspective_px: Option<f64>, flattened_px: Option<f64>) -> Guard {
    let (Some(p), Some(f)) = (measured(perspective_px), measured(flattened_px)) else {
        return Guard { kept: Kept::Perspective, why: Why::Unmeasured };
    };
    if p < STRAIGHT_ENOUGH_PX && f < STRAIGHT_ENOUGH_PX {
        return Guard { kept: Kept::Perspective, why: Why::AlreadyStraight };
    }
    if f <= p * GAIN { Guard { kept: Kept::Flattened, why: Why::Straighter } } else { Guard { kept: Kept::Perspective, why: Why::NotStraighter } }
}

/// `guard`, with a floor on the lines followed (#302): a flattened page the figures would keep is
/// kept only when at least `min_lines` lines were followed on both pages. `lines` are the
/// perspective page's and the flattened page's counts, `None` when a page was not measured.
/// `min_lines` 0 is no floor, the guard as before.
///
/// Under the floor a flattened page is still kept when both pages have at least [`FEW_LINES`]
/// lines and it reads at most [`FEW_GAIN`] of the perspective page's figure
/// ([`Why::MuchStraighter`]).
pub fn guard_with_floor(perspective_px: Option<f64>, flattened_px: Option<f64>, lines: (Option<u32>, Option<u32>), min_lines: u32) -> Guard {
    let decision = guard(perspective_px, flattened_px);
    let at_least = |n: Option<u32>, floor: u32| n.is_some_and(|n| n >= floor);
    if decision.kept == Kept::Flattened && min_lines > 0 && !(at_least(lines.0, min_lines) && at_least(lines.1, min_lines)) {
        let far_straighter = matches!((measured(perspective_px), measured(flattened_px)), (Some(p), Some(f)) if f <= p * FEW_GAIN);
        return match far_straighter && at_least(lines.0, FEW_LINES.min(min_lines)) && at_least(lines.1, FEW_LINES.min(min_lines)) {
            true => Guard { kept: Kept::Flattened, why: Why::MuchStraighter },
            false => Guard { kept: Kept::Perspective, why: Why::FewLines },
        };
    }
    decision
}

/// The figure when it is a usable number (finite and not negative), otherwise `None`.
fn measured(value: Option<f64>) -> Option<f64> {
    value.filter(|v| v.is_finite() && *v >= 0.0)
}

/// Pixels to cut from each side of a page.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Trim {
    /// Pixels cut from the left.
    pub left: u32,
    /// Pixels cut from the top.
    pub top: u32,
    /// Pixels cut from the right.
    pub right: u32,
    /// Pixels cut from the bottom.
    pub bottom: u32,
}

/// The four sides of a page.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Side {
    Left,
    Top,
    Right,
    Bottom,
}

/// A reduced copy of a page: each reduced pixel is the mean of a block of page pixels.
struct Small {
    /// Width in reduced pixels.
    w: usize,
    /// Height in reduced pixels.
    h: usize,
    /// Row-major pixels.
    px: Vec<[u8; 3]>,
}

impl Small {
    /// The pixel at `x`, `y`; black when out of range (never reached by the callers).
    fn get(&self, x: usize, y: usize) -> [u8; 3] {
        self.px.get(y * self.w + x).copied().unwrap_or([0; 3])
    }

    /// The number of lines running inward from `side`, and the length of each line.
    fn dims(&self, side: Side) -> (usize, usize) {
        match side {
            Side::Left | Side::Right => (self.w, self.h),
            Side::Top | Side::Bottom => (self.h, self.w),
        }
    }

    /// Pixel `j` along line `i` counted inward from `side`.
    fn at(&self, side: Side, i: usize, j: usize) -> [u8; 3] {
        match side {
            Side::Left => self.get(i, j),
            Side::Right => self.get(self.w.saturating_sub(1 + i), j),
            Side::Top => self.get(j, i),
            Side::Bottom => self.get(j, self.h.saturating_sub(1 + i)),
        }
    }
}

/// Spec S16 section 3: the trim that removes a thin strip of table along the edges of a
/// flattened page. The page's colour is the median of its central half (per channel), measured
/// on a copy reduced `REDUCE` times. From each side, the first line where at least 90 % of the
/// pixels are within 40 of that colour is the page's edge; the trim is that distance, never more
/// than 2 % of the page's size across that side. A side whose first line is already page is not
/// trimmed. A page whose pixels do not match its size gives no trim.
pub fn border_trim(page: &Rgb) -> Trim {
    let Some(small) = reduce(page) else {
        return Trim::default();
    };
    let median = centre_median(&small);
    Trim {
        left: side_trim(&small, Side::Left, median, page.width),
        top: side_trim(&small, Side::Top, median, page.height),
        right: side_trim(&small, Side::Right, median, page.width),
        bottom: side_trim(&small, Side::Bottom, median, page.height),
    }
}

/// The page without the trimmed border. The page itself when nothing is trimmed, when the trim
/// would leave nothing, or when the page's pixels do not match its size.
pub fn trimmed(page: &Rgb, trim: Trim) -> Rgb {
    let cut_w = trim.left.saturating_add(trim.right);
    let cut_h = trim.top.saturating_add(trim.bottom);
    let (Some(new_w), Some(new_h)) = (page.width.checked_sub(cut_w), page.height.checked_sub(cut_h)) else {
        return page.clone();
    };
    if new_w == 0 || new_h == 0 || (cut_w == 0 && cut_h == 0) {
        return page.clone();
    }
    let Some((w, _)) = page_size(page) else {
        return page.clone();
    };
    cut(page, trim, w, (new_w, new_h)).unwrap_or_else(|| page.clone())
}

/// Copies the `out` sized window that starts at the trim's top-left corner. `None` when the data
/// is too short for the window (cannot happen for a page that passed `page_size`).
fn cut(page: &Rgb, trim: Trim, w: usize, out: (u32, u32)) -> Option<Rgb> {
    let (nw, nh) = (usize::try_from(out.0).ok()?, usize::try_from(out.1).ok()?);
    let x0 = usize::try_from(trim.left).ok()?;
    let y0 = usize::try_from(trim.top).ok()?;
    let mut data = Vec::with_capacity(nw * nh * 3);
    for y in y0..y0 + nh {
        let start = (y * w + x0) * 3;
        data.extend_from_slice(page.data.get(start..start + nw * 3)?);
    }
    Some(Rgb { width: out.0, height: out.1, data })
}

/// The width and height of a page when its data holds exactly `width * height` pixels and both
/// are above zero; `None` otherwise.
fn page_size(page: &Rgb) -> Option<(usize, usize)> {
    let w = usize::try_from(page.width).ok()?;
    let h = usize::try_from(page.height).ok()?;
    let bytes = w.checked_mul(h)?.checked_mul(3)?;
    (w > 0 && h > 0 && page.data.len() == bytes).then_some((w, h))
}

/// The page reduced `REDUCE` times: each reduced pixel is the mean of a block of up to
/// `REDUCE` × `REDUCE` page pixels. `None` when the page's size is not valid.
fn reduce(page: &Rgb) -> Option<Small> {
    let (w, h) = page_size(page)?;
    let (sw, sh) = (w.div_ceil(REDUCE), h.div_ceil(REDUCE));
    let mut px = Vec::with_capacity(sw * sh);
    for by in 0..sh {
        for bx in 0..sw {
            px.push(block_mean(&page.data, (w, h), (bx * REDUCE, by * REDUCE)));
        }
    }
    Some(Small { w: sw, h: sh, px })
}

/// The mean colour of the block of page pixels that starts at `(x0, y0)`, clipped to the page.
fn block_mean(data: &[u8], (w, h): (usize, usize), (x0, y0): (usize, usize)) -> [u8; 3] {
    let mut sum = [0u32; 3];
    let mut n = 0u32;
    for y in y0..(y0 + REDUCE).min(h) {
        for x in x0..(x0 + REDUCE).min(w) {
            let i = (y * w + x) * 3;
            if let Some(p) = data.get(i..i + 3) {
                for (s, v) in sum.iter_mut().zip(p) {
                    *s += u32::from(*v);
                }
                n += 1;
            }
        }
    }
    if n == 0 {
        return [0; 3];
    }
    sum.map(|s| ((s + n / 2) / n) as u8)
}

/// The per-channel median of the central half of the reduced page (its middle quarter-to-three-
/// quarter span in each direction). Never empty, so the median always exists.
fn centre_median(small: &Small) -> [u8; 3] {
    let (x0, x1) = (small.w / 4, small.w - small.w / 4);
    let (y0, y1) = (small.h / 4, small.h - small.h / 4);
    let centre: Vec<[u8; 3]> = (y0..y1).flat_map(|y| (x0..x1).map(move |x| small.get(x, y))).collect();
    [0, 1, 2].map(|c| median_of(centre.iter().map(|p| p[c]).collect()))
}

/// The median of a list of channel values (the upper middle one for an even count); 0 when empty.
fn median_of(mut values: Vec<u8>) -> u8 {
    if values.is_empty() {
        return 0;
    }
    let mid = values.len() / 2;
    *values.select_nth_unstable(mid).1
}

/// The trim on one side, in full-resolution pixels. `depth_px` is the page's size across that
/// side (its width for the left and right sides), which sets the 2 % cap.
fn side_trim(small: &Small, side: Side, median: [u8; 3], depth_px: u32) -> u32 {
    let cap = (f64::from(depth_px) * MAX_TRIM_SHARE) as u32;
    let lines = first_page_line(small, side, median).saturating_mul(REDUCE);
    u32::try_from(lines).unwrap_or(u32::MAX).min(cap)
}

/// The number of reduced lines between `side` and the first line that looks like page; the
/// count of all lines when none does.
fn first_page_line(small: &Small, side: Side, median: [u8; 3]) -> usize {
    let (depth, _) = small.dims(side);
    (0..depth).find(|&i| line_is_page(small, side, i, median)).unwrap_or(depth)
}

/// Whether at least `PAGE_SHARE` of the pixels on line `i` (counted inward from `side`) look like
/// page.
fn line_is_page(small: &Small, side: Side, i: usize, median: [u8; 3]) -> bool {
    let (_, span) = small.dims(side);
    let page = (0..span).filter(|&j| is_page(small.at(side, i, j), median)).count();
    page as f64 >= PAGE_SHARE * span as f64
}

/// Whether every channel of `p` is within `COLOUR_TOLERANCE` of the page colour `median`.
fn is_page(p: [u8; 3], median: [u8; 3]) -> bool {
    p.iter().zip(median.iter()).all(|(a, b)| (i32::from(*a) - i32::from(*b)).abs() <= COLOUR_TOLERANCE)
}
