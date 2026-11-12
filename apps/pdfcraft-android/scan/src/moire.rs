//! Moiré on photos of screens and printed halftones (#305, research): find it, and take it out
//! with notches in the frequency domain.
//!
//! A screen's pixel grid and a halftone's dot pattern are periodic, so on a page photo they show
//! as a few narrow peaks in the spectrum away from its centre. Print is broadband along a few
//! directions, and its periodic parts (lines of text, ruled tables) are at long periods. So:
//!
//! - [`find`] cuts the page's grey copy into [`TILE`]-pixel squares, and in each square that is
//!   not blank looks for a pair of narrow peaks [`PEAK_RATIO`] times stronger than the median of
//!   their ring, at a period of at most [`MAX_PERIOD`] pixels once harmonics are traced back to
//!   their fundamental. The page is a screen photo when more than [`SCREEN_SHARE`] of the squares
//!   that are not blank are periodic at the same frequency, and a halftone when the periodic
//!   squares make a compact block (a printed photograph on a page of print).
//! - [`remove`] goes over the page in squares overlapping by half, with a Hann window, and
//!   notches out the found frequencies and their harmonics where a square has them; the squares
//!   are added back together, which the window makes exact where nothing was notched.
//!
//! Nothing in the app calls this yet: the issue asks for measurements before anything ships. All
//! thresholds are the issue's starting values, except [`MAX_PERIOD`], which this file adds so a
//! ruled table's harmonics are not taken for a grid; see `docs/ANDROID_SCAN_MOIRE.md`.

use crate::photo::Luma;

/// Side of the squares the spectrum is taken over (a power of two).
pub const TILE: usize = 256;
/// Radius, in frequency bins, of the disc around the centre that is not searched (lighting,
/// lines of text and anything else slower than a period of `TILE / 8` pixels).
pub const LOW_DISC: f32 = 8.0;
/// How many times stronger than the median of its ring a peak must be.
pub const PEAK_RATIO: f32 = 8.0;
/// A peak is narrow when it spans fewer bins than this across (at half its height).
pub const PEAK_WIDTH: f32 = 3.0;
/// Longest period, in pixels, that counts as a screen or a halftone: a ruled table or a dotted
/// background drawn on purpose has a longer one.
pub const MAX_PERIOD: f32 = 16.0;
/// Share of the squares that are not blank which must be periodic for a screen photo.
pub const SCREEN_SHARE: f32 = 0.40;
/// A square is blank when its grey levels' standard deviation is under this.
pub const BLANK_SPREAD: f32 = 3.0;
/// Two squares' peaks are the same frequency when they are within this many bins.
const SAME_FREQUENCY: f32 = 2.0;
/// Share of the periodic squares that must agree on the frequency for a screen photo.
const AGREEING: f32 = 0.7;
/// A halftone block: at least this many periodic squares, filling this share of the rectangle
/// around them.
const BLOCK_TILES: usize = 4;
const BLOCK_FILL: f32 = 0.6;
/// Standard deviation of a notch, in bins.
const NOTCH_SIGMA: f32 = 2.5;
/// The most peaks of one square that are looked at.
const MAX_CANDIDATES: usize = 48;
/// In [`remove`], a square is notched at a frequency only where it has a peak there at least
/// this many times its ring's median (as [`find`] asks: a square half over a halftone block still
/// has half of its peak).
const NOTCH_RATIO: f32 = 8.0;

/// What the page is, as far as periodic patterns go.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MoireKind {
    /// No periodic pattern at a short period.
    None,
    /// Most of the page has the same grid: a photo of a monitor or a phone.
    Screen,
    /// A compact block of the page has one: a printed photograph made of dots.
    Halftone,
}

impl MoireKind {
    /// The word written to the diagnostics log and a kept scan's page file.
    pub fn word(self) -> &'static str {
        match self {
            MoireKind::None => "none",
            MoireKind::Screen => "screen",
            MoireKind::Halftone => "halftone",
        }
    }
}

