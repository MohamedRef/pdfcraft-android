# Scanner: handover

State of the document scanner on 12 November 2026, for whoever continues it. The plan, the
priorities and the rules are in the tracking issue, #286; this file says where the work stands,
what is left, and what was learned on the way. **Keep it current: a pull request that changes
the scanner's state changes this file in the same pull request.**

The owner's goal, in his order: very precise page edges and a flat, correctly proportioned page;
a one-tap filter that makes a photo look like a clean scan; high quality from capture to PDF.
The app is for his own use. He will test on his phone over a long period once the features are
in, with the testing options and kept scans described below. **Nothing in the scanner has run on
a phone yet.** Every number in the scanner's documents was measured on a desktop.

## 1. Where each issue stands

| Issue | What | State | Merged in | What is left |
|---|---|---|---|---|
| #287 | `pdfcraft-scan` crate, one pass from photo to page | Done, closed | #331, #346 | |
| #288 | Measuring: harness, drawn pages, test set | Done in code | #346, #367, #382, #386, #394, #404, #414, #434 | The SmartDoc 2015 script is in (#414) but has never been run on the real dataset; the owner's own test set. On a kept-scans export, `scan-eval` leaves out merged pages, book halves, discarded scans and pages whose corners nobody checked (`"checked": true` in the page file), and counts them in its report (#425, fixed in #434). See [ANDROID_SCAN_MEASURING.md](ANDROID_SCAN_MEASURING.md) |
| #289 | Model files: pinned, loaded, exported, copied again when changed, packs ready, tensor helpers | Done | #360, #365, #370, #403, #410, #415 | Nothing. All three models stay in the app by the owner's choice; `docs/ANDROID_SCAN_MODELS.md` says how to make one a download |
| #290 | Page found by a network | Core done | #360 | Figures on the owner's set; phone time. Known: wrong and accepted on 2 of the 10 reference photos, nothing found on 2 |
| #291 | Edges refined at full size | Core done | #356, #291 PR | Corners off the photo (up to 15%) checked on drawn pages only; the owner's set; phone time |
| #292 | Crop screen: magnifier, side handles, snap, rotate | Done in code, not run on a phone | #381, #400 | Rotate is on the scan screen (#387, with #339's turns in `ScanSession` 2); the crop screen itself has no Rotate button, so the issue's bottom-row Rotate is not built. All of it on a phone (`docs/ANDROID_SCAN_CROP.md`) |
| #293 | True proportions, paper sizes | Core done | #353 | The focal length from the in-app camera (#294, section 7 there) |
| #294 | In-app camera (CameraX) | Done in code, not run on a phone | #390; #398, #405, #407, #408 and #413 build on it | Everything on the owner's phone (`ANDROID_SCAN_CAMERA.md`); the focal length from the camera's calibration for #293 |
| #295 | Live page outline, hints | Done in code, not run on a phone | #398, #405 | Detection rate, lag, flicker, heat and the hint thresholds on the phone; using the live outline when the still's detection fails (section 6 of the issue; `preview_quad` is recorded first so the gain can be judged). See `ANDROID_SCAN_LIVE.md` |
| #296 | Auto-capture, blur check | Done in code except the retake prompt, not run on a phone | #389, #407 | The retake prompt for blurred photos, left out on purpose: `scan_sharpness` does not detect motion blur and its threshold is from drawn pages, so real photos' recorded scores must set it first. Timings, thresholds and the issue's acceptance items on the phone (`docs/ANDROID_SCAN_CAPTURE.md`) |
| #297 | Multi-frame merge | Done in code, **off by default** (#426), not run on a phone | #389, #408, #431 | The setting is Off unless the person picks Auto or Always: a merged page enters the scan already cropped by the detector, with its photos deleted, so Crop cannot widen it (reason in `docs/ANDROID_SCAN_MERGE.md`). Left: keep the reference photo as the page and merge when the page is made, then decide the default on the owner's phone; noise, recognition and time per page on the phone. The experimental glare mode is left out |
| #298 | Auto look, part 1 | Core done | #346, #423 | Phone time. The paper White / Keep control is in since #423 |
| #299 | Auto look, part 2 | Done in code, not run on a phone | #373, #391, #421, #435, #437, #451 | Document types and their chip (#391); each kind's own tone, gentle sharpening inside the photo mask, the Flatten/Grayscale suggestion chips and the recognition-error check (#421). The book page's gutter rule is strict and the page measurements are kept with the straightened page (#435, for #427); barcodes, stamps and soft print are no longer taken for photographs (#437). One curved side no longer makes a Book page: it needs the gutter, two opposite curved sides or book mode, and a curved side suggests Flatten whatever the kind (#442, fixed in #451; testing option `strictBook`). All checked on drawn pages only. Left: the default sharpening is 0.13 point worse in character error rate on drawn pages, so whether it stays at 30 is the owner's call; the acceptance items on his photos (reference pairs, per-kind figures, halo crops, the confusion table from his kept scans) |
| #300 | Black and white, 1 bit | Core done | #359 | Speckle on textured grey fills; photographs wider than a quarter of the page are not dithered; 0.85 s for 12 megapixels on a desktop |
| #301 | Filter screen | Done in code, not run on a phone | #376, #380, #416, #423, #432 | Preview speed and sharpness, and save timings, on the phone. The strength slider in Clean scan with document types on was fixed in #432 (#424) |
| #302 | Flatten curved pages | Switch per page; automatic mode (off by default), its quality guard and border trim as testing options | #365, #385, #417, #436 | The guard keeps a flattened page only when at least 8 lines were followed on both pages (#436), or at least 4 when it reads half or less; it measures with the light evened out, and does not give a whole photo to the network (#466). On the owner's ten photos with the app's own corners the automatic mode flattens five pages, four clearly closer to the reference and none clearly worse (`ANDROID_SCAN_FLATTEN.md`). Left: turning the automatic mode on by default (#466); phone timings |
| #303 | Book mode | Parts A and B done in code, not run on a phone | #393, #413, #419 | Phone checks: 10 spreads in both orders; for part B, how many pages the network's guard rejects get flattened from their lines, and the time per page; `even_edges` only if photos show a gutter band (`ANDROID_SCAN_BOOK.md`, `ANDROID_SCAN_FLATTEN_LINES.md`) |
| #304 | "Max quality" (DocRes) | Feasibility in progress | #402, #420, #433 | The phone gate: time the DocRes file on the owner's phone with the testing option from #420 and share the log. That option (`scan_time_model`) reads only files inside the app's own folder, as every other scan call does (#429, fixed in #433). The authors' weights carry no licence, so nothing is bundled (`ANDROID_SCAN_MAX_QUALITY.md`) |
| #305, #306 | Research: moiré; fingers, glare | Detection only, reported in kept scans | #395, #397, #418 | Nothing acts on the reports: no page changes. The finger search was set on the owner's ten reference photos (it finds their three fingers and nothing else; #466) and `fill_fingers` removes them, but the app does not call it yet. Real moiré, glare and stains, and the cost on a 12 MP photo, are not measured (`ANDROID_SCAN_MOIRE.md`, `ANDROID_SCAN_DEFECTS.md`) |
| #307 | ML Kit as a second engine | **Dropped** | | The owner decided on 12 November 2026: nothing from Google in the app or in the scanner's work, so the app keeps no proprietary dependency and stays buildable by F-Droid. Do not add ML Kit or compare against Google's scanner |
| #424 | Strength slider in Clean scan with document types on | Fixed in code, not run on a phone | #432 | The slider's strength now wins over the document type's, the slider starts from the type's, and the sharpening option scales the type's. Testing option `pageStrength` (off is the old rule). How it looks on the owner's photos and phone |
| #444 | A page whose outline was not found was measured for its kind with the table in it | Fixed in code, not run on a phone | #450 | Until it is cropped, such a page is a Document at the look's own strength, and `kind_source` / `document_type_source` say `no_outline`. Testing option `unfoundDocument` (off is the old rule). Not done: the scan screen does not say that the page's edges were not found; a restored session counts a book half of such a photo as measured. Checked with unit tests only, not on the owner's photos or phone |
| #455, #457 | Screens one job each, in the order camera, adjust borders, enhance, document; first the camera: modes (One page, Many pages, Book), an icon top bar, Import / shutter / last page always in place, grid, zoom, permission page | Camera (#457) done in code, not run on a phone; #458, #459, #460 to come, #461 waits on the owner | #469 | Testing option `cameraBar` ("Camera modes, icon bar and zoom"; off is the old bars). Log `camera_mode`, `camera_zoom`, `camera_grid`, and `mode`/`zoom`/`grid` on each `camera` line; the kept page file's `capture` has the same. Until #458 lands, One page closes the camera after one photo and shows today's scan screen; the last page's thumbnail opens that screen too. The Book chip on the scan screen stays until #459 replaces that screen. Not checked: that the outline follows the page while zoomed, the layouts in the seven languages on a real screen, TalkBack |
| #445 | Scan log: crop lines counted pages from 0; a failed model timing gave no reason | Fixed in code | #464 | `crop`, `crop_snap` and `crop_detect` now carry `index` from 1. A failed timing writes `model_timing_failed` with `reason`, `stage`, `ms` and `native_ms` (new native call `scan_time_model_outcome`). Not run on a phone |
| #443 | Auto-detect on the crop screen says nothing when it finds nothing | Fixed in code, not run on a phone | #449 | A line under the photo (read by TalkBack) says when nothing was found; a search that already found nothing on the photo, at import or on the screen, is not made again: the next press tries the line detector, then presses only say the photo was already searched. Testing option `cropDetectNote` (off is the old behaviour). Log: `finder`, `other`, `skipped`, `note` on `crop_detect`; `detects_other`, `detects_skipped` in `crop`. How often the line detector finds what the network missed, on the owner's photos (`ANDROID_SCAN_CROP.md`) |
| #465 | Every page of a scan made in the background | Done in code, not run on a phone | this PR | A queue makes every page as it joins, the page on screen first; the strip shows results with a mark while waiting and "Preparing pages: n of m"; a page opened again shows at once; Apply to all carries the document type and Flatten page; Save keeps each page's shown turn and document type. Testing option `backgroundPages` (off is the old screen). Log `page_ready`, `scan_ready`, `preview_kept`; kept scan `made_before_shown`, `shown_kept`. Left: time and memory with nine 12 MP pages on the phone, and whether two pages at once is worth it; checked with unit tests only (`ANDROID_SCAN_PREVIEW.md`) |
| #456 | Back on the scan screen threw the whole scan away without asking; Delete had no way back | Fixed in code, not run on a phone | this PR | With pages, the back arrow and Android's Back ask "Discard this scan?" ("N pages will be deleted"; Keep scanning is the safe answer, and tapping outside keeps the scan); with none they leave at once. Delete asks "Delete page N?" and the photo is deleted only on Delete (the issue's other choice, Undo, was not taken: asking needs no photo kept on the side). Testing option `askDiscard` (off is the old behaviour), log lines `discard` and `page_delete`, kept-scan field `discard`. Checked with unit tests only (the rule, the option, the log fields and the page file); the dialogs have not been seen on a screen |
| #339 | Pages turned upright | Done in code, not run on a phone | #370, #387 | The chip, Rotate, the multi-page rule and the setting are in. Acceptance on the owner's test set per script, and phone timing; the neighbour rule's 0.95 bar may seldom act with this network's confidences (`ANDROID_SCAN_UPRIGHT.md`) |
| #446 | Clean scan preview: 200 to 400 ms per strength or paper change on the phone | Done in code, not run on a phone | #454 | The light estimate (about 80 % of a change on a desktop) is kept with the straightened preview page, so a change runs the tone curve alone: 175–202 ms down to 20–24 ms per change on a desktop, same bytes (`docs/performance/scan-clean-look-reuse.md`). Testing option `lookReuse` (off is the old way); log `preview_look_light_ms` … `preview_look_tone_ms`, `light_reused`; page file `preview.light_reused`, `preview.look_parts_ms`. Left: the phone's figures in the next log; Black and white (255 ms) is unchanged |

