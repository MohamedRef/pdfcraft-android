package io.github.artcraftandroid.pdfcraft.ui.viewer

import android.view.MotionEvent
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import kotlin.math.abs
import kotlin.math.hypot

/**
 * One raw pen sample as Android delivered it: event time (ms), window position (px), pressure,
 * and the pen's lean from upright in radians ([tilt], NaN when the pen does not report it).
 */
internal data class PenSample(val time: Long, val x: Float, val y: Float, val pressure: Float, val tilt: Float = Float.NaN)

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
     * The pen's tilt in radians at the sample logged at [time] (± [toleranceMs]), or `null` when
     * none was logged there or it had no tilt. Each historical sample keeps its own lean: the
     * newest one alone ([StylusLog.tiltRad]) would be given to every sample of a batch.
     */
    @Synchronized
    fun tiltAt(time: Long, toleranceMs: Long = 1L): Float? {
        var best: PenSample? = null
        for (s in samples) {
            val d = abs(s.time - time)
            if (d <= toleranceMs && (best == null || d < abs(best.time - time))) best = s
        }
        return best?.tilt?.takeIf { it.isFinite() }
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

    // the pen's tilt over the current stroke, in degrees (#569); NaN until a sample has it
    @Volatile private var tiltMin = Float.NaN
    @Volatile private var tiltMax = Float.NaN
    @Volatile private var tiltReported: Boolean? = null

    /**
     * The pen's lean from upright in radians at its newest sample (NaN when not reported). The pen
     * model takes each sample's own lean from [PenSampleLog.tiltAt]; this is the fallback when a
     * sample's time is not in the log.
     */
    @Volatile var tiltRad = Float.NaN
        private set

    private fun tilt(rad: Float) {
        tiltRad = rad
        if (!rad.isFinite()) return
        val deg = Math.toDegrees(rad.toDouble()).toFloat()
        if (tiltMin.isNaN() || deg < tiltMin) tiltMin = deg
        if (tiltMax.isNaN() || deg > tiltMax) tiltMax = deg
    }

    /** Record the stylus pointer of [event] with all its historical samples. */
    fun record(event: MotionEvent) {
        val index = (0 until event.pointerCount).firstOrNull {
            event.getToolType(it) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(it) == MotionEvent.TOOL_TYPE_ERASER
        } ?: return recordFinger(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                pen.setDown(true)
                tiltRad = Float.NaN
                tiltMin = Float.NaN
                tiltMax = Float.NaN
                tiltReported = runCatching { event.device?.getMotionRange(MotionEvent.AXIS_TILT, event.source) != null }.getOrNull()
                InkLatency.stroke.down(event.eventTime, event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER, Diag.enabled())
            }
            MotionEvent.ACTION_MOVE -> InkLatency.stroke.move(event.historySize + 1)
        }
        for (h in 0 until event.historySize) {
            val lean = event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, index, h)
            pen.add(PenSample(event.getHistoricalEventTime(h), event.getHistoricalX(index, h), event.getHistoricalY(index, h), event.getHistoricalPressure(index, h), lean))
            tilt(lean)
        }
        val newest = event.getAxisValue(MotionEvent.AXIS_TILT, index)
        pen.add(PenSample(event.eventTime, event.getX(index), event.getY(index), event.getPressure(index), newest))
        tilt(newest)
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

    /**
     * A one-finger touch, traced like a pen stroke (#128: the owner's lag was with a finger too). Its
     * samples stay out of the pen log, and a touch that drew no ink writes no line (see [strokeEnded]).
     */
    private fun recordFinger(event: MotionEvent) {
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tiltMin = Float.NaN
                tiltMax = Float.NaN
                tiltReported = null
                InkLatency.stroke.down(event.eventTime, eraser = false, active = Diag.enabled(), pointer = "finger")
            }
            MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1) InkLatency.stroke.move(event.historySize + 1)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> strokeEnded(event.eventTime, event.actionMasked == MotionEvent.ACTION_CANCEL)
        }
    }

    /** One line per stroke in the diagnostics log (#128): counts, rate and app-side latency. */
    private fun strokeEnded(time: Long, cancelled: Boolean) {
        val t = InkLatency.stroke.up(time, cancelled)?.takeIf { it.pointer != "finger" || it.draws > 0 || it.normal != null }
        val mode = PenDiag.takePenMode()
        val ink = PenDiag.takeInkStroke()
        if (t != null) Diag.event(
            DiagArea.Ink, "stroke",
            "ms" to t.durationMs, "events" to t.events, "samples" to t.samples, "sample_hz" to t.sampleHz,
            "eraser" to t.eraser, "cancelled" to t.cancelled, "mode" to mode,
            "normal_n" to (t.normal?.count ?: 0), "normal_p50_ms" to t.normal?.medianMs, "normal_p95_ms" to t.normal?.p95Ms, "normal_max_ms" to t.normalMaxMs,
            "pointer" to t.pointer, "draws" to t.draws,
            "frame_p50_ms" to t.frameGap?.medianMs, "frame_p95_ms" to t.frameGap?.p95Ms, "frame_max_ms" to t.frameGapMaxMs,
            "draw_p50_us" to t.drawUs?.medianMs, "draw_p95_us" to t.drawUs?.p95Ms, "draw_max_us" to t.drawMaxUs,
            "recompositions" to t.recompositions,
            *inkStrokeFields(ink, tiltMin, tiltMax, tiltReported),
        )
        // the palm guard's ignored touches around this stroke (#156)
        PenDiag.flushPalm()
    }
}