/// A frequency in bins of a [`TILE`]-pixel square, with `v >= 0` (its mirror is implied).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Frequency {
    pub u: f32,
    pub v: f32,
}

impl Frequency {
    /// Distance from the centre of the spectrum, in bins.
    pub fn radius(self) -> f32 {
        self.u.hypot(self.v)
    }

    /// The pattern's period in pixels.
    pub fn period(self) -> f32 {
        TILE as f32 / self.radius().max(f32::EPSILON)
    }

    /// The pattern's direction, in degrees from the horizontal axis, 0 to 180.
    pub fn angle(self) -> f32 {
        self.v.atan2(self.u).to_degrees().rem_euclid(180.0)
    }

    fn near(self, other: Frequency) -> bool {
        let same = (self.u - other.u).hypot(self.v - other.v) <= SAME_FREQUENCY;
        let mirrored = (self.u + other.u).hypot(self.v + other.v) <= SAME_FREQUENCY;
        same || mirrored
    }
}

/// What [`find`] saw.
#[derive(Clone, Debug, PartialEq)]
pub struct Moire {
    pub kind: MoireKind,
    /// Squares looked at, and how many of them were not blank.
    pub tiles: usize,
    pub busy_tiles: usize,
    /// Squares with a narrow peak at a short period.
    pub periodic_tiles: usize,
    /// The frequencies found, the most common first (empty when `kind` is `None`).
    pub frequencies: Vec<Frequency>,
}

impl Moire {
    /// The period, in pixels, of the most common frequency.
    pub fn period(&self) -> Option<f32> {
        self.frequencies.first().map(|f| f.period())
    }
}

/// Look for a screen's grid or a halftone's dots on a straightened page (its grey copy).
///
/// A page smaller than one square in either direction has no square to look at and is `None`.
pub fn find(page: &Luma) -> Moire {
    let (w, h) = (page.width as usize, page.height as usize);
    let (cols, rows) = (w / TILE, h / TILE);
    let window = hann(TILE);
    let mut busy = 0;
    let mut found: Vec<(usize, usize, Frequency)> = Vec::new();
    for row in 0..rows {
        for col in 0..cols {
            let tile = square(page, col * TILE, row * TILE);
            if spread(&tile) < BLANK_SPREAD {
                continue;
            }
            busy += 1;
            let spectrum = magnitude(&tile, &window);
            if let Some(f) = tile_peak(&spectrum) {
                found.push((col, row, f));
            }
        }
    }
    let periodic = found.len();
    let frequencies = common(&found.iter().map(|&(_, _, f)| f).collect::<Vec<_>>());
    let kind = classify(busy, &found, &frequencies);
    Moire {
        kind,
        tiles: cols * rows,
        busy_tiles: busy,
        periodic_tiles: periodic,
        frequencies: if kind == MoireKind::None { Vec::new() } else { frequencies },
    }
}

fn classify(busy: usize, found: &[(usize, usize, Frequency)], frequencies: &[Frequency]) -> MoireKind {
    if frequencies.is_empty() || found.is_empty() {
        return MoireKind::None;
    }
    // squares agreeing on the most common frequency, or the second (a lattice of dots has two
    // directions and squares may report either); squares with a stray peak of their own don't count
    let mut main = vec![frequencies[0]];
    if let Some(&second) = frequencies.get(1) {
        let (r0, r1) = (frequencies[0].radius(), second.radius());
        let turn = (frequencies[0].angle() - second.angle()).rem_euclid(180.0);
        if (r0 - r1).abs() <= 0.15 * r0.max(r1) && (60.0..=120.0).contains(&turn) {
            main.push(second);
        }
    }
    let agreeing: Vec<(usize, usize)> = found.iter().filter(|(_, _, f)| main.iter().any(|m| f.near(*m))).map(|&(c, r, _)| (c, r)).collect();
    if busy > 0 && agreeing.len() as f32 > SCREEN_SHARE * busy as f32 && agreeing.len() as f32 >= AGREEING * found.len() as f32 {
        return MoireKind::Screen;
    }
    if agreeing.len() >= BLOCK_TILES {
        let (c0, c1) = agreeing.iter().fold((usize::MAX, 0), |(lo, hi), &(c, _)| (lo.min(c), hi.max(c)));
        let (r0, r1) = agreeing.iter().fold((usize::MAX, 0), |(lo, hi), &(_, r)| (lo.min(r), hi.max(r)));
        let area = (c1 - c0 + 1) * (r1 - r0 + 1);
        if agreeing.len() as f32 >= BLOCK_FILL * area as f32 {
            return MoireKind::Halftone;
        }
    }
    MoireKind::None
}

