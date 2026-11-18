package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/*
 * More shapes (#693), each from one drag: flat ones saved as native, editable annotations (a
 * polygon, a line with two arrowheads, connected lines for axes), and solids drawn in an oblique
 * view as two drawings, their visible edges solid and their hidden edges dashed (`dash`), added as
 * one undo step (`comments_add`). Page points, y pointing down.
 */

/** One part of a shape: a path (closed when [closed]), and whether it is a hidden edge, drawn dashed. */
internal data class ShapePart(val points: List<Offset>, val closed: Boolean = false, val hidden: Boolean = false)

/** The catalog's shapes, flat then solid, in the order the More shapes sheet shows them. */
internal val CATALOG_FLAT_SHAPES = listOf(
    InkShapeMode.Triangle, InkShapeMode.RightTriangle, InkShapeMode.Diamond, InkShapeMode.Pentagon,
    InkShapeMode.Hexagon, InkShapeMode.DoubleArrow, InkShapeMode.Axes,
)
internal val CATALOG_SOLIDS = listOf(
    InkShapeMode.Cube, InkShapeMode.Prism, InkShapeMode.Pyramid, InkShapeMode.Cylinder, InkShapeMode.Cone,
    InkShapeMode.Sphere, InkShapeMode.Hemisphere, InkShapeMode.Frustum, InkShapeMode.Tube,
)

/** Whether [this] is one of the catalog's shapes (#693), drawn from [catalogShapeParts]. */
internal val InkShapeMode.isCatalog: Boolean get() = this in CATALOG_FLAT_SHAPES || this in CATALOG_SOLIDS

/** A regular polygon of [n] corners, its first one at [startDeg], stretched to fill the box. */
private fun regular(n: Int, startDeg: Double, l: Float, t: Float, r: Float, b: Float): List<Offset> {
    val unit = List(n) { k -> val a = Math.toRadians(startDeg + 360.0 * k / n); cos(a) to sin(a) }
    val (x0, x1) = unit.minOf { it.first } to unit.maxOf { it.first }
    val (y0, y1) = unit.minOf { it.second } to unit.maxOf { it.second }
    return unit.map { (x, y) -> Offset(l + ((x - x0) / (x1 - x0) * (r - l)).toFloat(), t + ((y - y0) / (y1 - y0) * (b - t)).toFloat()) }
}

/**
 * Points of the ellipse at ([cx], [cy]) with radii [rx], [ry] from [fromDeg] to [toDeg] (0 = right,
 * 90 = down): at least [steps] pieces, and enough that none is longer than [CATALOG_INK_STEP] (#701).
 */
private fun arc(cx: Float, cy: Float, rx: Float, ry: Float, fromDeg: Double, toDeg: Double, steps: Int = 24): List<Offset> {
    val length = Math.toRadians(abs(toDeg - fromDeg)) * maxOf(abs(rx), abs(ry))
    val n = maxOf(steps, ceil(length / CATALOG_INK_STEP).toInt())
    return List(n + 1) { k ->
        val a = Math.toRadians(fromDeg + (toDeg - fromDeg) * k / n)
        Offset(cx + rx * cos(a).toFloat(), cy + ry * sin(a).toFloat())
    }
}

/**
 * The longest piece, in page points, of a solid's saved drawing (#701). Saved ink is smoothed through
 * its points as handwriting is, so an edge held only by its two corners bowed out and its corners
 * rounded off; with a point every few page points the smoothing stays on the edge, and a corner rounds
 * only within about half of this, under a pen's own width.
 */
internal const val CATALOG_INK_STEP = 3f

/** [points] with points added along each piece longer than [step], every one of [points] kept (#701). */
internal fun densified(points: List<Offset>, step: Float = CATALOG_INK_STEP): List<Offset> {
    if (points.size < 2) return points
    val out = ArrayList<Offset>(points.size * 4)
    out += points.first()
    for (i in 1 until points.size) {
        val a = points[i - 1]
        val b = points[i]
        val n = maxOf(1, ceil((b - a).getDistance() / step).toInt())
        for (k in 1 until n) out += a + (b - a) * (k.toFloat() / n)
        out += b
    }
    return out
}

/** An ellipse lying flat: its near half (the lower one as seen) solid, its far half [farHidden] or solid. */
private fun flatEllipse(cx: Float, cy: Float, rx: Float, ry: Float, farHidden: Boolean): List<ShapePart> = listOf(
    ShapePart(arc(cx, cy, rx, ry, 0.0, 180.0)),
    ShapePart(arc(cx, cy, rx, ry, 180.0, 360.0), hidden = farHidden),
)

/**
 * The parts of catalog shape [mode] in the box [l]..[r] × [t]..[b] (page points), or, for the double
 * arrow, along [from]–[to]; null for a box too small to draw (under 2 points either way).
 */