/**
 * The pen model's numbers for one ink stroke's `stroke` line (#569), numbers and fixed words only:
 * nothing when the gesture drew no ink. Tilt is in degrees; NaN tilt (never reported) is left out.
 */
internal fun inkStrokeFields(ink: PenStrokeStats?, tiltMin: Float, tiltMax: Float, tiltReported: Boolean?): Array<Pair<String, Any?>> {
    if (ink == null) return emptyArray()
    val p = ink.pressures.toList()
    val v = ink.speeds.toList()
    val w = ink.widths.toList()
    return arrayOf(
        "style" to ink.style.key, "pressure_source" to ink.source.key, "pressure_setting" to ink.pressureSetting,
        "pressure_min" to p.minOrNull(), "pressure_p10" to quantile(p, 0.1f), "pressure_median" to quantile(p, 0.5f),
        "pressure_p90" to quantile(p, 0.9f), "pressure_max" to p.maxOrNull(),
        "raw_pressure_p10" to quantile(ink.raw.toList(), 0.1f), "raw_pressure_p90" to quantile(ink.raw.toList(), 0.9f),
        "speed_median" to quantile(v, 0.5f)?.toInt(), "speed_p90" to quantile(v, 0.9f)?.toInt(),
        "dp_per_pt" to ink.dpPerPt,
        "tilt_min" to tiltMin.takeIf { it.isFinite() }?.let { kotlin.math.round(it).toInt() },
        "tilt_max" to tiltMax.takeIf { it.isFinite() }?.let { kotlin.math.round(it).toInt() },
        "tilt_reported" to tiltReported,
        "width_min" to w.minOrNull(), "width_median" to quantile(w, 0.5f), "width_max" to w.maxOrNull(),
        // a pencil's ink density shows the paper through its grain: the opacity it looks like
        "opacity_min" to ink.opacity * (ink.densities.toList().minOrNull() ?: 1f), "opacity_max" to ink.opacity * (ink.densities.toList().maxOrNull() ?: 1f),
        "taper_start_pt" to ink.taperStartPt, "taper_end_pt" to ink.taperEndPt, "stabilisation" to ink.dials.smoothing,
        // how many of the pen's samples became points of the line (#609)
        "points" to ink.kept, "points_from" to ink.given,
        // what else was drawn each frame while it was written: with draw_p50_us, whether drawing slows as the page fills
        "drafts" to ink.draftsBefore, "draft_points" to ink.draftPointsBefore, "drafts_pictured" to ink.draftsPictured,
    )
}
