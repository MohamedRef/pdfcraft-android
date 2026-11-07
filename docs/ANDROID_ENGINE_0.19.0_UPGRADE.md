# Android engine v0.19.0 upgrade

## Live status — 2026-11-07

**Merged checkpoint:** [PR #37](https://github.com/MohamedRef/pdfcraft-android/pull/37) merged as `552aed9504b0764b0818bdeb72543abdc227f768` after final-head CI 42355818983 and post-main CI 42360494031 succeeded. The source below is now on main; published 0.0.11-beta (11012) remains the old engine binary until the next verified release. Earlier PR-progress wording below is retained as validation history.

Dedicated upgrade branch from main `7f597ad45612f53cc01f801cf97f34fb9421f0ee`. The owner authorized importing stable v0.19.0 while preserving every Android feature, stylus/ink, voice, recovery, signing and updater behavior. Source integration and local validation are complete on the PR; latest-head GitHub checks must succeed before merge; the released 0.0.11-beta (11012) remains immutable and uses v0.18.2.

Target: immutable upstream `8c572792d6760bdc903d6f9a032e3e38e8f71cb8` (`v0.19.0`), replacing source pin `7faa4f35cefb0dea04137ec475bdc770945c22cd`. Review context: [upstream impact assessment](UPSTREAM_0.19.0_REVIEW.md).

## Preservation and verification checklist

- [x] Three-way reconcile selected 32 core crates and nine vendors against the old baseline, retaining all 24 reviewed Android adaptations; explicitly review the twelve overlapping upstream edits.
- [x] Preserve Android bridge/Kotlin UI, S Pen pressure/history/hover/eraser, Sound/WAV annotations, forms/XFA output, selective note rendering, safe provider copies and updater/signing identity.
- [x] Pin dependency resolution and update provenance from immutable upstream objects, retaining deliberate 3D/PRC omissions. No arbitrary allowlist expansion.
- [x] Audit the six new command schemas and compatibility of existing mobile command arguments/results; importing tools does not imply a new mobile workflow.
- [x] Pass local focused Rust/bridge, annotation and signature regressions.
- [x] Pass the full locked local mobile workspace: 4,392 passed, zero failed, 13 reported ignores.
- [x] Pass source-head remote Rust/provenance, Android JVM/ARM64 bindings and title/aggregate gates ([42355122018](https://github.com/MohamedRef/pdfcraft-android/actions/runs/42355122018), `e38dc8f`). Recheck [the latest PR head](https://github.com/MohamedRef/pdfcraft-android/pull/37/checks) after the final test/documentation refresh; an older green head is not its status.
- [x] Record measured private-file host opening results without uploading the fixture; Samsung/Xodo and physical S Pen/provider/save/upgrade tests remain separate and unverified.

No new APK per PR, version/signing rotation or release publication as part of this upgrade PR. Build the next signed release through the existing owner-approved batch process.

## Source integration checkpoint

- Imported 147 changed/new selected upstream paths (48 new files). Immutable baseline has 1,018 core/vendor paths: 989 exact, 27 reviewed Android adaptations (24 retained plus three explicit mobile test compatibility adjustments), two deliberate 3D omissions and zero local additions in that lock table.
- Three textual conflicts resolved explicitly: `annot/types.rs` retains Sound while adopting new Style defaults; automation dispatch/table retain photo detection and add scan enhancement. Other overlapping adaptations were merged against v0.18.2, not overwritten. The twelve non-overlapping adaptations were carried forward at import; the timestamp regression file was then extended for generation-aware coverage.
- Header-only timestamp candidate discovery now compares the complete object reference and resolves stream Length with generation checks, retaining ordinary-reader fallback and full candidate validation. Added a differential nonzero/repaired/stale-Length-generation regression.
- Kotlin UI, S Pen routing/pressure/history/hover, provider recovery/updater/signing code, native bridge production source and existing eraser regressions are unchanged. Current focused tests passed: 25 Android bridge tests, 99 annotation unit tests, 42 signature unit tests and 134 signature integration tests; three pre-existing signature tests remain ignored. These include the new tool-table and clipboard payload-preservation regressions.
- Rust workspace/internal dependencies now coherently use 0.19.0. APK version/build remain unchanged. Cargo.lock retains every existing external version; the sole added external package is `aho-corasick 1.1.5`, required by upstream redaction. Existing png/sha2/wasm dependencies supply the added upstream edges. Locked host cargo check passed.
- Mobile table: 195 names, including all six additions, versus upstream 195; 193 shared, two mobile extras (`photo_find_document`, `comment_erase`), two deferred 3D tools (`model_add`, `model_render`). 191/193 shared definition blocks match ignoring whitespace; `comment_add` retains Sound/WAV and `comments_copy` explicitly documents the embedded-payload refusal. These are source/schema statistics, not runtime or UI parity.
- OCR still defaults to the existing pure-Rust ocrs engine. Optional external Tesseract is neither bundled nor downloaded; it requires an available user-installed executable/languages. New OCR review/dictionary/scan/comment transfer commands do not imply new dedicated Android UI in this PR.
- Full local workspace tests passed and source-head remote Rust/JVM gates succeeded. The final refresh changes only mobile test scoping and documentation and needs its own latest-head checks before merge. Initial documentation-only head checks are not upgrade evidence. Physical Samsung/Xodo, S Pen, SAF, install/upgrade and save/reopen validation remain outstanding.

## Compatibility findings and resolution

- Full workspace testing initially failed the new upstream display-only appearance test: desktop expects a Sound annotation without an appearance to stay undrawn, whereas Android intentionally supplies its existing microphone icon. Narrowly adjusted only that test's Sound expectations (including save/reopen), retaining the assertion that the original document is unchanged. This is the explicit 25th adaptation, not a production behavior removed to pass CI.
- Upstream XFDF copy carries Sound/FileAttachment annotation metadata without their embedded bytes. Android's new clipboard wrapper refuses a selected recording/attachment or a reply thread containing one before returning lossy data. Existing voice playback, attachments, save/reopen and ordinary ink copy/paste/Undo remain supported. A native regression verifies refusal without document edits, unchanged source bytes, original PCM samples and successful unrelated ink transfer with width/opacity.
- Corrected the new table test's signing tool spelling to the actual existing `sign_document`; no production signing tool was removed or renamed.
- `git diff --check` flags two trailing spaces inside the exact upstream `form_date_language.rs` PDF xref string fixture. Preserve those fixture bytes; all fork-authored changes are whitespace-clean. No extra provenance exception was added to alter that upstream fixture.

- Full workspace execution exposed six desktop PRC/model scenarios registered in the upstream automation harness, although this fork deliberately has never shipped their two tools. The mobile harness retains those source files pinned, excludes their inapplicable desktop registrations and adds an explicit UnknownTool/no-document-edit contract. Root-confinement coverage now exercises Android photo/Sound and the new OCR dictionary instead of absent model_add. The dictionary fixture uses its required valid filename so the root check, not an earlier filename rejection, is exercised. No supported tool or its regression coverage was removed.

## Local verification evidence

Environment: Apple Silicon macOS, Rust 1.99.0, existing pinned dependencies, `CARGO_INCREMENTAL=0`. `cargo test --locked --workspace --no-fail-fast` completed with **4,392 passed, zero failed, 13 ignored**, across 93 test/doc-test binaries. No new `#[ignore]` was introduced by the fork. Successful source-head CI [42355122018](https://github.com/MohamedRef/pdfcraft-android/actions/runs/42355122018) also compiled the ARM64 native bindings and passed Android JVM tests; signed APK packaging was skipped by per-PR policy. This is not a physical-device or APK-install test.

Private fixture measurement used five warmup opens followed by five recorded warm-cache trials, the dev optimized profile, and no concurrent local build/test workload. The local-only fixture is 352,078,544 bytes, encrypted, 869 pages. Native open times were **1,599 / 1,610 / 1,562 / 1,582 / 1,560 ms** (median **1,582 ms**). First-page raster at **306 × 392** pixels took **71 / 67 / 66 / 67 / 66 ms** (median **67 ms**). File digest before/after the recorded trials matched; the digest, filename, path and content were not published. The temporary numeric-only probe was removed from the repository working tree. These results exclude Android provider copying/UI/GPU presentation and do not establish an improvement over a controlled v0.18.2 baseline or Samsung/Xodo parity. Opening latency remains open pending device traces.

## Preserved adaptation inventory

- `crates/annot/src/appearance.rs` — Android Sound annotation drawing dispatch; preserve microphone icon.
- `crates/annot/src/appearance/symbols.rs` — Original voice-comment microphone icon in mobile PDF rendering.
- `crates/annot/src/create.rs` — Restore bounded Sound comment PDF object serialization and validation.
- `crates/annot/src/types.rs` — Android Sound annotation shape, style and subtype retained from original mobile implementation.
- `crates/automation/Cargo.toml` — Exclude desktop-only PRC and model-render dependencies.
- `crates/automation/src/a11y.rs` — Mobile form-fill script output reuses the released JS request/alert JSON serializers without changing their content.
- `crates/automation/src/a11y/form_tools.rs` — Expose existing request_json and alert_json as crate-visible for form-fill reports; no output schema alteration.
- `crates/automation/src/comments.rs` — Bounded WAV-to-Sound comment creation and mobile erase-path wrapper over existing native Edit::EraseInk; exclude FillSign and locked ink, cap gesture input. Refuse clipboard selections/reply threads with embedded Sound/FileAttachment payloads to prevent silent payload loss.
- `crates/automation/src/dispatch.rs` — Omit model3d dispatch; retain Android photo detection and bounded mobile comment_erase routing.
- `crates/automation/src/forms.rs` — Return already-executed form calculation alerts, viewer requests, console logs and script errors to Android instead of silently dropping them.
- `crates/automation/src/lib.rs` — Exclude 3D modules and retain Android-only photo detection.
- `crates/automation/src/tools.rs` — Headless tool table excludes 3D and includes Android photo_find_document plus bounded comment_erase schema. Explicit clipboard embedded-payload limit.
- `crates/engine/src/camera.rs` — Android photo detection / EXIF backport.
- `crates/engine/src/document.rs` — Bounded Android XMP metadata reader exposed through File Details.
- `crates/engine/src/edit/labels.rs` — Android-only Sound comment edit/history label.
- `crates/render/src/inspect.rs` — Re-export bounded sound_wav for Android voice-comment playback.
- `crates/render/src/inspect/resources.rs` — Bounded WAV extraction + tests; preserves phone sound-comment playback.
- `crates/render/src/lib.rs` — Public sound_wav re-export for Android native wrapper.
- `crates/render/src/raster.rs` — Selective sticky-note rendering flag used by Compose overlay.
- `crates/render/src/raster/tests.rs` — Regression test for selective note hiding and normal annotation painting.
- `vendor/hayro-interpret/src/interpret/mod.rs` — Android-only /Text annotation appearance filter and interpreter setting.
- `crates/cos/src/document/objects.rs` — Bounded generation-aware raw-header candidate scan; exact header reference and generation-aware Length lookup, with ordinary reader fallback for repaired/edited/compressed/uncertain objects.
- `crates/sign/src/pdf/listing.rs` — Discover timestamps using unencrypted names/key presence from headers, then fully load and validate every candidate as before.
- `crates/sign/src/pdf_discovery_tests.rs` — Differential encrypted/escaped-name and nonzero/repaired-generation timestamp discovery; unreadable-body validation regressions.
- `crates/annot/src/tests.rs` — Display-only missing-appearance regression expects the preserved Android Sound microphone icon; source annotations remain untouched.
- `crates/automation/tests/it/main.rs` — Mobile test scope retains desktop 3D test sources but does not register scenarios requiring deliberately omitted tools; explicit UnknownTool/no-document-edit rejection regression replaces those inapplicable scenarios.
- `crates/automation/tests/it/automation_roots.rs` — Root confinement tests exercise supported Android photo/Sound/OCR dictionary inputs instead of the deliberately absent model_add tool; all supported-file access assertions retained.
