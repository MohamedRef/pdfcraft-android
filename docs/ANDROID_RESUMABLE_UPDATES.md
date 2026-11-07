# Android APK updater: resumable verified download and What's New

## Behavior
- About > Check for updates still lists only **published owner GitHub Releases** with an exact expected ARM64 asset name and GitHub-supplied SHA-256 digest.
- Downloads use `filesDir/updates/` rather than the evictable cache. A digest-named `.part` is retained on connection failure, pause or activity recreation; a new attempt sends `Range: bytes=<length>-`.
- A valid HTTP `206` must have a matching `Content-Range` and total release size. A `200` after Range **truncates** rather than appending; a `416` can restart from zero once. Non-HTTPS redirects, untrusted hosts, malformed ranges, excessive bytes, unexpected response coding and size discrepancies fail closed.
- The final APK is never passed to the installer until **SHA-256, package ID, manifest versionCode, signing certificate and required ARM64 engine** all verify. Only then is the private partial renamed to its release filename.
- If the process stops after the final byte but before verification, the complete partial is verified on the next attempt.
- Installer remains an explicit Android user action (with permission prompt where needed).
- The Update panel shows versionName **(manifest versionCode)**, release channel, publication date, size, a progress bar, download Pause/Resume, and original categorized release notes (New features, Fixes, Improvements, expandable Known limits), plus a full-release link.
- Old temp cache APKs do not migrate automatically. No claim of continued download when the app is force-stopped: the **saved bytes** resume on next initiated attempt. Android-managed background download is not implemented.

## Existing interfaces and security
- Existing GitHub release parsing, version arithmetic, signature checks and installed package identity preserved.
- `res/xml/file_paths.xml` now exposes the private persistent verified-updates folder through non-exported FileProvider; no external storage permission was added.
- No automatic installation and no signature/hash bypass.
- Unit coverage: range handling (200/206/416), malformed/oversized ranges, immutable digest file names, categorization, Markdown links, release-note limits.
- Physical OTA path still requires device QA: pause on Samsung, low storage, interrupted Wi-Fi, unexpected server responses, process kill/relaunch, Android install permission and installed data preservation.

## Merge requirements
- Exact PR head Android JVM unit tests, Rust/provenance, signed APK build and aggregate gates must pass. Merge only after green gates and re-check branch conflicts.
- Do not publish a beta for this PR alone. Bundle with already-validated annotation improvements in the next beta, obeying `<versionName> (<actual manifest versionCode>)`, and keep signed releases immutable.
