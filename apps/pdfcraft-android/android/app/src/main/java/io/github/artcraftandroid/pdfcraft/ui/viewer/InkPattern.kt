package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.ui.geometry.Offset

/**
 * How a pen's line is laid down (#682): solid, in dashes, or in dots. A dashed or dotted stroke is
 * cut into its pieces when the pen lifts ([inkPatternRuns]); the pieces are drawn, erased and
 * selected as strokes, and saved together as one Ink annotation with a path per piece, which
 * every PDF reader shows the same way. While the pen is down the line is shown through a mask of
 * the same pattern ([inkPatternMask]).
 */
internal enum class InkPattern(val key: String) {
    Solid("solid"),
    Dashed("dashed"),
    Dotted("dotted");

    companion object {
        fun fromKey(value: String?): InkPattern = entries.firstOrNull { it.key == value } ?: Solid
    }
}

/** Whether [style] can be drawn dashed or dotted: every pen but the marker. */
internal fun inkPatternApplies(style: InkStyle): Boolean = style != InkStyle.Highlighter

/** A dash as long as three pen widths, the gap between two dashes two (the visible ink, ends included). */
internal const val INK_DASH_WIDTHS = 3f
internal const val INK_DASH_GAP_WIDTHS = 2f

/** Dots one pen width across, their centres 2.2 widths apart. */
internal const val INK_DOT_SPACING_WIDTHS = 2.2f

/** Most pieces one line is cut into. */
internal const val INK_PATTERN_MAX_PIECES = 5000

/** Where a pen's dashed or dotted choice is kept. */
internal fun penPatternKey(style: InkStyle) = "ink.${style.key}.pattern"

/**
 * The pieces of a centreline when [pattern] is laid along it, for a pen [widthPt] wide: each a
 * run of points ([points], with the line's own points inside it and two cut where it begins and
 * ends) and [values] interpolated there (the widths, or a pencil's densities, carried as for the
 * eraser's cut). A piece ends round, half a width beyond its last point, so a dash's centreline is
 * a width shorter than the dash it draws and the gap a width longer. A dot is a piece of two
 * points a hundredth of a point apart, which PDF readers draw as a round dot. The pieces start
 * where the line starts (as the mask shown while writing does, so nothing moves on lift); the last
 * dash may be shorter. A line shorter than one dash is one dash; a solid line is itself.
 */
internal fun inkPatternRuns(points: List<Offset>, values: List<Float>, pattern: InkPattern, widthPt: Float): List<InkRun> {
    if (pattern == InkPattern.Solid || points.size < 2 || values.size != points.size || !(widthPt.isFinite() && widthPt > 0f)) {
        return listOf(InkRun(points, values))
    }
    val w = widthPt.toDouble()
    // where each point lies along the line
    val at = DoubleArray(points.size)
    for (i in 1 until points.size) at[i] = at[i - 1] + (points[i] - points[i - 1]).getDistance().toDouble()
    val length = at.last()
    val (on, period) = when (pattern) {
        InkPattern.Dashed -> (INK_DASH_WIDTHS - 1f) * w to (INK_DASH_WIDTHS + INK_DASH_GAP_WIDTHS) * w
        else -> 0.01 to INK_DOT_SPACING_WIDTHS * w
    }
    if (!(length > on)) return listOf(InkRun(points, values))

    // the point and value at a distance along the line
    var seg = 0
    fun sample(d: Double): Pair<Offset, Float> {
        while (seg < points.size - 2 && at[seg + 1] < d) seg++
        while (seg > 0 && at[seg] > d) seg--
        val span = at[seg + 1] - at[seg]
        val t = if (span > 0.0) ((d - at[seg]) / span).coerceIn(0.0, 1.0) else 0.0
        val p = points[seg]
        val q = points[seg + 1]
        return Offset((p.x + (q.x - p.x) * t).toFloat(), (p.y + (q.y - p.y) * t).toFloat()) to (values[seg] + (values[seg + 1] - values[seg]) * t).toFloat()
    }

    val runs = ArrayList<InkRun>()
    var start = 0.0
    while (start <= length && runs.size < INK_PATTERN_MAX_PIECES) {
        var from = start
        var to = minOf(start + on, length)
        if (pattern == InkPattern.Dotted && to - from < on) from = maxOf(0.0, to - on)
        // a crumb of a dash at the very end is left off
        if (pattern == InkPattern.Dashed && runs.isNotEmpty() && to - from < 0.25 * w) break
        val pts = ArrayList<Offset>()
        val vals = ArrayList<Float>()
        sample(from).let { (p, v) -> pts.add(p); vals.add(v) }
        for (i in points.indices) if (at[i] > from && at[i] < to) { pts.add(points[i]); vals.add(values[i]) }
        sample(to).let { (p, v) -> pts.add(p); vals.add(v) }
        runs.add(InkRun(pts, vals))
        start += period
    }
    return runs
}

/**
 * The mask a patterned stroke is shown through while it is written, in page points: `on` and `off`
 * lengths along the line, how far into the pattern the line starts ([phase]), and whether the
 * mask's ends are round (dots) or square (dashes). Its dashes are the visible ones, round ends
 * included, where [inkPatternRuns] will put them, so the line looks as it will once cut.
 */
internal data class InkPatternMask(val on: Float, val off: Float, val phase: Float, val round: Boolean)

internal fun inkPatternMask(pattern: InkPattern, widthPt: Float): InkPatternMask? {
    if (pattern == InkPattern.Solid || !(widthPt.isFinite() && widthPt > 0f)) return null
    return when (pattern) {
        // the first dash's round start reaches half a width before the line's first point
        InkPattern.Dashed -> InkPatternMask(INK_DASH_WIDTHS * widthPt, INK_DASH_GAP_WIDTHS * widthPt, widthPt / 2f, round = false)
        else -> InkPatternMask(0.01f, INK_DOT_SPACING_WIDTHS * widthPt - 0.01f, 0f, round = true)
    }
}

/**
 * Consecutive strokes of one dashed or dotted line ([InkSaveStroke.group]) together, the others one
 * by one: each list is saved as one annotation ([inkSaveCommands] puts the pieces of one appearance
 * in one command), and is one step of the document's history.
 */
internal fun inkSaveStrokeGroups(strokes: List<InkSaveStroke>): List<List<InkSaveStroke>> {
    val out = ArrayList<MutableList<InkSaveStroke>>()
    for (s in strokes) {
        val last = out.lastOrNull()
        if (s.group != null && last != null && last.first().group === s.group) last.add(s) else out.add(mutableListOf(s))
    }
    return out
}
