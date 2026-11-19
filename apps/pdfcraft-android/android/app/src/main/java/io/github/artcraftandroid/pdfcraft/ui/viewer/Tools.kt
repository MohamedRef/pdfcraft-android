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

/** The text being marked with a markup tool: the page and the glyph range so far, and its rectangles. */
data class MarkupDrag(val range: TextRange, val rects: List<FloatArray>)

/**
 * The touch layer of the active tool over the pages. One finger acts (draws, marks text, places a
 * comment); two fingers still scroll and zoom the page, as in the reference.
 */
@Composable
fun ToolLayer(vm: AppViewModel, doc: OpenDocument) {
    val tool = doc.controller.tool
    if (vm.tools.prepareForm.doc === doc) {
        PrepareFormLayer(vm, doc)
        return
    }
    if (vm.tools.redact.placing === doc) {
        RedactPlaceLayer(vm, doc)
        return
    }
    if (tool == ViewerTool.None || tool == ViewerTool.Edit) return
    when (tool) {
        ViewerTool.Draw -> DrawLayer(vm, doc)
        ViewerTool.Markup -> MarkupLayer(vm, doc)
        ViewerTool.Comment -> TapLayer(doc) { p ->
            when (doc.controller.commentMode) {
                CommentMode.Voice -> vm.commenting.startVoice(doc, p)
                CommentMode.Stamp -> vm.runTool(
                    doc, "comment_add",
                    mapOf(
                        "page" to p.page + 1, "type" to "stamp", "at" to listOf(p.x.toDouble(), p.y.toDouble()),
                        "stamp" to doc.controller.stamp, "author" to vm.settings.toolPrefs.author.ifBlank { null },
                    ),
                )
                CommentMode.Text -> vm.commenting.notePrompt = NotePrompt(doc, p)
            }
        }
        ViewerTool.Text -> TapLayer(doc) { p -> vm.commenting.textPrompt = TextPrompt(doc, p, callout = doc.controller.textCallout) }
        ViewerTool.FillSign -> if (vm.signing.certPlacing === doc) CertPlaceLayer(vm, doc) else FillSignLayer(vm, doc)
        ViewerTool.Measure -> MeasureLayer(vm, doc)
        else -> Unit
    }
}

/**
 * The gesture split every tool layer shares: [onOne] gets a one-finger gesture (down, moves, up,
 * or cancelled when a second finger joins), two fingers pan and zoom the view.
 */
