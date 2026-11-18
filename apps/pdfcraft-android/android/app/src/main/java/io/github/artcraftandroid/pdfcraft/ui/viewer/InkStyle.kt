package io.github.artcraftandroid.pdfcraft.ui.viewer

/**
 * Distinct pen behaviors. The engine stores width-run PDF Ink, while freehand highlighter
 * uses the engine's dedicated highlight annotation. No proprietary brush assets are used.
 */
internal enum class InkStyle(val key: String) {
    Ballpoint("ballpoint"),
    Fountain("fountain"),
    /** A flat nib held at an angle (#678): thick across it, thin along it. */
    Calligraphy("calligraphy"),
    Pencil("pencil"),
    Brush("brush"),
    Highlighter("highlighter");

    companion object {
        fun fromKey(value: String?): InkStyle? = entries.firstOrNull { it.key == value }
    }
}

/**
 * Snapshots are taken on pen-down, so changing style cannot recolor a prior stroke. The pencil's
 * lightness is its ink density now (#569: the paper shows through its grain), not a lower opacity.
 */
internal fun inkStyleOpacity(opacity: Float, style: InkStyle): Float {
    val safe = opacity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
    return when (style) {
        InkStyle.Highlighter -> safe * 0.4f
        else -> safe
    }
}

/** A pen's width against its chosen thickness, before its dials: only the marker is broader. */
internal fun inkStyleWidthFactor(style: InkStyle): Float = if (style == InkStyle.Highlighter) 2.25f else 1f

/**
 * Width runs for a stroke saved without a width per point. [scales] are the stroke's widths as
 * multiples of the pen width ([PenDynamics]); a constant stroke ([varies] false, a shape, the
 * marker) is one run at the pen's width. A varying stroke is split where its width, rounded to
 * a quarter point, changes, at most [MAX_INK_WIDTH_RUNS] runs.
 */
internal fun inkStyleWidthRuns(
    scales: List<Float>,
    width: Float,
    varies: Boolean,
    style: InkStyle,
): List<InkWidthRun> {
    if (scales.size < 2) return emptyList()
    val safeWidth = width.takeIf { it.isFinite() && it > 0f }?.coerceIn(0.1f, 30f) ?: 1f
    val base = safeWidth * inkStyleWidthFactor(style)
    if (!varies || style == InkStyle.Highlighter) return listOf(InkWidthRun(0, scales.lastIndex, base))
    val widths = inkStylePointWidths(scales, width, style)
    fun segment(i: Int) = (kotlin.math.round((widths[i] + widths[i + 1]) / 2f * 4f) / 4f).coerceAtLeast(0.05f)
    val runs = ArrayList<InkWidthRun>()
    var start = 0
    var current = segment(0)
    val minimumRunEdges = ((scales.lastIndex + MAX_INK_WIDTH_RUNS - 1) / MAX_INK_WIDTH_RUNS).coerceAtLeast(1)
    for (i in 1 until scales.lastIndex) {
        val next = segment(i)
        if (next != current && i - start >= minimumRunEdges && runs.size < MAX_INK_WIDTH_RUNS - 1) {
            runs += InkWidthRun(start, i, current)
            start = i
            current = next
        }
    }
    runs += InkWidthRun(start, scales.lastIndex, current)
    return runs
}

internal fun inkStyleEngineType(style: InkStyle): String =
    if (style == InkStyle.Highlighter) "freehand_highlight" else "ink"

/**
 * One width per point (engine `comment_add` `widths`, the file's `/PCWidths`): the pen width ×
 * the style's factor × each point's scale from [PenDynamics]. A bad scale counts as 1.
 */
internal fun inkStylePointWidths(scales: List<Float>, width: Float, style: InkStyle): List<Float> {
    val safeWidth = width.takeIf { it.isFinite() && it > 0f }?.coerceIn(0.1f, 30f) ?: 1f
    val base = safeWidth * inkStyleWidthFactor(style)
    return scales.map { raw -> base * (raw.takeIf { it.isFinite() && it > 0f }?.coerceIn(0.01f, 3f) ?: 1f) }
}
