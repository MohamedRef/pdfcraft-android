package io.github.artcraftandroid.pdfcraft.ui.scan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import io.github.artcraftandroid.pdfcraft.ui.viewer.OptionChips
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.BarIcon
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.ScanFilter
import io.github.artcraftandroid.pdfcraft.ui.ScanPage
import io.github.artcraftandroid.pdfcraft.ui.pdfColors

/*
 * The Markup screen (#495): the page full screen with a row of tools. One finger rubs or draws,
 * two fingers move and zoom, as in the viewer's Draw. The engine draws the strokes on the page
 * after each one ends (so the eraser's paper is the engine's own), while the stroke being drawn
 * and those not drawn yet are shown on top; text boxes are shown here, from the same mask the
 * engine draws, so they can be moved.
 */

/** The Markup screen's tools, in the order of its bar. */
internal enum class ScanMarkupTool { Erase, Pen, Highlighter, Text,
    /** Takes back the person's own pen, highlighter and Color strokes where it rubs (#520); with Markup's ink on. */
    RemoveMarks,
}

/** The three sizes of each tool, as shares of the page's longer side (a text's letter height). */
internal fun scanMarkupSizes(tool: ScanMarkupTool): List<Float> = when (tool) {
    ScanMarkupTool.Erase, ScanMarkupTool.RemoveMarks -> listOf(0.02f, 0.04f, 0.08f)
    ScanMarkupTool.Pen -> listOf(0.0025f, 0.005f, 0.009f)
    ScanMarkupTool.Highlighter -> listOf(0.015f, 0.025f, 0.04f)
    ScanMarkupTool.Text -> listOf(0.018f, 0.026f, 0.04f)
}

/** Each tool's own swatches (0xRRGGBB); the eraser's Color fill has [SCAN_ERASE_COLOURS] (#503). */
internal fun scanMarkupColours(tool: ScanMarkupTool): List<Int> = when (tool) {
    ScanMarkupTool.Erase, ScanMarkupTool.RemoveMarks -> emptyList()
    ScanMarkupTool.Pen -> listOf(0x000000, 0x1565C0, 0xC62828, 0x2E7D32)
    ScanMarkupTool.Highlighter -> listOf(0xFFEB3B, 0x8BC34A, 0xF48FB1, 0x81D4FA)
    ScanMarkupTool.Text -> listOf(0x000000, 0x1565C0, 0xC62828)
}

/**
 * The tools offered on a page in [filter], in the bar's order: a black-and-white page has no
 * highlighter (it has no grey to multiply); Remove marks, beside Erase, only with Markup's [ink] (#520).
 */
internal fun scanMarkupTools(filter: ScanFilter, ink: Boolean = false): List<ScanMarkupTool> =
    listOf(ScanMarkupTool.Erase, ScanMarkupTool.RemoveMarks, ScanMarkupTool.Pen, ScanMarkupTool.Highlighter, ScanMarkupTool.Text)
        .filter { it != ScanMarkupTool.Highlighter || filter != ScanFilter.LightText }
        .filter { it != ScanMarkupTool.RemoveMarks || ink }

/** Undo and Redo of the Markup screen: whole states, so any change goes back in one step. */
internal class ScanMarkupHistory(start: ScanMarks) {
    var now: ScanMarks = start
        private set
    private val back = ArrayList<ScanMarks>()
    private val forward = ArrayList<ScanMarks>()
    val canUndo: Boolean get() = back.isNotEmpty()
    val canRedo: Boolean get() = forward.isNotEmpty()

    fun change(to: ScanMarks) {
        if (to == now) return
        back += now
        forward.clear()
        now = to
    }

    fun undo() {
        if (back.isEmpty()) return
        forward += now
        now = back.removeAt(back.lastIndex)
    }

    fun redo() {
        if (forward.isEmpty()) return
        back += now
        now = forward.removeAt(forward.lastIndex)
    }
}

/** The strokes of [now] still to be drawn over an engine drawing made of [drawn]: none when [drawn] is not where [now] started. */
internal fun scanMarkupPending(drawn: List<ScanStrokeMark>?, now: List<ScanStrokeMark>): List<ScanStrokeMark> {
    if (drawn == null || drawn.size > now.size) return emptyList()
    for (i in drawn.indices) if (drawn[i] != now[i]) return emptyList()
    return now.drop(drawn.size)
}

/**
 * Where the page is on the screen: [left], [top] and its drawn [width] and [height] in pixels, the
 * page shown turned [frameTurn] quarter turns from the marks' own frame.
 */
internal class PageRect(val left: Float, val top: Float, val width: Float, val height: Float, val frameTurn: Int) {
    val long: Float get() = maxOf(width, height)

    /** A screen point as a share point of the marks' frame. */
    fun toMarks(p: Offset): Pair<Float, Float> = scanTurnShare((p.x - left) / width, (p.y - top) / height, (4 - frameTurn).mod(4))

    /** A share point of the marks' frame on the screen. */
    fun toScreen(x: Float, y: Float): Offset = scanTurnShare(x, y, frameTurn).let { (sx, sy) -> Offset(left + sx * width, top + sy * height) }

    /** The marks' frame's width and height on the screen (turned back). */
    val frameSize: Size get() = if (frameTurn % 2 == 1) Size(height, width) else Size(width, height)
}

