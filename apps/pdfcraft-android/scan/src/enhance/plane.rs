//! Single-channel float images and the few filters the Auto look needs. Everything here is plain
//! arithmetic on rows: no threads, no state, the same numbers on every machine.

/// One channel, row-major, values nominally 0–1.
#[derive(Clone, Debug, PartialEq)]
pub(crate) struct Plane {
    pub w: usize,
    pub h: usize,
    pub d: Vec<f32>,
}

impl Plane {
    pub fn new(w: usize, h: usize, value: f32) -> Plane {
        Plane { w, h, d: vec![value; w * h] }
    }

    pub fn row(&self, y: usize) -> &[f32] {
        &self.d[y * self.w..(y + 1) * self.w]
    }

    /// A new plane with `f` applied to every value.
    pub fn map(&self, f: impl Fn(f32) -> f32) -> Plane {
        Plane { w: self.w, h: self.h, d: self.d.iter().map(|&v| f(v)).collect() }
    }

    /// A new plane from this one and `other`, value by value.
    pub fn zip(&self, other: &Plane, f: impl Fn(f32, f32) -> f32) -> Plane {
        Plane { w: self.w, h: self.h, d: self.d.iter().zip(&other.d).map(|(&a, &b)| f(a, b)).collect() }
    }
}

/// A yes/no value per pixel.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Mask {
    pub width: usize,
    pub height: usize,
    pub data: Vec<bool>,
}

impl Mask {
    pub(crate) fn new(width: usize, height: usize) -> Mask {
        Mask { width, height, data: vec![false; width * height] }
    }

    /// The share of pixels that are set (0 for an empty mask).
    pub fn share(&self) -> f32 {
        if self.data.is_empty() {
            return 0.0;
        }
        self.data.iter().filter(|&&v| v).count() as f32 / self.data.len() as f32
    }
}

/// BT.601 luminance of three planes (R, G, B).
pub(crate) fn luma3(c: &[Plane; 3]) -> Plane {
    let d = c[0].d.iter().zip(&c[1].d).zip(&c[2].d).map(|((&r, &g), &b)| 0.299 * r + 0.587 * g + 0.114 * b).collect();
    Plane { w: c[0].w, h: c[0].h, d }
}

/// The `p`-th percentile (0–100) with linear interpolation between neighbours, as numpy does it.
/// Reorders `v`. 0 for an empty slice.
pub(crate) fn percentile(v: &mut [f32], p: f32) -> f32 {
    if v.is_empty() {
        return 0.0;
    }
    let pos = f64::from(p.clamp(0.0, 100.0)) / 100.0 * (v.len() - 1) as f64;
    let lo = pos.floor() as usize;
    let frac = (pos - lo as f64) as f32;
    let (_, &mut a, right) = v.select_nth_unstable_by(lo, f32::total_cmp);
    if frac <= 0.0 || right.is_empty() {
        return a;
    }
    let b = right.iter().copied().fold(f32::INFINITY, f32::min);
    a + (b - a) * frac
}

pub(crate) fn median(v: &mut [f32]) -> f32 {
    percentile(v, 50.0)
}

