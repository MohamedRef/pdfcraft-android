//! A baseline JPEG encoder with Huffman tables made for each page (#505).
//!
//! The image crate's encoder, which wrote every page before #505, uses the standard tables of the
//! JPEG specification (Annex K.3) and keeps colour at full resolution (4:4:4). This one writes the
//! same baseline JPEG that every reader opens, but:
//!
//! - its Huffman tables are made from the page's own symbols (Annex K.2, as libjpeg's
//!   `optimize_coding`): the same coefficients in fewer bytes, nothing lost;
//! - colour can be stored at half resolution across and down (4:2:0), as cameras store it;
//! - a grey page is one component.
//!
//! The quantisation tables are the standard ones scaled by quality as libjpeg (and the image
//! crate) scale them, so a quality means the same here as before. The DCT is libjpeg's floating
//! point one (`jfdctflt.c`, the AAN algorithm).
//!
//! Two passes, one DCT: the first turns each block into its Huffman symbols and extra bits and
//! counts the symbols; the tables are made from the counts; the second writes the symbols. A
//! page of print is mostly empty blocks, so the symbols take far less memory than the
//! coefficients would.

use crate::ScanError;

/// The order coefficients are written in: `ZIGZAG[i]` is the natural (row-major) index of the
/// `i`-th coefficient written.
const ZIGZAG: [usize; 64] = [
    0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5, 12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28, 35, 42, 49, 56, 57, 50, 43,
    36, 29, 22, 15, 23, 30, 37, 44, 51, 58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63,
];

/// Table K.1, natural order.
const LUMA_Q: [u16; 64] = [
    16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55, 14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62, 18, 22, 37, 56,
    68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92, 49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99,
];

/// Table K.2, natural order.
const CHROMA_Q: [u16; 64] = [
    17, 18, 24, 47, 99, 99, 99, 99, 18, 21, 26, 66, 99, 99, 99, 99, 24, 26, 56, 99, 99, 99, 99, 99, 47, 66, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99,
    99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99,
];

/// The AAN DCT's output scale per frequency (libjpeg's `aanscalefactor`).
const AAN: [f32; 8] = [1.0, 1.387_039_8, 1.306_563, 1.175_875_6, 1.0, 0.785_694_96, 0.541_196_1, 0.275_899_38];

/// How a colour page's chroma is stored.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Chroma {
    /// At full resolution, as the image crate stored it.
    Full,
    /// At half resolution across and down, as cameras store it.
    Half,
}

