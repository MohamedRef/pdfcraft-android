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
| #288 | Measuring: harness, drawn pages, test set | Part | #346, #367 | The synthetic generator, the metrics module (`line_straightness` first: #302 waits for it), the `scan-eval` runner, the CI gate, the SmartDoc script |
| #289 | Model files: pinned, loaded, exported | Part | #360, #365, #370 | `scan_model_states`, a Models list in settings, optional packs, copying a *changed* model on update (section 6) |
| #290 | Page found by a network | Core done | #360 | Figures on the owner's set; phone time. Known: wrong and accepted on 2 of the 10 reference photos, nothing found on 2 |
| #291 | Edges refined at full size | Core done | #356 | A corner outside the photo is not accepted; the owner's set; phone time |
| #292 | Crop screen: magnifier, side handles, snap, rotate | **Not started** | | All of it. `scan_refine_quad` is the native half of "snap" and exists |
| #293 | True proportions, paper sizes | Core done | #353 | The focal length from the in-app camera (#294, section 7 there) |
| #294 | In-app camera (CameraX) | **Not started** | | All of it |
| #295 | Live page outline, hints | **Not started** | | Needs #294 |
| #296 | Auto-capture, blur check | **Not started** | | Needs #295 |
| #297 | Multi-frame merge | **Not started** | | Needs #294 |
| #298 | Auto look, part 1 | Core done | #346 | Phone time; the paper White / Keep control has no screen (#301) |
| #299 | Auto look, part 2 | Part | #373 | The block photo mask, document-type presets and `scan_classify`, the recognition-error check |
| #300 | Black and white, 1 bit | Core done | #359 | Speckle on textured grey fills; photographs wider than a quarter of the page are not dithered; 0.85 s for 12 megapixels on a desktop |
| #301 | Filter screen | Preview on the scan screen | #376, #380 | A look per page, strength, looks strip, compare, apply to all, the session's new fields, cancel. Preview speed and sharpness on a phone |
| #302 | Flatten curved pages | Switch only | #365 | The automatic mode and its quality guard (need #288's `line_straightness`), border trim, a switch per page |
| #303 | Book mode | **Not started** | | All of it |
| #304 | "Max quality" (DocRes) | **Not started** | | Feasibility first, as the issue says |
| #305, #306 | Research: moiré; fingers, glare | **Not started** | | |
| #307 | ML Kit as a second engine | **Not started** | | Cannot be tried without a phone with Google Play services |
| #339 | Pages turned upright | Core done | #370 | The "Turned upright · Undo" chip, a rotate button, the multi-page rule, a switch in ordinary settings |

"Core done" means the code and its tests are merged and the issue's acceptance items that need
the owner's phone or his test set are open. Each issue has a comment saying the same in detail.

### Blocks already written for the open issues

Before starting an item in the table, look here: part of it may exist.

- **In the crate, tested, called by nothing yet** ([ANDROID_SCAN_BLOCKS.md](ANDROID_SCAN_BLOCKS.md),
  one row per module with what its tests measured and its known gaps): `metrics_geometry`,
  `metrics_tone`, `metrics_compare` and `straightness` for #288; `flatten_guard` for #302;
  `photo_mask` and `preset` for #299; `book` for #303 (part A's Rust); `merge` for #297;
  `sharpness` for #296; `upright_pages` for #339 (the multi-page rule, and a quarter turn by
  reordering the corners, which gives Rotate without a new field in `ScanPageRequest`). Wire a
  block in instead of writing it again; its issue stays open until it has its testing option,
  its log fields and its kept-scan fields (section 3).
- **Parked, never compiled or reviewed** (branch `draft/scan-app-blocks`, folder
  `docs/scan-blocks/`, whose README says what state each file is in): Kotlin drafts for #301,
  #295, #307 and the pure half of #294, the drawn-photo generator for #288 with one test still
  failing, and research notes for #304, #305 and #306. Take what is useful from there and
  review it as new code; that branch is not for merging.

These came from one run of separate workers on 12 November that the owner stopped part-way.
Every threshold in them is the issue's starting value or was set on pages drawn in code.

One document per feature says how it works, what was measured and what was not:
`ANDROID_SCAN_AUTO_LOOK.md`, `_BLACK_WHITE.md`, `_DETAIL.md`, `_DETECTOR.md`, `_EDGES.md`,
`_FLATTEN.md`, `_PREVIEW.md`, `_PROPORTIONS.md`, `_TESTING.md`, `_UPRIGHT.md` (all
`docs/ANDROID_SCAN_…`). Read the one for a feature before changing it.

## 2. How a scan goes through the code today

```
MainActivity (TakePicture: the phone's camera app)  ->  cacheDir/camera/scan-*.jpg
AppViewModel.onScanPhoto
    prepareOcrModels            unpack assets/models into filesDir/models, set PDFCRAFT_MODELS
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
  `scan_preview_page`, `scan_release_previews`, `scan_can_flatten`. Kotlin bindings are generated
  at build time; nothing generated is committed.
- **Kotlin**: `ui/AppViewModel.kt` (`onScanPhoto`, `saveScan`, `discardScan`),
  `ui/ToolScreens.kt` (`ScanScreen`, `ScanCrop`, `ScanPage`, `ScanFilter`), `ui/ScanSession.kt`,
  `ui/ScanSave.kt`, `ui/ScanDiagnostics.kt`, and the testing side: `ui/ScanLab.kt`,
  `ui/ScanLabController.kt`, `ui/ScanTestStore.kt`, `ui/home/ScanLabSettings.kt`.
- **Models** (`assets/models/`, pinned by size and SHA-256 in `scan/src/models.rs`, run on
  `rten`): `scan-edges.onnx` 6.7 MB, `scan-flatten.onnx` 15.8 MB, `scan-upright.onnx` 3.4 MB.
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
4. **#299's presets**, **#339's undo and rotate** (`upright_pages::turn_corners` turns a page by
   reordering its corners; `upright.rs` has `turned`), **#303**. The blocks exist for all
   three (section 1).
5. **#294 to #297, the camera.** The largest and riskiest part: declaring the CAMERA permission
   changes how the existing path behaves (the manifest's comment explains it), and none of it
   can be tried without a phone. Keep the phone's camera app reachable as a setting, as the
   issue says, so a fault in the new screen cannot leave him unable to scan.
6. #304 to #307.

Parallel work is possible where the files do not overlap: #292 (new file), #294 (new files,
manifest, Gradle), #307 (new files) and the Rust-only items can each go on their own branch.
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

- Rust: `cargo test --locked -p pdfcraft-scan -p pdfcraft-android` (about 90 tests each; a
  minute and a half). `cargo clippy -p pdfcraft-scan --all-targets --no-deps` must add no
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
- Measuring against a reference scanner: `tools/scan-eval/README.md` (`reference_pairs.py`,
  with `scan_reference look`). Measuring orientation, flattening and detection on the same
  pairs: `scan_reference upright | flatten | detect`.
- Drawn test pages with real print (English, Arabic, a form): `tools/scan-eval/text_pages.py`;
  the three it makes are in `apps/pdfcraft-android/scan/tests/data/`.
- **A model file that changes must change its name.** `Engine.prepareOcrModels` copies a model
  out of the APK only when no file of that name is there yet. A new model is unpacked after an
  update; a replaced one is not, and then fails its SHA-256 check and the feature is silently
  off. #289, section 3, is the proper fix and is not done.
- The three scan models add 25.9 MB to the APK. #289 sets about 25 MB as the limit before
  models become optional downloads; the flattening model (15.8 MB) is the candidate.

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
