# Scanner drafts: app side and research (not compiled, not reviewed)

**Do not merge this folder.** It is a parking place. The files here were written by separate
workers from the issue texts on a machine with no Kotlin compiler, and the run was stopped by
the owner before the work was reviewed or wired in. Nothing here has been built, run or read
through by a second pair of eyes. Take a file from here, move it to its real place, make it
compile, and review it as new code.

The Rust blocks from the same run are different: they were built and tested in the real crate
and are in their own pull request (`docs/ANDROID_SCAN_BLOCKS.md` there).

Real places: Kotlin sources go to
`apps/pdfcraft-android/android/app/src/main/java/io/github/artcraftandroid/pdfcraft/ui/scan/`,
their tests to the same path under `src/test/`, package `io.github.artcraftandroid.pdfcraft.ui.scan`.

## What is here

| Issue | Files (`kotlin/`) | State when the run stopped |
|---|---|---|
| #301 | `ScanPageLook.kt`, `ScanPreviewQueue.kt`, `ScanSessionLooks.kt` and their three tests | Finished by its worker. Pure Kotlin, no Android import. The worker walked every test's expected values through the code by hand. |
| #301 | `ScanPreviewRenderer.kt`, `ScanPreviewPane.kt`, `ScanLooksStrip.kt`, `ScanLookControls.kt`, `STRINGS-preview.md` | Finished by its worker, **without seeing the three files above** (they were not written yet): it used their names from the task text. Check the two against each other first. No tests. |
| #307 | `ScanEngineChoice.kt`, `ScanEngineCopy.kt`, `GoogleScanner.kt`, two tests, `STRINGS-google.md`, `GRADLE-google.md` | Finished by its worker. `GoogleScanner.kt` needs the dependency in `GRADLE-google.md`; its version (16.0.0) is from Google's guide and was not checked on Google's Maven repository. |
| #295 | `ScanViewfinderMath.kt`, `ScanOutlineTracker.kt`, `ScanOutlineOverlay.kt`, two tests, `STRINGS-outline.md` | Written, but the worker was stopped before its own read-through and wrote no report. Treat as a first draft. |
| #294 | `ScanCameraLogic.kt` and its test | The pure half only. The CameraX screen, the Gradle and manifest lines were not started. No report. |
| #288 | `unfinished/synth.rs`, `unfinished/tests_synth.rs` | The drawn-photo generator. Builds against the stand-in crate; 28 of its 29 tests pass. The failing one straightens the photo back and compares it with the clean page (the worker was still changing that test). Uses `crate::geom`, which in the real crate is `pdfcraft_geom::perspective`. |
| #304, #305, #306 | `research/304.md`, `305.md`, `306.md` | Written from public sources, every claim labelled found (with its address), worked out, or not known. Nothing was run. Nobody has checked the sources a second time. |

Not started at all: the crop screen (#292) and the auto-capture reducer (#296).

## Notes the workers left

### `ScanPageLook.kt`, `ScanPreviewQueue.kt`, `ScanSessionLooks.kt` (#301)

- A look is saved as `look=<id>;strength=<0-100>;keep=<0|1>;flatten=<id>;turns=<int>;upright=<0|1>`;
  `encodeLooks` writes one `look\t<index>\t<encoded>` line per page.
- The session header is **not** bumped: `ScanSession.decode` rejects any other header, so a bump
  would make an older build lose the session. Older builds ignore the new lines.
- `ofOldFilter` also maps `CleanScan` to Auto 60. `ScanSession` does not store the filter today,
  so sessions from older builds get the default look.
- The queue: `ask(key)` returns a ticket when a render should start; a new ask while one runs
  replaces the waiting key; `done(ticket, ok)` says whether to show the result and hands back the
  waiting key with `nextTicket`. Nothing is cancelled, because the native call cannot be.
- **Gap:** `keepPaper` and `userTurns` have nowhere to go in `ScanPageRequest`. `snapPaper` there
  means "match standard paper sizes", not paper colour. Turns can be given by reordering the
  corners (`upright_pages::turn_corners` in the Rust blocks).
- Wiring: `ScanSession.encode()` appends `encodeLooks(looks)` after the page lines; reading uses
  `decodeLooks(text, pages.size)` on the same text; `saveScan` takes the native look and strength
  from `ScanPageLook.nativeLook(look)` (Whiteboard is asked for as `colour` at 85) and
  `keepUpright = true` means `upright = false`.
- Log per page: `look`, `strength`, `keep_paper`, `flatten`, `user_turns`.

### `ScanPreviewRenderer.kt` and the three composables (#301)

- What the worker was least sure would compile, in its order: its use of the queue (written
  blind, see above); `detectTapGestures` with `tryAwaitRelease()` beside `detectTransformGestures`
  on a second `pointerInput` of the same node; `items(ScanLookKind.entries)`; the `semantics`
  setters `selected` and `stateDescription`.
- Flatten is three pills (Auto, On, Off), since a switch would hide Auto. The preview sends
  `flatten = false` for Auto: saving must do the same until #302 decides.
- **Rotate is applied to the bitmap in the renderer.** The save path does not turn the page, so
  until it does the preview shows a turn the saved page will not have.
- Double tap zooms to 2.5× the fitted page, pinch 1× to 4×. No larger render is asked for on zoom.
- The strength slider asks once per step with no 30 ms wait (the queue drops stale keys).
- 19 new strings and 5 existing ones reused: `STRINGS-preview.md`. English only: the six
  translations are not written.
- Wiring it suggested for `ScanScreen`: hold one `ScanPreviewRenderer(scope) { key -> request }`
  in `remember`, close it in `DisposableEffect`; ask in a `LaunchedEffect` keyed on page and
  look: target `main` at 1280 px, one target `tile:<look id>` at 200 px per look; keep the looks
  in a `mutableStateListOf<ScanPageLook>()`. Log per drawn preview: `preview_ms`, `reused`,
  `look`, `strength`, `target`, `quarter_turns`, `flattened`.

### Google's scanner as a second engine (#307)

- The worker read Google's guide: `play-services-mlkit-document-scanner:16.0.0`, Android 5 and
  later, 1.7 GB of memory or the start fails with `MlKitException` code `UNSUPPORTED`.
- Least sure of: whether `RESULT_FORMAT_JPEG` and `SCANNER_MODE_*` are members of
  `GmsDocumentScannerOptions` (the guide uses them unqualified); the class name
  `GmsDocumentScanner` and its package; the constant `MlKitException.UNSUPPORTED`; nullability of
  `getPages()` and `getImageUri()`.
- The setting is titled "Scanner", because "engine" may not appear in text a person reads.
- A partly copied result is never added to the scan. After an `UNSUPPORTED` start the setting
  goes back to the app's own scanner; other start failures open the app's camera with a notice.
- The launcher belongs to `MainActivity` (`StartIntentSenderForResult`) and is passed in.
- Not done: all wiring, the setting row, `ATTRIBUTION.toml`, the comparison document, the APK
  size check. Log: `engine`, `pages`, `ms`, `cancelled`, `play_services`.
- This sends the camera pages through Google Play services' scanner. It is off unless the
  owner turns the setting on.
