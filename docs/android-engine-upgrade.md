# Android engine compatibility and upgrade plan

**Sources pinned for audit (2026-11-06):** `storytold/pdfcraft` `5aaa8186fd88d4c59f139665ff7809bd03775626` (`0.18.1` workspace) and the Android fork `c1d26a88b980f5d896fcb9ad8d797ca72074d64b` (`0.14.0` workspace).

## What Android already uses

The Android APK runs the **same kind of pure-Rust engine crates** through `apps/pdfcraft-android/src/lib.rs` (`MobileSession`, UniFFI `0.32.2`) and generated Kotlin/JNA bindings; the UI is Jetpack Compose, not the desktop egui application. `crates/automation` provides JSON tool calls; `crates/render` supplies page pixels/text. Copying `apps/pdfcraft/` (egui) is **not** part of an Android engine upgrade.

The current upstream and Android trees are **not interchangeable**: in the `crates/` subtree, 184 shared file blobs differ; upstream has 553 file paths absent from Android. These counts include tests and desktop-only modules, not just missing engine capabilities. Upstream adds `prc` and `prc-render`, and substantially restructures `engine`, `render`, `edit`, `automation`, and `vendor`. A folder overwrite would break workspace APIs, `Cargo.lock`, and the UniFFI contract.

## Safety-first migration sequence

1. **Backport proven correctness fixes to the current Android baseline.** This PR ports two original upstream regressions: negative trailer `/Size` in incremental saves (storytold/pdfcraft#5566) and full rewrites after deleting unsigned pages (storytold/pdfcraft#4279). Signed documents keep incremental saves. Run direct regression tests.
   This PR also caps native/JNA-to-Bitmap pixel transfers to prevent extreme PDF zoom from allocating 64-million-pixel images on low-memory devices. Tiled zoom retains high detail.
2. **Pin a complete engine snapshot**, not individual unrelated crates: update `Cargo.toml`, `Cargo.lock`, `crates/{cos,crypt,filters,model,render,organize,...}`, and required vendored hayro/lopdf files together. Respect pinned `craft-libs` revisions, transitive dependencies, Rust 1.95, and upstream licences. Do not add `ui-egui` or desktop apps to the Android binary.
3. **Port the engine/automation boundary** after a coherent core snapshot builds. Compare each Android tool against upstream JSON-schema argument/result contracts. Add or adapt `MobileSession` only for compatible tools; regenerate UniFFI Kotlin bindings from the exact Rust `.so` of that build, then compile the consumer.
4. **Validate Android-specific behavior**: both ARM ABIs, model files, 16 KB page compatibility, runtime native loading, encryption/owner permissions, malformed inputs, huge PDFs, page tiles, edits/undo, all save/reopen paths, SAF/MediaStore write failures, and trim-memory/process recreation. Include device/emulator smoke tests in GitHub Actions before calling a release stable. A successful unit build by itself is not proof of runtime stability.
5. **Expand features last**, keeping desktop-only functionality optional. Validate any OCR/font/3D dependencies on Android, not just Linux CI. Keep the custom package ID and the existing private release keystore unchanged for in-place updates of PdfCraft Custom.


## Phase 1A: bounded JBIG2 decoding port (stacked PR)

Imported the 12 changed source files of the complete vendored `hayro-jbig2` crate from upstream `5aaa8186fd88d4c59f139665ff7809bd03775626` (same `hayro-jbig2 0.3.0` manifest, no new external dependencies). These backports cover retained bitmaps/contexts, segment count and references, work accounting and bounded halftone/symbol decoding. They belong together: taking one decoder file without the others breaks the internal decode signatures and limits.

The Android fork continues to use the pinned older `hayro-interpret` and `pdfcraft-render` until a separate, coherent renderer upgrade passes tests. The public `Image::decode` / `Image::decode_with` entry points remain. `hayro-jbig2` is a vendored crate excluded from the workspace; Cargo cannot run its dev tests directly with `-p`. PR CI tests its behavior through `pdfcraft-render` integration tests and the Android Kotlin/JNA build. Running the vendor's own dev suite needs a separate harness.

**Do not treat this as an entire 0.18.1 engine upgrade.** After CI passes, validate repeated hostile JBIG2 files on emulator, memory pressure, and that image decoding and fallback remain correct.

## Required CI gates

PRs run Rust regression suites for `pdfcraft-cos`, `pdfcraft-engine`, and `pdfcraft-android` plus the Android JVM/NDK build tests. The APK signing job runs only outside PRs, and verifies both signing certificate and application ID. No debug-signed APK may masquerade as an upgrade.

The manual/emulator matrix remains a **required follow-up**, not something that the host Rust/JVM tests can establish. Do not merge or tag an engine-version bump until runtime device tests and data-integrity tests are green.

Upstream references: https://github.com/storytold/pdfcraft , https://github.com/storytold/pdfcraft/pull/5566 , https://github.com/storytold/pdfcraft/pull/4279 .
