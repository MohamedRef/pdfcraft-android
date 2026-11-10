package io.github.artcraftandroid.pdfcraft.engine

import android.os.SystemClock
import android.util.Log
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * Open-stage timings. Two outputs, both off by default: logcat after
 * `adb shell setprop log.tag.PdfCraftOpen DEBUG`, and the in-app [recorder] that
 * Settings → About → Diagnostics turns on so a phone without a computer can share them.
 * No document identifiers are ever written: only fixed stage names, durations and counts.
 */
internal object OpenTiming {
    /** The in-app recording, while Diagnostics → Record open timings is on. */
    @Volatile
    var recorder: TimingRecorder? = null

    @PublishedApi
    internal fun enabled(): Boolean = recorder != null || Log.isLoggable("PdfCraftOpen", Log.DEBUG)

    @PublishedApi
    internal fun emit(line: String, warn: Boolean = false) {
        if (Log.isLoggable("PdfCraftOpen", Log.DEBUG)) {
            if (warn) Log.w("PdfCraftOpen", line) else Log.d("PdfCraftOpen", line)
        }
        recorder?.add("${LocalTime.now().truncatedTo(ChronoUnit.MILLIS)} $line")
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

/**
 * The newest [capacity] timing lines, kept in memory only. Page renders add a line each, so the
 * bound keeps a long session from growing without limit; the oldest lines go first.
 */
internal class TimingRecorder(private val capacity: Int = 800) {
    private val lines = ArrayDeque<String>()
    private var dropped = 0

    init { require(capacity > 0) }

    @Synchronized
    fun add(line: String) {
        if (lines.size >= capacity) {
            lines.removeFirst()
            dropped++
        }
        lines.addLast(line)
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun droppedCount(): Int = dropped

    @Synchronized
    fun clear() {
        lines.clear()
        dropped = 0
    }
}

/** The plain-text report the Share button sends: a header of [facts], then the lines in order. */
internal fun timingReport(facts: List<Pair<String, String>>, lines: List<String>, dropped: Int): String = buildString {
    appendLine("PdfCraft open timings")
    for ((k, v) in facts) appendLine("$k: $v")
    if (dropped > 0) appendLine("older lines dropped: $dropped")
    appendLine()
    for (l in lines) appendLine(l)
}
