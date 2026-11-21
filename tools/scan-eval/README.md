# Scan evaluation tools

Developer-machine tools for the document scanner (`apps/pdfcraft-android/scan`, tracking issue #286).
Nothing here is built into the app. CI runs only the tests (`test_*.py`) of these tools.

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

## SmartDoc 2015 as a test set (`scan_dataset_smartdoc.py`)

Turns a local copy of the public SmartDoc 2015 Challenge 1 videos (CC BY 4.0) into `scan-eval`'s
test-set format: every 30th frame as `<background>-<document>-<frame>.jpg` with its `.json`
(corners, `kind`, `paper: a4`). It downloads nothing; `--list` shows what it would write without
OpenCV. See [ANDROID_SCAN_MEASURING.md](../../docs/ANDROID_SCAN_MEASURING.md) for the commands and
what has not been verified. Tests: `python3 -m unittest discover -s tools/scan-eval -p 'test_*.py'`.

## Aggregates of kept scans (`kept_scan_summary.py`)

Reads one or more unzipped kept-scan exports (the `page-NN.json` files and `diagnostics.txt`) and
prints aggregates only: pages, found and not found, untouched and no-touch shares with their
counts, the spread of corner moves, turns, flattening decisions, looks, output sizes, and for every
time field of every `[scan]` log event the sample size, median, 90th and 95th percentile and the
largest value. No file name and no corner is printed, so the output can be quoted in an issue; the
exports themselves hold someone's documents and stay outside the repository.

```sh
python3 -I tools/scan-eval/kept_scan_summary.py ~/kept/run-1 [~/kept/run-2 ...] \
    [--since last-install | --since "MM-DD HH:MM"] [--until "MM-DD HH:MM"] [--wrong-accepted N] [--json out.json]
```

- Needs only the standard library. Of a photo it reads the file header, for the pixel size a corner
  move is measured against; with no photo it uses `capture`'s size, and failing that the unit
  square (counted as `size_unknown`).
- Every key is optional: exports from older builds give fewer rows, never an error.
- A log usually runs over several builds. `--since last-install` counts from the last
  `[update] install` line; the report says when an install lies inside the window.
- `--wrong-accepted N` takes N pages a person judged wrong off the untouched count. The tool cannot
  see a wrong crop that nobody corrected.
- The 90th percentile is given from 10 values and the 95th from 20.

The definitions (found, untouched, no touch, miss, wrong accepted page) and the runs it is meant
for are in [ANDROID_SCAN_BASELINE.md](../../docs/ANDROID_SCAN_BASELINE.md). Its tests read made-up
exports in `testdata/kept-scans/`, written by `make_kept_scan_fixtures.py` (every number invented
there; a test fails when the files and the generator differ).
