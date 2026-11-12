package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.core.CommentInfo
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.BarIcon
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import io.github.artcraftandroid.pdfcraft.ui.PdfSheet
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import kotlin.math.roundToInt

/** A note waiting for its text: at a point (Comment tool) or on selected text (Add comment). */
class NotePrompt(
    val doc: OpenDocument,
    val at: PagePoint?,
    val selection: Selection? = null,
    /** A note written onto the markup just added (its text), instead of a new comment (#197). */
    val edit: MarkupFollowUp? = null,
    /** Replace Text: the selection is struck through and this note is its replacement (#170). */
    val replace: Selection? = null,
    /** Insert Text: a caret after the selection carries this note (#170). */
    val insert: Selection? = null,
)

/**
 * A text markup just added from the selection menu: [page] and [index] (0-based) as the comment
 * tools number it, [bounds] (points) to place its bar by, and its [color] (#197).
 */
data class MarkupFollowUp(val doc: OpenDocument, val page: Int, val index: Int, val bounds: FloatArray, val color: androidx.compose.ui.graphics.Color) {
    override fun equals(other: Any?) = other is MarkupFollowUp && other.doc === doc && other.page == page && other.index == index && other.color == color
    override fun hashCode() = (page * 31 + index) * 31 + color.hashCode()
}

/** The follow-up bar's colours: the markup palette's five most used. */
internal val FOLLOW_UP_COLORS = listOf(0xFFFFD100, 0xFF7ED957, 0xFF6EC6FF, 0xFFFF8AD8, 0xFFFF6B6B).map { androidx.compose.ui.graphics.Color(it) }

/** Free text waiting to be typed at a point (Text tool). */
class TextPrompt(val doc: OpenDocument, val at: PagePoint, val callout: Boolean = false)

/** A reply waiting to be written. */
class ReplyPrompt(val doc: OpenDocument, val comment: CommentInfo)

/**
 * The scrubber on the right edge: a pill with the page number that follows the scroll position
 * and can be dragged to move through the document; a tap opens Go to page.
 */
@Composable
fun BoxScope.Scrubber(doc: OpenDocument, visible: Boolean, top: Dp, bottom: Dp, onDrag: () -> Unit, onTap: () -> Unit) {
    val c = pdfColors
    val v = doc.viewer
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    AnimatedVisibility(visible || dragging, modifier = Modifier.align(Alignment.TopEnd), enter = fadeIn(tween(150)), exit = fadeOut(tween(300))) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(top = top + 8.dp, bottom = bottom + 8.dp)) {
            val track = with(density) { maxHeight.toPx() }
            val pillH = with(density) { 32.dp.toPx() }
            val maxScroll = (v.layout.height * v.zoom - v.viewH).coerceAtLeast(1f)
            val fraction = (v.scrollY / maxScroll).coerceIn(0f, 1f)
            val y = fraction * (track - pillH)
            val shown = pageLabelOf(doc, v.currentPage)
            val label = shown ?: "${v.currentPage + 1}"
            val pageOf = if (shown != null) stringResource(R.string.viewer_page_label_of, shown, v.currentPage + 1, doc.pageCount) else stringResource(R.string.viewer_page_of, v.currentPage + 1, doc.pageCount)
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, y.roundToInt()) }
                    .height(32.dp)
                    .widthIn(min = if (label.length >= 3) 52.dp else 40.dp, max = 140.dp)
                    .shadow(2.dp, RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                    .background(c.surface, RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                    .pointerInput(doc) {
                        detectTapGestures(onTap = { onTap() })
                    }
                    .pointerInput(doc) {
                        detectDragGestures(
                            onDragStart = { dragging = true },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false },
                        ) { change, drag ->
                            change.consume()
                            val newFraction = ((v.scrollY / maxScroll) + drag.y / (track - pillH).coerceAtLeast(1f)).coerceIn(0f, 1f)
                            v.setScroll(v.scrollX, newFraction * maxScroll)
                            onDrag()
                        }
                    }
                    .semantics { contentDescription = pageOf },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = c.text, fontSize = 15.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp, end = 10.dp))
            }
        }
    }
}

