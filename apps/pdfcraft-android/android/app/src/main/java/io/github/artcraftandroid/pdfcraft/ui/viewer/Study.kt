package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.core.CommentInfo
import io.github.artcraftandroid.pdfcraft.text
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import io.github.artcraftandroid.pdfcraft.ui.PdfSheet
import io.github.artcraftandroid.pdfcraft.ui.SheetBackHeader
import io.github.artcraftandroid.pdfcraft.ui.SheetRow
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import kotlinx.coroutines.launch

// ---- Masking tape and quiz mode (#143) ---------------------------------------------------------
//
// Tape is a rectangle annotation the engine draws invisibly (opacity 0) marked with [TAPE_MARK];
// PdfCraft draws the tape over it and a tap lifts or lays it again, which changes nothing in the
// file. Other readers, prints and exports show the page without tape. Quiz mode covers highlights
// with temporary masks that exist only in the viewer.

/** The text a tape annotation carries: how PdfCraft knows its own tape again. */
const val TAPE_MARK = "PdfCraft tape"

/** Tape colours: amber, blue, lilac and green, all dark enough to hide black text. */
val TAPE_COLORS = listOf(0xE0A526, 0x4F8FCF, 0x8E7CC3, 0x5BA65B)

/** Quiz masks: a slate that reads as "hidden" on paper and in Night Mode. */
private const val QUIZ_MASK_COLOR = 0x56657F

/** Whether a comment is PdfCraft's tape: an invisible rectangle marked [TAPE_MARK]. */
fun isTape(subtype: String, opacity: Float, contents: String?): Boolean =
    subtype == "Square" && opacity < 0.01f && contents == TAPE_MARK

fun CommentInfo.isTape(): Boolean = isTape(subtype, opacity, contents) && replyTo == null

/** The comment_add arguments for tape over [rect] (points, top-left origin) on [page] (0-based). */
fun tapeArgs(page: Int, rect: FloatArray, color: Int, author: String?): Map<String, Any?> {
    val hex = "#%06X".format(color and 0xFFFFFF)
    return mapOf(
        "page" to page + 1, "type" to "rectangle", "rect" to rect.map { it.toDouble() },
        "color" to hex, "fill" to hex, "opacity" to 0.0, "width" to 0.0,
        "contents" to TAPE_MARK, "author" to author,
    )
}

/** The rectangle two corners span, or null when under 4 points either way. */
internal fun tapeRectOf(x0: Float, y0: Float, x1: Float, y1: Float): FloatArray? {
    if (!(x0.isFinite() && y0.isFinite() && x1.isFinite() && y1.isFinite())) return null
    val r = floatArrayOf(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1))
    return r.takeIf { it[2] - it[0] >= 4f && it[3] - it[1] >= 4f }
}

/** Something covered: tape, or a highlight in a quiz. [key] stays the same while the file doesn't change. */
data class StudyMask(val key: String, val page: Int, val rect: List<Float>, val color: Int, val label: String? = null)

/** A comment's key: its own name, else where it is. */
internal fun maskKey(name: String?, page: Int, index: Int): String = name?.takeIf { it.isNotEmpty() } ?: "p$page#$index"

/** The mask under ([x], [y]) on [page], the topmost first; masks take a point of slack. */
internal fun maskAt(masks: List<StudyMask>, page: Int, x: Float, y: Float): StudyMask? =
    masks.lastOrNull { m -> m.page == page && m.rect.size == 4 && x in (m.rect[0] - 1)..(m.rect[2] + 1) && y in (m.rect[1] - 1)..(m.rect[3] + 1) }

/** The highlight types a quiz covers: what students mark the answers with. */
private val QUIZ_TYPES = setOf("Highlight", "Underline", "Squiggly")

