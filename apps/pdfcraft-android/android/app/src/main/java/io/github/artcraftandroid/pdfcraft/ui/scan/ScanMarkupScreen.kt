package io.github.artcraftandroid.pdfcraft.ui.scan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
internal enum class ScanMarkupTool { Erase, Pen, Highlighter, Text }

/** The three sizes of each tool, as shares of the page's longer side (a text's letter height). */
internal fun scanMarkupSizes(tool: ScanMarkupTool): List<Float> = when (tool) {
    ScanMarkupTool.Erase -> listOf(0.02f, 0.04f, 0.08f)
    ScanMarkupTool.Pen -> listOf(0.0025f, 0.005f, 0.009f)
    ScanMarkupTool.Highlighter -> listOf(0.015f, 0.025f, 0.04f)
    ScanMarkupTool.Text -> listOf(0.018f, 0.026f, 0.04f)
}

/** Each tool's colours (0xRRGGBB); the eraser has none, it paints the paper. */
internal fun scanMarkupColours(tool: ScanMarkupTool): List<Int> = when (tool) {
    ScanMarkupTool.Erase -> emptyList()
    ScanMarkupTool.Pen -> listOf(0x000000, 0x1565C0, 0xC62828, 0x2E7D32)
    ScanMarkupTool.Highlighter -> listOf(0xFFEB3B, 0x8BC34A, 0xF48FB1, 0x81D4FA)
    ScanMarkupTool.Text -> listOf(0x000000, 0x1565C0, 0xC62828)
}

/** The tools offered on a page in [filter]: a black-and-white page has no highlighter (it has no grey to multiply). */
internal fun scanMarkupTools(filter: ScanFilter): List<ScanMarkupTool> =
    ScanMarkupTool.entries.filter { it != ScanMarkupTool.Highlighter || filter != ScanFilter.LightText }

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
private class PageRect(val left: Float, val top: Float, val width: Float, val height: Float, val frameTurn: Int) {
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
    val tools = scanMarkupTools(page.look.filter)
    val history = remember(page.key) { mutableStateOf(ScanMarkupHistory(page.marks ?: ScanMarks())) }
    // bumped on every change, so the screen reads the history again
    var version by remember { mutableStateOf(0) }
    val h = history.value
    val marks = remember(version) { h.now }
    // the marks' frame: the page's turns when they were first made, or now for a page without marks
    var frameTurns by remember(page.key) { mutableStateOf(page.marks?.turns) }
    var tool by remember { mutableStateOf(ScanMarkupTool.Pen) }
    val sizeAt = remember { mutableStateListOf(1, 1, 1, 1) }
    val colourAt = remember { mutableStateListOf(0, 0, 0, 0) }
    var drawn by remember { mutableStateOf<ScanMarkupDrawn?>(null) }
    var current by remember { mutableStateOf<List<Float>?>(null) }
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

    fun close(done: Boolean) {
        val out = h.now.copy(turns = frameTurns ?: 0)
        vm.finishScanMarkup(index, if (done) out else null, cancelled = !done, erasedShare = drawn?.erasedShare)
    }