/// Shrink interleaved rows of `ch` channels from `w` × `h` to `ow` × `oh` by exact area
/// averaging. `fill` writes source row `y` (length `w * ch`). Never enlarges: `ow <= w`, `oh <= h`.
pub(crate) fn area_rows(size: (usize, usize), ch: usize, out: (usize, usize), mut fill: impl FnMut(usize, &mut [f32])) -> Vec<f32> {
    let ((w, h), (ow, oh)) = (size, out);
    let (fx, fy) = (w as f64 / ow as f64, h as f64 / oh as f64);
    // each source column feeds at most two output columns
    let split = |i: usize, f: f64, n: usize| -> [(usize, f32); 2] {
        let o0 = ((i as f64 / f).floor() as usize).min(n - 1);
        let end0 = (o0 + 1) as f64 * f;
        let first = (end0.min((i + 1) as f64) - i as f64).clamp(0.0, 1.0);
        [(o0, first as f32), ((o0 + 1).min(n - 1), (1.0 - first) as f32)]
    };
    let cols: Vec<[(usize, f32); 2]> = (0..w).map(|x| split(x, fx, ow)).collect();
    let mut src = vec![0f32; w * ch];
    let mut hrow = vec![0f32; ow * ch];
    let mut acc = vec![0f32; ow * oh * ch];
    for y in 0..h {
        fill(y, &mut src);
        hrow.fill(0.0);
        for (x, px) in src.chunks_exact(ch).enumerate() {
            for &(o, wgt) in &cols[x] {
                if wgt > 0.0 {
                    for (a, &v) in hrow[o * ch..(o + 1) * ch].iter_mut().zip(px) {
                        *a += v * wgt;
                    }
                }
            }
        }
        for &(o, wgt) in &split(y, fy, oh) {
            if wgt > 0.0 {
                for (a, &v) in acc[o * ow * ch..(o + 1) * ow * ch].iter_mut().zip(&hrow) {
                    *a += v * wgt;
                }
            }
        }
    }
    let norm = (1.0 / (fx * fy)) as f32;
    acc.iter_mut().for_each(|v| *v *= norm);
    acc
}

/// RGB8 rows shrunk by a whole factor `k` (each output pixel is the mean of a k × k cell; a
/// remainder of fewer than `k` pixels at the right and bottom is left out), as floats in 0–1.
pub(crate) fn shrink_rgb(data: &[u8], w: usize, h: usize, k: usize) -> (Vec<f32>, usize, usize) {
    let (ow, oh) = (w / k, h / k);
    let norm = 1.0 / (255.0 * (k * k) as f32);
    let mut out = vec![0f32; ow * oh * 3];
    let mut sums = vec![0u32; ow * 3];
    for oy in 0..oh {
        sums.fill(0);
        for y in oy * k..(oy + 1) * k {
            let row = &data[y * w * 3..(y * w + ow * k) * 3];
            for (cell, s) in row.chunks_exact(k * 3).zip(sums.chunks_exact_mut(3)) {
                for px in cell.chunks_exact(3) {
                    s[0] += u32::from(px[0]);
                    s[1] += u32::from(px[1]);
                    s[2] += u32::from(px[2]);
                }
            }
        }
        for (o, &s) in out[oy * ow * 3..(oy + 1) * ow * 3].iter_mut().zip(&sums) {
            *o = s as f32 * norm;
        }
    }
    (out, ow, oh)
}

/// [`area_rows`] for one plane.
pub(crate) fn area(src: &Plane, ow: usize, oh: usize) -> Plane {
    let d = area_rows((src.w, src.h), 1, (ow, oh), |y, row| row.copy_from_slice(src.row(y)));
    Plane { w: ow, h: oh, d }
}

/// Where output sample `i` of `n` falls between two source samples of `m`: (left index, right
/// index, weight of the right one), with pixel centres aligned and the edges clamped.
pub(crate) fn lerp_taps(n: usize, m: usize) -> Vec<(usize, usize, f32)> {
    let scale = m as f64 / n as f64;
    (0..n)
        .map(|i| {
            let pos = ((i as f64 + 0.5) * scale - 0.5).clamp(0.0, (m - 1) as f64);
            let lo = pos.floor() as usize;
            (lo, (lo + 1).min(m - 1), (pos - lo as f64) as f32)
        })
        .collect()
}

/// Bilinear resize to `ow` × `oh`.
pub(crate) fn bilinear(src: &Plane, ow: usize, oh: usize) -> Plane {
    let (xs, ys) = (lerp_taps(ow, src.w), lerp_taps(oh, src.h));
    let mut d = Vec::with_capacity(ow * oh);
    for &(y0, y1, wy) in &ys {
        let (a, b) = (src.row(y0), src.row(y1));
        d.extend(xs.iter().map(|&(x0, x1, wx)| {
            let top = a[x0] + (a[x1] - a[x0]) * wx;
            let bottom = b[x0] + (b[x1] - b[x0]) * wx;
            top + (bottom - top) * wy
        }));
    }
    Plane { w: ow, h: oh, d }
}

