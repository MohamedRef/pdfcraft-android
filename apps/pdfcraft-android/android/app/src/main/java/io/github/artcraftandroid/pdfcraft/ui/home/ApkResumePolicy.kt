package io.github.artcraftandroid.pdfcraft.ui.home

import java.io.IOException

/** HTTP range validation is deliberately independent of Android and the network. */
internal enum class ApkResumeAction { RESTART, APPEND, RETRY_WITHOUT_RANGE }

/**
 * A resumed download must be an exact suffix of the advertised APK.
 * HTTP 200 means the CDN ignored Range: safely truncate instead of appending.
 * HTTP 416 on an unfinished file retries once without Range.
 */
internal fun apkResumeAction(
    requestedOffset: Long,
    status: Int,
    contentRange: String?,
    expectedSize: Long,
): ApkResumeAction {
    if (expectedSize <= 0L || requestedOffset < 0L || requestedOffset > expectedSize) {
        throw IOException("Invalid APK download length.")
    }
    return when (status) {
        200 -> ApkResumeAction.RESTART
        206 -> {
            if (requestedOffset == 0L) throw IOException("Unexpected partial APK response.")
            val range = Regex("""bytes (\d+)-(\d+)/(\d+)""").matchEntire(contentRange?.trim().orEmpty())
                ?: throw IOException("Cannot verify APK resume range.")
            val start = range.groupValues[1].toLongOrNull()
            val end = range.groupValues[2].toLongOrNull()
            val total = range.groupValues[3].toLongOrNull()
            if (start != requestedOffset || end == null || end < requestedOffset || end >= expectedSize || total != expectedSize) {
                throw IOException("APK resume range does not match the published release.")
            }
            ApkResumeAction.APPEND
        }
        416 -> if (requestedOffset > 0L) ApkResumeAction.RETRY_WITHOUT_RANGE
            else throw IOException("APK server refused the requested download.")
        else -> throw IOException("APK server returned HTTP $status.")
    }
}

/**
 * Only bytes belonging to this immutable published SHA-256 are ever resumed.
 * This avoids stitching files from different releases, even if their APK names collide.
 */
internal fun apkPartialFileName(sha256: String): String {
    if (!Regex("""[a-f0-9]{64}""").matches(sha256)) throw IOException("Invalid update digest.")
    return "download-$sha256.part"
}

internal fun readableMegabytes(bytes: Long): String =
    String.format(java.util.Locale.ROOT, "%.1f", bytes.coerceAtLeast(0L).toDouble() / (1024.0 * 1024.0))
