package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow

/**
 * One pen's dials (#569). Every pen is the same model; the pens differ only in these numbers. A
 * width is a multiple of the pen's chosen width (its thickness in the popover).
 *
 * - [minScale]…[maxScale] is the pressure range: the width at the lightest and the hardest touch,
 *   [curve] the shape between (1 = straight, more = stays thin longer).
 * - [speedThin] is how much a fast stroke thins (0.3 = down to 70 % at [speedRef] pt/s and faster).
 *   [speedRef] is also the speed that counts as the lightest touch when pressure is made from speed.
 * - [nib] flattens the tip: a stroke along [nibAngleDeg] is thinner by that fraction (0 = round).
 * - [taperStart] and [taperEnd] draw the stroke's ends out to a point over that many pen widths.
 * - [dampingMs] is how fast the width may follow a change (a time constant; 0 = at once).
 */
internal data class PenDials(
    val minScale: Float,
    val maxScale: Float,
    val curve: Float = 1f,
    val speedThin: Float = 0f,
    val speedRef: Float = 600f,
    val nib: Float = 0f,
    val nibAngleDeg: Float = 0f,
    val taperStart: Float = 0f,
    val taperEnd: Float = 0f,
    val dampingMs: Float = 20f,
) {
    /** The same dials kept inside their slider ranges, so a stored value can never break a stroke. */
    fun sanitized(): PenDials {
        fun Float.ok(lo: Float, hi: Float, def: Float) = if (isFinite()) coerceIn(lo, hi) else def
        val lo = minScale.ok(0.05f, 3f, 1f)
        return PenDials(
            minScale = lo,
            maxScale = maxScale.ok(0.05f, 3f, 1f).coerceAtLeast(lo),
            curve = curve.ok(0.3f, 3f, 1f),
            speedThin = speedThin.ok(0f, 0.9f, 0f),
            speedRef = speedRef.ok(100f, 3000f, 600f),
            nib = nib.ok(0f, 0.9f, 0f),
            nibAngleDeg = nibAngleDeg.ok(-90f, 90f, 0f),
            taperStart = taperStart.ok(0f, 10f, 0f),
            taperEnd = taperEnd.ok(0f, 10f, 0f),
            dampingMs = dampingMs.ok(0f, 200f, 20f),
        )
    }

    /** Whether the width can change along a stroke; a constant pen saves as one plain line. */
    val varies: Boolean get() = maxScale - minScale > 1e-3f || speedThin > 0f || nib > 0f || taperStart > 0f || taperEnd > 0f

    companion object {
        /** The starting values of #569: guesses for the owner to tune in the Pen lab, not targets. */
        fun default(style: InkStyle): PenDials = when (style) {
            // nearly constant: a ballpoint moves a little with pressure, nothing else
            InkStyle.Ballpoint -> PenDials(minScale = 0.85f, maxScale = 1f)
            // wide range, thins when fast, downstrokes thicker than sidestrokes, pointed ends
            InkStyle.Fountain -> PenDials(0.25f, 1.6f, curve = 1.3f, speedThin = 0.3f, nib = 0.35f, taperStart = 2.5f, taperEnd = 3f, dampingMs = 25f)
            // very wide range, strong speed, long pointed ends
            InkStyle.Brush -> PenDials(0.1f, 2.5f, curve = 1.5f, speedThin = 0.5f, taperStart = 4f, taperEnd = 6f, dampingMs = 35f)
            InkStyle.Pencil -> PenDials(0.8f, 1.1f)
            // the marker keeps one width (its own factor), whatever the pen does
            InkStyle.Highlighter -> PenDials(1f, 1f, dampingMs = 0f)
        }
    }
}

/** The thinnest a tapered end goes, as a fraction of the width it would have had. */
internal const val PEN_TAPER_FLOOR = 0.12f

/** Where a stroke's pressure comes from: the pen's sensor, or made from the stroke's speed. */
internal enum class PressureSource(val key: String) { Pen("pen"), Speed("speed") }

