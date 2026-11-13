package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.core.ScanModelTiming

/*
 * Timing a model file picked on the phone (#304), for the diagnostics log's `model_timing` line
 * and the message shown after it. Measurement only: the file is not kept, never used on a page,
 * and nothing in the app ships it.
 */

/** The most input or output dimensions logged one by one. */
private const val MAX_DIMS = 6

private fun mb(kb: ULong?): Long = kb?.let { (it / 1024u).toLong() } ?: -1L

/** The timing as diagnostics fields: numbers and booleans only (-1 for not known). */
internal fun scanModelTimingDiag(t: ScanModelTiming): Array<Pair<String, Any?>> {
    fun dims(name: String, shape: List<UInt>): Array<Pair<String, Any?>> =
        arrayOf("${name}_dims" to shape.size.toLong(), *shape.take(MAX_DIMS).mapIndexed { i, d -> "${name}_$i" to d.toLong() }.toTypedArray())
    return arrayOf(
        "bytes" to t.bytes.toLong(), "sha" to "h${t.sha25616}",
        "inputs" to t.inputs.toLong(), *dims("input", t.inputShape), *dims("output", t.outputShape), "output_finite" to t.outputFinite,
        "threads" to t.threads.toLong(), "cores" to t.cores.toLong(),
        "load_ms" to t.loadMs.toLong(), *t.runsMs.mapIndexed { i, ms -> "run_${i + 1}_ms" to ms.toLong() }.toTypedArray(),
        "peak_mb_before" to mb(t.peakKbBefore), "peak_mb_after" to mb(t.peakKbAfter),
    )
}

/** The run times for the message, e.g. "8810, 8650", and the peak memory in MB ("?" when not known). */
internal fun scanModelTimingRuns(t: ScanModelTiming): String = t.runsMs.joinToString(", ")

internal fun scanModelTimingPeak(t: ScanModelTiming): String = mb(t.peakKbAfter).takeIf { it >= 0 }?.toString() ?: "?"

/** What copyCapped throws for a file past the size limit (#445). */
internal const val SCAN_MODEL_COPY_TOO_LARGE = "too large"

/**
 * Timing a model failed (#445): [reason] is a short lowercase word for the log (the native side's,
 * or `too_large` / `unreadable` when the copy into the app's folder failed), [stage] `copy` or
 * `time`, [shown] the message for the screen, [nativeMs] how long the native call ran (-1 when it
 * was not reached).
 */
internal class ScanModelTimingFailed(val reason: String, val stage: String, val shown: String, val nativeMs: Long) : Exception(reason)

/** The log's word for a failed copy of the picked file (#445). */
internal fun scanModelCopyReason(e: java.io.IOException): String = if (e.message == SCAN_MODEL_COPY_TOO_LARGE) "too_large" else "unreadable"

/** The words the native side and the copy give (#445); anything else is logged as `other`. */
internal val SCAN_MODEL_TIMING_REASONS = setOf(
    "not_ready", "outside_folder", "unreadable", "too_large", "not_a_model", "no_input", "not_fixed", "input_too_large", "no_output", "run_failed",
)

/** A failed timing as diagnostics fields (#445): [ms] from the press to the failure. */
internal fun scanModelTimingFailedDiag(e: ScanModelTimingFailed, ms: Long): Array<Pair<String, Any?>> = arrayOf(
    "reason" to (e.reason.takeIf { it in SCAN_MODEL_TIMING_REASONS } ?: "other"), "stage" to e.stage, "ms" to ms, "native_ms" to e.nativeMs,
)