/// Index `i` reflected into `0..n` without repeating the edge sample.
fn reflect(i: isize, n: usize) -> usize {
    if n == 1 {
        return 0;
    }
    let period = 2 * (n as isize - 1);
    let m = i.rem_euclid(period);
    (if m >= n as isize { period - m } else { m }) as usize
}

/// Convolve rows then columns with the symmetric `kernel` (centre at `kernel.len() / 2`).
fn separable(src: &Plane, kernel: &[f32]) -> Plane {
    let r = kernel.len() / 2;
    let (w, h) = (src.w, src.h);
    let mut tmp = vec![0f32; w * h];
    let mut padded = vec![0f32; w + 2 * r];
    for y in 0..h {
        let row = src.row(y);
        padded[r..r + w].copy_from_slice(row);
        for i in 0..r {
            padded[i] = row[reflect(i as isize - r as isize, w)];
            padded[r + w + i] = row[reflect((w + i) as isize, w)];
        }
        for (x, o) in tmp[y * w..(y + 1) * w].iter_mut().enumerate() {
            *o = kernel.iter().zip(&padded[x..x + kernel.len()]).map(|(&k, &v)| k * v).sum();
        }
    }
    let mut d = vec![0f32; w * h];
    for (k, &kv) in kernel.iter().enumerate() {
        for y in 0..h {
            let from = reflect(y as isize + k as isize - r as isize, h) * w;
            for (o, &v) in d[y * w..(y + 1) * w].iter_mut().zip(&tmp[from..from + w]) {
                *o += kv * v;
            }
        }
    }
    Plane { w, h, d }
}

/// Gaussian blur with standard deviation `sigma` (exact kernel out to 4 sigma, mirrored edges).
pub(crate) fn gauss(src: &Plane, sigma: f32) -> Plane {
    let r = (4.0 * sigma).ceil().max(1.0) as isize;
    let mut kernel: Vec<f32> = (-r..=r).map(|i| (-((i * i) as f32) / (2.0 * sigma * sigma)).exp()).collect();
    let sum: f32 = kernel.iter().sum();
    kernel.iter_mut().for_each(|v| *v /= sum);
    separable(src, &kernel)
}

/// One box-filter pass along rows and columns with half-width `half`; outside counts as zero.
fn box_pass(src: &mut Plane, half: usize, scratch: &mut Vec<f64>) {
    let (w, h) = (src.w, src.h);
    let norm = 1.0 / (2 * half + 1) as f64;
    for y in 0..h {
        let line = &mut src.d[y * w..(y + 1) * w];
        scratch.clear();
        scratch.push(0.0);
        let mut total = 0f64;
        for &v in line.iter() {
            total += f64::from(v);
            scratch.push(total);
        }
        for (i, v) in line.iter_mut().enumerate() {
            *v = ((scratch[(i + half + 1).min(w)] - scratch[i.saturating_sub(half)]) * norm) as f32;
        }
    }
    // columns: one running sum per column, rows entering below and leaving above
    let rows = src.d.clone();
    scratch.clear();
    scratch.resize(w, 0.0);
    let add = |sum: &mut [f64], y: usize, sign: f64| {
        for (s, &v) in sum.iter_mut().zip(&rows[y * w..(y + 1) * w]) {
            *s += sign * f64::from(v);
        }
    };
    for y in 0..half.min(h) {
        add(scratch, y, 1.0);
    }
    for y in 0..h {
        if y + half < h {
            add(scratch, y + half, 1.0);
        }
        if y > half {
            add(scratch, y - half - 1, -1.0);
        }
        for (o, &s) in src.d[y * w..(y + 1) * w].iter_mut().zip(scratch.iter()) {
            *o = (s * norm) as f32;
        }
    }
}

/// A wide Gaussian-like blur (three box passes), linear in pixels whatever the width. Outside the
/// image counts as zero, which is what a weighted average over a mask wants.
pub(crate) fn wide_blur(src: &Plane, sigma: f32) -> Plane {
    let ideal = (12.0 * sigma * sigma / 3.0 + 1.0).sqrt();
    let mut wl = ideal.floor() as i64;
    if wl % 2 == 0 {
        wl -= 1;
    }
    let wl = wl.max(1);
    let m = ((12.0 * sigma * sigma - (3 * wl * wl + 12 * wl + 9) as f32) / (-4 * wl - 4) as f32).round() as i64;
    let mut out = src.clone();
    let mut scratch = Vec::new();
    for pass in 0..3 {
        let width = if pass < m { wl } else { wl + 2 };
        box_pass(&mut out, (width / 2) as usize, &mut scratch);
    }
    out
}

