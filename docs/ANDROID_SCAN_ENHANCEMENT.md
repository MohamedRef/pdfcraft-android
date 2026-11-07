# Android page enhancement parity (native v0.19.0)

## Scope
Expose the existing `page_enhance_scan` native automation command in the Android viewer under More → Enhance scanned pages. This is UI/runtime parity with the original desktop command, **not** a new image-filter implementation.

- User explicitly chooses current page or a bounded run of at most 12 pages starting from current position.
- Background cleanup 0–100, edge shadows and isolated speckle cleanup are configurable.
- A second confirmation explains that PDF image content changes. The native engine preserves existing OCR text layers, comments and links, skips unsupported/compressed formats and only records an undoable edit when an image changes.
- No arbitrary file paths, path writes, exported copies or all-pages operations; uses the current open native document id and existing safe provider write-back flow.
- Signed, read-only and nonwritable documents are blocked before native invocation; native access controls remain authoritative. A busy guard prevents duplicate submissions and a generation guard prevents processing a stale selection.
- Results are reflected through the existing refresh plus optional Undo banner. No extra APK, version, package or signing changes.

## Regressions and limits
JVM checks cover current-page indexing, 10,000-page bounded batching, last-page clipping, invalid page positions, 0–100 level validation and noncontiguous/malformed scopes.

Native `page_enhance_scan` skips formats it cannot safely modify (JBIG2, CMYK, certain embedded artwork and similar). App does not promise every scan will change. Heavy native image processing is bounded by number of pages, not by total pixel count; engine memory guards and hardware validation remain essential. Saving to provider and undo/save/reopen must be checked on physical Samsung and with representative OCR, scanned and signed fixtures.

Dependency: highlight PR #43 is the immediate base; merge it only after exact-head Rust/JVM/title/aggregate gate success. Then retarget and test this standalone parity PR before merge. Unrelated draft #42 stays separate. Published 0.0.12-beta remains unchanged.
