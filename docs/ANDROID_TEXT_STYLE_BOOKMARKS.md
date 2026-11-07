# Android contents from heading styles

**Published checkpoint — 2026-11-07:** Included in [0.0.11-beta (11012)](https://github.com/MohamedRef/pdfcraft-android/releases/tag/v0.0.11-beta), release source `d81ef5d9060a0008d798b7a1f26e571274655c4c`. [Publishing CI](https://github.com/MohamedRef/pdfcraft-android/actions/runs/42345606702) plus independent manifest/certificate/hash/ABI checks passed. Earlier candidate, reopened and unmerged statements below describe preparation history; physical Samsung/provider behavior remains unverified.

Contents → Create contents from headings exposes the existing stable-engine `text_styles` and `bookmark_from_text_styles` commands. Analysis starts only when the user requests it; it may read the first 5,000 pages. The pinned native engine returns at most the largest 200 styles, with samples capped to 60 characters; Android exposes that complete native result without another selector cap. Image-only files need OCR first. No analysis runs while opening a PDF.

Choose at most nine styles in the desired heading order. Selection order defines levels 1–9; deselect/reselect to change order. Only exact font, point size, bold and italic matching fields go to native code; line/page counts and sample text are display-only. Displayed font names/samples are bounded, the list is virtualized and no extracted text is logged or exported.

The explicit Add action creates entries below a new “Untitled” root and preserves earlier contents. Native Undo/Redo and normal working-copy save/write-back remain in control. Private on-device reader bookmarks are separate. Closed/switched, signed, provider read-only, structurally unreadable and copying/assembly-restricted targets are refused. A stale generation is checked both in the UI/model and immediately before mutation in one serialized native command, so a queued double tap cannot append two trees from the same analysis.

## Evidence and limits

- Local Rust 1.99.0 native bridge regression passed: read-only style analysis, append while preserving earlier bookmarks, Undo/Redo, save to a new PDF, reopen with matching outline and unchanged source bytes.
- Kotlin JVM cases check exact four-field payloads, selected level order, Unicode font preservation, level bounds, invalid sizes and missing font. Current-head CI must run these and compile Compose; no local Android SDK/device result is claimed.
- Required per-PR Rust/provenance, Android JVM, title and aggregate merge gates. No per-PR APK, per owner policy.
- Manual batch-APK QA outstanding: selection ordering, cancel/close/re-analyze after edits, queued/double taps, malformed/encrypted/restricted/signed files, native style tolerance, long/Unicode fonts, OCR/scanned documents, existing outlines, save/reopen, RTL/accessibility, S Pen and phone/tablet UI.
- Native limits remain 200 listed styles and analysis of the first 5,000 pages (`crates/engine/src/text_style_bookmarks.rs`). These are stable desktop engine limits, not an additional Android truncation. Full UI/runtime parity is not claimed.
- Combined-release integration; not in published `0.0.10-beta (10012)`, not a PDF-opening optimization. Integration with the full bookmark/tablet/background/text-script/provider stack must pass current-head checks before merging.


Review reconciliation: an earlier draft described an extra 300-style Android cap. Inspection of the actual stable implementation showed `MAX_STYLES=200` and `SAMPLE_CHARS=60`, making that cap/banner unreachable. Removed them and corrected the limits. Synchronized with main after the complete bookmark/tablet batch; current-head CI must pass again.


Owner scope update (2026-11-07 Cairo): include this integration with #30/#32/#34 and the bookmark/tablet batch in one `0.0.11-beta (11012)` candidate after complete-stack CI. Earlier next-batch exclusion is superseded. Combined tests retain both background-inspection and heading-style regressions; physical QA remains outstanding.