/// The mean over a (2·half+1)² window, counting only pixels inside the image.
pub(crate) fn box_mean(src: &Plane, half: usize) -> Plane {
    let mut sum = src.clone();
    let mut count = Plane::new(src.w, src.h, 1.0);
    let mut scratch = Vec::new();
    box_pass(&mut sum, half, &mut scratch);
    box_pass(&mut count, half, &mut scratch);
    sum.zip(&count, |s, c| s / c.max(1e-6))
}

/// 3×3 median of the values cut to 8 bits (edges repeated). The nine neighbours of a whole row
/// are sorted at once, exchange by exchange, which the compiler turns into byte-wide vector code.
pub(crate) fn median3(src: &Plane) -> Plane {
    const ORDER: [(usize, usize); 19] = [
        (1, 2),
        (4, 5),
        (7, 8),
        (0, 1),
        (3, 4),
        (6, 7),
        (1, 2),
        (4, 5),
        (7, 8),
        (0, 3),
        (5, 8),
        (4, 7),
        (3, 6),
        (1, 4),
        (2, 5),
        (4, 7),
        (4, 2),
        (6, 4),
        (4, 2),
    ];
    let (w, h) = (src.w, src.h);
    let q: Vec<u8> = src.d.iter().map(|&v| (v * 255.0).clamp(0.0, 255.0) as u8).collect();
    let mut d = vec![0f32; w * h];
    let mut n = vec![vec![0u8; w]; 9];
    for y in 0..h {
        for (j, yy) in [y.saturating_sub(1), y, (y + 1).min(h - 1)].into_iter().enumerate() {
            let row = &q[yy * w..(yy + 1) * w];
            n[j * 3 + 1].copy_from_slice(row);
            n[j * 3][1..].copy_from_slice(&row[..w - 1]);
            n[j * 3][0] = row[0];
            n[j * 3 + 2][..w - 1].copy_from_slice(&row[1..]);
            n[j * 3 + 2][w - 1] = row[w - 1];
        }
        for (a, b) in ORDER {
            // after the exchange, column `a` holds the smaller value and column `b` the larger
            let (lo, hi) = if a < b { (a, b) } else { (b, a) };
            let (head, tail) = n.split_at_mut(hi);
            let (first, second) = (&mut head[lo], &mut tail[0]);
            let (small, large) = if a < b { (first, second) } else { (second, first) };
            for (s, l) in small.iter_mut().zip(large.iter_mut()) {
                (*s, *l) = ((*s).min(*l), (*s).max(*l));
            }
        }
        for (o, &v) in d[y * w..(y + 1) * w].iter_mut().zip(&n[4]) {
            *o = f32::from(v) / 255.0;
        }
    }
    Plane { w, h, d }
}

/// Gradient magnitude (Sobel, divided by 8 so a unit step per pixel reads 1), mirrored edges.
pub(crate) fn gradient(src: &Plane) -> Plane {
    let (w, h) = (src.w, src.h);
    let pw = w + 2;
    let mut p = vec![0f32; pw * (h + 2)];
    for py in 0..h + 2 {
        let row = src.row(reflect(py as isize - 1, h));
        p[py * pw + 1..py * pw + 1 + w].copy_from_slice(row);
        p[py * pw] = row[reflect(-1, w)];
        p[py * pw + w + 1] = row[reflect(w as isize, w)];
    }
    let mut d = vec![0f32; w * h];
    for y in 0..h {
        let (a, b, c) = (&p[y * pw..(y + 1) * pw], &p[(y + 1) * pw..(y + 2) * pw], &p[(y + 2) * pw..(y + 3) * pw]);
        for (x, o) in d[y * w..(y + 1) * w].iter_mut().enumerate() {
            let gx = (a[x + 2] + 2.0 * b[x + 2] + c[x + 2]) - (a[x] + 2.0 * b[x] + c[x]);
            let gy = (c[x] + 2.0 * c[x + 1] + c[x + 2]) - (a[x] + 2.0 * a[x + 1] + a[x + 2]);
            *o = (gx * gx + gy * gy).sqrt() / 8.0;
        }
    }
    Plane { w, h, d }
}

