package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import kotlinx.coroutines.delay

// DrawLayer's helpers that need only the document and what they are given (#730): drawing a
// draft, cutting drafts (page edge, pattern, eraser), the guides as shown, the lasso's turn room.

/** An eraser cut saved ink along [path] on [page]: the crash journal can't cut its strokes the same way, so it says so (#703). */
internal fun eraseTouchesJournal(doc: OpenDocument, page: Int, path: List<Offset>, radius: Float) {
    if (path.isEmpty()) return
    val area = androidx.compose.ui.geometry.Rect(
        path.minOf { it.x } - radius, path.minOf { it.y } - radius, path.maxOf { it.x } + radius, path.maxOf { it.y } + radius,
    )
    doc.inkJournal?.savedInkTouched(page, area)
}

internal fun DrawScope.drawDraftInk(doc: OpenDocument, s: InkStroke, lift: Offset, pageRect: androidx.compose.ui.geometry.Rect) {
    // Ink being drawn may run off its page (#75); only the part on the page shows.
    clipRect(pageRect.left, pageRect.top, pageRect.right, pageRect.bottom) {
        drawInkStroke(
            runs = s.paths.runs(s),
            color = Color(s.appearance.argb),
            opacity = s.appearance.opacity,
            highlighter = s.appearance.style == InkStyle.Highlighter,
            origin = doc.viewer.pageToScreen(s.page, lift.x, lift.y),
            scale = doc.viewer.pixelsPerPoint(s.page),
            pageHeightPt = doc.viewer.pages.getOrNull(s.page)?.height ?: 0f,
            pageBoundsOnScreen = pageRect,
            look = InkLook(doc.controller.nightMode, s.appearance.onDark),
        )
    }
}

/** One draft as the screen shows it: clipped to its page (#75), [lift]ed while the lasso moves it. */
internal fun DrawScope.drawDraft(doc: OpenDocument, s: InkStroke, lift: Offset = Offset.Zero) {
    val pageRect = doc.viewer.pageRectOnScreen(s.page)
    // a catalog shape being dragged (#693): its parts as they will be saved, hidden edges dashed
    if (s.shapeMode.isCatalog) {
        val page = doc.viewer.pages.getOrNull(s.page)
        if (page == null || s.points.size < 2) return
        val geometry = inkShapeGeometry(s.shapeMode, s.points.first(), s.points.last(), page.width, page.height) ?: return
        val parts = when (geometry) {
            is SnappedShape.Line -> catalogShapeParts(s.shapeMode, 0f, 0f, 0f, 0f, geometry.from, geometry.to)
            is SnappedShape.Rectangle -> catalogShapeParts(s.shapeMode, geometry.left, geometry.top, geometry.right, geometry.bottom)
            else -> null
        } ?: return
        val k = doc.viewer.pixelsPerPoint(s.page)
        val width = s.appearance.width.coerceAtLeast(0.5f)
        clipRect(pageRect.left, pageRect.top, pageRect.right, pageRect.bottom) {
            drawShapeParts(
                parts, { p -> doc.viewer.pageToScreen(s.page, p.x, p.y) }, Color(s.appearance.argb).copy(alpha = s.appearance.opacity),
                width * k, catalogHiddenDash(width).map { it.toFloat() * k },
            )
        }
        return
    }
    // a dashed or dotted line being written (not cut yet) is shown through its pattern (#682)
    val mask = if (s.group == null) inkPatternMask(s.pattern, inkStylePointWidths(listOf(1f), s.appearance.width, s.appearance.style).first()) else null
    if (mask != null && s.points.size >= 2) {
        drawIntoCanvas { it.saveLayer(pageRect, androidx.compose.ui.graphics.Paint()) }
        drawDraftInk(doc, s, lift, pageRect)
        val k = doc.viewer.pixelsPerPoint(s.page)
        val line = androidx.compose.ui.graphics.Path()
        s.points.forEachIndexed { i, p ->
            val q = doc.viewer.pageToScreen(s.page, p.x + lift.x, p.y + lift.y)
            if (i == 0) line.moveTo(q.x, q.y) else line.lineTo(q.x, q.y)
        }
        val widest = inkStylePointWidths(listOf(s.scales.maxOrNull() ?: 1f), s.appearance.width, s.appearance.style).first() * k
        drawPath(
            line, Color.Black,
            style = Stroke(
                // dashes cut square across the line wherever it is widest; dots are discs as wide as the line
                width = if (mask.round) widest * 1.05f else widest * 4f,
                cap = if (mask.round) androidx.compose.ui.graphics.StrokeCap.Round else androidx.compose.ui.graphics.StrokeCap.Butt, join = androidx.compose.ui.graphics.StrokeJoin.Round,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(mask.on * k, mask.off * k), mask.phase * k),
            ),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
        )
        drawIntoCanvas { it.restore() }
        return
    }
    drawDraftInk(doc, s, lift, pageRect)
}

