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
# options: --finder auto|network|lines|off, --no-refine, --md report.md, --contact-sheet <folder>
```

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

## Still to do in #288

- The CI gate on 40 drawn photos.
- The SmartDoc script.
- `scan-eval --reference` (section 8): `scan_reference look` does that job and stays.