/** The label shown for [page] (0-based) when the document labels its pages other than 1 to N, else null. */
@Composable
internal fun pageLabelOf(doc: OpenDocument, page: Int): String? {
    val custom = androidx.compose.runtime.remember(doc.info.generation) { io.github.artcraftandroid.pdfcraft.ui.hasCustomLabels(doc.info.pages.map { it.label }) }
    return if (custom) doc.info.pages.getOrNull(page)?.label?.takeIf { it.isNotEmpty() } else null
}

/** Go to page: a page label or number from 1 to N, and the way back to where the last jump came from. */
@Composable
fun GoToPageDialog(doc: OpenDocument, onDismiss: () -> Unit) {
    val c = pdfColors
    var text by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val ctl = doc.controller
    val previous = remember(ctl.jumpsVersion) { ctl.jumps.backTarget }
    val labels = remember(doc.info.generation) { doc.info.pages.map { it.label } }
    val custom = remember(labels) { io.github.artcraftandroid.pdfcraft.ui.hasCustomLabels(labels) }
    fun go() {
        // a page label first when the document has them (as Acrobat), else the plain number
        val page = if (custom) io.github.artcraftandroid.pdfcraft.ui.pageForEntry(labels, text) else parsePageNumber(text, doc.pageCount)
        // whether the entry was a page label or a number, and whether it was refused (#203)
        io.github.artcraftandroid.pdfcraft.engine.Diag.event(io.github.artcraftandroid.pdfcraft.engine.DiagArea.Viewer, "go_to_page", "doc" to doc.diagNumber, "labels" to custom, "ok" to (page != null), "page_count" to doc.pageCount)
        if (page == null) {
            // keep what was typed, and say what is allowed
            wrong = true
            return
        }
        ctl.rememberJump(doc.viewer)
        doc.viewer.goToPage(page)
        onDismiss()
    }
    io.github.artcraftandroid.pdfcraft.ui.PdfDialog(onDismiss = onDismiss, title = stringResource(R.string.viewer_go_to_page), confirm = stringResource(R.string.common_ok), onConfirm = ::go, confirmEnabled = text.isNotEmpty()) {
            Column {
                Text(
                    if (custom) stringResource(R.string.viewer_go_to_page_labels, labels.first(), labels.last(), doc.pageCount) else stringResource(R.string.viewer_go_to_page_range, doc.pageCount),
                    color = c.textSecondary, fontSize = 14.sp,
                )
                Spacer(Modifier.height(10.dp))
                io.github.artcraftandroid.pdfcraft.ui.PdfField(
                    text, { text = (if (custom) it.filterNot(Char::isISOControl) else it.filter(Char::isDigit)).take(if (custom) 64 else 6); wrong = false }, stringResource(R.string.viewer_enter_page_number), error = wrong, focus = focus,
                    keyboardOptions = KeyboardOptions(keyboardType = if (custom) KeyboardType.Text else KeyboardType.Number, imeAction = androidx.compose.ui.text.input.ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { go() }),
                )
                if (wrong) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.viewer_go_to_page_invalid, doc.pageCount),
                        color = c.red, fontSize = 13.sp,
                        modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
                    )
                }
                if (previous != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.viewer_back_to_page, previous.page + 1),
                        color = c.accent,
                        modifier = Modifier.clickable {
                            ctl.jumpBack(doc.viewer)
                            onDismiss()
                        }.padding(vertical = 4.dp),
                    )
                }
            }
    }
}

