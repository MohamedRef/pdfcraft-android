package io.github.artcraftandroid.pdfcraft.ui.viewer


import androidx.compose.foundation.selection.selectable
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.collect

import io.github.artcraftandroid.pdfcraft.engine.userMessage

import io.github.artcraftandroid.pdfcraft.engine.Engine

import io.github.artcraftandroid.pdfcraft.core.MobileException

import androidx.lifecycle.viewModelScope

import androidx.compose.runtime.snapshotFlow

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.artcraftandroid.pdfcraft.ui.LocalUndo
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.drawText
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.text
import io.github.artcraftandroid.pdfcraft.core.TextRange
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import io.github.artcraftandroid.pdfcraft.ui.PdfSheet
import io.github.artcraftandroid.pdfcraft.ui.SheetHost
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import kotlinx.coroutines.Job
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.minimumInteractiveComponentSize

// Moved from Tools.kt as is (#730): the ruler and protractor drawing, and the zoom writing pad.

/** The ruler band with ticks every 5 dp (longer every 25 dp) and its angle readout (#150). */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRuler(r: Ruler, measurer: androidx.compose.ui.text.TextMeasurer, density: Float) {
    rotate(r.angleDeg, pivot = r.center) {
        val top = r.center.y - r.halfWidth
        val left = r.center.x - r.halfLength
        drawRect(Color(0xFFF4F1E8).copy(alpha = 0.82f), Offset(left, top), androidx.compose.ui.geometry.Size(r.halfLength * 2f, r.halfWidth * 2f))
        drawRect(Color(0xFF5A6270), Offset(left, top), androidx.compose.ui.geometry.Size(r.halfLength * 2f, r.halfWidth * 2f), style = Stroke(width = 1.2f * density))
        val step = 5f * density
        var x = r.center.x - (r.halfLength / step).toInt() * step
        var i = -(r.halfLength / step).toInt()
        while (x <= r.center.x + r.halfLength) {
            val len = (if (i % 5 == 0) 12f else 6f) * density
            drawLine(Color(0xFF5A6270), Offset(x, top), Offset(x, top + len), strokeWidth = 1f * density)
            drawLine(Color(0xFF5A6270), Offset(x, r.center.y + r.halfWidth), Offset(x, r.center.y + r.halfWidth - len), strokeWidth = 1f * density)
            x += step
            i++
        }
        val label = measurer.measure("${"%.0f".format(java.util.Locale.ROOT, kotlin.math.abs(r.angleDeg))}°",
            style = androidx.compose.ui.text.TextStyle(color = Color(0xFF1F2430), fontSize = androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp)))
        drawText(label, topLeft = Offset(r.center.x - label.size.width / 2f, r.center.y - label.size.height / 2f))
    }
}

/** The protractor's half disc with 1°, 5° and 10° ticks, labels every 30° and a centre mark (#150). */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawProtractor(pr: Protractor, measurer: androidx.compose.ui.text.TextMeasurer, density: Float) {
    val ink = Color(0xFF5A6270)
    rotate(pr.angleDeg, pivot = pr.center) {
        // drawn at angle 0 (half disc above the baseline), then turned
        val r = pr.radius
        val tl = Offset(pr.center.x - r, pr.center.y - r)
        val sz = androidx.compose.ui.geometry.Size(2 * r, 2 * r)
        drawArc(Color(0xFFF4F1E8).copy(alpha = 0.82f), 180f, 180f, true, tl, sz)
        drawArc(ink, 180f, 180f, true, tl, sz, style = Stroke(width = 1.2f * density))
        for (deg in 0..180) {
            val len = when { deg % 10 == 0 -> 14f; deg % 5 == 0 -> 9f; else -> 5f } * density
            val a = Math.toRadians(deg.toDouble())
            val dir = Offset(kotlin.math.cos(a).toFloat(), -kotlin.math.sin(a).toFloat())
            drawLine(ink, pr.center + dir * r, pr.center + dir * (r - len), strokeWidth = (if (deg % 10 == 0) 1.2f else 0.8f) * density)
            if (deg % 30 == 0) {
                val label = measurer.measure("$deg",
                    style = androidx.compose.ui.text.TextStyle(color = Color(0xFF1F2430), fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp)))
                val at = pr.center + dir * (r - 26f * density)
                drawText(label, topLeft = Offset(at.x - label.size.width / 2f, at.y - label.size.height / 2f))
            }
        }
        drawCircle(ink, radius = 4f * density, center = pr.center, style = Stroke(width = 1.2f * density))
        drawLine(ink, pr.center - Offset(8f * density, 0f), pr.center + Offset(8f * density, 0f), strokeWidth = 1f * density)
    }
}

