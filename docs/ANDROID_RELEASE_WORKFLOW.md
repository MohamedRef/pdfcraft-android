# Android release flow: clear and verified stages

This workflow is intentionally split into distinct named GitHub Actions jobs:
1. Rust engine and provenance tests
2. Android JVM and UI unit tests
3. Build and verify signed ARM64 APK, produce checksum, and upload two immutable artifacts
4. Publish GitHub Release and update feed, only after the verified signed artifact is available

The third job uses the original PKCS12 keystore, checks the Android application ID, ABI and SHA-256 certificate, and creates the APK and `.sha256` in the `dist/` artifact. The fourth job downloads **the exact artifact from the same workflow run**, rechecks checksum, manifest version/build and permanent signer fingerprint, and publishes the release. No parallel signing or fallback debug build exists.

## What's New formatting

The release title is `PdfCraft <versionName> (<manifestVersionCode>)`. Owner-reviewed `docs/releases/<versionName>.md` contains headings for New features, Fixes, Improvements, Known limits and testing, and Installing. `scripts/verify_android_release_notes.py` fails CI on missing/incorrect version headings or missing sections. The publishing job appends source commit, CI run and checksum, followed by a separate generated **Merged pull requests** appendix. The actual in-app What's New parser keeps only the curated user-facing categories.

## Publishing policy

A normal push to `main` runs tests only. The existing exact one-time release-commit title and manual workflow-dispatch gates govern the signing and publishing jobs. The publisher leaves an existing tag/release immutable and never silently overwrites a distributed APK. Old release tags are never rewritten. Next-version numbering, signer, and corresponding owner-approved batch must be decided before the next release.

## Verification still required

GitHub success does not prove a physical install or in-place upgrade. Check source commit, Actions run, signed APK, accompanying checksum, arm64 manifest package/name/build and signer; test on Samsung hardware and confirm update resume, PDF editing and export/reopen before claiming device parity. The `0.0.14-beta (14012)` APK is not replaced by this workflow-only update.
