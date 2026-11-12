package io.github.artcraftandroid.pdfcraft.engine

import android.os.SystemClock
import android.util.Log

/**
 * Open-stage timings. Two outputs, both off by default: logcat after
 * `adb shell setprop log.tag.PdfCraftOpen DEBUG`, and the in-app diagnostics log ([Diag]) that
 * Settings → About → Diagnostics turns on so a phone without a computer can share them. Render
 * stages go to the log's `render` area (#236), the rest to `open`.
 * No document identifiers are ever written: only fixed stage names, durations and counts.
 */
internal object OpenTiming {
    @PublishedApi
    internal fun enabled(): Boolean = Diag.enabled() || Log.isLoggable("PdfCraftOpen", Log.DEBUG)

    @PublishedApi
    internal fun emit(line: String, warn: Boolean = false) {
        if (Log.isLoggable("PdfCraftOpen", Log.DEBUG)) {
            if (warn) Log.w("PdfCraftOpen", line) else Log.d("PdfCraftOpen", line)
        }
        Diag.sink?.add(openTimingLine(Diag.now(), line))
    }

    /** Opt-in error breadcrumbs: no filenames, URIs, paths, PDF contents or exception messages. */
    fun failure(stage: String, error: Throwable) {
        if (!enabled()) return
        emit("failed_stage=$stage error_class=${error.javaClass.simpleName}", warn = true)
    }

    inline fun <T> measure(stage: String, block: () -> T): T {
        val start = SystemClock.elapsedRealtimeNanos()
        try { return block() } finally {
            if (enabled()) emit("$stage duration_ms=${(SystemClock.elapsedRealtimeNanos() - start) / 1_000_000}")
        }
    }

    /**
     * Elapsed time since the user's latest open request. Every caller supplies a fixed stage name;
     * only aggregate byte/page counts are logged, never a document name, URI, password or contents.
     * This is diagnostics, NOT a measured performance improvement or GPU first-paint timestamp.
     */
    fun milestone(stage: String, openedAtMs: Long, bytes: Long? = null, pages: Int? = null) {
        if (!enabled()) return
        val elapsed = (SystemClock.elapsedRealtime() - openedAtMs).coerceAtLeast(0)
        emit(timingLine(stage, elapsed, bytes, pages))
    }

    /** A native stage completed during open; nested and parallel durations overlap. */
    fun duration(stage: String, durationMs: Long) {
        if (!enabled()) return
        emit("$stage duration_ms=${durationMs.coerceAtLeast(0)}")
    }

    /** A count that explains a timing, such as how many repairs opening needed. */
    fun count(stage: String, n: Long) {
        if (!enabled()) return
        emit("$stage count=${n.coerceAtLeast(0)}")
    }
}

/** One milestone line, as logcat and the shared report show it. */
internal fun timingLine(stage: String, elapsedMs: Long, bytes: Long? = null, pages: Int? = null): String {
    val size = bytes?.let { " bytes=${it.coerceAtLeast(0)}" }.orEmpty()
    val count = pages?.let { " pages=${it.coerceAtLeast(0)}" }.orEmpty()
    return "$stage elapsed_ms=${elapsedMs.coerceAtLeast(0)}$size$count"
}

/** An open-timing line in the diagnostics log: its stage names come from fixed code, not documents. */
internal fun openTimingLine(at: java.time.LocalDateTime, line: String): String {
    val stage = line.substringBefore(' ')
    val render = stage.contains("render") || stage.startsWith("tile") || stage == "stale_tile_dropped"
    val area = if (render) DiagArea.Render else DiagArea.Open
    return diagPrefix(at, area) + line
}