internal fun catalogShapeParts(mode: InkShapeMode, l: Float, t: Float, r: Float, b: Float, from: Offset = Offset(l, b), to: Offset = Offset(r, t)): List<ShapePart>? {
    if (!mode.isCatalog || !(l.isFinite() && t.isFinite() && r.isFinite() && b.isFinite())) return null
    val w = r - l
    val h = b - t
    if (mode == InkShapeMode.DoubleArrow) return if ((to - from).getDistance() >= 2f) listOf(ShapePart(listOf(from, to))) else null
    if (w < 2f || h < 2f) return null
    val cx = (l + r) / 2f
    val cy = (t + b) / 2f
    // how far back a solid reaches (up and to the right), and how flat its ellipses lie
    val d = 0.3f * minOf(w, h)
    val ry = minOf(0.15f * h, 0.3f * w)
    return when (mode) {
        InkShapeMode.Triangle -> listOf(ShapePart(listOf(Offset(cx, t), Offset(r, b), Offset(l, b)), closed = true))
        InkShapeMode.RightTriangle -> listOf(ShapePart(listOf(Offset(l, t), Offset(l, b), Offset(r, b)), closed = true))
        InkShapeMode.Diamond -> listOf(ShapePart(listOf(Offset(cx, t), Offset(r, cy), Offset(cx, b), Offset(l, cy)), closed = true))
        InkShapeMode.Pentagon -> listOf(ShapePart(regular(5, -90.0, l, t, r, b), closed = true))
        InkShapeMode.Hexagon -> listOf(ShapePart(regular(6, 0.0, l, t, r, b), closed = true))
        // two axes from the corner: up and to the right, an arrowhead at each end
        InkShapeMode.Axes -> listOf(ShapePart(listOf(Offset(l, t), Offset(l, b), Offset(r, b))))
        InkShapeMode.Cube -> {
            val (a, bb, c, e) = listOf(Offset(l, t + d), Offset(r - d, t + d), Offset(r - d, b), Offset(l, b))
            val back = Offset(d, -d)
            listOf(
                ShapePart(listOf(a, bb, c, e), closed = true),
                ShapePart(listOf(a, a + back, bb + back, c + back, c)),
                ShapePart(listOf(bb, bb + back)),
                // the corner behind, at the back on the left and below
                ShapePart(listOf(a + back, e + back, c + back), hidden = true),
                ShapePart(listOf(e, e + back), hidden = true),
            )
        }
        InkShapeMode.Prism -> {
            val back = Offset(d, -d)
            val fl = Offset(l, b)
            val fr = Offset(r - d, b)
            val apex = Offset(l + (w - d) / 2f, t + d)
            listOf(
                ShapePart(listOf(fl, fr, apex), closed = true),
                ShapePart(listOf(apex, apex + back, fr + back, fr)),
                ShapePart(listOf(apex + back, fl + back, fr + back), hidden = true),
                ShapePart(listOf(fl, fl + back), hidden = true),
            )
        }
        InkShapeMode.Pyramid -> {
            val fl = Offset(l, b)
            val fr = Offset(r - d, b)
            val br = Offset(r, b - d)
            val bl = Offset(l + d, b - d)
            val apex = Offset(cx, t)
            listOf(
                ShapePart(listOf(fl, fr, br)),
                ShapePart(listOf(fl, apex, fr)),
                ShapePart(listOf(apex, br)),
                ShapePart(listOf(br, bl, fl), hidden = true),
                ShapePart(listOf(apex, bl), hidden = true),
            )
        }
        InkShapeMode.Cylinder -> listOf(
            ShapePart(arc(cx, t + ry, w / 2f, ry, 0.0, 360.0, 48), closed = true),
            ShapePart(listOf(Offset(l, t + ry), Offset(l, b - ry))),
            ShapePart(listOf(Offset(r, t + ry), Offset(r, b - ry))),
        ) + flatEllipse(cx, b - ry, w / 2f, ry, farHidden = true)
        InkShapeMode.Cone -> listOf(
            ShapePart(listOf(Offset(l, b - ry), Offset(cx, t), Offset(r, b - ry))),
        ) + flatEllipse(cx, b - ry, w / 2f, ry, farHidden = true)
        InkShapeMode.Sphere -> listOf(
            ShapePart(arc(cx, cy, w / 2f, h / 2f, 0.0, 360.0, 64), closed = true),
        ) + flatEllipse(cx, cy, w / 2f, minOf(0.2f * h, ry * 1.4f), farHidden = true)
        InkShapeMode.Hemisphere -> listOf(
            // the dome over its flat base, which it hides at the back
            ShapePart(arc(cx, b - ry, w / 2f, h - ry, 180.0, 360.0, 32)),
        ) + flatEllipse(cx, b - ry, w / 2f, ry, farHidden = true)
        InkShapeMode.Frustum -> {
            val top = 0.3f * w
            listOf(
                ShapePart(arc(cx, t + ry * 0.6f, top, ry * 0.6f, 0.0, 360.0, 40), closed = true),
                ShapePart(listOf(Offset(cx - top, t + ry * 0.6f), Offset(l, b - ry))),
                ShapePart(listOf(Offset(cx + top, t + ry * 0.6f), Offset(r, b - ry))),
            ) + flatEllipse(cx, b - ry, w / 2f, ry, farHidden = true)
        }
        InkShapeMode.Tube -> listOf(
            ShapePart(arc(cx, t + ry, w / 2f, ry, 0.0, 360.0, 48), closed = true),
            // the hole at the top
            ShapePart(arc(cx, t + ry, 0.3f * w, 0.6f * ry, 0.0, 360.0, 40), closed = true),
            ShapePart(listOf(Offset(l, t + ry), Offset(l, b - ry))),
            ShapePart(listOf(Offset(r, t + ry), Offset(r, b - ry))),
        ) + flatEllipse(cx, b - ry, w / 2f, ry, farHidden = true)
        else -> null
    }
}