/** A quiz's items from [comments]: every highlight, or [page]'s alone, in reading order. */
fun quizItems(comments: List<CommentInfo>, page: Int?): List<StudyMask> =
    quizItemsBy(comments, page, { it.subtype }, { it.page.toInt() }, { it.index.toInt() }, { it.rect }, { it.replyTo != null || it.state != null || it.fillSign }, { it.name }, { it.contents })

internal fun <T> quizItemsBy(
    comments: List<T>, page: Int?, subtype: (T) -> String, pageOf: (T) -> Int, indexOf: (T) -> Int, rect: (T) -> List<Float>,
    skip: (T) -> Boolean, name: (T) -> String?, contents: (T) -> String?,
): List<StudyMask> = comments
    .filter { !skip(it) && subtype(it) in QUIZ_TYPES && rect(it).size == 4 && (page == null || pageOf(it) == page) }
    .sortedWith(compareBy({ pageOf(it) }, { rect(it)[1] }, { rect(it)[0] }))
    .map { StudyMask(maskKey(name(it), pageOf(it), indexOf(it)), pageOf(it), rect(it), QUIZ_MASK_COLOR, contents(it)?.trim()?.takeIf(String::isNotEmpty)) }

/**
 * A quiz over [items]: which are uncovered, and which the reader knew (true) or didn't (false).
 * Immutable, so each answer is a new state the viewer redraws from.
 */
data class QuizSession(
    val items: List<StudyMask>,
    val revealed: Set<String> = emptySet(),
    val answers: Map<String, Boolean> = emptyMap(),
    /** The item uncovered last, which the Knew it / Didn't know buttons answer. */
    val current: String? = null,
) {
    val masks: List<StudyMask> get() = items.filter { it.key !in revealed }
    val known: Int get() = answers.count { it.value }
    val unknown: List<StudyMask> get() = items.filter { answers[it.key] == false }
    val done: Boolean get() = items.isNotEmpty() && answers.size == items.size
    /** The current item, while it waits for an answer. */
    val awaiting: StudyMask? get() = current?.takeIf { it !in answers }?.let { k -> items.firstOrNull { it.key == k } }
    /** The first item still covered, from the start: where Next goes. */
    val next: StudyMask? get() = items.firstOrNull { it.key !in revealed }

    fun reveal(key: String): QuizSession =
        if (items.none { it.key == key }) this else copy(revealed = revealed + key, current = key)

    /** Cover [key] again (a tap on an uncovered item); its answer stays. */
    fun cover(key: String): QuizSession = copy(revealed = revealed - key, current = if (current == key) null else current)

    fun answer(knew: Boolean): QuizSession {
        val k = awaiting?.key ?: return this
        return copy(answers = answers + (k to knew))
    }

    /** A new quiz over the items the reader didn't know. */
    fun retryUnknown(): QuizSession = QuizSession(unknown)
}

/** One document's study state: what tape is lifted, tape drawing, and a quiz. Nothing here is saved. */
@Stable
class StudyState {
    /** Tape lifted by a tap, by key; [allLifted] lifts every piece (Reveal all). */
    val lifted = mutableStateMapOf<String, Boolean>()
    var allLifted by mutableStateOf(false)
    /** Draw tape: a drag on the page lays a strip. */
    var drawingTape by mutableStateOf(false)
    var tapeColor by mutableStateOf(TAPE_COLORS.first())
    var quiz by mutableStateOf<QuizSession?>(null)
    /** The quiz's results sheet is showing. */
    var results by mutableStateOf(false)

    fun isLifted(key: String) = allLifted || lifted[key] == true
}

/** The tape on [doc]'s loaded pages, lifted or not. */
fun tapes(doc: OpenDocument): List<StudyMask> = doc.comments.filter { it.isTape() }.map { c ->
    StudyMask(maskKey(c.name, c.page.toInt(), c.index.toInt()), c.page.toInt(), c.rect, (c.color ?: TAPE_COLORS.first().toUInt()).toInt())
}

