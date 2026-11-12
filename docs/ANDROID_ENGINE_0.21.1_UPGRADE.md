# Android engine v0.21.1 upgrade

## Status — 2026-11-12

Target: immutable upstream `b503b2e62dfeec5598f344e4e287d9c9cbdb34fa` (`v0.21.1`, released 2026-11-12; `v0.21.0` came out the day before and is included), replacing pin `2ad7717f87ad6f15b14bdf22cddc1019d6fd0aed` (`v0.20.0`). No APK, version or signing change in this PR. Nothing here has run on a phone yet.

Upstream release notes: https://github.com/storytold/pdfcraft/releases/tag/v0.21.0 and https://github.com/storytold/pdfcraft/releases/tag/v0.21.1 .

## Method

Same as the v0.20.0 upgrade: a per-file three-way merge for every selected path that changed upstream between the two tags (base v0.20.0, ours the fork, theirs v0.21.1).

- Files equal to the base were replaced by v0.21.1.
- Android-adapted files were merged with `git merge-file`.
- The two 3D omissions (`automation/src/model3d.rs`, `model_render.rs`) stay absent. Upstream did not change them.
- `vendor/edit-session` (new upstream) is not taken: only `ui-egui` uses it.

| Result | Files |
|---|---|
| Added upstream | 74 |
| Updated (unmodified on Android) | 207 |
| Android adaptation merged cleanly | 37 |
| Hand-resolved conflicts | 23 |
| Deleted upstream | 0 |

Hand-resolved conflicts. In each, both sides are kept:

- `annot/src/modify.rs`, `erase_ink`: upstream now reads strokes through `ink_strokes` (at most 1,000,000 points) and caps densified eraser samples at 4,000,000 (storytold/pdfcraft#6001). The Android pressure-ink eraser keeps its `/PCWidths` factors, its reach to the painted edge (#77) and its skipping of strokes the eraser can't reach. It now counts the samples it densifies, final points included, against the same cap. Untouched strokes add no samples.
- `annot/src/info.rs`, `annot/src/lib.rs`, `organize/src/lib.rs`, `cos/src/lib.rs`, `render/src/inspect.rs`, `render/src/lib.rs`, `automation/src/a11y.rs`, `automation/src/a11y/form_tools.rs`: union of upstream's new exports (pop-ups, groups, `release_members`, `set_popup`, attachment descriptions, link areas, portfolios, `DECODE_PARMS_KEYS`) and Android's (`props_batch`, `annots_to_layer`, `MARGIN_KEY`, `page_margins`, `OpenPasses`, `sound_wav`, bounded attachments, open trace).
- `automation/src/documents.rs`, `doc_open`: upstream's open parameters (`file.pdf#nameddest=…`, storytold/pdfcraft#3425) before Android's bounded private-input read and open timings. The destination report and `open_timings` both go in the result.
- `automation/src/tools.rs`: upstream's `comment_popup` beside Android's `comment_erase`, `comments_edit_many` and `comments_to_layer`.
- `render/src/inspect/core.rs`: upstream's shared field-text allowance (storytold/pdfcraft#6015) and layer order, inside Android's open-timing measurements.
- `engine/src/open.rs`: upstream's `with_highlight` beside Android's two-stage reading open.
- Shared immutable input (`PdfBytes` instead of `Arc<Vec<u8>>`): `cos/src/document/state.rs`, `engine/src/actions.rs`, `engine/src/ocr.rs`, `engine/src/session/files.rs`, `engine/src/sources.rs`, `automation/src/batch.rs` take upstream's new parameters (JavaScript preference, Office conversion, #5081 page-source checks) with Android's byte type.
- Two upstream tests adapted to Android behavior, same assertions: `the_eraser_densifies_within_a_total` erases across every segment of its zigzag, since the Android eraser densifies only the segments it can reach; `form_fill_cannot_print_but_js_run_can` reads the scripts' output from the `form_fill` result, where Android returns it.
- Edit history: the four new engine wordings (attachment description, open/close/move comment pop-up) are translated in `EditLabels.kt` and every `strings.xml`.
- Tests (`annot/src/tests.rs`, `engine/src/tests.rs`, `forms/src/tests.rs`, `automation/tests/it/main.rs`): both sides' tests kept. Upstream's tests use `PdfBytes` where they pass bytes by reference. `model_3d`/`model_render` test modules stay unregistered.

New adaptations, needed after the merge so the build compiles with the shared input type:

- `render/src/inspect/navigation.rs`: `named_destination` takes `PdfBytes`.
- `engine/src/find_replace/tests.rs`, `organize/src/tests.rs`: byte-slice comparison and owned copy in tests. Assertions unchanged.
- `apps/pdfcraft-android/src/lib.rs`: `page_marks` names upstream's new `MarkKind::PrinterMarks` as `printer_marks`. The app ignores names it doesn't know.

## Workspace and dependencies

- Android workspace members unchanged. Workspace version 0.21.1; internal crates at 0.21.0, matching upstream's manifest.
- `zeroize` workspace dependency added (crypt and sign wipe key material, storytold/pdfcraft#4427).
- `craft-print` and `craft-update` git revisions bumped to upstream's.
- `Cargo.lock`: only the craft-libs/winprint git revisions and internal versions change. Every new crate dependency (`rten-imageproc`, `skrifa`, `url`, `roxmltree`, `unicode-normalization`, `rustix`, `log`, `zeroize`) was already in the lock. All are at the version upstream pins, except `rustix`, which stays at the 1.1.5 Android already pinned (upstream: 1.1.6).

## Provenance

`scripts/verify_android_engine_source.py`: **1054 exact core/vendor files, 102 reviewed Android adaptations, 2 deliberate 3D omissions, 5 local additions.** The 99 earlier adaptations are kept. `automation/src/content.rs` is now byte-identical to upstream and counts as exact.

## Tools

The mobile table has 211 tools: the earlier 203 plus upstream's `attachment_edit`, `comment_popup`, `doc_convert_colours`, `doc_printer_marks`, `form_focus`, `js_event`, `printer_papers` and `text_replace`. They have no dedicated Android UI yet. `printer_papers` asks a desktop print system (CUPS), which Android doesn't have. The bridge regression `upgraded_tool_table_preserves_mobile_tools_and_adds_stable_021_schemas` checks them.

Office files (Word, Excel, PowerPoint) convert through LibreOffice upstream. LibreOffice isn't on Android, so that path reports that it isn't installed.

## Validation

- `cargo check --locked --workspace --all-targets` with the pinned 1.99.0: pass. One vendor warning (unused import in upstream `hayro-interpret/src/font/mod.rs`) comes from upstream.
- `cargo test` for the crates CI tests: see the PR.
- Still to do, as for every engine bump: a phone run of open timings on a large PDF, S Pen ink and eraser, SAF write-back, save and reopen in other viewers, and an install-over upgrade.