@Composable
internal fun ScanMarkupScreen(vm: AppViewModel, index: Int, page: ScanPage) {
    val c = pdfColors
    val lab = vm.scanLab.options
    // Markup's ink (#520): the engine draws the page only for the eraser's paper and edge fills
    val ink = lab.markupInk
    val tools = scanMarkupTools(page.look.filter, ink)
    val bilevel = page.look.filter == ScanFilter.LightText
    val history = remember(page.key) { mutableStateOf(ScanMarkupHistory(page.marks ?: ScanMarks())) }
    // bumped on every change, so the screen reads the history again
    var version by remember { mutableStateOf(0) }
    val h = history.value
    val marks = remember(version) { h.now }
    // the marks' frame: the page's turns when they were first made, or now for a page without marks
    var frameTurns by remember(page.key) { mutableStateOf(page.marks?.turns) }
    // the tool last used, with every tool in reach (#516); else the pen
    var tool by remember { mutableStateOf(scanMarkupStartTool(vm.scanMarkup.lastTool, tools, lab.toolsInReach)) }
    val sizeAt = remember { mutableStateListOf(1, 1, 1, 1, 1) }
    // each tool's width from the size slider (#520), remembered in this run of the app
    val widthOf = remember { mutableStateListOf(*ScanMarkupTool.entries.map { t -> vm.scanMarkup.widths[t] ?: scanMarkupSizes(t)[1] }.toTypedArray()) }

    /** The width a stroke of [t] is drawn with now: the slider's with Markup's ink, else the dot picked. */
    fun widthFor(t: ScanMarkupTool): Float = if (scanMarkupSlider(t, ink)) widthOf[t.ordinal] else scanMarkupSizes(t).let { it[sizeAt[t.ordinal].coerceIn(it.indices)] }
    // low-latency ink (#520): the viewer's own setting, the pen drawn into the display's front buffer
    val lowLatency = vm.settings.toolPrefs.lowLatencyInk
    val wet = remember { io.github.artcraftandroid.pdfcraft.ui.viewer.WetInk() }
    // the wet-ink surface is the photo's own box here; nothing the app draws lies over it
    val wetCovers = remember { io.github.artcraftandroid.pdfcraft.ui.viewer.WetInkCovers() }
    val pendingInput = remember { io.github.artcraftandroid.pdfcraft.ui.viewer.PendingInput() }
    // the page drawn once without strokes, for the paper fills to be drawn on (#520)
    var base by remember { mutableStateOf<ScanMarkupBase?>(null) }
    val patcher = remember { ScanMarkupPatcher() }
    // a pen (not a finger) draws the live stroke: it gets the predicted tail
    var liveStylus by remember { mutableStateOf(false) }
    // each tool's colour (0xRRGGBB) in the order of ScanMarkupTool: Erase's is its Color fill's (#503)
    val colourOf = remember { mutableStateListOf(0xFFFFFF, 0x000000, 0xFFEB3B, 0x000000, 0x000000) }
    // what Erase paints (#503); a fill whose testing option is off is the paper
    val fills = scanEraseFills(lab)
    var fillChosen by remember { mutableStateOf(ScanEraseFill.Paper) }
    val fill = fillChosen.takeIf { it in fills } ?: ScanEraseFill.Paper
    // the eyedropper (#503): on until a colour is picked from the page; the loupe where the finger is
    var dropping by remember { mutableStateOf(false) }
    var loupe by remember { mutableStateOf<Pair<Offset, Int?>?>(null) }
    var dropperUses by remember { mutableStateOf(0) }
    var moreColours by remember { mutableStateOf(false) }
    var drawn by remember { mutableStateOf<ScanMarkupDrawn?>(null) }
    // the stroke being drawn, in a growing array (#520); its version changes with each sample
    var current by remember { mutableStateOf<ScanInkPoints?>(null) }
    var liveVersion by remember { mutableStateOf(0) }
    // the strokes Remove marks is rubbing over, hidden until the finger lets go (#520)
    var removing by remember { mutableStateOf<Set<Int>>(emptySet()) }
    // where the eraser's ring is drawn: the finger or pen on the page (#520)
    var ring by remember { mutableStateOf<Offset?>(null) }
    val inkStats = remember { ScanInkStats() }
    // when Undo, Redo or Clear was tapped, for the time until the screen showed it (#520)
    var changedAt by remember { mutableStateOf(0L) }
    var drawing by remember { mutableStateOf(false) }
    val strokeFramesOver = remember { intArrayOf(0) }
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var editing by remember { mutableStateOf<ScanTextEdit?>(null) }
    // text boxes being moved, shown in place of the page's until the finger lets go (then one change for Undo)
    var dragTexts by remember { mutableStateOf<List<ScanTextBox>?>(null) }

    fun commit(to: ScanMarks) {
        h.change(to)
        version++
    }

    /** Markup is left by Done, the arrow or the Back gesture: every way keeps the marks (#531). */
    fun close(leftBy: ScanMarkupLeftBy) {
        val left = scanMarkupLeave(h.now.copy(turns = frameTurns ?: 0))
        if (ink && left.kept != null && inkStats.removed > 0) vm.scanMarkup.marksRemoved.merge(page.key, inkStats.removed, Int::plus)
        vm.finishScanMarkup(
            index, left.kept, cancelled = left.kept == null, erasedShare = drawn?.erasedShare, eyedropper = dropperUses, eraseFill = fill.id,
            ink = inkStats.diag(ink) + scanMarkupLeaveDiag(leftBy, left.discarded),
        )
    }

    /** The mark tool and colour a stroke of [t] is made with now. */
    fun strokeOf(t: ScanMarkupTool): Pair<ScanMarkTool, Int> = when (t) {
        ScanMarkupTool.Erase -> fill.tool to (if (fill == ScanEraseFill.Colour) colourOf[0] else 0)
        ScanMarkupTool.Highlighter -> ScanMarkTool.Highlighter to colourOf[t.ordinal]
        else -> ScanMarkTool.Pen to colourOf[t.ordinal]
    }

    /** A colour picked for the tool in use (from the row, the sheet or the page); Erase takes it as its Color fill. */
    fun pickColour(rgb: Int) {
        colourOf[tool.ordinal] = rgb and 0xFFFFFF
        if (tool == ScanMarkupTool.Erase && ScanEraseFill.Colour in fills) fillChosen = ScanEraseFill.Colour
    }

    // the engine draws the strokes after each change: the first drawing also says the page's turns;
    // with Markup's ink (#520) only for the strokes up to the last paper or edge fill
    val engineStrokes = remember(marks.strokes, ink) { scanMarkupEngineStrokes(marks.strokes, ink) }
    LaunchedEffect(engineStrokes, frameTurns) {
        val started = android.os.SystemClock.elapsedRealtime()
        val made = if (ink) {
            // with Markup's ink the engine makes the page once, without strokes; the paper fills are
            // then drawn on it alone, a patch at a time (#520)
            val b = base ?: vm.scanMarkupBase(page)?.also {
                base = it
                inkStats.engineDraws++
                inkStats.engineMsMax = maxOf(inkStats.engineMsMax, android.os.SystemClock.elapsedRealtime() - started)
            } ?: return@LaunchedEffect
            if (frameTurns == null) {
                // the first drawing says the page's turns; the effect starts again with them
                frameTurns = b.drawn.turns
                drawn = b.drawn
                return@LaunchedEffect
            }
            if (engineStrokes.isEmpty()) b.drawn else {
                val patchedAt = android.os.SystemClock.elapsedRealtime()
                patcher.draw(b, ScanMarks(frameTurns ?: b.drawn.turns, engineStrokes))?.also {
                    inkStats.patches++
                    inkStats.patchMsMax = maxOf(inkStats.patchMsMax, android.os.SystemClock.elapsedRealtime() - patchedAt)
                }
            }
        } else {
            vm.scanMarkupDrawn(page, ScanMarks(frameTurns ?: 0, engineStrokes))?.also {
                inkStats.engineDraws++
                inkStats.engineMsMax = maxOf(inkStats.engineMsMax, android.os.SystemClock.elapsedRealtime() - started)
            }
        }
        if (made != null) {
            if (frameTurns == null) frameTurns = made.turns
            drawn = made
            if (changedAt > 0) {
                inkStats.undoMsMax = maxOf(inkStats.undoMsMax, android.os.SystemClock.elapsedRealtime() - changedAt)
                changedAt = 0
            }
        }
    }
    // Undo, Redo and Clear that leave the engine's part as it is show in the next frame (#520)
    LaunchedEffect(version) {
        if (changedAt == 0L) return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        if (drawn?.strokes == engineStrokes) {
            inkStats.undoMsMax = maxOf(inkStats.undoMsMax, android.os.SystemClock.elapsedRealtime() - changedAt)
            changedAt = 0
        }
    }
    // frames longer than 16 ms while a stroke is drawn (#520): a scribble's frame times in the log
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            androidx.compose.runtime.withFrameNanos { t ->
                if (drawing && last > 0 && t - last > 16_700_000L) {
                    inkStats.framesOver16++
                    strokeFramesOver[0]++
                }
                last = if (drawing) t else 0L
            }
        }
    }
    val image = drawn?.image
    val rect = image?.let { img ->
        if (box.width == 0 || box.height == 0) null else {
            val fit = minOf(box.width / img.width.toFloat(), box.height / img.height.toFloat()) * 0.94f
            val w = img.width * fit * zoom
            val hh = img.height * fit * zoom
            PageRect((box.width - w) / 2f + pan.x, (box.height - hh) / 2f + pan.y, w, hh, ((drawn?.turns ?: 0) - (frameTurns ?: 0)).mod(4))
        }
    }
    val rectNow by rememberUpdatedState(rect)
    val toolNow by rememberUpdatedState(tool)
    val marksNow by rememberUpdatedState(marks)
    val droppingNow by rememberUpdatedState(dropping)
    val lowNow by rememberUpdatedState(lowLatency)
    val imageNow by rememberUpdatedState(image)
    val inkNow by rememberUpdatedState(ink)

    io.github.artcraftandroid.pdfcraft.ui.ToolScaffold(
        stringResource(R.string.scan_markup), { close(ScanMarkupLeftBy.Back) }, stringResource(R.string.common_done), image != null, { close(ScanMarkupLeftBy.Done) },
        trailing = {
            BarIcon(LucideIcons.Undo2, stringResource(R.string.common_undo), { changedAt = android.os.SystemClock.elapsedRealtime(); h.undo(); version++; selected = null }, enabled = h.canUndo)
            BarIcon(LucideIcons.Redo2, stringResource(R.string.common_redo), { changedAt = android.os.SystemClock.elapsedRealtime(); h.redo(); version++; selected = null }, enabled = h.canRedo)
            BarIcon(LucideIcons.Trash2, stringResource(R.string.scan_markup_clear), { changedAt = android.os.SystemClock.elapsedRealtime(); commit(ScanMarks()); selected = null }, enabled = !marks.isEmpty)
        },
    ) {
        // Android's Back gesture: the same as the arrow, logged apart (#531); composed after the bar's, so it is the one asked
        androidx.activity.compose.BackHandler { close(ScanMarkupLeftBy.Gesture) }
        val description = stringResource(R.string.scan_markup_page_description, index + 1)
        Box(
            Modifier.weight(1f).fillMaxWidth().background(c.desk).onSizeChanged { box = it }.semantics { contentDescription = description }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val r = rectNow ?: return@awaitEachGesture
                        if (droppingNow) {
                            // the eyedropper (#503): the loupe follows the finger; the colour is taken where it lets go
                            val bitmap = imageNow?.asAndroidBitmap() ?: return@awaitEachGesture
                            fun sample(p: Offset): Int? = scanDropperSample(bitmap, (p.x - r.left) / r.width * bitmap.width, (p.y - r.top) / r.height * bitmap.height)
                            var at = down.position
                            loupe = at to sample(at)
                            down.consume()
                            while (true) {
                                val e = awaitPointerEvent()
                                val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                at = ch.position
                                loupe = at to sample(at)
                                ch.consume()
                            }
                            val picked = loupe?.second
                            loupe = null
                            if (picked != null) {
                                pickColour(picked)
                                vm.settings.toolPrefs.rememberInkColor(0xFF000000.toInt() or picked)
                                dropperUses++
                                dropping = false
                            }
                            return@awaitEachGesture
                        }
                        val t = toolNow
                        // Pen only (the viewer's own setting, #520): with Markup's ink a finger moves the page and does not draw
                        if (inkNow && down.type == PointerType.Touch && vm.settings.toolPrefs.stylusOnly && t != ScanMarkupTool.Text) {
                            var ev = currentEvent
                            while (ev.changes.any { it.pressed }) {
                                zoom = (zoom * ev.calculateZoom()).coerceIn(1f, 6f)
                                pan += ev.calculatePan()
                                ev.changes.forEach { it.consume() }
                                ev = awaitPointerEvent()
                            }
                            if (zoom <= 1.01f) pan = Offset.Zero
                            return@awaitEachGesture
                        }
                        val start = r.toMarks(down.position)
                        // Text: a box under the finger is picked up and moved; a tap elsewhere places a new one
                        val hit = if (t == ScanMarkupTool.Text) scanTextHit(marksNow.texts, start, r) else null
                        if (hit != null) selected = hit
                        val points = ScanInkPoints()
                        val fs0 = r.frameSize
                        val aspect = if (fs0.height > 0f) fs0.width / fs0.height else 1f
                        val removeRadius = if (t == ScanMarkupTool.RemoveMarks) widthFor(t) / 2f else 0f
                        var touched = emptySet<Int>()
                        val stylus = down.type == PointerType.Stylus
                        // the pen into the display's front buffer, with low-latency ink (#520)
                        val front = stylus && scanMarkupFront(t, inkNow, lowNow)
                        var wetFrom: Offset? = if (front) down.position else null
                        val wetWidth = if (front) widthFor(t) * r.long else 0f
                        val wetArgb = if (front) strokeOf(t).second or 0xFF000000.toInt() else 0
                        fun add(p: Offset, pressure: Float, at: Long = 0L) {
                            val (x, y) = r.toMarks(p)
                            wetFrom?.let { from ->
                                wet.segment(io.github.artcraftandroid.pdfcraft.ui.viewer.WetSegment(from, p, scanPenWidth(wetWidth, pressure), wetArgb, at))
                                wetFrom = p
                            }
                            if (stylus && wetFrom == null && at > 0L) pendingInput.at = at
                            if (t == ScanMarkupTool.RemoveMarks) {
                                val hits = scanStrokesTouched(marksNow.strokes, x, y, removeRadius, aspect)
                                if (!touched.containsAll(hits)) {
                                    touched = touched + hits
                                    removing = touched
                                }
                            }
                            points.add(x, y, pressure)
                        }
                        val startedAt = android.os.SystemClock.elapsedRealtime()
                        if (t != ScanMarkupTool.Text) {
                            add(down.position, if (stylus) down.pressure.coerceIn(0f, 1f) else 1f)
                            liveStylus = stylus
                            current = points
                            liveVersion++
                            drawing = true
                            strokeFramesOver[0] = 0
                            if (inkNow && (t == ScanMarkupTool.Erase || t == ScanMarkupTool.RemoveMarks)) ring = down.position
                        }
                        var moved = 0f
                        var last = down.position
                        while (true) {
                            val e = awaitPointerEvent()
                            if (e.changes.count { it.pressed } >= 2) {
                                // two fingers: the stroke is dropped, the page moves and zooms
                                current = null
                                wet.clear()
                                drawing = false
                                ring = null
                                removing = emptySet()
                                var ev = e
                                while (ev.changes.any { it.pressed }) {
                                    zoom = (zoom * ev.calculateZoom()).coerceIn(1f, 6f)
                                    pan += ev.calculatePan()
                                    ev.changes.forEach { it.consume() }
                                    ev = awaitPointerEvent()
                                }
                                if (zoom <= 1.01f) pan = Offset.Zero
                                return@awaitEachGesture
                            }
                            val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                            moved += (ch.position - last).getDistance()
                            if (hit != null && ch.pressed) {
                                val (ax, ay) = r.toMarks(last)
                                val (bx, by) = r.toMarks(ch.position)
                                val texts = (dragTexts ?: marksNow.texts).toMutableList()
                                val box0 = texts[hit]
                                texts[hit] = box0.copy(x = (box0.x + bx - ax).coerceIn(0f, 1f), y = (box0.y + by - ay).coerceIn(0f, 1f))
                                // moving is one change for Undo: the history takes the box where it is let go
                                dragTexts = texts
                            }
                            last = ch.position
                            if (!ch.pressed) break
                            if (t != ScanMarkupTool.Text) {
                                // every sample the pen sent since the last frame, with Markup's ink (#520)
                                // each with its own pressure, from the pen's log, as the viewer's Draw reads it (#520)
                                if (inkNow) for (hs in ch.historical) {
                                    val pressure = if (stylus) io.github.artcraftandroid.pdfcraft.ui.viewer.StylusLog.pen.pressureAt(hs.uptimeMillis) ?: ch.pressure.coerceIn(0f, 1f) else 1f
                                    add(hs.position, pressure, if (stylus) hs.uptimeMillis else 0L)
                                }
                                add(ch.position, if (stylus) ch.pressure.coerceIn(0f, 1f) else 1f, if (stylus) ch.uptimeMillis else 0L)
                                liveVersion++
                                if (ring != null) ring = ch.position
                            }
                            ch.consume()
                        }
                        drawing = false
                        ring = null
                        if (inkNow && t != ScanMarkupTool.Text && points.count > 0) {
                            inkStats.strokes++
                            inkStats.samples += points.count
                            if (front) inkStats.frontStrokes++
                            io.github.artcraftandroid.pdfcraft.engine.Diag.event(
                                io.github.artcraftandroid.pdfcraft.engine.DiagArea.Scan, "markup_stroke",
                                *scanInkStrokeDiag(
                                    if (t == ScanMarkupTool.RemoveMarks) "remove" else strokeOf(t).first.id, points.count, android.os.SystemClock.elapsedRealtime() - startedAt,
                                    stylus, strokeFramesOver[0], front, widthFor(t),
                                ),
                            )
                        }
                        when {
                            t == ScanMarkupTool.RemoveMarks -> {
                                // the strokes rubbed over go, as one change for Undo
                                if (touched.isNotEmpty()) {
                                    inkStats.removed += touched.size
                                    commit(marksNow.copy(strokes = marksNow.strokes.filterIndexed { i, _ -> i !in touched }))
                                }
                                removing = emptySet()
                                current = null
                            }
                            hit != null -> dragTexts?.let { commit(marksNow.copy(texts = it)) }.also { dragTexts = null }
                            t == ScanMarkupTool.Text -> if (moved < 24f) {
                                selected = null
                                editing = ScanTextEdit(null, start.first, start.second, sizeAt[3], colourOf[3], "")
                            }
                            points.size >= 3 -> {
                                val width = widthFor(t)
                                val (markTool, colour) = strokeOf(t)
                                val fs = r.frameSize
                                val thin = scanThinPoints(points.toArray(), (width * 0.15f).coerceAtLeast(0.0008f), fs.width / fs.height)
                                if (marksNow.strokes.size < ScanMarks.MAX_MARKS) commit(marksNow.copy(strokes = marksNow.strokes + ScanStrokeMark(markTool, colour, width, thin)))
                                current = null
                            }
                            else -> current = null
                        }
                        // the stroke is the screen's now: the front buffer is wiped
                        if (front) wet.clear()
                        liveStylus = false
                    }
                },
        ) {
            if (image == null || rect == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(40.dp), color = c.accent)
            } else {
                val pending = scanMarkupOnScreen(drawn?.strokes, marks.strokes, ink)
                // the index in marks.strokes of the first stroke drawn here, for the strokes Remove marks hides
                val pendingFrom = marks.strokes.size - pending.size
                val hidden = removing
                val texts = dragTexts ?: marks.texts
                val live = current
                val ringAt = ring
                val tool0 = tool
                val lens = loupe
                Canvas(Modifier.fillMaxSize()) {
                    // read here, so a new sample only draws again (#520)
                    if (liveVersion < 0) return@Canvas
                    drawImage(image, srcOffset = IntOffset.Zero, srcSize = IntSize(image.width, image.height), dstOffset = IntOffset(rect.left.toInt(), rect.top.toInt()), dstSize = IntSize(rect.width.toInt(), rect.height.toInt()))
                    for ((k, s) in pending.withIndex()) if (pendingFrom + k !in hidden) drawMarkStroke(rect, s.tool, s.colour, s.width, s.points)
                    if (live != null && tool0 != ScanMarkupTool.Text && tool0 != ScanMarkupTool.RemoveMarks) {
                        val (markTool, colour) = strokeOf(tool0)
                        val width = widthFor(tool0)
                        drawMarkStroke(rect, markTool, colour, width, live.data, live.count)
                        // a predicted tail on the pen's stroke (#520): where the pen will be about a frame from
                        // now, from its real speed; drawn only, never kept, the next sample replaces it
                        if (ink && liveStylus && markTool == ScanMarkTool.Pen && live.count > 0) {
                            io.github.artcraftandroid.pdfcraft.ui.viewer.StylusLog.pen.predictedOffset(android.os.SystemClock.uptimeMillis())?.let { (dx, dy) ->
                                val n = live.count - 1
                                val from = rect.toScreen(live.data[n * 3], live.data[n * 3 + 1])
                                drawLine(
                                    Color(0xFF000000.toInt() or colour), from, Offset(from.x + dx, from.y + dy),
                                    strokeWidth = scanPenWidth(width * rect.long, live.data[n * 3 + 2]), cap = StrokeCap.Round,
                                )
                            }
                        }
                        if (liveStylus) pendingInput.drawn(android.os.SystemClock.uptimeMillis(), io.github.artcraftandroid.pdfcraft.ui.viewer.InkLatency.normal)
                    }
                    // the eraser's size under the finger or pen (#520)
                    if (ringAt != null && (tool0 == ScanMarkupTool.Erase || tool0 == ScanMarkupTool.RemoveMarks)) {
                        val radius = widthFor(tool0) * rect.long / 2f
                        drawCircle(Color.Black.copy(alpha = 0.6f), radius, ringAt, style = Stroke(width = 2.5f))
                        drawCircle(Color.White.copy(alpha = 0.9f), radius - 2f, ringAt, style = Stroke(width = 1.5f))
                    }
                    for ((i, t) in texts.withIndex()) drawTextBox(rect, t, i == selected && tool0 == ScanMarkupTool.Text)
                    if (lens != null) drawLoupe(image, rect, lens.first, lens.second)
                }
                if (ink && lowLatency) io.github.artcraftandroid.pdfcraft.ui.viewer.FrontBufferedInkOverlay(wet, wetCovers, Modifier.fillMaxSize())
            }
        }
        val colours = lab.markupColours && !bilevel
        val withColour = tool != ScanMarkupTool.RemoveMarks && (tool != ScanMarkupTool.Erase || fill == ScanEraseFill.Colour)
        ScanMarkupBars(
            tool, tools, fill, fills, dropping, sizeAt[tool.ordinal], colourOf[tool.ordinal],
            scanMarkupSwatches(tool, fill, colourOf[tool.ordinal], bilevel),
            more = colours && withColour,
            // the eyedropper also turns Erase to its Color fill
            dropper = colours && (withColour || ScanEraseFill.Colour in fills),
            selectedText = selected?.takeIf { tool == ScanMarkupTool.Text }?.let { marks.texts.getOrNull(it) },
            onTool = { t ->
                tool = t
                vm.scanMarkup.lastTool = t
                dropping = false
                if (t != ScanMarkupTool.Text) selected = null
            },
            onFill = { fillChosen = it; dropping = false },
            onSize = { sizeAt[tool.ordinal] = it },
            onColour = { pickColour(it) },
            onMore = { moreColours = true; dropping = false },
            onDropper = { dropping = !dropping },
            onEditText = {
                val at = selected ?: return@ScanMarkupBars
                val t = marks.texts.getOrNull(at) ?: return@ScanMarkupBars
                editing = ScanTextEdit(at, t.x, t.y, scanMarkupSizes(ScanMarkupTool.Text).indexOfFirst { it == t.size }.coerceAtLeast(0), t.colour, t.text, t.size)
            },
            onDeleteText = {
                val at = selected ?: return@ScanMarkupBars
                commit(marks.copy(texts = marks.texts.filterIndexed { i, _ -> i != at }))
                selected = null
            },
            // the size slider (#520)
            width = widthOf[tool.ordinal].takeIf { scanMarkupSlider(tool, ink) },
            widthRange = scanMarkupWidthRange(scanMarkupSizes(tool)),
            onWidth = { w ->
                widthOf[tool.ordinal] = w
                vm.scanMarkup.widths[tool] = w
            },
        )
    }
    if (moreColours) {
        ScanColourSheet(vm, colourOf[tool.ordinal], onDismiss = { moreColours = false }) { pickColour(it) }
    }
    editing?.let { e ->
        ScanTextDialog(
            e,
            onDismiss = { editing = null },
            onDone = { text, sizeIndex, colour ->
                editing = null
                val size = e.keepSize?.takeIf { sizeIndex == e.sizeIndex } ?: scanMarkupSizes(ScanMarkupTool.Text)[sizeIndex]
                sizeAt[3] = sizeIndex
                colourOf[3] = colour
                val box0 = ScanTextBox(text.trimEnd(), e.x, e.y, size, colour)
                when {
                    text.isBlank() && e.index != null -> commit(marks.copy(texts = marks.texts.filterIndexed { i, _ -> i != e.index }))
                    text.isBlank() -> Unit
                    e.index != null -> commit(marks.copy(texts = marks.texts.toMutableList().also { it[e.index] = box0 }))
                    marks.texts.size < ScanMarks.MAX_MARKS -> {
                        commit(marks.copy(texts = marks.texts + box0))
                        selected = marks.texts.size
                    }
                }
            },
        )
    }
}

