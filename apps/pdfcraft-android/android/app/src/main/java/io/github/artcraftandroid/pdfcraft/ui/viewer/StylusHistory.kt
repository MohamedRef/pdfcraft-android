package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.ui.geometry.Offset

internal data class StylusHistoryPoint(val uptimeMillis: Long, val position: Offset)
internal data class StylusMotionPoint(val uptimeMillis: Long, val position: Offset, val pressure: Float)

/** Prevent untrusted input histories from allocating unbounded Compose/PDF points per frame. */
internal const val MAX_STYLUS_HISTORY_POINTS = 64

/**
 * Coalesce validated pen history with the current pointer location in event-time order.
 * Compose HistoricalChange carries position/time, not historical pressure; interpolate between
 * the last valid and current stylus pressure. This is a visual estimate, not a hardware sample.
 * Never use history from an extra touch/palm pointer or from a different gesture.
 */
internal fun stylusMotionPoints(
    previousPosition: Offset,
    position: Offset,
    previousTime: Long,
    currentTime: Long,
    previousPressure: Float,
    pressure: Float,
    history: List<StylusHistoryPoint>,
): List<StylusMotionPoint> {
    fun valid(p: Offset) = p.x.isFinite() && p.y.isFinite()
    val prev = previousPressure.takeIf { it.isFinite() && it > 0f }?.coerceIn(0f, 1f) ?: 1f
    val now = pressure.takeIf { it.isFinite() && it > 0f }?.coerceIn(0f, 1f) ?: prev
    if (!valid(position)) return emptyList()

    val points = ArrayList<StylusMotionPoint>(MAX_STYLUS_HISTORY_POINTS + 1)
    var lastPosition = previousPosition
    var lastTime = previousTime
    if (currentTime > previousTime) {
        // Sample before sorting: even a maliciously huge history allocates at most 64 points.
        val stride = ((history.size + MAX_STYLUS_HISTORY_POINTS - 1) / MAX_STYLUS_HISTORY_POINTS).coerceAtLeast(1)
        val ordered = history.asSequence()
            .filterIndexed { index, _ -> index % stride == 0 }
            .filter { it.uptimeMillis > previousTime && it.uptimeMillis < currentTime && valid(it.position) }
            .sortedBy { it.uptimeMillis }
            .toList()
        for (point in ordered) {
            if (point.uptimeMillis <= lastTime || point.position == lastPosition) continue
            val fraction = ((point.uptimeMillis - previousTime).toDouble() / (currentTime - previousTime).toDouble()).toFloat()
            points += StylusMotionPoint(point.uptimeMillis, point.position, prev + (now - prev) * fraction)
            lastPosition = point.position
            lastTime = point.uptimeMillis
        }
    }
    if (position != lastPosition) {
        points += StylusMotionPoint(currentTime, position, now)
    }
    return points
}
