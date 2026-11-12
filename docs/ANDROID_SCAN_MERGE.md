# Scanner: low-light merge (#297)

In dim light the in-app camera (#294) takes four stills in a row instead of one. It finds the page
on each and merges them with `scan_merge_frames` (#389, `ANDROID_SCAN_CAPTURE.md`) into one
straightened page with less noise, which goes to the scan in place of the single photo. **Nothing
here has run on a phone**: it was compiled, its decisions tested on the JVM, and the merge itself
measured on drawn frames only (#389).

**It is Off by default** (#426, 12 November 2026). See [the default](#the-default-off-426).

## The default: Off (#426)

The setting starts at **Off**. Auto and Always are still there, in Settings ▸ Scan and among the
testing options, for anyone who picks them.

**Why.** A merge throws away the two things that make a wrong page recoverable:

- **The crop is baked in.** The page is found on each photo by the detector alone, with nobody
  checking the outline, and `scan_merge_frames` writes a page already straightened and cropped by
  those outlines. The scan gets that page with whole-picture corners, so the Crop screen (#381)
  only has the cropped page to work on. If the outline cut part of the page off or took in the
  table, the page has to be taken again. The detector's own figure on the ten reference photos is
  wrong and accepted on 2, nothing found on 2 (#290). Nothing found falls back to the first photo;
  wrong and accepted does not, because all four frames get the same wrong outline and the merge
  succeeds. Dim light, where Auto merges, is where detection is weakest.
- **The photos are deleted.** All four burst photos go after a merge, so a kept scan holds only
  the cropped page. The test set the owner is about to collect would lose its dim-light photos,
  and those pages would mislead `scan-eval` (#425) as ground truth.

The owner's first priority is precise edges with Crop as the safety net, so the safe choice is to
keep the net: one photo, found and cropped like any other page, until the merge has been tried on
the owner's phone. Of the two fixes #426 offers, Off is the one that changes nothing else; the
other, below, is a larger change to how a merged page is made.

**Chosen over keeping the reference photo as the page** (the page enters the scan as the
sharpest photo with its found corners, and the merge runs when the page is made, with the corners
the person left, from frames kept until the scan is saved or discarded). That is the better design
and is the way to turn the merge back on, but it moves the merge into the page pipeline, keeps
four 12 MP photos per page for the life of the scan and changes the session's files. It is left for
a later pull request, and has not been started.

**What a person who turns it on gets** is the behaviour below, unchanged; the setting's text now
says it is off by default and that the merged page comes already cropped.

**Existing installs.** The setting is stored only when someone picks a value. An install where
nobody picked one moves from Auto to Off; one where someone picked Auto or Always keeps it.

**To change the default** after phone tests: `ScanMergeSetting.DEFAULT` in `ui/scan/ScanLowLight.kt`,
the test `settingDecodes`, this section and the handover's row for #297.

## How it works

1. **When** (`scanBurstWhy`, `ui/scan/ScanLowLight.kt`):
   - The setting *Low-light merge*, in Settings ▸ Scan and among the scanner's testing options, is
     **Auto**, **Always** or **Off** (the default since #426).
   - Off, or a phone whose memory class is under 256 MB, takes one photo.
   - Always takes a burst.
   - Auto takes a burst when the live frame's mean brightness is under 70 (#295) or the preview
     reports a sensitivity of ISO 800 or more. The ISO is `CaptureResult.SENSOR_SENSITIVITY`,
     read through Camera2 interop.
   - With neither known (no live outline and no ISO reported), Auto takes one photo.
2. **The burst.**
   - Four stills come from the same `ImageCapture` (`MAXIMIZE_QUALITY`), one after the other. The
     first goes into the file the scan asked for, the others beside it (`scan-…-b2.jpg` …).
   - The shutter's focus and metering step holds for 4 s, which covers the burst. No separate
     AE lock is set.
   - Auto-capture (#296) treats the burst as one capture.
   - The line under the viewfinder says *Hold still: merging four photos*. The shutter shows its
     spinner and stays off until the page is in the scan.
   - If a later frame fails, the frames already taken are kept.
3. **The merge** (`ScanCameraRouter.merged`):
   - The page is found on each frame with the still detector (`scan_find_document_with`, the
     ScanLab finder options). A frame without a page is left out.
   - With two or more found, `scan_merge_frames` writes `scan-…-merged.jpg` (JPEG, the ScanLab
     *snap to paper* option). Inside the call, the sharpest frame is the reference, blurred or
     misaligned frames are dropped, and the rest are averaged robustly.
4. **What the scan gets.**
   - **The merged page.** It is marked in the capture facts, so the scan does not search for its
     edges again: its corners are the whole picture (one line in `AppViewModel.onScanPhoto`).
     Look, upright and save then run as for any page.
   - **If too few pages were found or the merge failed**, the first photo goes to the scan as an
     ordinary photo (the issue: "keep the reference frame").
   - **Clean-up.** The extra photos are deleted, and after a merge so is the first one.
   - **Closing the camera during the merge** still adds the page.

The thresholds are the issue's starting values (`ScanMergeRules`).

## Testing and what is recorded (handover, section 3)

1. **Testing option**: *Low-light merge* Auto / Always / Off (the same setting as in Settings).
   *Always* lets the merge be tried in any light. *Off*, the default, is the camera as in #296.
2. **Diagnostics log** (`scan`):
   - `camera_merge` for every in-app photo:
     - `why`: `off`, `memory`, `bright`, `unknown`, `always`, `dark_luma` or `dark_iso`.
     - For one photo, also `iso` and `luma`.
     - For a burst, also `frames`, `found`, `merged`, `used`, `rejected_blur`, `rejected_align`,
       `psnr_gain_tenths_db`, `burst_ms`, `find_ms`, `merge_ms`, `fallback` (`none`,
       `few_found`, `failed`), `slow_burst` (over 2.5 s), and the native stages as `*_ms`.
   - `setting` (`auto`, `always`, `off`) and `chosen` (false while it is the default) on every
     `camera_merge` line (#426).
   - `camera_merge_option` (`merge`, and `default`: `off`) when the setting changes.
   - `burst`, `merged`, `merge_setting` and `merge_chosen` on each `camera` line.
   - `straightened` on the `photo` line.
3. **Kept scans**: `capture` in the page file adds `merge` (the burst's facts above, or `null` for
   one photo), and since #426:
   - `merge_setting` and `merge_chosen` (`null` and `false` for the phone's camera app and imports);
   - `photos_kept`: false for a merged page, whose photos were deleted and whose crop is the
     detector's. Such a page is not a raw photo and should not be used as ground truth (#425).

## What was verified, and what was not

**Verified:**
- It compiles.
- `ScanLowLightTest` passes. It covers:
  - Off and small memory;
  - Always;
  - Auto at the luma and ISO limits;
  - unknown light;
  - the setting's ids;
  - the burst's file names;
  - leaving out frames with no page or bad corners;
  - fewer than two left;
  - what is kept and deleted after a merge and after a failure;
  - the recorded fields;
  - the default is Off, also for a stored value it does not know, and Off takes one photo (#426);
  - `merge_setting`, `merge_chosen` and `photos_kept` in the log and the page file (#426).
- The merge's own measurements on drawn frames are in #389: 3.9 to 4.1 dB less noise, and blurred
  and shifted frames are rejected.

**Not verified (no phone):** all of the issue's acceptance items, which are:
- the noise in a dim room against a single shot;
- the recognition check on a dim page;
- 4 s per page.

Also unmeasured:
- how long four `MAXIMIZE_QUALITY` stills take (the log's `burst_ms` and `slow_burst` will say;
  `MINIMIZE_LATENCY` is not tried);
- whether the exposure stays the same across the burst without an AE lock;
- the time to find the page four times at 12 MP;
- memory at 12 MP;
- whether processing an already-straightened page again (whole-picture corners, the page's
  proportions, snap to paper) leaves it unchanged.

**Left out:** the issue's experimental glare mode (section 4). It is off by default in the issue
and needs glossy paper on a phone to judge.

## Where the code is

- `ui/scan/ScanLowLight.kt`: the decisions, files and facts.
- `ui/scan/ScanCameraScreen.kt`: the burst, and the ISO from the preview.
- `ui/scan/ScanCameraRouter.kt`: find, merge and hand over.
- `ScanCameraModel`: the setting, the memory class and the merging state.
- `ScanCameraSetting.kt`: the setting row.
- Strings: `res/values*/strings_scan_merge.xml`.