/**
 * The Markup screen's bars under the page: the hint, Erase's fills (#503), the tool's sizes and
 * colours with "More colors" and the eyedropper when offered ([more], [dropper]), then the tools.
 */
@Composable
internal fun ScanMarkupBars(
    tool: ScanMarkupTool,
    tools: List<ScanMarkupTool>,
    fill: ScanEraseFill,
    fills: List<ScanEraseFill>,
    dropping: Boolean,
    size: Int,
    colour: Int,
    swatches: List<Int>,
    more: Boolean,
    dropper: Boolean,
    selectedText: ScanTextBox?,
    onTool: (ScanMarkupTool) -> Unit,
    onFill: (ScanEraseFill) -> Unit,
    onSize: (Int) -> Unit,
    onColour: (Int) -> Unit,
    onMore: () -> Unit,
    onDropper: () -> Unit,
    onEditText: () -> Unit,
    onDeleteText: () -> Unit,
    width: Float? = null,
    widthRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onWidth: (Float) -> Unit = {},
) {
    val c = pdfColors
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(scanMarkupHint(tool, fill, dropping)),
            color = c.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        if (tool == ScanMarkupTool.Erase && fills.size > 1) {
            Box(Modifier.padding(horizontal = 12.dp)) {
                OptionChips(fills, fill, onFill) { stringResource(scanEraseFillLabel(it)) }
            }
        }
        ScanMarkupOptions(
            size, colour, swatches, onSize, onColour, width, widthRange, onWidth,
            onMore = if (more) onMore else null,
            onDropper = if (dropper) onDropper else null,
            dropping = dropping, selectedText = selectedText, onEditText = onEditText, onDeleteText = onDeleteText,
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            for (t in tools) {
                val label = stringResource(scanMarkupToolLabel(t))
                val on = t == tool
                Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (on) c.pressed else Color.Transparent).semantics { this.selected = on }) {
                    ScanBarButton(scanMarkupToolIcon(t), label, modifier = Modifier.fillMaxWidth()) { onTool(t) }
                }
            }
        }
    }
}

