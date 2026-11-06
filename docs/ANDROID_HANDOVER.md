# PdfCraft Android - development handover

**Snapshot: 2026-11-06.** This is a living handover. Before making any changes, recheck GitHub `main`, open PRs, Actions, releases, and the mandatory audit document. Historical snapshots are not a substitute for live checks.

## Repository and source of truth

- Android repository: https://github.com/MohamedRef/pdfcraft-android
- Mandatory append-only audit: https://github.com/MohamedRef/pdfcraft-android/blob/sync/storytold-0182-android-safe/docs/android-desktop-engine-parity-0.18.2.md
- This handover: `docs/ANDROID_HANDOVER.md` on `main`.
- Desktop upstream: https://github.com/storytold/pdfcraft , pinned at **v0.18.2**, source commit `7faa4f35cefb0dea04137ec475bdc770945c22cd` in `docs/android-engine-source.json`; Android provenance allowlist in `docs/android-engine-provenance-lock.json`.
- Latest *release-source* commit verified in `main` at this snapshot: `d2baefdd064554766b1be281209060687c23033e`. A documentation-only handover commit can advance `main` beyond that SHA without changing the released binary; do not confuse the two.
- Open PRs at snapshot: **0**. PRs #5 to #11 are all merged, including stacked #9 and #10 into the #8 foundation branch before #8 landed in `main`. No new performance feature PR has been opened yet.
- Device targets: **Samsung Galaxy S24 Ultra** and **Galaxy Tab S11**, Android APK **arm64-v8a** only by default; `x86_64` opt-in for emulator builds, not a distributed extra ABI. Respect responsive Compose layouts, One UI multi-window and S Pen.
- Application ID: `io.github.mohamedref.pdfcraft`.

## Completed work (verified merged)

