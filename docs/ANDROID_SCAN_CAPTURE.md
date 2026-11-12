# Scan: sharpness and multi-frame merge as native calls (#296, #297, native side)

The blocks `sharpness.rs` and `merge.rs` ([ANDROID_SCAN_BLOCKS.md](ANDROID_SCAN_BLOCKS.md)) are
now reachable from the app through two UniFFI calls. **No screen calls them yet**: there is no
in-app camera (#294), so no burst and no auto-capture. The blocks themselves are unchanged; the
new code is the plumbing around them, in `apps/pdfcraft-android/scan/src/capture.rs` (bytes in,
encoded page out) and `apps/pdfcraft-android/src/scan.rs` (files, the scan root, errors).

## The calls

`scan_sharpness(photo_path, corners) -> ScanSharpness`

- Decodes the photo with the usual limits (64 megapixels, 256 MB file), takes its luminance and
  hands it to `sharpness::sharpness` with the page's quad. The block halves the photo itself and
  measures inside the quad shrunk by 5 %, so the full-size luminance is what it is given.
- `corners` are 8 shares of the upright photo, as for every scan call.
- Returns `score`, `blurred_unverified`, `threshold` (1.0) and `timings` (`scan_decode`,
  `scan_sharpness`).
- **`blurred_unverified` is for the log and the kept scan only. Do not show "Blurry, taking it
  again" from it.** The threshold was set on drawn pages, and motion blur, the commonest blur in
  a hand-held shot, is not told apart (drawn 7 and 13 pixel streaks score across the sharp
  range). The field's name says so, so the app side cannot mistake it for a verdict.

`scan_merge_frames(ScanMergeRequest) -> ScanMergeResult`

- The request: 2 to 4 `ScanMergeFrame { photo_path, corners }`, the `output_path` (`.jpg` or
  `.png` is added), the `format`, and `focal_35mm` and `snap_paper` as in `ScanPageRequest`.
- What it does, in order: every frame's sharpness (`sharpness::sharpness`), the sharpest as the
  reference and frames under 70 % of its score left out (`merge::choose`); the reference
  straightened to its proportions as `page_base` does it (#293), shrunk to at most 12
  megapixels; each other frame straightened to the same size with its own corners, its move
  against the reference found (`merge::align`), then straightened again from the photo **with the
  move folded into its perspective transform**, so the frame is resampled once; its noise
  (`merge::noise_between`); and the frame folded into the robust average (`merge::Merger`).
- A frame is left out for its alignment (`rejected_align`) when the move is over 2.5 pixels
  (`Shift::usable`) or the mean difference after the move exceeds 3 × its noise + 2 grey
  levels. The "+ 2" is not in the issue: frames with no measurable noise (drawn pages, a
  camera's own denoising) would otherwise be refused for the half grey level resampling alone
  leaves. It is the same floor `Merger` adds to its own limit.
- Returns `path`, `width`, `height`, `format`, `dpi`, `reference`, `scores`, `used`,
  `rejected_blur`, `rejected_align`, `psnr_gain_tenths_db` (10·log10 of the mean count, in
  tenths of a dB: the theoretical gain, not a measurement) and `timings` (`scan_decode`,
  `scan_sharpness`, `scan_rectify`, `scan_merge_align`, `scan_merge`, `scan_encode`, each summed
  over the frames).
- The merged page has no look. To give it one, pass it to `scan_process_page` with the
  whole-photo corners.
- Memory: one decoded photo at a time, the reference twice (the merger keeps its own copy and has
  no accessor; `merge.rs` was not changed), its luminance, the merger's sum and count, and one
  straightened frame. Every photo is decoded twice (once to score it, once to straighten it) so
  that four photos are never held at once.

Folding the move: a translation followed by the perspective transform is a perspective
transform, and four points fix it. `capture::folded` sends the output rectangle's corners,
moved back by the shift, through the frame's transform and straightens from those corners.
`merge::moved` is not used in the pipeline; a test checks that the folded result is the page
`moved` gives (0.4 to 0.7 grey levels RMS apart on a softened drawn page, 3.5 to 42 against the
opposite move, so the sign is right).

## What was measured, on drawn pages only

Every photo below is drawn in code and written as a PNG or JPEG, as a camera would hand it over.
None is a real photo and nothing ran on a phone.

| Test | Result |
|---|---|
| `tests_capture::three_noisy_frames_merge_into_a_cleaner_page` (scan crate): three 700 × 900 frames, noise σ 10 per channel, corners off by up to 1.4 px | all three used; moves found within 0.1 px of the corner errors; error against the clean page 10.01 alone, 6.38 merged (3.9 dB; the estimate says 4.7) |
| `scan::tests::a_burst_on_disk_merges_into_a_cleaner_page` (UniFFI layer, files on disk): three 480 × 600 frames, noise σ about 10, corners off by up to 1 px | all three used; 10.02 alone, 6.23 merged (4.1 dB; estimate 47 tenths) |
| `tests_capture::blurred_and_far_moved_frames_are_left_out` | a frame blurred by a box of 7 three times scores 0.20 against 1.05: `rejected_blur` 1; a frame whose corners are 6 px off: `rejected_align` 1 |
| `tests_capture::a_blurred_photo_scores_lower_than_a_sharp_one` (JPEG 92) | sharp drawn page 1.07, the same page blurred 0.18 |
| `scan::tests::a_blurred_photo_scores_lower_than_a_sharp_one` (JPEG) | sharp 1.82, blurred 0.20 |
| Refusals | 1 or 5 frames, 6 numbers for corners, corners that enclose nothing, a corner that is not a number, a missing file, a file outside the scan root, an unreadable file: an error each, no panic, no file written |
| Determinism | one thread and two threads give the same bytes |

Two things these tests showed that are worth knowing before the app side:

- **The sharp drawn page in a JPEG scored 1.07, just over the threshold of 1.0**, while the
  block's own drawn pages score 1.5 to 1.9. A softened drawn page (a box blur of 3, three times,
  about σ 1.4) scored 0.61 and is "blurred". The threshold is close to where ordinary pages
  land: one more reason to only log it until it is set from real photos.
- **Corner errors add up between frames.** Frames whose corners are each within 1.3 px of the
  truth can be 2.7 px apart, and then one of them is refused for its move (one test was first
  written that way and refused a frame). With the refined corners of #291 this should be rare,
  but `rejected_align` in the log will say.

Timings in the tests (desktop, test profile, three frames of 0.6 megapixels) are not a guide to a
phone and are not quoted as one.

## Not verified

- Nothing ran on a phone; no real photo was used, and none of the owner's photos.
- Motion blur is not detected by `sharpness`; its threshold comes from drawn pages.
- The noise gain on real phone JPEGs (already denoised by the camera) is not measured; #297's
  acceptance items (a dim-room crop, the OCR check, 4 s per page) are open.
- Memory on a phone at 12 megapixels is not measured.
- The app side (a ScanLab option for each, the log fields, the kept-scan fields, and the camera
  that would call them) is the work of the app pull requests for #296 and #297.
