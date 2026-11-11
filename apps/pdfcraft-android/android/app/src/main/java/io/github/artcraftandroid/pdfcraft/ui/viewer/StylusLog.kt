package io.github.artcraftandroid.pdfcraft.ui.viewer

import android.view.MotionEvent
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import kotlin.math.abs
import kotlin.math.hypot

/** One raw pen sample as Android delivered it: event time (ms), window position (px), pressure. */
internal data class PenSample(val time: Long, val x: Float, val y: Float, val pressure: Float)

/**
 * A bounded log of the latest raw pen samples, in time order. Compose's historical pointer changes
 * carry time and position but not pressure; the matching raw samples give the real pressure, and
 * the newest give the pen's speed for a short predicted tail.
 */
internal class PenSampleLog(private val capacity: Int = 256) {
    private val samples = ArrayDeque<PenSample>(capacity)
    /** Whether the pen is touching (between its down and its up or cancel). */
    var down = false
        private set

    @Synchronized
    fun add(sample: PenSample) {
        if (!(sample.x.isFinite() && sample.y.isFinite())) return
        val last = samples.lastOrNull()
        if (last != null && sample.time < last.time) samples.clear() // a new, earlier time base
        if (samples.size >= capacity) samples.removeFirst()
        samples.addLast(sample)
    }

    @Synchronized
    fun setDown(value: Boolean) {
        down = value
        if (value) samples.clear()
    }

    /** The real pressure sampled at [time] (± [toleranceMs]), or `null` when none was logged. */
    @Synchronized
    fun pressureAt(time: Long, toleranceMs: Long = 1L): Float? {
        var best: PenSample? = null
        for (s in samples) {
            val d = abs(s.time - time)
            if (d <= toleranceMs && (best == null || d < abs(best.time - time))) best = s
        }
        return best?.pressure?.takeIf { it.isFinite() && it > 0f }?.coerceIn(0f, 1f)
    }

    /**
     * Where the pen will be [aheadMs] after its newest sample, as a window-pixel offset from that
     * sample: its mean velocity over the last [windowMs], capped at [maxPx]. `null` when the pen
     * is up, the samples are stale (older than [now] − [staleMs]) or too few.
     */
    @Synchronized
    fun predictedOffset(now: Long, aheadMs: Float = 16f, windowMs: Long = 40L, staleMs: Long = 50L, maxPx: Float = 48f): Pair<Float, Float>? {
        if (!down) return null
        val last = samples.lastOrNull() ?: return null
        if (now - last.time > staleMs) return null
        val first = samples.lastOrNull { last.time - it.time >= windowMs / 2 && last.time - it.time <= windowMs }
            ?: samples.firstOrNull { last.time - it.time <= windowMs && it !== last } ?: return null
        val dt = (last.time - first.time).toFloat()
        if (dt <= 0f) return null
        var dx = (last.x - first.x) / dt * aheadMs
        var dy = (last.y - first.y) / dt * aheadMs
        val len = hypot(dx, dy)
        if (!len.isFinite()) return null
        if (len > maxPx) {
            dx *= maxPx / len
            dy *= maxPx / len
        }
        return dx to dy
    }
}

/** The app's pen log, fed by the activity before Compose sees each event. */
internal object StylusLog {
    val pen = PenSampleLog()

    /** Record the stylus pointer of [event] with all its historical samples. */
    fun record(event: MotionEvent) {
        val index = (0 until event.pointerCount).firstOrNull {
            event.getToolType(it) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(it) == MotionEvent.TOOL_TYPE_ERASER
        } ?: return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                pen.setDown(true)
                InkLatency.stroke.down(event.eventTime, event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER, Diag.enabled())
            }
            MotionEvent.ACTION_MOVE -> InkLatency.stroke.move(event.historySize + 1)
        }
        for (h in 0 until event.historySize) {
            pen.add(PenSample(event.getHistoricalEventTime(h), event.getHistoricalX(index, h), event.getHistoricalY(index, h), event.getHistoricalPressure(index, h)))
        }
        pen.add(PenSample(event.eventTime, event.getX(index), event.getY(index), event.getPressure(index)))
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pen.setDown(false)
                strokeEnded(event.eventTime, event.actionMasked == MotionEvent.ACTION_CANCEL)
            }
            MotionEvent.ACTION_POINTER_UP -> if (event.actionIndex == index) {
                pen.setDown(false)
                strokeEnded(event.eventTime, cancelled = false)
            }
        }
    }

    /** One line per stroke in the diagnostics log (#128): counts, rate and app-side latency. */
    private fun strokeEnded(time: Long, cancelled: Boolean) {
        val t = InkLatency.stroke.up(time, cancelled) ?: return
        Diag.event(
            DiagArea.Ink, "stroke",
            "ms" to t.durationMs, "events" to t.events, "samples" to t.samples, "sample_hz" to t.sampleHz,
            "eraser" to t.eraser, "cancelled" to t.cancelled,
            "normal_n" to (t.normal?.count ?: 0), "normal_p50_ms" to t.normal?.medianMs, "normal_p95_ms" to t.normal?.p95Ms, "normal_max_ms" to t.normalMaxMs,
            "front_n" to (t.front?.count ?: 0), "front_p50_ms" to t.front?.medianMs, "front_p95_ms" to t.front?.p95Ms, "front_max_ms" to t.frontMaxMs,
        )
    }
}
