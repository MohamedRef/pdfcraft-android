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

// Moved from Tools.kt as is (#730): the Highlight, Underline, Strike and Squiggly tools' drag over text.

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
internal suspend fun resolveMarkup(
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
internal fun MarkupLayer(vm: AppViewModel, doc: OpenDocument) {
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