/** What covers [doc]'s pages now: tape still laid, then the quiz's masks. */
fun studyMasks(doc: OpenDocument): List<StudyMask> {
    val s = doc.study
    return tapes(doc).filter { !s.isLifted(it.key) } + s.quiz?.masks.orEmpty()
}

// ---- The view model's part ------------------------------------------------------------------

/** A tap on a mask: tape lifts or is laid again, a quiz item uncovers or covers. False when none is there. */
fun AppViewModel.tapStudyMask(doc: OpenDocument, at: PagePoint?): Boolean {
    if (at == null) return false
    val s = doc.study
    // quiz items first: they sit on top
    s.quiz?.let { q ->
        maskAt(q.items, at.page, at.x, at.y)?.let { m ->
            s.quiz = if (m.key in q.revealed) q.cover(m.key) else q.reveal(m.key)
            return true
        }
    }
    val tape = maskAt(tapes(doc), at.page, at.x, at.y) ?: return false
    toggleTape(doc, tape.key)
    return true
}

fun toggleTape(doc: OpenDocument, key: String) {
    val s = doc.study
    if (s.isLifted(key)) {
        if (s.allLifted) {
            // lay this one again, the rest stay lifted
            s.allLifted = false
            tapes(doc).forEach { s.lifted[it.key] = true }
        }
        s.lifted.remove(key)
    } else {
        s.lifted[key] = true
    }
}

/** Reveal all ([lifted]) or Cover all. */
fun setAllTapeLifted(doc: OpenDocument, lifted: Boolean) {
    doc.study.lifted.clear()
    doc.study.allLifted = lifted
}

/** Lay a strip of tape over [rect] on [page]: one undo step. */
fun AppViewModel.addTape(doc: OpenDocument, page: Int, rect: FloatArray) {
    if (!canEditDocument(doc)) {
        tell(text(R.string.study_tape_read_only))
        return
    }
    runTool(doc, "comment_add", tapeArgs(page, rect, doc.study.tapeColor, commentAuthor))
}

/** Remove every piece of tape in the document (one undo step per page). */
fun AppViewModel.removeAllTape(doc: OpenDocument) {
    viewModelScope.launch {
        val all = comments(doc) ?: return@launch
        val byPage = all.filter { it.isTape() }.groupBy { it.page.toInt() }
        if (byPage.isEmpty()) {
            tell(text(R.string.study_tape_none))
            return@launch
        }
        for ((page, list) in byPage) {
            runTool(doc, "comments_edit_many", mapOf("page" to page + 1, "comments" to list.map { it.index.toInt() + 1 }, "delete" to true))
        }
        setAllTapeLifted(doc, false)
        tell(text(R.string.study_tape_removed))
    }
}

/** Start a quiz on [page]'s highlights (0-based), or the whole document's when null. */
fun AppViewModel.startQuiz(doc: OpenDocument, page: Int?) {
    viewModelScope.launch {
        val all = if (page == null) comments(doc) ?: return@launch else doc.comments
        val items = quizItems(all, page)
        if (items.isEmpty()) {
            tell(text(if (page == null) R.string.study_quiz_none_document else R.string.study_quiz_none_page))
            return@launch
        }
        doc.study.results = false
        doc.study.quiz = QuizSession(items)
        if (page == null) doc.viewer.goToPage(items.first().page, items.first().rect[1])
    }
}

fun endQuiz(doc: OpenDocument) {
    doc.study.quiz = null
    doc.study.results = false
}

// ---- Drawing ---------------------------------------------------------------------------------

