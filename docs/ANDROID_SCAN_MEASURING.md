# Scan: measuring on drawn photos (#288)

How the scanner is measured on a desktop and in CI, without anyone's photos. The owner's own
evidence comes from kept scans on his phone ([ANDROID_SCAN_TESTING.md](ANDROID_SCAN_TESTING.md));
this page is about the photos drawn in code, which anyone can make again from a seed.

**Nothing here has run on a phone, and a drawn photo is not a real one.** Drawn photos catch a
change that makes the scanner worse on pages whose answers are known exactly; they say nothing
about how often the scanner is right on real documents in real light.

## The drawn photos (`synth.rs`)

`apps/pdfcraft-android/scan/src/synth.rs`, compiled for the crate's tests and behind the crate's
`synth` feature, never in the app. `generate(SceneSpec::new(seed, kind, width, height))` returns a
`Scene`:

- **the photo**, as a JPEG file (quality 85–97);
- **the page's corners** in photo pixels, to a fraction of a pixel, top-left first and clockwise;
- **the clean page** (RGB) and **a label per page pixel**: paper, ink, stamp, picture, signature,
  pencil;
- **the light** on each photo pixel (1 = full), before the colour cast;
- **the true proportions** (width over height) and **the camera's focal length** in pixels;
- **the curl** of a book page, in page pixels (0 for a flat page).

How it is drawn:

- **Page**: white or tinted paper; rows of word bars; a ruled table; a blue stamp and a red
  signature; sometimes a picture region and faint pencil writing. A4 or Letter, or a receipt
  2.4–3.4 times as long as it is wide.
- **Camera**: a real pinhole camera (focal length 0.75–1.15 of the long side) looking at the page
  tilted up to 45° (desk and light scenes; 30° otherwise) and turned up to ±20°, covering 30–85 %
  of the frame and wholly inside it. Each photo pixel is the mean of 2 × 2 samples.
- **Scene**: a dark desk, or a light surface that is plain, striped like wood, checked like cloth
  or noisy.
- **Light**: a linear or radial fall-off; a soft-edged shadow in shadow scenes (and sometimes in
  others); a colour cast; dim light in low-light scenes.
- **Camera faults**: Gaussian blur (σ up to 1.4 px, 0.8–2.5 in low light), sensor noise (σ up to
  5, 6–12 in low light), the JPEG round trip.
- **Kinds**: desk, light, receipt, book (a bowed page), shadow, low light: the names of the test
  set's `kind` field (#288, section 1).
- **Plain**: `SceneSpec { plain: true, .. }` gives the same page in the same place with even light,
  no cast, no blur and no noise, so a test can tell a fault of the geometry from one of the light.

The same seed gives the same bytes (a seeded xorshift; no `rand`).

### What its tests check (`tests_synth.rs`)

| Check | Measured when it was written |
|---|---|
| The same seed gives the same photo, corners and labels; another seed another photo | all six kinds |
| The page lies inside the photo, in reading order, at the share of the frame asked | 12 seeds × 6 kinds |
| The scene's corners and focal length give back the paper's proportions (`aspect::proportions`) | within 1 % on 48 scenes (desk, light, receipt, shadow) |
| A plain scene straightened with its true corners is the clean page (`metrics_compare::psnr`) | 21.4–24.6 dB with the true corners, 7.1–10.3 dB with corners 1 % of the photo off, on 16 scenes; the outline moved by one pixel either way is always worse |
| The labels and kinds say what was drawn | ink darker than 60, paper lighter than 220, a blue stamp; a shadow in at least 5 of 6 shadow scenes, a curl in every book scene |

The PSNR with the true corners stops near 24 dB because the page is drawn at 1.4 times the
photo's resolution, so the finest print is lost in the photo and cannot come back.

### Known gaps

- The book curl bows the page's lines with a sine; it is not a cylinder seen in 3D, and its
  inverse map is not returned. Do not use book scenes to judge flattening by geometry yet.
- Word bars are not letters: text recognition (`cer`) cannot be measured on drawn photos. The
  three pages with real print in `scan/tests/data/` are for that.
