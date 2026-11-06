package io.github.artcraftandroid.pdfcraft.ui.viewer

import kotlin.math.roundToInt

/** Inclusive point indices in one original stroke. Neighboring runs share their boundary point. */
internal data class InkWidthRun(val start: Int, val end: Int, val width: Float)

/**
 * PDF Ink annotations have one /BS width per annotation, not a width for every InkList point.
 * Quantize stylus pressure and split only at width transitions, so saved PDF appearances retain
 * the variable widths rather than silently reverting to the last selected pen width.
 * Touch input and shape snapping retain the exact selected constant width.
 */
internal fun inkWidthRuns(pressures: List<Float>, selectedWidth: Float, stylus: Boolean): List<InkWidthRun> {
    if (pressures.size < 2) return emptyList()
    val base = selectedWidth.takeIf { it.isFinite() && it > 0f } ?: 1f
    if (!stylus) return listOf(InkWidthRun(0, pressures.lastIndex, base))

    fun level(value: Float): Int {
        // Zero/invalid pressure is common in pen-up events. Use neutral rather than a thin spike.
        val pressure = value.takeIf { it.isFinite() && it > 0f }?.coerceIn(0f, 1f) ?: 1f
        return (pressure * 7f).roundToInt().coerceIn(0, 7)
    }
    fun width(index: Int): Float {
        val bucket = ((level(pressures[index]) + level(pressures[index + 1])) / 2f).roundToInt()
        return base * (0.35f + 0.65f * bucket / 7f)
    }

    val runs = ArrayList<InkWidthRun>()
    var start = 0
    var currentWidth = width(0)
    for (i in 1 until pressures.lastIndex) {
        val nextWidth = width(i)
        if (nextWidth != currentWidth) {
            runs += InkWidthRun(start, i, currentWidth)
            start = i
            currentWidth = nextWidth
        }
    }
    runs += InkWidthRun(start, pressures.lastIndex, currentWidth)
    return runs
}
