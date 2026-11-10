# Android engine v0.20.0 upgrade

## Status — 2026-11-10

Branch `engine/upstream-0.20.0`, [PR #65](https://github.com/MohamedRef/pdfcraft-android/pull/65), from main `6e424f2` (0.0.15-beta). Target: immutable upstream `2ad7717f87ad6f15b14bdf22cddc1019d6fd0aed` (`v0.20.0`, released 2026-11-09), replacing pin `8c572792d6760bdc903d6f9a032e3e38e8f71cb8` (`v0.19.0`). Published 0.0.15-beta (15012) remains on v0.19.0 until the next verified release. No APK, version or signing change in this PR.

## Method

Per-file three-way merge for every selected path that changed upstream between the two tags: base v0.19.0, ours the fork, theirs v0.20.0.

- Files equal to the base were replaced by v0.20.0.
- Android-adapted files were merged with `git merge-file`.
- The two 3D omissions (`automation/src/model3d.rs`, `model_render.rs`) stay absent.

| Result | Files |
|---|---|
| Added upstream | 66 |
| Updated (unmodified on Android) | 340 |
| Android adaptation merged cleanly | 18 |
| Hand-resolved conflicts | 3 |
| Merged despite NUL bytes (`annot/src/tests.rs`) | 1 |

Conflicts and resolutions:

- `crates/automation/src/tools.rs`:
  - `model_render` changed upstream (orbit options) and remains omitted on Android.
  - `comment_add` takes upstream's Thai text-box note and keeps the Android `sound` sentence.
- `crates/render/src/lib.rs` — re-export upstream `ReviewStates` and Android `sound_wav`.
- `crates/render/src/raster/tests.rs` — upstream's new trailing-data/encryption/endstream tests followed by the Android sticky-note test.

## Workspace and dependencies

- Android workspace members unchanged.
- Internal crates at 0.20.0, matching upstream's manifest:
  - `flate2` now uses the `zlib-rs` backend;
  - `regex` added for redaction patterns;
  - `craft-print` git revision bumped.
- `Cargo.lock` adds only `regex 1.13.1`, `regex-automata 0.4.18`, `icu_segmenter 2.3.0` (+data), `icu_locale_fallback 2.3.0` (+data) and `thread-priority 3.1.1`. All are at the exact versions pinned in the upstream v0.20.0 lock; no existing external version changed.

## Provenance

`scripts/verify_android_engine_source.py`: **1055 exact core/vendor files, 27 reviewed Android adaptations (same set as v0.19.0), 2 deliberate 3D omissions, 0 local additions.**

## Tools

The mobile table has 200 tools: upstream's 200, minus `model_add`/`model_render`, plus Android `photo_find_document`/`comment_erase`.

New upstream tools:
- `object_copy` / `object_paste`;
- `doc_fix_hairlines`;
- `text_prepare`;
- `accessibility_layout_table_to_blocks`.

All operate on page content or structure tags, not annotations, so the Sound/FileAttachment clipboard refusal is unaffected. They have no dedicated Android UI yet. The bridge regression `upgraded_tool_table_preserves_mobile_tools_and_adds_stable_020_schemas` checks them.

## Validation

- `cargo check --locked --workspace --all-targets`: pass (sandbox Rust 1.97; the pinned 1.99 could not be downloaded there; minimum 1.95). One vendor warning (unused import in upstream `hayro-interpret/src/font/mod.rs`) comes from upstream.
- CI on the PR head runs the pinned toolchain, full Rust/provenance and Android JVM/ARM64 gates. A green head is required before merge.
- Outstanding as for every engine bump: physical S Pen, SAF write-back, save/reopen in other viewers, install-over upgrade.
