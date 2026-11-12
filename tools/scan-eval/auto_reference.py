"""Reference prototype of the Auto look (numpy + OpenCV). An executable description of the steps, not code to ship.
Input and output are float32 BGR images in 0..1 (OpenCV channel order)."""
import cv2, numpy as np

WORK = 1000                      # long side of the working copy, px
def lum(x): return 0.299 * x[..., 2] + 0.587 * x[..., 1] + 0.114 * x[..., 0]
def chroma(x): return x.max(-1) - x.min(-1)
def sstep(x, a, b):
    t = np.clip((x - a) / (b - a), 0, 1); return t * t * (3 - 2 * t)
def normconv(img, mask, sigma):
    """Gaussian average of `img` over the pixels where `mask` is true; also returns the local share of such pixels."""
    w = mask.astype(np.float32); den = cv2.GaussianBlur(w, (0, 0), sigma)
    num = cv2.GaussianBlur(img * w[..., None], (0, 0), sigma)
    return num / np.maximum(den[..., None], 1e-4), den

def stroke_width(Y):
    """75th percentile of the horizontal run lengths of dark pixels, every 6th row."""
    m = (Y < cv2.blur(Y, (31, 31)) * 0.8).astype(np.int8)[::6]
    d = np.diff(np.pad(m, ((0, 0), (1, 1))), axis=1)
    runs = np.concatenate([np.nonzero(r == -1)[0] - np.nonzero(r == 1)[0] for r in d]) if len(d) else np.array([3])
    runs = runs[(runs > 0) & (runs < 60)]
    return float(np.percentile(runs, 75)) if len(runs) > 50 else 3.0

def closing(small, r):
    k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * r + 1, 2 * r + 1))
    pre = cv2.medianBlur((small * 255).astype(np.uint8), 3).astype(np.float32) / 255
    return cv2.GaussianBlur(cv2.morphologyEx(pre, cv2.MORPH_CLOSE, k), (0, 0), 1.5)

def dark_objects(small, Y, b0, bl, depth):
    """Printed dark areas wider than the closing (logos, filled bars, photos) and fingers: not shadows."""
    sh, sw = Y.shape; Yb = lum(b0)
    n, lab, stats, _ = cv2.connectedComponentsWithStats((depth < 0.8).astype(np.uint8), connectivity=8)
    # edges are measured on the closed copy: print is gone from it, so only the area's own edge counts
    g = np.hypot(cv2.Sobel(Yb, cv2.CV_32F, 1, 0, ksize=3) / 8, cv2.Sobel(Yb, cv2.CV_32F, 0, 1, ksize=3) / 8)
    obj = np.zeros((sh, sw), bool); k5 = np.ones((5, 5), np.uint8)
    for i in range(1, n):
        a = stats[i, cv2.CC_STAT_AREA]
        if a < 12 or a > 0.30 * sh * sw: continue
        x, y, w_, h_ = stats[i, :4]; touches = x == 0 or y == 0 or x + w_ >= sw or y + h_ >= sh
        m = lab == i; mu = m.astype(np.uint8)
        ring = (cv2.dilate(mu, k5) > 0) & ~(cv2.erode(mu, k5) > 0)
        d50 = float(np.median(depth[m])); drop = float(np.median(bl[m]) - np.median(Yb[m]))
        sharp = float(np.percentile(g[ring], 75) / max(drop, 0.02))          # 1/px: steepness of the boundary
        cin = np.median(small[m], axis=0); out = ring & ~m; cout = np.median(small[out], axis=0) if out.any() else cin
        cd = float(np.abs(cin / max(cin.max(), 1e-3) - cout / max(cout.max(), 1e-3)).max())   # colour step
        keep = (d50 < 0.5) or (sharp >= 0.12 and (cd > 0.2 or not touches))
        if keep: obj |= m
    return obj

