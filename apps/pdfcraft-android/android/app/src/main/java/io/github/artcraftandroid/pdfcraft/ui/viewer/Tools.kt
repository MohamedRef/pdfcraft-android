package io.github.artcraftandroid.pdfcraft.ui.viewer


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
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A stroke being drawn: its page and points (in the page's points). */
internal class InkStroke(val page: Int, val appearance: InkAppearance, stylus: Boolean = false, val shapeMode: InkShapeMode = InkShapeMode.Freehand) {
    val points = mutableStateListOf<Offset>()
    val pressures = mutableStateListOf<Float>()
    var pressureSensitive by mutableStateOf(stylus)

    /** The clean shape shown while the finger is held still (press and hold to snap). */
    var preview by mutableStateOf<List<Offset>?>(null)

    /** Smoothed page-space paths, rebuilt only when this stroke's points change. */
    val paths = InkPathCache()
}

/** Reversible changes to the uncommitted Draw layer, in actual gesture order. */
private sealed class DraftInkEdit {
    data class Added(val stroke: InkStroke) : DraftInkEdit()
    data class Moved(val strokes: List<InkStroke>, val offset: Offset) : DraftInkEdit()
}

private fun applyDraftInkEdit(strokes: androidx.compose.runtime.snapshots.SnapshotStateList<InkStroke>, edit: DraftInkEdit, forward: Boolean) {
    when (edit) {
        is DraftInkEdit.Added -> {
            if (forward) strokes.add(edit.stroke) else strokes.remove(edit.stroke)
        }
        is DraftInkEdit.Moved -> {
            val d = if (forward) edit.offset else -edit.offset
            edit.strokes.forEach { stroke ->
                for (i in stroke.points.indices) stroke.points[i] = stroke.points[i] + d
                stroke.preview = null
            }
        }
    }
}

/** How long the finger stays still to snap a stroke to a shape, and how far it may wander. */
private const val SNAP_HOLD_MS = 500L
private val SNAP_SLOP = 8.dp

/** The text being marked with a markup tool: the page and the glyph range so far, and its rectangles. */
data class MarkupDrag(val range: TextRange, val rects: List<FloatArray>)

/**
 * The touch layer of the active tool over the pages. One finger acts (draws, marks text, places a
 * comment); two fingers still scroll and zoom the page, as in the reference.
 */
@Composable
fun ToolLayer(vm: AppViewModel, doc: OpenDocument) {
    val tool = doc.controller.tool
    if (tool == ViewerTool.None || tool == ViewerTool.Edit) return
    when (tool) {
        ViewerTool.Draw -> DrawLayer(vm, doc)
        ViewerTool.Markup -> MarkupLayer(vm, doc)
        ViewerTool.Comment -> TapLayer(doc) { p ->
            when (doc.controller.commentMode) {
                CommentMode.Voice -> vm.startVoice(doc, p)
                CommentMode.Stamp -> vm.runTool(
                    doc, "comment_add",
                    mapOf(
                        "page" to p.page + 1, "type" to "stamp", "at" to listOf(p.x.toDouble(), p.y.toDouble()),
                        "stamp" to doc.controller.stamp, "author" to vm.toolPrefs.author.ifBlank { null },
                    ),
                )
                CommentMode.Text -> vm.notePrompt = NotePrompt(doc, p)
            }
        }
        ViewerTool.Text -> TapLayer(doc) { p -> vm.textPrompt = TextPrompt(doc, p, callout = doc.controller.textCallout) }
        ViewerTool.FillSign -> if (vm.certPlacing === doc) CertPlaceLayer(vm, doc) else FillSignLayer(vm, doc)
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
): Modifier = pointerInput(doc, penOnly) {
    val routing = StylusRouting()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val stylus = down.type == PointerType.Stylus
        var activePen = stylus || down.type == PointerType.Eraser
        var activeId = down.id
        // Some pens report an eraser tip; other Android devices expose a stylus button.
        // A button stays in eraser mode until lift even when the button is released mid-stroke.
        var eraser = down.type == PointerType.Eraser ||
            (stylus && (currentEvent.buttons.isPrimaryPressed || currentEvent.buttons.isSecondaryPressed))
        if (activePen) routing.penStarted()
        var navigation = down.type == PointerType.Touch && routing.fingersNavigate(penOnly)
        var one = !navigation && !eraser
        var eraseOne = !navigation && eraser
        var moved = false
        var last = down.position
        var lastPressure = down.pressure
        if (one) onDown(last, lastPressure, activePen)
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
                moved = true
                last = sample.position
                if (eraseOne) onEraserMove(last) else if (one) onMove(last, sample.pressure, true)
            }
            lastPressure = nextPressure
        }
        try {
            while (true) {
                val event = awaitPointerEvent()
                val arrivingPen = event.changes.firstOrNull { it.pressed && (it.type == PointerType.Stylus || it.type == PointerType.Eraser) }
                if (!activePen && arrivingPen != null) {
                    if (one) onCancel()
                    if (eraseOne) onEraserCancel()
                    activeId = arrivingPen.id
                    activePen = true
                    eraser = arrivingPen.type == PointerType.Eraser ||
                        (event.buttons.isPrimaryPressed || event.buttons.isSecondaryPressed)
                    navigation = false
                    one = !eraser
                    eraseOne = eraser
                    last = arrivingPen.position
                    lastPressure = arrivingPen.pressure
                    routing.penStarted()
                    if (one) onDown(last, lastPressure, true)
                    if (eraseOne) onEraserDown(last)
                }
                val active = event.changes.firstOrNull { it.id == activeId }
                // A pen button pressed during ink cancels the provisional stroke before erasing.
                if (activePen && !eraser && (event.buttons.isPrimaryPressed || event.buttons.isSecondaryPressed)) {
                    if (one) onCancel()
                    one = false
                    eraser = true
                    eraseOne = true
                    onEraserDown(last)
                }
                // Touch/palm pointers do not replace or interrupt an active pen or eraser tip.
                if (activePen) {
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
                if (pressed.isEmpty()) {
                    if (active?.isConsumed == true && one) { onCancel(); one = false }
                    break
                }
                if (navigation || routing.cancelForExtraTouch(false, pressed.size)) {
                    if (one) { one = false; onCancel() }
                    val zoom = event.calculateZoom()
                    if (zoom != 1f) doc.viewer.zoomBy(zoom, event.calculateCentroid())
                    val pan = event.calculatePan()
                    if (pan != Offset.Zero) doc.viewer.scrollBy(pan.x, pan.y)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                    continue
                }
                if (!one) continue
                if (active == null || !active.pressed) { onCancel(); one = false; continue }
                if (active.positionChanged()) {
                    if ((active.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                    last = active.position
                    onMove(last, active.pressure, false)
                    active.consume()
                }
            }
            if (eraseOne) onEraserUp(last) else if (one) onUp(last, moved)
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (one) onCancel()
            if (eraseOne) onEraserCancel()
            throw e
        }
    }
}

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
    val strokes = remember { mutableStateListOf<InkStroke>() }
    // A single ordered history keeps mixed draw/lasso/transform gestures undoable in the right order.
    val edits = remember { mutableStateListOf<DraftInkEdit>() }
    val undone = remember { mutableStateListOf<DraftInkEdit>() }
    var current by remember { mutableStateOf<InkStroke?>(null) }
    var lassoPage by remember { mutableStateOf<Int?>(null) }
    val lassoPoints = remember { mutableStateListOf<Offset>() }
    val selected = remember { mutableStateListOf<InkStroke>() }
    var moveAnchor by remember { mutableStateOf<Offset?>(null) }
    var moveOffset by remember { mutableStateOf(Offset.Zero) }
    var moving by remember { mutableStateOf(false) }
    fun clearSelection() { selected.clear(); lassoPage = null; lassoPoints.clear(); moving = false; moveAnchor = null; moveOffset = Offset.Zero }
    val prefs = vm.toolPrefs
    // Capture appearance at pointer down. Adjacent matching strokes may share an annotation.
    DisposableEffect(doc) {
        doc.commitTool = {
            val commands = inkSaveCommands(
                strokes.map { s -> InkSaveStroke(s.page, s.appearance, s.points.toList(), s.pressures.toList(), s.pressureSensitive) },
            ) { argb -> Color(argb).hex() }
            vm.runToolBatch(doc, "comment_add", commands)
            strokes.clear()
            edits.clear()
            undone.clear()
            clearSelection()
        }
        doc.localUndo = LocalUndo(
            // Draw edits are local until committed. The eraser commits them first and then
            // relies on the native document's undo/redo for its one-gesture ink edit.
            canUndo = { edits.isNotEmpty() || doc.info.undo != null },
            canRedo = { undone.isNotEmpty() || doc.info.redo != null },
            undo = {
                if (edits.isNotEmpty()) {
                    val edit = edits.removeAt(edits.lastIndex)
                    clearSelection()
                    applyDraftInkEdit(strokes, edit, forward = false)
                    undone.add(edit)
                } else if (doc.info.undo != null) vm.runTool(doc, "edit_undo")
            },
            redo = {
                if (undone.isNotEmpty()) {
                    val edit = undone.removeAt(undone.lastIndex)
                    clearSelection()
                    applyDraftInkEdit(strokes, edit, forward = true)
                    edits.add(edit)
                } else if (doc.info.redo != null) vm.runTool(doc, "edit_redo")
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
    LaunchedEffect(current) {
        val s = current ?: return@LaunchedEffect
        if (s.shapeMode != InkShapeMode.Freehand || s.appearance.style == InkStyle.Highlighter) return@LaunchedEffect
        while (true) {
            delay(50)
            if (prefs.snapToShape && s.preview == null && System.currentTimeMillis() - movedAt >= SNAP_HOLD_MS) {
                s.preview = ShapeSnap.recognise(s.points.toList())?.outline()
            }
        }
    }
    var eraserPage by remember { mutableStateOf<Int?>(null) }
    val eraserPoints = remember { mutableStateListOf<Offset>() }
    var eraserScreen by remember { mutableStateOf<Offset?>(null) }
    // Snapshot for the duration of a gesture: slider changes never resize an in-flight erase.
    var activeEraserRadius by remember { mutableFloatStateOf(prefs.eraserRadius) }
    // Preview only: hovering never inserts annotation points or touches document history.
    var hoverPosition by remember(doc) { mutableStateOf<Offset?>(null) }
    var hoverEraser by remember(doc) { mutableStateOf(false) }

    fun beginErase(p: Offset) {
        clearSelection()
        val at = doc.viewer.screenToPage(p) ?: return
        // Queue all pending freehand ink first. The native eraser then sees the just-drawn
        // annotations on the same serial engine dispatcher, not a stale UI comment list.
        doc.commitTool?.invoke()
        current = null
        eraserPage = at.page
        activeEraserRadius = prefs.eraserRadius
        eraserScreen = p
        eraserPoints.clear()
        eraserPoints.add(Offset(at.x, at.y))
    }

    fun moveErase(p: Offset) {
        val page = eraserPage ?: return
        val at = doc.viewer.screenToPage(p, nearest = true) ?: return
        if (at.page != page) return
        eraserScreen = p
        val pt = Offset(at.x, at.y)
        if (eraserPoints.lastOrNull() == pt) return
        // Bound native work while preserving every region of a long hand gesture.
        if (eraserPoints.size >= 512) {
            val sampled = eraserPoints.filterIndexed { index, _ -> index % 2 == 0 }
            eraserPoints.clear()
            eraserPoints.addAll(sampled)
        }
        eraserPoints.add(pt)
    }

    fun cancelErase() {
        eraserPage = null
        eraserScreen = null
        eraserPoints.clear()
    }

    fun finishErase(p: Offset) {
        val page = eraserPage ?: return
        moveErase(p)
        val path = eraserPoints.toList()
        cancelErase()
        if (path.isNotEmpty()) {
            // One native Edit::Batch per gesture, including freehand marker Ink.
            vm.runTool(doc, "comment_erase", inkEraseCommand(page, path, activeEraserRadius.toDouble()))
        }
    }

    fun beginLasso(p: Offset) {
        val at = doc.viewer.screenToPage(p) ?: run { clearSelection(); return }
        val existing = selected.firstOrNull()?.page
        if (existing == at.page && selected.isNotEmpty()) {
            val b = inkLassoBounds(selected.map { it.points.toList() })
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
        if (moving) {
            val anchor = moveAnchor ?: return
            val pageSize = doc.viewer.pages.getOrNull(page) ?: return
            moveOffset = boundedInkLassoMove(
                selected.map { stroke -> stroke.points.toList() },
                point - anchor,
                pageSize.width,
                pageSize.height,
            )
        } else {
            addInkLassoPoint(lassoPoints, point)
        }
    }

    fun finishLasso(p: Offset) {
        val page = lassoPage ?: return
        moveLasso(p)
        if (moving) {
            if (moveOffset != Offset.Zero) {
                val edit = DraftInkEdit.Moved(selected.toList(), moveOffset)
                applyDraftInkEdit(strokes, edit, forward = true)
                edits.add(edit)
                undone.clear()
            }
            moving = false
            moveAnchor = null
            moveOffset = Offset.Zero
            return
        }
        val polygon = lassoPoints.toList()
        selected.clear()
        if (polygon.size >= 3) {
            selected.addAll(strokes.filter { it.page == page && inkLassoHitsStroke(polygon, it.points.toList()) })
        }
        if (selected.isEmpty()) clearSelection()
        else lassoPoints.clear()
    }

    Box(
        Modifier.fillMaxSize()
            .pointerInput(doc) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
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
            onDown = { p, pressure, isStylus ->
                if (doc.controller.lassoMode) {
                    beginLasso(p)
                    return@toolGestures
                }
                if (doc.controller.eraserMode) {
                    beginErase(p)
                    return@toolGestures
                }
                clearSelection()
                val at = doc.viewer.screenToPage(p) ?: return@toolGestures
                stillAt = p
                movedAt = System.currentTimeMillis()
                val inkStyle = prefs.inkStyle
                val appearance = InkAppearance(
                    prefs.color(StyledTool.Ink).toArgbInt(),
                    inkStyleOpacity(prefs.opacity(StyledTool.Ink), inkStyle),
                    prefs.inkWidth, prefs.author.ifBlank { null }, inkStyle,
                )
                current = InkStroke(at.page, appearance, isStylus && doc.controller.inkShapeMode == InkShapeMode.Freehand, doc.controller.inkShapeMode).also {
                    it.points.add(Offset(at.x, at.y))
                    it.pressures.add(pressure)
                    strokes.add(it)
                }
            },
            onMove = { p, pressure, _ ->
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
                }
                val at = doc.viewer.screenToPage(p, nearest = true) ?: return@toolGestures
                if (at.page == s.page) {
                    val pos = Offset(at.x, at.y)
                    if (s.shapeMode == InkShapeMode.Freehand) {
                        s.points.add(pos)
                        s.pressures.add(pressure)
                    } else {
                        // Keep only endpoints in Compose state while dragging; native ink gets a
                        // densified outline after lift. A temporary preview never reaches the engine.
                        if (s.points.size == 1) s.points.add(pos) else s.points[1] = pos
                        s.preview = doc.viewer.pages.getOrNull(s.page)?.let { page ->
                            inkShapeOutline(s.shapeMode, s.points.first(), pos, page.width, page.height)
                        }
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
                val s = current
                current = null
                if (s != null) {
                    // A palm/cancelled gesture must not discard a user's redo history.
                    // A tap is an actual dot, not a discarded stroke. Native PDF Ink needs
                    // two points, so represent the dot by a microscopic round-capped segment.
                    if (s.shapeMode == InkShapeMode.Freehand && s.points.size == 1) {
                        val persisted = persistedInkTapPoints(s.points.toList())
                        if (persisted.size == 2) {
                            s.points.add(persisted.last())
                            s.pressures.add(s.pressures.firstOrNull() ?: 1f)
                        }
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
                            vm.runTool(doc, "comment_add", args)
                        }
                        return@toolGestures
                    }
                    if (s.points.size >= 2) {
                        edits.add(DraftInkEdit.Added(s))
                        undone.clear()
                    } else {
                        strokes.remove(s)
                    }
                    // a held shape always snaps; with Snap to shape on, any stroke that is one does
                    val shape = if (s.shapeMode != InkShapeMode.Freehand) {
                        val page = doc.viewer.pages.getOrNull(s.page)
                        if (page != null && s.points.size >= 2) inkShapeOutline(s.shapeMode, s.points.first(), s.points.last(), page.width, page.height) else null
                    } else if (s.appearance.style == InkStyle.Highlighter) null else s.preview // only a held stroke snaps: handwriting (l, 1, o, 0) is never turned into a shape on lift
                    s.preview = null
                    if (s.shapeMode != InkShapeMode.Freehand && shape == null) {
                        strokes.remove(s)
                        edits.remove(DraftInkEdit.Added(s))
                    }
                    if (shape != null) {
                        s.points.clear()
                        s.points.addAll(shape)
                        // Shape snapping deliberately uses the selected, constant pen width.
                        s.pressureSensitive = false
                        s.pressures.clear()
                        s.pressures.addAll(List(shape.size) { 1f })
                    }
                }
            },
            onCancel = {
                if (doc.controller.lassoMode) {
                    if (moving) {
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
        ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            for (s in strokes) {
                val pageRect = doc.viewer.pageRectOnScreen(s.page)
                val lift = if (moving && s in selected) moveOffset else Offset.Zero
                drawInkStroke(
                    runs = s.paths.runs(s),
                    color = Color(s.appearance.argb),
                    opacity = s.appearance.opacity,
                    highlighter = s.appearance.style == InkStyle.Highlighter,
                    origin = doc.viewer.pageToScreen(s.page, lift.x, lift.y),
                    scale = doc.viewer.pixelsPerPoint(s.page),
                    pageBoundsOnScreen = pageRect,
                )
            }
            // A predicted tail on the stroke being drawn (pen only): where the pen will be about
            // one frame from now, from its real speed. Drawn only, never saved; the next real
            // sample replaces it.
            current?.takeIf { it.pressureSensitive && it.shapeMode == InkShapeMode.Freehand && it.preview == null }?.let { s ->
                val last = s.points.lastOrNull() ?: return@let
                val (dx, dy) = StylusLog.pen.predictedOffset(android.os.SystemClock.uptimeMillis()) ?: return@let
                val from = doc.viewer.pageToScreen(s.page, last.x, last.y)
                val width = inkStyleWidthRuns(listOf(s.pressures.lastOrNull() ?: 1f, s.pressures.lastOrNull() ?: 1f), s.appearance.width, true, s.appearance.style)
                    .firstOrNull()?.width ?: s.appearance.width
                drawLine(
                    Color(s.appearance.argb).copy(alpha = s.appearance.opacity),
                    from, Offset(from.x + dx, from.y + dy),
                    strokeWidth = width * doc.viewer.pixelsPerPoint(s.page),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
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
                    val bounds = inkLassoBounds(selected.map { it.points.toList() })
                    if (bounds != null) {
                        val a = doc.viewer.pageToScreen(page, bounds.left + moveOffset.x, bounds.top + moveOffset.y)
                        val b = doc.viewer.pageToScreen(page, bounds.right + moveOffset.x, bounds.bottom + moveOffset.y)
                        drawRect(Color(0xFF1A85CA).copy(alpha = 0.12f), a, Size(b.x - a.x, b.y - a.y))
                        drawRect(Color(0xFF1A85CA), a, Size(b.x - a.x, b.y - a.y), style = Stroke(width = 1.5.dp.toPx()))
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
            val hovering = hoverPosition
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
    }
}

/** A gesture only holds coordinates; no native query runs on pointer down. */
private class MarkupGesture(val start: PagePoint, val downScreen: Offset) {
    var latest by mutableStateOf(start)
    var moved by mutableStateOf(false)
}

/**
 * Resolve both word endpoints and their rectangles on the native serial dispatcher.
 * A tap marks its whole word; a drag snaps to word boundaries in either direction.
 * The engine's wordAt has a bounded glyph hit box, unlike unrestricted nearestGlyph.
 */
private suspend fun resolveMarkup(
    eng: Engine,
    doc: OpenDocument,
    start: PagePoint,
    end: PagePoint,
    slopPoints: Float,
    tap: Boolean,
): MarkupDrag? = withContext(eng.commands) {
    if (start.page != end.page) return@withContext null
    val page = start.page.toUInt()
    val anchor = eng.session.wordAt(doc.id, page, start.x, start.y, slopPoints) ?: return@withContext null
    // A drag ending slightly outside the last line still targets its nearest word.
    // A tap deliberately does not use nearestGlyph: blank space must stay blank.
    val focus = if (tap) anchor else {
        eng.session.wordAt(doc.id, page, end.x, end.y, slopPoints) ?: run {
            val glyph = eng.session.nearestGlyph(doc.id, page, end.x, end.y) ?: return@withContext null
            val rect = eng.session.glyphRect(doc.id, page, glyph).toList()
            if (!markupEndpointNearGlyph(end.x, end.y, rect, (slopPoints * 2f).coerceAtMost(40f)))
                return@withContext null
            val centerX = (minOf(rect[0], rect[2]) + maxOf(rect[0], rect[2])) / 2f
            val centerY = (minOf(rect[1], rect[3]) + maxOf(rect[1], rect[3])) / 2f
            eng.session.wordAt(doc.id, page, centerX, centerY, 0.1f)
        }
    }
    val range = markupWordRange(anchor, focus) ?: return@withContext null
    val rects = markupLineRects(eng.session.textRects(doc.id, range).toList())
    if (rects.isEmpty()) null else MarkupDrag(range, rects)
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
    val marker = doc.controller.markup
    val color = vm.toolPrefs.color(marker.styled)
    val opacity = vm.toolPrefs.opacity(marker.styled)
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
                pendingTap = screen
                val start = doc.viewer.screenToPage(screen) ?: return@toolGestures
                val gesture = MarkupGesture(start, screen)
                active = gesture
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
                val selectedColor = vm.toolPrefs.color(selectedMarkup.styled)
                val selectedOpacity = vm.toolPrefs.opacity(selectedMarkup.styled)
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
                        val selected = resolveMarkup(eng, doc, gesture.start, end, radius, tap)
                        if (vm.document !== doc || !doc.info.allowsAnnotation) return@launch
                        if (selected != null) {
                            if (requestSerial == serial) {
                                drag = selected
                                pendingTap = null
                            }
                            queued = true
                            vm.addMarkup(
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
                            )
                        } else if (tap) {
                            val count = withContext(eng.commands) { eng.session.glyphCount(doc.id, page.toUInt()) }
                            if (count == 0u && vm.document === doc) vm.tell(text(R.string.toolbar_no_text_to_mark))
                        }
                    } catch (e: MobileException) {
                        if (vm.document === doc) vm.tell(e.userMessage)
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

/** Which property picker a sub-toolbar has open. */
enum class Picker { None, Color, Width, TextSize, Presets, EraserSize, Stamp }

/** The active tool's sub-toolbar. */
@Composable
fun ToolSubToolbar(vm: AppViewModel, doc: OpenDocument) {
    val ctl = doc.controller
    val prefs = vm.toolPrefs
    val picker = ctl.picker
    fun toggle(p: Picker) {
        ctl.picker = if (ctl.picker == p) Picker.None else p
    }
    when (ctl.tool) {
        ViewerTool.Markup -> SubToolbar {
            SubTool(LucideIcons.Highlighter, stringResource(R.string.common_tool_highlight), ctl.markup == TextMarkup.Highlight, { ctl.markup = TextMarkup.Highlight })
            SubTool(LucideIcons.Underline, stringResource(R.string.common_tool_underline), ctl.markup == TextMarkup.Underline, { ctl.markup = TextMarkup.Underline })
            SubTool(LucideIcons.Strikethrough, stringResource(R.string.toolbar_strikethrough), ctl.markup == TextMarkup.Strikethrough, { ctl.markup = TextMarkup.Strikethrough })
            SubTool(LucideIcons.Spline, stringResource(R.string.toolbar_squiggly), ctl.markup == TextMarkup.Squiggly, { ctl.markup = TextMarkup.Squiggly })
            SubDivider()
            ColorDot(prefs.color(ctl.markup.styled), picker == Picker.Color) { toggle(Picker.Color) }
        }
        ViewerTool.Draw -> DrawToolbar(vm, doc)
        ViewerTool.Comment -> SubToolbar {
            SubTool(LucideIcons.MessageSquareText, stringResource(R.string.toolbar_text_comment), ctl.commentMode == CommentMode.Text, { ctl.commentMode = CommentMode.Text })
            SubTool(LucideIcons.MicAudioLines, stringResource(R.string.toolbar_voice_comment), ctl.commentMode == CommentMode.Voice, { ctl.commentMode = CommentMode.Voice })
            SubTool(LucideIcons.Stamp, stringResource(R.string.toolbar_stamp), ctl.commentMode == CommentMode.Stamp, {
                ctl.commentMode = CommentMode.Stamp
                ctl.picker = Picker.Stamp
            })
            if (ctl.commentMode == CommentMode.Stamp) {
                SubDivider()
                SubProperty(LucideIcons.SlidersHorizontal, stringResource(R.string.toolbar_stamp_choose), picker == Picker.Stamp, { toggle(Picker.Stamp) })
            }
        }
        ViewerTool.Text -> SubToolbar {
            SubTool(LucideIcons.Type, stringResource(R.string.toolbar_text_box), !ctl.textCallout, { ctl.textCallout = false })
            SubTool(LucideIcons.MessageSquareQuote, stringResource(R.string.toolbar_callout), ctl.textCallout, { ctl.textCallout = true })
            SubDivider()
            SubProperty(LucideIcons.ALargeSmall, stringResource(R.string.toolbar_text_size), picker == Picker.TextSize, { toggle(Picker.TextSize) })
            ColorDot(prefs.color(StyledTool.FreeText), picker == Picker.Color) { toggle(Picker.Color) }
        }
        ViewerTool.FillSign -> FillSignToolbar(vm, doc)
        ViewerTool.Edit -> EditToolbar(vm, doc)
        ViewerTool.None -> Unit
    }
}

/**
 * The active tool's sheets (colour, thickness, text size; Fill & Sign's Sign panel), drawn over
 * the whole viewer rather than inside the bottom bar.
 */
@Composable
fun ToolSheets(vm: AppViewModel, doc: OpenDocument) {
    val ctl = doc.controller
    val styled = when (ctl.tool) {
        ViewerTool.Markup -> ctl.markup.styled
        ViewerTool.Draw -> StyledTool.Ink
        ViewerTool.Text -> StyledTool.FreeText
        else -> null
    }
    SheetHost(ctl.picker.takeIf { it != Picker.None && styled != null }) { p ->
        PropertyPicker(vm, styled ?: StyledTool.Highlight, p, ctl.markup) {
            ctl.picker = Picker.None
            // Pen selection works even after the eraser/lasso or shape tool was active.
            if (p == Picker.Presets && ctl.tool == ViewerTool.Draw) {
                ctl.eraserMode = false
                ctl.lassoMode = false
                ctl.inkShapeMode = InkShapeMode.Freehand
            }
        }
    }
    if (ctl.tool == ViewerTool.FillSign && ctl.signPanel) SignPanel(vm, doc) { ctl.signPanel = false }
    if (ctl.tool == ViewerTool.Comment && ctl.picker == Picker.Stamp) StampPicker(ctl) { ctl.picker = Picker.None }
}

/** The colour, thickness or text-size picker of a tool, as a sheet above its sub-toolbar. */
@Composable
fun PropertyPicker(vm: AppViewModel, tool: StyledTool, picker: Picker, markup: TextMarkup, onDismiss: () -> Unit) {
    val prefs = vm.toolPrefs
    val c = pdfColors
    // over the tool's bar, which stays in view with the picker's button shaded
    PdfSheet(onDismiss = onDismiss, modifier = Modifier.padding(bottom = (if (tool == StyledTool.Ink) DrawToolbarHeight else BottomBarHeight) + 1.dp).navigationBarsPadding(), bottomBand = false) {
        when (picker) {
            Picker.Color -> {
                val title = stringResource(
                    when (tool) {
                        StyledTool.Highlight -> R.string.toolbar_color_highlight
                        StyledTool.Underline -> R.string.toolbar_color_underline
                        StyledTool.Strikethrough -> R.string.toolbar_color_strikethrough
                        StyledTool.Squiggly -> R.string.toolbar_color_squiggly
                        StyledTool.Ink -> R.string.toolbar_color_drawing
                        StyledTool.FreeText -> R.string.toolbar_color_text
                        StyledTool.Note -> R.string.toolbar_color_comment
                    },
                )
                Text(title, color = c.text, fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.padding(start = 20.dp, top = 3.dp, bottom = 18.dp))
                ColorGrid(prefs.color(tool)) { prefs.setColor(tool, it) }
                if (tool != StyledTool.FreeText) {
                    Text(stringResource(R.string.toolbar_opacity), color = c.text, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.padding(start = 78.dp, top = 20.dp, bottom = 8.dp))
                    OpacitySlider(prefs.color(tool), prefs.opacity(tool)) { prefs.setOpacity(tool, it) }
                }
                Spacer(Modifier.height(30.dp))
            }
            Picker.Width -> {
                Text(stringResource(R.string.toolbar_line_thickness), color = c.text, fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.padding(start = 20.dp, bottom = 12.dp))
                InkWidthRow(prefs.color(StyledTool.Ink), prefs.inkWidth) { prefs.updateInkWidth(it) }
            }
            Picker.TextSize -> {
                Text(stringResource(R.string.toolbar_text_size), color = c.text, fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.padding(start = 20.dp, bottom = 4.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.toolbar_text_size_pt, prefs.textSize.toInt()), color = c.text, fontSize = 15.sp, modifier = Modifier.width(56.dp))
                    Slider(
                        value = prefs.textSize,
                        onValueChange = { v -> prefs.updateTextSize((Math.round(v / 2f) * 2f).coerceIn(6f, 22f)) },
                        valueRange = 6f..22f,
                        steps = TextSizes.size - 2,
                        colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent),
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            Picker.Presets -> InkPresetsPicker(vm, onDismiss)
            Picker.EraserSize -> {
                Text(
                    stringResource(R.string.ink_eraser_size_title),
                    color = c.text,
                    fontSize = 17.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 20.dp, bottom = 12.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.ink_eraser_diameter, (prefs.eraserRadius * 2f).toInt()),
                        color = c.text,
                        fontSize = 15.sp,
                        modifier = Modifier.width(90.dp),
                    )
                    Slider(
                        value = prefs.eraserRadius,
                        onValueChange = { prefs.updateEraserRadius(it) },
                        valueRange = ERASER_RADIUS_MIN..ERASER_RADIUS_MAX,
                        colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent),
                        modifier = Modifier.weight(1f),
                    )
                }
                Box(Modifier.fillMaxWidth().height(78.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size((prefs.eraserRadius * 2f).dp)
                            .border(2.dp, c.toolAccent, RoundedCornerShape(50)),
                    )
                }
                Text(
                    stringResource(R.string.ink_eraser_size_help),
                    color = c.label,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                Spacer(Modifier.height(12.dp))
            }
            Picker.None, Picker.Stamp -> Unit
        }
    }
}

/** The seven line widths as bars of growing thickness, the chosen one framed. */
@Composable
fun InkWidthRow(color: Color, selected: Float?, onPick: (Float) -> Unit) {
    val c = pdfColors
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.Center) {
        InkWidths.forEachIndexed { i, w ->
            val description = stringResource(R.string.toolbar_stroke_width, i + 1)
            Box(
                Modifier.size(48.dp)
                    .then(if (selected == w) Modifier.border(1.5.dp, c.accent, RoundedCornerShape(4.dp)) else Modifier)
                    .clickable(role = Role.RadioButton) { onPick(w) }
                    .semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(32.dp)) { drawCircle(color, radius = (1.5f + w / 2f).dp.toPx(), center = center) }
            }
        }
    }
}

/** The 18 colour swatches: 32 dp rounded squares 18 dp apart, the chosen one ringed and checked. */
@Composable
fun ColorGrid(selected: Color, onPick: (Color) -> Unit) {
    val c = pdfColors
    val ring = c.toolAccent
    Column(Modifier.fillMaxWidth().padding(horizontal = 51.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        CommentPalette.chunked(6).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                row.forEach { (color, name) ->
                    val label = stringResource(name)
                    val isSel = color.toArgbInt() == selected.toArgbInt()
                    // only a swatch the colour of the sheet itself needs an outline to be seen
                    val outlined = kotlin.math.abs(color.luminance() - c.surface.luminance()) < 0.08f
                    Box(
                        Modifier.size(32.dp)
                            .drawBehind {
                                if (isSel) {
                                    val w = 2.dp.toPx()
                                    val out = 4.dp.toPx()
                                    drawRoundRect(
                                        ring, topLeft = Offset(-out + w / 2, -out + w / 2), size = Size(size.width + 2 * out - w, size.height + 2 * out - w),
                                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(w),
                                    )
                                }
                            }
                            .clip(RoundedCornerShape(3.dp))
                            .background(color)
                            .then(if (outlined) Modifier.border(1.dp, c.divider, RoundedCornerShape(3.dp)) else Modifier)
                            .clickable(role = Role.RadioButton) { onPick(color) }
                            .semantics { contentDescription = label },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSel) Icon(LucideIcons.Check, null, tint = if (color.luminance() > 0.5f) Color.Black else Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

/**
 * Opacity from 25 % to 100 %: a 35 dp checkered track (12 dp squares) washed with the tool's colour,
 * and a round thumb in that colour, as the reference draws it. Tap or drag along it.
 */
@Composable
fun OpacitySlider(color: Color, value: Float, onChange: (Float) -> Unit) {
    val min = 0.25f
    val sheet = pdfColors.surface
    val description = stringResource(R.string.toolbar_opacity_percent, (value * 100).toInt())
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 78.dp).height(35.dp)
            .pointerInput(Unit) {
                fun at(x: Float) = onChange((min + (x / size.width).coerceIn(0f, 1f) * (1f - min)))
                detectTapGestures { at(it.x) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ -> onChange((min + (change.position.x / size.width).coerceIn(0f, 1f) * (1f - min))) }
            }
            .semantics { contentDescription = description },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val sq = 12.dp.toPx()
            clipPath(androidx.compose.ui.graphics.Path().apply {
                addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())))
            }) {
                var y = 0f
                var r = 0
                while (y < size.height) {
                    var x = 0f
                    var col = 0
                    while (x < size.width) {
                        drawRect(if ((col + r) % 2 == 0) Color(0xFF8A8A8A) else Color(0xFFE6E6E6), Offset(x, y), Size(sq, sq))
                        x += sq
                        col++
                    }
                    y += sq
                    r++
                }
                drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(color.copy(alpha = min), color)), size = size)
            }
            val cx = (value - min) / (1f - min) * size.width
            val radius = 10.5.dp.toPx()
            // a thin white ring, a gap in the sheet's colour, then the colour itself
            drawCircle(Color.White, radius, Offset(cx, size.height / 2))
            drawCircle(sheet, radius - 1.dp.toPx(), Offset(cx, size.height / 2))
            drawCircle(color.copy(alpha = 1f), radius - 3.dp.toPx(), Offset(cx, size.height / 2))
        }
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

/** The standard stamps, as a list above the Comment tool's bar; picking one closes it. */
@Composable
private fun StampPicker(ctl: ViewerController, onDismiss: () -> Unit) {
    val c = pdfColors
    PdfSheet(onDismiss = onDismiss, modifier = Modifier.padding(bottom = BottomBarHeight + 1.dp).navigationBarsPadding(), bottomBand = false) {
        Text(
            stringResource(R.string.toolbar_stamp_choose), color = c.text, fontSize = 17.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            modifier = Modifier.padding(start = 20.dp, top = 3.dp, bottom = 10.dp),
        )
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            for (stamp in STANDARD_STAMPS) {
                val selected = ctl.stamp == stamp
                Text(
                    stampLabel(stamp),
                    color = if (selected) c.toolAccent else c.text,
                    fontSize = 16.sp,
                    fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.SemiBold else null,
                    modifier = Modifier.fillMaxWidth()
                        .clickable(role = Role.RadioButton) {
                            ctl.stamp = stamp
                            onDismiss()
                        }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
