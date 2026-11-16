package io.github.artcraftandroid.pdfcraft.ui.viewer

import android.content.SharedPreferences
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea

/**
 * Measures the pressure band ordinary handwriting uses on this pen (#569). Each pen stroke long
 * enough to count gives its 10th and 90th percentile; the band is the running mean of those, used
 * once [MIN_STROKES] strokes are in. Pure, so it is unit-tested; [PenTuning] keeps it.
 */
internal data class PressureCalibration(val lo: Float = 0f, val hi: Float = 1f, val strokes: Int = 0, val measuredAt: Long = 0L) {
    /** The range the next stroke stretches by: the identity until enough strokes are measured. */
    val range: PressureRange get() = if (strokes >= MIN_STROKES && hi > lo) PressureRange(lo, hi) else PressureRange()

    /** The calibration after one more stroke of raw sensor [pressures]; unchanged for a stroke too short or pressureless. */
    fun learn(pressures: List<Float>, now: Long): PressureCalibration {
        val valid = pressures.filter { it.isFinite() && it > 0f && it <= 1f }
        if (valid.size < MIN_SAMPLES) return this
        val p10 = quantile(valid, 0.1f) ?: return this
        val p90 = quantile(valid, 0.9f) ?: return this
        // a pen that reports one fixed pressure has nothing to measure
        if (p90 - p10 < 1e-3f && valid.all { it == valid[0] }) return this
        val n = strokes.coerceAtMost(WINDOW - 1)
        val newLo = if (strokes == 0) p10 else (lo * n + p10) / (n + 1)
        val newHi = if (strokes == 0) p90 else (hi * n + p90) / (n + 1)
        return PressureCalibration(newLo, newHi, strokes + 1, now)
    }

    companion object {
        const val MIN_SAMPLES = 12
        const val MIN_STROKES = 5
        /** The mean follows about the last 30 strokes, so a new pen or a new habit is learnt again. */
        const val WINDOW = 30
    }
}

/**
 * The Pen lab's dials per pen and the measured pressure range (#569), kept on this device. A dial
 * the owner never moved is the pen's default ([PenDials.default]), so better defaults reach him
 * with a new build; a moved one stays until "Reset".
 */
@Stable
internal class PenTuning(private val prefs: SharedPreferences) {
    init {
        // Dials moved under an older pen model are dropped once: they were tuned against its
        // faults (#601: the owner had cut the fountain pen to 0.25…0.28 to get rid of the blob) and
        // mean something else now. The hand's pressure curve and measured range stay.
        if (prefs.getInt(MODEL_KEY, 1) < MODEL) {
            val e = prefs.edit()
            InkStyle.entries.forEach { style -> KEYS.forEach { e.remove(key(style, it)) } }
            // and the old Jetpack Ink trial's switch (the trial itself is removed) is cleared
            e.remove("jetpack")
            e.putInt(MODEL_KEY, MODEL).apply()
        }
    }

    private val dials = InkStyle.entries.associateWith { mutableStateOf(load(it)) }

    var calibration by mutableStateOf(
        PressureCalibration(
            prefs.getFloat("pressure.lo", 0f), prefs.getFloat("pressure.hi", 1f),
            prefs.getInt("pressure.strokes", 0), prefs.getLong("pressure.at", 0L),
        ),
    )
        private set

    /** This hand's pressure curve, for every pen (Pen lab). */
    var pressureCurve by mutableStateOf(
        PressureCurve(List(3) { i -> runCatching { prefs.getFloat("curve.y$i", PressureCurve.XS[i + 1]) }.getOrDefault(PressureCurve.XS[i + 1]) }),
    )
        private set

    fun updatePressureCurve(c: PressureCurve) {
        pressureCurve = c
        val e = prefs.edit()
        c.ys.forEachIndexed { i, y -> if (c.linear) e.remove("curve.y$i") else e.putFloat("curve.y$i", y) }
        e.apply()
    }

    fun dials(style: InkStyle): PenDials = dials.getValue(style).value

    /** Whether [style]'s dials differ from its defaults. */
    fun tuned(style: InkStyle): Boolean = dials(style) != PenDials.default(style)

    fun update(style: InkStyle, d: PenDials) {
        val v = d.sanitized()
        dials.getValue(style).value = v
        val e = prefs.edit()
        if (v == PenDials.default(style)) KEYS.forEach { e.remove(key(style, it)) }
        else KEYS.zip(v.toList()).forEach { (k, f) -> e.putFloat(key(style, k), f) }
        e.apply()
    }

