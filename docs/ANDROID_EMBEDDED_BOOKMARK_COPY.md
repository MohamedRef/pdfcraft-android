# Embedded PDF bookmarks — copy between documents via Android SAF

**Scope:** This PR adds Android UI and provider-safe staging for the already-present Rust `bookmark_copy` command from pinned upstream desktop v0.18.2. This is **not** a new Rust implementation or a new PDF parser.

## Workflow

1. In an unsigned, editable target PDF, open **Bookmarks & Table of Contents → Contents → Copy embedded bookmarks from another PDF**.
2. Pick a different PDF with Android's system file picker. A dedicated Activity Result avoids mixing its result with normal Open/Insert or bookmark JSON import.
3. Stage the selected source PDF through a **read-only** SAF stream into a temporary app-owned file using the shared bounded stream utility. Copy in 32 KiB chunks, never hold the whole file in Kotlin RAM, refuse a known or observed length above **512 MiB**, and delete incomplete files on exceptions.
4. Use the unchanged native `doc_open` on the temporary path to create a **second** session document. Confirm the source permits copying and has a nonempty embedded outline.
5. Invoke the existing `bookmark_copy` with `doc=<target>`, `from=<second document>` and `replace=false`. The engine's `Edit::ImportBookmarks` keeps the change undoable and reports unmapped pages / truncated depth. Refresh target Contents and Undo/Redo state.
6. **Always** `doc_close` the temporary native source document and delete the staged file in `finally`, even after malformed PDF, edit refusal or read error.

## Safety and behavior

- The source PDF and its SAF URI are never passed to a write API. The PDF is only read; the importer cannot modify the picked original, even on providers that fail during the transfer.
- Guard the target by object identity after picker return and before mutation; reject read-only, signed, or closed/switched targets, and identical source URI. The native engine checks input and edit permissions independently.
- Cross-document outline destinations map by **page number**; when pages do not exist in the target, keep the bookmark without a destination and report the count. A deeply nested source may be truncated at the native 33-level listing limit. Do not claim perfect tree fidelity for these cases.
- The reader's private `UserBookmarks` are untouched. Existing target embedded bookmarks are **appended**, never implicitly replaced. Existing SAF write-back recovery, signatures and undo history are not bypassed.
- A password-protected source PDF that needs a password is **not yet supported by this picker flow**. It fails with the existing native password error and leaves no temporary file or target edit. Password prompting would be a separate change.
- Storage use temporarily includes the staged source PDF. The 512 MiB maximum is a safety cap, not a performance benchmark; disk-full, revoked permission, non-seekable provider and cancellation must all be tested.

## Validation gates (prior to merge)

- [ ] Exact-current-head Rust/provenance and Android JVM/aggregate Merge gate, including source-provenance unchanged.
- [ ] JVM bounded SAF stream tests (UTF-8/bytes, exact/overflow limit, stalled provider, empty/failed stream). Existing JSON tests must continue passing after reusing the shared copy helper.
- [ ] On device/emulator: same-source refusal; empty outline; read-only/signed/encrypted PDFs; source permission restrictions; malformed/truncated/oversize PDFs; SAF revoked access, non-seekable provider, failure midstream and low-storage cleanup.
- [ ] Copy nested outlines with mapped and unmapped pages. Verify target Undo/Redo and save/reopen, source bytes unchanged, user bookmarks unchanged, and no leaked temporary file/open Rust source.
- [ ] S24 Ultra and Galaxy Tab S11 phone/tablet layout and stylus interactions are **not claimed verified**.

This is a draft stacked after the JSON export/import PRs, not part of the already signed `0.0.10-beta (10012)` release. Performance remains unresolved independently under `docs/PDF_OPEN_PROFILING.md`.
