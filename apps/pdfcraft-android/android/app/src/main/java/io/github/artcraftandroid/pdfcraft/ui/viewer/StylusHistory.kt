package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.ui.geometry.Offset

internal data class StylusHistoryPoint(val uptimeMillis: Long, val position: Offset)
internal data class StylusMotionPoint(val uptimeMillis: Long, val position: Offset, val pressure: Float)

/** Prevent untrusted input histories from allocating unbounded Compose/PDF points per frame. */
internal const val MAX_STYLUS_HISTORY_POINTS = 64

/**
 * Coalesce validated pen history with the current pointer location in event-time order.
 * Compose HistoricalChange carries position/time, not historical pressure: [pressureAt] supplies
 * the real pressure the activity logged for that sample time ([StylusLog]); without one, the
 * pressure is interpolated between the last valid and current stylus pressure (an estimate).
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
    /** The real pressure sampled at a time, when the raw pen log has it. */
    pressureAt: (Long) -> Float? = { null },
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
            points += StylusMotionPoint(point.uptimeMillis, point.position, pressureAt(point.uptimeMillis) ?: (prev + (now - prev) * fraction))
            lastPosition = point.position
            lastTime = point.uptimeMillis
        }
    }
    if (position != lastPosition) {
        points += StylusMotionPoint(currentTime, position, now)
    }
    return points
}

/**
 * The positions of one finger move, in order: the batched historical samples a 120–240 Hz
 * digitiser delivers between frames (at most [MAX_STYLUS_HISTORY_POINTS], evenly kept), then
 * [current]. Without them a fast finger stroke becomes a polygon of one point per frame (#84).
 * Repeats of the previous point are left out.
 */
internal fun fingerMotionPoints(previous: Offset, history: List<Offset>, current: Offset): List<Offset> {
    val stride = ((history.size + MAX_STYLUS_HISTORY_POINTS - 1) / MAX_STYLUS_HISTORY_POINTS).coerceAtLeast(1)
    val out = ArrayList<Offset>(minOf(history.size, MAX_STYLUS_HISTORY_POINTS) + 1)
    var last = previous
    history.forEachIndexed { i, p ->
        if (i % stride == 0 && p.x.isFinite() && p.y.isFinite() && p != last) {
            out.add(p)
            last = p
        }
    }
    if (current != last || out.isEmpty()) out.add(current)
    return out
}
