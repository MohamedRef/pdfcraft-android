# Scanner building blocks: written and tested, not yet used

Eleven modules of the scan crate (`apps/pdfcraft-android/scan/src`) that the remaining scanner
issues need. Each is a file of its own with its tests, uses only the crate's own types, and is
declared in `lib.rs`. **Nothing calls them yet**: no page the app makes changes with this, there
is no setting, and nothing is logged. Wiring each one in (the function that calls it, its
testing option, its log fields, its kept-scan fields: the three requirements in
[ANDROID_SCAN_HANDOVER.md](ANDROID_SCAN_HANDOVER.md)) is the work that is left.

They were written by separate workers from the issue texts, each against a stand-in for the
crate, then built and tested here in the real crate: `cargo test -p pdfcraft-scan --lib` runs
287 tests, all passing, and `cargo clippy -p pdfcraft-scan --all-targets` reports nothing in
these files. Every test page is drawn in code. **None of this has seen a real photo or a
phone**, and every threshold below is the issue's starting value or was set on drawn pages.

| Issue | Module | What it gives | Measured in its tests | Known gaps |
|---|---|---|---|---|
| #288 | `metrics_geometry` | `iou`, `corner_err`, `no_touch`, `aspect_err` for found corners against checked ones | square against itself turned 45°: 0.70711; a corner 25 px off on 4000×3000: max 0.005 (`no_touch`), 26 px: not | `corner_err` tries the four starting corners of the same winding only |
| #288 | `metrics_tone` | `sharpness`, `paper_white`, `illum_spread`, `ink_contrast`, `hue_shift`, `percentile` over masks | ramp 100→200: spread 0.6; red→orange: 30.1°; 350°→10°: 20.2°; checkerboard 7.3× sharper than blurred | `sharpness` follows how much of the page is print (one line on a blank page: 0.13 of a full page), so it compares the same page only; `illum_spread` works on a copy reduced 4× |
| #288 | `metrics_compare` | `psnr`, `ssim` (8×8 windows stepped 4), `cer`, `edit_distance` | +5 grey levels: 34.15 dB; brightness +10: SSIM 0.997; blurred stripes: 0.62; `kitten`/`sitting`: 3/7 | SSIM not checked against another implementation; no Unicode normalisation in `cer` |
| #288, #302 | `straightness` | `line_straightness(page) -> Option<Straightness { px, bowed_px, lines }>`: how far lines of text are from level and straight | level lines 0.00 px; 12 px bow 3.57 (3.58 by formula); 10 px lean 2.90, bowed 0.15; blur and noise change it by 0.001; blank page, noise, two lines: `None` | ruled table lines are followed as text lines; lines under about half the page width are not followed |
| #302 | `flatten_guard` | `guard(perspective_px, flattened_px)`: keep the flattened page only when at least 10 % straighter; `border_trim` and `trimmed`: cut a strip of table from a page's edge, 2 % at most | 5.0 against 4.4 keeps flattened, 4.6 does not; 1 % strip trimmed exactly, 5 % strip trimmed by 2 %; text reaching the edge not trimmed | trims move in 4 px steps; not connected to `flatten.rs` or the preview |
| #299 | `photo_mask` | `photo_mask(page) -> Option<PhotoMask>`: 24 px blocks that are photograph, with a soft edge, `whole_page` over 60 % | quarter-page photograph: 300 of 300 blocks found, none over 2 blocks away; ruled table, black heading, blue stamp: none | dense table blocks come close to the thresholds (`mid` 0.40 against 0.45, `ori` 2.51 against 2.6); the union with the #298 object mask is the caller's |
| #299 | `preset` | `classify(features) -> Preset`, `params(preset)`, `ink_features(page, dpi)`, `gutter_band(page)` | each rule and just under each threshold; `ColouredForm` never chosen in 10 000 drawn feature sets | settings the issue's table leaves unsaid take the Document preset's; a card-sized page not marked as an ID card becomes Receipt by the short-side rule; glare is the plain share of near-white pixels, so it must be measured before whitening |
| #303 | `book` | `find_gutter(spread) -> Option<Gutter>`, `split(quad, gutter) -> Option<Spread>`: two page outlines from one outline of an open book | gutter at 0.50 and 0.46 found exactly; leaning 0.47/0.53 found as 0.4701/0.5301; a dark photograph away from the middle not taken for it; halves add to 99 % | part A's Rust only: the mode switch, page order and two pages sharing a file are the app's; part B (flattening from text lines) not started; portrait spreads have no centre-line fallback |
| #297 | `merge` | `choose`, `align` (±3 px, then a fraction of a pixel), `noise_between`, `Merger` (reference, sum and count: never all frames), `moved` | four frames with noise 10: error 0.55 of one frame's (28.1 → 33.3 dB); moves found within 0.1 px; a dark blob in one frame leaves the page within noise | works on frames already straightened to one size: straightening with the move folded in is the caller's; no report was written for this module (its worker was stopped at that step), so read the file's own comments |
| #296 | `sharpness` | `sharpness(luma, quad) -> Sharpness { score, blurred }`, `BLURRED_BELOW = 1.0` | sharp drawn pages 1.49–1.93; Gaussian blur σ 2.5 and 4: 0.15–0.48; σ 1.5: 0.61–0.83; a dim copy scores the same; 12 MP in 0.7 s (debug) | **motion blur is not told apart**: 7 and 13 px streaks score 0.90–1.60, across the sharp range. The threshold was set on drawn pages. Do not show a "take it again" prompt from it before it is set from real sharp and shaken photos |
| #339 | `upright_pages` | `settle(pages)`, `followed(pages)`: an unsure page follows two sure neighbours (≥ 0.95) that agree; `turn_corners`, `turn_back`, `total_turn`: a quarter turn by reordering the corners, for Rotate and its undo | direction checked with a homography: a point at (x, y) lands at (h − y, x) after one clockwise turn | not wired into `page.rs` or the app |

## What was started and not finished

The same run was stopped by the owner before these were done. Nothing of them is in the crate:

- **Drawn test photos with exact answers** (#288, the `synth` generator): half written, its
  tests not yet passing.
- **Kotlin and research drafts**: see the draft pull request named in this pull request's
  description. They were never compiled.

## Using a block

1. Read the module's top comment and its tests: the tests name what it promises, with numbers.
2. Call it from the pipeline (`page.rs`, `enhance/`, or the UniFFI layer in
   `apps/pdfcraft-android/src/scan.rs`).
3. Give it a testing option in `ScanLab`, log what it decided, and write that into the kept
   scan's page file.
4. Replace its drawn-page threshold with one from the owner's photos, and say in the issue what
   the photos showed.
