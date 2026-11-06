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
}