    // the engine draws the strokes after each change: the first drawing also says the page's turns
    LaunchedEffect(marks.strokes, frameTurns) {
        val made = vm.scanMarkupDrawn(page, ScanMarks(frameTurns ?: 0, marks.strokes))
        if (made != null) {
            if (frameTurns == null) frameTurns = made.turns
            drawn = made
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

    io.github.artcraftandroid.pdfcraft.ui.ToolScaffold(
        stringResource(R.string.scan_markup), { close(false) }, stringResource(R.string.common_done), image != null, { close(true) },
        trailing = {
            BarIcon(LucideIcons.Undo2, stringResource(R.string.common_undo), { h.undo(); version++; selected = null }, enabled = h.canUndo)
            BarIcon(LucideIcons.Redo2, stringResource(R.string.common_redo), { h.redo(); version++; selected = null }, enabled = h.canRedo)
            BarIcon(LucideIcons.Trash2, stringResource(R.string.scan_markup_clear), { commit(ScanMarks()); selected = null }, enabled = !marks.isEmpty)
        },
    ) {
        val description = stringResource(R.string.scan_markup_page_description, index + 1)
        Box(
            Modifier.weight(1f).fillMaxWidth().background(c.desk).onSizeChanged { box = it }.semantics { contentDescription = description }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val r = rectNow ?: return@awaitEachGesture
                        val t = toolNow
                        val start = r.toMarks(down.position)
                        // Text: a box under the finger is picked up and moved; a tap elsewhere places a new one
                        val hit = if (t == ScanMarkupTool.Text) scanTextHit(marksNow.texts, start, r) else null
                        if (hit != null) selected = hit
                        val points = ArrayList<Float>()
                        fun add(p: Offset, pressure: Float) {
                            val (x, y) = r.toMarks(p)
                            points += x; points += y; points += pressure
                        }
                        val stylus = down.type == PointerType.Stylus
                        if (t != ScanMarkupTool.Text) {
                            add(down.position, if (stylus) down.pressure.coerceIn(0f, 1f) else 1f)
                            current = points.toList()
                        }
                        var moved = 0f
                        var last = down.position
                        while (true) {
                            val e = awaitPointerEvent()
                            if (e.changes.count { it.pressed } >= 2) {
                                // two fingers: the stroke is dropped, the page moves and zooms
                                current = null
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
                                add(ch.position, if (stylus) ch.pressure.coerceIn(0f, 1f) else 1f)
                                current = points.toList()
                            }
                            ch.consume()
                        }
                        when {
                            hit != null -> dragTexts?.let { commit(marksNow.copy(texts = it)) }.also { dragTexts = null }
                            t == ScanMarkupTool.Text -> if (moved < 24f) {
                                selected = null
                                editing = ScanTextEdit(null, start.first, start.second, sizeAt[3], colourAt[3], "")
                            }
                            points.size >= 3 -> {
                                val sizes = scanMarkupSizes(t)
                                val width = sizes[sizeAt[t.ordinal].coerceIn(sizes.indices)]
                                val colours = scanMarkupColours(t)
                                val colour = colours.getOrNull(colourAt[t.ordinal]) ?: 0
                                val fs = r.frameSize
                                val thin = scanThinPoints(points.toFloatArray(), (width * 0.15f).coerceAtLeast(0.0008f), fs.width / fs.height)
                                val markTool = when (t) {
                                    ScanMarkupTool.Erase -> ScanMarkTool.Erase
                                    ScanMarkupTool.Highlighter -> ScanMarkTool.Highlighter
                                    else -> ScanMarkTool.Pen
                                }
                                if (marksNow.strokes.size < ScanMarks.MAX_MARKS) commit(marksNow.copy(strokes = marksNow.strokes + ScanStrokeMark(markTool, colour, width, thin)))
                                current = null
                            }
                            else -> current = null
                        }
                    }
                },
        ) {
            if (image == null || rect == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(40.dp), color = c.accent)
            } else {
                val pending = scanMarkupPending(drawn?.strokes, marks.strokes)
                val texts = dragTexts ?: marks.texts
                val live = current
                val tool0 = tool
                Canvas(Modifier.fillMaxSize()) {
                    drawImage(image, srcOffset = IntOffset.Zero, srcSize = IntSize(image.width, image.height), dstOffset = IntOffset(rect.left.toInt(), rect.top.toInt()), dstSize = IntSize(rect.width.toInt(), rect.height.toInt()))
                    for (s in pending) drawMarkStroke(rect, s.tool, s.colour, s.width, s.points)
                    if (live != null && tool0 != ScanMarkupTool.Text) {
                        val sizes = scanMarkupSizes(tool0)
                        val colours = scanMarkupColours(tool0)
                        val markTool = when (tool0) {
                            ScanMarkupTool.Erase -> ScanMarkTool.Erase
                            ScanMarkupTool.Highlighter -> ScanMarkTool.Highlighter
                            else -> ScanMarkTool.Pen
                        }
                        drawMarkStroke(rect, markTool, colours.getOrNull(colourAt[tool0.ordinal]) ?: 0, sizes[sizeAt[tool0.ordinal]], live.toFloatArray())
                    }
                    for ((i, t) in texts.withIndex()) drawTextBox(rect, t, i == selected && tool0 == ScanMarkupTool.Text)
                }
            }
        }
        // the hint, the chosen tool's sizes and colours, then the tools
        Text(
            stringResource(if (tool == ScanMarkupTool.Text) R.string.scan_markup_text_hint else R.string.scan_markup_hint),
            color = c.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        ScanMarkupOptions(
            tool, sizeAt[tool.ordinal], colourAt[tool.ordinal],
            onSize = { sizeAt[tool.ordinal] = it },
            onColour = { colourAt[tool.ordinal] = it },
            selectedText = selected?.takeIf { tool == ScanMarkupTool.Text }?.let { marks.texts.getOrNull(it) },
            onEditText = {
                val at = selected ?: return@ScanMarkupOptions
                val t = marks.texts.getOrNull(at) ?: return@ScanMarkupOptions
                editing = ScanTextEdit(at, t.x, t.y, scanMarkupSizes(ScanMarkupTool.Text).indexOfFirst { it == t.size }.coerceAtLeast(0), scanMarkupColours(ScanMarkupTool.Text).indexOf(t.colour).coerceAtLeast(0), t.text, t.size, t.colour)
            },
            onDeleteText = {
                val at = selected ?: return@ScanMarkupOptions
                commit(marks.copy(texts = marks.texts.filterIndexed { i, _ -> i != at }))
                selected = null
            },
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            for (t in tools) {
                val label = stringResource(scanMarkupToolLabel(t))
                val on = t == tool
                Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (on) c.pressed else Color.Transparent).semantics { this.selected = on }) {
                    ScanBarButton(scanMarkupToolIcon(t), label, modifier = Modifier.fillMaxWidth()) {
                        tool = t
                        if (t != ScanMarkupTool.Text) selected = null
                    }
                }
            }
        }
    }
    editing?.let { e ->
        ScanTextDialog(
            e,
            onDismiss = { editing = null },
            onDone = { text, sizeIndex, colourIndex ->
                editing = null
                val size = e.keepSize?.takeIf { sizeIndex == e.sizeIndex } ?: scanMarkupSizes(ScanMarkupTool.Text)[sizeIndex]
                val colour = e.keepColour?.takeIf { colourIndex == e.colourIndex } ?: scanMarkupColours(ScanMarkupTool.Text)[colourIndex]
                sizeAt[3] = sizeIndex
                colourAt[3] = colourIndex
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

/** The text dialog's starting state: [index] of the box changed, or null for a new one at ([x], [y]). */
internal class ScanTextEdit(val index: Int?, val x: Float, val y: Float, val sizeIndex: Int, val colourIndex: Int, val text: String, val keepSize: Float? = null, val keepColour: Int? = null)

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

private fun DrawScope.drawMarkStroke(r: PageRect, tool: ScanMarkTool, colour: Int, width: Float, points: FloatArray) {
    val n = points.size / 3
    if (n == 0) return
    val full = width * r.long
    val (paint, blend) = when (tool) {
        // the eraser as it rubs: the page's paper is the engine's to find, so a light veil shows where
        ScanMarkTool.Erase -> Color.White.copy(alpha = 0.85f) to BlendMode.SrcOver
        ScanMarkTool.Highlighter -> Color(0xFF000000.toInt() or colour).copy(alpha = 0.85f) to BlendMode.Multiply
        ScanMarkTool.Pen -> Color(0xFF000000.toInt() or colour) to BlendMode.SrcOver
    }
    fun widthAt(i: Int) = if (tool == ScanMarkTool.Pen) full * (0.35f + 0.65f * points[i * 3 + 2]) else full
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

/** The chosen tool's three sizes and its colours; with a text box picked, Edit and Delete. */
@Composable
private fun ScanMarkupOptions(
    tool: ScanMarkupTool,
    size: Int,
    colour: Int,
    onSize: (Int) -> Unit,
    onColour: (Int) -> Unit,
    selectedText: ScanTextBox?,
    onEditText: () -> Unit,
    onDeleteText: () -> Unit,
) {
    val c = pdfColors
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (selectedText != null) {
            TextButton(onClick = onEditText) { Text(stringResource(R.string.scan_markup_edit_text), color = c.accent) }
            TextButton(onClick = onDeleteText) { Text(stringResource(R.string.scan_markup_delete_text), color = c.accent) }
            return@Row
        }
        val labels = listOf(R.string.scan_markup_size_small, R.string.scan_markup_size_medium, R.string.scan_markup_size_large)
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
        val colours = scanMarkupColours(tool)
        if (colours.isNotEmpty()) Spacer(Modifier.width(10.dp))
        for ((i, rgb) in colours.withIndex()) {
            val name = stringResource(R.string.scan_markup_colour, i + 1)
            val on = i == colour
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF000000.toInt() or rgb))
                    .then(if (on) Modifier.border(3.dp, c.accent, CircleShape) else Modifier.border(1.dp, c.divider, CircleShape))
                    .clickable(role = Role.RadioButton, onClick = { onColour(i) }).semantics { contentDescription = name; selected = on },
            )
        }
    }
}