/** The hint under the page: what the tool in use does (#503 says what each of Erase's fills does). */
internal fun scanMarkupHint(tool: ScanMarkupTool, fill: ScanEraseFill, dropping: Boolean): Int = when {
    dropping -> R.string.scan_markup_dropper_hint
    tool == ScanMarkupTool.Text -> R.string.scan_markup_text_hint
    tool == ScanMarkupTool.RemoveMarks -> R.string.scan_markup_remove_hint
    tool != ScanMarkupTool.Erase -> R.string.scan_markup_hint
    fill == ScanEraseFill.Colour -> R.string.scan_markup_erase_colour_hint
    fill == ScanEraseFill.Edge -> R.string.scan_markup_erase_edge_hint
    else -> R.string.scan_markup_erase_hint
}

private fun scanEraseFillLabel(f: ScanEraseFill): Int = when (f) {
    ScanEraseFill.Paper -> R.string.scan_markup_fill_paper
    ScanEraseFill.Colour -> R.string.scan_markup_fill_colour
    ScanEraseFill.Edge -> R.string.scan_markup_fill_edge
}

/** The text dialog's starting state: [index] of the box changed, or null for a new one at ([x], [y]). */
internal class ScanTextEdit(val index: Int?, val x: Float, val y: Float, val sizeIndex: Int, val colour: Int, val text: String, val keepSize: Float? = null)