/** Waits until [le]'s edit's own page is drawn (at the latest a few seconds after lift), then logs it and calls [gone]. */
internal suspend fun untilEditDrawn(doc: OpenDocument, le: LiveErase, gone: () -> Unit) {
    while (true) {
        delay(32)
        val gen = le.liftedGen ?: continue
        val now = android.os.SystemClock.uptimeMillis()
        val shown = (doc.images.latestBase(le.region.page)?.key?.gen ?: Long.MIN_VALUE) > gen
        if (shown || now - le.liftedAt > 5_000) {
            le.log(if (shown) "page_drawn" else "timed_out", now)
            gone()
            return
        }
    }
}

internal fun zoomFrameOnScreen(doc: OpenDocument): androidx.compose.ui.geometry.Rect? {
    val zb = doc.controller.zoomBox?.takeIf { doc.controller.zoomBoxOn } ?: return null
    val a = doc.viewer.pageToScreen(zb.page, zb.x, zb.y)
    val b = doc.viewer.pageToScreen(zb.page, zb.x + zb.width, zb.y + zb.height)
    return androidx.compose.ui.geometry.Rect(a, b)
}
internal fun activeRuler(doc: OpenDocument): Ruler? = doc.controller.ruler?.takeIf { doc.controller.rulerOn }?.let { it.copy(angleDeg = rulerDisplayAngle(it.angleDeg)) }
internal fun activeProtractor(doc: OpenDocument): Protractor? =
    doc.controller.protractor?.takeIf { doc.controller.protractorOn }?.let { it.copy(angleDeg = rulerDisplayAngle(it.angleDeg)) }

/**
 * The eraser over draft strokes on [page], from [a] to [b]: they are cut here, at once. [radius] and
 * [whole] ("Erase whole strokes") are the gesture's, fixed when it touched down.
 */
internal fun eraseDrafts(strokes: MutableList<InkStroke>, page: Int, a: Offset, b: Offset, radius: Float, whole: Boolean) {
    var i = 0
    while (i < strokes.size) {
        val s = strokes[i]
        if (s.page != page) { i++; continue }
        // far from the eraser: left without copying its points (#687); a line is at most 3 times its pen's width
        val widest = inkStylePointWidths(listOf(3f), s.appearance.width, s.appearance.style).first() / 2f
        val box = s.bounds()
        if (box == null || inkEraserMisses(box, a, b, radius + widest)) { i++; continue }
        val pts = s.points.toList()
        val half = inkStylePointWidths(s.scales.toList(), s.appearance.width, s.appearance.style).map { it / 2f }
        val runs = eraseInkRun(pts, s.scales.toList(), half, a, b, radius)
        if (runs == null) { i++; continue }
        // the whole-stroke eraser takes all of a stroke it touches (#521)
        if (whole) { strokes.removeAt(i); continue }
        // the same cut carries a pencil's densities (the points come out the same)
        val densityRuns = if (s.densities.size == pts.size) eraseInkRun(pts, s.densities.toList(), half, a, b, radius) else null
        strokes.removeAt(i)
        val pieces = runs.mapIndexed { k, run ->
            InkStroke(s.page, s.appearance, s.pressureSensitive, s.shapeMode, null, s.stylus).also {
                it.pattern = s.pattern
                it.group = s.group
                it.points.addAll(run.points)
                it.scales.addAll(run.scales)
                densityRuns?.getOrNull(k)?.scales?.takeIf { d -> d.size == run.points.size }?.let { d -> it.densities.addAll(d) }
            }
        }
        strokes.addAll(i, pieces)
        i += pieces.size
    }
}

/** [s] cut to its page; off-page parts are dropped and the pieces replace it in paint order. */
internal fun clipStrokeToPage(doc: OpenDocument, strokes: MutableList<InkStroke>, s: InkStroke): List<InkStroke> {
    val page = doc.viewer.pages.getOrNull(s.page) ?: return listOf(s)
    val points = s.points.toList()
    val runs = clipInkToPage(points, s.scales.toList(), page.width, page.height)
    if (runs.size == 1 && runs[0].points == points) return listOf(s)
    // the same cut carries a pencil's densities (the points come out the same)
    val densityRuns = if (s.densities.size == points.size) clipInkToPage(points, s.densities.toList(), page.width, page.height) else null
    val at = strokes.indexOf(s).takeIf { it >= 0 } ?: strokes.size
    strokes.remove(s)
    val pieces = runs.mapIndexed { i, run ->
        InkStroke(s.page, s.appearance, s.pressureSensitive, s.shapeMode, null, s.stylus).also {
            it.pattern = s.pattern
            it.points.addAll(run.points)
            it.scales.addAll(run.pressures)
            densityRuns?.getOrNull(i)?.pressures?.takeIf { d -> d.size == run.points.size }?.let { d -> it.densities.addAll(d) }
        }
    }
    strokes.addAll(at.coerceAtMost(strokes.size), pieces)
    return pieces
}