/** Tape and quiz masks over the pages: opaque, with diagonal stripes so they read as tape. */
fun DrawScope.drawStudyMasks(doc: OpenDocument) {
    val masks = studyMasks(doc)
    val s = doc.study
    // the quiz item that waits for an answer gets an outline
    val awaiting = s.quiz?.awaiting
    if (masks.isEmpty() && awaiting == null) return
    for (m in masks) {
        val a = doc.viewer.pageToScreen(m.page, m.rect[0], m.rect[1])
        val b = doc.viewer.pageToScreen(m.page, m.rect[2], m.rect[3])
        if (b.x < 0 || b.y < 0 || a.x > size.width || a.y > size.height) continue
        val w = b.x - a.x
        val h = b.y - a.y
        val base = Color(0xFF000000 or m.color.toLong())
        drawRect(base, a, Size(w, h))
        val stripe = Color.White.copy(alpha = 0.18f)
        val step = 10.dp.toPx()
        clipRect(a.x, a.y, b.x, b.y) {
            var x = a.x - h
            while (x < b.x) {
                drawLine(stripe, Offset(x, b.y), Offset(x + h, a.y), strokeWidth = 3.dp.toPx())
                x += step
            }
        }
        drawRect(Color.Black.copy(alpha = 0.25f), a, Size(w, h), style = Stroke(1.dp.toPx()))
    }
    awaiting?.let { m ->
        val a = doc.viewer.pageToScreen(m.page, m.rect[0], m.rect[1])
        val b = doc.viewer.pageToScreen(m.page, m.rect[2], m.rect[3])
        val pad = 2.dp.toPx()
        drawRect(Color(0xFF1473E6), Offset(a.x - pad, a.y - pad), Size(b.x - a.x + 2 * pad, b.y - a.y + 2 * pad), style = Stroke(2.dp.toPx()))
    }
}

// ---- Composables -----------------------------------------------------------------------------

/**
 * The study layer over the pages: TalkBack nodes for each mask (they take no touches, so the
 * page still scrolls under them), Draw tape, and the quiz bar and results.
 */
@Composable
fun BoxScope.StudyLayer(vm: AppViewModel, doc: OpenDocument) {
    val s = doc.study
    MaskSemantics(doc)
    if (s.drawingTape) {
        TapeDrawLayer(vm, doc)
        TapeBar(doc, Modifier.align(Alignment.BottomCenter))
    }
    s.quiz?.let { q ->
        if (s.results || q.done) QuizResultsSheet(doc, q)
        else QuizBar(doc, q, Modifier.align(Alignment.BottomCenter))
    }
}

/** An accessibility node over each tape and quiz item: "Hidden answer, double-tap to reveal". */
@Composable
private fun MaskSemantics(doc: OpenDocument) {
    val s = doc.study
    val all = tapes(doc).map { it to !s.isLifted(it.key) } +
        s.quiz?.items.orEmpty().map { it to (it.key !in s.quiz!!.revealed) }
    if (all.isEmpty()) return
    val hidden = stringResource(R.string.study_hidden_answer)
    val shown = stringResource(R.string.study_shown_answer)
    val reveal = stringResource(R.string.study_reveal)
    val cover = stringResource(R.string.study_cover)
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize()) {
        for ((m, covered) in all) {
            val a = doc.viewer.pageToScreen(m.page, m.rect[0], m.rect[1])
            val b = doc.viewer.pageToScreen(m.page, m.rect[2], m.rect[3])
            if (b.x - a.x < 1f || b.y - a.y < 1f) continue
            Box(
                Modifier.offset { IntOffset(a.x.toInt(), a.y.toInt()) }
                    .size(with(density) { (b.x - a.x).toDp() }, with(density) { (b.y - a.y).toDp() })
                    .semantics {
                        contentDescription = if (covered) hidden else shown
                        onClick(if (covered) reveal else cover) {
                            val q = s.quiz
                            if (q != null && q.items.any { it.key == m.key }) s.quiz = if (covered) q.reveal(m.key) else q.cover(m.key)
                            else toggleTape(doc, m.key)
                            true
                        }
                    },
            )
        }
    }
}