/** Ctrl+/: the viewer's keyboard shortcuts (#153). */
@Composable
fun ShortcutsDialog(onDismiss: () -> Unit) {
    val c = pdfColors
    val context = androidx.compose.ui.platform.LocalContext.current
    io.github.artcraftandroid.pdfcraft.ui.PdfDialog(onDismiss = onDismiss, title = stringResource(R.string.shortcut_help), confirm = stringResource(R.string.common_ok), onConfirm = onDismiss, dismiss = null) {
        androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(SHORTCUTS.size) { i ->
                val e = SHORTCUTS[i]
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(e.label), color = c.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text(
                        keyLabel(context, e), color = c.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.background(c.pressed, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

/**
 * The way back after a jump: "‹ Page 12" (and "Page 87 ›" once back) above the bottom bar, for a
 * few seconds after a jump and again when scrolling stops. TalkBack announces it.
 */
@Composable
fun BoxScope.JumpChip(doc: OpenDocument, visible: Boolean, bottom: Dp) {
    val c = pdfColors
    val ctl = doc.controller
    val version = ctl.jumpsVersion
    val back = remember(version) { ctl.jumps.backTarget }
    val forward = remember(version) { ctl.jumps.forwardTarget }
    if (!visible || (back == null && forward == null)) return
    Row(
        Modifier.align(Alignment.BottomCenter).padding(bottom = bottom + 12.dp)
            .shadow(3.dp, RoundedCornerShape(20.dp))
            .background(c.surface, RoundedCornerShape(20.dp))
            .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (back != null) {
            val label = stringResource(R.string.viewer_jump_back, back.page + 1)
            Row(
                Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button) { ctl.jumpBack(doc.viewer) }
                    .semantics(mergeDescendants = true) { contentDescription = label }
                    .heightIn(min = 40.dp).padding(start = 8.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Icon(LucideIcons.ChevronLeft, null, tint = c.accent, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.viewer_page_short, back.page + 1), color = c.accent, fontSize = 15.sp)
            }
        }
        if (back != null && forward != null) Box(Modifier.width(1.dp).height(24.dp).background(c.divider))
        if (forward != null) {
            val label = stringResource(R.string.viewer_jump_forward, forward.page + 1)
            Row(
                Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button) { ctl.jumpForward(doc.viewer) }
                    .semantics(mergeDescendants = true) { contentDescription = label }
                    .heightIn(min = 40.dp).padding(start = 14.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.viewer_page_short, forward.page + 1), color = c.accent, fontSize = 15.sp)
                androidx.compose.material3.Icon(LucideIcons.ChevronRight, null, tint = c.accent, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/**
 * The menu by selected text: Add comment, Highlight, Underline and Strikethrough as icons, then
 * Edit text, Copy text and Read aloud. Like the reference's it sits below the selection when there
 * is room, else above.
 */
@Composable
fun BoxScope.SelectionMenu(vm: AppViewModel, doc: OpenDocument, sel: Selection) {
    val c = pdfColors
    val density = LocalDensity.current
    val page = sel.range.page.toInt()
    val first = sel.rects.firstOrNull() ?: return
    val last = sel.rects.lastOrNull() ?: return
    val top = doc.viewer.pageToScreen(page, first[0], first[1])
    val bottom = doc.viewer.pageToScreen(page, last[2], last[3])
    var menuSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val margin = with(density) { 29.dp.toPx() }
    val maxBottom = doc.viewer.viewH - doc.viewer.padBottom
    val below = bottom.y + margin
    val y = if (below + menuSize.height < maxBottom) below else (top.y - margin - menuSize.height).coerceAtLeast(doc.viewer.padTop)
    val x = ((top.x + bottom.x) / 2f - menuSize.width / 2f).coerceIn(with(density) { 8.dp.toPx() }, (doc.viewer.viewW - menuSize.width - with(density) { 8.dp.toPx() }).coerceAtLeast(0f))
    val allowed = doc.info.allowsAnnotation
    val prefs = vm.toolPrefs
    fun markup(m: TextMarkup) {
        if (!allowed) return
        vm.addSelectedMarkup(doc, m, page, sel.rects, prefs.color(m.styled), prefs.opacity(m.styled))
        doc.selection.clear()
    }
    Column(
        Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .onSizeChanged { menuSize = it }
            .width(252.dp)
            .shadow(6.dp, RoundedCornerShape(4.dp))
            .background(c.menu, RoundedCornerShape(4.dp)),
    ) {
        // each icon has a short label under it, and Highlight a dot of the colour it will use (#197)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
            MenuIcon(LucideIcons.MessageSquarePlus, stringResource(R.string.viewer_add_comment), allowed, short = stringResource(R.string.viewer_menu_note)) { vm.notePrompt = NotePrompt(doc, null, sel) }
            MenuIcon(LucideIcons.Highlighter, stringResource(R.string.common_tool_highlight), allowed, short = stringResource(R.string.viewer_menu_highlight), dot = prefs.color(TextMarkup.Highlight.styled)) { markup(TextMarkup.Highlight) }
            MenuIcon(LucideIcons.Underline, stringResource(R.string.common_tool_underline), allowed, short = stringResource(R.string.viewer_menu_underline)) { markup(TextMarkup.Underline) }
            MenuIcon(LucideIcons.Strikethrough, stringResource(R.string.viewer_strikethrough), allowed, short = stringResource(R.string.viewer_menu_strike)) { markup(TextMarkup.Strikethrough) }
            MenuIcon(LucideIcons.Spline, stringResource(R.string.toolbar_squiggly), allowed, short = stringResource(R.string.viewer_menu_squiggly)) { markup(TextMarkup.Squiggly) }
        }
        // an inset divider, then the rows with 8 dp above and below, as the reference's menu
        Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp).height(1.dp).background(c.divider))
        Spacer(Modifier.height(8.dp))
        if (allowed) {
            MenuRow(LucideIcons.Replace, stringResource(R.string.viewer_replace_text)) { vm.notePrompt = NotePrompt(doc, null, replace = sel) }
            MenuRow(LucideIcons.TextCursorInput, stringResource(R.string.viewer_insert_text)) { vm.notePrompt = NotePrompt(doc, null, insert = sel) }
        }
        if (doc.writable || doc.source == null) MenuRow(LucideIcons.FilePenLine, stringResource(R.string.viewer_edit_text)) {
            // Edit PDF opens on the paragraph the selection is in, not in general (#197)
            val r = sel.rects.first()
            val at = PagePoint(page, (r[0] + r[2]) / 2f, (r[1] + r[3]) / 2f)
            doc.selection.clear()
            vm.enterTool(doc, ViewerTool.Edit)
            if (doc.controller.tool == ViewerTool.Edit) vm.editTap(doc, at)
        }
        if (doc.info.allowsCopying) MenuRow(LucideIcons.Copy, stringResource(R.string.viewer_copy_text)) { vm.copySelection(doc) }
        MenuRow(LucideIcons.SwatchBook, stringResource(R.string.cards_make)) { vm.makeCardFromSelection(doc, cloze = false) }
        MenuRow(LucideIcons.SwatchBook, stringResource(R.string.cards_make_cloze)) { vm.makeCardFromSelection(doc, cloze = true) }
        // while reading aloud, the reading moves to the selection (the reference's "from here")
        MenuRow(LucideIcons.Volume2, stringResource(if (vm.readAloud != null) R.string.viewer_read_from_here else R.string.common_tool_read_aloud)) {
            vm.startReadAloud(doc, fromSelection = true)
            doc.selection.clear()
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun MenuIcon(icon: ImageVector, label: String, enabled: Boolean, short: String? = null, dot: androidx.compose.ui.graphics.Color? = null, onClick: () -> Unit) {
    val c = pdfColors
    val tint = if (enabled) c.icon else c.icon.copy(alpha = 0.35f)
    Column(
        Modifier.widthIn(min = 40.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics(mergeDescendants = true) { contentDescription = label }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
            if (dot != null) Box(Modifier.align(Alignment.BottomEnd).size(9.dp).background(c.menu, CircleShape).padding(1.5.dp).background(dot, CircleShape))
        }
        if (short != null) Text(short, color = tint, fontSize = 10.sp, maxLines = 1, modifier = Modifier.clearAndSetSemantics {})
    }
}

/**
 * After a text markup from the selection menu: five colours, Add note and Delete, by the new
 * markup (#197). Any other tap on the page dismisses it.
 */
@Composable
fun BoxScope.MarkupFollowUpBar(vm: AppViewModel, f: MarkupFollowUp) {
    val c = pdfColors
    val density = LocalDensity.current
    val doc = f.doc
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val a = doc.viewer.pageToScreen(f.page, f.bounds[0], f.bounds[1])
    val b = doc.viewer.pageToScreen(f.page, f.bounds[2], f.bounds[3])
    val margin = with(density) { 12.dp.toPx() }
    val below = b.y + margin
    val maxBottom = doc.viewer.viewH - doc.viewer.padBottom
    val y = if (below + size.height < maxBottom) below else (a.y - margin - size.height).coerceAtLeast(doc.viewer.padTop)
    val x = ((a.x + b.x) / 2f - size.width / 2f).coerceIn(with(density) { 8.dp.toPx() }, (doc.viewer.viewW - size.width - with(density) { 8.dp.toPx() }).coerceAtLeast(0f))
    Row(
        Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }.onSizeChanged { size = it }
            .shadow(6.dp, RoundedCornerShape(24.dp)).background(c.menu, RoundedCornerShape(24.dp)).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val colorLabel = stringResource(R.string.viewer_markup_color)
        for (color in FOLLOW_UP_COLORS) {
            val on = color == f.color
            Box(
                Modifier.size(40.dp).clickable(role = Role.RadioButton) { vm.recolorFollowUp(f, color) }
                    .semantics { contentDescription = colorLabel; selected = on },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(if (on) 26.dp else 22.dp).background(color, CircleShape).then(if (on) Modifier.border(2.dp, c.accent, CircleShape) else Modifier))
            }
        }
        Box(Modifier.padding(horizontal = 4.dp).size(width = 1.dp, height = 24.dp).background(c.divider))
        MenuIcon(LucideIcons.MessageSquarePlus, stringResource(R.string.viewer_add_note), true, short = stringResource(R.string.viewer_menu_note)) { vm.noteFollowUp(f) }
        MenuIcon(LucideIcons.Trash2, stringResource(R.string.common_delete), true, short = stringResource(R.string.common_delete)) { vm.deleteFollowUp(f) }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = pdfColors
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = c.icon, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(21.dp))
        Text(label, color = c.text, fontSize = 15.sp)
    }
}

/** The comment panel for a new note: "Add a comment" and Post. */
@Composable
fun NoteSheet(vm: AppViewModel, prompt: NotePrompt) {
    val at = prompt.at
    InlineNoteBar(
        hint = stringResource(
            when {
                prompt.replace != null -> R.string.viewer_replacement_hint
                prompt.insert != null -> R.string.viewer_insertion_hint
                else -> R.string.viewer_write_note
            },
        ),
        onDismiss = { vm.notePrompt = null },
        onPost = { vm.postNote(prompt, it) },
        onVoice = if (at != null && prompt.selection == null && prompt.edit == null) ({ vm.notePrompt = null; vm.startVoice(prompt.doc, at) }) else null,
        author = vm.prefsChanged.let { vm.toolPrefs.author }, onAuthor = { vm.authorPrompt = true },
    )
}

/** A reply being typed: the same bar, under the comment it answers. */
@Composable
fun ReplySheet(vm: AppViewModel, prompt: ReplyPrompt) {
    InlineNoteBar(hint = stringResource(R.string.viewer_write_reply), onDismiss = { vm.replyPrompt = null }, onPost = { vm.postReply(prompt, it) }, onVoice = null, author = vm.prefsChanged.let { vm.toolPrefs.author }, onAuthor = { vm.authorPrompt = true })
}

/**
 * The note bar the reference docks above the keyboard: up to three lines of text with no box,
 * then the microphone (record instead of typing, while nothing is typed) and, after a short
 * divider, the send arrow, blue once there is text. Back or a tap above it cancels.
 */
@Composable
private fun InlineNoteBar(hint: String, onDismiss: () -> Unit, onPost: (String) -> Unit, onVoice: (() -> Unit)?, author: String = "", onAuthor: (() -> Unit)? = null) {
    val c = pdfColors
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onDismiss() } })
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding()
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).background(c.surface),
        ) {
            // whose name the comment carries, changed here when it's typed rather than in a dialog
            // before the first mark (#202)
            if (onAuthor != null) {
                Text(
                    if (author.isBlank()) stringResource(R.string.viewer_comment_as_add) else stringResource(R.string.viewer_comment_as, author),
                    color = if (author.isBlank()) c.toolAccent else c.textSecondary, fontSize = 13.sp, maxLines = 1,
                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onAuthor).padding(start = 16.dp, end = 16.dp, top = 10.dp),
                )
            }
            BasicTextField(
                value = text, onValueChange = { text = it }, maxLines = 3,
                textStyle = androidx.compose.ui.text.TextStyle(color = c.text, fontSize = 17.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(c.toolAccent),
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 15.dp, bottom = 15.dp).focusRequester(focus)
                    .semantics { contentDescription = hint },
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) Text(hint, color = c.textSecondary, fontSize = 17.sp)
                        inner()
                    }
                },
            )
            Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onVoice != null) BarIcon(LucideIcons.Mic, stringResource(R.string.voice_record_comment), { onVoice() }, enabled = text.isBlank())
                Spacer(Modifier.weight(1f))
                Box(Modifier.width(1.dp).height(21.dp).background(c.divider))
                Spacer(Modifier.width(4.dp))
                BarIcon(LucideIcons.Send, stringResource(R.string.common_post), { onPost(text) }, enabled = text.isNotBlank(), tint = c.toolAccent)
            }
        }
    }
}

