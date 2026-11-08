# Android OCR confidence review parity — upstream v0.19.0

## Scope

Expose existing native `ocr_words` as an opt-in, **read-only** current-page viewer sheet at More > Review OCR confidence. This is UI/runtime parity with the already-pinned stable desktop Rust engine, **not** a new OCR implementation or a claim of full OCR review/correction parity.

The user explicitly starts an OCR recognition for one 1-based page. The bridge uses `retain_review=false` to avoid creating a session-local edit/review handle, and `skip_text_pages=false` so an existing text layer does not silently prevent inspection. The underlying engine enforces the document's text-copy permission, and the UI never writes to a document or changes Undo history. Existing `ocr_recognize` remains separate.

## Safety, memory and UI

- Only `doc.id` and the current page are sent; no remote document upload or all-pages scan.
- OCR model availability is checked through the existing model preparation/status path.
- Native page results are parsed in memory and **all** word entries are checked for low confidence, but at most 100 low-confidence and 100 other entries are retained for the visible preview, which is itself capped at 100.
- The result displays the total number of recognized words and a low-confidence count. The user sees a label when the list is truncated and can return to the original More sheet.
- Results are keyed by document/page/generation, and responses are ignored when the open document is replaced or edited. Cancelling the sheet does not write to disk.
- Native engine honors permissions. Errors and missing OCR models are visible, without claiming successful recognition.

## Known limits and subsequent work

This first parity increment only **inspects** recognized text; it does not yet offer word correction or the `ocr_review_apply` mutating path, nor OCR dictionary edits. Those need separate proposals with exact PDF generation guards, optional output destination, Undo, non-overwriting SAF exports and consent to apply changes. The source-native `ocr_words` entry remains unchanged. Results depend on the installed OCR model and may have an unknown confidence percentage.

Test and verify current PR head Rust/provenance, Android JVM and merge gate before integration. Unit tests cover suspect-first priority, limits, deterministic order and no source mutation. Physical Samsung S Pen, low-memory, signed/protected PDF, large scanned page and interrupted OCR behavior remain unverified. No per-feature APK or new release.