/** Draw tape: drag across what to cover. */
@Composable
private fun TapeDrawLayer(vm: AppViewModel, doc: OpenDocument) {
    var start by remember(doc) { mutableStateOf<PagePoint?>(null) }
    var end by remember(doc) { mutableStateOf<PagePoint?>(null) }
    val color = Color(0xFF000000 or doc.study.tapeColor.toLong())
    Box(
        Modifier.fillMaxSize().toolGestures(
            doc,
            onDown = { p, _, _ ->
                start = doc.viewer.screenToPage(p)
                end = start
            },
            onMove = { p, _, _ ->
                val s = start ?: return@toolGestures
                end = doc.viewer.screenToPageOf(s.page, p) ?: end
            },
            onUp = { _, moved ->
                val s = start
                val e = end
                start = null
                end = null
                if (s == null || e == null || !moved) return@toolGestures
                tapeRectOf(s.x, s.y, e.x, e.y)?.let { vm.addTape(doc, s.page, it) }
            },
            onCancel = {
                start = null
                end = null
            },
        ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val s = start ?: return@Canvas
            val e = end ?: return@Canvas
            val r = tapeRectOf(s.x, s.y, e.x, e.y) ?: return@Canvas
            val a = doc.viewer.pageToScreen(s.page, r[0], r[1])
            val b = doc.viewer.pageToScreen(s.page, r[2], r[3])
            drawRect(color.copy(alpha = 0.85f), a, Size(b.x - a.x, b.y - a.y))
        }
    }
}

/** While drawing tape: the colours and Done. */
@Composable
private fun TapeBar(doc: OpenDocument, modifier: Modifier) {
    val c = pdfColors
    val s = doc.study
    Row(
        modifier.navigationBarsPadding().padding(bottom = 24.dp).clip(RoundedCornerShape(24.dp)).background(c.menu)
            .border(0.5.dp, c.divider, RoundedCornerShape(24.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.study_tape_hint), color = c.text, fontSize = 14.sp, modifier = Modifier.widthIn(max = 180.dp), maxLines = 2)
        val names = listOf(R.string.study_color_amber, R.string.study_color_blue, R.string.study_color_lilac, R.string.study_color_green)
        TAPE_COLORS.forEachIndexed { i, col ->
            val picked = s.tapeColor == col
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(Color(0xFF000000 or col.toLong()))
                    .border(if (picked) 3.dp else 0.dp, c.accent, CircleShape)
                    .clickable(role = Role.RadioButton, onClickLabel = stringResource(names[i])) { s.tapeColor = col }
                    .semantics { contentDescription = text(names[i]) },
            )
        }
        Text(
            stringResource(R.string.common_done), color = c.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button) { s.drawingTape = false }
                .heightIn(min = 40.dp).padding(horizontal = 8.dp, vertical = 10.dp),
        )
    }
}

/** The quiz's bar: progress, Knew it / Didn't know for the item just uncovered, Next and End. */
@Composable
private fun QuizBar(doc: OpenDocument, q: QuizSession, modifier: Modifier) {
    val c = pdfColors
    val s = doc.study
    Column(
        modifier.navigationBarsPadding().padding(bottom = 24.dp, start = 16.dp, end = 16.dp).widthIn(max = 480.dp).fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)).background(c.menu).border(0.5.dp, c.divider, RoundedCornerShape(16.dp))
            .padding(12.dp),
    ) {
        Text(
            stringResource(R.string.study_quiz_progress, q.answers.size, q.items.size),
            color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        )
        val awaiting = q.awaiting
        Text(
            if (awaiting != null) stringResource(R.string.study_quiz_did_you_know) else stringResource(R.string.study_quiz_hint),
            color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (awaiting != null) {
                QuizButton(stringResource(R.string.study_quiz_knew), Color(0xFF2E7D32)) { s.quiz = q.answer(true) }
                QuizButton(stringResource(R.string.study_quiz_didnt), Color(0xFFC62828)) { s.quiz = q.answer(false) }
            } else {
                q.next?.let { n -> QuizButton(stringResource(R.string.study_quiz_next), c.accent) { doc.viewer.goToPage(n.page, n.rect[1]) } }
            }
            Spacer(Modifier.weight(1f))
            QuizButton(stringResource(R.string.study_quiz_end), c.textSecondary, filled = false) {
                if (q.answers.isEmpty()) endQuiz(doc) else s.results = true
            }
        }
    }
}

