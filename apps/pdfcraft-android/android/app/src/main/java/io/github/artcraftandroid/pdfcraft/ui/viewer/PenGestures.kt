package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.atan2

/** What a finished freehand stroke means when smart pen gestures are on (#148). */
internal enum class PenGesture { None, Line, ScratchOut }

/**
 * Classifies a lifted stroke (page points, recognised only after lift so the live stroke has no
 * added latency). A [PenGesture.Line] is straight (within 8 % of its length) and within 12° of
 * horizontal; a [PenGesture.ScratchOut] reverses horizontal direction at least four times and
 * travels much further than it spans.
 */
internal fun classifyPenGesture(points: List<Offset>, minLength: Float = 12f): PenGesture {
    if (points.size < 2) return PenGesture.None
    val first = points.first()
    val last = points.last()
    val chord = (last - first).getDistance()
    var path = 0f
    for (i in 1 until points.size) path += (points[i] - points[i - 1]).getDistance()
    // Reversals of horizontal direction, ignoring jitter under 2 pt.
    var reversals = 0
    var dir = 0
    var anchor = first.x
    for (p in points) {
        val dx = p.x - anchor
        if (abs(dx) < 2f) continue
        val d = if (dx > 0) 1 else -1
        if (dir != 0 && d != dir) reversals++
        dir = d
        anchor = p.x
    }
    val box = strokeBounds(points)
    if (reversals >= 4 && path >= 2.5f * maxOf(chord, 1f) && box.width >= 6f) return PenGesture.ScratchOut
    if (chord < minLength) return PenGesture.None
    val dx = last.x - first.x
    val dy = last.y - first.y
    val deviation = points.maxOf { p -> abs(dx * (p.y - first.y) - dy * (p.x - first.x)) / chord }
    val angle = Math.toDegrees(atan2(abs(dy).toDouble(), abs(dx).toDouble()))
    return if (deviation <= maxOf(1.5f, chord * 0.08f) && angle <= 12.0) PenGesture.Line else PenGesture.None
}

internal fun strokeBounds(points: List<Offset>): Rect =
    Rect(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })

/** Whether most (60 %) of a stroke's points lie inside the scratch-out [area]. */
internal fun scratchCovers(area: Rect, points: List<Offset>): Boolean =
    points.isNotEmpty() && points.count { area.contains(it) } >= points.size * 0.6f

/** Whether most (60 %) of a saved comment's [rect] lies inside the scratch-out [area]. */
internal fun scratchCoversRect(area: Rect, rect: List<Float>): Boolean {
    if (rect.size != 4 || rect.any { !it.isFinite() }) return false
    val r = Rect(minOf(rect[0], rect[2]), minOf(rect[1], rect[3]), maxOf(rect[0], rect[2]), maxOf(rect[1], rect[3]))
    val i = r.intersect(area)
    if (i.width <= 0f || i.height <= 0f) return false
    val a = maxOf(r.width * r.height, 0.01f)
    return i.width * i.height >= a * 0.6f
}

/**
 * Which text markup a straight stroke makes over a text line with [lineTop]..[lineBottom]
 * (page points, y down) at mean height [strokeY]: a marker stroke inside the line highlights it;
 * a pen stroke within 4 pt below its baseline area underlines it; otherwise it stays ink.
 */
internal fun lineGestureMarkup(marker: Boolean, strokeY: Float, lineTop: Float, lineBottom: Float): TextMarkup? = when {
    marker && strokeY in lineTop..lineBottom -> TextMarkup.Highlight
    !marker && strokeY - lineBottom in -1f..4f -> TextMarkup.Underline
    else -> null
}
