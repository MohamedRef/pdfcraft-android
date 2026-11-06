# PdfCraft desktop engine → Android parity audit

Audit date: **2026-11-06**. Original source: [storytold/pdfcraft](https://github.com/storytold/pdfcraft). Last stable release: **v0.18.2**, commit `7faa4f35cefb0dea04137ec475bdc770945c22cd` (2026-11-05). Upstream main as inspected: `a0811650dec177caf06a1b143a6cc7590c85dff6` (2026-11-06, **not a stable release**). Android fork's starting main: `6b95363fa61afbeebfbb4ed97211753a9f2d72e4`, Rust workspace **0.14.0** plus selected later backports.

**Goal:** keep all mobile-suitable **engine behavior** in sync with released original PdfCraft, not `egui` desktop UI. The Android Jetpack Compose UI calls Rust through `MobileSession`, UniFFI 0.32.2, and the headless automation tool table. A shared name is **not** proof of equivalent behavior.

## Headless tool parity at v0.18.2

Parsing the 189 `t("tool_name", ...)` entries of upstream `crates/automation/src/tools.rs` against Android's 180:
- **179 shared names**. Of those, **150 table definitions appear unchanged** ignoring whitespace, and **29 definitions differ** (schemas, labels, or descriptions; review implementations separately).
- **10 released tools absent on Android**; **1 Android-only** command, `photo_find_document`, should be preserved.
- **6 additional commands only on the inspected upstream `main`** (unreleased; keep on watchlist).

| Missing in Android at stable v0.18.2 | Fit on Android | Migration |
| --- | --- | --- |
| `bookmark_export`, `bookmark_import`, `bookmark_copy` | **Yes, high** | Port `automation/bookmark_io.rs` with SAF input/output picking, page mapping, password and path tests. |
| `text_styles`, `bookmark_from_text_styles` | **Yes, high/medium** | Port engine text-style grouping, preview/progress and cancel support; limit work on scanned/huge PDFs. |
| `doc_backdrops` | **Yes, medium** | Read-only page background diagnosis; test scans, full-page images, and layers. |
| `text_script` | **Yes, medium** | Port text-edit script case handling and font fallback, then surface in mobile text formatting. |
| `doc_bates_multi` | **Yes, advanced** | Ordered multi-PDF batch operation via SAF/working copies; confirmation, per-file failures, no overwrite. |
| `model_add`, `model_render` | **Defer** | PRC/U3D/3D rendering would bring `pdfcraft-prc` and `pdfcraft-prc-render`, heavy CPU/RAM and additional ABI tests; not part of general PDF reading/editing MVP. |

After v0.18.2 upstream main adds `page_enhance_scan`, `comments_copy`, `comments_paste`, `ocr_words`, `ocr_review_apply`, `ocr_dictionary`. Mobile candidates (especially scan/OCR and comments), but **do not mark released parity without their versioned tests**.

## Underlying Rust engine gap

The crate tree itself differs substantially even where tools match. Stable `crates/engine` has ~71 files with a modularized `session/{editing,save,files,view}`, while Android's engine still has ~42 files and a monolithic `lib.rs`. The stable `crates/automation` has ~50 files vs Android's ~29; refactors account for some files and **are not themselves missing features**. Stable adds `crates/prc` and `crates/prc-render`, both out-of-scope for now. `crates/ui-egui` (~281 files) is desktop only and must **not** be copied to Android. Many `vendor/hayro-*`, `Cargo.lock`, Rust crate APIs and feature flags changed: raw directory swaps mix incompatible code.

Priority discrepancy categories:
1. **P0 document safety**: image decode bounds, parser/repair/xref, ink/masks and color, form calculations, XFA scripts, redaction (no leaked old content), save/reopen, signatures, incremental revisions, malformed PDFs.
2. **P1 Android-useful features**: page/annotation workflows, bookmarks, search and OCR, text editing, exports (Markdown, clean HTML, CSV), scan cleanup, optimizer, batch operations via SAF.
3. **P2 opt-in**: Bates across many files, advanced form scripting, 3D/PRC preview (only with memory/devices benchmarked). 
4. **Skip** desktop: egui, desktop window/dialog state, hotbar/trackpad/wheel, macOS/Windows/FreeBSD printing and packaging, Flatpak/CUPS, desktop update clients, native desktop filesystem watching. Keep Android equivalents instead.

## Current incremental progress

- Previous merged #2: unsigned page deletes full-save, malformed negative trailer `/Size`, Android page/tile buffer limits.
- Previous merged #3: bounded vendored JBIG2 decoder with Rust and Android tests.
- Previous merged #4: Rust/Android and both ARM ABI APK smoke merge gate; signed release remains separate.
- Open #5: Android working-copy recovery when content-provider write-back fails.
- This PR: upstream security/correctness backports `try_oid` sign/empty arc rejection ([#5581](https://github.com/storytold/pdfcraft/pull/5581)) and valid finite image `/Decode` pair check ([#5678](https://github.com/storytold/pdfcraft/pull/5678)); add unit regressions for sign and create.

## Versioned migration checkpoints

1. **0.18.2 engine foundation**. Update `Cargo.toml`, Cargo.lock, and all transitive core crates/vendor patches as a **coherent snapshot**, pin immutable upstream SHA, then test original PDF fixtures. **Do not** publish a release as engine 0.18.2 until this succeeds.
2. **Rendering + editing**. Test viewer/text extraction/CMYK/soft masks/JBIG2/fax, undo/redo and save/reopen on real and malformed files, before exposing newer automation commands.
3. **Mobile-capable new commands**. Port the seven bookmark/text/background commands first, then advanced Bates, preserving the Android-only scan helper. Regenerate UniFFI Kotlin bindings with exactly the native `.so` used in each APK.
4. **Release gates**. Test Rust unit/property suites; Android JVM; each ABI release-mode native compile; emulator instrumentation for startup and PDF workflows; 16 KB page alignment; OCR model availability; SAF revoke, partial write, out-of-space recovery; signed/encrypted PDF and low memory. Pin APK `applicationId`, versionCode progression, and production signing certificate.
5. **Post-release sync**. Review commits added after `v0.18.2` only against a *new stable release*; create separate small PRs, never automatically merge upstream `main`.

This report is a **source-level audit**, not proof of full runtime or feature parity. Current Android native build must continue to identify itself with its real underlying code version until the coherent core upgrade is verified.

## Implementation log — 2026-11-06

- Work started against `sync/storytold-0182-android-safe`; upstream target remains immutable stable `7faa4f35cefb0dea04137ec475bdc770945c22cd`.
- Existing work preserved: [#5](https://github.com/MohamedRef/pdfcraft-android/pull/5) working-copy recovery, [#6](https://github.com/MohamedRef/pdfcraft-android/pull/6) validation backports/audit, and [#7](https://github.com/MohamedRef/pdfcraft-android/pull/7) forms/XFA safety. At inspection, #6 and #7 Rust, Android JVM and both ARM ABI APK smoke checks passed; signed release jobs were skipped. These checks establish their respective small changes, not full engine parity.
- In progress: inspect repository instructions, pin and compare the stable core snapshot with Android adaptations, then implement and test coherent migration increments. Each delivered increment will update this file with changes, commands/results, unresolved gates and the next action. No release/version parity claim yet.

### Foundation source import (implementation branch)

- Imported all 32 existing mobile engine/support crates and all nine existing patched vendor crates from stable `7faa4f35cefb0dea04137ec475bdc770945c22cd` together. Source inventory and adaptation list: `docs/android-engine-source.json`. This replaces the old monolithic engine/automation internals with the released modular implementation; desktop UI/apps and PRC rendering crates remain excluded.
- Preserved Android `MobileSession`, UniFFI **0.32.2**, Compose UI, package/signing identity and native pixel caps. Preserved Android-only `photo_find_document` and its EXIF tests; its automation input now uses the stable bounded file reader. Preserved the 256 KiB bounded XMP metadata API used by File Details.
- Stable source includes the earlier save, OID/image validation, JBIG2 and forms/XFA fixes. PR #7 remains separate; this import must not be described as merging it.
- The stable tool implementations include the eight previously absent non-3D tools, including advanced Bates. Their availability in a headless table does **not** establish a usable SAF/Compose workflow. Mobile UI integration and runtime gates remain pending.
- Local verification host: Apple Silicon macOS, installed Rust **1.99.0** (matching `rust-toolchain.toml`; an existing environment override selected 1.92.0, so local commands explicitly use `cargo +1.99.0`). No Android SDK was found at the usual local path. Dependency resolution/native boundary compile is in progress; Android ABI/JVM checks will run in PR CI. No test success is claimed at this checkpoint.
- Workspace/native version remains **0.14.0** pending verification. No signed APK or release is published.

Implementation PR: [#8](https://github.com/MohamedRef/pdfcraft-android/pull/8), branch `sync/storytold-0182-engine-foundation`, initial source import `9ef4f12`. All source-import notes above refer to that **draft PR**, not code merged into this audit branch. The source inventory is available on the implementation branch.

### Native ABI compilation checkpoint: 2026-11-06 (CI triage)

- **Evidence:** [PR #8](https://github.com/MohamedRef/pdfcraft-android/pull/8), pinned import commit `9ef4f12a8f4650d34e9d4ed583ca1956e62735c4`, [Actions run 42202945010](https://github.com/MohamedRef/pdfcraft-android/actions/runs/42202945010), `rust-tests` job `127842471883` failed at `cargo test --locked` (exit 101).
- **Five concrete native wrapper compatibility errors:** (1) `pdfcraft_render::sound_wav` was removed; (2) `RenderConfig.hide_notes` was removed, but Android draws its own sticky-note icons; (3) `Annotation.modified_raw` was removed; (4) `Annotation.opacity` was removed; (5) `Annotation.width` was removed.
- **Safety requirement:** do not silence the errors by dropping voice comments or suppressing *all* comments. Preserve audio playback and the visual distinction between sticky notes and other rendered annotations. Use the new `Document::comment_props(page,index)` to retrieve opacity/line width, and use the released `Annotation.modified` date for display (note: this is formatted rather than the former raw timestamp). Regression tests should cover voice WAV, comment styling, sticky-note visibility, and release-mode ABI builds.
- **Work split:** solve the above as a dedicated mobile API-compatibility increment, isolated from the upstream source snapshot to avoid mixing dependency changes with frontend adapters. A Rust compilation success alone does not prove full Android runtime stability. Only the two confirmed Rust sub-jobs from older #6/#7 are green; do not call #8 successful until every required current check is green.
- **Next verification:** native wrapper `cargo test --locked -p pdfcraft-android`, Rust crate suites including import changes, Android JVM tests, both ARM ABI APK smoke jobs, and emulator SAF/save/reopen/low-memory tests. Keep #8 draft and retain workspace version `0.14.0` until these succeed.

### Android native bridge compatibility PR — 2026-11-06

- Created [PR #9](https://github.com/MohamedRef/pdfcraft-android/pull/9) (branch `fix/0182-android-native-bridge`, commit `b57d138772d9af4310c6c6570057ee4bd62026f6`), **stacked on** the coherent stable-source import [PR #8](https://github.com/MohamedRef/pdfcraft-android/pull/8). Seven files changed.
- Restored Android-only `RenderConfig.hide_notes` and vendored hayro `InterpreterSettings.hide_notes`: skip only `/Text` sticky-note appearances because Compose draws them separately, while retaining highlights, ink and other PDF annotations.
- Restored PDF Sound comment → WAV (`pdfcraft_render::sound_wav`) to the new modular render inspector. Guarded decoded sound to **32 MiB** (earlier code allowed substantially larger allocation). Added WAV header/codec/error regression tests.
- Mapped the released `Annotation` model to the unchanged UniFFI `CommentInfo` record via `Document::comment_props(page,index)` for raw date, opacity and border width; bounds-check and default missing values on read-only/invalid sources.
- **Status:** code committed and PR opened; CI and device runtime checks **pending**. Do not confuse uploaded code with a passed build. Review audio-sticky-note tests, then require Rust/JVM/ARM ABIs and emulator checks before merge.
- **Dependency order:** #6 (audit and small validation) → #8 (stable core import) → #9 (mobile-native API compatibility). #7 (XFA small fixes) overlaps code in #8 and needs post-import comparison/rebase, not a blind merge. #5 (provider write-back safety) remains separate, must be integrated before describing Android saves as protected.

### Reproducible upstream source verification — 2026-11-06

- Created [PR #10](https://github.com/MohamedRef/pdfcraft-android/pull/10) (commit `0fce303a9563adce487248e086a7765b59364cb3`), stacked on #9 → #8 → #6; draft until CI passes.
- Compared the **entire chosen engine/support and patched vendor tree**, not only feature names, with original `storytold/pdfcraft` stable `7faa4f35cefb0dea04137ec475bdc770945c22cd`: **970 original source files = 956 byte-identical + 12 reviewed Android-specific changes + 2 intentional omissions**. No untracked-in-source additions among the selected crates and vendor directories.
- Added `docs/android-engine-provenance-lock.json`, recording immutable upstream Git blob SHAs per source path and review reasons for each exception, and `scripts/verify_android_engine_source.py`. The new GitHub CI `rust-tests` preflight checks all tracked selected files, source pin, workspace membership and mobile exclusions; unreviewed drift fails before compilation. This is a **source-integrity invariant**, not a substitute for real Android/emulator PDF tests.
- **Reproduce after checkout of #10:** `python3 scripts/verify_android_engine_source.py`. The script requires Python 3.11+ and Git, no network. To update the source in a later stable release, explicitly review the new tag/immutable SHA, regenerate lock entries and exception reasons, then update this log and rerun Rust/Android/ABI/emulator test gates.
- **Status when recorded:** #8's initial native and Android test attempts failed because of the same five API mismatches; #9 contains the compatibility code and had Rust/Android tests running. #10 CI newly opened; **no result or merge claim** until its run completes. Branch stacks must be merged in dependency order, and #5's SAF recovery remains a separate pending integration.

### Sequential merge rule and focused PR #5 safety review (2026-11-06)

**Updated operator rule (requested by owner):** Do not start new feature/engine PRs while existing PRs remain incomplete. Pick the oldest independent open PR, fix it on its existing branch, require current-`main` Rust/JVM/**both ARM ABI release APK** gates plus actual Android UI/provider behavior checks, merge into `main`, and only then move to the next PR. An integration or renderer API change is NOT complete merely because Cargo compiles. Draft PRs #6/#7/#8/#9/#10 stay on hold in their present state; do not stack more PRs or merge a later branch ahead of its prerequisites.

**Current focus: [PR #5](https://github.com/MohamedRef/pdfcraft-android/pull/5)**, Android content-provider write-back recovery. Earlier run `42190580006` passed only Rust and Android unit jobs; its branch predated the newer dual-ABI APK merge gate, so that success was insufficient to merge.

- On the **existing PR #5 branch** `fix/android-working-copy-recovery`, commit `5987d996610ba46b24051bc6e4a2657fc23e3f68` merged the *current main* baseline (`6b95363fa61afbeebfbb4ed97211753a9f2d72e4`) without changes to production signing or application ID.
- Fixed same-URI reopen: Android can request the document again while it's already open. The prior path copied potentially stale content over the live working file before auto-save. The app now reuses the open document without refreshing its bytes.
- Fixed user-confirmed **Discard**: removing the durable pending working copy stops previously discarded edits being revived on the next launch. Ordinary automatic cleanup continues to preserve pending copies. A marker with a missing local recovery file now fails closed instead of trusting the potentially truncated original.
- Serialized **Save a copy** under the same coroutine mutex as auto-save to stop simultaneous reads and writes of the local PDF. Added JVM tests for discard and refusal to mark an invalid recovery directory.
- **New verification run:** [Actions 42206240320](https://github.com/MohamedRef/pdfcraft-android/actions/runs/42206240320). At this log checkpoint the run was **in progress**. DO NOT mark PR #5 merged unless Rust, JVM, both ARM APK smoke and the aggregate merge gate all pass. Critical manual/instrumentation gates: Android launch, open/save/reopen, same URI reopen, cancel Save a copy, failed/partially truncating SAF provider, deliberate Discard, revoked access, out-of-space, force-stop/recovery. Where no emulator/device test was run, explicitly label runtime as **unverified**, not 100% compatible.

**Required next action:** complete and verify PR #5 alone, merge it into main only after passing the agreed gates, then resume the next existing PR. No new PR before that.


### Owner-requested sequential gate — verified GitHub checkpoint (2026-11-06)

- **Operating policy:** finish the **currently active oldest independent PR** before starting any new feature. Sync with the latest target branch, test, review for feature regressions/discrepancies/conflicts, and merge only after *all* required checks including applicable device/provider behavior are verified. After merging, verify the resulting main and then select the next dependent PR. Do not mistake mergeability or green unit tests for runtime parity.
- **PR #5 inspected:** https://github.com/MohamedRef/pdfcraft-android/pull/5 remains **open, draft, unmerged**, head `5987d996610ba46b24051bc6e4a2657fc23e3f68`, base `main` at `44f4a318d24e77d349c4ee4ea2ae6f9915c70cb3`; GitHub reports mergeable=true, which does not waive test gates.
- **CI run checked:** https://github.com/MohamedRef/pdfcraft-android/actions/runs/42206240320 at inspection: `android-tests` **success**, `rust-tests` **success**, `APK smoke (arm64-v8a)` **in_progress**, `APK smoke (armeabi-v7a)` **in_progress**, `signed-apk` **skipped**. Do not claim an aggregate pass based on partial results. Recheck the run and merge-gate status against this exact SHA.
- **Still unverified:** emulator/physical-device launch, save/reopen, duplicate URI reopen, Save a copy cancellation, truncating or failing SAF provider, discarded edits, revoked permissions, out-of-space, force-stop/recovery, and post-merge main verification. No device-level success is asserted here.
- **Execution lock:** do **not** start #6/#7/#8/#9/#10 or additional engine features, and do **not** merge #5 until outstanding ABI/aggregate and device/provider checks are satisfied. Document each verification with run URL, head SHA, result and blockers before changing states. The source-import PR chain remains separate and draft.
- **Handoff next action:** finish the #5 ABI/merge-gate check, perform the missing Android runtime tests, address failures on #5 branch, update this document with evidence, merge #5 only after every agreed gate passes, verify main, then proceed to #6. This checkpoint changes documentation only; it does not assert new code, tests, or a merge.

### Device-specific Android support policy (2026-11-06)

Owner's primary supported physical devices: **Samsung Galaxy S24 Ultra** phone and **Samsung Galaxy Tab S11** tablet. Treat these as the concrete target hardware for UI layouts, performance and validation, instead of broad unsupported Android ABI/device promises.

- Prioritize **arm64-v8a** APK/native library generation for these target devices. The existing `armeabi-v7a` CI smoke gate is currently required by the established PR #5 merge contract; **do not silently remove or skip it mid-PR**. After PR #5 is completely verified and merged, consider a separate, explicit CI/packaging simplification change only after checking Gradle ABI filters, UniFFI/native bundling, release distribution, and any other consumers. Document evidence before removing older ABI requirements.
- Validate **phone and tablet adaptive Compose UI** (portrait/landscape, density, large-screen sizing, folding/resizing and multi-window where supported), safe insets, accessible touch targets, file pickers and PDF canvas interactions. For the Tab S11, check stylus/S Pen input on features where it applies; avoid assuming phone-only navigation sizes work on tablet.
- Run Rust and Android JVM tests, native Android builds, and relevant PDF open/edit/save/reopen/SAF regressions. Record whether tests were performed on physical S24 Ultra / Tab S11, emulator, or CI only; **never claim physical-device testing without evidence**.
- Avoid unnecessary desktop-only components, irrelevant ABI builds and unrelated feature work. Preserve the sequential PR rule: finish, test and merge the existing feature before starting a new one. Current first blocker remains PR #5; this paragraph changes documentation only, not CI, code, compatibility, or merge status.

### Queued feature (NOT STARTED): About → Check for updates / download APK (2026-11-06)

**Owner request:** add a user-triggered About-screen update checker for the newest compatible **Alpha, Beta, Preview, or Stable** Android APK build, suitable for Samsung Galaxy S24 Ultra and Galaxy Tab S11. **Do not start this feature until the existing sequential PR queue is tested and merged according to the owner rule**; current blocker is PR #5. This is backlog/specification only, with no change to Android source or CI.

Design/acceptance criteria for a future dedicated PR:
1. In About, show installed versionCode/versionName, release channel and build identifier, and a **Check for updates** action; fetch metadata on user request and show version, build date, channel, release notes and download size when available, with a clear **Download** action and progress/error/retry states.
2. Query a **trusted first-party GitHub Releases feed** (including prereleases) and/or explicitly published, immutable APK builds from GitHub Actions. Do not equate a new Git commit/workflow with an installable APK. **At inspection the GitHub Releases API returned an empty array** for this repo; the publish pipeline must be established/verified before this can work.
3. By default choose the **newest compatible published build across all four channels**, not just latest stable. Clearly label prereleases and allow opting for Stable-only later; sort by an authoritative monotonically increasing Android `versionCode` plus trustworthy build/release metadata, not a lexical version-string comparison or a mutable branch name. Never offer a lower/same versionCode as an upgrade; identify and report same-version differently signed builds.
4. Restrict to verified `arm64-v8a` APKs targeting the supported devices and Android SDK, correct applicationId and signing certificate, and compatible minSdk. Never automatically install or silently bypass Android package installer. Use system `DownloadManager` or a robust streamed download with integrity checks (published SHA-256 plus final file hash), scoped/temporary storage and `FileProvider` install intent with user consent; handle Android permission and unknown-app-install restrictions appropriately. Avoid storing GitHub credentials in the APK or exposing private Actions artifacts to anonymous users.
5. Preserve app data when upgrading, provide offline/no update/error/auth/rate-limit/network/interrupted-download handling and reject unsigned, mismatched, revoked, wrong-ABI or corrupted APKs. Tests include version/channel ordering, semver/preview precedence versus `versionCode`, missing assets, wrong signature/ABI, idempotence, cancellation, and Android About UX on both target screen sizes.
6. Require current-main merge integration, JVM/Rust and APK checks, actual install/update test on S24 Ultra/Tab S11 (or explicitly mark unavailable), and a post-merge check. Log GitHub URLs, SHA, test results and remaining risks here as the feature moves forward.

**Status:** recorded as a future feature request only; no PR opened and no installation/updater behavior implemented at this checkpoint.

### Update checker implementation checkpoint — priority changed by owner (2026-11-06)

The owner explicitly promoted **About → Check for Updates** to the **first next feature**, overriding its former backlog-only priority. This is an **isolated draft implementation**, NOT a request to skip validation or merge unverified PRs. Older open PRs #5–#10 remain preserved and are not claimed merged.

- New working branch: `feat/about-github-apk-updates`, based on `main`. Commits so far: `01402b1` updater logic, `88cc51f` About UI, `53ed5d0` Internet permission, `4686f23` version/channel unit tests, `d213689` supported icon fix, `a807b25` cleanup.
- About now has a manual Check for updates action and Download APK action, with busy/no-update/failure/download-started messaging. It calls the owner's **public published GitHub Releases API** (not upstream PdfCraft and not draft Releases), handles Alpha/Beta/Preview/Stable names, chooses newer versions using the existing Gradle numeric version scheme, and accepts only the repo's `pdfcraft-android-<version>.apk` ARM64 artifact name. The system Download Manager saves the APK to Downloads; **installation is manual** via Android. No automatic/silent installation or background monitoring is claimed.
- **Verified repository publishing blocker:** the public Releases API currently returned `[]`, and the current `.github/workflows/release.yml` creates **draft** releases requiring manual publication. Therefore the checker will correctly show no published APK until a signed GitHub Release is published; merely having a successful Actions artifact does NOT make an update available. A future preview workflow must explicitly publish an authorized, compatible release rather than distribute private/temporary CI artifacts without validation.
- **Version code limitation to resolve before production:** the existing Gradle numeric scheme does not give distinct ordering to equal-number Alpha/Beta/Preview builds. The updater intentionally refuses equal or lower version codes. Distinct cross-channel upgrades require coordinated strictly increasing versionCode publication; do not claim all cross-channel switches are installable.
- **Security limitations (must address before merge):** source-host and APK name are filtered, but the first increment does **not** yet verify the downloaded binary's SHA-256 or signing certificate itself, nor trigger verified package installation. The user's installer can reject mismatched signatures; this is **not** equivalent to end-to-end updater verification. Add trusted checksums/certificate checks and downloaded-file validation before offering a one-tap installer. Review CDN redirects, publication trust and Release asset integrity.
- **Testing status:** source files committed and a JVM unit test added; no successful Android CI, ARM64 APK build, or S24 Ultra / Tab S11 device tests are claimed at this checkpoint. The new PR must remain draft until verification and review are complete. In parallel, do not start a second new feature.
- **Next operator action:** open a draft PR, run Android JVM/native ARM64 CI, inspect compile failures, and test release API empty/error/asset selection. Validate PDF regressions, tablet/phone UX, download completion and manual installation, and build signing. Document the exact green CI run and device evidence before merge. Keep previous PR queues intact.
