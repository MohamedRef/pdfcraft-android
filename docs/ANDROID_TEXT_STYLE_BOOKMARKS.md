# Android contents from heading styles

Contents → Create contents from headings exposes the existing stable-engine `text_styles` and `bookmark_from_text_styles` commands. Analysis starts only when the user requests it; it may read the first 5,000 pages. The mobile selector shows at most the first 300 styles, largest first, and explicitly reports truncation of the selector. Image-only files need OCR first. No analysis runs while opening a PDF.

Choose at most nine styles in the desired heading order. Selection order defines levels 1–9; deselect/reselect to change order. Only exact font, point size, bold and italic matching fields go to native code; line/page counts and sample text are display-only. Displayed font names/samples are bounded, the list is virtualized and no extracted text is logged or exported.

The explicit Add action creates entries below a new “Untitled” root and preserves earlier contents. Native Undo/Redo and normal working-copy save/write-back remain in control. Private on-device reader bookmarks are separate. Closed/switched, signed, provider read-only, structurally unreadable and copying/assembly-restricted targets are refused. A stale generation is checked both in the UI/model and immediately before mutation in one serialized native command, so a queued double tap cannot append two trees from the same analysis.

## Evidence and limits

- Local Rust 1.99.0 native bridge regression passed: read-only style analysis, append while preserving earlier bookmarks, Undo/Redo, save to a new PDF, reopen with matching outline and unchanged source bytes.
- Kotlin JVM cases check exact four-field payloads, selected level order, Unicode font preservation, level bounds, invalid sizes and missing font. Current-head CI must run these and compile Compose; no local Android SDK/device result is claimed.
- Required per-PR Rust/provenance, Android JVM, title and aggregate merge gates. No per-PR APK, per owner policy.
- Manual batch-APK QA outstanding: selection ordering, cancel/close/re-analyze after edits, queued/double taps, malformed/encrypted/restricted/signed files, native style tolerance, long/Unicode fonts, OCR/scanned documents, existing outlines, save/reopen, RTL/accessibility, S Pen and phone/tablet UI.
- At most 300 analyzed styles can currently be chosen through this selector, even if native returns more. Full UI/runtime parity is not claimed.
- Next-batch preparation only; not in published `0.0.10-beta (10012)`, not a PDF-opening optimization. Integration with open import/copy/tablet PRs must be synchronized and rechecked before merging.