fun Modifier.toolGestures(
    doc: OpenDocument,
    onDown: (Offset, Float, Boolean) -> Unit,
    onMove: (Offset, Float, Boolean) -> Unit,
    onUp: (Offset, moved: Boolean) -> Unit,
    onCancel: () -> Unit,
    penOnly: Boolean = false,
    onEraserDown: (Offset) -> Unit = {},
    onEraserMove: (Offset) -> Unit = {},
    onEraserUp: (Offset) -> Unit = {},
    onEraserCancel: () -> Unit = {},
    /** What a pen-down with the side button held does (#144); the eraser tip always erases. */
    penButtonHold: PenButtonHold = PenButtonHold.Eraser,
    /** Called before [onDown] with whether this stroke is a temporary highlighter (button held). */
    onPenDownMode: (PenDownMode) -> Unit = {},
    /** A gesture that starts here belongs to another control on top (the zoom writing pad, #130). */
    ignoreAt: ((Offset) -> Boolean)? = null,
    /** A finger gesture that starts on an overlay (the ruler, #150) moves and turns it instead. */
    overlayHit: ((Offset) -> Boolean)? = null,
    onOverlayTransform: (pan: Offset, rotationDeg: Float, pivot: Offset) -> Unit = { _, _, _ -> },
    /** True for a finger touch that the writing-hand guard rejects (#156); it then neither draws nor scrolls. */
    rejectTouch: ((Offset, Long) -> Boolean)? = null,
    /** A quick tap with two or three fingers and no pan or zoom (#189); null turns it off. */
    onMultiFingerTap: ((fingers: Int) -> Unit)? = null,
): Modifier = pointerInput(doc, penOnly, penButtonHold, onMultiFingerTap != null, rejectTouch != null, overlayHit != null, ignoreAt != null) {
    val routing = StylusRouting()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (ignoreAt?.invoke(down.position) == true) return@awaitEachGesture
        // The routing decisions live in GestureRoute (unit-tested, #81). Some pens report an
        // eraser tip; other Android devices expose a stylus button. The mode is decided at
        // contact and kept until lift, whatever the button does mid-stroke (#82, #144).
        val route = GestureRoute(viewConfiguration.touchSlop, routing)
        var activeId = down.id
        // A palm resting beside a hovering or writing pen is ignored, though a pen may still arrive.
        val overlay = down.type == PointerType.Touch && overlayHit?.invoke(down.position) == true
        val palm = !overlay && down.type == PointerType.Touch && rejectTouch?.invoke(down.position, down.uptimeMillis) == true
        route.start(
            down.type.toolPointer(), down.position,
            buttonHeld = currentEvent.buttons.isPrimaryPressed || currentEvent.buttons.isSecondaryPressed,
            hold = penButtonHold, penOnly = penOnly, overlay = overlay, palm = palm,
        )
        var navigation = route.role == GestureRole.Navigate
        var one = route.role == GestureRole.Draw
        var eraseOne = route.role == GestureRole.Erase
        var last = down.position
        var lastPressure = down.pressure
        var maxTouches = 1
        var touchTravel = 0f
        var zoomDrift = 0f
        var endTime = down.uptimeMillis
        val diag = if (io.github.artcraftandroid.pdfcraft.engine.Diag.enabled()) GestureDiag(down.uptimeMillis, down.type.toolPointer(), route.role) else null
        inkSampleTimeMs = down.uptimeMillis
        if (one) { onPenDownMode(route.mode); onDown(last, lastPressure, route.activePen) }
        if (eraseOne) onEraserDown(last)
        fun dispatchPenMotion(change: androidx.compose.ui.input.pointer.PointerInputChange) {
            val nextPressure = change.pressure.takeIf { it.isFinite() && it > 0f } ?: lastPressure
            val samples = stylusMotionPoints(
                previousPosition = change.previousPosition,
                position = change.position,
                previousTime = change.previousUptimeMillis,
                currentTime = change.uptimeMillis,
                previousPressure = lastPressure,
                pressure = nextPressure,
                // Bound allocations before creating historical snapshots; providers can send long batches.
                history = change.historical.let { raw ->
                    val stride = ((raw.size.toLong() + MAX_STYLUS_HISTORY_POINTS - 1) /
                        MAX_STYLUS_HISTORY_POINTS).coerceAtLeast(1).toInt()
                    raw.asSequence().filterIndexed { index, _ -> index % stride == 0 }
                        .map { StylusHistoryPoint(it.uptimeMillis, it.position) }.toList()
                },
                pressureAt = { time -> StylusLog.pen.pressureAt(time) },
            )
            for (sample in samples) {
                route.motion(sample.position)
                last = sample.position
                inkSampleTimeMs = sample.uptimeMillis
                if (eraseOne) onEraserMove(last) else if (one) onMove(last, sample.pressure, true)
            }
            lastPressure = nextPressure
        }
        try {
            while (true) {
                val event = awaitPointerEvent()
                val arrivingPen = event.changes.firstOrNull { it.pressed && (it.type == PointerType.Stylus || it.type == PointerType.Eraser) }
                if (!route.activePen && arrivingPen != null) {
                    if (one) onCancel()
                    if (eraseOne) onEraserCancel()
                    activeId = arrivingPen.id
                    diag?.penArrived = true
                    val role = route.penArrives(
                        arrivingPen.type.toolPointer(), arrivingPen.position,
                        event.buttons.isPrimaryPressed || event.buttons.isSecondaryPressed, penButtonHold,
                    )
                    navigation = false
                    one = role == GestureRole.Draw
                    eraseOne = role == GestureRole.Erase
                    last = arrivingPen.position
                    lastPressure = arrivingPen.pressure
                    inkSampleTimeMs = arrivingPen.uptimeMillis
                    if (one) { onPenDownMode(route.mode); onDown(last, lastPressure, true) }
                    if (eraseOne) onEraserDown(last)
                }
                val active = event.changes.firstOrNull { it.id == activeId }
                // The side button is read only at contact: pressing it mid-stroke never cancels,
                // splits or erases the stroke being written (#82, #144).
                // Touch/palm pointers do not replace or interrupt an active pen or eraser tip.
                if (route.activePen) {
                    if (active != null) endTime = maxOf(endTime, active.uptimeMillis)
                    if (active == null || !active.pressed) {
                        if (active?.isConsumed == true) {
                            if (one) onCancel()
                            if (eraseOne) onEraserCancel()
                            one = false
                            eraseOne = false
                        } else if (active != null && (active.positionChanged() || active.historical.isNotEmpty())) {
                            dispatchPenMotion(active)
                        }
                        break
                    }
                    if (active.positionChanged() || active.historical.isNotEmpty()) {
                        // HistoricalChange has time/position but not hardware pressure.
                        // Preserve all valid ordered intermediate points on this active S Pen.
                        dispatchPenMotion(active)
                    }
                    event.changes.forEach { it.consume() }
                    continue
                }
                val pressed = event.changes.filter { it.pressed }
                maxTouches = maxOf(maxTouches, pressed.size)
                event.changes.forEach { endTime = maxOf(endTime, it.uptimeMillis) }
                if (pressed.isEmpty()) {
                    if (active?.isConsumed == true && one) { onCancel(); one = false }
                    break
                }
                if (overlay) {
                    val pan = event.calculatePan()
                    val rotation = event.calculateRotation()
                    if (pan != Offset.Zero || rotation != 0f) onOverlayTransform(pan, rotation, event.calculateCentroid())
                    event.changes.forEach { if (it.type == PointerType.Touch) it.consume() }
                    continue
                }
                if (palm) {
                    event.changes.forEach { if (it.type == PointerType.Touch) it.consume() }
                    continue
                }
                if (navigation || route.touches(pressed.size)) {
                    if (one) { one = false; onCancel() }
                    val zoom = event.calculateZoom()
                    zoomDrift += kotlin.math.abs(zoom - 1f)
                    if (zoom != 1f) doc.viewer.zoomBy(zoom, event.calculateCentroid())
                    val pan = event.calculatePan()
                    touchTravel += pan.getDistance()
                    if (pan != Offset.Zero) doc.viewer.scrollBy(pan.x, pan.y)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                    continue
                }
                if (!one) continue
                if (active == null || !active.pressed) { onCancel(); one = false; continue }
                if (active.positionChanged()) {
                    // Every batched sample of a fast finger, not one point per frame (#84).
                    val points = fingerMotionPoints(last, active.historical.map { it.position }, active.position)
                    diag?.let { it.fingerEvents++; it.fingerSamples += points.size }
                    for (point in points) {
                        route.motion(point)
                        last = point
                        inkSampleTimeMs = active.uptimeMillis
                        onMove(point, active.pressure, false)
                    }
                    active.consume()
                }
            }
            if (eraseOne) onEraserUp(last) else if (one) onUp(last, route.moved)
            diag?.log(route, maxTouches, endTime, cancelled = false)
            if (!route.activePen && !palm && !overlay && onMultiFingerTap != null) {
                multiFingerTapFingers(maxTouches, endTime - down.uptimeMillis, touchTravel + if (route.moved) Float.MAX_VALUE else 0f, zoomDrift, viewConfiguration.touchSlop)
                    ?.let(onMultiFingerTap)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (one) onCancel()
            if (eraseOne) onEraserCancel()
            diag?.log(route, maxTouches, endTime, cancelled = true)
            throw e
        }
    }
}

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
private fun ZoomWritingPad(
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

/**
 * How many fingers a finished touch gesture tapped with, or null when it was not a tap: one
 * finger, too long, moved more than [slop] in total, or zoomed noticeably.
 */
internal fun multiFingerTapFingers(maxFingers: Int, durationMs: Long, travel: Float, zoomDrift: Float, slop: Float): Int? =
    maxFingers.takeIf { it in 2..3 && durationMs in 0..MULTI_FINGER_TAP_MS && travel <= slop && zoomDrift <= 0.05f }

/** A tool that acts on a tap (Comment, Text): two fingers scroll, a tap on a page calls [onTap]. */
@Composable
private fun TapLayer(doc: OpenDocument, onTap: (PagePoint) -> Unit) {
    Box(
        Modifier.fillMaxSize().toolGestures(
            doc,
            onDown = { _, _, _ -> },
            onMove = { _, _, _ -> },
            onUp = { p, moved -> if (!moved) doc.viewer.screenToPage(p)?.let(onTap) },
            onCancel = {},
        ),
    )
}

@Composable
private fun DrawLayer(vm: AppViewModel, doc: OpenDocument) {
    // counted per stroke in the diagnostics log: a recomposition per pen move would be costly (#128)
    androidx.compose.runtime.SideEffect { InkLatency.stroke.recomposed() }
    // Drafts live with the document (#73): a save refused after Draw was left keeps them there.
    val drafts = doc.ink
    val strokes = drafts.strokes
    val edits = drafts.edits
    val undone = drafts.undone
    // Which of the document's undo steps this Draw session made (#78).
    val steps = remember(doc) { DrawUndoSteps() }
    var current by remember { mutableStateOf<InkStroke?>(null) }
    // a polygon or connected lines being placed point by point (#133), on one page
    val polyPoints = remember(doc) { mutableStateListOf<Offset>() }
    var polyPage by remember(doc) { mutableStateOf<Int?>(null) }
    LaunchedEffect(doc.controller.inkShapeMode) {
        polyPoints.clear()
        polyPage = null
    }
    var lassoPage by remember { mutableStateOf<Int?>(null) }
    val lassoPoints = remember { mutableStateListOf<Offset>() }
    val selected = remember { mutableStateListOf<InkStroke>() }
    // Saved drawings and shapes the lasso selected on lassoPage (moved, recoloured or deleted natively).
    val savedSelected = remember { mutableStateListOf<io.github.artcraftandroid.pdfcraft.core.CommentInfo>() }
    var moveAnchor by remember { mutableStateOf<Offset?>(null) }
    var moveOffset by remember { mutableStateOf(Offset.Zero) }
    val lassoDrag = remember { LassoDragDiag() }
    var moving by remember { mutableStateOf(false) }
    // The selection's resize or turn handle being dragged (#689): about [transformPivot] (page
    // points), from [transformFrom], by [transformScale] or [transformAngle] (degrees, clockwise as seen).
    var transformHandle by remember { mutableStateOf<LassoHandle?>(null) }
    var transformPivot by remember { mutableStateOf(Offset.Zero) }
    var transformFrom by remember { mutableStateOf(Offset.Zero) }
    var transformScale by remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    var transformAngle by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    // which side of the box the turn handle was taken on (#709), for the log
    var transformSide by remember { mutableStateOf(LassoTurnSide.Above) }
    fun endTransform() { transformHandle = null; transformScale = 1f; transformAngle = 0f }
    fun clearSelection() { selected.clear(); savedSelected.clear(); lassoPage = null; lassoPoints.clear(); moving = false; moveAnchor = null; moveOffset = Offset.Zero; endTransform() }
    /** Every selected outline in page points: draft stroke points and saved comments' rectangles. */
    fun selectionPaths(): List<List<Offset>> = selected.map { it.points.toList() } + savedSelected.mapNotNull { lassoRectOutline(it.rect) }
    // Worked out once per selection, not on every frame of a drag (#85): the points do not change
    // while the selection follows the pen, only moveOffset does.
    val selectionBounds by remember { androidx.compose.runtime.derivedStateOf { inkLassoBounds(selectionPaths()) } }
    val selectedSet by remember { androidx.compose.runtime.derivedStateOf { selected.toHashSet() } }
    /** A Draw edit of the document; [steps] learns whether it became an undo step. */
    fun documentEdit(name: String, args: Map<String, Any?>, settled: (Boolean) -> Unit = {}) {
        steps.started()
        var made = 0
        // A refused edit or a no-op (an eraser that met no ink) answers without the document summary.
        vm.runTool(doc, name, args, onSettled = { steps.finished(made); settled(made > 0) }) { r -> if (r.has("doc")) made = 1 }
    }
    /**
     * An edit of the selected saved comments. [journal] makes the same change to the strokes the
     * crash journal keeps for them (#703); null when it changes nothing the journal keeps.
     */
    fun savedEdit(page: Int, extra: Map<String, Any?>, journal: ((InkSaveStroke) -> InkSaveStroke?)? = null) {
        val indices = savedSelected.map { it.index.toInt() + 1 }
        if (indices.isEmpty()) return
        val inkRects = savedSelected.filter { it.subtype == "Ink" }.map { it.rect }
        documentEdit("comments_edit_many", mapOf("page" to page + 1, "comments" to indices) + extra) { made ->
            if (made && journal != null && inkRects.isNotEmpty()) doc.inkJournal?.savedInkChanged(page, inkRects, journal)
        }
    }
    /** An eraser cut saved ink along [path] on [page]: the crash journal can't cut its strokes the same way, so it says so (#703). */
    fun eraseTouchesJournal(page: Int, path: List<Offset>, radius: Float) {
        if (path.isEmpty()) return
        val area = androidx.compose.ui.geometry.Rect(
            path.minOf { it.x } - radius, path.minOf { it.y } - radius, path.maxOf { it.x } + radius, path.maxOf { it.y } + radius,
        )
        doc.inkJournal?.savedInkTouched(page, area)
    }
    val prefs = vm.settings.toolPrefs
    // The finished drafts as one picture (#609), so a frame of writing does not fill every draft again.
    val draftPicture = remember(doc) { InkDraftPicture() }
    var canvasSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    fun pictureView() = InkPictureView(canvasSize.width, canvasSize.height, doc.viewer.zoom, doc.viewer.scrollX, doc.viewer.scrollY, doc.viewer.layout, doc.controller.nightMode)
    // not while the lasso holds strokes: a selection is drawn where it is being moved to
    fun pictureWanted() = prefs.penTuning.draftPicture && !moving && !doc.controller.lassoMode
    fun DrawScope.drawDraftInk(s: InkStroke, lift: Offset, pageRect: androidx.compose.ui.geometry.Rect) {
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
    fun DrawScope.drawDraft(s: InkStroke, lift: Offset = Offset.Zero) {
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
            drawDraftInk(s, lift, pageRect)
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
        drawDraftInk(s, lift, pageRect)
    }
    val pictureDensity = LocalDensity.current
    LaunchedEffect(doc) {
        // What the picture is of. A stroke being written, here or on the zoom pad, is left out, so
        // its samples ask for nothing.
        snapshotFlow {
            if (!pictureWanted()) null else {
                val writing = current
                Triple(pictureView(), (drafts.saving + strokes).filter { it !== writing && (it.dynamics == null || it.lifted) }.map(::inkDraftKey), writing != null)
            }
        }.collectLatest { wanted ->
            if (wanted == null) {
                draftPicture.clear()
                return@collectLatest
            }
            // never while the pen is down, and only once the page and the drafts have stood still
            if (wanted.third) return@collectLatest
            delay(INK_PICTURE_SETTLE_MS)
            draftPicture.update(wanted.first, wanted.second.map { it.stroke }, pictureDensity) { drawDraft(it) }
        }
    }
    /** Drops a just-drawn draft stroke that turned out to be a gesture, without an undo step of its own. */
    fun dropGestureStroke(s: InkStroke) {
        strokes.remove(s)
        edits.removeAll { it is DraftInkEdit.Added && s in it.strokes }
    }
    /**
     * Smart pen gestures (#148), after lift only: a zig-zag deletes the ink under it, a straight
     * marker stroke over a text line highlights its words, a straight pen stroke just under a
     * line underlines them. Anything else stays the ink it was drawn as.
     */
    fun penGestureAfterLift(s: InkStroke) {
        val pts = s.points.toList()
        when (classifyPenGesture(pts)) {
            PenGesture.ScratchOut -> {
                val area = strokeBounds(pts).inflate(2f)
                val victims = strokes.filter { it !== s && it.page == s.page && scratchCovers(area, it.points.toList()) }
                val saved = doc.comments.filter {
                    it.page.toInt() == s.page && it.subtype == "Ink" && !it.locked && scratchCoversRect(area, it.rect)
                }
                PenDiag.gesture("scratch_out", if (victims.isEmpty() && saved.isEmpty()) "nothing_under" else "erased", victims.size, saved.size)
                if (victims.isEmpty() && saved.isEmpty()) return
                dropGestureStroke(s)
                if (victims.isNotEmpty()) {
                    val edit = DraftInkEdit.Removed(victims.map { strokes.indexOf(it) to it }.filter { it.first >= 0 })
                    applyDraftInkEdit(strokes, edit, forward = true)
                    edits.add(edit)
                    undone.clear()
                    steps.drafted()
                }
                if (saved.isNotEmpty()) {
                    documentEdit("comments_edit_many", mapOf("page" to s.page + 1, "comments" to saved.map { it.index.toInt() + 1 }, "delete" to true)) { made ->
                        // the crash journal drops the strokes it kept for them (#703)
                        if (made) doc.inkJournal?.savedInkChanged(s.page, saved.map { it.rect }) { null }
                    }
                }
                vm.tell(text(R.string.pen_gesture_scratched, victims.size + saved.size))
            }
            PenGesture.Line -> {
                val eng = vm.engine ?: return
                if (!doc.info.allowsAnnotation) {
                    PenDiag.gesture("line", "not_allowed")
                    return
                }
                val marker = s.appearance.style == InkStyle.Highlighter
                val y = pts.map { it.y }.average().toFloat()
                // An underline sits below its line: look for words a few points higher up.
                val probe = if (marker) 0f else 4f
                val start = PagePoint(s.page, pts.first().x, pts.first().y - probe)
                val end = PagePoint(s.page, pts.last().x, pts.last().y - probe)
                val color = Color(s.appearance.argb)
                val opacity = s.appearance.opacity
                vm.viewModelScope.launch {
                    val found = try { resolveMarkup(eng, doc, start, end, 3f, tap = false) } catch (_: MobileException) { null }
                    val line = found?.rects?.firstOrNull { r -> lineGestureMarkup(marker, y, minOf(r[1], r[3]), maxOf(r[1], r[3])) != null }
                    if (found == null || line == null) {
                        PenDiag.gesture("line", "no_text")
                        return@launch
                    }
                    val markup = lineGestureMarkup(marker, y, minOf(line[1], line[3]), maxOf(line[1], line[3])) ?: return@launch
                    // Committed meanwhile (Done): it is ink now, so leave it as drawn.
                    if (vm.document !== doc || s !in strokes) return@launch
                    val rects = found.rects.filter { r -> kotlin.math.abs((minOf(r[1], r[3]) + maxOf(r[1], r[3])) / 2f - (minOf(line[1], line[3]) + maxOf(line[1], line[3])) / 2f) < 1f }
                    // The stroke steps aside while the markup is made; it stays in the history,
                    // so Undo first brings the raw stroke back and a second Undo removes it.
                    val index = strokes.indexOf(s)
                    strokes.remove(s)
                    steps.started()
                    var made = 0
                    vm.runTool(doc, "comment_add", markupArgs(markup, s.page, rects, color, if (marker) opacity.coerceAtLeast(0.25f) else opacity, vm.settings.toolPrefs.author.ifBlank { null }),
                        onSettled = {
                            steps.finished(made)
                            PenDiag.gesture(if (marker) "line_highlight" else "line_underline", if (made > 0) "markup" else "failed")
                            if (made > 0) {
                                edits.add(DraftInkEdit.BecameMarkup(index, s, steps.own))
                                undone.clear()
                            } else if (s !in strokes) {
                                strokes.add(index.coerceIn(0, strokes.size), s)
                            }
                        }) { r -> if (r.has("doc")) made = 1 }
                }
            }
            PenGesture.None -> Unit
        }
    }
    // Capture appearance at pointer down. Every stroke is saved as its own annotation (#72).
    DisposableEffect(doc) {
        doc.commitTool = {
            val taken = drafts.takeForSaving()
            clearSelection()
            if (taken.isNotEmpty()) {
                // the pieces of a dashed or dotted line are one annotation, one group (#682)
                val strokeGroups = inkSaveStrokeGroups(
                    taken.map { s -> InkSaveStroke(s.page, s.appearance, s.points.toList(), s.scales.toList(), s.pressureSensitive, s.densities.toList(), s.group) },
                )
                val groups = strokeGroups.map { group -> inkSaveCommands(group) { argb -> Color(argb).hex() } }
                steps.started()
                val savedAt = android.os.SystemClock.uptimeMillis()
                // The drafts stay drawn until the engine has them; the ones it refuses stay drafts (#73).
                vm.runToolBatch(doc, "comment_add", groups) { applied ->
                    PenDiag.draftsSaved(taken.size, taken.sumOf { it.points.size }, groups.sumOf { it.size }, applied, android.os.SystemClock.uptimeMillis() - savedAt)
                    // the engine took the first [applied] groups: a dashed line is one group of many strokes (#682)
                    val savedStrokes = strokeGroups.take(applied).sumOf { it.size }
                    val kept = drafts.settle(taken, savedStrokes)
                    // in the engine now, but not in the file until the next save (#680)
                    doc.inkJournal?.committed(taken.take(savedStrokes))
                    steps.finished(groups.take(applied).sumOf { it.size })
                    if (kept > 0) vm.inkKept(doc, kept)
                }
            }
        }
        doc.localUndo = LocalUndo(
            // Draw edits are local until committed. Then Undo goes on to the document's steps,
            // quietly for this session's own and announced for an older one (#78). A Draw edit
            // still running holds both until it is known whether it became a step.
            canUndo = { steps.idle && (edits.isNotEmpty() || doc.info.undo != null) },
            // with a Draw edit of its own on top, Undo takes that back, not the document's newest step (#635)
            ownSteps = { edits.isNotEmpty() },
            canRedo = {
                steps.idle && (undone.isNotEmpty() || steps.canRedoDocument(doc.info.redo != null))
            },
            undo = undo@{
                if (!steps.idle) return@undo
                if (edits.isNotEmpty()) {
                    val edit = edits.removeAt(edits.lastIndex)
                    clearSelection()
                    if (edit is DraftInkEdit.BecameMarkup) {
                        // The markup must still be this session's newest step; otherwise only forget it.
                        val markupUndone = edit.ownStep == steps.own && doc.info.undo != null
                        PenDiag.gestureUndo(markupUndone)
                        if (!markupUndone) return@undo
                        steps.releaseOwn()
                        vm.undoDocument(doc, announce = false)
                    }
                    applyDraftInkEdit(strokes, edit, forward = false)
                    undone.add(edit)
                } else if (doc.info.undo != null) {
                    clearSelection()
                    vm.undoDocument(doc, announce = steps.undo() == DrawUndoSteps.Route.Announced)
                }
            },
            redo = redo@{
                if (!steps.idle) return@redo
                if (!steps.redoesDocumentFirst() && undone.isNotEmpty()) {
                    val edit = undone.removeAt(undone.lastIndex)
                    clearSelection()
                    if (edit is DraftInkEdit.BecameMarkup) {
                        if (doc.info.redo == null) return@redo
                        steps.reclaimOwn()
                        vm.redoDocument(doc, announce = false)
                    }
                    applyDraftInkEdit(strokes, edit, forward = true)
                    edits.add(edit)
                } else if (steps.canRedoDocument(doc.info.redo != null)) {
                    clearSelection()
                    vm.redoDocument(doc, announce = steps.redo() == DrawUndoSteps.Route.Announced)
                }
            },
        )
        onDispose {
            doc.localUndo = null
        }
    }
    // press and hold: where the finger has been still since, and when it last moved further
    var stillAt by remember { mutableStateOf(Offset.Zero) }
    var movedAt by remember { mutableStateOf(0L) }
    val slop = with(LocalDensity.current) { SNAP_SLOP.toPx() }
    // A stroke that follows the ruler or protractor is already straight or an arc: holding at
    // its end does not turn it into another shape (#150).
    var strokeGuided by remember(doc) { mutableStateOf(false) }
    // Smart pen gestures (#148): a closed loop held still at its end becomes a lasso on lift.
    var heldLoop by remember(doc) { mutableStateOf<InkStroke?>(null) }
    val loopHaptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    LaunchedEffect(current) {
        val s = current ?: return@LaunchedEffect
        if (s.shapeMode != InkShapeMode.Freehand || s.appearance.style == InkStyle.Highlighter) return@LaunchedEffect
        while (true) {
            delay(50)
            val held = System.currentTimeMillis() - movedAt >= SNAP_HOLD_MS
            if (prefs.penGestures && !strokeGuided && held && heldLoop !== s && isClosedLoop(s.points.toList())) {
                heldLoop = s
                s.preview = null
                loopHaptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            }
            if (prefs.snapToShape && !strokeGuided && heldLoop !== s && s.preview == null && held) {
                s.preview = ShapeSnap.recognise(s.points.toList())?.outline()
            }
        }
    }
    var eraserPage by remember { mutableStateOf<Int?>(null) }
    val eraserPoints = remember { mutableStateListOf<Offset>() }
    /** Chunks of the current eraser gesture sent before lift, for the diagnostics log (#76). */
    val eraserChunks = remember { intArrayOf(0) }
    var eraserScreen by remember { mutableStateOf<Offset?>(null) }
    // Snapshot for the duration of a gesture: slider changes never resize an in-flight erase.
    var activeEraserRadius by remember { mutableFloatStateOf(prefs.eraserRadius) }
    var activeEraseWhole by remember { mutableStateOf(prefs.eraseWholeStrokes) }
    // Preview only: hovering never inserts annotation points or touches document history.
    var hoverPosition by remember(doc) { mutableStateOf<Offset?>(null) }
    var hoverEraser by remember(doc) { mutableStateOf(false) }
    var buttonHighlighter by remember(doc) { mutableStateOf(false) }
    val penTracker = remember(doc) { PenTracker() }
    var layerOrigin by remember(doc) { mutableStateOf(Offset.Zero) }
    // Ink latency (#128): the newest pen sample a Compose draw has not shown yet.
    val pendingInput = remember(doc) { PendingInput() }
    // The ruler (#150): screen-space, kept per document while it is open, never saved.
    val density = LocalDensity.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val rulerReach = with(density) { 24.dp.toPx() }
    var rulerEdge by remember(doc) { mutableStateOf<Int?>(null) }
    var layerSize by remember(doc) { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    // The live eraser (#521): the page without its erasable ink, shown along the eraser's path.
    var liveErase by remember(doc) { mutableStateOf<LiveErase?>(null) }
    // The gesture before's live view when a new gesture starts before that one's edit's page is
    // drawn: kept until then, so what it erased does not come back meanwhile (#655).
    var liveErasePrev by remember(doc) { mutableStateOf<LiveErase?>(null) }
    /** Waits until [le]'s edit's own page is drawn (at the latest a few seconds after lift), then logs it and calls [gone]. */
    suspend fun untilEditDrawn(le: LiveErase, gone: () -> Unit) {
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
    /** The drafts as they were when the eraser touched down: its undo step, or what a cancel puts back. */
    var eraseDraftsBefore by remember(doc) { mutableStateOf<List<InkStroke>?>(null) }
    // The text markups on the eraser's page and those it has touched so far (#660): each goes
    // whole on lift, one undo step, whatever "Erase whole strokes" says (a markup has no part).
    var eraseMarkups by remember(doc) { mutableStateOf<List<ErasableMarkup>>(emptyList()) }
    val eraseMarkupHits = remember(doc) { mutableSetOf<List<Any?>>() }
    fun touchMarkups(a: Offset, b: Offset) {
        for (m in eraseMarkups) if (m.key !in eraseMarkupHits && m.boxes.any { eraserTouchesBox(a, b, activeEraserRadius, it) }) eraseMarkupHits += m.key
    }
    /** Where the lasso's action buttons are, in this layer; touches there are theirs, not the page's. */
    var lassoMenuBounds by remember(doc) { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val liveEraseScope = rememberCoroutineScope()
    LaunchedEffect(liveErase) {
        // gone once the edit's own page is drawn (or the edit changed nothing), at the latest after a few seconds
        val le = liveErase ?: return@LaunchedEffect
        untilEditDrawn(le) { if (liveErase === le) liveErase = null }
    }
    LaunchedEffect(liveErasePrev) {
        val le = liveErasePrev ?: return@LaunchedEffect
        untilEditDrawn(le) { if (liveErasePrev === le) liveErasePrev = null }
    }
    if (doc.controller.rulerOn && doc.controller.ruler == null && layerSize.width > 0f) {
        doc.controller.ruler = Ruler(
            Offset(layerSize.width / 2f, layerSize.height / 2f), 0f,
            halfWidth = with(density) { 34.dp.toPx() }, halfLength = maxOf(layerSize.width, layerSize.height) * 1.5f,
        )
    }
    val rulerTextMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    // The protractor (#150): arcs along its edge, whole-degree rays from its centre.
    if (doc.controller.protractorOn && doc.controller.protractor == null && layerSize.width > 0f) {
        doc.controller.protractor = Protractor(
            Offset(layerSize.width / 2f, layerSize.height * 0.6f), 0f,
            radius = minOf(with(density) { 150.dp.toPx() }, minOf(layerSize.width, layerSize.height) * 0.38f),
        )
    }
    var protractorSnap by remember(doc) { mutableStateOf<ProtractorSnap?>(null) }
    var rayDeg by remember(doc) { mutableStateOf<Float?>(null) }
    var arcStartDeg by remember(doc) { mutableStateOf(0f) }
    var protractorReadout by remember(doc) { mutableStateOf<Float?>(null) }
    // The zoom writing box (#130): a frame on the page, written in through a magnified pad.
    var padSize by remember(doc) { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    var overlayTarget by remember(doc) { mutableStateOf(0) }
    val padHeightPx = with(density) { ZOOM_PAD_HEIGHT.toPx() }
    if (doc.controller.zoomBoxOn && doc.controller.zoomBox == null && padSize.width > 0f) {
        doc.viewer.screenToPage(Offset(layerSize.width / 2f, layerSize.height / 4f), nearest = true)?.let { at ->
            doc.viewer.pages.getOrNull(at.page)?.let { pg ->
                doc.controller.zoomBox = ZoomBox.start(at.page, pg.width, pg.height, at.y, padSize)
            }
        }
    }
    fun zoomFrameOnScreen(): androidx.compose.ui.geometry.Rect? {
        val zb = doc.controller.zoomBox?.takeIf { doc.controller.zoomBoxOn } ?: return null
        val a = doc.viewer.pageToScreen(zb.page, zb.x, zb.y)
        val b = doc.viewer.pageToScreen(zb.page, zb.x + zb.width, zb.y + zb.height)
        return androidx.compose.ui.geometry.Rect(a, b)
    }
    fun activeRuler(): Ruler? = doc.controller.ruler?.takeIf { doc.controller.rulerOn }?.let { it.copy(angleDeg = rulerDisplayAngle(it.angleDeg)) }
    fun activeProtractor(): Protractor? =
        doc.controller.protractor?.takeIf { doc.controller.protractorOn }?.let { it.copy(angleDeg = rulerDisplayAngle(it.angleDeg)) }
    /**
     * A freehand stroke that starts beside the ruler follows its edge for the whole stroke; one
     * that starts on the protractor's arc follows the circle, and one from its centre is a ray.
     */
    fun rulerPoint(p: Offset): Offset {
        val outset = prefs.inkWidth * doc.viewer.pixelsPerPoint(current?.page ?: 0) / 2f
        rulerEdge?.let { edge -> return activeRuler()?.project(p, edge, outset) ?: p }
        val pr = activeProtractor() ?: return p
        return when (protractorSnap) {
            ProtractorSnap.Arc -> {
                protractorReadout = sweptAngle(arcStartDeg, pr.angleOf(p), protractorReadout ?: 0f)
                pr.projectArc(p, outset)
            }
            ProtractorSnap.Ray -> {
                // The ray's direction is fixed once the pen is clear of the centre.
                val deg = rayDeg ?: if ((p - pr.center).getDistance() > rulerReach) protractorRayAngle(pr.angleOf(p)).also { rayDeg = it } else null
                protractorReadout = deg
                if (deg == null) pr.center else pr.projectRay(p, deg)
            }
            null -> p
        }
    }
    val guardSizePx = with(LocalDensity.current) { prefs.palmGuardSizeDp.dp.toPx() }
    val buttonClick = remember(doc) { PenButtonClickDetector() }

    /**
     * Renders the page under its erasable ink for the gesture starting at [first] (#521); for the
     * whole-stroke eraser it also reads the page's saved strokes, to hide each one it touches (#655).
     */
    fun startLiveErase(page: Int, first: Offset) {
        // the gesture before, lifted, stays shown until its own edit's page is drawn (#655)
        liveErase?.takeIf { it.liftedGen != null }?.let { before ->
            liveErasePrev?.log("replaced", android.os.SystemClock.uptimeMillis())
            liveErasePrev = before
        }
        liveErase = null
        val hidden = erasableInk(doc.comments, page)
        if (hidden.isEmpty()) return
        val view = androidx.compose.ui.geometry.Rect(Offset.Zero, layerSize)
        val region = eraseRegion(page, doc.viewer.pageRectOnScreen(page), view, doc.viewer.pixelsPerPoint(page)) ?: return
        val startedAt = android.os.SystemClock.uptimeMillis()
        val le = LiveErase(region, activeEraserRadius, startedAt, activeEraseWhole)
        le.add(first)
        le.passed(first, startedAt)
        liveErase = le
        liveEraseScope.launch {
            val img = doc.images.renderHiding(page, region.scale, io.github.artcraftandroid.pdfcraft.core.TileRect(region.x.toUInt(), region.y.toUInt(), region.w.toUInt(), region.h.toUInt()), hidden)
            le.rendered(img, android.os.SystemClock.uptimeMillis())
        }
        if (le.whole) liveEraseScope.launch {
            val strokes = doc.images.erasableStrokes(page)
            le.strokesRead(strokes, android.os.SystemClock.uptimeMillis())
        }
    }

    /** The eraser over draft strokes on [page], from [a] to [b]: they are cut here, at once. */
    fun eraseDrafts(page: Int, a: Offset, b: Offset) {
        val radius = activeEraserRadius
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
            if (activeEraseWhole) { strokes.removeAt(i); continue }
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

    fun beginErase(p: Offset) {
        clearSelection()
        val at = doc.viewer.screenToPage(p) ?: return
        // Drafts are not saved first: the eraser cuts them here, at once, and the engine erases
        // only the saved ink. Saving first made the first erase after writing wait for every
        // stroke to be saved, seconds on a big document, with nothing erased meanwhile.
        current = null
        eraseDraftsBefore = strokes.toList()
        activeEraserRadius = prefs.eraserRadius
        activeEraseWhole = prefs.eraseWholeStrokes
        eraseDrafts(at.page, Offset(at.x, at.y), Offset(at.x, at.y))
        eraseMarkupHits.clear()
        eraseMarkups = erasableMarkups(doc.comments, at.page)
        touchMarkups(Offset(at.x, at.y), Offset(at.x, at.y))
        eraserPage = at.page
        activeEraserRadius = prefs.eraserRadius
        eraserScreen = p
        eraserPoints.clear()
        eraserPoints.add(Offset(at.x, at.y))
        eraserChunks[0] = 0
        // the live view rubs out what is under the eraser, or hides each saved stroke it touches (#655)
        startLiveErase(at.page, Offset(at.x, at.y))
    }

    fun moveErase(p: Offset) {
        val page = eraserPage ?: return
        // Unclamped: an eraser that leaves the page is not dragged along its edge (#75).
        val at = doc.viewer.screenToPageUnclamped(page, p) ?: return
        eraserScreen = p
        eraserPoints.lastOrNull()?.let { from ->
            eraseDrafts(page, from, Offset(at.x, at.y))
            touchMarkups(from, Offset(at.x, at.y))
        }
        val live = liveErase?.takeIf { it.region.page == page && it.liftedGen == null }
        live?.add(Offset(at.x, at.y))
        val before = eraserPoints.lastOrNull()
        appendEraserPoint(eraserPoints, Offset(at.x, at.y), activeEraserRadius)?.let { chunk ->
            // A very long gesture erases its finished part now; the rest follows on lift (#76).
            eraserChunks[0]++
            val radius = activeEraserRadius
            documentEdit("comment_erase", inkEraseCommand(page, chunk, radius.toDouble(), activeEraseWhole)) { made ->
                if (made) eraseTouchesJournal(page, chunk, radius)
            }
        }
        // the whole-stroke eraser tests each step of the path the edit gets, as the edit will (#655)
        eraserPoints.lastOrNull()?.takeIf { it != before }?.let { live?.passed(it, android.os.SystemClock.uptimeMillis()) }
    }

    fun cancelErase() {
        // a cancelled gesture erases nothing: the page under the ink must not stay shown, and the drafts come back
        eraseDraftsBefore?.let { before ->
            if (strokes.toList() != before) { strokes.clear(); strokes.addAll(before) }
        }
        eraseDraftsBefore = null
        liveErase?.takeIf { it.liftedGen == null }?.let { it.log("cancelled", android.os.SystemClock.uptimeMillis()); liveErase = null }
        eraserPage = null
        eraserScreen = null
        eraserPoints.clear()
        eraseMarkupHits.clear()
        eraseMarkups = emptyList()
    }

    fun finishErase(p: Offset) {
        val page = eraserPage ?: return
        moveErase(p)
        val path = eraserPoints.toList()
        val radius = activeEraserRadius
        // the drafts the eraser cut are one undo step of their own
        eraseDraftsBefore?.let { before ->
            val after = strokes.toList()
            if (after != before) {
                edits.add(DraftInkEdit.Erased(before, after))
                undone.clear()
                steps.drafted()
            }
        }
        eraseDraftsBefore = null
        // the text markups it touched go whole, before the ink: removing them first keeps the
        // ink's indices, and the ink eraser finds its own (#660)
        val markups = markupIndicesNow(doc.comments, page, eraseMarkupHits.toSet()).sortedDescending()
        if (markups.isNotEmpty()) {
            MarkupDiag.markupsErased(doc, markups.size)
            documentEdit("comments_edit_many", mapOf("page" to page + 1, "comments" to markups.map { it + 1 }, "delete" to true))
        }
        // the engine only has saved ink to erase, and ink still being saved
        val savedInk = erasableInk(doc.comments, page).isNotEmpty() || drafts.saving.any { it.page == page }
        // the live view stays until the edit's page is drawn (#521)
        val le = liveErase?.takeIf { it.region.page == page }?.also {
            it.liftedGen = doc.pageGenerations.of(page)
            it.liftedAt = android.os.SystemClock.uptimeMillis()
        }
        if (le != null) liveErase = null
        cancelErase()
        if (le != null) liveErase = le
        logEraseGesture(path.size, eraserChunks[0], radius)
        if (path.isNotEmpty() && savedInk) {
            // One native Edit::Batch per gesture, including freehand marker Ink.
            val radius = activeEraserRadius
            documentEdit("comment_erase", inkEraseCommand(page, path, radius.toDouble(), activeEraseWhole)) { made ->
                if (made) eraseTouchesJournal(page, path, radius)
                // nothing erased (or the edit failed): nothing changes on the page, so the live
                // view goes now, also when a newer gesture has started meanwhile (#655)
                if (!made && le != null && (liveErase === le || liveErasePrev === le)) {
                    le.log("nothing_erased", android.os.SystemClock.uptimeMillis())
                    if (liveErase === le) liveErase = null else liveErasePrev = null
                }
            }
        } else if (le != null) liveErase = null
    }

    /** [s] cut to its page; off-page parts are dropped and the pieces replace it in paint order. */
    fun clipStrokeToPage(s: InkStroke): List<InkStroke> {
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
    fun layPattern(lines: List<InkStroke>): List<InkStroke> = lines.flatMap { s ->
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
     */
    fun lassoTurnRoom(page: Int): LassoTurnRoom? {
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
        val ptPerDp = density.density / perPt
        return LassoTurnRoom(
            androidx.compose.ui.geometry.Rect(maxOf(0f, a.x), maxOf(0f, a.y), minOf(size.width, b.x), minOf(size.height, b.y)),
            menu, LASSO_KNOB_DP * ptPerDp, LASSO_HANDLE_SLOP_DP * ptPerDp,
        )
    }

    fun beginLasso(p: Offset) {
        val at = doc.viewer.screenToPage(p) ?: run { clearSelection(); return }
        val existing = lassoPage
        if (existing == at.page && (selected.isNotEmpty() || savedSelected.isNotEmpty())) {
            val b = selectionBounds
            // a handle first (#689): the resize handle is a corner of the box, which a move would take
            val perPt = doc.viewer.pixelsPerPoint(at.page).takeIf { it > 0f } ?: 1f
            val turnOffset = LASSO_TURN_HANDLE_DP * density.density / perPt
            // the turn handle where it is drawn: beside or below the box where above is hidden (#709)
            val room = lassoTurnRoom(at.page)
            val handle = b?.let { lassoHandleAt(it, Offset(at.x, at.y), LASSO_HANDLE_SLOP_DP * density.density / perPt, turnOffset, room) }
            if (b != null && handle != null) {
                transformHandle = handle
                transformPivot = lassoPivot(b, handle)
                transformFrom = lassoHandlePosition(b, handle, turnOffset, room)
                transformSide = lassoTurnSpot(b, turnOffset, room).side
                transformScale = 1f
                transformAngle = 0f
                return
            }
            if (b?.contains(Offset(at.x, at.y), 6f) == true) {
                moving = true
                moveAnchor = Offset(at.x, at.y)
                moveOffset = Offset.Zero
                return
            }
        }
        clearSelection()
        lassoPage = at.page
        addInkLassoPoint(lassoPoints, Offset(at.x, at.y))
    }

    fun moveLasso(p: Offset) {
        val page = lassoPage ?: return
        val at = doc.viewer.screenToPageOf(page, p) ?: return
        val point = Offset(at.x, at.y)
        when (transformHandle) {
            LassoHandle.Resize -> { transformScale = lassoResizeScale(transformPivot, transformFrom, point); return }
            LassoHandle.Turn -> { transformAngle = lassoTurnAngle(transformPivot, transformFrom, point); return }
            null -> Unit
        }
        if (moving) {
            val anchor = moveAnchor ?: return
            val pageSize = doc.viewer.pages.getOrNull(page) ?: return
            moveOffset = lassoDrag.time {
                boundedInkLassoMove(
                    selectionBounds,
                    point - anchor,
                    pageSize.width,
                    pageSize.height,
                )
            }
        } else {
            addInkLassoPoint(lassoPoints, point)
        }
    }

    /** Selects the drafts and saved comments [polygon] (page points on [page]) encloses or crosses. */
    fun selectInLoop(page: Int, polygon: List<Offset>) {
        selected.clear()
        savedSelected.clear()
        if (polygon.size >= 3) {
            selected.addAll(strokes.filter { it.page == page && inkLassoHitsStroke(polygon, it.points.toList()) })
            savedSelected.addAll(
                doc.comments.filter { c ->
                    c.page.toInt() == page && !c.hidden && savedLassoTarget(c.subtype, c.locked, c.fillSign, c.replyTo) && inkLassoHitsRect(polygon, c.rect)
                },
            )
        }
        if (selected.isEmpty() && savedSelected.isEmpty()) clearSelection()
        else lassoPoints.clear()
    }

    /**
     * The handle was let go (#689): drafts are replaced by turned and scaled copies (one Undo step),
     * saved comments are turned and scaled by the engine (one undo step); rectangles, ovals, text
     * and stamps are scaled but not turned.
     */
    fun finishTransform(page: Int, handle: LassoHandle) {
        val scale = if (handle == LassoHandle.Resize) transformScale else 1f
        val angle = if (handle == LassoHandle.Turn) transformAngle else 0f
        val pivot = transformPivot
        val side = if (handle == LassoHandle.Turn) transformSide.name.lowercase() else null
        endTransform()
        if (kotlin.math.abs(scale - 1f) < 1e-3f && kotlin.math.abs(angle) < 0.05f) return
        val draftCount = selected.size
        val savedCount = savedSelected.size
        if (selected.isNotEmpty()) {
            val before = strokes.toList()
            val changed = selected.associateWith { lassoTransformedDraft(it, pivot, scale, angle) }
            // the eraser's edit: every draft before and after, so Undo puts the old ones back
            val edit = DraftInkEdit.Erased(before, before.map { changed[it] ?: it })
            applyDraftInkEdit(strokes, edit, forward = true)
            edits.add(edit)
            undone.clear()
            steps.drafted()
            selected.clear()
            selected.addAll(changed.values)
        }
        var notTurned = 0
        if (savedSelected.isNotEmpty()) {
            val targets = if (angle != 0f) savedSelected.filter { it.subtype in LASSO_TURNABLE_SUBTYPES } else savedSelected.toList()
            notTurned = savedSelected.size - targets.size
            if (targets.isNotEmpty()) {
                val inkRects = targets.filter { it.subtype == "Ink" }.map { it.rect }
                documentEdit("comments_edit_many", mapOf(
                    "page" to page + 1, "comments" to targets.map { it.index.toInt() + 1 },
                    "transform" to listOf(pivot.x.toDouble(), pivot.y.toDouble(), scale.toDouble(), angle.toDouble()),
                )) { made ->
                    // the crash journal turns and scales the strokes it keeps for them the same way (#703)
                    if (made && inkRects.isNotEmpty()) doc.inkJournal?.savedInkChanged(page, inkRects) { s -> lassoTransformedSaved(s, pivot, scale, angle) }
                }
            }
            // their rectangles refresh with the document, so the saved part of the selection ends here
            savedSelected.clear()
            if (selected.isEmpty()) lassoPage = null
            if (notTurned > 0) vm.tell(text(R.string.ink_lasso_not_turned))
        }
        PenDiag.lassoTransform(handle.name.lowercase(), scale, angle, draftCount, savedCount, notTurned, side)
    }

    fun finishLasso(p: Offset) {
        val page = lassoPage ?: return
        moveLasso(p)
        transformHandle?.let { handle ->
            finishTransform(page, handle)
            return
        }
        if (moving) {
            lassoDrag.end(selected.size, savedSelected.size, moveOffset != Offset.Zero)
            if (moveOffset != Offset.Zero) {
                if (selected.isNotEmpty()) {
                    val edit = DraftInkEdit.Moved(selected.toList(), moveOffset)
                    applyDraftInkEdit(strokes, edit, forward = true)
                    edits.add(edit)
                    undone.clear()
                    steps.drafted()
                }
                // Saved comments move natively, all in one undo step; their rectangles refresh
                // with the document, so the saved part of the selection ends here.
                if (savedSelected.isNotEmpty()) {
                    val by = moveOffset
                    savedEdit(page, mapOf("move" to listOf(by.x.toDouble(), by.y.toDouble()))) { s -> s.copy(points = s.points.map { it + by }) }
                    savedSelected.clear()
                    if (selected.isEmpty()) lassoPage = null
                }
            }
            moving = false
            moveAnchor = null
            moveOffset = Offset.Zero
            return
        }
        val loop = lassoPoints.toList()
        // How long finding what a loop encloses takes on the phone (#85).
        io.github.artcraftandroid.pdfcraft.engine.Diag.measure(
            io.github.artcraftandroid.pdfcraft.engine.DiagArea.Ink, "lasso_select",
            "points" to loop.size, "drafts" to strokes.size, "saved_comments" to doc.comments.size,
        ) { selectInLoop(page, loop) }
        if (io.github.artcraftandroid.pdfcraft.engine.Diag.enabled()) {
            io.github.artcraftandroid.pdfcraft.engine.Diag.event(
                io.github.artcraftandroid.pdfcraft.engine.DiagArea.Ink, "lasso_selected",
                "strokes" to selected.size, "saved" to savedSelected.size,
            )
        }
    }


    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { layerSize = androidx.compose.ui.geometry.Size(it.width.toFloat(), it.height.toFloat()) }
            .onGloballyPositioned { layerOrigin = it.positionInRoot() }
            .pointerInput(doc) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.firstOrNull { it.type == PointerType.Stylus || it.type == PointerType.Eraser }
                            ?.let {
                                penTracker.seen(it.position, it.uptimeMillis)
                                doc.controller.penInRoot.seen(layerOrigin + it.position, it.uptimeMillis)
                            }
                        val hoverPen = event.changes.firstOrNull { !it.pressed && it.type == PointerType.Stylus }
                        if (event.type == PointerEventType.Press || event.type == PointerEventType.Exit || hoverPen == null) {
                            buttonClick.reset()
                        } else if (buttonClick.hover(event.buttons.isPrimaryPressed || event.buttons.isSecondaryPressed, hoverPen.uptimeMillis)) {
                            // A side-button click while hovering, with no stroke (#144).
                            PenDiag.penButtonClick(prefs.penButtonClick)
                            when (prefs.penButtonClick) {
                                PenButtonClick.Undo -> vm.undo(doc)
                                PenButtonClick.TogglePens -> {
                                    prefs.togglePreviousPen()
                                    doc.controller.eraserMode = false
                                    doc.controller.lassoMode = false
                                }
                                PenButtonClick.None -> Unit
                            }
                        }
                        when (event.type) {
                            PointerEventType.Exit, PointerEventType.Press, PointerEventType.Release -> hoverPosition = null
                            PointerEventType.Enter, PointerEventType.Move -> {
                                val pen = event.changes.firstOrNull {
                                    !it.pressed && (it.type == PointerType.Stylus || it.type == PointerType.Eraser)
                                }
                                // Do not treat finger motion or a pressed stylus as a hover event.
                                hoverPosition = pen?.position?.takeIf { it.x.isFinite() && it.y.isFinite() }
                                hoverEraser = pen?.type == PointerType.Eraser
                            }
                        }
                    }
                }
            }
            .toolGestures(
            doc,
            penOnly = prefs.stylusOnly,
            onMultiFingerTap = if (prefs.multiFingerUndo) ({ fingers ->
                if (fingers == 2) vm.undo(doc) else if (fingers == 3) vm.redo(doc)
            }) else null,
            onDown = { p, pressure, isStylus ->
                if (doc.controller.inkEyedropper) {
                    doc.controller.inkEyedropper = false
                    val at = doc.viewer.screenToPage(p) ?: return@toolGestures
                    val scale = doc.viewer.pixelsPerPoint(at.page).takeIf { it.isFinite() && it > 0f } ?: 1f
                    val picked = eyedropperColor(
                        strokes.filter { it.page == at.page }.map { EyedropperStroke(it.points.toList(), it.appearance.width / 2f, it.appearance.argb or 0xFF000000.toInt()) },
                        doc.comments.filter { it.page.toInt() == at.page && it.color != null }
                            .map { EyedropperTarget(it.rect, it.color!!.toInt(), it.subtype == "Ink") },
                        Offset(at.x, at.y),
                        tolerance = 12f / scale,
                    )
                    PenDiag.eyedropper(picked != null)
                    if (picked != null) {
                        prefs.setColor(StyledTool.Ink, Color(picked))
                        vm.tell(text(R.string.ink_eyedropper_picked, "#%06X".format(picked and 0xFFFFFF)))
                    } else vm.tell(text(R.string.ink_eyedropper_none))
                    return@toolGestures
                }
                if (doc.controller.lassoMode) {
                    beginLasso(p)
                    return@toolGestures
                }
                if (doc.controller.eraserMode) {
                    beginErase(p)
                    return@toolGestures
                }
                clearSelection()
                // a polygon's points are placed on lift, one tap each
                if (doc.controller.inkShapeMode.isTapped) return@toolGestures
                rulerEdge = if (doc.controller.inkShapeMode == InkShapeMode.Freehand) activeRuler()?.snapEdge(p, rulerReach) else null
                protractorSnap = if (rulerEdge == null && doc.controller.inkShapeMode == InkShapeMode.Freehand) activeProtractor()?.snapAt(p, rulerReach) else null
                rayDeg = null
                protractorReadout = null
                activeProtractor()?.let { arcStartDeg = it.angleOf(p) }
                strokeGuided = rulerEdge != null || protractorSnap != null
                val at = doc.viewer.screenToPage(rulerPoint(p)) ?: return@toolGestures
                stillAt = p
                movedAt = System.currentTimeMillis()
                // Holding the side button can draw with the highlighter for this stroke only (#144).
                val inkStyle = if (buttonHighlighter) InkStyle.Highlighter else prefs.inkStyle
                val pen = prefs.penSettings(inkStyle)
                val appearance = InkAppearance(
                    inkColorOnPaper(pen.argb, inkStyle, doc.controller.darkPaper),
                    inkStyleOpacity(pen.opacity, inkStyle),
                    pen.width, prefs.author.ifBlank { null }, inkStyle, onDark = doc.controller.darkPaper,
                )
                current = newInkStroke(
                    at.page, appearance, isStylus, doc.controller.inkShapeMode, prefs.penTuning,
                    dpPerPt = doc.viewer.pixelsPerPoint(at.page) / density.density,
                    pressureSetting = prefs.penPressure(inkStyle),
                ).also {
                    if (doc.controller.inkShapeMode == InkShapeMode.Freehand) it.pattern = prefs.penPattern(inkStyle)
                    it.dynamics?.stats?.let { stats ->
                        stats.draftsBefore = strokes.size + drafts.saving.size
                        stats.draftPointsBefore = strokes.sumOf { d -> d.points.size } + drafts.saving.sumOf { d -> d.points.size }
                    }
                    it.add(Offset(at.x, at.y), inkSampleTimeMs, pressure)
                    strokes.add(it)
                }
            },
            onMove = { raw, pressure, _ ->
                val p = if (current != null) rulerPoint(raw) else raw
                if (doc.controller.lassoMode) {
                    moveLasso(p)
                    return@toolGestures
                }
                if (eraserPage != null) {
                    moveErase(p)
                    return@toolGestures
                }
                val s = current ?: return@toolGestures
                if (s.shapeMode == InkShapeMode.Freehand && (p - stillAt).getDistance() > slop) {
                    stillAt = p
                    movedAt = System.currentTimeMillis()
                    s.preview = null
                    if (heldLoop === s) heldLoop = null
                }
                if (s.shapeMode == InkShapeMode.Freehand) {
                    // Unclamped: ink that leaves the page keeps its real path and is cut at the
                    // page edge on lift, never pinned along the border (#75).
                    val at = doc.viewer.screenToPageUnclamped(s.page, p) ?: return@toolGestures
                    s.add(Offset(at.x, at.y), inkSampleTimeMs, pressure)
                    // Straighten lines (#569): the highlighter is shown as the straight line it will be
                    if (inkStraightened(s, prefs.highlighterStraight)) s.preview = listOf(s.points.first(), s.points.last())
                    // the newest pen sample a draw has not shown yet (#128)
                    pendingInput.at = if (s.stylus) penTracker.timeMs else 0L
                    return@toolGestures
                }
                val at = doc.viewer.screenToPage(p, nearest = true) ?: return@toolGestures
                if (at.page == s.page) {
                    val pos = Offset(at.x, at.y)
                    // Keep only endpoints in Compose state while dragging; native ink gets a
                    // densified outline after lift. A temporary preview never reaches the engine.
                    if (s.points.size == 1) s.points.add(pos) else s.points[1] = pos
                    s.preview = doc.viewer.pages.getOrNull(s.page)?.let { page ->
                        inkShapeOutline(s.shapeMode, s.points.first(), pos, page.width, page.height)
                    }
                }
            },
            onUp = { p, _ ->
                if (doc.controller.lassoMode) {
                    finishLasso(p)
                    return@toolGestures
                }
                if (eraserPage != null) {
                    finishErase(p)
                    return@toolGestures
                }
                val mode = doc.controller.inkShapeMode
                if (mode.isTapped) {
                    val at = doc.viewer.screenToPage(p) ?: return@toolGestures
                    if (polyPage != null && polyPage != at.page) return@toolGestures
                    val point = Offset(at.x, at.y)
                    val radius = slop / doc.viewer.pixelsPerPoint(at.page).coerceAtLeast(0.1f) * 2f
                    when (polyTap(polyPoints, point, radius, closed = mode == InkShapeMode.Polygon)) {
                        PolyTap.Add -> {
                            polyPage = at.page
                            polyPoints.add(point)
                        }
                        PolyTap.Ignore -> Unit
                        PolyTap.Finish -> {
                            val args = polyAnnotationArgs(
                                mode == InkShapeMode.Polygon, polyPoints.toList(), at.page,
                                prefs.color(StyledTool.Ink).hex(), prefs.opacity(StyledTool.Ink), prefs.inkWidth.coerceAtLeast(0.5f), prefs.author.ifBlank { null },
                            )
                            polyPoints.clear()
                            polyPage = null
                            if (args != null) {
                                doc.commitTool?.invoke()
                                vm.runTool(doc, "comment_add", args)
                            }
                        }
                    }
                    return@toolGestures
                }
                val s = current
                current = null
                if (s != null && (rulerEdge != null || protractorSnap != null)) {
                    // A ruler or protractor stroke (#150) and the angle it settled on.
                    when (protractorSnap) {
                        ProtractorSnap.Arc -> PenDiag.guideStroke("protractor_arc", protractorReadout, s.points.size)
                        ProtractorSnap.Ray -> PenDiag.guideStroke("protractor_ray", rayDeg, s.points.size)
                        null -> PenDiag.guideStroke("ruler", activeRuler()?.angleDeg, s.points.size)
                    }
                }
                if (s != null && heldLoop === s) {
                    // Circle-then-hold (#148): the loop selects what it encloses, as the lasso would.
                    heldLoop = null
                    strokes.remove(s)
                    s.preview = null
                    clearSelection()
                    lassoPage = s.page
                    s.points.forEach { addInkLassoPoint(lassoPoints, it) }
                    selectInLoop(s.page, lassoPoints.toList())
                    PenDiag.gesture("loop_lasso", if (selected.isNotEmpty() || savedSelected.isNotEmpty()) "selected" else "nothing_inside", selected.size, savedSelected.size)
                    if (selected.isNotEmpty() || savedSelected.isNotEmpty()) doc.controller.lassoMode = true
                    else vm.tell(text(R.string.pen_gesture_lasso_empty))
                    return@toolGestures
                }
                if (s != null) {
                    finishInkStroke(s, prefs.penTuning)
                    // A palm/cancelled gesture must not discard a user's redo history.
                    // A tap is an actual dot, not a discarded stroke. Native PDF Ink needs
                    // two points, so represent the dot by a microscopic round-capped segment.
                    if (s.shapeMode == InkShapeMode.Freehand && s.points.size == 1) {
                        val persisted = persistedInkTapPoints(s.points.toList())
                        if (persisted.size == 2) {
                            s.points.add(persisted.last())
                            s.scales.add(s.scales.firstOrNull() ?: 1f)
                            s.matchDensities()
                        }
                    }
                    if (s.shapeMode.isCatalog) {
                        // More shapes (#693): a flat one is one annotation, a solid its visible and
                        // its dashed hidden edges, added together as one undo step
                        strokes.remove(s)
                        s.preview = null
                        val pageSize = doc.viewer.pages.getOrNull(s.page)
                        val geometry = if (pageSize != null && s.points.size >= 2) {
                            inkShapeGeometry(s.shapeMode, s.points.first(), s.points.last(), pageSize.width, pageSize.height)
                        } else null
                        val commands = geometry?.let {
                            catalogShapeCommands(s.shapeMode, it, s.page, Color(s.appearance.argb).hex(), s.appearance.opacity, s.appearance.width.coerceAtLeast(0.5f), s.appearance.author)
                        }
                        if (!commands.isNullOrEmpty()) {
                            doc.commitTool?.invoke()
                            if (commands.size == 1) documentEdit("comment_add", commands[0]) else documentEdit("comments_add", mapOf("comments" to commands))
                            MarkupDiag.shape(doc, s.shapeMode.name.lowercase(), s.points.size)
                        }
                        return@toolGestures
                    }
                    if (s.shapeMode.isAnnotation) {
                        // An arrow or cloud is its own PDF annotation, not ink: add the pending
                        // drafts first so the document's history keeps the drawing order.
                        strokes.remove(s)
                        s.preview = null
                        val pageSize = doc.viewer.pages.getOrNull(s.page)
                        val geometry = if (pageSize != null && s.points.size >= 2) {
                            inkShapeGeometry(s.shapeMode, s.points.first(), s.points.last(), pageSize.width, pageSize.height)
                        } else null
                        val args = geometry?.let {
                            shapeAnnotationArgs(s.shapeMode, it, s.page, Color(s.appearance.argb).hex(), s.appearance.opacity, s.appearance.width.coerceAtLeast(0.5f), s.appearance.author)
                        }
                        if (args != null) {
                            doc.commitTool?.invoke()
                            documentEdit("comment_add", args)
                            MarkupDiag.shape(doc, s.shapeMode.name.lowercase(), s.points.size)
                        }
                        return@toolGestures
                    }
                    if (inkStraightened(s, prefs.highlighterStraight)) {
                        // Straighten lines (#569): from where the stroke began to where it ended
                        val line = listOf(s.points.first(), s.points.last())
                        val scale = s.scales.firstOrNull() ?: 1f
                        s.preview = null
                        s.points.clear()
                        s.points.addAll(line)
                        s.pressureSensitive = false
                        s.scales.clear()
                        s.scales.addAll(listOf(scale, scale))
                        s.matchDensities()
                    }
                    // a held shape always snaps; with Snap to shape on, any stroke that is one does
                    val shape = if (s.shapeMode != InkShapeMode.Freehand) {
                        val page = doc.viewer.pages.getOrNull(s.page)
                        if (page != null && s.points.size >= 2) inkShapeOutline(s.shapeMode, s.points.first(), s.points.last(), page.width, page.height) else null
                    } else if (s.appearance.style == InkStyle.Highlighter) null else s.preview // only a held stroke snaps: handwriting (l, 1, o, 0) is never turned into a shape on lift
                    s.preview = null
                    if (s.points.size < 2 || (s.shapeMode != InkShapeMode.Freehand && shape == null)) {
                        strokes.remove(s)
                        return@toolGestures
                    }
                    if (shape != null) {
                        s.points.clear()
                        s.points.addAll(shape)
                        // Shape snapping deliberately uses the selected, constant pen width.
                        s.pressureSensitive = false
                        s.scales.clear()
                        s.scales.addAll(List(shape.size) { 1f })
                        s.densities.clear()
                    }
                    // Cut at the page edge: what is off the page is dropped (#75).
                    val pointsIn = s.points.size
                    val clipped = clipStrokeToPage(s)
                    if (clipped.singleOrNull() !== s) logStrokeClip(pointsIn, clipped)
                    val pieces = layPattern(clipped)
                    if (pieces.isNotEmpty()) {
                        edits.add(DraftInkEdit.Added(pieces))
                        undone.clear()
                        steps.drafted()
                    }
                    // A pen gesture is a stroke that stayed on its page (it may drop it again).
                    if (shape == null && pieces.singleOrNull() === s && prefs.penGestures && s.shapeMode == InkShapeMode.Freehand &&
                        rulerEdge == null && protractorSnap == null && s.points.size >= 2) {
                        penGestureAfterLift(s)
                    }
                }
            },
            onCancel = {
                if (doc.controller.lassoMode) {
                    // a handle let go of by a cancel changes nothing (#689)
                    endTransform()
                    if (moving) {
                        lassoDrag.end(selected.size, savedSelected.size, moved = false)
                        moving = false
                        moveAnchor = null
                        moveOffset = Offset.Zero
                    } else {
                        lassoPoints.clear()
                        if (selected.isEmpty()) lassoPage = null
                    }
                }
                if (eraserPage != null) cancelErase()
                current?.let { strokes.remove(it) }
                current = null
            },
            onEraserDown = { p -> beginErase(p) },
            onEraserMove = { p -> moveErase(p) },
            onEraserUp = { p -> finishErase(p) },
            onEraserCancel = { cancelErase() },
            penButtonHold = prefs.penButtonHold,
            // A touch on the lasso's Delete / Link / Recolour is the button's own: drawn on the
            // page under it, it started a new lasso first and the selection was gone before Delete.
            ignoreAt = { p -> (doc.controller.zoomBoxOn && p.y >= layerSize.height - padHeightPx) || lassoMenuBounds?.contains(p) == true },
            overlayHit = if (doc.controller.rulerOn || doc.controller.zoomBoxOn || doc.controller.protractorOn) ({ p ->
                when {
                    doc.controller.protractorOn && doc.controller.protractor?.contains(p) == true -> { overlayTarget = 2; true }
                    doc.controller.rulerOn && doc.controller.ruler?.contains(p) == true -> { overlayTarget = 0; true }
                    zoomFrameOnScreen()?.contains(p) == true -> { overlayTarget = 1; true }
                    else -> false
                }
            }) else null,
            onOverlayTransform = { pan, rotation, pivot ->
                if (overlayTarget == 1) {
                    // Dragging the frame moves where the pad writes on the page.
                    val zb = doc.controller.zoomBox ?: return@toolGestures
                    val pg = doc.viewer.pages.getOrNull(zb.page) ?: return@toolGestures
                    val ppp = doc.viewer.pixelsPerPoint(zb.page).takeIf { it > 0f } ?: return@toolGestures
                    val moved = zb.movedBy(pan.x / ppp, pan.y / ppp, pg.width, pg.height)
                    doc.controller.zoomBox = moved.copy(lineStart = if (pan.x != 0f) moved.x else moved.lineStart)
                    return@toolGestures
                }
                if (overlayTarget == 2) {
                    val was = doc.controller.protractor ?: return@toolGestures
                    val now = was.transformed(pan, rotation, pivot)
                    val snapped = rulerDisplayAngle(now.angleDeg)
                    if (snapped != now.angleDeg && rulerDisplayAngle(was.angleDeg) != snapped) {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                    }
                    doc.controller.protractor = now
                    return@toolGestures
                }
                val before = doc.controller.ruler ?: return@toolGestures
                val after = before.transformed(pan, rotation, pivot)
                // A haptic tick each time the angle settles on a 15° step.
                val wasSnapped = rulerDisplayAngle(before.angleDeg) != before.angleDeg
                val snapped = rulerDisplayAngle(after.angleDeg)
                if (snapped != after.angleDeg && (!wasSnapped || rulerDisplayAngle(before.angleDeg) != snapped)) {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                }
                doc.controller.ruler = after
            },
            rejectTouch = if (prefs.palmGuard) ({ p, t ->
                palmGuardRejects(penTracker.position, penTracker.timeMs, p, t, prefs.leftHanded, guardSizePx)
                    .also { if (it) PenDiag.palmRejected(PalmSpot.Page, prefs.palmGuardSizeDp, prefs.leftHanded) }
            }) else null,
            onPenDownMode = { mode ->
                buttonHighlighter = mode == PenDownMode.Highlight
                PenDiag.penDown(mode)
            },
        ),
    ) {
        Canvas(Modifier.fillMaxSize().onSizeChanged { canvasSize = it }) {
            if (current != null) pendingInput.drawn(android.os.SystemClock.uptimeMillis(), InkLatency.normal)
            val drawStart = if (current != null) System.nanoTime() else 0L
            // The whole-stroke eraser (#655): each saved stroke it touched is hidden at once, under
            // the drafts, until the edit's own page is drawn.
            for (le in listOfNotNull(liveErasePrev, liveErase)) {
                if (!le.whole) continue
                val erased = le.region.page
                drawWholeStrokeErase(le, doc.viewer.pageToScreen(erased, 0f, 0f), doc.viewer.pixelsPerPoint(erased), if (doc.controller.nightMode) nightInvertFilter else null)
            }
            // Strokes being saved stay drawn until the document has them, under the newer drafts.
            val all = drafts.saving + strokes
            // Finished drafts are shown from their picture when it stands for exactly what is on
            // the page at this view (#609); the stroke being written and anything newer than the
            // picture are drawn on top, stroke by stroke.
            draftPicture.generation
            val pictured = if (pictureWanted()) draftPicture.covers(pictureView(), all) else null
            current?.dynamics?.stats?.let { it.draftsPictured = maxOf(it.draftsPictured, pictured?.count() ?: 0) }
            var i = 0
            while (i < all.size) {
                val image = draftPicture.image
                if (pictured != null && image != null && i == pictured.first) {
                    drawImage(image)
                    i = pictured.last + 1
                    continue
                }
                val s = all[i]
                val handle = transformHandle
                if (handle != null && s in selectedSet) {
                    // the selection where the handle takes it (#689): its line widths scale with it, as on lift
                    val pivot = doc.viewer.pageToScreen(s.page, transformPivot.x, transformPivot.y)
                    withTransform({
                        rotate(transformAngle, pivot)
                        scale(transformScale, transformScale, pivot)
                    }) { drawDraft(s) }
                } else {
                    drawDraft(s, if (moving && s in selectedSet) moveOffset else Offset.Zero)
                }
                i++
            }
            // The live eraser (#521): along the eraser's path, the page without its erasable ink
            // covers the ink there (and drafts being saved), until the edit's own page is drawn.
            for (le in listOfNotNull(liveErasePrev, liveErase)) {
                if (le.whole) continue
                val img = le.image ?: continue
                val r = le.region
                val pts = le.path.toList()
                if (pts.isEmpty()) continue
                val ppp = doc.viewer.pixelsPerPoint(r.page)
                val topLeft = doc.viewer.pageToScreen(r.page, r.x / r.scale, r.y / r.scale)
                val size = androidx.compose.ui.unit.IntSize(Math.round(r.w / r.scale * ppp), Math.round(r.h / r.scale * ppp))
                drawIntoCanvas { canvas ->
                    canvas.saveLayer(androidx.compose.ui.geometry.Rect(Offset.Zero, this.size), androidx.compose.ui.graphics.Paint())
                    drawPath(
                        erasePathOnScreen(pts) { doc.viewer.pageToScreen(r.page, it.x, it.y) }, Color.Black,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = 2f * le.radius * ppp,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round,
                        ),
                    )
                    drawImage(
                        img, dstOffset = androidx.compose.ui.unit.IntOffset(Math.round(topLeft.x), Math.round(topLeft.y)), dstSize = size, blendMode = androidx.compose.ui.graphics.BlendMode.SrcIn,
                        // the page under the eraser is shown as the page around it: inverted in Night Mode (#610)
                        colorFilter = if (doc.controller.nightMode) nightInvertFilter else null,
                    )
                    canvas.restore()
                }
            }
            // how long the drafts took to draw this frame, and the time since the last frame (#128)
            if (drawStart != 0L) System.nanoTime().let { now -> InkLatency.stroke.drawn(now, (now - drawStart) / 1000) }
            // The tail of the stroke being drawn: from the line's last point on to the pen, which
            // smoothing keeps a little ahead of it (#569), and for a pen on to where it will be
            // about one frame from now, from its real speed. Drawn only, never saved; the next
            // real sample replaces it.
            current?.takeIf { it.shapeMode == InkShapeMode.Freehand && it.preview == null }?.let { s ->
                val (last, tip, scale) = inkTailStart(s) ?: return@let
                val tail = inkTailPoints(
                    doc.viewer.pageToScreen(s.page, last.x, last.y),
                    tip?.let { doc.viewer.pageToScreen(s.page, it.x, it.y) },
                    if (s.stylus) StylusLog.pen.predictedOffset(android.os.SystemClock.uptimeMillis()) else null,
                )
                if (tail.isEmpty()) return@let
                val width = inkStylePointWidths(listOf(scale), s.appearance.width, s.appearance.style).first()
                val pageRect = doc.viewer.pageRectOnScreen(s.page)
                clipRect(pageRect.left, pageRect.top, pageRect.right, pageRect.bottom) {
                    drawInkTail(tail, Color(s.appearance.argb), inkTailOpacity(s), width * doc.viewer.pixelsPerPoint(s.page), s.appearance.style == InkStyle.Highlighter, InkLook(doc.controller.nightMode, s.appearance.onDark))
                }
            }
            // the polygon or connected lines placed so far, with a dot on each point
            polyPage?.let { page ->
                val pts = polyPoints.map { doc.viewer.pageToScreen(page, it.x, it.y) }
                val ink = prefs.color(StyledTool.Ink)
                for (i in 1 until pts.size) drawLine(ink, pts[i - 1], pts[i], strokeWidth = (prefs.inkWidth * doc.viewer.pixelsPerPoint(page)).coerceAtLeast(1.dp.toPx()))
                pts.forEachIndexed { i, o ->
                    drawCircle(Color.White, (if (i == 0) 6 else 4).dp.toPx(), o)
                    drawCircle(Color(0xFF1A85CA), (if (i == 0) 6 else 4).dp.toPx(), o, style = Stroke(width = 1.5.dp.toPx()))
                }
            }
            if (doc.controller.lassoMode) {
                val page = lassoPage
                if (page != null) {
                    val polygon = lassoPoints.toList()
                    if (polygon.size >= 2) {
                        val path = Path()
                        polygon.forEachIndexed { i, pt ->
                            val o = doc.viewer.pageToScreen(page, pt.x, pt.y)
                            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                        }
                        drawPath(path, Color(0xFF1A85CA), style = Stroke(width = 1.5.dp.toPx()))
                    }
                    // Saved comments do not move until the finger lifts: their frames follow it.
                    val handle = transformHandle
                    // where a page point is shown while a handle is dragged (#689): turned and scaled about the pivot
                    fun shown(p: Offset): Offset {
                        val q = if (handle != null) {
                            lassoTransformPoint(p, transformPivot, if (handle == LassoHandle.Resize) transformScale else 1f, if (handle == LassoHandle.Turn) transformAngle else 0f)
                        } else p + moveOffset
                        return doc.viewer.pageToScreen(page, q.x, q.y)
                    }
                    fun frame(outline: List<Offset>, fill: Color?, stroke: Color, width: Float, still: Boolean = false) {
                        val path = Path()
                        outline.forEachIndexed { i, pt ->
                            val o = if (still) doc.viewer.pageToScreen(page, pt.x, pt.y) else shown(pt)
                            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                        }
                        path.close()
                        if (fill != null) drawPath(path, fill)
                        drawPath(path, stroke, style = Stroke(width = width))
                    }
                    for (c in savedSelected) {
                        val o = lassoRectOutline(c.rect) ?: continue
                        // a rectangle, oval, text box or stamp is not turned: its frame only scales
                        if (handle == LassoHandle.Turn && c.subtype !in LASSO_TURNABLE_SUBTYPES) {
                            frame(o, null, Color(0xFF1A85CA).copy(alpha = 0.3f), 1.dp.toPx(), still = true)
                            continue
                        }
                        frame(o, null, Color(0xFF1A85CA).copy(alpha = 0.6f), 1.dp.toPx())
                    }
                    val bounds = selectionBounds
                    if (bounds != null && handle != null) {
                        val outline = lassoRectOutline(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom))
                        if (outline != null) frame(outline, Color(0xFF1A85CA).copy(alpha = 0.12f), Color(0xFF1A85CA), 1.5.dp.toPx())
                    } else if (bounds != null) {
                        val a = doc.viewer.pageToScreen(page, bounds.left + moveOffset.x, bounds.top + moveOffset.y)
                        val b = doc.viewer.pageToScreen(page, bounds.right + moveOffset.x, bounds.bottom + moveOffset.y)
                        drawRect(Color(0xFF1A85CA).copy(alpha = 0.12f), a, Size(b.x - a.x, b.y - a.y))
                        drawRect(Color(0xFF1A85CA), a, Size(b.x - a.x, b.y - a.y), style = Stroke(width = 1.5.dp.toPx()))
                        // the resize handle at the corner and the turn handle above (#689), or beside or below
                        // the box where above is hidden (#709), while the selection stands still
                        if (!moving && lassoPoints.isEmpty()) {
                            val perPt = doc.viewer.pixelsPerPoint(page).takeIf { it > 0f } ?: 1f
                            val turnOffset = LASSO_TURN_HANDLE_DP * density.density / perPt
                            val corner = lassoHandlePosition(bounds, LassoHandle.Resize, turnOffset).let { doc.viewer.pageToScreen(page, it.x, it.y) }
                            // the same spot the touch looks for it at (beginLasso)
                            val spot = lassoTurnSpot(bounds, turnOffset, lassoTurnRoom(page))
                            val turn = doc.viewer.pageToScreen(page, spot.knob.x, spot.knob.y)
                            // its stalk from the box's edge nearest it
                            drawLine(Color(0xFF1A85CA), doc.viewer.pageToScreen(page, spot.root.x, spot.root.y), turn, strokeWidth = 1.5.dp.toPx())
                            for (o in listOf(corner, turn)) {
                                drawCircle(Color.White, LASSO_KNOB_DP.dp.toPx(), o)
                                drawCircle(Color(0xFF1A85CA), LASSO_KNOB_DP.dp.toPx(), o, style = Stroke(width = 2.dp.toPx()))
                            }
                            drawCircle(Color(0xFF1A85CA), 2.5.dp.toPx(), turn)
                        }
                    }
                }
            }
            val page = eraserPage
            val screen = eraserScreen
            if (page != null && screen != null) {
                drawCircle(
                    Color.DarkGray.copy(alpha = 0.75f),
                    radius = activeEraserRadius * doc.viewer.pixelsPerPoint(page),
                    center = screen,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
            zoomFrameOnScreen()?.let { f ->
                drawRect(Color(0xFF2E7DFF).copy(alpha = 0.08f), f.topLeft, f.size)
                drawRect(Color(0xFF2E7DFF), f.topLeft, f.size, style = Stroke(width = 1.5f * density.density))
            }
            activeRuler()?.let { r -> drawRuler(r, rulerTextMeasurer, density.density) }
            activeProtractor()?.let { pr ->
                drawProtractor(pr, rulerTextMeasurer, density.density)
                // The angle of the ray, or the angle the arc has swept, beside the pen while drawing.
                val readout = protractorReadout
                val pen = current?.points?.lastOrNull()?.let { doc.viewer.pageToScreen(current!!.page, it.x, it.y) }
                if (readout != null && pen != null && protractorSnap != null) {
                    val label = rulerTextMeasurer.measure("${"%.0f".format(java.util.Locale.ROOT, kotlin.math.abs(readout))}°",
                        style = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp)))
                    val at = pen + Offset(14f * density.density, -14f * density.density - label.size.height)
                    drawRoundRect(Color(0xE61F2430), at - Offset(6f * density.density, 3f * density.density),
                        androidx.compose.ui.geometry.Size(label.size.width + 12f * density.density, label.size.height + 6f * density.density),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f * density.density))
                    drawText(label, topLeft = at)
                }
            }
            val hovering = hoverPosition
            if (hovering != null && prefs.palmGuard) {
                // The guard region is visible while the pen hovers, so its size can be judged.
                val r = palmGuardRegion(hovering, prefs.leftHanded, guardSizePx)
                drawRect(Color(0xFF2E7DFF).copy(alpha = 0.06f), r.topLeft, r.size)
                drawRect(Color(0xFF2E7DFF).copy(alpha = 0.35f), r.topLeft, r.size, style = Stroke(width = 1.dp.toPx()))
            }
            if (hovering != null && current == null && eraserPage == null) {
                val onPage = doc.viewer.screenToPage(hovering)
                if (onPage != null) {
                    val diameter = if (doc.controller.eraserMode || hoverEraser) prefs.eraserRadius * 2f else inkStyleWidthRuns(listOf(1f, 1f), prefs.inkWidth, false, prefs.inkStyle).firstOrNull()?.width ?: prefs.inkWidth
                    drawCircle(
                        Color.DarkGray.copy(alpha = 0.6f),
                        radius = maxOf(2f, diameter * doc.viewer.pixelsPerPoint(onPage.page) / 2f),
                        center = hovering,
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }
            }
        }
        // Lasso actions under the selection: Delete (drafts and saved), and Recolour saved
        // comments with the current pen colour. Saved edits are one native undo step each.
        val selPage = lassoPage
        if (doc.controller.lassoMode && selPage != null && !moving && transformHandle == null && (selected.isNotEmpty() || savedSelected.isNotEmpty())) {
            val b = selectionBounds
            if (b != null) {
                val anchor = doc.viewer.pageToScreen(selPage, b.left, b.bottom)
                val density = LocalDensity.current
                DisposableEffect(Unit) { onDispose { lassoMenuBounds = null } }
                Row(
                    Modifier
                        .offset { androidx.compose.ui.unit.IntOffset(anchor.x.toInt().coerceAtLeast(0), (anchor.y + with(density) { 8.dp.toPx() }).toInt()) }
                        .onGloballyPositioned { lassoMenuBounds = it.boundsInParent() }
                        .background(pdfColors.menu, RoundedCornerShape(8.dp))
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SubTool(LucideIcons.Trash2, stringResource(R.string.common_delete), false, {
                        if (selected.isNotEmpty()) {
                            val removed = selected.map { s -> strokes.indexOf(s) to s }.filter { it.first >= 0 }
                            val edit = DraftInkEdit.Removed(removed)
                            applyDraftInkEdit(strokes, edit, forward = true)
                            edits.add(edit)
                            undone.clear()
                            steps.drafted()
                        }
                        savedEdit(selPage, mapOf("delete" to true)) { null }
                        clearSelection()
                    })
                    // link the selected handwriting to a page or web address (#159)
                    if (doc.info.allowsAnnotation) SubTool(LucideIcons.Link, stringResource(R.string.ink_lasso_link), false, {
                        doc.commitTool?.invoke()
                        vm.linksAndMeasure.linkEdit = LinkEdit(doc, selPage, floatArrayOf(b.left - 2f, b.top - 2f, b.right + 2f, b.bottom + 2f), from = "lasso")
                        clearSelection()
                    })
                    if (savedSelected.isNotEmpty()) {
                        SubTool(LucideIcons.Palette, stringResource(R.string.ink_lasso_recolor), false, {
                            val colour = prefs.color(StyledTool.Ink)
                            savedEdit(selPage, mapOf("color" to colour.hex())) { s -> s.copy(appearance = s.appearance.copy(argb = colour.toArgb())) }
                            clearSelection()
                        })
                    }
                }
            }
        }
        if (doc.controller.zoomBoxOn) {
            ZoomWritingPad(
                doc, prefs,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(ZOOM_PAD_HEIGHT)
                    .onSizeChanged { padSize = androidx.compose.ui.geometry.Size(it.width.toFloat(), it.height.toFloat()) },
                padSize = padSize,
                drafts = strokes,
                newStroke = { page, pressure, isStylus, at, dpPerPt ->
                    val pen = prefs.penSettings(prefs.inkStyle)
                    val appearance = InkAppearance(
                        inkColorOnPaper(pen.argb, prefs.inkStyle, doc.controller.darkPaper), inkStyleOpacity(pen.opacity, prefs.inkStyle),
                        pen.width, prefs.author.ifBlank { null }, prefs.inkStyle, onDark = doc.controller.darkPaper,
                    )
                    newInkStroke(page, appearance, isStylus, InkShapeMode.Freehand, prefs.penTuning, dpPerPt, prefs.penPressure(prefs.inkStyle)).also {
                        it.pattern = prefs.penPattern(prefs.inkStyle)
                        it.add(at, inkSampleTimeMs, pressure)
                        strokes.add(it)
                    }
                },
                finishStroke = { s ->
                    finishInkStroke(s, prefs.penTuning)
                    if (s.points.size == 1) {
                        val persisted = persistedInkTapPoints(s.points.toList())
                        if (persisted.size == 2) {
                            s.points.add(persisted.last())
                            s.scales.add(s.scales.firstOrNull() ?: 1f)
                            s.matchDensities()
                        }
                    }
                    if (s.points.size >= 2) {
                        edits.add(DraftInkEdit.Added(layPattern(listOf(s))))
                        undone.clear()
                        steps.drafted()
                    } else strokes.remove(s)
                },
            )
        }
    }
}

/** A gesture only holds coordinates; no native query runs on pointer down. */
/**
 * Area highlight (#134): the rectangle a Highlight drag covers where the page has no text under
 * it, as [x0, y0, x1, y1] in points, or null when it is too thin to be meant (under 4 points).
 */
internal fun areaHighlightRect(x0: Float, y0: Float, x1: Float, y1: Float): FloatArray? {
    if (!(x0.isFinite() && y0.isFinite() && x1.isFinite() && y1.isFinite())) return null
    val r = floatArrayOf(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1))
    return r.takeIf { it[2] - it[0] >= 4f && it[3] - it[1] >= 4f }
}

private class MarkupGesture(val start: PagePoint, val downScreen: Offset) {
    var latest by mutableStateOf(start)
    var moved by mutableStateOf(false)
}

/**
 * What a markup gesture covers, resolved on the native serial dispatcher (#657). A tap marks its
 * whole word. A drag marks from the letter where it started to the letter under the pen now, in
 * reading order, like a text selection: not whole words. Either end may lie beside its line (the
 * margin, between lines) and takes that line's nearest letter; one far from any text gives null.
 */
private suspend fun resolveMarkup(
    eng: Engine,
    doc: OpenDocument,
    start: PagePoint,
    end: PagePoint,
    slopPoints: Float,
    tap: Boolean,
): MarkupDrag? = resolveMarkupWhy(eng, doc, start, end, slopPoints, tap).first

/** [resolveMarkup], with why nothing was found (the reasons of `markup_unresolved`). */
private suspend fun resolveMarkupWhy(
    eng: Engine,
    doc: OpenDocument,
    start: PagePoint,
    end: PagePoint,
    slopPoints: Float,
    tap: Boolean,
): Pair<MarkupDrag?, String?> = withContext(eng.commands) {
    if (start.page != end.page) return@withContext null to "two_pages"
    val page = start.page.toUInt()
    val range = if (tap) {
        // A tap deliberately does not reach far: blank space must stay blank.
        eng.session.wordAt(doc.id, page, start.x, start.y, slopPoints) ?: return@withContext null to "no_start_letter"
    } else {
        // The engine's nearestGlyph prefers the same line; the reach keeps it from another column.
        fun letter(p: PagePoint, vertical: Float): UInt? {
            val glyph = eng.session.nearestGlyph(doc.id, page, p.x, p.y) ?: return null
            val rect = eng.session.glyphRect(doc.id, page, glyph).toList()
            return glyph.takeIf { markupNearLine(p.x, p.y, rect, MARKUP_SIDE_REACH_PT, vertical) }
        }
        val a = letter(start, slopPoints) ?: return@withContext null to "no_start_letter"
        val b = letter(end, (slopPoints * 2f).coerceAtMost(40f)) ?: return@withContext null to "no_end_letter"
        // the word a letter is in, found from the letter's own middle (#739)
        fun wordOf(glyph: UInt): TextRange? {
            val r = eng.session.glyphRect(doc.id, page, glyph).toList()
            if (r.size != 4) return null
            return eng.session.wordAt(doc.id, page, (r[0] + r[2]) / 2f, (r[1] + r[3]) / 2f, 0.5f)
        }
        // a drag that starts or ends inside a word takes the whole word (#739)
        val (lo, hi) = markupWholeWords(minOf(a, b), maxOf(a, b), wordOf(minOf(a, b)), wordOf(maxOf(a, b)))
        markupLetterRange(page, lo, hi) ?: return@withContext null to "too_long"
    }
    val rects = markupLineRects(eng.session.textRects(doc.id, range).toList())
    if (rects.isEmpty()) null to "no_lines" else MarkupDrag(range, rects) to null
}

@Composable
private fun MarkupLayer(vm: AppViewModel, doc: OpenDocument) {
    val eng = vm.engine ?: return
    val scope = rememberCoroutineScope()
    var drag by remember(doc) { mutableStateOf<MarkupDrag?>(null) }
    var active by remember(doc) { mutableStateOf<MarkupGesture?>(null) }
    var previewJob by remember(doc) { mutableStateOf<Job?>(null) }
    var pendingTap by remember(doc) { mutableStateOf<Offset?>(null) }
    var requestSerial by remember(doc) { androidx.compose.runtime.mutableIntStateOf(0) }
    // How many letters the touched page has: only a page without any (a scan, a picture) gets an
    // area highlight; on a page with text a drag marks letters or nothing (#657).
    var pageGlyphs by remember(doc) { mutableStateOf<Pair<Int, UInt>?>(null) }
    val marker = doc.controller.markup
    val color = vm.settings.toolPrefs.color(marker.styled)
    val opacity = vm.settings.toolPrefs.opacity(marker.styled)
    val moveThresholdPx = with(LocalDensity.current) { 8.dp.toPx() }
    val hitRadiusPx = with(LocalDensity.current) { 14.dp.toPx() }

    fun resetPreview() {
        active = null
        previewJob?.cancel()
        previewJob = null
        drag = null
    }

    Box(
        Modifier.fillMaxSize().toolGestures(
            doc,
            onDown = { screen, _, _ ->
                requestSerial++
                resetPreview()
                // the bar by the last markup goes as the next one begins (#650)
                vm.commenting.markupFollowUp = null
                pendingTap = screen
                val start = doc.viewer.screenToPage(screen) ?: return@toolGestures
                val gesture = MarkupGesture(start, screen)
                active = gesture
                if (pageGlyphs?.first != start.page) {
                    pageGlyphs = null
                    scope.launch {
                        val n = runCatching { withContext(eng.commands) { eng.session.glyphCount(doc.id, start.page.toUInt()) } }.getOrNull()
                        if (n != null && active === gesture) pageGlyphs = start.page to n
                    }
                }
                // One native query at a time, skipping intermediate points. The initial
                // word previews on finger-down; fast moves cannot starve the preview.
                previewJob = scope.launch {
                    snapshotFlow { gesture.latest to gesture.moved }
                        .conflate()
                        .collect { (end, moved) ->
                            val radius = (hitRadiusPx / doc.viewer.pixelsPerPoint(start.page).coerceAtLeast(0.1f)).coerceIn(0.1f, 24f)
                            val preview = resolveMarkup(eng, doc, start, end, radius, tap = !moved)
                            if (active === gesture && gesture.latest == end && gesture.moved == moved) {
                                drag = preview
                                pendingTap = null
                            }
                        }
                }
            },
            onMove = { screen, _, _ ->
                val gesture = active ?: return@toolGestures
                val at = doc.viewer.screenToPageOf(gesture.start.page, screen) ?: return@toolGestures
                if ((screen - gesture.downScreen).getDistance() > moveThresholdPx) gesture.moved = true
                if (drag == null) pendingTap = screen
                gesture.latest = at
            },
            onUp = { screen, _ ->
                val gesture = active ?: return@toolGestures
                val end = doc.viewer.screenToPageOf(gesture.start.page, screen) ?: gesture.latest
                val tap = !gesture.moved && (screen - gesture.downScreen).getDistance() <= moveThresholdPx
                val page = gesture.start.page
                val radius = (hitRadiusPx / doc.viewer.pixelsPerPoint(page).coerceAtLeast(0.1f)).coerceIn(0.1f, 24f)
                val selectedMarkup = doc.controller.markup
                val selectedColor = vm.settings.toolPrefs.color(selectedMarkup.styled)
                val selectedOpacity = vm.settings.toolPrefs.opacity(selectedMarkup.styled)
                val serial = requestSerial
                active = null
                previewJob?.cancel()
                previewJob = null
                // Keep the last correctly resolved preview visible until the final native
                // selection is ready. A quick tap gets a small pending marker instead.
                pendingTap = if (drag == null) screen else null
                // Final selection is computed from the *release* position, not the last
                // asynchronous preview. Keep the completed gesture alive if Done is tapped.
                vm.viewModelScope.launch {
                    var queued = false
                    try {
                        if (vm.document !== doc || !doc.info.allowsAnnotation) return@launch
                        val (selected, why) = resolveMarkupWhy(eng, doc, gesture.start, end, radius, tap)
                        if (vm.document !== doc || !doc.info.allowsAnnotation) return@launch
                        if (selected != null) {
                            if (requestSerial == serial) {
                                drag = selected
                                pendingTap = null
                            }
                            queued = true
                            if (!tap) MarkupDiag.textMarked(doc, selectedMarkup, (selected.range.end - selected.range.start).toInt(), selected.rects.size)
                            vm.commenting.addMarkup(
                                doc, selectedMarkup, selected.range.page.toInt(), selected.rects,
                                selectedColor, selectedOpacity,
                                onSettled = {
                                    // The overlay stays visible while native save/refresh runs,
                                    // preventing the old one-second blank flicker.
                                    if (requestSerial == serial) {
                                        pendingTap = null
                                        drag = null
                                    }
                                },
                                // the new markup stays in hand until the page is touched again (#650)
                                onDone = { r ->
                                    if (requestSerial == serial && doc.controller.tool == ViewerTool.Markup) {
                                        vm.commenting.keepInHand(doc, selected.range.page.toInt(), selected.rects, selectedColor, r)
                                        MarkupDiag.inHand(doc, selectedMarkup, selected.rects.size)
                                    }
                                },
                            )
                        } else {
                            val glyphs = withContext(eng.commands) { eng.session.glyphCount(doc.id, page.toUInt()) }
                            val area = if (!tap && glyphs == 0u && selectedMarkup == TextMarkup.Highlight)
                                areaHighlightRect(gesture.start.x, gesture.start.y, end.x, end.y) else null
                            when {
                                // a page with text: a drag far from it marks nothing, never a free box (#657)
                                glyphs != 0u -> MarkupDiag.unresolved(doc, selectedMarkup, why ?: "-", tap, glyphs.toLong())
                                // no text on the page at all (a scan, a picture): highlight the area itself (#134)
                                area != null -> {
                                    queued = true
                                    MarkupDiag.areaHighlight(doc, area)
                                    vm.commenting.addMarkup(doc, TextMarkup.Highlight, page, listOf(area), selectedColor, selectedOpacity, onSettled = {
                                        if (requestSerial == serial) {
                                            pendingTap = null
                                            drag = null
                                        }
                                    }, onDone = { r ->
                                        if (requestSerial == serial && doc.controller.tool == ViewerTool.Markup) {
                                            vm.commenting.keepInHand(doc, page, listOf(area), selectedColor, r)
                                            MarkupDiag.inHand(doc, TextMarkup.Highlight, 1)
                                        }
                                    })
                                }
                                // a tap, or Underline or Strikeout, where there is no text: say how to get some
                                (tap || selectedMarkup != TextMarkup.Highlight) && vm.document === doc -> {
                                    MarkupDiag.unresolved(doc, selectedMarkup, "no_text", tap, 0)
                                    vm.tell(text(R.string.toolbar_no_text_to_mark), text(R.string.common_tool_recognize_text)) { vm.recognizeText(doc) }
                                }
                            }
                        }
                    } catch (e: MobileException) {
                        if (vm.document === doc) vm.fail(e.userMessage)
                    } finally {
                        if (!queued && requestSerial == serial) {
                            pendingTap = null
                            drag = null
                        }
                    }
                }
            },
            onCancel = {
                requestSerial++
                pendingTap = null
                resetPreview()
            },
        ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            pendingTap?.let { point ->
                drawCircle(
                    color.copy(alpha = 0.65f),
                    radius = 10.dp.toPx(), center = point,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
            // a Highlight drag that found no text previews the area it will highlight
            val g = active
            if (drag == null && g != null && g.moved && marker == TextMarkup.Highlight && pageGlyphs == (g.start.page to 0u)) {
                areaHighlightRect(g.start.x, g.start.y, g.latest.x, g.latest.y)?.let { r ->
                    val a = doc.viewer.pageToScreen(g.start.page, r[0], r[1])
                    val b = doc.viewer.pageToScreen(g.start.page, r[2], r[3])
                    drawRect(color.copy(alpha = 0.4f * opacity), a, Size(b.x - a.x, b.y - a.y))
                }
            }
            val d = drag ?: return@Canvas
            val page = d.range.page.toInt()
            for (r in d.rects) {
                val a = doc.viewer.pageToScreen(page, r[0], r[1])
                val b = doc.viewer.pageToScreen(page, r[2], r[3])
                when (marker) {
                    TextMarkup.Highlight -> drawRect(color.copy(alpha = 0.4f * opacity), a, Size(b.x - a.x, b.y - a.y))
                    TextMarkup.Underline -> drawLine(color.copy(alpha = opacity), Offset(a.x, b.y - 1f), Offset(b.x, b.y - 1f), 2.dp.toPx())
                    TextMarkup.Strikethrough -> drawLine(color.copy(alpha = opacity), Offset(a.x, (a.y + b.y) / 2f), Offset(b.x, (a.y + b.y) / 2f), 2.dp.toPx())
                    TextMarkup.Squiggly -> drawSquiggle(color.copy(alpha = opacity), a.x, b.x, b.y - 2.dp.toPx(), 1.5.dp.toPx())
                }
            }
        }
    }
}

private fun PointerType.toolPointer(): ToolPointer = when (this) {
    PointerType.Stylus -> ToolPointer.Stylus
    PointerType.Eraser -> ToolPointer.Eraser
    else -> ToolPointer.Touch
}
