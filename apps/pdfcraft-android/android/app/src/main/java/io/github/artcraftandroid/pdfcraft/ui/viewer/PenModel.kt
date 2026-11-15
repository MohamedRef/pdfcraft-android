package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
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
 * - [speedThin] is how much a fast stroke thins (0.3 = down to 70 % at [speedRef] and faster).
 *   [speedRef] is the pen's speed on the glass, in dp per second, so the same hand movement
 *   thins the same at every zoom and on a phone as on a tablet. It is also the speed that counts
 *   as the lightest touch when pressure is made from speed.
 * - [nib] flattens the tip: a stroke along [nibAngleDeg] is thinner by that fraction (0 = round).
 * - [taperStart] and [taperEnd] draw the stroke's ends out to a point over that many pen widths.
 * - [dampingMs] is how fast the width may follow a change (a time constant; 0 = at once).
 * - [smoothing] takes the hand's tremor out of the line itself (0 = the points as the pen gave
 *   them, 1 = the most), see [InkSmoother].
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
    val smoothing: Float = 0f,
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
            smoothing = smoothing.ok(0f, 1f, 0f),
        )
    }

    /** Whether the width can change along a stroke; a constant pen saves as one plain line. */
    val varies: Boolean get() = maxScale - minScale > 1e-3f || speedThin > 0f || nib > 0f || taperStart > 0f || taperEnd > 0f

    companion object {
        /** The starting values of #569: guesses for the owner to tune in the Pen lab, not targets. */
        fun default(style: InkStyle): PenDials = when (style) {
            // nearly constant: a ballpoint moves a little with pressure, nothing else
            InkStyle.Ballpoint -> PenDials(minScale = 0.85f, maxScale = 1f, smoothing = 0.4f)
            // wide range, thins when fast, downstrokes thicker than sidestrokes, pointed ends
            InkStyle.Fountain -> PenDials(0.25f, 1.6f, curve = 1.3f, speedThin = 0.3f, nib = 0.35f, taperStart = 2.5f, taperEnd = 3f, dampingMs = 25f, smoothing = 0.5f)
            // very wide range, strong speed, long pointed ends
            InkStyle.Brush -> PenDials(0.1f, 2.5f, curve = 1.5f, speedThin = 0.5f, taperStart = 4f, taperEnd = 6f, dampingMs = 35f, smoothing = 0.5f)
            InkStyle.Pencil -> PenDials(0.8f, 1.1f, smoothing = 0.3f)
            // the marker keeps one width (its own factor), whatever the pen does
            InkStyle.Highlighter -> PenDials(1f, 1f, dampingMs = 0f, smoothing = 0.5f)
        }
    }
}

/** The thinnest a tapered end goes, as a fraction of the width it would have had. */
internal const val PEN_TAPER_FLOOR = 0.12f

/** Where a stroke's pressure comes from: the pen's sensor, or made from the stroke's speed. */
internal enum class PressureSource(val key: String) { Pen("pen"), Speed("speed") }

/**
 * The pressure band ordinary handwriting uses on this pen ([lo]…[hi], its 10th and 90th percentile,
 * measured, see [PressureCalibration]), stretched over the pen's range. The identity (0…1) until
 * it is measured.
 *
 * The band takes the middle of the range ([BAND_LOW]…[BAND_HIGH]), not all of it: a touch lighter
 * than usual still gets thinner and a press harder than usual still gets thicker, up to the pen's
 * real limits. Stretching the band over the whole range left a tenth of ordinary writing at the
 * thinnest width, a tenth at the thickest, and nothing more to give when pressing for emphasis.
 */
