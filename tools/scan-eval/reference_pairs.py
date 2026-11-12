"""Private reference pairs: <name>.before.jpg (the photo) and <name>.after.jpg (the same photo as another scanner
rendered it). Developer machine only; the pairs never enter the repository.

  reference_pairs.py register <dir>            -> <name>.ref.json (crop, size, how far the output is from a homography)
                                                  <name>.aligned.png (the photo resampled onto the reference's pixel grid)
  reference_pairs.py compare  <dir> <ours_dir> -> table: our <name>.png (same size as after.jpg) against the reference
"""
import cv2, numpy as np, json, sys, glob, os

def lum(x): return 0.299 * x[..., 2] + 0.587 * x[..., 1] + 0.114 * x[..., 0]
def basis(x, y, deg=3): return np.stack([x**i * y**j for i in range(deg + 1) for j in range(deg + 1 - i)], -1)

def matches(o, m):
    go = cv2.createCLAHE(3.0, (8, 8)).apply(cv2.cvtColor(o, cv2.COLOR_BGR2GRAY)); gm = cv2.cvtColor(m, cv2.COLOR_BGR2GRAY)
    s = min(1.0, 1600 / max(gm.shape)); so = max(1.0, 1600 / max(go.shape))
    sift = cv2.SIFT_create(nfeatures=12000, contrastThreshold=0.02)
    ko, do = sift.detectAndCompute(cv2.resize(go, None, fx=so, fy=so, interpolation=cv2.INTER_CUBIC), None)
    km, dm = sift.detectAndCompute(cv2.resize(gm, None, fx=s, fy=s, interpolation=cv2.INTER_AREA), None)
    good = [a for a, b in cv2.BFMatcher().knnMatch(dm, do, k=2) if a.distance < 0.75 * b.distance]
    pm = np.float32([km[g.queryIdx].pt for g in good]) / s; po = np.float32([ko[g.trainIdx].pt for g in good]) / so
    H, _ = cv2.findHomography(pm, po, cv2.RANSAC, 2.0)                      # reference px -> photo px
    _, loose = cv2.findHomography(pm, po, cv2.RANSAC, 12.0); keep = loose.ravel().astype(bool)
    return H, pm[keep], po[keep]

def fit(o, m):
    """Homography plus a smooth cubic residual (what a page-flattening step adds on top of a perspective crop)."""
    H, pm, po = matches(o, m); h, w = m.shape[:2]; coef = np.zeros((10, 2))
    Bm = basis(pm[:, 0] / w * 2 - 1, pm[:, 1] / h * 2 - 1)
    for it in range(3):
        r = po - cv2.perspectiveTransform(pm[None], H)[0]
        if it:
            keep = np.linalg.norm(r - Bm @ coef, axis=1) < 3
            H, _ = cv2.findHomography(pm[keep], (po - Bm @ coef)[keep], 0); r = po - cv2.perspectiveTransform(pm[None], H)[0]
        wgt = (np.linalg.norm(r - Bm @ coef, axis=1) < 6).astype(float) if it else (np.linalg.norm(r, axis=1) < 40).astype(float)
        for _ in range(12):
            coef = np.linalg.lstsq(Bm * wgt[:, None], r * wgt[:, None], rcond=None)[0]
            e = np.linalg.norm(r - Bm @ coef, axis=1); wgt = np.where(e < 3, 1.0, np.where(e < 40, 3 / np.maximum(e, 1e-6), 0)) * (np.linalg.norm(r, axis=1) < 40)
    r = po - cv2.perspectiveTransform(pm[None], H)[0]
    gx, gy = np.meshgrid(np.linspace(-1, 1, 41), np.linspace(-1, 1, 41)); mag = np.linalg.norm(basis(gx.ravel(), gy.ravel()) @ coef, axis=1)
    quad = cv2.perspectiveTransform(np.float32([[0, 0], [w, 0], [w, h], [0, h]])[None], H)[0]
    diag = 0.5 * (np.linalg.norm(quad[0] - quad[2]) + np.linalg.norm(quad[1] - quad[3]))
    oh, ow = o.shape[:2]
    info = dict(photo=[ow, oh], reference=[w, h], megapixels=round(w * h / 1e6, 2),
                corners=[round(float(v), 4) for p in quad for v in (p[0] / ow, p[1] / oh)],      # shares of the photo: TL TR BR BL
                upscale=round(float(np.sqrt(w * h / max(cv2.contourArea(quad), 1))), 2),
                matches=len(pm), within_2px_homography=int((np.linalg.norm(r, axis=1) < 2).sum()),
                within_2px_smooth=int((np.linalg.norm(r - Bm @ coef, axis=1) < 2).sum()),
                nonprojective_p95_pct=round(float(np.percentile(mag, 95) / diag * 100), 2), H=H.tolist(), coef=coef.tolist())
    return H, coef, info

