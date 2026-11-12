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
