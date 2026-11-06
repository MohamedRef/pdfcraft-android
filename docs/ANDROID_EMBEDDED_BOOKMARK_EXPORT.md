# Embedded PDF bookmark JSON export — Android integration

## Scope

This isolated PR adds **export only** for the PDF's embedded outline to a JSON document in Android Downloads. It does **not** export reader-added, private on-device bookmarks, and it does not implement cross-document copy or import.

- UI: Bookmarks & Table of Contents -> **Contents** -> **Export embedded PDF bookmarks (JSON)**, shown only when the loaded PDF reports an outline.
- Engine: uses the existing `bookmark_export` command and unchanged `crates/automation/src/bookmark_io.rs` schema (`bookmarks` array, `truncated` if depth exceeds 33). No duplicate native implementation, modification to signing, source PDF or open pipeline.
- Workflow: execute on the existing serialized engine dispatcher, atomically write a private temporary JSON through Rust, deliver via existing `DocumentFiles.saveToDownloads(..., "application/json")` and delete the temp file after success or failure.
- The existing user's private bookmarks and their storage remain independent of embedded PDF outline data.

## Validation before merge

- [ ] Exact-current-head Rust/provenance tests.
- [ ] Exact-current-head Android JVM tests and aggregate Merge gate.
- [ ] Validate new text resource, Compose screen, and JSON export build.
- [ ] Manually export nested/colored/empty-title/long PDF outlines and reimport the resulting JSON with the native `bookmark_import` tool in a controlled test.
- [ ] Validate no-outline state, password-protected and copy-restricted PDFs, full Downloads storage, large bookmark trees, and cancellation/error surfaces.
- [ ] On Samsung S24 Ultra and Tab S11, verify Downloads receives a real nonempty `application/json` file and that original PDF/signatures and private bookmarks are unchanged.

## Follow-up (separate PR)

Implement bounded SAF JSON import, clear append-vs-replace confirmation and Undo/Redo, then a separate cross-document `bookmark_copy` bridge and page mapping, with source document lifecycle cleanup and safe provider handling. Export alone is not full embedded bookmark parity.

No performance improvement or hardware verification is claimed by this feature.
