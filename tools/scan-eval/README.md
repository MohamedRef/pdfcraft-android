# Scan evaluation tools

Developer-machine tools for the document scanner (`apps/pdfcraft-android/scan`, tracking issue #286).
Nothing here is built into the app or run by CI.

## Reference pairs (`reference_pairs.py`)

A reference pair is a photo and what another scanner made of the same photo. Lining the two up pixel
by pixel turns "as good as that scanner" into numbers: where it cropped, whether it flattened the
page, and how far our look is from its look. The Auto look (#298) was designed and is checked this
way (#288, section 8).

**The pairs are somebody's documents. Keep them, and every file made from them, outside the
repository and out of pull requests.**

Needs Python 3 with `numpy` and `opencv-python`.

```sh
# 1. a folder of <name>.before.jpg (the photo) and <name>.after.jpg (the other scanner's result,
#    not rotated or edited afterwards)
python3 tools/scan-eval/reference_pairs.py register ~/scan-pairs
#    -> <name>.ref.json     the reference's crop (corners as shares of the photo), its size, and
#                           nonprojective_p95_pct: under 0.3 the other scanner only straightened
#                           the page, over 1 it also flattened it; alignment below 0.85 = unreliable
#    -> <name>.aligned.png  the photo resampled onto the reference's pixel grid

# 2. our look on the aligned photos (so only the look differs, not the geometry)
cargo run --release -p pdfcraft-scan --example scan_reference -- look ~/scan-pairs ~/scan-pairs/ours

# 3. the comparison
python3 tools/scan-eval/reference_pairs.py compare ~/scan-pairs ~/scan-pairs/ours
```

`compare` prints, per pair:

| Value | Meaning |
|---|---|
| `ref_mae` | mean absolute luminance difference from the reference (0–1) |
| `ref_mae_ink`, `_paper`, `_mid` | the same over the reference's ink (below 0.5), paper (0.9 and above) and the rest |
| `ref_lost` | share of the reference's ink that we render lighter than 0.8 |
| `ref_residue` | share of the reference's paper that we render darker than 0.6 |
| `white`, `ref_white` | share of the page at luminance 0.98 or more, ours and the reference's |
| `stroke_ratio` | our stroke width divided by the reference's |
| `edge_ratio` | our edge transition width divided by the reference's (below 1 = crisper) |

`scan_reference -- page <dir> <out>` runs the whole pipeline (decode, straighten to the reference's
corners, Auto, encode) on each `before.jpg`, which is what the app produces once the corners are
right. It does not flatten curved pages yet (#302).

## The Auto look's prototype (`auto_reference.py`)

The numpy and OpenCV prototype the Auto look was first measured with. The Rust implementation in
`apps/pdfcraft-android/scan/src/enhance` follows it step by step and gives the same numbers on the
owner's ten pairs (mean `ref_mae` 2.9 % for both). Keep it as the readable description of the steps
and for trying a change quickly:

```python
import cv2, numpy as np, auto_reference as R
page = cv2.imread("page.png").astype(np.float32) / 255        # BGR, 0..1
out, tone, info = R.auto(page, strength=60)
cv2.imwrite("out.png", (out * 255).astype(np.uint8))
```

Known differences from the Rust code, none of which moved the numbers: the working copy never
enlarges a small page; the two wide averaging scales run on a quarter-size copy; wide blurs are
three box passes instead of an exact Gaussian; the gain (1 / light) is interpolated instead of the
light.
