# Scan: measuring on drawn photos (#288)

How the scanner is measured on a desktop and in CI, without anyone's photos. The owner's own
evidence comes from kept scans on his phone ([ANDROID_SCAN_TESTING.md](ANDROID_SCAN_TESTING.md));
this page is about the photos drawn in code, which anyone can make again from a seed.

The baseline for 0.0.46-beta, with the words every comparison uses, the runs to make on the phone
and the tablet, and which border decision each runner makes, is in
[ANDROID_SCAN_BASELINE.md](ANDROID_SCAN_BASELINE.md) (#779).

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
# options: --finder auto|network|lines|off, --no-refine, --second-look, --unchecked, --md report.md, --contact-sheet <folder>
```

**The second look (#779).** The app asks the line detector too when the network is unsure (#290,
on by default in the app). The runner does so only with `--second-look`; without it, and in the
CI gate, the page is the network's alone, as every figure on this page was taken. With the flag
the report says on how many photos the line detector was asked and taken, and the JSON has
`second_look` per entry and in its head. The baseline page has both paths side by side.

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

A test of the scan crate, so it runs in CI's "Rust scanner tests" job with the crate's other tests:
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

## Clean scan on pages whose marks are known (`scan-eval --clean`, #784)

The drawn photos above are for finding the page; their marks are word bars and one picture, and
their clean-up measures are four. For the question "what does Clean scan do to print, to pale
marks, to colour and to a photograph on the page", there is a second set of drawn pages
(`clean_pages.rs`) on which every pixel says what it is, and a measure per kind of mark
(`metrics_marks.rs`, put together in `clean_eval.rs`).

```sh
cargo run --release -p pdfcraft-scan --features synth --bin scan-eval -- --clean 4 --md clean.md --json clean.json
# options: --seed S (first seed, 0), --groups text,photo (some of the groups), --baseline old.json
```

**The pages.** Each is a straight page that fills the photo (A4 at 200 dpi, 1654 × 2339, so the
app's page is the photo pixel for pixel and a mark's mask needs no warping; the receipt is
1150 × 3220), drawn clean, then given a camera's faults: uneven light, a colour cast, less
contrast, blur, grain, JPEG. Nothing on them is a letter; strokes are drawn as bars, hairlines,
dots and rings of known width and darkness.

| Group | What is on it | Camera faults |
|---|---|---|
| `text` | lines of print, hairlines one and two pixels wide, rows of dots (the dot of an i, a decimal point), print running to 6 px from each edge | ordinary |
| `receipt` | a long narrow page of thin print, faded thermal print (a third as dark as ink), a coloured stamp | ordinary |
| `pencil` | print, pencil lines (grey, a quarter to a third as dark as ink), a faint ruled grid | ordinary |
| `colour` | print, blue and red pen, highlighter with print under it, a dark stamp and a pale one | ordinary |
| `photo` | print, and a colour photograph with a dark part and a light part, each holding fine detail of low contrast | ordinary |
| `shadow` | the `colour` page of the same seed | a hard shadow across it and a strong fall of light |
| `lowlight` | the `pencil` page of the same seed | a dim, noisy, blurred photo with a strong cast |

**The measures.** "Under the paper beside it" is the mark's grey level against the median of the
paper in the same 64 px tile, so the same measure reads the photo as taken and the cleaned page.

| Measure | What it is | Better |
|---|---|---|
| `paper_white` | share of the blank paper at 250 or over | higher |
| `paper_tint` | mean chroma of the blank paper, grey levels | lower |
| `paper_specks` | share of the blank paper a tenth of the range or more under the paper beside it | lower |
| `illum_spread` | as in `metrics_tone` | lower |
| `ink_contrast`, `thin_contrast`, `edge_contrast`, `faint_contrast`, `pencil_contrast`, `highlight_ink_contrast` | median drop of the mark under the paper beside it, grey levels (255 is black on white); for print, hairlines, print at the edges, faded print and the grid, pencil, print under highlighter | higher |
| `thin_kept`, `dot_kept`, `edge_kept`, `faint_kept`, `pencil_kept`, `pale_stamp_kept` | share of the mark's pixels still at least 25.5 levels (a tenth) under the paper beside them | higher |
| `ink_weight`, `thin_weight` | pixels within 3 of the strokes that are at least half as dark as the strokes' median, over the strokes' own count: 1 is as drawn, more is fatter | nearer 1 |
| `pen_chroma_kept`, `highlight_chroma_kept`, `stamp_chroma_kept`, `pale_stamp_chroma_kept`, `photo_chroma_kept` | median chroma of the mark over that of the drawn page | nearer 1 |
| `pen_hue`, `highlight_hue`, `stamp_hue`, `pale_stamp_hue` | hue against the drawn page, degrees (`metrics_tone::hue_shift`) | lower |
| `photo_tone_kept` | slope of the page's grey levels against the drawn ones inside the photograph: 1 keeps every step, more is harder contrast | nearer 1 |
| `photo_dark_detail`, `photo_light_detail` | the same slope inside the photograph's dark part and its light part: 0 is the detail gone to one flat tone | nearer 1 |
| `photo_black_clipped`, `photo_white_clipped` | share of the photograph at 8 or under, at 250 or over | lower |
| `photo_ssim` | SSIM of the photograph against the drawn one | higher |
| `photo_edge_paper` | share of the blank paper within 12 px of the photograph at 250 or over | higher |

**The runs.** Each page is measured four times: the photo as taken (straightened only), Clean
scan with the app's defaults (document types, their tone, gentle photograph sharpening, finger
removal, the photograph tone below), the same with one curve for photographs too (the app before
#784's change), and Clean scan at strength 50 with no document types (what `--synthetic` runs).
The JSON has `clean.<run>.<group>.<measure>` (`original`, `app`, `one_curve`, `plain`), and
`--baseline` compares the `app` run group by group.

### Baseline: 4 pages a group, seeds 0–3, means

Columns: photo as taken | Clean scan, app defaults | one curve for photographs too | strength 50,
no types. Where the third column is left out it equals the second (the page has no photograph).

| Group | Measure | Photo | App | One curve | 50, no types |
|---|---|---|---|---|---|
| text | `paper_white` | 0.006 | 1.000 | | 1.000 |
| text | `ink_contrast` | 152.8 | 250.5 | | 244.5 |
| text | `thin_contrast` | 115.0 | 209.8 | | 197.8 |
| text | `edge_contrast` | 162.0 | 252.5 | | 247.8 |
| text | `thin_kept`, `dot_kept`, `edge_kept` | 1.000 | 1.000 | | 1.000 |
| text | `ink_weight` | 1.046 | 1.055 | | 1.051 |
| text | `thin_weight` | 1.278 | 1.210 | | 1.201 |
| receipt | `faint_contrast` | 57.0 | 151.0 | | 106.5 |
| receipt | `faint_kept` | 1.000 | 1.000 | | 1.000 |
| receipt | `thin_contrast` | 97.5 | 233.0 | | 188.2 |
| receipt | `ink_weight` | 1.026 | 1.131 | | 1.035 |
| receipt | `stamp_chroma_kept` | 0.720 | 0.897 | | 0.943 |
| receipt | `stamp_hue` | 1.59° | 10.20° | | 5.79° |
| pencil | `pencil_contrast` | 49.8 | 94.5 | | 88.0 |
| pencil | `pencil_kept` | 0.9996 | 1.000 | | 1.000 |
| pencil | `faint_contrast` | 58.8 | 114.2 | | 105.8 |
| colour | `paper_tint` | 9.32 | 0.024 | | 0.023 |
| colour | `pen_chroma_kept` | 0.620 | 0.877 | | 0.877 |
| colour | `pen_hue` | 2.40° | 6.84° | | 5.44° |
| colour | `highlight_chroma_kept` | 0.860 | 1.012 | | 0.990 |
| colour | `highlight_hue` | 3.56° | 2.70° | | 2.66° |
| colour | `highlight_ink_contrast` | 123.0 | 195.8 | | 195.8 |
| colour | `stamp_chroma_kept` | 0.735 | 0.944 | | 0.971 |
| colour | `stamp_hue` | 1.72° | 5.39° | | 4.46° |
| colour | `pale_stamp_chroma_kept` | 0.719 | 1.090 | | 1.058 |
| colour | `pale_stamp_kept` | 0.973 | 0.996 | | 0.996 |
| photo | `photo_tone_kept` | 0.824 | 1.187 | 1.300 | 1.298 |
| photo | `photo_dark_detail` | 0.776 | 0.512 | 0.001 | 0.003 |
| photo | `photo_light_detail` | 0.831 | 1.222 | 0.236 | 0.292 |
| photo | `photo_black_clipped` | 0.000 | 0.090 | 0.145 | 0.124 |
| photo | `photo_white_clipped` | 0.000 | 0.000 | 0.086 | 0.084 |
| photo | `photo_ssim` | 0.893 | 0.873 | 0.763 | 0.798 |
| photo | `photo_edge_paper` | 0.000 | 0.921 | 0.926 | 0.926 |
| photo | `ink_contrast` | 135.0 | 242.2 | 242.5 | 232.8 |
| photo | `thin_contrast` | 91.3 | 181.5 | 183.0 | 171.2 |
| shadow | `paper_white` | 0.003 | 0.994 | | 0.994 |
| shadow | `paper_specks` | 0.0056 | 0.0035 | | 0.0035 |
| shadow | `illum_spread` | 0.533 | 0.014 | | 0.012 |
| shadow | `pen_chroma_kept` | 0.441 | 0.836 | | 0.821 |
| shadow | `highlight_chroma_kept` | 0.583 | 0.975 | | 0.948 |
| shadow | `pale_stamp_kept` | 0.953 | 0.995 | | 0.994 |
| lowlight | `paper_white` | 0.000 | 0.997 | | 0.996 |
| lowlight | `ink_contrast` | 59.8 | 227.0 | | 215.2 |
| lowlight | `thin_kept` | 0.873 | 1.000 | | 1.000 |
| lowlight | `faint_kept` | 0.363 | 0.995 | | 0.995 |
| lowlight | `pencil_kept` | 0.225 | 0.981 | | 0.980 |
| lowlight | `pencil_contrast` | 20.0 | 59.3 | | 55.5 |
| lowlight | `thin_weight` | 2.43 | 2.37 | | 2.39 |

What the figures say:

- **Print, hairlines, dots, print at the edges, pencil, faded print, a pale stamp:** no fault. All
  of every such mark is still there, darker against the paper than in the photo, and strokes are
  not fatter than the photo had them. In the dim photo a quarter of the pencil could be seen
  before the look and 98 % after.
- **Colour:** pen, highlighter and stamps come out with more of their drawn colour than the photo
  had (0.84–1.09 against 0.44–0.86). Their hue moves by 5–7° for a pen and a dark stamp, and by
  10° for a stamp on a receipt, whose type adds a darker curve. That is a channel reaching its
  end before the others. It is measured and gated at today's value, not changed: nothing drawn
  can say which hue error an eye accepts.
- **A photograph on a page of print was the one fault found.** With one curve for the whole page
  the photograph's dark part went to one flat black (detail 0.001 of the drawn, 14 % of the
  photograph at black) and 9 % of it to white. `ANDROID_SCAN_AUTO_LOOK.md` has the change; the
  second and third columns are after and before.
- **Paper beside a photograph:** 7–8 % of the paper within 12 px of a photograph is not white,
  with or without the change (the look's own soft edge around what it takes for an object). Left
  as it is, and gated so it does not grow.

### The gate (`tests_clean_eval.rs`)

One page a group (seed 0) goes through the app's defaults in the crate's tests, about 35 s on
four cores beside nothing else. Each limit is a little looser than what was measured when it was
set. On every page: `paper_white` ≥ 0.97, `paper_specks` ≤ 0.01, `ink_contrast` ≥ 200,
`edge_contrast` ≥ 220, `edge_kept` ≥ 0.999, `thin_kept` ≥ 0.995, `dot_kept` ≥ 0.995,
`illum_spread` ≤ 0.05; and 33 limits by group (the `LIMITS` table in the file, each with the
value measured), among them `pencil_kept` ≥ 0.97 in low light, `pen_chroma_kept` ≥ 0.75,
`photo_dark_detail` ≥ 0.40, `photo_white_clipped` ≤ 0.01 and `photo_edge_paper` ≥ 0.90. Other
tests check that the photo as taken breaks the paper and print limits (so they measure the look),
that a page without photographs is byte for byte the same with the photograph tone on and off,
and that a region the finger step filled is left out of it.

### What these pages cannot say

- Strokes are bars and rings, not letters: no text recognition is run, and nothing here measures
  whether small Latin or Arabic print reads better. `scan_cer` (`ANDROID_SCAN_AUTO_LOOK.md`) is
  the tool for that, on real pages.
- The drawn photograph is smooth shapes with fine texture; a printed photograph has a halftone
  screen and its own black. How dark a printed photograph's black should come out is a matter
  for real pages.
- Halos around print are not measured apart from `ink_weight`; folds, show-through from the back
  of the sheet, glossy glare and coloured paper are not drawn.
- The times printed are a desktop's; no time here is a baseline.

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