/** The text box at a share point of the marks' frame, the last drawn first; null for none. */
private fun scanTextHit(texts: List<ScanTextBox>, at: Pair<Float, Float>, r: PageRect): Int? {
    val frame = r.frameSize
    for (i in texts.indices.reversed()) {
        val t = texts[i]
        val (bw, bh) = scanTextBoxSize(t) ?: continue
        // the box's half sides in shares of the frame's width and height, with a little room for a finger
        val hx = bw * r.long / frame.width / 2f + 0.02f
        val hy = bh * r.long / frame.height / 2f + 0.02f
        if (kotlin.math.abs(at.first - t.x) <= hx && kotlin.math.abs(at.second - t.y) <= hy) return i
    }
    return null
}

private fun DrawScope.drawMarkStroke(r: PageRect, tool: ScanMarkTool, colour: Int, width: Float, points: FloatArray, count: Int = points.size / 3) {
    val n = count
    if (n == 0) return
    val full = width * r.long
    val (paint, blend) = when (tool) {
        // the eraser as it rubs: the page's paper (or edge) is the engine's to find, so a light veil shows where
        ScanMarkTool.Erase, ScanMarkTool.EraseEdge -> Color.White.copy(alpha = 0.85f) to BlendMode.SrcOver
        // the Color fill (#503): its colour, opaque
        ScanMarkTool.EraseColour -> Color(0xFF000000.toInt() or colour) to BlendMode.SrcOver
        ScanMarkTool.Highlighter -> Color(0xFF000000.toInt() or colour).copy(alpha = 0.85f) to BlendMode.Multiply
        ScanMarkTool.Pen -> Color(0xFF000000.toInt() or colour) to BlendMode.SrcOver
    }
    fun widthAt(i: Int) = if (tool == ScanMarkTool.Pen) scanPenWidth(full, points[i * 3 + 2]) else full
    if (n == 1) {
        drawCircle(paint, widthAt(0) / 2f, r.toScreen(points[0], points[1]), blendMode = blend)
        return
    }
    if (tool == ScanMarkTool.Highlighter) {
        // one path, so where the stroke crosses itself it is not laid down twice
        val path = androidx.compose.ui.graphics.Path()
        val p0 = r.toScreen(points[0], points[1])
        path.moveTo(p0.x, p0.y)
        for (i in 1 until n) r.toScreen(points[i * 3], points[i * 3 + 1]).let { path.lineTo(it.x, it.y) }
        drawPath(path, paint, style = Stroke(width = full, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round), blendMode = blend)
        return
    }
    for (i in 1 until n) {
        drawLine(paint, r.toScreen(points[(i - 1) * 3], points[(i - 1) * 3 + 1]), r.toScreen(points[i * 3], points[i * 3 + 1]), strokeWidth = widthAt(i), cap = StrokeCap.Round, blendMode = blend)
    }
}