/** Typing free text where the page was tapped: a field over that spot, placed on Done. */
@Composable
fun BoxScope.TextEntry(vm: AppViewModel, prompt: TextPrompt, onDone: (TextPrompt, String) -> Unit) {
    val c = pdfColors
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val doc = prompt.doc
    val at = doc.viewer.pageToScreen(prompt.at.page, prompt.at.x, prompt.at.y)
    val density = LocalDensity.current
    val color = vm.toolPrefs.color(StyledTool.FreeText)
    val sizeSp = with(density) { (vm.toolPrefs.textSize * doc.viewer.pixelsPerPoint(prompt.at.page)).toSp() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val currentText = androidx.compose.runtime.rememberUpdatedState(text)
    val placeholder = stringResource(R.string.viewer_add_text)
    // leaving the tool (Done) places the text too, after whatever else the tool had pending
    androidx.compose.runtime.DisposableEffect(prompt) {
        val before = doc.commitTool
        val commit = {
            before?.invoke()
            keyboard?.hide()
            onDone(prompt, currentText.value)
        }
        doc.commitTool = commit
        onDispose { if (doc.commitTool === commit) doc.commitTool = before }
    }
    // a tap anywhere else places the text (and puts the keyboard away)
    Box(Modifier.fillMaxSize().pointerInput(prompt) {
        detectTapGestures {
            keyboard?.hide()
            onDone(prompt, currentText.value)
        }
    })
    Box(Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }.imePadding()) {
        androidx.compose.foundation.text.BasicTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = androidx.compose.ui.text.TextStyle(color = color, fontSize = sizeSp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
            modifier = Modifier
                .widthIn(min = 80.dp, max = 320.dp)
                .background(Color.White.copy(alpha = 0.6f))
                .padding(4.dp)
                .focusRequester(focus),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) Text(placeholder, color = color.copy(alpha = 0.5f), fontSize = sizeSp)
                    inner()
                }
            },
        )
    }
}