/** A hidden edge's dash: three widths on, two and a half off, at least 2 points each. */
internal fun catalogHiddenDash(width: Float): List<Double> = listOf(maxOf(3f * width, 2f).toDouble(), maxOf(2.5f * width, 2f).toDouble())

private fun Offset.json() = listOf(x.toDouble(), y.toDouble())

/**
 * The `comment_add` arguments of catalog shape [mode] (#693) drawn in [shape] (the dragged box, or the
 * double arrow's line), without `doc`: one command for a flat shape, saved as an editable annotation,
 * and for a solid one drawing of its visible edges and one, dashed, of its hidden edges, which go
 * to the engine together (`comments_add`). Null for a shape too small to draw.
 */
internal fun catalogShapeCommands(
    mode: InkShapeMode, shape: SnappedShape, page: Int, color: String, opacity: Float, width: Float, author: String?,
): List<Map<String, Any?>>? {
    val parts = when (shape) {
        is SnappedShape.Line -> catalogShapeParts(mode, minOf(shape.from.x, shape.to.x), minOf(shape.from.y, shape.to.y), maxOf(shape.from.x, shape.to.x), maxOf(shape.from.y, shape.to.y), shape.from, shape.to)
        is SnappedShape.Rectangle -> catalogShapeParts(mode, shape.left, shape.top, shape.right, shape.bottom)
        else -> null
    } ?: return null
    val common = mapOf("page" to page + 1, "color" to color, "opacity" to opacity.toDouble(), "width" to width.toDouble(), "author" to author)
    val arrows = listOf("OpenArrow", "OpenArrow")
    return when (mode) {
        InkShapeMode.DoubleArrow -> listOf(common + mapOf("type" to "line", "from" to parts[0].points.first().json(), "to" to parts[0].points.last().json(), "endings" to arrows))
        InkShapeMode.Axes -> listOf(common + mapOf("type" to "polyline", "points" to parts[0].points.map { it.json() }, "endings" to arrows))
        in CATALOG_FLAT_SHAPES -> listOf(common + mapOf("type" to "polygon", "points" to parts[0].points.map { it.json() }))
        else -> {
            // each edge with a point every few page points, so the saved drawing keeps it straight (#701)
            fun ink(of: List<ShapePart>) = common + mapOf("type" to "ink", "strokes" to of.map { p -> densified(if (p.closed) p.points + p.points.first() else p.points).map { it.json() } })
            val (hidden, visible) = parts.partition { it.hidden }
            listOfNotNull(
                visible.takeIf { it.isNotEmpty() }?.let { ink(it) },
                hidden.takeIf { it.isNotEmpty() }?.let { ink(it) + ("dash" to catalogHiddenDash(width)) },
            )
        }
    }
}

/**
 * Draw [parts] (page points) mapped to the screen by [toScreen], [widthPx] wide, hidden edges dashed
 * at [dashPx]: the shape while it is dragged, and the More shapes sheet's pictures.
 */
internal fun DrawScope.drawShapeParts(parts: List<ShapePart>, toScreen: (Offset) -> Offset, color: Color, widthPx: Float, dashPx: List<Float>) {
    for (part in parts) {
        if (part.points.size < 2) continue
        val path = Path()
        part.points.forEachIndexed { i, p -> val q = toScreen(p); if (i == 0) path.moveTo(q.x, q.y) else path.lineTo(q.x, q.y) }
        if (part.closed) path.close()
        drawPath(
            path, color,
            style = Stroke(
                width = widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round,
                pathEffect = if (part.hidden) PathEffect.dashPathEffect(dashPx.toFloatArray(), 0f) else null,
            ),
        )
    }
}

/** The More shapes sheet's picture of [mode], filling the canvas. */
internal fun DrawScope.drawCatalogShapeIcon(mode: InkShapeMode, color: Color) {
    val pad = size.minDimension * 0.1f
    val parts = catalogShapeParts(mode, pad, pad, size.width - pad, size.height - pad, Offset(pad, size.height - pad), Offset(size.width - pad, pad)) ?: return
    val w = size.minDimension / 14f
    drawShapeParts(parts, { it }, color, w, listOf(w * 1.6f, w * 1.4f))
    // the arrowheads the double arrow and the axes are saved with
    if (mode == InkShapeMode.DoubleArrow || mode == InkShapeMode.Axes) {
        val p = parts[0].points
        for ((tip, from) in listOf(p.first() to p[1], p.last() to p[p.size - 2])) {
            val head = arrowPreview(from, tip).drop(2)
            drawShapeParts(listOf(ShapePart(listOf(head[0], tip, head[2]))), { it }, color, w, emptyList())
        }
    }
}