internal data class PressureRange(val lo: Float = 0f, val hi: Float = 1f) {
    fun stretch(raw: Float): Float {
        val p = raw.takeIf { it.isFinite() && it > 0f }?.coerceIn(0f, 1f) ?: return 1f
        if (!(hi - lo >= MIN_SPAN)) return p
        // A narrow band is widened around its middle first (0.0.33 log: a light hand wrote at
        // 0.07…0.15, stretched about ten times, so a touch more pressure jumped the line to its
        // thickest). The stretch never multiplies the pen's pressure by more than about 2.6.
        val span = maxOf(hi - lo, MIN_STRETCH_SPAN)
        val lo = ((lo + hi - span) / 2f).coerceIn(0f, 1f - span)
        val hi = lo + span
        // a band that starts at 0 or ends at 1 has no room beyond it: the unmeasured 0…1 stays as it is
        val low = if (lo > 0f) BAND_LOW else 0f
        val high = if (hi < 1f) BAND_HIGH else 1f
        return when {
            p < lo -> low * p / lo
            p > hi -> high + (1f - high) * (p - hi) / (1f - hi)
            else -> low + (high - low) * (p - lo) / (hi - lo)
        }
    }

    companion object {
        /** A narrower band is not trusted: the stretch would turn sensor noise into width. */
        const val MIN_SPAN = 0.08f
        /** The narrowest band stretched over [BAND_LOW]…[BAND_HIGH]: at most 0.9 / 0.35 ≈ 2.6 times the pen's own change. */
        const val MIN_STRETCH_SPAN = 0.35f
        /** Where the measured band's ends land in the pen's range: its percentiles, 10 % and 90 %. */
        const val BAND_LOW = 0.1f
        const val BAND_HIGH = 0.9f
    }
}

/**
 * What one stroke used, for its `stroke` log line (#569): numbers only. [dpPerPt] is the page's
 * size on the glass while it was drawn (dp per page point): the zoom the owner writes at.
 */
internal class PenStrokeStats(
    val style: InkStyle, val source: PressureSource, val penWidth: Float, val dials: PenDials, val opacity: Float,
    val dpPerPt: Float = 1f,
) {
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
 *
 * Speed is the pen's speed on the glass (dp per second), not on the page: [dpPerPt] is how many dp
 * one page point takes on screen while the stroke is drawn. Measured on the page, the same hand
 * movement was several times "faster" at fit-width on a phone than zoomed in on a tablet, so a
 * pen tuned at one zoom drew a different line at another.
 */
internal class PenDynamics(
    dials: PenDials,
    private val source: PressureSource,
    private val range: PressureRange,
    /** The pen's width in points; the start taper's length is counted in it. */
    private val penWidth: Float,
    val stats: PenStrokeStats? = null,
    /** This hand's pressure curve (Pen lab), applied to the pen's own pressure after the stretch. */
    private val pressureCurve: PressureCurve = PressureCurve.LINEAR,
    dpPerPt: Float = 1f,
) {
    private val d = dials.sanitized()
    private val dpPerPt = dpPerPt.takeIf { it.isFinite() && it > 0f } ?: 1f
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
                val instant = pendingDist * dpPerPt / (dtMs / 1000f)
                speed += (instant - speed) * (1f - exp(-dtMs / SPEED_TAU_MS))
                pendingDist = 0f
            }
        }
        if (prev == null || timeMs > lastTime) lastTime = timeMs
        last = p

        val made = source == PressureSource.Speed
        val p01 = if (made) (1f - speed / d.speedRef).coerceIn(0f, 1f) else pressureCurve.map(range.stretch(pressure))
        val shaped = p01.pow(d.curve)
        var target = d.minScale + (d.maxScale - d.minScale) * shaped
        if (d.speedThin > 0f) target *= 1f - d.speedThin * (speed / d.speedRef).coerceIn(0f, 1f)
        if (d.nib > 0f && (dirX != 0f || dirY != 0f)) {
            target *= nibWidth(atan2(dirY, dirX) - Math.toRadians(d.nibAngleDeg.toDouble()).toFloat(), d.nib)
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

    /** How much this stroke's points are smoothed ([PenDials.smoothing]). */
    val smoothing: Float get() = d.smoothing

    /** The pen's speed on the glass now, dp/s (for tests). */
    val speedDpPerS: Float get() = speed

    companion object {
        /** How quickly the measured speed follows the pen. */
        const val SPEED_TAU_MS = 30f
    }
}