- No glare, no fingers, no folds, no moiré, no page cut by the frame.

## The runner (`scan-eval`)

```sh
# drawn photos (800 × 1066 unless --size says otherwise; the kinds in turn, seeds from --seed)
cargo run --release -p pdfcraft-scan --features synth --bin scan-eval -- --synthetic 200 --json now.json
# a test set: <name>.jpg with <name>.json, in any subfolder (an unzipped kept-scans export is one)
cargo run --release -p pdfcraft-scan --features synth --bin scan-eval -- ~/kept-scans
# options: --finder auto|network|lines|off, --no-refine, --unchecked, --md report.md, --contact-sheet <folder>
```

**What a kept-scans export needs before its figures mean anything (#425).** A page file is a
detection test only when its `corners` are the page's true corners. The runner leaves out, and
counts in its report (`left_out` in the JSON, a table in the text) with the reason:

| Left out | Why it is not a detection test |
|---|---|
| `merged`: `capture.merge.merged` is true, or `capture.photos_kept` is false (#426) | A low-light merge's photo is already the cropped page, so its corners are the whole picture |
| `book_half`: `book` is not null | The photo holds the whole spread and the corners only one half; the detector finds the spread |
| `discarded`: `outcome` is `discarded` | A scan given up on, often because something went wrong, corners included |
| `unchecked`: `checked` is not true | The app writes `"checked": false`. Corners nobody looked at are the detector's own answer and score as perfect. Open the photo, correct the page file's `corners` if needed, then set `"checked": true` |

The first three are always left out (their look could still be measured, but the runner does not
do that yet). `--unchecked` measures unchecked pages anyway, for a quick look; do not choose a
default from such a run. A page file made by hand (it has no `app` key) counts as checked unless
it says `"checked": false`, so test sets made before #425 and the SmartDoc script's are unchanged.

Each photo goes through the app's own stages (`eval.rs`): decode, find the page
(`detect_with`, the testing option's finder), refine the edges, straighten (`page_base`, with
paper matching on and turning upright off), Clean scan at strength 50 (`page_look`), and the
encoder. It prints a table per kind and for all photos (median, mean and 95th percentile of each
measure, and how many photos had it) and the time of each stage.

| Measure | Block | Taken on |
|---|---|---|
| `iou`, `corner_err_mean`, `corner_err_max` (shares of the photo's diagonal), `no_touch` | `metrics_geometry` | the corners the app ended with, against the true ones |
| `aspect_err` | `metrics_geometry` | the page made from the found corners |
| `line_straightness` (`px`), `bowed_px` | `straightness` | the page made from the found corners, before its look |
| `sharpness` | `metrics_tone` | the same page after its look |
| `paper_white`, `illum_spread`, `ink_contrast`, `hue_shift` | `metrics_tone` | the page made from the **true** corners after its look, with the drawn page's labels (a pixel within two of another label counts as neither paper nor ink) |
| `psnr`, `ssim` | `metrics_compare` | the same, against the drawn clean page |

A test set entry has no clean page, so it gets the geometry measures, the proportions (from its
`paper`, when not `unknown`), the straightness and the sharpness. `cer` is not taken: the scan
crate does not run text recognition, and drawn word bars are not letters.

**Comparing two builds.** Run the same command on the old build with `--json base.json`, then on
the new one with `--baseline base.json`: it prints each measure whose mean got worse by more than
its tolerance (`eval::METRICS`; for example 0.01 of `iou`, 0.002 of `corner_err_max`, 0.5 dB of
`psnr`) and exits with 1. Times are left out of the comparison: they depend on the machine.

**Contact sheets** are the photos with the true outline in green and the found one in red. Never
make them from someone's own documents for the repository or a pull request.

CI runs the runner on six drawn photos on every pull request, so it keeps building and running.

### Baseline: the pipeline as of this runner

`scan-eval --synthetic 200` (800 × 1066, seeds 0–199, the six kinds in turn), release build,
on the x86_64 Linux desktop this was written on, 12 November 2026. The detector found a page in
194 of 200 photos (the network; its model file is in the repository).

| measure | median | mean | p95 | n |
|---|---|---|---|---|
| iou | 0.9999 | 0.9792 | 1.0000 | 200 |
| corner_err_mean | 0.0000 | 0.0066 | 0.0088 | 200 |
| corner_err_max | 0.0001 | 0.0114 | 0.0278 | 200 |
| no_touch | 1.000 | 0.8250 | 1.000 | 200 |
| aspect_err | 0.0001 | 0.0233 | 0.0568 | 200 |
| sharpness | 21.289 | 21.808 | 41.356 | 200 |
| paper_white | 0.9108 | 0.8537 | 0.9903 | 200 |
| illum_spread | 0.0359 | 0.1109 | 0.5322 | 200 |
| ink_contrast | 255.0 | 252.8 | 255.0 | 200 |
| hue_shift | 6.516 | 6.817 | 10.767 | 200 |
| psnr | 18.429 | 16.801 | 20.874 | 200 |
| ssim | 0.7654 | 0.7025 | 0.8393 | 200 |
| line_straightness | 3.390 | 6.426 | 28.553 | 197 |
| bowed_px | 2.318 | 4.561 | 17.779 | 197 |

| kind | photos | iou median | no_touch share | corner_err_max p95 | line_straightness median (px) |
|---|---|---|---|---|---|
| desk | 34 | 1.0000 | 0.912 | 0.0435 | 3.74 |
| light | 34 | 0.9999 | 1.000 | 0.0001 | 2.78 |
| receipt | 33 | 0.9999 | 0.879 | 0.3031 | 0.11 |
| book | 33 | 0.9883 | 0.242 | 0.0242 | 12.75 |
| shadow | 33 | 0.9999 | 0.939 | 0.0314 | 2.02 |
| lowlight | 33 | 0.9988 | 0.970 | 0.0015 | 0.52 |

Time per stage in milliseconds (`threads: 4`; a desktop, not a phone):

| stage | median | mean | p95 |
|---|---|---|---|
| decode | 6.2 | 7.4 | 13.5 |
| detect | 37.2 | 49.0 | 184.2 |
| refine | 3.7 | 3.9 | 5.1 |
| scan_rectify | 108.0 | 108.8 | 129.0 |
| scan_look | 303.2 | 302.6 | 388.9 |
| scan_encode | 87.0 | 96.6 | 142.9 |

How to read it:

- **Drawn photos are easy for the edges.** Their page edges are straight, hard and unbroken, so
  on flat pages the found corners are within a hundredth of a per cent of the diagonal. That says
  the pipeline is not broken, not that it is this exact on photos. Book pages miss `no_touch`
  most often because their edges bow. The misses that are left are whole pages not found or
  found wrongly (6 photos with no page found; the receipts' 95th percentile is 30 % of the
  diagonal): those photos are where a detector change will show first.
- `ink_contrast` sits at 255 because Clean scan takes paper to white and print to black on
  these pages. PSNR and SSIM against the drawn page are low (about 18 dB) for the same reason:
  the drawn page's paper is not pure white and its ink not pure black, and Clean scan changes
  both on purpose. Compare them between builds, not with a target.
- `line_straightness` is 2–4 px on flat drawn pages: the block follows the ruled table and the
  picture region as lines too (its known gap in [ANDROID_SCAN_BLOCKS.md](ANDROID_SCAN_BLOCKS.md)).
  Book pages are at about 13 px.

## The CI gate (`tests_gate.rs`)

A test of the scan crate, so it runs in CI's "Rust engine and provenance" job with the others:
40 drawn photos (seeds 288–327, the six kinds in turn) go through the pipeline, and a summary of
each gated measure must stay within its limit. Each limit is a little looser than what the
pipeline measured on these photos when the gate was set. A change that makes a measure better
should tighten its limit in the same pull request; one that makes it worse fails the gate.

| Measure | Over the 40 photos | Limit | Measured when set |
|---|---|---|---|
| `iou` | median | ≥ 0.995 | 0.99976 |
| `no_touch` | share | ≥ 0.80 | 0.900 (36 of 40) |
| `corner_err_max` | median | ≤ 0.002 | 0.00010 |
| `aspect_err` | median | ≤ 0.005 | 0.00009 |
| `paper_white` | mean | ≥ 0.79 | 0.820 |
| `illum_spread` | mean | ≤ 0.15 | 0.133 |
| `ink_contrast` | mean | ≥ 240 | 250.0 |
| `hue_shift` | mean | ≤ 8.0° | 6.17° |
| `psnr` | mean | ≥ 15.3 dB | 15.90 dB |
| `ssim` | mean | ≥ 0.66 | 0.679 |
| `line_straightness` | median | ≤ 3.5 px | 2.66 px |

A measure taken on fewer than 36 of the 40 photos fails too. A second test runs the same photos
with the detector switched off (the page is the whole photo) and checks that `iou`, `no_touch`,
`corner_err_max` and `aspect_err` all fail: with nothing found, they were 0.41, 0, 0.27 and 0.06.

Where it departs from the issue (#288, section 5):

- The photos are 600 × 800, not 800 × 1066. The time goes into the page the app makes, whose
  size comes from the paper size and not from the photo, so a bigger photo adds little but
  drawing time; 600 × 800 keeps the drawing short.
- The found page is not given its look (`Options::look_found_page`): the clean-up measures come
  from the page made with the true corners either way, and the look is the slowest stage.
- The scan crate is built at `opt-level = 2` in the dev profile (root `Cargo.toml`), as its
  dependencies already were. At 1, the gate took over a minute on four cores.

Time: the two gate tests took 39 s together on the 4-core desktop this was written on, with no
other test running; the whole scan crate's tests 73 s. CI's runner also has four cores, and runs
the gate beside the crate's other tests.

## Public photos: SmartDoc 2015 (`tools/scan-eval/scan_dataset_smartdoc.py`)

SmartDoc 2015 Challenge 1 (ICDAR 2015, CC BY 4.0) is phone video of six printed documents on five
backgrounds, with the page's corners marked on every frame: real camera photos whose answers are
known, which nobody's private documents are in. The script turns a local copy into a test set that
`scan-eval <folder>` reads as it is (section 7 of #288):

It reads either published form: the frames release (`frames.tar.gz` from
https://github.com/jchazalon/smartdoc15-ch1-dataset/releases, unpacked: `metadata.csv.gz` and the
frames as JPEG), which needs only Python's standard library, or the original videos with their
`*.gt.xml` files (Zenodo record 1230218), which need `opencv-python` to decode.

```sh
python3 tools/scan-eval/scan_dataset_smartdoc.py ~/smartdoc ~/smartdoc-set --every 30 --list   # what it would write
python3 tools/scan-eval/scan_dataset_smartdoc.py ~/smartdoc ~/smartdoc-set --every 30
cargo run --release -p pdfcraft-scan --features synth --bin scan-eval -- ~/smartdoc-set --md smartdoc.md
```

- Every 30th frame (`--every`), starting at the first; frames the dataset marks `rejected`, or with
  a corner missing, are left out.
- `corners` are the marked corners as shares of the frame, TL TR BR BL, not clamped; `paper` is
  `a4`, `curved` false, `notes` names the background, the document and the frame.
- `kind` is `other` unless `--kind background01=desk` (and so on) says otherwise. The backgrounds
  were not looked at when the script was written, so it claims no mapping of its own.
- It downloads nothing, and neither the dataset nor what the script writes goes into the
  repository.

- The dataset's `tl` is the page's own top-left as a reader holds it, even when the page is upside
  down in the frame: the same convention as the test set.

**Not verified:** the script has not run on the real dataset; it was not downloaded on the machine
the script was written on, which has no OpenCV either. Its tests (`test_scan_dataset_smartdoc.py`,
run by CI) use files written by hand in the two formats as their documentation gives them (the
frames release's README and its example row; the `.gt.xml` `frame` / `point` layout), and check the
sampling, the corner order, the JPEG size read, the copy and the JSON. No `scan-eval` figures on
SmartDoc exist yet.

## Still to do in #288

- `scan-eval --reference` (section 8): `scan_reference look` does that job and stays.