/**
 * The pressure band ordinary handwriting uses on this pen ([lo]…[hi], measured, see [PressureCalibration]),
 * stretched over the pen's whole range. The identity (0…1) until it is measured.
 */
internal data class PressureRange(val lo: Float = 0f, val hi: Float = 1f) {
    fun stretch(raw: Float): Float {
        val p = raw.takeIf { it.isFinite() && it > 0f }?.coerceIn(0f, 1f) ?: return 1f
        if (!(hi - lo >= MIN_SPAN)) return p
        return ((p - lo) / (hi - lo)).coerceIn(0f, 1f)
    }

    companion object {
        /** A narrower band is not trusted: the stretch would turn sensor noise into width. */
        const val MIN_SPAN = 0.08f
    }
}

/** What one stroke used, for its `stroke` log line (#569): numbers only. */
internal class PenStrokeStats(val style: InkStyle, val source: PressureSource, val penWidth: Float, val dials: PenDials, val opacity: Float) {
    val pressures = FloatSampleList()
    /** The raw sensor pressures (pen only), for the pressure range measurement. */
    val raw = FloatSampleList()
    val speeds = FloatSampleList()
    val widths = FloatSampleList()
    val taperStartPt: Float get() = dials.taperStart * penWidth
    val taperEndPt: Float get() = dials.taperEnd * penWidth
}

/** A bounded list of samples: past [cap] it keeps every other one, so a long stroke stays small. */
internal class FloatSampleList(private val cap: Int = 2048) {
    private var data = FloatArray(64)
    var size = 0
        private set
    private var stride = 1
    private var seen = 0

    fun add(v: Float) {
        if (!v.isFinite()) return
        if (seen++ % stride != 0) return
        if (size == cap) {
            // halve: keep every other sample and take every other one from now on
            for (i in 0 until size / 2) data[i] = data[i * 2]
            size /= 2
            stride *= 2
        }
        if (size == data.size) data = data.copyOf(minOf(cap, data.size * 2))
        data[size++] = v
    }

    fun toList(): List<Float> = data.copyOf(size).toList()
}

/** The [q] quantile (0…1) of [values] by nearest rank; null when empty. */
internal fun quantile(values: List<Float>, q: Float): Float? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    return sorted[((sorted.size - 1) * q.coerceIn(0f, 1f)).let { kotlin.math.round(it).toInt() }]
}

/**
 * The width of one stroke as it grows (#569): each new sample gets its width from the pressure
 * (measured range stretched, or made from speed), speed, direction, start taper and damping.
 * Everything is worked out from the samples so far, so the line drawn under the pen is the line
 * that is saved; only the end taper ([inkEndTaper]) is added on lift.
 */