/**
 * The width an elliptical nib draws moving at [angle] (radians from its long axis), as a fraction
 * of its long axis; [flat] is how much thinner it is across (0 = round). An ellipse with axes
 * `u`, `v` and semi-axes `a`, `b` swept along direction `d` covers `2·√(a²(u·n)² + b²(v·n)²)` across
 * the normal `n` (the support-point width, as VectorCraft's calligraphic brush, Apache-2.0, sweeps
 * its nib). So a stroke along the long axis is `1 - flat` wide, one across it full width, and the
 * width turns smoothly between, without the kink of a plain `|cos|`.
 */
internal fun nibWidth(angle: Float, flat: Float): Float {
    val b = 1f - flat.coerceIn(0f, 0.95f)
    val s = kotlin.math.sin(angle)
    val c = cos(angle)
    return kotlin.math.sqrt(s * s + b * b * c * c)
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

/**
 * Takes the hand's tremor out of a stroke's points as they come (#569): a One Euro filter (Casiez,
 * Roussel and Vogel, CHI 2012), a low-pass filter whose cut-off rises with the pen's speed. A slow
 * pen, where a wobble shows, is smoothed hard; a fast one hardly at all, so the line does not trail
 * behind the pen. [amount] 0 passes the points through unchanged. Each point is final once given,
 * so the line drawn under the pen is the line saved; on lift the line is taken to the pen's last
 * point ([end]), as PhotoCraft's "catch-up on end" does.
 *
 * The speed is the pen's speed on the glass (dp per second; [dpPerPt] is how many dp one page point
 * takes on screen), because the tremor is the hand's: measured on the page, the same writing was
 * smoothed more, and trailed the pen further, the more the page was zoomed in. While the stroke is
 * drawn the gap between the smoothed line and the pen is filled by a tail to [tip], drawn only.
 */
internal class InkSmoother(amount: Float, dpPerPt: Float = 1f) {
    private val a = amount.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    private val dpPerPt = dpPerPt.takeIf { it.isFinite() && it > 0f } ?: 1f
    /** The cut-off when the pen is still: 30 Hz (barely smoothed) at a little, 2 Hz at the most. */
    private val minCutoffHz = 2f + 28f * (1f - a) * (1f - a)
    private var smoothed: Offset? = null
    private var raw: Offset? = null
    private var velocity = Offset.Zero
    private var lastTime = 0L

    /** The smoothed point for the pen at [p] (page points) at [timeMs]. */
    fun next(p: Offset, timeMs: Long): Offset {
        val prev = smoothed
        // a broken sample keeps the line where it is
        if (!p.x.isFinite() || !p.y.isFinite()) return prev ?: p
        if (a <= 0f || prev == null) {
            raw = p
            smoothed = p
            lastTime = timeMs
            return p
        }
        raw = p
        // samples batched at one time count as 1 ms apart
        val dt = (timeMs - lastTime).coerceAtLeast(1L) / 1000f
        if (timeMs > lastTime) lastTime = timeMs
        // how fast the pen is leaving the line behind, on the glass
        velocity += ((p - prev) * dpPerPt / dt - velocity) * alpha(VELOCITY_CUTOFF_HZ, dt)
        val cutoff = minCutoffHz + BETA * velocity.getDistance()
        val out = prev + (p - prev) * alpha(cutoff, dt)
        smoothed = out
        return out
    }

    /** Where the pen is now (page points): the last point given, smoothed or not; null before the first. */
    val tip: Offset? get() = raw

    /** The pen's last point when the smoothed line stopped short of it, else null. */
    fun end(): Offset? {
        val r = raw ?: return null
        val s = smoothed ?: return null
        return r.takeIf { a > 0f && (r - s).getDistance() > END_SLOP_PT }
    }

    private fun alpha(cutoffHz: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoffHz)
        return 1f / (1f + tau / dt)
    }

    companion object {
        /**
         * How much the cut-off rises per dp/s of speed on the glass. 0.1 is what the first version
         * (0.06 per pt/s on the page) came to with a page at fit-width on a phone (0.6 dp a point).
         */
        const val BETA = 0.1f
        const val VELOCITY_CUTOFF_HZ = 1f
        const val END_SLOP_PT = 0.05f
    }
}

