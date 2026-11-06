# Embedded PDF bookmarks — bounded JSON import (Android)

This change is intentionally **stacked on** the existing embedded bookmark JSON export PR. It integrates the **existing Rust** `bookmark_import` command with an Android SAF file picker; it does not duplicate or change the pinned desktop-engine command.

## User flow

1. Open an editable, unsigned PDF. Choose **Bookmarks & Table of Contents → Contents → Import embedded PDF bookmarks (JSON)**.
2. Pick a `.json` file using the Android system picker (SAF read grant, no storage permission).
3. The app copies at most **16 MiB** from the provider to a short-lived private JSON file with a fixed 32 KiB buffer. Empty, stalled or oversized providers and partial reads fail closed. Provider URI and file name are never logged.
4. The serialized engine invokes `bookmark_import` with `file` set to that local temporary path and `replace=false`. The native schema validates required keys, bookmark count, nesting, page/view/color types and destination mapping before edit. Newly imported PDF outline nodes are appended, not substituted for existing ones, and remain undoable.
5. The viewer refreshes its embedded outline and Undo/Redo state. The app reports any unmapped pages or `truncated` depth warning. The temporary file is deleted after success/failure.

**Separate concepts:** the reader's personal `UserBookmarks` remain stored locally and are **not imported/overwritten**. Import changes the editable PDF outline. If a file is signed, read-only, or the target document was closed/switched while the picker was open, the app refuses the edit. Do not weaken signature safeguards to enable the feature.

## Failure and privacy boundaries

- SAF picker cancellation does not modify the document; its pending target reference is cleared.
- A distinct Activity Result launcher is used so the existing regular picker kind cannot confuse a bookmark JSON result with a PDF/ID selection.
- A provider that stalls, ends empty, fails during streaming, or exceeds 16 MiB leaves no imported tree. Temporary files are removed on exceptions and successful import.
- The app never writes to the provider URI or replaces the source PDF directly. Ordinary working-copy save/write-back recovery and the user's explicit Save a copy remain unchanged.
- Imported outline edits may be unsaved until the application's existing save flow runs. Record save/reopen and Undo/Redo on the batch APK; source CI does not prove them.
- `bookmark_import` has its own max count/depth and schema validations; this integration deliberately does not reimplement the JSON parser on Kotlin.

## CI and physical tests required

Owner batch policy (`.github/ANDROID_SIGNING.md`): Rust/provenance, Android JVM, title and aggregate merge checks gate each PR. One signed APK is built after a merged batch for device tests. The device/provider items below remain unchecked and must not be inferred from CI.

- [ ] Rust/provenance, Android JVM tests and aggregate Merge gate pass on the exact latest PR head.
- [ ] Bounded-copy JVM tests pass: unicode bytes, limit boundary, one-byte excess, empty source, stalled provider and midstream error.
- [ ] Android device/emulator: SAF pick/cancel, failed and revoked grants, low storage, no matching pages, malformed/oversized JSON, empty outline, pre-existing outline, deeply nested/tree limit, valid export→import round-trip.
- [ ] Verify imported entries appear in Contents after refresh, Undo removes the new outline, Redo restores it, save/reopen retains it; separate reader bookmarks and source JSON remain unchanged.
- [ ] Verify encrypted/locked, signed and provider read-only PDFs are not silently modified, and error messages are readable.
- [ ] Samsung S24 Ultra / Tab S11, landscape/portrait and system picker behavior are **unverified** until an owner device test is recorded.

Do not claim all embedded bookmark parity from this alone. Cross-PDF `bookmark_copy`, batch operations, phone/tablet UI testing and large-file speed work remain separate.


### Integration review — 2026-11-06

Reviewed picker isolation, current target identity and signed/read-only checks before and after bounded staging, append-only native import, warnings, temporary-file cleanup and generation-triggered outline refresh. Fourteen existing native bookmark regressions passed locally on Rust 1.99.0; prior import CI 42273660570 passed on `317524aee25496caabb222014d0bd5c3e1cc69bb`. The synchronized branch requires fresh current-head CI after export lands. SAF device/provider and save/reopen results remain outstanding, and this is not in published `0.0.10-beta (10012)`.