def warm_objects(small, b0):
    """Skin, wood, cardboard: warmer and more saturated than the lit paper, with a clear colour step at the boundary."""
    rb = np.log((b0[..., 2] + 1e-3) / (b0[..., 0] + 1e-3)); sat = chroma(b0) / np.maximum(b0.max(-1), 1e-3)
    Yb = lum(b0); lit = Yb >= np.percentile(Yb, 85)
    warm = ((rb > np.log(1.22) + np.median(rb[lit])) & (sat > 0.10 + np.median(sat[lit]))).astype(np.uint8)
    warm = cv2.morphologyEx(warm, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
    n, lab, stats, _ = cv2.connectedComponentsWithStats(warm, connectivity=8); out = np.zeros(warm.shape, bool)
    sm = cv2.GaussianBlur(small, (0, 0), 1.0); rbs = np.log((sm[..., 2] + 1e-3) / (sm[..., 0] + 1e-3))
    g = np.hypot(cv2.Sobel(rbs, cv2.CV_32F, 1, 0, ksize=3) / 8, cv2.Sobel(rbs, cv2.CV_32F, 0, 1, ksize=3) / 8)
    k7 = np.ones((7, 7), np.uint8); k15 = np.ones((15, 15), np.uint8)
    for i in range(1, n):
        if stats[i, cv2.CC_STAT_AREA] < 0.001 * warm.size: continue
        m = lab == i; mu = m.astype(np.uint8); d7 = cv2.dilate(mu, k7) > 0
        ring = d7 & ~(cv2.erode(mu, k7) > 0); outside = (cv2.dilate(mu, k15) > 0) & ~d7
        if not outside.any(): continue
        jump = float(np.median(rbs[m]) - np.median(rbs[outside]))
        sharp = float(np.percentile(g[ring], 75) / max(abs(jump), 0.03))
        if jump >= 0.25 and sharp >= 0.08: out |= m
    return out

def background(O):
    """Paper colour under the local light, per channel, at full size. Also the object mask and a few numbers."""
    H, W = O.shape[:2]; s = WORK / max(H, W); sw, sh = max(8, round(W * s)), max(8, round(H * s))
    small = cv2.GaussianBlur(cv2.resize(O, (sw, sh), interpolation=cv2.INTER_AREA), (0, 0), 0.6); Y = lum(small)
    r = int(np.clip(round(2.5 * stroke_width(Y)), 4, 0.024 * WORK))
    b0 = closing(small, r)                                                  # pass 1: anything dark and thin is gone
    R = int(0.12 * WORK) // 4; q = cv2.resize(lum(b0), (sw // 4, sh // 4), interpolation=cv2.INTER_AREA)
    kL = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * R + 1, 2 * R + 1))
    bl = cv2.resize(cv2.GaussianBlur(cv2.morphologyEx(q, cv2.MORPH_CLOSE, kL), (0, 0), 3), (sw, sh))
    depth = lum(b0) / np.maximum(bl, 0.03)
    obj = dark_objects(small, Y, b0, bl, depth) | warm_objects(small, b0)
    kd = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * r + 5, 2 * r + 5)); objd = cv2.dilate(obj.astype(np.uint8), kd) > 0
    ink = cv2.dilate((Y / np.maximum(lum(b0), 0.02) < 0.88).astype(np.uint8), np.ones((3, 3), np.uint8)) > 0
    paper = ~ink & ~objd; sig = max(2.0, 0.5 * r)                           # pass 2: average the paper pixels only
    B1, d1 = normconv(small, paper, sig); B2, d2 = normconv(small, paper, 4 * sig); B3, d3 = normconv(small, paper, 16 * sig)
    glob = np.median(small[paper], axis=0) if paper.any() else np.ones(3, np.float32)
    B = np.where(d1[..., None] > 0.15, B1, np.where(d2[..., None] > 0.02, B2, np.where(d3[..., None] > 0.003, B3, glob)))
    B = np.where(objd[..., None], B, np.minimum(B, b0))                     # keeps hard shadow edges in place
    return (cv2.resize(B.astype(np.float32), (W, H), interpolation=cv2.INTER_LINEAR),
            cv2.resize(obj.astype(np.uint8), (W, H), interpolation=cv2.INTER_NEAREST) > 0, dict(r=r, objects=float(obj.mean())))

def black_colour(n):
    """Colour of the darkest 2 % of the page, with its distance from grey limited to 0.08 per channel."""
    Y = lum(n); D = np.median(n[Y <= np.percentile(Y, 2.0)], axis=0); l = float(lum(D[None, None])[0, 0])
    return l, np.clip(D - l, -0.08, 0.08).astype(np.float32)

def params(strength=60):
    k = strength / 100
    return dict(bp=0.42 * k, wp=1 - 0.05 * k, gamma=1 + 0.67 * k, sat=1 + 0.33 * k)

def auto(O, strength=60, B=None):
    p = params(strength); info = {}
    if B is None: B, obj, info = background(O)
    n = np.clip(O / np.maximum(B, 0.02), 0, 1.3)                            # even light, paper = 1 in every channel
    l, off = black_colour(n); n = np.clip(n - off * np.clip((1 - lum(n)) / (1 - l + 1e-3), 0, 1)[..., None], 0, 1.3)
    Y = lum(n); T = np.clip((Y - p["bp"]) / (p["wp"] - p["bp"]), 0, 1) ** p["gamma"]
    out = T[..., None] + p["sat"] * sstep(chroma(n), 0.04, 0.12)[..., None] * (n - Y[..., None])
    return np.clip(out, 0, 1), T, info
