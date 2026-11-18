package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.ui.geometry.Offset
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The lasso selection's handles (#689): [Resize] at its bottom-right corner scales it about the
 * opposite corner, keeping its proportions; [Turn], above its middle, turns it about its centre.
 * Page points throughout, y pointing down, so a positive angle turns clockwise as seen (the
 * engine's `comments_edit_many` `transform` takes it the same way).
 */
internal enum class LassoHandle { Resize, Turn }

/** How far above the selection the turn handle sits, in dp on the glass. */
internal const val LASSO_TURN_HANDLE_DP = 28f

/** How near a handle a touch counts as on it, in dp on the glass. */
internal const val LASSO_HANDLE_SLOP_DP = 18f

/** The smallest and largest the selection may be scaled in one drag. */
internal const val LASSO_MIN_SCALE = 0.05f
internal const val LASSO_MAX_SCALE = 20f

/** A turn this near a quarter turn (in degrees) is that quarter turn. */
internal const val LASSO_TURN_SNAP_DEG = 4f

/** Where [handle] is for a selection in [box], with the turn handle [turnOffset] page points above it. */
internal fun lassoHandlePosition(box: InkLassoBounds, handle: LassoHandle, turnOffset: Float): Offset = when (handle) {
    LassoHandle.Resize -> Offset(box.right, box.bottom)
    LassoHandle.Turn -> Offset((box.left + box.right) / 2f, box.top - turnOffset)
}

/** The handle [p] is on (within [slop] page points), the turn handle first; null when on neither. */
internal fun lassoHandleAt(box: InkLassoBounds, p: Offset, slop: Float, turnOffset: Float): LassoHandle? {
    if (!(p.x.isFinite() && p.y.isFinite())) return null
    return LassoHandle.entries.firstOrNull { (lassoHandlePosition(box, it, turnOffset) - p).getDistance() <= slop }
}

/** The point the handle turns or scales about: the opposite corner for a resize, the centre for a turn. */
internal fun lassoPivot(box: InkLassoBounds, handle: LassoHandle): Offset = when (handle) {
    LassoHandle.Resize -> Offset(box.left, box.top)
    LassoHandle.Turn -> Offset((box.left + box.right) / 2f, (box.top + box.bottom) / 2f)
}

/**
 * The scale of a resize dragged from [from] to [to] about [pivot]: how far along the corner's own
 * direction the handle went, so the selection keeps its proportions, within
 * [LASSO_MIN_SCALE]..[LASSO_MAX_SCALE]; 1 for a corner on the pivot.
 */
internal fun lassoResizeScale(pivot: Offset, from: Offset, to: Offset): Float {
    val d = from - pivot
    val len2 = d.x * d.x + d.y * d.y
    if (!(len2 > 1e-6f) || !(to.x.isFinite() && to.y.isFinite())) return 1f
    val along = ((to.x - pivot.x) * d.x + (to.y - pivot.y) * d.y) / len2
    return along.coerceIn(LASSO_MIN_SCALE, LASSO_MAX_SCALE)
}

/**
 * The angle (degrees, clockwise as seen) of a turn dragged from [from] to [to] about [pivot], in
 * -180..180, snapped to a quarter turn within [LASSO_TURN_SNAP_DEG]; 0 for a drag on the pivot.
 */
internal fun lassoTurnAngle(pivot: Offset, from: Offset, to: Offset): Float {
    val a = from - pivot
    val b = to - pivot
    if (a.getDistance() < 1e-3f || b.getDistance() < 1e-3f || !(b.x.isFinite() && b.y.isFinite())) return 0f
    var deg = Math.toDegrees((atan2(b.y, b.x) - atan2(a.y, a.x)).toDouble()).toFloat()
    while (deg > 180f) deg -= 360f
    while (deg <= -180f) deg += 360f
    for (q in listOf(-180f, -90f, 0f, 90f, 180f)) if (kotlin.math.abs(deg - q) <= LASSO_TURN_SNAP_DEG) return if (q == -180f) 180f else q
    return deg
}

/** [p] turned by [angleDeg] (clockwise as seen, page y down) and scaled by [scale] about [pivot]. */
internal fun lassoTransformPoint(p: Offset, pivot: Offset, scale: Float, angleDeg: Float): Offset {
    val r = Math.toRadians(angleDeg.toDouble())
    val c = cos(r).toFloat()
    val s = sin(r).toFloat()
    val x = p.x - pivot.x
    val y = p.y - pivot.y
    return Offset(pivot.x + scale * (x * c - y * s), pivot.y + scale * (x * s + y * c))
}

/** Saved comments the engine turns as well as scales; the others (rectangles, ovals, text, stamps) only scale. */
internal val LASSO_TURNABLE_SUBTYPES = setOf("Ink", "Line", "Polygon", "PolyLine")

/**
 * [s] turned and scaled (#689): a new draft with its points moved and its pen's width scaled the
 * same, so shrunk handwriting is not drawn bolder; its widths per point and densities stay.
 */
internal fun lassoTransformedDraft(s: InkStroke, pivot: Offset, scale: Float, angleDeg: Float): InkStroke {
    val width = (s.appearance.width * scale).coerceIn(0.1f, 30f)
    return InkStroke(s.page, s.appearance.copy(width = width), s.pressureSensitive, s.shapeMode, null, s.stylus).also {
        it.points.addAll(s.points.map { p -> lassoTransformPoint(p, pivot, scale, angleDeg) })
        it.scales.addAll(s.scales)
        it.densities.addAll(s.densities)
    }
}