def inkmap(y):
    """Soft "how much ink is here", comparable between a photo and a cleaned scan."""
    k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (31, 31)); bg = cv2.GaussianBlur(cv2.morphologyEx(y, cv2.MORPH_CLOSE, k), (0, 0), 8)
    return (np.clip((1 - y / np.maximum(bg, 0.05)) * 3, 0, 1) * 255).astype(np.uint8)

def lapcorr(a, b):
    g = lambda im: cv2.Laplacian(cv2.GaussianBlur(cv2.cvtColor(cv2.resize(im, None, fx=.25, fy=.25, interpolation=cv2.INTER_AREA), cv2.COLOR_BGR2GRAY).astype(np.float32), (0, 0), 1.5), cv2.CV_32F)
    return float(np.corrcoef(g(a).ravel(), g(b).ravel())[0, 1])

def aligned(o, m, H, coef):
    """The photo on the reference's pixel grid: homography + cubic residual, then a dense correction where both have ink."""
    h, w = m.shape[:2]; xs, ys = np.meshgrid(np.arange(w, dtype=np.float32), np.arange(h, dtype=np.float32))
    pts = np.stack([xs.ravel(), ys.ravel()], -1)
    p = cv2.perspectiveTransform(pts[None], H)[0] + basis(pts[:, 0] / w * 2 - 1, pts[:, 1] / h * 2 - 1) @ coef
    mx, my = p[:, 0].reshape(h, w).astype(np.float32), p[:, 1].reshape(h, w).astype(np.float32)
    al = cv2.remap(o, mx, my, cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)
    a2 = cv2.resize(inkmap(lum(al.astype(np.float32) / 255)), None, fx=.5, fy=.5, interpolation=cv2.INTER_AREA)
    b2 = cv2.resize(inkmap(lum(m.astype(np.float32) / 255)), None, fx=.5, fy=.5, interpolation=cv2.INTER_AREA)
    dis = cv2.DISOpticalFlow_create(cv2.DISOPTICAL_FLOW_PRESET_MEDIUM); dis.setFinestScale(1); fl = dis.calc(b2, a2, None)
    wgt = cv2.GaussianBlur(((a2 > 60) & (b2 > 60)).astype(np.float32), (0, 0), 3) * (np.linalg.norm(fl, axis=-1) < 25)
    den = cv2.GaussianBlur(wgt, (0, 0), 25); num = cv2.GaussianBlur(fl * wgt[..., None], (0, 0), 25)
    fl = cv2.resize(np.where(den[..., None] > 0.01, num / np.maximum(den[..., None], 1e-4), 0).astype(np.float32), (w, h)) * 2
    al2 = cv2.remap(o, cv2.remap(mx, xs + fl[..., 0], ys + fl[..., 1], cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE),
                    cv2.remap(my, xs + fl[..., 0], ys + fl[..., 1], cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE), cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)
    return al2 if lapcorr(al2, m) > lapcorr(al, m) + 0.005 else al       # keep the correction only if it lines up better

def edge_width(y):
    ink = (y < 0.5).astype(np.uint8); per = (cv2.morphologyEx(ink, cv2.MORPH_GRADIENT, np.ones((3, 3), np.uint8)) > 0).sum() / 2
    return float(((y > 0.1) & (y < 0.9)).sum() / max(per, 1)), float(2 * ink.sum() / max(per, 1))    # transition width, stroke width (px)

def compare(ours, ref):
    T = lum(ours.astype(np.float32) / 255); Y = lum(ref.astype(np.float32) / 255); e = np.abs(T - Y)
    ink, pap = Y < 0.5, Y >= 0.9; mid = ~ink & ~pap; (ew, sw), (rew, rsw) = edge_width(T), edge_width(Y)
    return dict(ref_mae=e.mean(), ref_mae_ink=e[ink].mean(), ref_mae_paper=e[pap].mean(), ref_mae_mid=e[mid].mean(),
                ref_lost=(ink & (T > 0.8)).sum() / max(ink.sum(), 1), ref_residue=(pap & (T < 0.6)).sum() / max(pap.sum(), 1),
                white=(T >= 0.98).mean(), ref_white=(Y >= 0.98).mean(), stroke_ratio=sw / rsw, edge_ratio=ew / rew)

if __name__ == "__main__":
    mode, d = sys.argv[1], sys.argv[2]
    for before in sorted(glob.glob(os.path.join(d, "*.before.jpg"))):
        name = before[:-len(".before.jpg")]; o = cv2.imread(before); m = cv2.imread(name + ".after.jpg")
        if mode == "register":
            H, coef, info = fit(o, m); al = aligned(o, m, H, coef); info["alignment"] = round(lapcorr(al, m), 3)
            json.dump(info, open(name + ".ref.json", "w")); cv2.imwrite(name + ".aligned.png", al)
            print(os.path.basename(name), {k: v for k, v in info.items() if k not in ("H", "coef")})
        else:
            ours = cv2.imread(os.path.join(sys.argv[3], os.path.basename(name) + ".png"))
            print(os.path.basename(name), {k: round(float(v), 4) for k, v in compare(ours, m).items()})
