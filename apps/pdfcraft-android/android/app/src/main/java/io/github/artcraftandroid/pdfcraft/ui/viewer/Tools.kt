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
    /** What a pen-down with the side button held does (#144); the eraser tip always erases. */
    penButtonHold: PenButtonHold = PenButtonHold.Eraser,
    /** Called before [onDown] with whether this stroke is a temporary highlighter (button held). */
    onPenDownMode: (PenDownMode) -> Unit = {},
    /** True for a finger touch that the writing-hand guard rejects (#156); it then neither draws nor scrolls. */
    rejectTouch: ((Offset, Long) -> Boolean)? = null,
    /** A quick tap with two or three fingers and no pan or zoom (#189); null turns it off. */
    onMultiFingerTap: ((fingers: Int) -> Unit)? = null,
): Modifier = pointerInput(doc, penOnly, penButtonHold, onMultiFingerTap != null, rejectTouch != null) {
    val routing = StylusRouting()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val stylus = down.type == PointerType.Stylus
        var activePen = stylus || down.type == PointerType.Eraser
        var activeId = down.id
        // Some pens report an eraser tip; other Android devices expose a stylus button. The mode
        // is decided at contact and kept until lift, whatever the button does mid-stroke (#82, #144).
        var downMode = penDownMode(
            down.type == PointerType.Eraser,
            stylus && (currentEvent.buttons.isPrimaryPressed || currentEvent.buttons.isSecondaryPressed),
            penButtonHold,
        )
        var eraser = downMode == PenDownMode.Erase
        if (activePen) routing.penStarted()
        // A palm resting beside a hovering or writing pen is ignored, though a pen may still arrive.
        val palm = down.type == PointerType.Touch && rejectTouch?.invoke(down.position, down.uptimeMillis) == true
        var navigation = !palm && down.type == PointerType.Touch && routing.fingersNavigate(penOnly)
        var one = !palm && !navigation && !eraser
        var eraseOne = !palm && !navigation && eraser
        var moved = false
        var last = down.position
        var lastPressure = down.pressure
        var maxTouches = 1
        var touchTravel = 0f
        var zoomDrift = 0f
        var endTime = down.uptimeMillis
        if (one) { onPenDownMode(downMode); onDown(last, lastPressure, activePen) }
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
                    downMode = penDownMode(
                        arrivingPen.type == PointerType.Eraser,
                        event.buttons.isPrimaryPressed || event.buttons.isSecondaryPressed,
                        penButtonHold,
                    )
                    eraser = downMode == PenDownMode.Erase
                    navigation = false
                    one = !eraser
                    eraseOne = eraser
                    last = arrivingPen.position
                    lastPressure = arrivingPen.pressure
                    routing.penStarted()
                    if (one) { onPenDownMode(downMode); onDown(last, lastPressure, true) }
                    if (eraseOne) onEraserDown(last)
                }
                val active = event.changes.firstOrNull { it.id == activeId }
                // The side button is read only at contact: pressing it mid-stroke never cancels,
                // splits or erases the stroke being written (#82, #144).
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
                maxTouches = maxOf(maxTouches, pressed.size)
                event.changes.forEach { endTime = maxOf(endTime, it.uptimeMillis) }
                if (pressed.isEmpty()) {
                    if (active?.isConsumed == true && one) { onCancel(); one = false }
                    break
                }
                if (palm) {
                    event.changes.forEach { if (it.type == PointerType.Touch) it.consume() }
                    continue
                }
                if (navigation || routing.cancelForExtraTouch(false, pressed.size)) {
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
                    if ((active.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                    last = active.position
                    onMove(last, active.pressure, false)
                    active.consume()
                }
            }
            if (eraseOne) onEraserUp(last) else if (one) onUp(last, moved)
            if (!activePen && !palm && onMultiFingerTap != null) {
                multiFingerTapFingers(maxTouches, endTime - down.uptimeMillis, touchTravel + if (moved) Float.MAX_VALUE else 0f, zoomDrift, viewConfiguration.touchSlop)
                    ?.let(onMultiFingerTap)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (one) onCancel()
            if (eraseOne) onEraserCancel()
            throw e
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
    // Drafts live with the document (#73): a save refused after Draw was left keeps them there.
    val drafts = doc.ink
    val strokes = drafts.strokes
    val edits = drafts.edits
    val undone = drafts.undone
    // Which of the document's undo steps this Draw session made (#78).
    val steps = remember(doc) { DrawUndoSteps() }
    var current by remember { mutableStateOf<InkStroke?>(null) }
    var lassoPage by remember { mutableStateOf<Int?>(null) }
    val lassoPoints = remember { mutableStateListOf<Offset>() }
    val selected = remember { mutableStateListOf<InkStroke>() }
    // Saved drawings and shapes the lasso selected on lassoPage (moved, recoloured or deleted natively).
    val savedSelected = remember { mutableStateListOf<io.github.artcraftandroid.pdfcraft.core.CommentInfo>() }
    var moveAnchor by remember { mutableStateOf<Offset?>(null) }
    var moveOffset by remember { mutableStateOf(Offset.Zero) }
    var moving by remember { mutableStateOf(false) }
    fun clearSelection() { selected.clear(); savedSelected.clear(); lassoPage = null; lassoPoints.clear(); moving = false; moveAnchor = null; moveOffset = Offset.Zero }
    /** Every selected outline in page points: draft stroke points and saved comments' rectangles. */
    fun selectionPaths(): List<List<Offset>> = selected.map { it.points.toList() } + savedSelected.mapNotNull { lassoRectOutline(it.rect) }
    /** A Draw edit of the document; [steps] learns whether it became an undo step. */
    fun documentEdit(name: String, args: Map<String, Any?>) {
        steps.started()
        var made = 0
        // A refused edit or a no-op (an eraser that met no ink) answers without the document summary.
        vm.runTool(doc, name, args, onSettled = { steps.finished(made) }) { r -> if (r.has("doc")) made = 1 }
    }
    fun savedEdit(page: Int, extra: Map<String, Any?>) {
        val indices = savedSelected.map { it.index.toInt() + 1 }
        if (indices.isEmpty()) return
        documentEdit("comments_edit_many", mapOf("page" to page + 1, "comments" to indices) + extra)
    }
    val prefs = vm.toolPrefs
    // Capture appearance at pointer down. Every stroke is saved as its own annotation (#72).
    DisposableEffect(doc) {
        doc.commitTool = {
            val taken = drafts.takeForSaving()
            clearSelection()
            if (taken.isNotEmpty()) {
                val groups = inkSaveCommandGroups(
                    taken.map { s -> InkSaveStroke(s.page, s.appearance, s.points.toList(), s.pressures.toList(), s.pressureSensitive) },
                ) { argb -> Color(argb).hex() }
                steps.started()
                // The drafts stay drawn until the engine has them; the ones it refuses stay drafts (#73).
                vm.runToolBatch(doc, "comment_add", groups) { applied ->
                    val kept = drafts.settle(taken, applied)
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
            canRedo = {
                steps.idle && (undone.isNotEmpty() || steps.canRedoDocument(doc.info.redo != null))
            },
            undo = undo@{
                if (!steps.idle) return@undo
                if (edits.isNotEmpty()) {
                    val edit = edits.removeAt(edits.lastIndex)
                    clearSelection()
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
    var buttonHighlighter by remember(doc) { mutableStateOf(false) }
    val penTracker = remember(doc) { PenTracker() }
    val guardSizePx = with(LocalDensity.current) { prefs.palmGuardSizeDp.dp.toPx() }
    val buttonClick = remember(doc) { PenButtonClickDetector() }

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
            documentEdit("comment_erase", inkEraseCommand(page, path, activeEraserRadius.toDouble()))
        }
    }

    fun beginLasso(p: Offset) {
        val at = doc.viewer.screenToPage(p) ?: run { clearSelection(); return }
        val existing = lassoPage
        if (existing == at.page && (selected.isNotEmpty() || savedSelected.isNotEmpty())) {
            val b = inkLassoBounds(selectionPaths())
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
                selectionPaths(),
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
                    savedEdit(page, mapOf("move" to listOf(moveOffset.x.toDouble(), moveOffset.y.toDouble())))
                    savedSelected.clear()
                    if (selected.isEmpty()) lassoPage = null
                }
            }
            moving = false
            moveAnchor = null
            moveOffset = Offset.Zero
            return
        }
        val polygon = lassoPoints.toList()
        selected.clear()
        savedSelected.clear()
        if (polygon.size >= 3) {
            selected.addAll(strokes.filter { it.page == page && inkLassoHitsStroke(polygon, it.points.toList()) })
            savedSelected.addAll(
                doc.comments.filter { c ->
                    c.page.toInt() == page && savedLassoTarget(c.subtype, c.locked, c.fillSign, c.replyTo) && inkLassoHitsRect(polygon, c.rect)
                },
            )
        }
        if (selected.isEmpty() && savedSelected.isEmpty()) clearSelection()
        else lassoPoints.clear()
    }

    Box(
        Modifier.fillMaxSize()
            .pointerInput(doc) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.firstOrNull { it.type == PointerType.Stylus || it.type == PointerType.Eraser }
                            ?.let { penTracker.seen(it.position, it.uptimeMillis) }
                        val hoverPen = event.changes.firstOrNull { !it.pressed && it.type == PointerType.Stylus }
                        if (event.type == PointerEventType.Press || event.type == PointerEventType.Exit || hoverPen == null) {
                            buttonClick.reset()
                        } else if (buttonClick.hover(event.buttons.isPrimaryPressed || event.buttons.isSecondaryPressed, hoverPen.uptimeMillis)) {
                            // A side-button click while hovering, with no stroke (#144).
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
                val at = doc.viewer.screenToPage(p) ?: return@toolGestures
                stillAt = p
                movedAt = System.currentTimeMillis()
                // Holding the side button can draw with the highlighter for this stroke only (#144).
                val inkStyle = if (buttonHighlighter) InkStyle.Highlighter else prefs.inkStyle
                val pen = prefs.penSettings(inkStyle)
                val appearance = InkAppearance(
                    pen.argb,
                    inkStyleOpacity(pen.opacity, inkStyle),
                    pen.width, prefs.author.ifBlank { null }, inkStyle,
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
                            documentEdit("comment_add", args)
                        }
                        return@toolGestures
                    }
                    if (s.points.size >= 2) {
                        edits.add(DraftInkEdit.Added(s))
                        undone.clear()
                        steps.drafted()
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
            penButtonHold = prefs.penButtonHold,
            rejectTouch = if (prefs.palmGuard) ({ p, t ->
                palmGuardRejects(penTracker.position, penTracker.timeMs, p, t, prefs.leftHanded, guardSizePx)
            }) else null,
            onPenDownMode = { mode -> buttonHighlighter = mode == PenDownMode.Highlight },
        ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // Strokes being saved stay drawn until the document has them, under the newer drafts.
            for (s in drafts.saving + strokes) {
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
                    // Saved comments do not move until the finger lifts: their frames follow it.
                    for (c in savedSelected) {
                        val o = lassoRectOutline(c.rect) ?: continue
                        val a = doc.viewer.pageToScreen(page, o[0].x + moveOffset.x, o[0].y + moveOffset.y)
                        val b = doc.viewer.pageToScreen(page, o[2].x + moveOffset.x, o[2].y + moveOffset.y)
                        drawRect(Color(0xFF1A85CA).copy(alpha = 0.6f), a, Size(b.x - a.x, b.y - a.y), style = Stroke(width = 1.dp.toPx()))
                    }
                    val bounds = inkLassoBounds(selectionPaths())
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
        if (doc.controller.lassoMode && selPage != null && !moving && (selected.isNotEmpty() || savedSelected.isNotEmpty())) {
            val b = inkLassoBounds(selectionPaths())
            if (b != null) {
                val anchor = doc.viewer.pageToScreen(selPage, b.left, b.bottom)
                val density = LocalDensity.current
                Row(
                    Modifier
                        .offset { androidx.compose.ui.unit.IntOffset(anchor.x.toInt().coerceAtLeast(0), (anchor.y + with(density) { 8.dp.toPx() }).toInt()) }
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
                        savedEdit(selPage, mapOf("delete" to true))
                        clearSelection()
                    })
                    if (savedSelected.isNotEmpty()) {
                        SubTool(LucideIcons.Palette, stringResource(R.string.ink_lasso_recolor), false, {
                            savedEdit(selPage, mapOf("color" to prefs.color(StyledTool.Ink).hex()))
                            clearSelection()
                        })
                    }
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
enum class Picker { None, Color, Width, TextSize, Presets, PenStyle, EraserSize, Stamp }

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
            Picker.PenStyle -> PenStylePicker(vm)
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
                    .selectable(selected = selected == w, role = Role.RadioButton) { onPick(w) }
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
                            .selectable(selected = isSel, role = Role.RadioButton) { onPick(color) }
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
            .semantics { contentDescription = description }
            // 5% steps from 25% to 100%: TalkBack's swipe up/down and Switch Access can change it.
            .rangeSemantics(value, min..1f, steps = 14, state = description, onChange = onChange),
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
                        .selectable(selected = selected, role = Role.RadioButton) {
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