@Composable
private fun QuizButton(label: String, color: Color, filled: Boolean = true, onClick: () -> Unit) {
    Text(
        label, color = if (filled) Color.White else color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(if (filled) color else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick).heightIn(min = 40.dp).padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

/** At the end: how many the reader knew, and the ones they didn't, each a link to its page. */
@Composable
private fun QuizResultsSheet(doc: OpenDocument, q: QuizSession) {
    val c = pdfColors
    val s = doc.study
    PdfSheet(onDismiss = { endQuiz(doc) }, dim = true) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.study_results_title), color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            Text(
                stringResource(R.string.study_results_score, q.known, q.items.size),
                color = c.text, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 16.dp),
            )
            val unknown = q.unknown
            if (unknown.isNotEmpty()) {
                Text(
                    pluralStringResource(R.plurals.study_results_unknown, unknown.size, unknown.size),
                    color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                )
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    for (m in unknown) {
                        SheetRow(
                            LucideIcons.CircleHelp,
                            stringResource(R.string.study_results_item, m.page + 1, m.label ?: stringResource(R.string.study_results_highlight)),
                            {
                                s.results = false
                                // back to the quiz with this one covered again, on its page
                                s.quiz = q.copy(revealed = q.revealed - m.key, answers = q.answers - m.key, current = null)
                                doc.viewer.goToPage(m.page, m.rect[1])
                            },
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (unknown.isNotEmpty()) QuizButton(stringResource(R.string.study_results_retry), c.accent) {
                    s.results = false
                    s.quiz = q.retryUnknown()
                    unknown.first().let { doc.viewer.goToPage(it.page, it.rect[1]) }
                }
                QuizButton(stringResource(R.string.common_done), c.textSecondary, filled = false) { endQuiz(doc) }
            }
        }
    }
}

/** More ▸ Study: tape and quiz. */
@Composable
fun StudySheet(vm: AppViewModel, doc: OpenDocument) {
    val c = pdfColors
    val ctl = doc.controller
    val s = doc.study
    fun close() { ctl.sheet = ViewerSheet.None }
    val anyTape = doc.comments.any { it.isTape() }
    PdfSheet(onDismiss = ::close, maxHeightFraction = 0.85f) {
        SheetBackHeader(stringResource(R.string.study_title), onBack = { ctl.sheet = ViewerSheet.More })
        Text(stringResource(R.string.study_explain), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SheetRow(LucideIcons.Bandage, stringResource(R.string.study_draw_tape), {
                close()
                s.drawingTape = true
            }, enabled = canEditDocument(doc))
            if (anyTape) {
                if (s.allLifted || s.lifted.isNotEmpty()) SheetRow(LucideIcons.EyeOff, stringResource(R.string.study_cover_all), { close(); setAllTapeLifted(doc, false) })
                if (!s.allLifted) SheetRow(LucideIcons.Eye, stringResource(R.string.study_reveal_all), { close(); setAllTapeLifted(doc, true) })
                SheetRow(LucideIcons.Trash2, stringResource(R.string.study_remove_tape), { close(); vm.removeAllTape(doc) }, enabled = canEditDocument(doc))
            }
            SheetRow(LucideIcons.CircleHelp, stringResource(R.string.study_quiz_page), { close(); vm.startQuiz(doc, doc.viewer.currentPage) })
            SheetRow(LucideIcons.ListChecks, stringResource(R.string.study_quiz_document), { close(); vm.startQuiz(doc, null) })
            if (s.quiz != null) SheetRow(LucideIcons.X, stringResource(R.string.study_quiz_end), { close(); endQuiz(doc) })
            Spacer(Modifier.size(16.dp))
        }
    }
}
