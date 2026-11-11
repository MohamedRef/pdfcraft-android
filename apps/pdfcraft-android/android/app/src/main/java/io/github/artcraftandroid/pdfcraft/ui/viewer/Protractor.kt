package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The Draw protractor (#150), in screen pixels: a half disc of [radius] above a baseline through
 * [center] at [angleDeg] (0 = baseline pointing right, the half disc above it). Like the ruler it
 * lives only in the viewer and is never written to the PDF, exports or prints.
 *
 * A freehand stroke that starts on its arc follows the circle (an arc of any length); a stroke
 * that starts on its centre draws a straight ray from the centre at a whole-degree angle.
 */
internal data class Protractor(val center: Offset, val angleDeg: Float, val radius: Float) {
    private val rad get() = Math.toRadians(angleDeg.toDouble())
    private val along: Offset get() = Offset(cos(rad).toFloat(), sin(rad).toFloat())
    /** Unit normal towards the half disc (screen y grows downwards, so "up" is -y at angle 0). */
    private val up: Offset get() = Offset(sin(rad).toFloat(), -cos(rad).toFloat())

    /** [p] in the protractor's frame: x along the baseline, y up into the half disc. */
    private fun local(p: Offset): Offset {
        val d = p - center
        return Offset(d.x * along.x + d.y * along.y, d.x * up.x + d.y * up.y)
    }

    private fun fromLocal(x: Float, y: Float): Offset = center + along * x + up * y

    /** Whether [p] is on the half disc (with [slack] around it), where fingers move and turn it. */
    fun contains(p: Offset, slack: Float = 0f): Boolean {
        val l = local(p)
        return l.y >= -slack && hypot(l.x, l.y) <= radius + slack
    }

    /** The angle of [p] about the centre, measured from the baseline towards the half disc, in (-180, 180]. */
    fun angleOf(p: Offset): Float {
        val l = local(p)
        return normalizedAngle(Math.toDegrees(atan2(l.y.toDouble(), l.x.toDouble())).toFloat())
    }

    /** What a stroke starting at [p] follows: the arc, a ray from the centre, or nothing. */
    fun snapAt(p: Offset, reach: Float): ProtractorSnap? {
        val l = local(p)
        val d = hypot(l.x, l.y)
        return when {
            d <= reach -> ProtractorSnap.Ray
            abs(d - radius) <= reach && l.y >= -reach -> ProtractorSnap.Arc
            else -> null
        }
    }

    /** [p] moved onto the circle, [outset] outside it (half the stroke width, so ink sits on the edge). */
    fun projectArc(p: Offset, outset: Float = 0f): Offset {
        val a = Math.toRadians(angleOf(p).toDouble())
        val r = radius + outset
        return fromLocal((r * cos(a)).toFloat(), (r * sin(a)).toFloat())
    }

    /** [p] moved onto the ray from the centre at [rayDeg] (from the baseline); never behind the centre. */
    fun projectRay(p: Offset, rayDeg: Float): Offset {
        val a = Math.toRadians(rayDeg.toDouble())
        val dir = Offset(cos(a).toFloat(), sin(a).toFloat())
        val l = local(p)
        val t = (l.x * dir.x + l.y * dir.y).coerceAtLeast(0f)
        return fromLocal(dir.x * t, dir.y * t)
    }

    /** Moved by [pan] and turned by [rotationDeg] about [pivot]. */
    fun transformed(pan: Offset, rotationDeg: Float, pivot: Offset): Protractor {
        val r = Math.toRadians(rotationDeg.toDouble())
        val d = center - pivot
        val turned = Offset(
            (d.x * cos(r) - d.y * sin(r)).toFloat(),
            (d.x * sin(r) + d.y * cos(r)).toFloat(),
        ) + pivot
        return copy(center = turned + pan, angleDeg = normalizedAngle(angleDeg + rotationDeg))
    }
}

internal enum class ProtractorSnap { Arc, Ray }

/** A ray's angle: whole degrees. */
internal fun protractorRayAngle(raw: Float): Float = normalizedAngle(raw.roundToInt().toFloat())

/** The angle an arc stroke has swept from [startDeg] to [nowDeg], unwrapped across ±180 by [previous] (degrees, signed). */
internal fun sweptAngle(startDeg: Float, nowDeg: Float, previous: Float): Float {
    val raw = normalizedAngle(nowDeg - startDeg)
    // pick the turn count that keeps the sweep continuous with the last value
    var best = raw
    for (k in -2..2) {
        val v = raw + 360f * k
        if (abs(v - previous) < abs(best - previous)) best = v
    }
    return best
}
