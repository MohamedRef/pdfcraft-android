# Open with crash hardening — 2026-11-08

## User report
Selecting PdfCraft from another app's **Open with** chooser sometimes crashes the Android application. The source application, crash signature, and specific PDF bytes remain unknown; there is no device logcat or physical-device validation at this checkpoint.

## Changes in this focused PR
- External Intent parsing accepts `ACTION_VIEW`, `ACTION_SEND`, and `ACTION_SEND_MULTIPLE`, including valid `ClipData` when the sender did not provide `EXTRA_STREAM`. Malformed parcelables are not permitted to escape the Activity's input router; unsupported schemes are ignored. The first valid PDF URI wins.
- The `singleTask` activity calls `setIntent(intent)` on `onNewIntent` and handles a recreated task without reopening a retained document. The active external document still returns to the sending app when closed.
- Document opening is serialized through a dedicated `Mutex` to prevent overlapping external Intents and internal picker requests from racing in the same engine and provider working-copy directory. Waiting, superseded requests are ignored.
- Activity `onPause` autosave now uses that same open/close mutex and checks that the document is still live, preventing a queued lifecycle save against an already closed native document. Unsaved-change prompts are not bypassed.
- Save a copy / Discard prompt resolution blocks newly delivered Open with requests until the prior document has been safely closed or the save-copy picker is cancelled/failed. Cancelled/failed save copy restores the unsaved prompt rather than dropping its recovery choice.
- The `fromOtherApp` origin is carried as a request-local value into native opening, password challenge and revoked-URI fallback, rather than shared global `otherAppUri`.
- Security boundary remains: native PDF engine validates copied bytes; provider access is still copied into private disk for safe engine use; no request grants are fabricated, no arbitrary URL downloads, no sign/ID changes, no data deletion or bypass of pending writeback recovery.
- Pure Kotlin JVM tests cover valid/suspicious URI schemes, supported actions and ordering of incoming candidate URIs.

## Remaining investigations / acceptance
- A live Samsung S24 Ultra reproduction and sanitized `adb logcat -b crash` stack trace are essential to determine whether the crash was in Intent dispatch, provider metadata/read, old-document save, native PDF open or UI rendering. No success claim until verified.
- Check cold/warm start, process recreation, rapid repeated Open with, picker while external open, WhatsApp/Telegram/Gmail/Drive, corrupt/0 byte/encrypted PDFs, temporary grant expiration, file provider pipes, cloud/offline files, huge PDFs, opening while a document has unsaved edits, Back, read-only Save a copy, Recent and export.
- Check exact PR head Android JVM tests, Rust provenance and merge gate. Merge only on green checks. If a race or source-provider exception remains reproducible, keep the PR open and fix it, then re-run.
- Existing owner-signed `0.0.15-beta (15012)` publishing workflow is isolated, and this PR is not included in that APK. Include validated crash correction in a later signed beta.
