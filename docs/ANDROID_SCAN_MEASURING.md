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

## Still to do in #288

- The `scan-eval` runner: the pipeline stage by stage on drawn photos or a kept-scans folder,
  with the `metrics_*` and `straightness` blocks, a table per kind, timings, a JSON report and a
  comparison with a saved one.
- The CI gate on 40 drawn photos.
- The SmartDoc script.