/**
 * The tail drawn after a growing stroke's last point, in screen pixels (#569): on to the pen
 * ([tip]) when smoothing keeps the line behind it, then on by [ahead] (how far the pen will have
 * moved a frame from now) when that is known. Empty when there is nothing to draw. Drawn only: the
 * next sample replaces it, so the ink reaches the pen without the saved line being any less smooth.
 */
internal fun inkTailPoints(last: Offset, tip: Offset?, ahead: Pair<Float, Float>?): List<Offset> {
    if (!last.x.isFinite() || !last.y.isFinite()) return emptyList()
    val out = ArrayList<Offset>(3)
    out += last
    val pen = tip?.takeIf { it.x.isFinite() && it.y.isFinite() } ?: last
    if (pen != last) out += pen
    if (ahead != null && ahead.first.isFinite() && ahead.second.isFinite() && (ahead.first != 0f || ahead.second != 0f)) {
        out += Offset(pen.x + ahead.first, pen.y + ahead.second)
    }
    return if (out.size < 2) emptyList() else out
}

/**
 * How the pen's pressure (0…1, after the stretch) turns into the pressure the pens use: a smooth
 * curve through (0, 0), three points at 0.25, 0.5 and 0.75 that the owner drags in the Pen lab
 * ([ys]), and (1, 1). A monotone cubic (Fritsch and Carlson, 1980), as PhotoCraft's response
 * curve (Apache-2.0), so a harder press never gives a thinner line and the curve never overshoots.
 */
internal class PressureCurve(ys: List<Float>) {
    /** The three dragged points, kept in 0…1 and rising. */
    val ys: List<Float>
    private val yk: FloatArray
    private val mk: FloatArray

    init {
        var lo = 0f
        val clean = List(3) { i ->
            val y = (ys.getOrNull(i)?.takeIf { it.isFinite() } ?: XS[i + 1]).coerceIn(0f, 1f).coerceAtLeast(lo)
            lo = y
            y
        }
        this.ys = clean
        yk = floatArrayOf(0f, clean[0], clean[1], clean[2], 1f)
        val n = XS.size
        val delta = FloatArray(n - 1) { (yk[it + 1] - yk[it]) / (XS[it + 1] - XS[it]) }
        mk = FloatArray(n) { k ->
            when (k) {
                0 -> delta[0]
                n - 1 -> delta[n - 2]
                else -> if (delta[k - 1] * delta[k] <= 0f) 0f else (delta[k - 1] + delta[k]) / 2f
            }
        }
        for (k in 0 until n - 1) {
            if (delta[k] == 0f) {
                mk[k] = 0f
                mk[k + 1] = 0f
                continue
            }
            val a = mk[k] / delta[k]
            val b = mk[k + 1] / delta[k]
            val h = a * a + b * b
            if (h > 9f) {
                val t = 3f / kotlin.math.sqrt(h)
                mk[k] = t * a * delta[k]
                mk[k + 1] = t * b * delta[k]
            }
        }
    }

    val linear: Boolean get() = ys.indices.all { kotlin.math.abs(ys[it] - XS[it + 1]) < 1e-4f }

    fun map(x: Float): Float {
        if (!x.isFinite()) return 1f
        val v = x.coerceIn(0f, 1f)
        val k = (v * 4f).toInt().coerceAtMost(3)
        val h = XS[k + 1] - XS[k]
        val t = (v - XS[k]) / h
        val t2 = t * t
        val t3 = t2 * t
        val y = (2 * t3 - 3 * t2 + 1) * yk[k] + (t3 - 2 * t2 + t) * h * mk[k] +
            (-2 * t3 + 3 * t2) * yk[k + 1] + (t3 - t2) * h * mk[k + 1]
        return y.coerceIn(0f, 1f)
    }

    override fun equals(other: Any?) = other is PressureCurve && other.ys == ys
    override fun hashCode() = ys.hashCode()

    companion object {
        val XS = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        val LINEAR = PressureCurve(listOf(0.25f, 0.5f, 0.75f))
    }
}