/** A hairline for menus. */
@Composable
fun MenuDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(pdfColors.divider))
}

/**
 * The selection's two handles: teardrops hanging under its first and last glyph. Dragging one
 * moves that end glyph by glyph; the other end stays.
 */
@Composable
fun BoxScope.SelectionHandles(doc: OpenDocument, sel: Selection) {
    val page = sel.range.page.toInt()
    val density = LocalDensity.current
    val size = with(density) { 20.dp.toPx() }
    val blue = Color(0xFF4285F4)
    val startLabel = stringResource(R.string.viewer_selection_start)
    val endLabel = stringResource(R.string.viewer_selection_end)
    for (start in listOf(true, false)) {
        val r = (if (start) sel.startRect else sel.endRect) ?: continue
        // The handle is positioned from the latest selected glyph, but its gesture
        // coordinates use a stable target captured at drag start. Recomposition as
        // the selected range changes must never reset the accumulated movement.
        val anchor = doc.viewer.pageToScreen(page, if (start) r[0] else r[2], r[3])
        Box(
            Modifier
                .offset { IntOffset((anchor.x - size / 2f).roundToInt(), anchor.y.roundToInt()) }
                .size(20.dp)
                .pointerInput(doc, page, start) {
                    var target = Offset.Zero
                    detectDragGestures(
                        onDragStart = {
                            val latest = doc.selection.selection
                            val rect = (if (start) latest?.startRect else latest?.endRect)
                            if (rect != null && latest?.range?.page?.toInt() == page) {
                                val at = doc.viewer.pageToScreen(page, if (start) rect[0] else rect[2], rect[3])
                                val top = doc.viewer.pageToScreen(page, rect[0], rect[1]).y
                                target = Offset(at.x, (at.y + top) / 2f)
                                doc.selection.beginHandleDrag(start)
                                doc.selection.magnifierAt = target
                            } else target = Offset.Unspecified
                        },
                        onDragEnd = {
                            doc.selection.endHandleDrag()
                            doc.selection.magnifierAt = null
                        },
                        onDragCancel = {
                            doc.selection.endHandleDrag()
                            doc.selection.magnifierAt = null
                        },
                    ) { change, delta ->
                        change.consume()
                        if (target.x.isFinite() && target.y.isFinite()) {
                            target += delta
                            doc.selection.magnifierAt = target
                            doc.viewer.screenToPageOf(page, target)?.let { doc.selection.dragHandle(start, it) }
                        }
                    }
                }
                .semantics { contentDescription = if (start) startLabel else endLabel },
        ) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val rad = this.size.width / 2f
                drawCircle(blue, rad, Offset(rad, rad * 1.1f))
                drawRect(blue, topLeft = Offset(if (start) rad else 0f, 0f), size = androidx.compose.ui.geometry.Size(rad, rad))
            }
        }
    }
}

