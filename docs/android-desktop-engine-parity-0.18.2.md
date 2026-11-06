# PdfCraft desktop engine → Android parity audit

Audit date: **2026-11-06**. Original source: [storytold/pdfcraft](https://github.com/storytold/pdfcraft). Last stable release: **v0.18.2**, commit `7faa4f35cefb0dea04137ec475bdc770945c22cd` (2026-11-05). Upstream main as inspected: `a0811650dec177caf06a1b143a6cc7590c85dff6` (2026-11-06, **not a stable release**). Android fork's starting main: `6b95363fa61afbeebfbb4ed97211753a9f2d72e4`, Rust workspace **0.14.0** plus selected later backports.

**Goal:** keep all mobile-suitable **engine behavior** in sync with released original PdfCraft, not `egui` desktop UI. The Android Jetpack Compose UI calls Rust through `MobileSession`, UniFFI 0.32.2, and the headless automation tool table. A shared name is **not** proof of equivalent behavior.

## Headless tool parity at v0.18.2

Parsing the 189 `t("tool_name", ...)` entries of upstream `crates/automation/src/tools.rs` against Android's 180:
- **179 shared names**. Of those, **150 table definitions appear unchanged** ignoring whitespace, and **29 definitions differ** (schemas, labels, or descriptions; review implementations separately).
- **10 released tools absent on Android**; **1 Android-only** command, `photo_find_document`, should be preserved.
- **6 additional commands only on the inspected upstream `main`** (unreleased; keep on watchlist).

| Missing in Android at stable v0.18.2 | Fit on Android | Migration |
| --- | --- | --- |
| `bookmark_export`, `bookmark_import`, `bookmark_copy` | **Yes, high** | Port `automation/bookmark_io.rs` with SAF input/output picking, page mapping, password and path tests. |
| `text_styles`, `bookmark_from_text_styles` | **Yes, high/medium** | Port engine text-style grouping, preview/progress and cancel support; limit work on scanned/huge PDFs. |
| `doc_backdrops` | **Yes, medium** | Read-only page background diagnosis; test scans, full-page images, and layers. |
| `text_script` | **Yes, medium** | Port text-edit script case handling and font fallback, then surface in mobile text formatting. |
| `doc_bates_multi` | **Yes, advanced** | Ordered multi-PDF batch operation via SAF/working copies; confirmation, per-file failures, no overwrite. |
| `model_add`, `model_render` | **Defer** | PRC/U3D/3D rendering would bring `pdfcraft-prc` and `pdfcraft-prc-render`, heavy CPU/RAM and additional ABI tests; not part of general PDF reading/editing MVP. |

After v0.18.2 upstream main adds `page_enhance_scan`, `comments_copy`, `comments_paste`, `ocr_words`, `ocr_review_apply`, `ocr_dictionary`. Mobile candidates (especially scan/OCR and comments), but **do not mark released parity without their versioned tests**.

## Underlying Rust engine gap

The crate tree itself differs substantially even where tools match. Stable `crates/engine` has ~71 files with a modularized `session/{editing,save,files,view}`, while Android's engine still has ~42 files and a monolithic `lib.rs`. The stable `crates/automation` has ~50 files vs Android's ~29; refactors account for some files and **are not themselves missing features**. Stable adds `crates/prc` and `crates/prc-render`, both out-of-scope for now. `crates/ui-egui` (~281 files) is desktop only and must **not** be copied to Android. Many `vendor/hayro-*`, `Cargo.lock`, Rust crate APIs and feature flags changed: raw directory swaps mix incompatible code.

Priority discrepancy categories:
1. **P0 document safety**: image decode bounds, parser/repair/xref, ink/masks and color, form calculations, XFA scripts, redaction (no leaked old content), save/reopen, signatures, incremental revisions, malformed PDFs.
2. **P1 Android-useful features**: page/annotation workflows, bookmarks, search and OCR, text editing, exports (Markdown, clean HTML, CSV), scan cleanup, optimizer, batch operations via SAF.
3. **P2 opt-in**: Bates across many files, advanced form scripting, 3D/PRC preview (only with memory/devices benchmarked). 
4. **Skip** desktop: egui, desktop window/dialog state, hotbar/trackpad/wheel, macOS/Windows/FreeBSD printing and packaging, Flatpak/CUPS, desktop update clients, native desktop filesystem watching. Keep Android equivalents instead.

## Current incremental progress

- Previous merged #2: unsigned page deletes full-save, malformed negative trailer `/Size`, Android page/tile buffer limits.
- Previous merged #3: bounded vendored JBIG2 decoder with Rust and Android tests.
- Previous merged #4: Rust/Android and both ARM ABI APK smoke merge gate; signed release remains separate.
- Open #5: Android working-copy recovery when content-provider write-back fails.
- This PR: upstream security/correctness backports `try_oid` sign/empty arc rejection ([#5581](https://github.com/storytold/pdfcraft/pull/5581)) and valid finite image `/Decode` pair check ([#5678](https://github.com/storytold/pdfcraft/pull/5678)); add unit regressions for sign and create.

## Versioned migration checkpoints

1. **0.18.2 engine foundation**. Update `Cargo.toml`, Cargo.lock, and all transitive core crates/vendor patches as a **coherent snapshot**, pin immutable upstream SHA, then test original PDF fixtures. **Do not** publish a release as engine 0.18.2 until this succeeds.
2. **Rendering + editing**. Test viewer/text extraction/CMYK/soft masks/JBIG2/fax, undo/redo and save/reopen on real and malformed files, before exposing newer automation commands.
3. **Mobile-capable new commands**. Port the seven bookmark/text/background commands first, then advanced Bates, preserving the Android-only scan helper. Regenerate UniFFI Kotlin bindings with exactly the native `.so` used in each APK.
4. **Release gates**. Test Rust unit/property suites; Android JVM; each ABI release-mode native compile; emulator instrumentation for startup and PDF workflows; 16 KB page alignment; OCR model availability; SAF revoke, partial write, out-of-space recovery; signed/encrypted PDF and low memory. Pin APK `applicationId`, versionCode progression, and production signing certificate.
5. **Post-release sync**. Review commits added after `v0.18.2` only against a *new stable release*; create separate small PRs, never automatically merge upstream `main`.

This report is a **source-level audit**, not proof of full runtime or feature parity. Current Android native build must continue to identify itself with its real underlying code version until the coherent core upgrade is verified.

## Implementation log — 2026-11-06

- Work started against `sync/storytold-0182-android-safe`; upstream target remains immutable stable `7faa4f35cefb0dea04137ec475bdc770945c22cd`.
- Existing work preserved: [#5](https://github.com/MohamedRef/pdfcraft-android/pull/5) working-copy recovery, [#6](https://github.com/MohamedRef/pdfcraft-android/pull/6) validation backports/audit, and [#7](https://github.com/MohamedRef/pdfcraft-android/pull/7) forms/XFA safety. At inspection, #6 and #7 Rust, Android JVM and both ARM ABI APK smoke checks passed; signed release jobs were skipped. These checks establish their respective small changes, not full engine parity.
- In progress: inspect repository instructions, pin and compare the stable core snapshot with Android adaptations, then implement and test coherent migration increments. Each delivered increment will update this file with changes, commands/results, unresolved gates and the next action. No release/version parity claim yet.

### Foundation source import (implementation branch)

- Imported all 32 existing mobile engine/support crates and all nine existing patched vendor crates from stable `7faa4f35cefb0dea04137ec475bdc770945c22cd` together. Source inventory and adaptation list: `docs/android-engine-source.json`. This replaces the old monolithic engine/automation internals with the released modular implementation; desktop UI/apps and PRC rendering crates remain excluded.
- Preserved Android `MobileSession`, UniFFI **0.32.2**, Compose UI, package/signing identity and native pixel caps. Preserved Android-only `photo_find_document` and its EXIF tests; its automation input now uses the stable bounded file reader. Preserved the 256 KiB bounded XMP metadata API used by File Details.
- Stable source includes the earlier save, OID/image validation, JBIG2 and forms/XFA fixes. PR #7 remains separate; this import must not be described as merging it.
- The stable tool implementations include the eight previously absent non-3D tools, including advanced Bates. Their availability in a headless table does **not** establish a usable SAF/Compose workflow. Mobile UI integration and runtime gates remain pending.
- Local verification host: Apple Silicon macOS, installed Rust **1.99.0** (matching `rust-toolchain.toml`; an existing environment override selected 1.92.0, so local commands explicitly use `cargo +1.99.0`). No Android SDK was found at the usual local path. Dependency resolution/native boundary compile is in progress; Android ABI/JVM checks will run in PR CI. No test success is claimed at this checkpoint.
- Workspace/native version remains **0.14.0** pending verification. No signed APK or release is published.

Implementation PR: [#8](https://github.com/MohamedRef/pdfcraft-android/pull/8), branch `sync/storytold-0182-engine-foundation`, initial source import `9ef4f12`. All source-import notes above refer to that **draft PR**, not code merged into this audit branch. The source inventory is available on the implementation branch.

### Native ABI compilation checkpoint: 2026-11-06 (CI triage)

- **Evidence:** [PR #8](https://github.com/MohamedRef/pdfcraft-android/pull/8), pinned import commit `9ef4f12a8f4650d34e9d4ed583ca1956e62735c4`, [Actions run 42202945010](https://github.com/MohamedRef/pdfcraft-android/actions/runs/42202945010), `rust-tests` job `127842471883` failed at `cargo test --locked` (exit 101).
- **Five concrete native wrapper compatibility errors:** (1) `pdfcraft_render::sound_wav` was removed; (2) `RenderConfig.hide_notes` was removed, but Android draws its own sticky-note icons; (3) `Annotation.modified_raw` was removed; (4) `Annotation.opacity` was removed; (5) `Annotation.width` was removed.
- **Safety requirement:** do not silence the errors by dropping voice comments or suppressing *all* comments. Preserve audio playback and the visual distinction between sticky notes and other rendered annotations. Use the new `Document::comment_props(page,index)` to retrieve opacity/line width, and use the released `Annotation.modified` date for display (note: this is formatted rather than the former raw timestamp). Regression tests should cover voice WAV, comment styling, sticky-note visibility, and release-mode ABI builds.
- **Work split:** solve the above as a dedicated mobile API-compatibility increment, isolated from the upstream source snapshot to avoid mixing dependency changes with frontend adapters. A Rust compilation success alone does not prove full Android runtime stability. Only the two confirmed Rust sub-jobs from older #6/#7 are green; do not call #8 successful until every required current check is green.
- **Next verification:** native wrapper `cargo test --locked -p pdfcraft-android`, Rust crate suites including import changes, Android JVM tests, both ARM ABI APK smoke jobs, and emulator SAF/save/reopen/low-memory tests. Keep #8 draft and retain workspace version `0.14.0` until these succeed.
