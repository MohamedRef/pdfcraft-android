# Read-only page background inspection on Android

**Published checkpoint — 2026-11-07:** Included in [0.0.11-beta (11012)](https://github.com/MohamedRef/pdfcraft-android/releases/tag/v0.0.11-beta), release source `d81ef5d9060a0008d798b7a1f26e571274655c4c`. [Publishing CI](https://github.com/MohamedRef/pdfcraft-android/actions/runs/42345606702) plus independent manifest/certificate/hash/ABI checks passed. Earlier candidate, reopened and unmerged statements below describe preparation history; physical Samsung/provider behavior remains unverified.

File details → Page backgrounds exposes the existing stable-v0.18.2 `doc_backdrops` command without an engine fork or PDF edits. The panel checks up to 24 pages per request, starting with the group containing the current page. Previous/next controls inspect other groups on demand. No background analysis runs during PDF opening.

The result distinguishes opaque white fills, page-sized images and pages with no covering backdrop detected. This is an explanation of why an added background may be hidden, not a promise that every drawing or transparency is classified. The panel does not add, replace or remove backgrounds. It writes no output files or provider data, and does not refresh the document as if it were edited.

Calls use the existing serialized native dispatcher. Effects are keyed to document ID, generation and requested page group; cancellation propagates and obsolete results are not displayed for another document. At most 24 result rows enter the virtualized list. Unknown result kinds or failed commands show an error rather than a false absence of background. Render-only documents whose structure could not be parsed are rejected before the command, because the native API uses an empty result when no COS editor is available; that empty fallback is not a successful inspection. Provider read-only access alone does not block analysis.

## Verification

- Native Android bridge regression: synthetic PDF with white-fill, page-sized-image and blank pages; exact command/schema, scoped selection, invalid page rejection, unchanged generation/dirty/Undo and source bytes.
- Required PR checks: pinned source provenance/Rust, Android JVM/Compose compilation, title and aggregate merge gate. No APK packaging for this individual PR.
- Still pending on device/emulator: file-details routing, previous/next groups, final partial group, switching/closing/editing while inspection runs, signed/read-only/encrypted files, RTL/accessibility, tablet resizing and large/scanned files. Native host tests are not physical Samsung evidence.
- Published `0.0.10-beta (10012)` does not include this integration. The combined release must retain the production certificate and document its actual manifest build number. This does not resolve opening latency versus Xodo.


Owner scope update (2026-11-07 Cairo): include this PR with #31/#32/#34 and the bookmark/tablet batch in one `0.0.11-beta (11012)` candidate, after integration/current-head checks. Previous next-batch release isolation is superseded; manifest/signature/assets and physical QA remain unverified until actually performed.