/// Group close frequencies; the groups' means, the largest group first.
fn common(found: &[Frequency]) -> Vec<Frequency> {
    let mut groups: Vec<(Frequency, Vec<Frequency>)> = Vec::new();
    for &f in found {
        match groups.iter_mut().find(|(first, _)| first.near(f)) {
            Some((first, members)) => {
                // keep members on the same side as the group's first one
                let same = (first.u - f.u).hypot(first.v - f.v) <= SAME_FREQUENCY;
                members.push(if same { f } else { Frequency { u: -f.u, v: -f.v } });
            }
            None => groups.push((f, vec![f])),
        }
    }
    groups.sort_by_key(|g| std::cmp::Reverse(g.1.len()));
    groups
        .into_iter()
        .map(|(_, members)| {
            let n = members.len() as f32;
            let (u, v) = members.iter().fold((0.0, 0.0), |(u, v), f| (u + f.u, v + f.v));
            Frequency { u: u / n, v: v / n }
        })
        .collect()
}

/// Take the found frequencies out of the page, where they are. Returns the page unchanged when
/// `found` has none.
pub fn remove(page: &Luma, found: &Moire) -> Luma {
    if found.frequencies.is_empty() {
        return page.clone();
    }
    let data = notch_plane(&page.data, page.width as usize, page.height as usize, 1, 0, &found.frequencies);
    Luma { width: page.width, height: page.height, data }
}

/// [`remove`] on each channel of an RGB8 page, with the frequencies [`find`] saw on its grey
/// copy.
pub fn remove_rgb(rgb: &[u8], width: u32, height: u32, found: &Moire) -> Vec<u8> {
    if found.frequencies.is_empty() {
        return rgb.to_vec();
    }
    let (w, h) = (width as usize, height as usize);
    let mut out = rgb.to_vec();
    for channel in 0..3 {
        let plane = notch_plane(rgb, w, h, 3, channel, &found.frequencies);
        for (i, v) in plane.into_iter().enumerate() {
            out[i * 3 + channel] = v;
        }
    }
    out
}

/// Notch one channel of an interleaved picture: squares of [`TILE`] stepped by half of it over
/// the picture extended by mirroring, Hann-windowed (the windows of overlapping squares add up to
/// one), filtered where they have the peaks, added back.
fn notch_plane(src: &[u8], w: usize, h: usize, stride: usize, channel: usize, frequencies: &[Frequency]) -> Vec<u8> {
    let half = TILE / 2;
    let window = hann(TILE);
    let notches = notch_centres(frequencies);
    // the extended picture runs from -half to w + half (and the same down), so every pixel is in
    // squares whose windows add to one
    let (ew, eh) = (w + 2 * half, h + 2 * half);
    let mut sum = vec![0f32; ew * eh];
    let mut re = vec![0f32; TILE * TILE];
    let mut im = vec![0f32; TILE * TILE];
    let mut y0 = 0;
    while y0 + TILE <= eh + half {
        let mut x0 = 0;
        while x0 + TILE <= ew + half {
            for ty in 0..TILE {
                let sy = mirror(y0 as isize + ty as isize - half as isize, h);
                for tx in 0..TILE {
                    let sx = mirror(x0 as isize + tx as isize - half as isize, w);
                    let v = f32::from(src[(sy * w + sx) * stride + channel]);
                    re[ty * TILE + tx] = v * window[ty] * window[tx];
                    im[ty * TILE + tx] = 0.0;
                }
            }
            fft2(&mut re, &mut im, false);
            filter(&mut re, &mut im, &notches);
            fft2(&mut re, &mut im, true);
            for ty in 0..TILE {
                let ey = y0 + ty;
                if ey >= eh {
                    break;
                }
                for tx in 0..TILE {
                    let ex = x0 + tx;
                    if ex < ew {
                        sum[ey * ew + ex] += re[ty * TILE + tx];
                    }
                }
            }
            x0 += half;
        }
        y0 += half;
    }
    let mut out = vec![0u8; w * h];
    for y in 0..h {
        for x in 0..w {
            out[y * w + x] = sum[(y + half) * ew + x + half].round().clamp(0.0, 255.0) as u8;
        }
    }
    out
}