/// The pixels to write.
#[derive(Clone, Copy, Debug)]
pub enum Samples<'a> {
    Grey(&'a [u8]),
    Rgb(&'a [u8], Chroma),
}

/// `table` scaled for `quality` as libjpeg scales it (`jpeg_quality_scaling`).
pub fn scaled_table(table: &[u16; 64], quality: u8) -> [u16; 64] {
    let q = u32::from(quality.clamp(1, 100));
    let scale = if q < 50 { 5000 / q } else { 200 - q * 2 };
    table.map(|v| ((u32::from(v) * scale + 50) / 100).clamp(1, 255) as u16)
}

/// The page as a baseline JPEG at `quality`, `dpi` in its JFIF header.
pub fn encode(samples: Samples<'_>, width: u32, height: u32, quality: u8, dpi: u16) -> Result<Vec<u8>, ScanError> {
    let (w, h) = (usize::try_from(width).map_err(|_| ScanError::Encode)?, usize::try_from(height).map_err(|_| ScanError::Encode)?);
    if w == 0 || h == 0 || w > 65_535 || h > 65_535 {
        return Err(ScanError::Encode);
    }
    let channels = match samples {
        Samples::Grey(p) if p.len() == w * h => 1,
        Samples::Rgb(p, _) if p.len() == w * h * 3 => 3,
        _ => return Err(ScanError::Encode),
    };
    let tables = [scaled_table(&LUMA_Q, quality), scaled_table(&CHROMA_Q, quality)];
    // what each coefficient is divided by: the table and the AAN DCT's scale, as libjpeg's
    // floating-point divisors
    let divisors = tables.map(|t| {
        let mut d = [0f32; 64];
        for (k, v) in d.iter_mut().enumerate() {
            *v = 1.0 / (f32::from(t[k]) * AAN[k / 8] * AAN[k % 8] * 8.0);
        }
        d
    });

    let mut tokens = Tokens::default();
    match samples {
        Samples::Grey(p) => scan_grey(p, w, h, &divisors[0], &mut tokens),
        Samples::Rgb(p, chroma) => scan_rgb(p, w, h, chroma, &divisors, &mut tokens),
    }

    // the tables: 0 for luma, 1 for chroma; DC then AC
    let specs: Vec<HuffSpec> = (0..if channels == 1 { 2 } else { 4 }).map(|t| optimal_table(&tokens.counts[t])).collect();
    let codes: Vec<[(u16, u8); 256]> = specs.iter().map(codes_of).collect();

    let mut out = Vec::with_capacity(tokens.list.len() * 2 + 1024);
    out.extend_from_slice(&[0xff, 0xd8]);
    // JFIF APP0 with the resolution in dots per inch
    let d = dpi.max(1).to_be_bytes();
    out.extend_from_slice(&[0xff, 0xe0, 0, 16, b'J', b'F', b'I', b'F', 0, 1, 1, 1, d[0], d[1], d[0], d[1], 0, 0]);
    // DQT, in the order the coefficients are written
    for (id, table) in tables.iter().enumerate().take(if channels == 1 { 1 } else { 2 }) {
        out.extend_from_slice(&[0xff, 0xdb, 0, 67, id as u8]);
        out.extend(ZIGZAG.iter().map(|&k| table[k] as u8));
    }
    // SOF0
    let (wb, hb) = ((w as u16).to_be_bytes(), (h as u16).to_be_bytes());
    out.extend_from_slice(&[0xff, 0xc0, 0, (8 + 3 * channels) as u8, 8, hb[0], hb[1], wb[0], wb[1], channels as u8]);
    match samples {
        Samples::Grey(_) => out.extend_from_slice(&[1, 0x11, 0]),
        Samples::Rgb(_, Chroma::Full) => out.extend_from_slice(&[1, 0x11, 0, 2, 0x11, 1, 3, 0x11, 1]),
        Samples::Rgb(_, Chroma::Half) => out.extend_from_slice(&[1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1]),
    }
    // DHT: class 0 (DC) / 1 (AC), destination 0 (luma) / 1 (chroma)
    for (t, spec) in specs.iter().enumerate() {
        let class_dest = ((t % 2) << 4 | (t / 2)) as u8;
        let len = 2 + 1 + 16 + spec.values.len();
        out.extend_from_slice(&[0xff, 0xc4, (len >> 8) as u8, len as u8, class_dest]);
        out.extend_from_slice(&spec.bits);
        out.extend_from_slice(&spec.values);
    }
    // SOS
    out.extend_from_slice(&[0xff, 0xda, 0, (6 + 2 * channels) as u8, channels as u8]);
    if channels == 1 {
        out.extend_from_slice(&[1, 0x00]);
    } else {
        out.extend_from_slice(&[1, 0x00, 2, 0x11, 3, 0x11]);
    }
    out.extend_from_slice(&[0, 63, 0]);

    let mut bits = BitWriter { out, acc: 0, n: 0 };
    for &token in &tokens.list {
        let (table, symbol, size, extra) = unpack(token);
        let (code, len) = codes[table][symbol as usize];
        bits.put(u32::from(code), len);
        if size > 0 {
            bits.put(u32::from(extra), size);
        }
    }
    let mut out = bits.finish();
    out.extend_from_slice(&[0xff, 0xd9]);
    Ok(out)
}

/// The symbols of every block, in the order they are written, and how often each was used per
/// table (0 luma DC, 1 luma AC, 2 chroma DC, 3 chroma AC).
struct Tokens {
    list: Vec<u32>,
    counts: [[u32; 256]; 4],
}

impl Default for Tokens {
    fn default() -> Self {
        Tokens { list: Vec::new(), counts: [[0; 256]; 4] }
    }
}

/// A token: the table (2 bits), the Huffman symbol (8), how many extra bits follow (4) and the
/// extra bits (16).
fn pack(table: usize, symbol: u8, size: u8, extra: u16) -> u32 {
    (table as u32) << 28 | u32::from(symbol) << 20 | u32::from(size) << 16 | u32::from(extra)
}

fn unpack(t: u32) -> (usize, u8, u8, u16) {
    ((t >> 28) as usize, (t >> 20) as u8, ((t >> 16) & 0xf) as u8, t as u16)
}

/// The number of bits of `v`'s magnitude, and the bits written for it (one's complement for a
/// negative value, F.1.2.1).
fn magnitude(v: i32) -> (u8, u16) {
    let size = (32 - v.unsigned_abs().leading_zeros()) as u8;
    let bits = if v < 0 { (v - 1) as u32 & ((1u32 << size) - 1) } else { v as u32 };
    (size, bits as u16)
}

impl Tokens {
    /// One block's quantised coefficients (natural order) and the DC before it of its component.
    fn block(&mut self, q: &[i32; 64], dc_prev: &mut i32, chroma: bool) {
        let (dc_t, ac_t) = if chroma { (2, 3) } else { (0, 1) };
        let diff = q[0] - *dc_prev;
        *dc_prev = q[0];
        let (size, extra) = magnitude(diff);
        self.push(dc_t, size, size, extra);
        let mut run = 0u8;
        for &k in &ZIGZAG[1..] {
            let v = q[k];
            if v == 0 {
                run += 1;
                continue;
            }
            while run > 15 {
                self.push(ac_t, 0xf0, 0, 0);
                run -= 16;
            }
            let (size, extra) = magnitude(v);
            self.push(ac_t, run << 4 | size, size, extra);
            run = 0;
        }
        if run > 0 {
            self.push(ac_t, 0x00, 0, 0);
        }
    }

    fn push(&mut self, table: usize, symbol: u8, size: u8, extra: u16) {
        self.counts[table][symbol as usize] += 1;
        self.list.push(pack(table, symbol, size, extra));
    }
}

/// libjpeg's floating-point forward DCT (`jfdctflt.c`) of one block of level-shifted samples, in
/// place; each output is the true coefficient times `AAN[u]·AAN[v]·8`.
fn fdct(d: &mut [f32; 64]) {
    for pass in 0..2 {
        for i in 0..8 {
            let at = |k: usize| if pass == 0 { i * 8 + k } else { k * 8 + i };
            let x: [f32; 8] = std::array::from_fn(|k| d[at(k)]);
            let (t0, t7) = (x[0] + x[7], x[0] - x[7]);
            let (t1, t6) = (x[1] + x[6], x[1] - x[6]);
            let (t2, t5) = (x[2] + x[5], x[2] - x[5]);
            let (t3, t4) = (x[3] + x[4], x[3] - x[4]);
            let (t10, t13) = (t0 + t3, t0 - t3);
            let (t11, t12) = (t1 + t2, t1 - t2);
            d[at(0)] = t10 + t11;
            d[at(4)] = t10 - t11;
            let z1 = (t12 + t13) * std::f32::consts::FRAC_1_SQRT_2;
            d[at(2)] = t13 + z1;
            d[at(6)] = t13 - z1;
            let (a, b, c) = (t4 + t5, t5 + t6, t6 + t7);
            let z5 = (a - c) * 0.382_683_43;
            let z2 = 0.541_196_1 * a + z5;
            let z4 = 1.306_563 * c + z5;
            let z3 = b * std::f32::consts::FRAC_1_SQRT_2;
            let (z11, z13) = (t7 + z3, t7 - z3);
            d[at(5)] = z13 + z2;
            d[at(3)] = z13 - z2;
            d[at(1)] = z11 + z4;
            d[at(7)] = z11 - z4;
        }
    }
}

/// The block's quantised coefficients, natural order.
fn quantise(block: &mut [f32; 64], divisors: &[f32; 64]) -> [i32; 64] {
    fdct(block);
    std::array::from_fn(|k| (block[k] * divisors[k]).round() as i32)
}

/// An 8 × 8 block of `plane` (`w` × `h`, one byte per sample) at block (`bx`, `by`), the last row
/// and column repeated past the edge, level-shifted.
fn load(plane: &[u8], w: usize, h: usize, bx: usize, by: usize) -> [f32; 64] {
    std::array::from_fn(|k| {
        let (x, y) = ((bx * 8 + k % 8).min(w - 1), (by * 8 + k / 8).min(h - 1));
        f32::from(plane[y * w + x]) - 128.0
    })
}

fn scan_grey(p: &[u8], w: usize, h: usize, divisors: &[f32; 64], tokens: &mut Tokens) {
    let mut dc = 0;
    for by in 0..h.div_ceil(8) {
        for bx in 0..w.div_ceil(8) {
            let mut b = load(p, w, h, bx, by);
            tokens.block(&quantise(&mut b, divisors), &mut dc, false);
        }
    }
}

/// The page's Y, Cb and Cr planes (JFIF's conversion), full size.
fn ycbcr(p: &[u8]) -> [Vec<u8>; 3] {
    let n = p.len() / 3;
    let (mut y, mut cb, mut cr) = (Vec::with_capacity(n), Vec::with_capacity(n), Vec::with_capacity(n));
    for &px in p.as_chunks::<3>().0 {
        let (r, g, b) = (f32::from(px[0]), f32::from(px[1]), f32::from(px[2]));
        y.push((0.299 * r + 0.587 * g + 0.114 * b).round().clamp(0.0, 255.0) as u8);
        cb.push((-0.168_736 * r - 0.331_264 * g + 0.5 * b + 128.0).round().clamp(0.0, 255.0) as u8);
        cr.push((0.5 * r - 0.418_688 * g - 0.081_312 * b + 128.0).round().clamp(0.0, 255.0) as u8);
    }
    [y, cb, cr]
}

/// `plane` (`w` × `h`) averaged over 2 × 2, the last row and column repeated past the edge.
fn halve(plane: &[u8], w: usize, h: usize) -> (Vec<u8>, usize, usize) {
    let (hw, hh) = (w.div_ceil(2), h.div_ceil(2));
    let mut out = Vec::with_capacity(hw * hh);
    for y in 0..hh {
        let (y0, y1) = (2 * y, (2 * y + 1).min(h - 1));
        for x in 0..hw {
            let (x0, x1) = (2 * x, (2 * x + 1).min(w - 1));
            let sum = u32::from(plane[y0 * w + x0]) + u32::from(plane[y0 * w + x1]) + u32::from(plane[y1 * w + x0]) + u32::from(plane[y1 * w + x1]);
            out.push(((sum + 2) / 4) as u8);
        }
    }
    (out, hw, hh)
}

fn scan_rgb(p: &[u8], w: usize, h: usize, chroma: Chroma, divisors: &[[f32; 64]; 2], tokens: &mut Tokens) {
    let [y, cb, cr] = ycbcr(p);
    let mut dc = [0i32; 3];
    match chroma {
        Chroma::Full => {
            for by in 0..h.div_ceil(8) {
                for bx in 0..w.div_ceil(8) {
                    for (c, plane) in [&y, &cb, &cr].into_iter().enumerate() {
                        let mut b = load(plane, w, h, bx, by);
                        tokens.block(&quantise(&mut b, &divisors[usize::from(c > 0)]), &mut dc[c], c > 0);
                    }
                }
            }
        }
        Chroma::Half => {
            let (cb, hw, hh) = halve(&cb, w, h);
            let (cr, _, _) = halve(&cr, w, h);
            for my in 0..h.div_ceil(16) {
                for mx in 0..w.div_ceil(16) {
                    for (dy, dx) in [(0, 0), (0, 1), (1, 0), (1, 1)] {
                        // a block wholly past the edge repeats the edge, as any other
                        let mut b = load(&y, w, h, 2 * mx + dx, 2 * my + dy);
                        tokens.block(&quantise(&mut b, &divisors[0]), &mut dc[0], false);
                    }
                    for (c, plane) in [(1, &cb), (2, &cr)] {
                        let mut b = load(plane, hw, hh, mx, my);
                        tokens.block(&quantise(&mut b, &divisors[1]), &mut dc[c], true);
                    }
                }
            }
        }
    }
}

/// A Huffman table as a DHT segment holds it: how many codes of each length 1–16, and the
/// symbols in order of their codes.
struct HuffSpec {
    bits: [u8; 16],
    values: Vec<u8>,
}

/// The optimal table for these symbol counts, no code longer than 16 bits and no code of all
/// ones (Annex K.2; libjpeg's `jpeg_gen_optimal_table`).
fn optimal_table(counts: &[u32; 256]) -> HuffSpec {
    let mut freq = [0i64; 257];
    for (f, &c) in freq.iter_mut().zip(counts) {
        *f = i64::from(c);
    }
    // a symbol no one uses, so no real code is all ones
    freq[256] = 1;
    let mut codesize = [0usize; 257];
    let mut others = [-1i32; 257];
    loop {
        // the least frequent symbol (the highest such value on a tie), then the next least
        let mut c1 = -1i32;
        let mut v = i64::MAX;
        for (i, &f) in freq.iter().enumerate() {
            if f != 0 && f <= v {
                v = f;
                c1 = i as i32;
            }
        }
        let mut c2 = -1i32;
        v = i64::MAX;
        for (i, &f) in freq.iter().enumerate() {
            if f != 0 && f <= v && i as i32 != c1 {
                v = f;
                c2 = i as i32;
            }
        }
        if c2 < 0 {
            break;
        }
        let (mut a, mut b) = (c1 as usize, c2 as usize);
        freq[a] += freq[b];
        freq[b] = 0;
        codesize[a] += 1;
        while others[a] >= 0 {
            a = others[a] as usize;
            codesize[a] += 1;
        }
        others[a] = c2;
        codesize[b] += 1;
        while others[b] >= 0 {
            b = others[b] as usize;
            codesize[b] += 1;
        }
    }
    let mut bits = [0u32; 33];
    for &size in codesize.iter().filter(|&&s| s > 0) {
        bits[size.min(32)] += 1;
    }
    // no code longer than 16 bits (K.3's adjustment)
    for i in (17..=32).rev() {
        while bits[i] > 0 {
            let mut j = i - 2;
            while bits[j] == 0 {
                j -= 1;
            }
            bits[i] -= 2;
            bits[i - 1] += 1;
            bits[j + 1] += 2;
            bits[j] -= 1;
        }
    }
    // the unused symbol's code (the longest) goes
    let mut i = 16;
    while bits[i] == 0 {
        i -= 1;
    }
    bits[i] -= 1;
    let mut values = Vec::new();
    for len in 1..=32 {
        for (sym, &size) in codesize.iter().enumerate().take(256) {
            if size == len {
                values.push(sym as u8);
            }
        }
    }
    let mut out = [0u8; 16];
    for (o, &b) in out.iter_mut().zip(&bits[1..=16]) {
        *o = b as u8;
    }
    HuffSpec { bits: out, values }
}

/// Each symbol's code and length (Annex C).
fn codes_of(spec: &HuffSpec) -> [(u16, u8); 256] {
    let mut table = [(0u16, 0u8); 256];
    let mut code = 0u32;
    let mut k = 0;
    for (len, &n) in spec.bits.iter().enumerate() {
        for _ in 0..n {
            table[spec.values[k] as usize] = (code as u16, len as u8 + 1);
            code += 1;
            k += 1;
        }
        code <<= 1;
    }
    table
}

/// Entropy-coded bits, a zero byte after every 0xFF, padded with ones.
struct BitWriter {
    out: Vec<u8>,
    acc: u64,
    n: u8,
}

impl BitWriter {
    fn put(&mut self, bits: u32, len: u8) {
        self.acc = self.acc << len | u64::from(bits) & ((1u64 << len) - 1);
        self.n += len;
        while self.n >= 8 {
            self.n -= 8;
            let b = (self.acc >> self.n) as u8;
            self.out.push(b);
            if b == 0xff {
                self.out.push(0);
            }
        }
        self.acc &= (1u64 << self.n) - 1;
    }

    fn finish(mut self) -> Vec<u8> {
        if self.n > 0 {
            let pad = 8 - self.n;
            self.put((1 << pad) - 1, pad);
        }
        self.out
    }
}