internal class PenDynamics(
    dials: PenDials,
    private val source: PressureSource,
    private val range: PressureRange,
    /** The pen's width in points; the start taper's length is counted in it. */
    private val penWidth: Float,
    val stats: PenStrokeStats? = null,
) {
    private val d = dials.sanitized()
    private var last: Offset? = null
    private var lastTime = Long.MIN_VALUE
    private var pendingDist = 0f
    private var speed = 0f
    private var travelled = 0f
    private var dirX = 0f
    private var dirY = 0f
    private var scale = Float.NaN

    /** The width (a multiple of the pen width) of the sample at [p] (page points), [timeMs], [pressure] (0…1 from the pen). */
    fun next(p: Offset, timeMs: Long, pressure: Float): Float {
        val prev = last
        var dtMs = 0f
        if (prev != null) {
            val dx = p.x - prev.x
            val dy = p.y - prev.y
            val len = hypot(dx, dy)
            if (len.isFinite()) {
                travelled += len
                pendingDist += len
                // the stroke's direction over its last few samples
                dirX = dirX * 0.5f + dx
                dirY = dirY * 0.5f + dy
            }
            if (timeMs > lastTime) {
                // batched samples can share one time: their distance waits for the next time step
                dtMs = (timeMs - lastTime).toFloat()
                val instant = pendingDist / (dtMs / 1000f)
                speed += (instant - speed) * (1f - exp(-dtMs / SPEED_TAU_MS))
                pendingDist = 0f
            }
        }
        if (prev == null || timeMs > lastTime) lastTime = timeMs
        last = p

        val made = source == PressureSource.Speed
        val p01 = if (made) (1f - speed / d.speedRef).coerceIn(0f, 1f) else range.stretch(pressure)
        val shaped = p01.pow(d.curve)
        var target = d.minScale + (d.maxScale - d.minScale) * shaped
        if (d.speedThin > 0f) target *= 1f - d.speedThin * (speed / d.speedRef).coerceIn(0f, 1f)
        if (d.nib > 0f && (dirX != 0f || dirY != 0f)) {
            val angle = atan2(dirY, dirX) - Math.toRadians(d.nibAngleDeg.toDouble()).toFloat()
            target *= 1f - d.nib * abs(cos(angle))
        }
        scale = if (scale.isNaN() || d.dampingMs <= 0f) target else {
            // same-time samples still move a little, as if 2 ms apart
            val alpha = 1f - exp(-maxOf(dtMs, 2f) / d.dampingMs)
            scale + (target - scale) * alpha
        }
        untapered.add(scale)
        var out = scale
        if (d.taperStart > 0f) out *= taperFactor(travelled / (d.taperStart * penWidth))
        stats?.let {
            it.pressures.add(p01)
            if (!made) it.raw.add(pressure)
            it.speeds.add(speed)
            it.widths.add(out * penWidth * inkStyleWidthFactor(it.style))
        }
        return out
    }

    /** Each sample's width before the start taper, for [finish]. */
    private val untapered = ArrayList<Float>()

    /**
     * The stroke is lifted: its end is drawn out to a point ([inkEndTaper]) over at most half its
     * length. A stroke shorter than the start taper (a dot, a full stop) keeps its full width
     * instead, so a dot is never a faint speck.
     */
    fun finish(points: List<Offset>, scales: MutableList<Float>) {
        if (points.size != scales.size || points.isEmpty()) return
        if (d.taperStart > 0f && travelled < d.taperStart * penWidth && untapered.size == scales.size) {
            for (i in scales.indices) scales[i] = untapered[i]
        }
        if (d.taperEnd > 0f) inkEndTaper(points, scales, minOf(d.taperEnd * penWidth, travelled / 2f))
    }

    /** The speed now, pt/s (for tests). */
    val speedPtPerS: Float get() = speed

    companion object {
        /** How quickly the measured speed follows the pen. */
        const val SPEED_TAU_MS = 30f
    }
}

/** The width fraction at [t] (0 = the tip, 1 = full width) of a tapered end: a smooth ease out. */
internal fun taperFactor(t: Float): Float {
    if (!(t < 1f)) return 1f
    val x = t.coerceAtLeast(0f)
    val eased = 1f - (1f - x) * (1f - x)
    return PEN_TAPER_FLOOR + (1f - PEN_TAPER_FLOOR) * eased
}

/**
 * Draw the end of a finished stroke out to a point (#569): the widths within [lengthPt] of the
 * last point are scaled down smoothly to [PEN_TAPER_FLOOR], so nothing jumps where the taper starts.
 */
internal fun inkEndTaper(points: List<Offset>, scales: MutableList<Float>, lengthPt: Float) {
    if (!(lengthPt > 0f) || points.size < 2 || scales.size != points.size) return
    var fromEnd = 0f
    for (i in points.lastIndex downTo 0) {
        if (i < points.lastIndex) fromEnd += (points[i + 1] - points[i]).getDistance()
        if (fromEnd >= lengthPt) break
        scales[i] = scales[i] * taperFactor(fromEnd / lengthPt)
    }
}
