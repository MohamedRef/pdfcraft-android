# Android superscript and subscript controls

Edit PDF → select a paragraph → Superscript and subscript exposes the existing `text_script` native command. The formatting toolbar now scrolls horizontally so additional controls remain reachable in narrow phone windows.

The panel offers Superscript (x²) and Subscript (H₂O). With a nonempty inline-editor selection it formats only that text; otherwise it formats the selected paragraph. Unicode forms are stored in extracted text, not a baseline-shift-only visual style. Characters without a mapping stay unchanged. The font must support the mapped glyphs; native rewrite warnings are shown locally (at most three bounded messages). Undo restores the exact prior mixture; repeated toggles need not reconstruct mixed original forms.

## Safety and selection

- Uncommitted typing changes and active IME composition must be finished before using script controls. An unchanged draft can be dismissed without losing typed content.
- Compose UTF-16 selections are normalized and converted to native half-open UTF-8 byte ranges in one pass without copying paragraph/UTF-8 buffers. Invalid bounds, split surrogate pairs and byte-offset overflow fail before a native edit.
- For ranges, the selected paragraph text must exactly match fresh native `editor_text` (or `text` fallback). This prevents applying display-text offsets to differently normalized editor text.
- Provider read-only, signed, unreadable, closed/switched and stale-generation targets are refused. Generation validation, range-text comparison and script execution run together on the serial native dispatcher. Native permissions remain authoritative.
- The existing personal bookmarks, signatures, forms, other annotations and safe working-copy save/recovery paths are unchanged; this is ordinary undoable paragraph editing through the pinned engine.

## Verification

Local Rust 1.99.0 mobile-bridge regression passed selected-digit conversion (`H2O + CO2` → `H₂O + CO2`), Undo/Redo, save/reopen and unchanged original source bytes. Kotlin JVM tests cover Arabic/emoji/multibyte text, reversed selection, collapsed cursors, bounds and surrogate-pair splits. XML/source-provenance/diff checks and exact-head Rust/Android JVM/title/merge gates remain required.

Device/batch APK checks still outstanding: selecting text with touch/S Pen/keyboard, focus and IME state, changed drafts, repeated/queued taps, ligatures/normalization, fonts missing script glyphs, encrypted/restricted/signed PDFs, errors/warnings, RTL/accessibility, narrow toolbar scrolling and save/reopen. No physical Samsung verification or opening-speed improvement is claimed.

This is next-batch work alongside read-only backgrounds #30 and heading-style contents #31. It is absent from published `0.0.10-beta (10012)` and the earlier bookmark/tablet batch candidate. No per-PR APK.


Owner scope update (2026-11-07 Cairo): include this integration with #30/#31/#34 and the bookmark/tablet work in one candidate release, after complete-stack tests. Previous next-batch exclusions are superseded. Combined bridge tests retain background, heading-style and selected-script regressions; no physical Samsung/IME verification is claimed.