/// The found frequencies and their harmonics below the Nyquist limit, both mirrors.
fn notch_centres(frequencies: &[Frequency]) -> Vec<Frequency> {
    let nyquist = (TILE / 2) as f32;
    let mut out = Vec::new();
    for f in frequencies {
        for k in 1..=4 {
            let (u, v) = (f.u * k as f32, f.v * k as f32);
            if u.abs() >= nyquist || v.abs() >= nyquist {
                break;
            }
            out.push(Frequency { u, v });
            out.push(Frequency { u: -u, v: -v });
        }
    }
    out
}

/// Multiply the spectrum by Gaussian notches at the centres this square has peaks at.
fn filter(re: &mut [f32], im: &mut [f32], notches: &[Frequency]) {
    let n = TILE;
    let mags: Vec<f32> = re.iter().zip(im.iter()).map(|(r, i)| r.hypot(*i)).collect();
    let rings = ring_medians(&mags);
    let present: Vec<Frequency> = notches
        .iter()
        .copied()
        .filter(|f| {
            let (bu, bv) = (f.u.round() as isize, f.v.round() as isize);
            let mut best = 0f32;
            for dv in -1..=1 {
                for du in -1..=1 {
                    best = best.max(mags[index(bu + du, bv + dv, n)]);
                }
            }
            let ring = rings.get(f.radius().round() as usize).copied().unwrap_or(0.0);
            best > NOTCH_RATIO * ring.max(f32::EPSILON)
        })
        .collect();
    if present.is_empty() {
        return;
    }
    let reach = (NOTCH_SIGMA * 4.0).ceil() as isize;
    for f in present {
        let (cu, cv) = (f.u.round() as isize, f.v.round() as isize);
        for dv in -reach..=reach {
            for du in -reach..=reach {
                let (u, v) = (cu + du, cv + dv);
                let d2 = (u as f32 - f.u).powi(2) + (v as f32 - f.v).powi(2);
                let keep = 1.0 - (-d2 / (2.0 * NOTCH_SIGMA * NOTCH_SIGMA)).exp();
                let i = index(u, v, n);
                re[i] *= keep;
                im[i] *= keep;
            }
        }
    }
}