"Core done" means the code and its tests are merged and the issue's acceptance items that need
the owner's phone or his test set are open. Each issue has a comment saying the same in detail.

### Blocks already written for the open issues

Before starting an item in the table, look here: part of it may exist.

- **In the crate, tested, and now wired in** ([ANDROID_SCAN_BLOCKS.md](ANDROID_SCAN_BLOCKS.md),
  one row per module with what its tests measured and its known gaps). All eleven blocks from
  #382 are called today:
  - `metrics_geometry`, `metrics_tone`, `metrics_compare`: by `eval.rs`, the `scan-eval` runner
    and the CI gate (#394, #404). Not by the app.
  - `straightness`: by `page.rs` for #302's automatic mode (#385) and by `flatten_lines.rs`
    (#419); also by `eval.rs`.
  - `flatten_guard`: by `page.rs` (`compare_flattened`, #385; `trim_flattened`, #417).
  - `preset` and `photo_mask`: by `page_kind.rs`, the document types (#391). The mask is also
    used inside sharpening, in `enhance::auto_with_photos` (#421).
  - `book`: by `src/scan_book.rs` (`book::split_photo`, #393).
  - `sharpness` and `merge`: by `capture.rs` (#389), called from the in-app camera (#407, #408).
  - `upright_pages`: by `page.rs` (`total_turn`) and `src/scan.rs` (`settle`, `followed`), #387.

  Outside those eleven: the research modules `moire` and `defects` only report in kept scans
  (#418) and change no page; the DocRes groundwork (`enhance/learned.rs`, #402) is called by nothing.
- **Parked, never compiled or reviewed** (branch `draft/scan-app-blocks`, folder
  `docs/scan-blocks/`, whose README says what state each file is in): Kotlin drafts for #301,
  #295, #307 and the pure half of #294, a second drawn-photo generator for #288 (superseded by
  `synth.rs` in the crate), and research notes for #304, #305 and #306. Take what is useful from there and
  review it as new code; that branch is not for merging.

These came from one run of separate workers on 12 November that the owner stopped part-way.
Every threshold in them is the issue's starting value or was set on pages drawn in code.

One document per feature says how it works, what was measured and what was not:
`ANDROID_SCAN_AUTO.md`, `_AUTO_LOOK.md`, `_BLACK_WHITE.md`, `_CAPTURE.md`, `_DETAIL.md`, `_DETECTOR.md`, `_EDGES.md`,
`_CAMERA.md`, `_FLATTEN.md`, `_LIVE.md`, `_MEASURING.md`, `_MERGE.md`, `_MODELS.md`, `_PREVIEW.md` (with #446's kept light estimate), `_PROPORTIONS.md`, `_TESTING.md`, `_UPRIGHT.md` (all
`docs/ANDROID_SCAN_…`). Read the one for a feature before changing it.

## 2. How a scan goes through the code today

```
ScanCameraRouter: PdfCraft's camera screen (ui/scan, #294) or the phone's camera app
                                                     ->  cacheDir/camera/scan-*.jpg
AppViewModel.onScanPhoto
    prepareOcrModels            copy new or changed models into filesDir/models, set PDFCRAFT_MODELS
    scan_find_document_with     decode, detect (detect.rs), refine edges (refine.rs)
ScanScreen / ScanCrop (ui/ToolScreens.kt)   thumbnail with the outline; four corner handles
AppViewModel.saveScan, per page
    scan_process_page           page.rs: page_base, then page_look, then the encoder
        page_base               decode -> proportions (aspect.rs) -> flatten (flatten.rs) or
                                rectify (warp.rs) -> upright (upright.rs)
        page_look               look.rs: Auto (enhance/: light, grain, sharpen, tone),
                                black and white (enhance/bw.rs), the engine's other looks
    doc_create from the page images, then text recognition, then the PDF is saved
```

- **Native code**: `apps/pdfcraft-android/scan` (crate `pdfcraft-scan`, about 7 900 lines with
  tests) and its UniFFI layer `apps/pdfcraft-android/src/scan.rs`: `scan_set_root`,
  `scan_find_document`, `scan_find_document_with`, `scan_refine_quad`, `scan_process_page`,
  `scan_preview_page`, `scan_release_previews`, `scan_can_flatten`, `scan_sharpness`,
  `scan_merge_frames` (the in-app camera calls the last two, #407 and #408). Kotlin bindings are generated
  at build time; nothing generated is committed.
- **Kotlin**: `ui/AppViewModel.kt` (`onScanPhoto`, `saveScan`, `discardScan`),
  `ui/ToolScreens.kt` (`ScanScreen`, `ScanCrop`, `ScanPage`, `ScanFilter`), `ui/ScanSession.kt`,
  `ui/ScanSave.kt`, `ui/ScanDiagnostics.kt`, and the testing side: `ui/ScanLab.kt`,
  `ui/ScanLabController.kt`, `ui/ScanTestStore.kt`, `ui/home/ScanLabSettings.kt`.
- **Models** (`assets/models/`, pinned by size and SHA-256 in `scan/src/models.rs`, run on
  `rten`; copied out of the APK by `engine/ModelFiles.kt`, listed in Settings → Scanner models): `scan-edges.onnx` 6.7 MB, `scan-flatten.onnx` 15.8 MB, `scan-upright.onnx` 3.4 MB.
  `tools/scan-models/README.md` says where each is from and how it was made, to the byte.
- **Looks on screen**: the chips are `ScanFilter` (Original, Clean scan = Auto, Auto color,
  Grayscale, Whiteboard, Light text = black and white), one look for the whole scan, plus the
  Flatten page switch. The screen shows the camera's thumbnail, not the result.

## 3. The owner's way of testing, and what must stay true for it

`docs/ANDROID_SCAN_TESTING.md` is the full description. In short, under Settings → About →
Diagnostics, while the log is recorded:

- **A testing option per scanner feature** (`ScanLab`): which detector, edge refinement,
  proportions, paper matching, upright, sharpening, smoothing, one strength for all filters.
- **Keep scans for testing**: each page's camera photo, the corners as he left them, what the
  app found, the look and options in force, and the page the app made. Shared as a zip with the
  diagnostics log in it.
- On a desktop, `cargo run --release -p pdfcraft-scan --example scan_replay -- <folder>` runs
  an unzipped export through the current build.

He asked for this in these words: every feature goes in, each with a debugging option, and logs
that can be pulled out of the app. So **every new scanner feature needs three things**:

1. a testing option that switches it off or puts back what it replaced (a field in `ScanLab`,
   a row in `ScanLabSettings`, strings in all seven languages);
2. what it decided, in the `page` or `photo` line of the diagnostics log (numbers, booleans and
   short lowercase words only: the log never holds a file name or document content) and in
   `docs/ANDROID_DIAGNOSTICS_LOG.md`;
3. the same in a kept scan's page file (`scanTestPageJson`), so `scan_replay` can repeat it.

## 4. What is left, in a workable order

The order in #286 stands. Within it, this is what unblocks the most:

1. **#301, the screen for the preview.** Done in #380: the scan screen shows the preview, with
   Result and Photo to switch. What follows is how it was meant; the rest of #301 is still to do.
   The native call exists (#376,
   `docs/ANDROID_SCAN_PREVIEW.md`). Show its result on the scan screen in place of the
   thumbnail, with a way back to the photo and its outline (he needs to see the detected edges
   too). Build the request with the one function `saveScan` uses, so preview and save cannot
   drift apart. One preview at a time on a dispatcher of its own; latest wins. This makes every
   other option testable at a glance, which is why it comes first. Then the rest of #301.
2. **#292, the crop screen.** His first priority is precise edges, and on the reference photos
   the detector is wrong or finds nothing on four of ten: when it fails, this screen is the
   whole experience. Put it in a file of its own (`ui/scan/`) and leave `ScanPhoto` as
   `ScanScreen` uses it.
3. **#288, the metrics module**, `line_straightness` first, then **#302's automatic mode**.
   The measures and the guard are written (`straightness`, `flatten_guard`, `metrics_*`): what
   is left is the runner, the gate, and making both versions of the page at preview size.
4. **#303** (its block exists, section 1). #299's document types are in (#391:
   `ScanPageRequest.preset`, `page_kind.rs`) and #339's undo and rotate (#387:
   `ScanPageRequest.turns`, added with `upright_pages::total_turn`).
5. **#294 to #297, the camera.** The largest and riskiest part: declaring the CAMERA permission
   changes how the existing path behaves (the manifest's comment explains it), and none of it
   can be tried without a phone. Keep the phone's camera app reachable as a setting, as the
   issue says, so a fault in the new screen cannot leave him unable to scan.
6. #304 to #306. (#307 was dropped: nothing from Google.)

Parallel work is possible where the files do not overlap: #292 (new file), #294 (new files,
manifest, Gradle) and the Rust-only items can each go on their own branch.
What conflicts in practice: `ScanLab.kt` and `ScanLabTest.kt` (every feature adds a field),
`AppViewModel.saveScan`, `ScanScreen`, the seven `strings.xml`, `page.rs` and `src/scan.rs`.
Merge main into a branch before its last push, not after.

## 5. Rules, and why each is there

The list in #286 ("Rules for every scan PR") is the reference. The ones that have mattered:

- **Scan code goes in `apps/pdfcraft-android/scan` and the UniFFI layer, never in `crates/` or
  `vendor/`.** Those are pinned to upstream v0.20.0;
  `python3 scripts/verify_android_engine_source.py` fails the build otherwise.
- **Merge only when every check on the exact head commit is green** (#206), by squash, with the
  title `<title> (#n)`. A pull request is opened for every change; nothing is pushed to main.
- **Say what was measured and what was not**, in the pull request and in the feature's
  document. Twice in this work a first figure turned out wrong on a second look (section 7);
  both were corrected in the open. A number nobody can reproduce is worth less than "not
  measured".
- **The owner's photos never enter the repository**: not the photos, not anything made from
  them (aligned copies, outputs, contact sheets, overlays), not in issues or pull requests
  either. They are other people's documents as well as his. Describe them in words.
- **Nothing is taken from the CamScanner app, and its product names stay out of the app's text
  and identifiers.** Its results on his photos are compared as an ordinary user would compare
  them.
- **Every model is a separate pinned file with its notice**: `MODELS` in `models.rs`, an entry
  in `assets/models/ATTRIBUTION.txt` (a test checks both), its licence text beside it, a line on
  the Licences screen (`AppRoot.kt`), and its export in `tools/scan-models/README.md`.
- **Text people read comes from `strings.xml`, in English and the six translations.** Three
  JVM tests enforce it: `UiTextTest` (no English in code), `CopyConsistencyTest` (US spelling;
  no "engine", "native", "JSON", "OCR", "picture", "..." in English text),
  `TranslationPlaceholdersTest` (the same `%1$s` in every language).
- **No release unless the owner asks.** `AGENTS.md` has the build-number and release-note rules.

## 6. Building, testing, measuring

- Rust: `cargo test --locked -p pdfcraft-scan -p pdfcraft-android` (about 300 and 90 tests;
  the scan crate's take a little over a minute, most of it the CI gate of #288, which fails a
  change that makes the scanner worse on 40 drawn photos: see `ANDROID_SCAN_MEASURING.md`). `cargo clippy -p pdfcraft-scan --all-targets --no-deps` must add no
  warning; CI runs neither clippy nor rustfmt, so run both. `cargo fmt -p pdfcraft-scan
  -p pdfcraft-android` also reformats files this work does not own (`src/eraser_tests.rs`,
  `pressure_ink_tests.rs`, `progressive_tests.rs`, `tests.rs`, `scan/src/tests.rs`,
  `examples/scan_sharpness.rs`): put those back before committing.
- The scan crate denies clippy's `unwrap_used`, `expect_used`, `panic`, `todo`,
  `unimplemented` and `unreachable`, and `unsafe`; the workspace denies `cognitive_complexity`,
  `too_many_arguments` and `wildcard_imports`. Only a local clippy run shows a breach.
- Kotlin: CI's "Android JVM and UI tests" job is the compiler and the unit tests. The machine
  this was written on had no Android SDK; every Kotlin change here was checked by CI alone.
- CI on every pull request: "Rust engine and provenance" (about 15 minutes), "Android JVM and
  UI tests" (about 8), "Merge gate", "Conventional title".
- Measuring on drawn photos or a test set: `scan-eval` ([ANDROID_SCAN_MEASURING.md](ANDROID_SCAN_MEASURING.md)).
- Measuring against a reference scanner: `tools/scan-eval/README.md` (`reference_pairs.py`,
  with `scan_reference look`). Measuring orientation, flattening and detection on the same
  pairs: `scan_reference upright | flatten | detect`.
- Drawn test pages with real print (English, Arabic, a form): `tools/scan-eval/text_pages.py`;
  the three it makes are in `apps/pdfcraft-android/scan/tests/data/`.
- **A changed model is copied again on update** (#289): the build lists each model's size and
  SHA-256 in `models.index`, and `engine/ModelFiles.kt` copies a file whose line differs from the
  one recorded at the last copy. A model can keep its file name when it is replaced.
  `docs/ANDROID_SCAN_MODELS.md` says how, and how to see each model's state.
- The three scan models add 25.9 MB to the APK. #289 sets about 25 MB as the limit before
  models become optional downloads; the flattening model (15.8 MB) is the candidate. The owner
  chose on 12 November 2026 to keep all three bundled for now. The download code is in
  (`engine/ModelDownload.kt`); `docs/ANDROID_SCAN_MODELS.md` says how to make a model a pack.

## 7. Learned the hard way

- **One look at a whole page tells a classifier little.** The orientation network was right on
  32 of 40 pages when shown the page shrunk to its 224 pixels, and on 40 of 40 when shown nine
  tiles at a size where print is legible, voting. The same may hold for any small-input network
  used on a page.
- **A network must be shown what it was trained on.** UVDoc given the bounding box of a page
  lying at an angle was 11 % of the diagonal off; given the perspective-straightened page with
  a margin, 1.3–1.5 %.
- **Flattening hurts flat pages** by up to 2 % of the diagonal, and nothing measured so far
  tells a flat page from a bent one (the grid's own bend is 0.34–1.30 % on all ten reference
  photos). Hence a switch, not an automatic mode.
- **Sharpening does not bring a page closer to the reference** at any setting; it makes edges
  crisper than the reference's and costs a little faint ink. Before the tone curve is slightly
  better than after it.
- **A preview made small is not the saved page made small.** At 1280 pixels the looks are 1.4
  to 2.4 grey levels apart, black and white 5.1; at 640, Auto is 4.8 apart.
- **Measures of geometry against the reference are unsteady on bent pages**: the same pipeline
  gave 3.5 % and 1.5 % on one photo in two runs, because the fit depends on which points happen
  to match. Run such a measure twice before quoting it.
- **Two figures were first reported wrong and then corrected**: that flattening improved all
  three bent reference pages (it clearly improves two), and that the whole-page orientation
  look was right on 25 of 40 (32; the first count did not know that two of the reference
  results are themselves sideways). Both came from quoting a number before checking what it was
  measured against.
- **Hugging Face could not be reached** from the build machine. The orientation model is the
  same PaddleClas network taken from the `rapid-orientation` wheel on PyPI; whether it is
  byte-for-byte PaddlePaddle's own export was not checked. DocRes (#304) may meet the same wall.
- **PyTorch did not load** there either. UVDoc's ONNX file is written by hand from the
  checkpoint and checked against a NumPy forward pass (`tools/scan-models/export_uvdoc.py`).
- **`rten` 0.26** ran all three models as exported: depthwise convolutions, hard-swish,
  reflected padding, PReLU, a batch dimension left open, weights stored as 16-bit floats.
- Thresholds that came from ten photos and are waiting for more data: the detector's
  acceptance (0.5), the orientation vote (0.5 of the vote, half of the tiles), the grain floor
  (2 grey levels), the sharpening default (30 of 100).

## 8. What only the owner's photos can settle

The ten reference photos (each with what another scanner made of it) are not in the repository
and cannot be given to anyone else. Every figure "on the ten reference photos" in these
documents was measured on the machine that had them and cannot be repeated without them. For
new data the route is his kept scans (section 3).

Open until he has scanned for a while:

- how often no corner needs moving (#286's gate is 85 %), with which detector;
- whether the orientation thresholds hold on Arabic, Latin and handwriting separately;
- whether Flatten is worth an automatic mode, and what would trigger it;
- whether sharper than the reference looks better to him, and at which setting;
- how long each stage takes on his phone (the log records every stage);
- whether smoothing ever runs (none of the ten photos has enough grain) and what it does then.

## 9. Reviewing a scanner pull request

What the reviewer of this work will look for, so it is worth having ready:

- the issue it is part of, and where it departs from the issue's design and why;
- figures with what they were measured on, and a plain list of what was not verified;
- the testing option, the log fields and the kept-scan fields of section 3;
- tests that fail without the change; the JVM text tests of section 5 still pass;
- the feature's document, this file's table, and a comment on the issue, all saying the same;
- nothing from the owner's photos anywhere.