private val maskImages = object : LinkedHashMap<ScanTextBox, ImageBitmap>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ScanTextBox, ImageBitmap>?): Boolean = size > 32
}

/** The text's mask as a picture to tint: the same mask the engine draws on the page. */
private fun maskImage(box: ScanTextBox): ImageBitmap? {
    maskImages[box]?.let { return it }
    val m = scanTextMask(box) ?: return null
    val bitmap = android.graphics.Bitmap.createBitmap(m.width, m.height, android.graphics.Bitmap.Config.ALPHA_8)
    val stride = bitmap.rowBytes
    val rows = if (stride == m.width) m.bytes else ByteArray(stride * m.height).also { out -> for (y in 0 until m.height) System.arraycopy(m.bytes, y * m.width, out, y * stride, m.width) }
    bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(rows))
    return bitmap.asImageBitmap().also { maskImages[box] = it }
}

private fun DrawScope.drawTextBox(r: PageRect, t: ScanTextBox, selected: Boolean) {
    val img = maskImage(t) ?: return
    val (bw, bh) = scanTextBoxSize(t) ?: return
    val w = bw * r.long
    val hgt = bh * r.long
    val centre = r.toScreen(t.x, t.y)
    withTransform({ rotate(r.frameTurn * 90f, centre) }) {
        drawImage(
            img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height),
            dstOffset = IntOffset((centre.x - w / 2f).toInt(), (centre.y - hgt / 2f).toInt()), dstSize = IntSize(w.toInt().coerceAtLeast(1), hgt.toInt().coerceAtLeast(1)),
            colorFilter = ColorFilter.tint(Color(0xFF000000.toInt() or t.colour), BlendMode.SrcIn),
        )
        if (selected) {
            drawRect(Color(0xFF1E88E5), topLeft = Offset(centre.x - w / 2f, centre.y - hgt / 2f), size = Size(w, hgt), style = Stroke(width = 2.dp.toPx()))
        }
    }
}