    fun reset(style: InkStyle) = update(style, PenDials.default(style))

    /** One more pen stroke's raw pressures; logs `pressure_range` each time the band moves on by [LOG_EVERY] strokes. */
    fun learn(pressures: List<Float>, now: Long = System.currentTimeMillis()) {
        val next = calibration.learn(pressures, now)
        if (next == calibration) return
        val wasInUse = calibration.range != PressureRange()
        val inUse = next.range != PressureRange()
        calibration = next
        prefs.edit().putFloat("pressure.lo", next.lo).putFloat("pressure.hi", next.hi)
            .putInt("pressure.strokes", next.strokes).putLong("pressure.at", next.measuredAt).apply()
        // also whenever the stretch comes into use or goes out of it, so the log shows a switch (#607)
        if (next.strokes == PressureCalibration.MIN_STROKES || next.strokes % LOG_EVERY == 0 || inUse != wasInUse) logRange(now)
    }

    /** Forget the measured range; the next strokes measure it again. */
    fun resetPressure() {
        calibration = PressureCalibration()
        prefs.edit().remove("pressure.lo").remove("pressure.hi").remove("pressure.strokes").remove("pressure.at").apply()
    }

    /** `pressure_range`: the band in use for the stretch and how long ago it was last measured. */
    fun logRange(now: Long = System.currentTimeMillis()) {
        val c = calibration
        val r = c.range
        Diag.event(
            DiagArea.Ink, "pressure_range",
            "lo" to r.lo, "hi" to r.hi, "measured_lo" to c.lo, "measured_hi" to c.hi, "strokes" to c.strokes,
            "in_use" to (c.range != PressureRange()), "age_min" to if (c.measuredAt > 0L) (now - c.measuredAt) / 60_000L else -1L,
        )
    }

    /** `pen_lab_values`: every pen's dials, one line per pen, when the owner taps "Write values to the log". */
    fun logValues() {
        InkStyle.entries.forEach { style ->
            val d = dials(style)
            Diag.event(
                DiagArea.Ink, "pen_lab_values",
                "style" to style.key, "tuned" to tuned(style),
                "min" to d.minScale, "max" to d.maxScale, "curve" to d.curve,
                "speed_thin" to d.speedThin, "speed_ref" to d.speedRef.toInt(),
                "nib" to d.nib, "nib_angle" to d.nibAngleDeg.toInt(),
                "taper_start" to d.taperStart, "taper_end" to d.taperEnd, "damping_ms" to d.dampingMs.toInt(), "smoothing" to d.smoothing, "density_min" to d.densityMin, "density_max" to d.densityMax, "tilt" to d.tilt,
            )
        }
        val c = pressureCurve
        Diag.event(DiagArea.Ink, "pressure_curve", "y25" to c.ys[0], "y50" to c.ys[1], "y75" to c.ys[2], "linear" to c.linear)
        logRange()
    }

    private fun load(style: InkStyle): PenDials {
        val def = PenDials.default(style)
        val defaults = def.toList()
        val values = KEYS.mapIndexed { i, k -> runCatching { prefs.getFloat(key(style, k), defaults[i]) }.getOrDefault(defaults[i]) }
        return penDialsOf(values).sanitized()
    }

    private fun key(style: InkStyle, dial: String) = "${style.key}.$dial"

    companion object {
        val KEYS = listOf("min", "max", "curve", "speedThin", "speedRef", "nib", "nibAngle", "taperStart", "taperEnd", "damping", "smoothing", "densityMin", "densityMax", "tilt")
        const val LOG_EVERY = 25
        /** The pen model the stored dials were tuned for; a newer [MODEL] drops them (2: #601). */
        const val MODEL = 2
        const val MODEL_KEY = "dials.model"
    }
}

/** The dials in [PenTuning.KEYS] order. */
internal fun PenDials.toList(): List<Float> =
    listOf(minScale, maxScale, curve, speedThin, speedRef, nib, nibAngleDeg, taperStart, taperEnd, dampingMs, smoothing, densityMin, densityMax, tilt)

internal fun penDialsOf(v: List<Float>): PenDials {
    // a missing value is NaN, which [PenDials.sanitized] turns into the dial's neutral value
    fun at(i: Int) = v.getOrElse(i) { Float.NaN }
    return PenDials(at(0), at(1), at(2), at(3), at(4), at(5), at(6), at(7), at(8), at(9), at(10), at(11), at(12), at(13))
}
