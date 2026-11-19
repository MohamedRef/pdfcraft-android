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

// Moved from Tools.kt as is (#730): the tool layer over the pages, its gestures and the tap layer.

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


private fun PointerType.toolPointer(): ToolPointer = when (this) {
    PointerType.Stylus -> ToolPointer.Stylus
    PointerType.Eraser -> ToolPointer.Eraser
    else -> ToolPointer.Touch
}
