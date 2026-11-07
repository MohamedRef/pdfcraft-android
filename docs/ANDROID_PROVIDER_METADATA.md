# Provider metadata columns: filename, size, dates and write flags

DocumentFiles now resolves requested metadata by column name instead of assuming it is column zero. This covers both display-name methods, size, document/media modification dates and document write flags. Providers returning a proper single-column projection behave the same. When a provider returns extra or reordered columns, its unrelated row ID is no longer read as the filename, byte count or flags. Missing/null columns still use existing URI/name or capability fallbacks; cursor lifetime and safe PDF snapshots/write-back are unchanged.

This is a source-confirmed defensive correction relevant to the owner’s report that a file appeared as “2”. A reordered cursor with `_id=2` reproduces that class of misread in a unit fixture. It does **not** prove the owner’s actual Samsung provider returned that cursor; the real filename report remains unverified until device testing.

Four JVM cases use the actual Cursor interface through a JDK proxy (no Android method bodies): reordered filename, independent size/date/read-only flags, absent/null columns and ordinary single-column responses. Current-head Android CI must compile and run them; no local Android SDK/device test is claimed. Android specifies named lookup in [Cursor.getColumnIndex](https://developer.android.com/reference/android/database/Cursor#getColumnIndex(java.lang.String)).

Next-batch independent fix, outside the frozen bookmark/tablet release candidate and published `0.0.10-beta (10012)`. No per-PR APK and no opening-speed claim. Manual provider tests still needed: Samsung My Files/Downloads/cloud, absent metadata, revoked grants and safe read-only/failed-write recovery. No source PDF, URI or filename is logged or uploaded.


Owner scope update (2026-11-07 Cairo): this PR was unintentionally auto-closed by an issue-closing keyword in the release-preparation description, not merged or abandoned. It is reopened, and the keyword was corrected. Include it with #30/#31/#32 and the bookmark/tablet work in one release after complete-stack verification. Older frozen-candidate exclusions are superseded.
