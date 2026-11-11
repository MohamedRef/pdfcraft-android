package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The Draw ruler (#150), in screen pixels: a band of [halfWidth] either side of a line through
 * [center] at [angleDeg]. It lives only in the viewer (never in the PDF, exports or prints).
 */
internal data class Ruler(val center: Offset, val angleDeg: Float, val halfWidth: Float, val halfLength: Float) {
    private val rad get() = Math.toRadians(angleDeg.toDouble())
    /** Unit vector along the ruler. */
    val along: Offset get() = Offset(cos(rad).toFloat(), sin(rad).toFloat())
    /** Unit normal (towards edge 1). */
    val normal: Offset get() = Offset(-sin(rad).toFloat(), cos(rad).toFloat())

    /** A point on edge 0 (-normal side) or edge 1 (+normal side). */
    fun edgePoint(edge: Int): Offset = center + normal * (if (edge == 0) -halfWidth else halfWidth)

    private fun local(p: Offset): Offset {
        val d = p - center
        return Offset(d.x * along.x + d.y * along.y, d.x * normal.x + d.y * normal.y)
    }

    fun contains(p: Offset): Boolean {
        val l = local(p)
        return abs(l.x) <= halfLength && abs(l.y) <= halfWidth
    }

    /**
     * The edge a stroke starting at [p] should follow: the nearest edge when [p] is outside the
     * band (or on it) within [reach] of that edge and alongside the ruler; null otherwise.
     */
    fun snapEdge(p: Offset, reach: Float): Int? {
        val l = local(p)
        if (abs(l.x) > halfLength) return null
        val d0 = abs(l.y + halfWidth)
        val d1 = abs(l.y - halfWidth)
        val edge = if (d0 <= d1) 0 else 1
        return edge.takeIf { minOf(d0, d1) <= reach }
    }

    /** [p] moved perpendicularly onto [edge], offset outwards by [outset] (half the stroke width). */
    fun project(p: Offset, edge: Int, outset: Float = 0f): Offset {
        val l = local(p)
        val y = if (edge == 0) -halfWidth - outset else halfWidth + outset
        return center + along * l.x + normal * y
    }

    /** Moved by [pan] and turned by [rotationDeg] about [pivot]; the angle snaps to 15° steps. */
    fun transformed(pan: Offset, rotationDeg: Float, pivot: Offset): Ruler {
        val r = Math.toRadians(rotationDeg.toDouble())
        val d = center - pivot
        val turned = Offset(
            (d.x * cos(r) - d.y * sin(r)).toFloat(),
            (d.x * sin(r) + d.y * cos(r)).toFloat(),
        ) + pivot
        return copy(center = turned + pan, angleDeg = normalizedAngle(angleDeg + rotationDeg))
    }
}

/** Angle in (-180, 180]. */
internal fun normalizedAngle(a: Float): Float {
    var v = a % 360f
    if (v <= -180f) v += 360f
    if (v > 180f) v -= 360f
    return v
}

/** The angle shown and used for drawing: within [tolerance]° of a 15° step it snaps to it. */
internal fun rulerDisplayAngle(raw: Float, tolerance: Float = 2f): Float {
    val step = (raw / 15f).roundToInt() * 15f
    return if (abs(raw - step) <= tolerance) normalizedAngle(step) else raw
}