/**
 * The eyedropper's loupe (#503): the page under the finger three times larger, in a circle above
 * it, ringed with the colour it would take (a grey ring off the page), with the disc it averages
 * marked in its middle.
 */
internal fun DrawScope.drawLoupe(image: ImageBitmap, r: PageRect, at: Offset, colour: Int?) {
    val radius = 46.dp.toPx()
    val centre = Offset(at.x.coerceIn(radius, size.width - radius), (at.y - radius - 36.dp.toPx()).let { if (it < radius) at.y + radius + 36.dp.toPx() else it })
    // page pixels per screen pixel in the loupe: three times closer than the page is shown
    val scale = image.width / r.width / 3f
    val half = (radius * scale).toInt().coerceAtLeast(1)
    val ix = ((at.x - r.left) / r.width * image.width).toInt()
    val iy = ((at.y - r.top) / r.height * image.height).toInt()
    val sx = (ix - half).coerceIn(0, (image.width - 2 * half).coerceAtLeast(0))
    val sy = (iy - half).coerceIn(0, (image.height - 2 * half).coerceAtLeast(0))
    val sw = minOf(2 * half, image.width)
    val sh = minOf(2 * half, image.height)
    // where the finger is inside the loupe, after the source was kept on the page
    val fx = centre.x + (ix - sx - half) / scale
    val fy = centre.y + (iy - sy - half) / scale
    val clip = androidx.compose.ui.graphics.Path().apply { addOval(androidx.compose.ui.geometry.Rect(centre, radius)) }
    clipPath(clip) {
        drawRect(Color(0xFF808080), topLeft = centre - Offset(radius, radius), size = Size(2 * radius, 2 * radius))
        drawImage(image, srcOffset = IntOffset(sx, sy), srcSize = IntSize(sw, sh), dstOffset = IntOffset((centre.x - radius).toInt(), (centre.y - radius).toInt()), dstSize = IntSize((sw / scale).toInt(), (sh / scale).toInt()))
        val disc = scanDropperRadius(image.width, image.height) / scale
        drawCircle(Color.White, disc + 1.5.dp.toPx(), Offset(fx, fy), style = Stroke(1.dp.toPx()))
        drawCircle(Color.Black, disc, Offset(fx, fy), style = Stroke(1.dp.toPx()))
    }
    val ring = if (colour != null) Color(0xFF000000.toInt() or colour) else Color(0xFF9E9E9E)
    drawCircle(ring, radius - 4.dp.toPx(), centre, style = Stroke(8.dp.toPx()))
    drawCircle(Color.White, radius, centre, style = Stroke(1.5.dp.toPx()))
    drawCircle(Color.Black, radius + 1.5.dp.toPx(), centre, style = Stroke(1.dp.toPx()))
}

/**
 * The chosen tool's three sizes, "More colors" ([onMore]) and the eyedropper ([onDropper], ringed
 * while [dropping]) when offered (#503), then its colours: [swatches] (0xRRGGBB, [colour] ringed). With a text box picked, Edit and Delete instead. The row scrolls on a narrow screen.
 */