/**
 * A dashed or dotted line (#682), cut on lift into its pieces, which take its place in the
 * drafts and share one group (one annotation when saved). A solid stroke, or one shorter than a
 * dash, is itself.
 */
internal fun layPattern(strokes: MutableList<InkStroke>, lines: List<InkStroke>): List<InkStroke> = lines.flatMap { s ->
    if (s.pattern == InkPattern.Solid || s.shapeMode != InkShapeMode.Freehand) return@flatMap listOf(s)
    val points = s.points.toList()
    val width = inkStylePointWidths(listOf(1f), s.appearance.width, s.appearance.style).first()
    val runs = inkPatternRuns(points, s.scales.toList(), s.pattern, width)
    if (runs.size == 1) {
        s.pattern = InkPattern.Solid
        return@flatMap listOf(s)
    }
    // the same cut carries a pencil's densities (the points come out the same)
    val densityRuns = if (s.densities.size == points.size) inkPatternRuns(points, s.densities.toList(), s.pattern, width) else null
    val group = Any()
    val pieces = runs.mapIndexed { i, run ->
        InkStroke(s.page, s.appearance, s.pressureSensitive, s.shapeMode, null, s.stylus).also {
            it.pattern = s.pattern
            it.group = group
            it.points.addAll(run.points)
            it.scales.addAll(run.pressures)
            densityRuns?.getOrNull(i)?.pressures?.takeIf { d -> d.size == run.points.size }?.let { d -> it.densities.addAll(d) }
        }
    }
    val at = strokes.indexOf(s).takeIf { it >= 0 } ?: strokes.size
    strokes.remove(s)
    strokes.addAll(at.coerceAtMost(strokes.size), pieces)
    PenDiag.pattern(s.pattern.key, pieces.size, points.size)
    pieces
}

/**
 * What the lasso's turn handle has to keep clear of on [page] (#709), in its points: the part of
 * the page shown under the top bar and the tab strip and above the toolbar and the zoom pad, and
 * the selection's buttons. Null before the layer is laid out (the handle is then above the box).
 * [layerSize] is the Draw layer's size, [padHeightPx] the zoom pad's height, [lassoMenuBounds] where the
 * selection's buttons are in the layer, and [pxPerDp] the screen's pixels per dp.
 */
internal fun lassoTurnRoom(
    doc: OpenDocument, page: Int, layerSize: androidx.compose.ui.geometry.Size, padHeightPx: Float,
    lassoMenuBounds: androidx.compose.ui.geometry.Rect?, pxPerDp: Float,
): LassoTurnRoom? {
    val size = doc.viewer.pages.getOrNull(page) ?: return null
    val perPt = doc.viewer.pixelsPerPoint(page).takeIf { it > 0f } ?: return null
    if (layerSize.width <= 0f || layerSize.height <= 0f) return null
    val covered = maxOf(doc.viewer.padBottom, if (doc.controller.zoomBoxOn) padHeightPx else 0f)
    val a = doc.viewer.screenToPageUnclamped(page, Offset(0f, doc.viewer.padTop)) ?: return null
    val b = doc.viewer.screenToPageUnclamped(page, Offset(layerSize.width, layerSize.height - covered)) ?: return null
    val menu = lassoMenuBounds?.let { m ->
        val p = doc.viewer.screenToPageUnclamped(page, m.topLeft)
        val q = doc.viewer.screenToPageUnclamped(page, m.bottomRight)
        if (p != null && q != null) androidx.compose.ui.geometry.Rect(p.x, p.y, q.x, q.y) else null
    }
    val ptPerDp = pxPerDp / perPt
    return LassoTurnRoom(
        androidx.compose.ui.geometry.Rect(maxOf(0f, a.x), maxOf(0f, a.y), minOf(size.width, b.x), minOf(size.height, b.y)),
        menu, LASSO_KNOB_DP * ptPerDp, LASSO_HANDLE_SLOP_DP * ptPerDp,
    )
}
