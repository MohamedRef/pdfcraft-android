# Android multi-document Bates numbering

## Scope

Tools → Bates numbering exposes the existing native `doc_bates_multi` through a system PDF picker. Select up to 20 saved PDFs, reorder/remove them, choose a positive start (up to 12 digits), minimum digits 1–12, prefix/suffix, then explicitly number and export new copies to Downloads. Native numbering continues across files and reports reserved per-file ranges even when an individual output fails. The open viewer's unsaved edits are not silently included; save them before selecting their stored file.

## Source and output safety

- Selected URIs are opened for reading only. Generic indexed filenames in a fresh private cache directory prevent provider names from controlling paths. Actual stream bytes, not just provider metadata, enforce the shared 512 MiB aggregate input limit with 32 KiB buffers. Native output staging is also capped at 512 MiB.
- Temporary native opens reject signed/structurally read-only PDFs before numbering. Normal native encryption/editing permissions remain authoritative. Password prompts are not in this first workflow; PDFs requiring a password fail without modifying the original.
- The complete native report is checked for expected file paths/counts/statuses and consecutive page-number intervals before any publication.
- Each output reserves a new app-owned pending MediaStore Downloads row, streams the bounded PDF, then publishes it. Failure/cancellation removes only that new row; existing downloads and original PDFs are never a write target. Cleanup failures remain attached to the original error. Failed rows and any already saved copies are reported separately.
- Cancellation is checked between chunks and exports. It cannot interrupt a provider already blocked inside read, or an in-flight native batch. Native ownership and local file lifetime remain intact until the blocking operation finishes; cancellation then prevents further exports. URI ownership is recorded inside the non-cancellable publication boundary so a late cancellation cannot hide an already saved copy.
- A dedicated picker/token and selection epoch reject stale results and metadata reads. Selection/settings freeze while running. Process death may lose an unstarted selection or leave private cache files for Android to reclaim; it does not overwrite sources. The feature does not promise all-or-nothing publication across multiple completed Downloads rows.

## Verification

JVM regressions cover numeric/Unicode-byte bounds, malformed/partial/gapped/overflowing native intervals, pending-row rollback on cancellation and preservation of primary/cleanup failures. Existing native Bates regressions cover numbering across two PDFs, preflight/permissions and non-overwriting output behavior. Rust/provenance and Android JVM/ARM64 binding CI must pass on the exact PR head before merge. Actual Samsung/provider/cancellation/Downloads/install/save-reopen QA remains outstanding. No APK per PR; release once per completed batch.
