package io.github.artcraftandroid.pdfcraft.engine

import android.os.SystemClock
import android.util.Log

/** Enable with `adb shell setprop log.tag.PdfCraftOpen DEBUG`. No document identifiers. */
internal object OpenTiming {
    inline fun <T> measure(stage: String, block: () -> T): T {
        val start = SystemClock.elapsedRealtimeNanos()
        try { return block() } finally {
            if (Log.isLoggable("PdfCraftOpen", Log.DEBUG)) {
                Log.d("PdfCraftOpen", "$stage duration_ms=${(SystemClock.elapsedRealtimeNanos() - start) / 1_000_000}")
            }
        }
    }

    /**
     * Elapsed time since the user's latest open request. Every caller supplies a fixed stage name;
     * only aggregate byte/page counts are logged, never a document name, URI, password or contents.
     * This is diagnostics, NOT a measured performance improvement or GPU first-paint timestamp.
     */
    fun milestone(stage: String, openedAtMs: Long, bytes: Long? = null, pages: Int? = null) {
        if (!Log.isLoggable("PdfCraftOpen", Log.DEBUG)) return
        val elapsed = (SystemClock.elapsedRealtime() - openedAtMs).coerceAtLeast(0)
        val size = bytes?.let { " bytes=${it.coerceAtLeast(0)}" }.orEmpty()
        val count = pages?.let { " pages=${it.coerceAtLeast(0)}" }.orEmpty()
        Log.d("PdfCraftOpen", "$stage elapsed_ms=$elapsed$size$count")
    }
}