/// The strongest narrow peak outside the low disc of one square's spectrum whose pattern has a
/// short period. Bins are addressed by signed frequency (no shifted copy is built).
///
/// Every narrow peak above threshold is a candidate, strongest first. A candidate's period is
/// that of the comb it belongs to: the peaks on the same ray from the centre, whose spacing is
/// the pattern's fundamental. A grating has peaks at f, 2f, 3f (spacing f); lines of text or a
/// ruled table have a comb of many peaks at a long period's harmonics (spacing small), however
/// strong one of the harmonics is.
fn tile_peak(mags: &[f32]) -> Option<Frequency> {
    let n = TILE as isize;
    let half = n / 2;
    let rings = ring_medians(mags);
    let threshold = |u: isize, v: isize| {
        let r = ((u * u + v * v) as f32).sqrt().round() as usize;
        PEAK_RATIO * rings.get(r).copied().unwrap_or(f32::INFINITY).max(f32::EPSILON)
    };
    // narrow peaks may be a screen's or a halftone's; any peak may be a relative that shows a
    // narrow one belongs to print or a table
    let mut candidates: Vec<Peak> = Vec::new();
    let mut relatives: Vec<Peak> = Vec::new();
    // the upper half-plane (v > 0, or v == 0 and u > 0); the rest is its mirror. Peaks nearer the
    // centre than the low disc still count as relatives, down to 3 bins.
    for v in 0..half {
        for u in -half + 1..half {
            if v == 0 && u <= 0 {
                continue;
            }
            let r = ((u * u + v * v) as f32).sqrt();
            if r < 3.0 || r >= half as f32 {
                continue;
            }
            let m = mags[index(u, v, TILE)];
            if m <= threshold(u, v) || !local_max(mags, u, v) {
                continue;
            }
            let peak = Peak { ratio: m / threshold(u, v), magnitude: m, at: centroid(mags, u, v) };
            if narrow(mags, u, v) {
                candidates.push(peak);
            }
            relatives.push(peak);
        }
    }
    candidates.sort_by(|a, b| b.ratio.total_cmp(&a.ratio));
    let long_radius = TILE as f32 / MAX_PERIOD;
    let free: Vec<Peak> =
        candidates.iter().take(MAX_CANDIDATES).filter(|p| p.at.radius() > LOW_DISC.max(long_radius) && !in_family(p, &relatives)).copied().collect();
    // of the pattern's own peaks, its fundamental: the nearest the centre of those at least half
    // as strong as the strongest (a pattern's harmonics and aliases lie further out)
    let strongest = free.first()?.ratio;
    free.iter().filter(|p| p.ratio >= 0.5 * strongest).min_by(|a, b| a.at.radius().total_cmp(&b.at.radius())).map(|p| p.at)
}

/// A local maximum of a square's spectrum.
#[derive(Clone, Copy, Debug)]
struct Peak {
    /// Its magnitude over the threshold of its ring (above 1).
    ratio: f32,
    magnitude: f32,
    at: Frequency,
}

/// `peak` belongs to a family of peaks of comparable magnitude that a long-period pattern makes,
/// not to a screen's or a halftone's grid:
///
/// - a comb: another peak on its ray from the centre that is not one of its own harmonics (lines
///   of text, whose strokes repeat at the line pitch, have a comb of them up to the Nyquist
///   limit);
/// - a harmonic: it is 2 to 6 times a peak of a long period (a ruled table's thin lines);
/// - a lattice point: it is a whole combination of two peaks of long periods (a ruled table's
///   crossings, a dotted background's diagonals).
///
/// "Comparable" is at least half `peak`'s magnitude. A grating on a printed page is far stronger
/// than the print's own peaks, and the sidebands that the picture under a halftone's dots puts
/// beside its peak are weaker than the peak; a long period's low harmonics are as strong as its
/// high ones or stronger.
fn in_family(peak: &Peak, relatives: &[Peak]) -> bool {
    let comparable = 0.5 * peak.magnitude;
    let long_radius = TILE as f32 / MAX_PERIOD;
    let f = peak.at;
    let r = f.radius();
    let (du, dv) = (f.u / r, f.v / r);
    let mut teeth = 0usize;
    let mut long: Vec<Frequency> = Vec::new();
    for p in relatives.iter().filter(|p| p.magnitude >= comparable) {
        let q = p.at;
        if (q.u - f.u).hypot(q.v - f.v) < 1.5 {
            continue;
        }
        let along = (q.u * du + q.v * dv).abs();
        let across = (q.u * dv - q.v * du).abs();
        let harmonic_of_peak = along > r && (along - (along / r).round() * r).abs() <= 1.5;
        if across <= 1.5 && !harmonic_of_peak {
            teeth += 1;
        }
        if q.radius() < long_radius {
            let m = (r / q.radius()).round();
            if (2.0..=6.0).contains(&m) && [1.0f32, -1.0].iter().any(|s| (f.u - s * m * q.u).hypot(f.v - s * m * q.v) <= 1.5) {
                return true;
            }
            long.push(q);
        }
    }
    // the comb: a peak that is not twice as strong as every other peak on its ray (bar its own
    // harmonics) is one tooth of many
    if teeth > 0 {
        return true;
    }
    for (n, p) in long.iter().enumerate() {
        for q in &long[n + 1..] {
            let det = p.u * q.v - p.v * q.u;
            if det.abs() < 1.0 {
                continue;
            }
            let i = (f.u * q.v - f.v * q.u) / det;
            let j = (p.u * f.v - p.v * f.u) / det;
            let whole = |x: f32| (x - x.round()).abs() <= 0.15 && x.abs() <= 16.5;
            if whole(i) && whole(j) {
                return true;
            }
        }
    }
    false
}