/** Single page mode's scrubber: a pill with the page number along the bottom; drag to go through the pages. */
@Composable
fun BoxScope.HorizontalScrubber(doc: OpenDocument, visible: Boolean, bottom: Dp, onDrag: () -> Unit, onTap: () -> Unit) {
    val c = pdfColors
    val v = doc.viewer
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    var dragX by remember { mutableFloatStateOf(0f) }
    AnimatedVisibility(visible || dragging, modifier = Modifier.align(Alignment.BottomCenter), enter = fadeIn(tween(150)), exit = fadeOut(tween(300))) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = bottom + 12.dp).height(32.dp)) {
            val track = with(density) { maxWidth.toPx() }
            val pillW = with(density) { 48.dp.toPx() }
            val n = (doc.pageCount - 1).coerceAtLeast(1)
            val x = if (dragging) dragX else v.currentPage.toFloat() / n * (track - pillW)
            val shown = pageLabelOf(doc, v.currentPage)
            val pageOf = if (shown != null) stringResource(R.string.viewer_page_label_of, shown, v.currentPage + 1, doc.pageCount) else stringResource(R.string.viewer_page_of, v.currentPage + 1, doc.pageCount)
            Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(2.dp).background(c.divider))
            Box(
                Modifier
                    .offset { IntOffset(x.roundToInt(), 0) }
                    .size(48.dp, 30.dp)
                    .shadow(2.dp, RoundedCornerShape(15.dp))
                    .background(c.surface, RoundedCornerShape(15.dp))
                    .pointerInput(doc) { detectTapGestures(onTap = { onTap() }) }
                    .pointerInput(doc) {
                        detectDragGestures(
                            onDragStart = { dragging = true; dragX = x },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false },
                        ) { change, d ->
                            change.consume()
                            dragX = (dragX + d.x).coerceIn(0f, track - pillW)
                            val page = (dragX / (track - pillW).coerceAtLeast(1f) * n).roundToInt()
                            if (page != v.currentPage) v.goToPage(page)
                            onDrag()
                        }
                    }
                    .semantics { contentDescription = pageOf },
                contentAlignment = Alignment.Center,
            ) {
                Text(shown ?: "${v.currentPage + 1}", color = c.text, fontSize = 15.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
}