| PR | Result |
| --- | --- |
| [#5](https://github.com/MohamedRef/pdfcraft-android/pull/5) | Protect local PDF recovery copies when SAF/provider save fails; no data-sacrificing overwrite for test convenience. |
| [#6](https://github.com/MohamedRef/pdfcraft-android/pull/6) | Backport Android-safe PDF validation fixes from upstream v0.18.2. |
| [#7](https://github.com/MohamedRef/pdfcraft-android/pull/7) | FormCalc/XFA safety and numeric/hex regression fixes; integrated into the modular engine. |
| [#9](https://github.com/MohamedRef/pdfcraft-android/pull/9) | Android native bridge preserves voice, sticky notes and comment formatting; merged into #8 foundation. |
| [#10](https://github.com/MohamedRef/pdfcraft-android/pull/10) | Source provenance pin/checker for stable engine with documented Android exceptions; merged into #8 foundation. |
| [#11](https://github.com/MohamedRef/pdfcraft-android/pull/11) | About > Check for Updates, published Alpha/Beta/Preview/Stable GitHub Release discovery, monotonically newer `versionCode`, APK SHA-256/package/signing-certificate verification, user-approved Android installation, no silent install. Merge `deaf3676140dd2cd6c76bd7c195faa670115329b`. |
| [#8](https://github.com/MohamedRef/pdfcraft-android/pull/8) | Merge suitable stable desktop v0.18.2 Rust engine into Android, retaining mobile bridge, Forms/XFA safety and #11 updater; merge `72083071ac3395b0c3a8a378041f23a8cb714d4f`. |

**Integration checks:** [PR #8 Actions 42218541208](https://github.com/MohamedRef/pdfcraft-android/actions/runs/42218541208) passed Rust (including provenance), Android JVM, release-mode ARM64 APK smoke and aggregate merge gate for exact head `5ea3f72e19f72a7c2545cda05e1cd2f91f3ed1bd`. The merge commit used the same source tree. The intermediate post-merge `main` [run 42221318495](https://github.com/MohamedRef/pdfcraft-android/actions/runs/42221318495) was **cancelled** by a later push; never label it green.

## Latest officially published APK

- **PdfCraft 0.0.7-beta.4 (7042)**, [public non-draft prerelease](https://github.com/MohamedRef/pdfcraft-android/releases/tag/v0.0.7-beta.4), signed ARM64 APK.
- [Direct APK](https://github.com/MohamedRef/pdfcraft-android/releases/download/v0.0.7-beta.4/pdfcraft-android-0.0.7-beta.4.apk); 48,372,020 bytes; asset SHA-256 `b8c967665383bd5641339577dd22635af696b85df08d425c406dd9efc65a475c` (GitHub release asset metadata). A `.sha256` file accompanies it.
- [Release-source CI 42221831656](https://github.com/MohamedRef/pdfcraft-android/actions/runs/42221831656): Rust/provenance, Android JVM and **permanently signed APK job** all completed **success** for source SHA `d2baefdd064554766b1be281209060687c23033e`. On a `main` push, PR-only ARM64 smoke/merge gate jobs are **skipped by design**.
- Signing workflow verified package ID and APK cert. Certificate SHA-256 `0cb9f18f2e41e2cf0b0f1866b409adff798c1d83dfe6325d03fbb0b13ee051c1` matches earlier public **0.0.7-beta.3 (7032)** signing logs. This supports signature-compatible upgrades but **does not prove** an upgrade was performed or that user data survived one.
- The predecessor [0.0.7-beta.3 (7032)](https://github.com/MohamedRef/pdfcraft-android/releases/tag/v0.0.7-beta.3) was published *before* the complete v0.18.2 engine import. Do not confuse it with beta.4.
- Gradle: `apps/pdfcraft-android/android/gradle.properties` has `pdfcraft.version=0.0.7-beta.4`, `pdfcraft.versionCode=704`, `pdfcraft.abis=arm64-v8a`. `app/build.gradle.kts` computes the actual single-ABI manifest `versionCode=7042` by appending ABI digit `2`.

## Newly reported issue - large PDFs open more slowly than Xodo

**User observation:** PdfCraft takes noticeably longer than Xodo to open large PDFs. There is **no collected side-by-side timing or device trace yet**, and **no performance fix has been coded or tested**. Treat the following as code-confirmed potential bottlenecks, not measured causal attribution:

1. `apps/pdfcraft-android/android/app/src/main/java/io/github/artcraftandroid/pdfcraft/engine/DocumentFiles.kt` > `workingCopy(uri)`: non-app-owned `content://` / external documents are streamed in full to an app-private PDF before opening. `dropCopyIfReachable` can delete reusable copies when the original is reachable; opening again can repeat the full copy. The existing `PendingWorkingCopy` recovery marker takes priority and **must never be bypassed**.
2. `.../ui/AppViewModel.kt` > `open()`, `openFile()`, `loadLinks()`: after the source copy and native `eng.command { open(...) }`, the app loads widgets, comments, **links for every page** in one blocking command, and signatures before setting `document = doc` and showing the viewer. This introduces work that scales with page count before the first visible page.
3. `apps/pdfcraft-android/src/lib.rs` > `MobileSession::open`: the JNI/UniFFI bridge currently passes an absolute path into `doc_open`; the Rust engine reads/parses the file into memory. Removing the Android copy by itself **does not eliminate parsing and memory costs**.
4. `.../engine/LocalFiles.kt` thumbnail generation for `content:` inputs also copies the file when an uncached thumbnail is required. Rendering has its own queue (`PageImages.kt`), so measure **time to first paint** separately from **time until viewer UI**.

**Desired behavior:** read from the original source *when safe and supported*, show the first page quickly, keep editing/voice/annotations/forms/signatures/SAF save protections intact. Android SAF `content://` is not necessarily a seekable filesystem path. `ParcelFileDescriptor` and direct read-only access can be investigated for supported providers, with an explicit fallback to protected local working copy for cloud/non-seekable sources. If enabling direct read, keep the descriptor valid across native access and still use copy-on-write/rollback for edits; do not write directly to the provider as an unsafe shortcut.

## Next actions, strictly sequential

1. **Recheck** `main`, PR list, CI jobs, Release metadata, required signing workflow, and audit file. Never assume the above snapshot is current.
2. Start a **single focused performance change**, not multiple parallel features: instrument `pick/open -> content provider copy -> Rust open -> widgets/comments/links/signatures -> viewer shown -> first page render`, using elapsed monotonic time and size/page count without logging user file names, PDF contents or private URI tokens. Benchmark identical PDFs in PdfCraft and Xodo on the target Samsung phones/tablets, local vs SAF/cloud when possible. Do not fabricate results.
3. Address **eager all-pages link scanning** as a low-risk first optimization: lazy per-visible-page/on-demand link hydration or background fetching without blocking first paint; make sure link taps, search, forms, comments, signatures, audio and cancellation remain correct. Test PDFs with thousands of pages, embedded links, forms, XFA, encrypted files and signatures.
4. Investigate **read-only in-place/seekable file descriptor support** and **safe reuse of unchanged working copies**, including cloud fallbacks; preserve `PendingWorkingCopy`, provider permission loss recovery, crash-safe write-back and all existing Android capabilities. Never silently reuse stale copies or trust size/mtime alone when a provider gives unreliable metadata.
5. Run relevant Rust, Android JVM, provenance, ARM64 release-mode APK, aggregate merge gate, and regression tests **against the latest `main`**, fix on the same feature branch, and only merge when everything required is green and conflict-free. Verify the new `main` after each merge. No new feature until the prior one has been fully merged/verified.
6. For a new user-approved release: preserve the permanent PKCS12 signing identity, confirm package/versionCode/digest, publish a **public non-draft** GitHub Release with direct signed ARM64 APK and a `.sha256`, verify the release API and updater discovery. Do not publish a debug-signed test artifact as a release.
7. Device regression gates outstanding: install/update beta.3 -> beta.4 and beyond without loss of app data, open/switch/scroll/zoom large PDFs and measure first paint, edit/save/SAF revoked and failed write, OCR, bookmarks, links, comments/voice/sticky notes, FormCalc/XFA, digital signing, multi-window and S Pen. **None of these physical-device end-to-end tests is verified yet**.

## Release numbering and project rules

- Display every build as **`versionName (actualManifestVersionCode)`** as required by `AGENTS.md`, e.g. `0.0.7-beta.4 (7042)`.
- The owner explicitly chose to **keep beta.4 numbering unchanged**. Starting with *future* updates: SemVer `MAJOR.MINOR.PATCH` with beta labels; e.g. small bug fixes may move `0.0.8-beta.1` then `0.0.9-beta.1`, larger backward-compatible enhancements `0.1.0-beta.1`, a major milestone `1.0.0`. Update both `pdfcraft.version` and **matching computed** `pdfcraft.versionCode` according to `app/build.gradle.kts`; check the resulting manifest integer increases across all existing published channels.
- Never delete or disable Android functions to make CI green. Preserve signer certificate, user's documents, recovery paths, render/edit/sign/audio/comments/OCR and Android responsive support. Never claim real-device tests or faster-than-Xodo results without measurements.
- Required progress logging: append commits, CI URLs, pass/fail/skipped jobs, merge SHAs, limitations and device-unverified notes to the **mandatory branch audit document** linked at the top; update this handover at meaningful milestones.

## Starting instruction for the next developer

Read this file and the full mandatory audit. Inspect live GitHub. The last confirmed published product is **0.0.7-beta.4 (7042)**, all earlier PRs are merged, and the *newest unresolved request* is **large-PDF opening latency vs Xodo**. Make a measured, safe, Android-focused performance improvement through one PR, not just a plan. Keep all functionality and publish a signed version only after verified merge and release gates.

### Large PDF performance — PR #12 review checkpoint (2026-11-06)

- Refreshed live state: main `05963db9a69901cc4b7a9ff38524b81163e9ae69`, latest release unchanged **0.0.7-beta.4 (7042)**, one existing draft PR [#12](https://github.com/MohamedRef/pdfcraft-android/pull/12) at `ccf8a519`. Continued that PR and merged latest main into its branch; no second feature PR.
- Narrowed change to lazy visible-page links plus opt-in monotonic instrumentation. Removed metadata-only working-copy cache: provider size/mtime cannot guarantee unchanged contents. Recovery/full-copy safety retained. Direct seekable/provider reading and thumbnail copy elimination are deferred pending safe lifetime/copy-on-write tests; native parsing unchanged.
- Links load in batches of at most 12, drain the visible range, invalidate on generation changes, discard stale results after document switch/edit and propagate coroutine cancellation. Link map is Compose observable. Widgets/comments/signatures still load before display to preserve interactive behavior.
- Enable timing: `adb shell setprop log.tag.PdfCraftOpen DEBUG`, then `adb logcat -s PdfCraftOpen:D`. Fixed-stage durations: provider metadata/copy, native open, widgets/comments/signatures, visible links and page rendering. `viewer_frame_ms` and `first_page_canvas_ms` are elapsed since open request (including copy/native); Canvas submission is not proof of GPU display. Disable with `adb shell setprop log.tag.PdfCraftOpen INFO`. No URI, password, file name or contents in these timing records.
- Tests added for 10,000-page selection, multiple batches, invalidation and extreme viewport bounds. CI pending on the new head. No Samsung/emulator execution, 100/500 MB sample benchmark or Xodo comparison available locally; **no measured speed improvement claimed**. Release/version unchanged until verified merge.

- Follow-up review: hydrate a tapped page before dispatching the first link tap if viewport loading has not finished; abandon the tap if the document/generation changes while waiting. Existing field/comment precedence retained.

- Release preparation on the same PR: next patch beta `0.0.8-beta.1` with base code `801`, expected ARM64 **0.0.8-beta.1 (8012)**. Actual APK manifest must confirm 8012. Existing **0.0.7-beta.4 (7042)** and its public asset remain unchanged. On verified merge, the existing main workflow builds with the permanent key and publishes the new version only after Rust/Android tests and signing/package checks. Do not report publication until that run and Release API succeed.

### Next sequential performance increment: seekable provider snapshots — 2026-11-06

- Owner explicitly requested the next feature after #12 merge, while #12 post-merge signed publication continues. Started from main `172be4b10daa1ff98f848074d07b799b40d6d21a`; no outstanding feature PR at start. PR #12 exact-head tests passed; its main run is [42235100637](https://github.com/MohamedRef/pdfcraft-android/actions/runs/42235100637), currently Rust success/Android pending.
- In-place opening is **not enabled**: save, print, share, signing and encrypted-copy paths require a durable local file; removing it before a full lifecycle redesign risks data loss. This increment instead transfers seekable regular provider asset ranges to a fresh local snapshot through `FileChannel.transferTo`, preserving the full working-copy/recovery design. Asset offset/declared length respected; unknown/nonregular sources stream, unsupported descriptor paths retry the original provider stream into a truncated temporary file. Source is read-only. No metadata-only cache.
- Transfer handles partial progress, switches to a bounded 256 KiB buffer when zero/unsupported transfer occurs, rejects unexpected EOF/overflow and zero progress, and cleans up partial temporary files before returning errors. Added JVM regressions for asset subranges, short transfers, zero-transfer fallback and truncation/overflow. Tests pending; **no device speed claim**.
- API references: https://developer.android.com/reference/java/nio/channels/FileChannel and https://developer.android.com/reference/android/content/res/AssetFileDescriptor . Timing adds `provider_descriptor_copy` and `provider_stream_fallback`; no paths/content logged.
- Prepared next sequential patch **0.0.9-beta.1 (expected ARM64 9012)** to avoid reusing #12's intended **0.0.8-beta.1 (8012)** release number. Actual manifest and permanent signer require CI verification. No merge or second release yet. First Aid 2026 owner baseline remains approximately 5 s PdfCraft / 1 s Xodo, not an instrumented measurement.