/// A peak's position to a fraction of a bin: the centroid of the 3×3 magnitudes around it.
fn centroid(mags: &[f32], u: isize, v: isize) -> Frequency {
    let (mut su, mut sv, mut sum) = (0f32, 0f32, 0f32);
    for dv in -1..=1 {
        for du in -1..=1 {
            let m = mags[index(u + du, v + dv, TILE)];
            su += m * du as f32;
            sv += m * dv as f32;
            sum += m;
        }
    }
    let sum = sum.max(f32::EPSILON);
    Frequency { u: u as f32 + su / sum, v: v as f32 + sv / sum }
}

fn local_max(mags: &[f32], u: isize, v: isize) -> bool {
    let m = mags[index(u, v, TILE)];
    (-1..=1).all(|dv| (-1..=1).all(|du| (du == 0 && dv == 0) || mags[index(u + du, v + dv, TILE)] <= m))
}

/// Fewer than [`PEAK_WIDTH`]² bins of the 7×7 around the peak are above half its height.
fn narrow(mags: &[f32], u: isize, v: isize) -> bool {
    let half_height = mags[index(u, v, TILE)] / 2.0;
    let mut count = 0;
    for dv in -3..=3 {
        for du in -3..=3 {
            if mags[index(u + du, v + dv, TILE)] > half_height {
                count += 1;
            }
        }
    }
    (count as f32) < PEAK_WIDTH * PEAK_WIDTH
}

/// The median magnitude of each ring of whole-bin radius (rings two bins wide).
fn ring_medians(mags: &[f32]) -> Vec<f32> {
    let n = TILE as isize;
    let half = n / 2;
    let mut rings: Vec<Vec<f32>> = vec![Vec::new(); half as usize + 2];
    for v in -half + 1..half {
        for u in -half + 1..half {
            let r = ((u * u + v * v) as f32).sqrt();
            let lo = (r - 1.0).max(0.0).round() as usize;
            let hi = (r + 1.0).round() as usize;
            let m = mags[index(u, v, TILE)];
            for ring in rings.iter_mut().take(hi.min(half as usize + 1) + 1).skip(lo) {
                ring.push(m);
            }
        }
    }
    rings
        .into_iter()
        .map(|mut ring| {
            if ring.is_empty() {
                return f32::INFINITY;
            }
            let mid = ring.len() / 2;
            ring.select_nth_unstable_by(mid, f32::total_cmp);
            ring[mid]
        })
        .collect()
}

/// Index of the signed frequency (u, v) in an unshifted `n`×`n` spectrum.
fn index(u: isize, v: isize, n: usize) -> usize {
    let n = n as isize;
    (v.rem_euclid(n) * n + u.rem_euclid(n)) as usize
}

fn mirror(i: isize, len: usize) -> usize {
    let len = len as isize;
    if len == 1 {
        return 0;
    }
    let period = 2 * (len - 1);
    let k = i.rem_euclid(period);
    (if k < len { k } else { period - k }) as usize
}