/// The half-widths of a disk of radius `r`, one per row from `-r` to `r` (OpenCV's ellipse).
fn disk(r: usize) -> Vec<usize> {
    let rf = r as f64;
    (0..=2 * r)
        .map(|i| {
            let dy = i as f64 - rf;
            (rf * (1.0 - dy * dy / (rf * rf).max(1.0)).max(0.0).sqrt()).round() as usize
        })
        .collect()
}

/// Running maxima of every row over windows of 1, 2, 4, … pixels starting at each position
/// (Urbach and Wilkinson's chord tables). Rows are padded by `r` on both sides with −∞, so a
/// window may hang over the image's edge. Returns the tables and the padded width.
fn chord_tables(src: &Plane, r: usize) -> (Vec<Vec<f32>>, usize) {
    let (w, h, pw) = (src.w, src.h, src.w + 2 * r);
    let levels = (usize::BITS - (2 * r + 1).leading_zeros()) as usize;
    let mut base = vec![f32::NEG_INFINITY; pw * h];
    for y in 0..h {
        base[y * pw + r..y * pw + r + w].copy_from_slice(src.row(y));
    }
    let mut tables = vec![base];
    for level in 1..levels {
        let step = 1 << (level - 1);
        let prev = &tables[level - 1];
        let mut cur = prev.clone();
        for (row, from) in cur.chunks_exact_mut(pw).zip(prev.chunks_exact(pw)) {
            for (o, &v) in row.iter_mut().zip(&from[step..]) {
                *o = o.max(v);
            }
        }
        tables.push(cur);
    }
    (tables, pw)
}

/// Grey dilation by a disk of radius `r`: each pixel becomes the largest value under the disk.
/// Every row of the disk is one chord, and a chord's maximum is the larger of two overlapping
/// power-of-two windows from the tables, so the cost per pixel grows with `r`, not `r²`.
pub(crate) fn dilate_disk(src: &Plane, r: usize) -> Plane {
    if r == 0 {
        return src.clone();
    }
    let (w, h) = (src.w, src.h);
    let (tables, pw) = chord_tables(src, r);
    let mut out = Plane::new(w, h, f32::NEG_INFINITY);
    for (i, &k) in disk(r).iter().enumerate() {
        let level = (usize::BITS - 1 - (2 * k + 1).leading_zeros()) as usize;
        let table = &tables[level];
        // the chord covers padded columns x + r - k ..= x + r + k
        let (first, second) = (r - k, r + k + 1 - (1 << level));
        for y in 0..h {
            let Some(from) = (y + i).checked_sub(r).filter(|&f| f < h) else { continue };
            let chords = &table[from * pw..(from + 1) * pw];
            let (a, b) = (&chords[first..first + w], &chords[second..second + w]);
            for ((o, &u), &v) in out.d[y * w..(y + 1) * w].iter_mut().zip(a).zip(b) {
                *o = o.max(u.max(v));
            }
        }
    }
    out
}

/// Grey closing by a disk of radius `r`: dark things thinner than the disk disappear, the rest
/// keeps its shape.
pub(crate) fn close_disk(src: &Plane, r: usize) -> Plane {
    let up = dilate_disk(src, r);
    dilate_disk(&up.map(|v| -v), r).map(|v| -v)
}

/// How many set pixels each (2·half+1)² window holds, and how many pixels of it are inside.
fn window_counts(mask: &Mask, half: usize) -> (Plane, Plane) {
    let ones = Plane { w: mask.width, h: mask.height, d: mask.data.iter().map(|&v| if v { 1.0 } else { 0.0 }).collect() };
    let mut scratch = Vec::new();
    let (mut set, mut inside) = (ones, Plane::new(mask.width, mask.height, 1.0));
    box_pass(&mut set, half, &mut scratch);
    box_pass(&mut inside, half, &mut scratch);
    (set, inside)
}