/** Add or change a text box: the words, one of three sizes and a colour. An empty text removes a box. */
@Composable
private fun ScanTextDialog(edit: ScanTextEdit, onDismiss: () -> Unit, onDone: (String, Int, Int) -> Unit) {
    val c = pdfColors
    var text by remember { mutableStateOf(edit.text) }
    var size by remember { mutableStateOf(edit.sizeIndex) }
    var colour by remember { mutableStateOf(edit.colourIndex) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (edit.index == null) R.string.scan_markup_add_text else R.string.scan_markup_edit_text)) },
        text = {
            Column {
                OutlinedTextField(text, { text = it.take(500) }, label = { Text(stringResource(R.string.scan_markup_text)) }, modifier = Modifier.fillMaxWidth(), maxLines = 4)
                Spacer(Modifier.height(8.dp))
                ScanMarkupOptions(ScanMarkupTool.Text, size, colour, { size = it }, { colour = it }, null, {}, {})
            }
        },
        confirmButton = { TextButton(onClick = { onDone(text, size, colour) }) { Text(stringResource(R.string.common_done), color = c.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), color = c.accent) } },
    )
}

private fun scanMarkupToolLabel(t: ScanMarkupTool): Int = when (t) {
    ScanMarkupTool.Erase -> R.string.scan_markup_erase
    ScanMarkupTool.Pen -> R.string.scan_markup_pen
    ScanMarkupTool.Highlighter -> R.string.scan_markup_highlighter
    ScanMarkupTool.Text -> R.string.scan_markup_text
}

private fun scanMarkupToolIcon(t: ScanMarkupTool) = when (t) {
    ScanMarkupTool.Erase -> LucideIcons.Eraser
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
