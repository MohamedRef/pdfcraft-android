package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.artcraftandroid.pdfcraft.core.TextRange
import io.github.artcraftandroid.pdfcraft.engine.Engine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The selected text on one page, its rectangles (points), and the glyphs its two handles sit on. */
data class Selection(val range: TextRange, val rects: List<FloatArray>, val startRect: FloatArray?, val endRect: FloatArray?)

/**
 * Selected text stays on one page. Long-press chooses a word, drag handles refine
 * its bounds, and a desktop mouse can select a span.
 */
@Stable
class TextSelectionState(private val engine: Engine, private val doc: ULong, private val scope: CoroutineScope) {
    var selection by mutableStateOf<Selection?>(null)
        private set

    var magnifierAt by mutableStateOf<androidx.compose.ui.geometry.Offset?>(null)

    // Increment whenever a selection is cleared or superseded, so slow native
    // responses cannot bring an old selection back after dismissal/navigation.
    private var revision = 0

    /** Select the word at [p]; false if no text lies within [slopPoints]. */
    suspend fun selectWordAt(p: PagePoint, slopPoints: Float): Boolean {
        val id = ++revision
        val range = withContext(engine.commands) {
            engine.session.wordAt(doc, p.page.toUInt(), p.x, p.y, slopPoints)
        } ?: return false
        if (id != revision) return false
        show(range, id)
        return id == revision && selection?.range == range
    }

    private var span: Pair<PagePoint, PagePoint>? = null
    private var spanJob: Job? = null

    /** Multiple mouse events are conflated to the newest endpoint. */
    fun selectSpan(from: PagePoint, to: PagePoint) {
        if (from.page != to.page) return
        span = from to to
        if (spanJob?.isActive == true) return
        val id = ++revision
        spanJob = scope.launch {
            while (true) {
                val (a, b) = span ?: break
                span = null
                val page = a.page.toUInt()
                val (ga, gb) = withContext(engine.commands) {
                    engine.session.nearestGlyph(doc, page, a.x, a.y) to
                        engine.session.nearestGlyph(doc, page, b.x, b.y)
                }
                if (id != revision) break
                if (span != null || ga == null || gb == null) continue
                show(TextRange(page, minOf(ga, gb), maxOf(ga, gb) + 1u), id)
            }
        }
    }

    fun selectPage(page: Int) {
        val id = ++revision
        scope.launch {
            val n = withContext(engine.commands) { engine.session.glyphCount(doc, page.toUInt()) }
            if (n == 0u || id != revision) return@launch
            show(TextRange(page.toUInt(), 0u, n), id)
        }
    }

    private data class HandleMove(val start: Boolean, val point: PagePoint, val fixed: UInt)
    private var handlePending: HandleMove? = null
    private var handleJob: Job? = null
    private var handleStart: Boolean? = null
    private var fixedGlyph: UInt? = null

    /** Capture the stationary end ONCE. It must not change when handles cross. */
    fun beginHandleDrag(start: Boolean) {
        val sel = selection ?: return
        handleJob?.cancel()
        handlePending = null
        handleStart = start
        fixedGlyph = if (start) sel.range.end - 1u else sel.range.start
        ++revision
    }

    /** Leave the last requested endpoint to finish, but reset the next gesture's anchor. */
    fun endHandleDrag() {
        handleStart = null
        fixedGlyph = null
        magnifierAt = null
    }

    /** Only one native query at a time; retain the latest finger position. */
    fun dragHandle(start: Boolean, p: PagePoint) {
        val sel = selection ?: return
        if (p.page.toUInt() != sel.range.page) return
        val fixed = if (handleStart == start) fixedGlyph else null
        handlePending = HandleMove(
            start, p, fixed ?: if (start) sel.range.end - 1u else sel.range.start,
        )
        if (handleJob?.isActive == true) return
        val id = revision
        handleJob = scope.launch {
            while (true) {
                val request = handlePending ?: break
                handlePending = null
                val g = withContext(engine.commands) {
                    engine.session.nearestGlyph(doc, request.point.page.toUInt(), request.point.x, request.point.y)
                } ?: continue
                if (id != revision) break
                if (handlePending != null) continue
                val a = minOf(request.fixed, g)
                val b = maxOf(request.fixed, g) + 1u
                show(TextRange(request.point.page.toUInt(), a, b), id)
            }
        }
    }

    /** Native text/geometry is validated before it becomes visible on screen. */
    private suspend fun show(range: TextRange, id: Int) {
        val (rects, start, end) = withContext(engine.commands) {
            Triple(
                engine.session.textRects(doc, range),
                engine.session.glyphRect(doc, range.page, range.start),
                engine.session.glyphRect(doc, range.page, range.end - 1u),
            )
        }
        if (id != revision) return
        val boxes = markupLineRects(rects.toList())
        if (boxes.isEmpty() || start.size != 4 || end.size != 4) return
        selection = Selection(range, boxes, start.toFloatArray(), end.toFloatArray())
    }

    suspend fun text(): String? {
        val sel = selection ?: return null
        return withContext(engine.commands) { engine.session.textOf(doc, sel.range) }
    }

    fun clear() {
        ++revision
        selection = null
        magnifierAt = null
        span = null
        spanJob?.cancel()
        spanJob = null
        handlePending = null
        handleJob?.cancel()
        handleJob = null
        handleStart = null
        fixedGlyph = null
    }
}