/// Grow a mask by a square of half-width `half`.
pub(crate) fn grow(mask: &Mask, half: usize) -> Mask {
    let (set, _) = window_counts(mask, half);
    let tiny = 0.5 / ((2 * half + 1) * (2 * half + 1)) as f32;
    Mask { width: mask.width, height: mask.height, data: set.d.iter().map(|&v| v > tiny).collect() }
}

/// Shrink a mask by a square of half-width `half`; pixels outside the image do not shrink it.
pub(crate) fn shrink(mask: &Mask, half: usize) -> Mask {
    let (set, inside) = window_counts(mask, half);
    let tiny = 0.5 / ((2 * half + 1) * (2 * half + 1)) as f32;
    Mask { width: mask.width, height: mask.height, data: set.d.iter().zip(&inside.d).map(|(&s, &i)| s > i - tiny).collect() }
}

/// Grow a mask by a disk of radius `r`.
pub(crate) fn grow_disk(mask: &Mask, r: usize) -> Mask {
    let plane = Plane { w: mask.width, h: mask.height, d: mask.data.iter().map(|&v| if v { 1.0 } else { 0.0 }).collect() };
    Mask { width: mask.width, height: mask.height, data: dilate_disk(&plane, r).d.iter().map(|&v| v > 0.5).collect() }
}

/// One connected group of set pixels (8 neighbours): its pixel indices and its bounding box.
#[derive(Clone, Debug)]
pub(crate) struct Component {
    pub pixels: Vec<usize>,
    pub x0: usize,
    pub y0: usize,
    pub x1: usize,
    pub y1: usize,
}

/// The connected groups of `mask`, in the order their first pixel is met row by row.
pub(crate) fn components(mask: &Mask) -> Vec<Component> {
    let (w, h) = (mask.width, mask.height);
    let mut seen = vec![false; w * h];
    let mut out = Vec::new();
    let mut stack = Vec::new();
    for start in 0..w * h {
        if !mask.data[start] || seen[start] {
            continue;
        }
        let mut c = Component { pixels: Vec::new(), x0: w, y0: h, x1: 0, y1: 0 };
        seen[start] = true;
        stack.push(start);
        while let Some(i) = stack.pop() {
            let (x, y) = (i % w, i / w);
            c.pixels.push(i);
            (c.x0, c.y0, c.x1, c.y1) = (c.x0.min(x), c.y0.min(y), c.x1.max(x), c.y1.max(y));
            for yy in y.saturating_sub(1)..=(y + 1).min(h - 1) {
                for xx in x.saturating_sub(1)..=(x + 1).min(w - 1) {
                    let j = yy * w + xx;
                    if mask.data[j] && !seen[j] {
                        seen[j] = true;
                        stack.push(j);
                    }
                }
            }
        }
        out.push(c);
    }
    out
}

/// A component cut out with a margin: a small mask plus where its top-left sits in the image.
pub(crate) struct Cutout {
    pub mask: Mask,
    pub x: usize,
    pub y: usize,
}

impl Cutout {
    /// `c` inside an image of `w` × `h`, with `margin` pixels of room (less at the image's edge,
    /// where the cut-out's edge is then the image's edge).
    pub fn of(c: &Component, w: usize, h: usize, margin: usize) -> Cutout {
        let (x, y) = (c.x0.saturating_sub(margin), c.y0.saturating_sub(margin));
        let (x1, y1) = ((c.x1 + margin).min(w - 1), (c.y1 + margin).min(h - 1));
        let mut mask = Mask::new(x1 - x + 1, y1 - y + 1);
        for &i in &c.pixels {
            mask.data[(i / w - y) * mask.width + (i % w - x)] = true;
        }
        Cutout { mask, x, y }
    }

    /// The image indices of the pixels set in `m` (a mask the size of this cut-out).
    pub fn indices<'a>(&'a self, m: &'a Mask, image_w: usize) -> impl Iterator<Item = usize> + 'a {
        m.data.iter().enumerate().filter(|&(_, &v)| v).map(move |(i, _)| (self.y + i / m.width) * image_w + self.x + i % m.width)
    }
}