/** Height of the zoom writing pad (#130). */
internal val ZOOM_PAD_HEIGHT = 200.dp

/**
 * The docked zoom writing pad (#130): the zoom box's part of the page, magnified, with the ink
 * on it. Writing here adds ink at the matching page points; lifting the pen in the right-hand
 * zone moves the box along the line, then to the next line and page.
 */
@Composable
internal fun ZoomWritingPad(
    doc: OpenDocument,
    prefs: ToolPrefs,
    modifier: Modifier,
    padSize: androidx.compose.ui.geometry.Size,
    drafts: List<InkStroke>,
    /** [dpPerPt]: how many dp one page point takes on the pad, where the pen moves. */
    newStroke: (page: Int, pressure: Float, isStylus: Boolean, at: Offset, dpPerPt: Float) -> InkStroke,
    finishStroke: (InkStroke) -> Unit,
) {
    val c = pdfColors
    val ctl = doc.controller
    // A sharp background for the box (#130): the box's region rendered at the pad's magnification,
    // a short moment after the box settles; until then the whole-page image stands in.
    var sharp by remember(doc) { mutableStateOf<Pair<PadRender, androidx.compose.ui.graphics.ImageBitmap>?>(null) }
    var sharpGen by remember(doc) { mutableStateOf<Long?>(null) }
    // the stroke being written on the pad, for its tail to the pen
    var writing by remember(doc) { mutableStateOf<InkStroke?>(null) }
    val zbNow = ctl.zoomBox
    val wanted = zbNow?.let { zb -> doc.viewer.pages.getOrNull(zb.page)?.let { zb.padRender(padSize, it.width, it.height) } }
    val gen = zbNow?.let { doc.pageGenerations.of(it.page) }
    LaunchedEffect(wanted, gen) {
        if (wanted == null || sharp?.first == wanted && sharpGen == gen) return@LaunchedEffect
        kotlinx.coroutines.delay(120)
        val started = android.os.SystemClock.uptimeMillis()
        val img = doc.images.renderRegion(wanted.page, wanted.scale, io.github.artcraftandroid.pdfcraft.core.TileRect(wanted.x.toUInt(), wanted.y.toUInt(), wanted.w.toUInt(), wanted.h.toUInt()))
        PenDiag.zoomBoxRender(android.os.SystemClock.uptimeMillis() - started, wanted.scale, wanted.w, wanted.h, img != null)
        if (img != null) { sharp = wanted to img; sharpGen = gen }
    }
    Box(
        // the pad's paper and page are shown as the page above it is: inverted in Night Mode (#610)
        modifier.background(if (ctl.nightMode) Color.Black else Color.White).drawBehind {
            drawLine(c.divider, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 2f)
        }.pointerInput(doc) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                val stylus = down.type == PointerType.Stylus
                if (down.type == PointerType.Eraser || (!stylus && prefs.stylusOnly)) return@awaitEachGesture
                val zb = ctl.zoomBox ?: return@awaitEachGesture
                val pad = androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat())
                inkSampleTimeMs = down.uptimeMillis
                val s = newStroke(zb.page, down.pressure, stylus, zb.toPage(down.position, pad), zb.magnification(pad) / density)
                writing = s
                var lastPressure = down.pressure
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    change.historical.forEach { h ->
                        s.add(zb.toPage(h.position, pad), h.uptimeMillis, lastPressure)
                    }
                    if (change.positionChanged()) {
                        lastPressure = change.pressure.takeIf { it.isFinite() && it > 0f } ?: lastPressure
                        s.add(zb.toPage(change.position, pad), change.uptimeMillis, lastPressure)
                    }
                    event.changes.forEach { it.consume() }
                    if (!change.pressed) break
                }
                finishStroke(s)
                writing = null
                val last = s.points.lastOrNull() ?: return@awaitEachGesture
                val page = doc.viewer.pages.getOrNull(zb.page) ?: return@awaitEachGesture
                if (ctl.zoomBox == zb && zb.inAdvanceZone(last.x)) {
                    val next = zb.advanced(page.width, page.height, doc.viewer.pages.size)
                    PenDiag.zoomBoxAdvance(newLine = next.y != zb.y || next.page != zb.page, newPage = next.page != zb.page)
                    ctl.zoomBox = next
                    if (next.page != zb.page) doc.viewer.goToPage(next.page, next.y)
                }
            }
        },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val zb = ctl.zoomBox ?: return@Canvas
            val page = doc.viewer.pages.getOrNull(zb.page) ?: return@Canvas
            val m = zb.magnification(size)
            val origin = zb.pageOriginInPad(size)
            val pageRect = androidx.compose.ui.geometry.Rect(origin, androidx.compose.ui.geometry.Size(page.width * m, page.height * m))
            clipRect {
                // The rendered page behind the box, from the page's latest whole-page image.
                doc.images.latestBase(zb.page)?.let { img ->
                    drawImage(
                        img.bitmap,
                        dstOffset = androidx.compose.ui.unit.IntOffset(pageRect.left.toInt(), pageRect.top.toInt()),
                        dstSize = androidx.compose.ui.unit.IntSize(pageRect.width.toInt(), pageRect.height.toInt()),
                        colorFilter = if (ctl.nightMode) nightInvertFilter else null,
                    )
                }
                // The sharp render on top, placed by page points so it lines up even while a newer one renders.
                sharp?.let { (r, img) ->
                    if (r.page != zb.page) return@let
                    val k = m / r.scale
                    drawImage(
                        img,
                        dstOffset = androidx.compose.ui.unit.IntOffset(Math.round(origin.x + r.x * k), Math.round(origin.y + r.y * k)),
                        dstSize = androidx.compose.ui.unit.IntSize(Math.round(r.w * k), Math.round(r.h * k)),
                        colorFilter = if (ctl.nightMode) nightInvertFilter else null,
                    )
                }
                for (s in drafts) if (s.page == zb.page) {
                    drawInkStroke(s.paths.runs(s), Color(s.appearance.argb), s.appearance.opacity, s.appearance.style == InkStyle.Highlighter, origin, m, pageRect, page.height, InkLook(ctl.nightMode, s.appearance.onDark))
                }
                // the tail to the pen while a stroke is written on the pad: smoothing keeps the line a little behind it
                writing?.takeIf { it.page == zb.page }?.let { s ->
                    val (last, tip, scale) = inkTailStart(s) ?: return@let
                    val tail = inkTailPoints(origin + last * m, tip?.let { origin + it * m }, null)
                    val width = inkStylePointWidths(listOf(scale), s.appearance.width, s.appearance.style).first()
                    drawInkTail(tail, Color(s.appearance.argb), inkTailOpacity(s), width * m, s.appearance.style == InkStyle.Highlighter, InkLook(ctl.nightMode, s.appearance.onDark))
                }
                // The advance zone: lifting the pen here moves the box on.
                drawRect(Color(0xFF2E7DFF).copy(alpha = 0.07f), Offset(size.width * 0.8f, 0f), androidx.compose.ui.geometry.Size(size.width * 0.2f, size.height))
            }
        }
        Row(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
            SubTool(LucideIcons.ChevronsLeft, stringResource(R.string.zoom_box_new_line), false, {
                val zb = ctl.zoomBox ?: return@SubTool
                val page = doc.viewer.pages.getOrNull(zb.page) ?: return@SubTool
                val next = zb.nextLine(page.width, page.height, doc.viewer.pages.size)
                ctl.zoomBox = next
                if (next.page != zb.page) doc.viewer.goToPage(next.page, next.y)
            })
            SubTool(LucideIcons.X, stringResource(R.string.common_done), false, { ctl.zoomBoxOn = false })
        }
    }
}

/** Longest press that still counts as a two- or three-finger tap. */
internal const val MULTI_FINGER_TAP_MS = 300L