@Composable
private fun ScanMarkupOptions(
    size: Int,
    colour: Int,
    swatches: List<Int>,
    onSize: (Int) -> Unit,
    onColour: (Int) -> Unit,
    width: Float?,
    widthRange: ClosedFloatingPointRange<Float>,
    onWidth: (Float) -> Unit,
    onMore: (() -> Unit)?,
    onDropper: (() -> Unit)?,
    dropping: Boolean,
    selectedText: ScanTextBox?,
    onEditText: () -> Unit,
    onDeleteText: () -> Unit,
) {
    val c = pdfColors
    Row(
        Modifier.fillMaxWidth().height(52.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (selectedText != null) {
            TextButton(onClick = onEditText) { Text(stringResource(R.string.scan_markup_edit_text), color = c.accent) }
            TextButton(onClick = onDeleteText) { Text(stringResource(R.string.scan_markup_delete_text), color = c.accent) }
            return@Row
        }
        // the size slider (#520), with a dot as wide as the stroke will be, up to the dots' largest
        if (width != null) {
            val name = stringResource(R.string.scan_markup_size)
            val share = ((width - widthRange.start) / (widthRange.endInclusive - widthRange.start)).coerceIn(0f, 1f)
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size((4 + share * 22).dp).clip(CircleShape).background(c.text))
            }
            androidx.compose.material3.Slider(
                value = width, onValueChange = onWidth, valueRange = widthRange,
                modifier = Modifier.width(168.dp).semantics { contentDescription = name },
            )
        }
        val labels = if (width != null) emptyList() else listOf(R.string.scan_markup_size_small, R.string.scan_markup_size_medium, R.string.scan_markup_size_large)
        for ((i, label) in labels.withIndex()) {
            val name = stringResource(label)
            val on = i == size
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(if (on) c.pressed else Color.Transparent)
                    .clickable(role = Role.RadioButton, onClick = { onSize(i) }).semantics { contentDescription = name; selected = on },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size((6 + i * 7).dp).clip(CircleShape).background(c.text))
            }
        }
        if (onMore != null || onDropper != null) Spacer(Modifier.width(4.dp))
        if (onMore != null) {
            val name = stringResource(R.string.scan_markup_more_colours)
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onMore).semantics { contentDescription = name },
                contentAlignment = Alignment.Center,
            ) { Icon(LucideIcons.Palette, null, tint = c.text, modifier = Modifier.size(24.dp)) }
        }
        if (onDropper != null) {
            val name = stringResource(R.string.scan_markup_dropper)
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(if (dropping) c.pressed else Color.Transparent)
                    .then(if (dropping) Modifier.border(2.dp, c.accent, CircleShape) else Modifier)
                    .clickable(role = Role.Button, onClick = onDropper).semantics { contentDescription = name; selected = dropping },
                contentAlignment = Alignment.Center,
            ) { Icon(ScanMarkupIcons.Dropper, null, tint = c.text, modifier = Modifier.size(22.dp)) }
        }
        if (swatches.isNotEmpty() || onDropper != null) Spacer(Modifier.width(4.dp))
        for (rgb in swatches) {
            val name = stringResource(R.string.ink_color_hex_name, "#%06X".format(rgb and 0xFFFFFF))
            val on = rgb == (colour and 0xFFFFFF) && !dropping
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(Color(0xFF000000.toInt() or rgb))
                    .then(if (on) Modifier.border(3.dp, c.accent, CircleShape) else Modifier.border(1.dp, c.divider, CircleShape))
                    .clickable(role = Role.RadioButton, onClick = { onColour(rgb) }).semantics { contentDescription = name; selected = on },
            )
        }
    }
}

/** Add or change a text box: the words, one of three sizes and a colour (Text's own, or the one in use). An empty text removes a box. */
@Composable
private fun ScanTextDialog(edit: ScanTextEdit, onDismiss: () -> Unit, onDone: (String, Int, Int) -> Unit) {
    val c = pdfColors
    var text by remember { mutableStateOf(edit.text) }
    var size by remember { mutableStateOf(edit.sizeIndex) }
    var colour by remember { mutableStateOf(edit.colour and 0xFFFFFF) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (edit.index == null) R.string.scan_markup_add_text else R.string.scan_markup_edit_text)) },
        text = {
            Column {
                OutlinedTextField(text, { text = it.take(500) }, label = { Text(stringResource(R.string.scan_markup_text)) }, modifier = Modifier.fillMaxWidth(), maxLines = 4)
                Spacer(Modifier.height(8.dp))
                val own = scanMarkupColours(ScanMarkupTool.Text)
                ScanMarkupOptions(size, colour, if ((edit.colour and 0xFFFFFF) in own) own else own + (edit.colour and 0xFFFFFF), { size = it }, { colour = it }, null, 0f..1f, {}, null, null, false, null, {}, {})
            }
        },
        confirmButton = { TextButton(onClick = { onDone(text, size, colour) }) { Text(stringResource(R.string.common_done), color = c.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), color = c.accent) } },
    )
}

internal fun scanMarkupToolLabel(t: ScanMarkupTool): Int = when (t) {
    ScanMarkupTool.Erase -> R.string.scan_markup_erase
    ScanMarkupTool.RemoveMarks -> R.string.scan_markup_remove
    ScanMarkupTool.Pen -> R.string.scan_markup_pen
    ScanMarkupTool.Highlighter -> R.string.scan_markup_highlighter
    ScanMarkupTool.Text -> R.string.scan_markup_text
}

private fun scanMarkupToolIcon(t: ScanMarkupTool) = when (t) {
    ScanMarkupTool.Erase -> LucideIcons.Eraser
    ScanMarkupTool.RemoveMarks -> LucideIcons.BrushCleaning
    ScanMarkupTool.Pen -> LucideIcons.PenLine
    ScanMarkupTool.Highlighter -> LucideIcons.Highlighter
    ScanMarkupTool.Text -> LucideIcons.Type
}

/** "Remove this page's markup?" before new borders or a change of flattening (#495). Keep is the safe answer. */
@Composable
internal fun ScanMarkupAskDialog(ask: ScanMarkupAsk, onKeep: () -> Unit, onRemove: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(stringResource(R.string.scan_markup_ask_title)) },
        text = { Text(stringResource(R.string.scan_markup_ask_body)) },
        confirmButton = { TextButton(onClick = onKeep) { Text(stringResource(R.string.scan_markup_ask_keep)) } },
        dismissButton = { TextButton(onClick = onRemove) { Text(stringResource(R.string.scan_markup_ask_remove)) } },
    )
}