fn square(page: &Luma, x0: usize, y0: usize) -> Vec<f32> {
    let w = page.width as usize;
    let mut out = Vec::with_capacity(TILE * TILE);
    for y in y0..y0 + TILE {
        out.extend(page.data[y * w + x0..y * w + x0 + TILE].iter().map(|&v| f32::from(v)));
    }
    out
}

fn spread(tile: &[f32]) -> f32 {
    let n = tile.len() as f32;
    let mean = tile.iter().sum::<f32>() / n;
    (tile.iter().map(|v| (v - mean).powi(2)).sum::<f32>() / n).sqrt()
}

/// The spectrum's magnitude of a square with its mean taken off, Hann-windowed.
fn magnitude(tile: &[f32], window: &[f32]) -> Vec<f32> {
    let mean = tile.iter().sum::<f32>() / tile.len() as f32;
    let mut re: Vec<f32> = tile.iter().enumerate().map(|(i, v)| (v - mean) * window[i / TILE] * window[i % TILE]).collect();
    let mut im = vec![0f32; tile.len()];
    fft2(&mut re, &mut im, false);
    re.iter().zip(im.iter()).map(|(r, i)| r.hypot(*i)).collect()
}

/// The periodic Hann window: copies stepped by half its length add up to exactly one.
fn hann(n: usize) -> Vec<f32> {
    (0..n).map(|i| 0.5 - 0.5 * (std::f32::consts::TAU * i as f32 / n as f32).cos()).collect()
}

/// A 2D FFT of a [`TILE`]×[`TILE`] square in place: rows, then columns. The inverse is scaled by
/// 1 / n².
pub(crate) fn fft2(re: &mut [f32], im: &mut [f32], inverse: bool) {
    let n = TILE;
    for row in 0..n {
        fft(&mut re[row * n..(row + 1) * n], &mut im[row * n..(row + 1) * n], inverse);
    }
    let (mut cr, mut ci) = (vec![0f32; n], vec![0f32; n]);
    for col in 0..n {
        for i in 0..n {
            cr[i] = re[i * n + col];
            ci[i] = im[i * n + col];
        }
        fft(&mut cr, &mut ci, inverse);
        for i in 0..n {
            re[i * n + col] = cr[i];
            im[i * n + col] = ci[i];
        }
    }
    if inverse {
        let scale = 1.0 / (n * n) as f32;
        re.iter_mut().for_each(|v| *v *= scale);
        im.iter_mut().for_each(|v| *v *= scale);
    }
}

/// Iterative radix-2 FFT of a power-of-two length, in place, unscaled.
pub(crate) fn fft(re: &mut [f32], im: &mut [f32], inverse: bool) {
    let n = re.len();
    if n < 2 || !n.is_power_of_two() || im.len() != n {
        return;
    }
    let bits = n.trailing_zeros();
    for i in 0..n {
        let j = i.reverse_bits() >> (usize::BITS - bits);
        if j > i {
            re.swap(i, j);
            im.swap(i, j);
        }
    }
    let sign = if inverse { 1.0 } else { -1.0 };
    // twiddles once per call, in f64 so they do not depend on the order of operations
    let twiddles: Vec<(f32, f32)> = (0..n / 2)
        .map(|k| {
            let (s, c) = (sign * std::f64::consts::TAU * k as f64 / n as f64).sin_cos();
            (c as f32, s as f32)
        })
        .collect();
    let mut len = 2;
    while len <= n {
        let stride = n / len;
        for start in (0..n).step_by(len) {
            for k in 0..len / 2 {
                let (wr, wi) = twiddles[k * stride];
                let (a, b) = (start + k, start + k + len / 2);
                let tr = re[b] * wr - im[b] * wi;
                let ti = re[b] * wi + im[b] * wr;
                re[b] = re[a] - tr;
                im[b] = im[a] - ti;
                re[a] += tr;
                im[a] += ti;
            }
        }
        len *= 2;
    }
}
