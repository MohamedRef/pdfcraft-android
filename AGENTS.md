# Repository instructions

## Android build-number display (required)

For **every new Android build** (preview, alpha, beta, stable, CI, or release), show its actual Android build number in parentheses immediately after the version wherever the build is presented to a person.

**Required format:** `<versionName> (<versionCode>)`

Example: `PdfCraft 0.0.7-beta.2 (7022)` for an `arm64-v8a` APK, or `PdfCraft 0.0.7-beta.2 (7021)` for an `armeabi-v7a` APK.

- Use the **final APK manifest's** integer `versionCode` as the build number, not a GitHub Actions run number, commit count, or the unadjusted `pdfcraft.versionCode` in `gradle.properties`.
- The Android Gradle configuration in `apps/pdfcraft-android/android/app/build.gradle.kts` appends an ABI-specific digit. Verify the displayed build number separately for each APK variant.
- Apply the format consistently in the app's About/version display, in-app update information, GitHub release titles and notes, build summaries, test handoffs, and any user-visible APK/artifact labels.
- Preserve machine-readable version tags and any filenames required by automated update/install workflows; if those cannot contain parentheses, show the formatted name alongside them instead. Do **not** change the package's numeric `versionCode` or signing identity just to change how the build is labeled.
- Before marking a new build ready for download, verify that its displayed `versionCode` matches the built APK's manifest.

For pull requests and signing requirements, follow `.github/ANDROID_SIGNING.md` and its merge-gate checks.

## Release notes (owner requirement)

Every public release must clearly describe new features, fixes, improvements and known/unverified behavior. Use categorized generated PR notes (`.github/release.yml`) and supplement them with concise user-facing behavior and validation limits. Do not claim a measured speed-up, full feature parity or device success unless evidenced. Include actual `versionName (versionCode)` and source/CI links. Keep published APKs immutable; correct notes without silently replacing an existing build.

## Document scanner (owner requirement)

Before working on anything under `apps/pdfcraft-android/scan`, the Scan screens or a `Scan S…` issue, read `docs/ANDROID_SCAN_HANDOVER.md` (state, remaining work, rules, what was learned) and the tracking issue #286. In particular:

- Every scanner feature ships with a testing option (`ScanLab`), its decisions in the diagnostics log, and the same in a kept scan's page file. The owner tests on his phone with these.
- The owner's photos, and anything made from them, never go into the repository, an issue or a pull request.
- Say in each pull request what was measured and on what, and what was not verified. Nothing in the scanner has run on a phone unless a document says so.
- Update `docs/ANDROID_SCAN_HANDOVER.md` in the same pull request that changes the scanner's state.
