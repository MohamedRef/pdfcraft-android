# Desktop v0.19.0 — Android impact review

**Current development update:** Dedicated [upgrade PR #37](https://github.com/MohamedRef/pdfcraft-android/pull/37) merged the selected v0.19.0 source with preserved Android adaptations after successful exact-head and post-main checks. See [upgrade evidence](ANDROID_ENGINE_0.19.0_UPGRADE.md). The assessment below records the original pre-upgrade comparison; published 0.0.11-beta (11012) remains the v0.18.2 binary.

Verified 2026-11-07 (Cairo) using the upstream GitHub release, immutable tag and code comparison. This is a review, not an engine upgrade or a runtime-parity claim.

## Published stable source

- Latest published stable: [v0.19.0](https://github.com/storytold/pdfcraft/releases/tag/v0.19.0), published **2026-11-06 20:21:58 UTC** (22:21:58 Cairo).
- The actual tag resolves to commit `8c572792d6760bdc903d6f9a032e3e38e8f71cb8`.
- Android still pins v0.18.2 at `7faa4f35cefb0dea04137ec475bdc770945c22cd` through its source/provenance locks. The app’s independent version is not the engine version.
- [Immutable comparison](https://github.com/storytold/pdfcraft/compare/7faa4f35cefb0dea04137ec475bdc770945c22cd...8c572792d6760bdc903d6f9a032e3e38e8f71cb8): **235 commits**, **243 changed paths** returned by the comparison, including **147** paths in this fork’s selected mobile core/vendor groups. No truncated file-list assumption: 243 is below GitHub’s 300-file comparison response limit.

## Command-table changes

Parsed the actual `t("name", ...)` definitions at both immutable baselines: upstream v0.18.2 has 189 unique names, v0.19.0 has 195; six added, none removed:

| Added command | Android review area |
| --- | --- |
| `comments_copy`, `comments_paste` | Cross-document comment selection, clipboard lifetimes, annotations/voice preservation and Undo |
| `ocr_dictionary` | OCR review dictionary storage, scope and bounded mobile UI |
| `ocr_words` | OCR words/confidence access, privacy and editable result mapping |
| `ocr_review_apply` | Applying reviewed OCR edits with safe save/recovery and cancellation |
| `page_enhance_scan` | Scan enhancement, image memory limits and render quality |

Names do not prove equivalent schemas, behavior or a usable mobile workflow. Android currently has 189 names of its own, including `photo_find_document` and `comment_erase`; 187 upstream v0.18.2 names are present. Against v0.19.0, the six new commands plus intentionally deferred `model_add`/`model_render` are not yet ported. Do not label old 98.9% name coverage as current v0.19.0 parity.

## Adaptations needing explicit reconciliation

Twelve of the currently allowed Android-adaptation paths also changed upstream:

- `crates/annot/src/appearance.rs`, `appearance/symbols.rs`, `create.rs`, `types.rs`
- `crates/automation/src/comments.rs`, `dispatch.rs`, `lib.rs`, `tools.rs`
- `crates/cos/src/document/objects.rs`
- `crates/engine/src/document.rs`, `edit/labels.rs`
- `crates/render/src/raster/tests.rs`

Preserve Android Sound/WAV comments, selective sticky-note rendering, real Ink Eraser, escaped/header timestamp discovery and every documented adaptation; do not overwrite adapted files or regenerate a permissive lock that merely accepts drift.

## Source areas worth prioritizing

The changed mobile groups include annotation/automation, COS/crypt, forms, rendering/fonts, OCR, redaction/signing, editing, creation/PCX, compare/measure, optimize/preflight, XFDF and `vendor/hayro`. The upstream release describes crypt-filter/full-save handling, bounded form decoding, JBIG2/PNG rendering work, OCR review/dictionaries, scan enhancement, comment transfer, redaction validation and signing changes. These need native/mobile regression review. Desktop egui scrolling, page raster cache and press routing are not directly interchangeable with Android Compose input/rendering.

The owner’s encrypted large-PDF opening issue remains unresolved. Source changes in parsing/decryption/rendering are areas to investigate, not proof that v0.19.0 will open that file faster on Samsung.

## Concrete upgrade gates

1. A dedicated engine-upgrade PR against the complete current Android main; pin the immutable v0.19.0 tag, review the coherent suitable crates/vendor set and internal dependency-version changes.
2. Reconcile all Android adaptations, especially the twelve overlapping paths. Retain owner `AGENTS.md`, app ID/signing identity, updater checks, safe SAF copies/recovery and all newly integrated Android UI routes.
3. Update source/provenance inventory only with explicit reviewed differences; keep desktop/3D-only code deferred. Audit all six new command schemas and mobile exposure separately.
4. Pass Rust/provenance, Android JVM, ARM64 native bindings, aggregate merge gate and focused regressions for encrypted/signed PDFs, forms/XFA, voice/notes/ink, OCR/scan memory, save/reopen and cancellation.
5. Measure the private large PDF locally without upload, then require same-file Samsung/Xodo first-visible-page trials before a speed claim. Publish through the existing permanent-key signing/manifest/hash process only after integration verification.

No v0.19.0 source or new command was imported by this review. The published [0.0.11-beta (11012)](https://github.com/MohamedRef/pdfcraft-android/releases/tag/v0.0.11-beta) uses the protected v0.18.2 engine; its ARM64 manifest build number, signature and hash have been independently verified. Device performance remains unverified.
