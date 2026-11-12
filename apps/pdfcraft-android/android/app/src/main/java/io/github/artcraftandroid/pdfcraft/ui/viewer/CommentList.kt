package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.core.CommentInfo
import io.github.artcraftandroid.pdfcraft.core.TileRect
import io.github.artcraftandroid.pdfcraft.engine.toBitmap
import io.github.artcraftandroid.pdfcraft.plural
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.BarIcon
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import io.github.artcraftandroid.pdfcraft.ui.PdfSheet
import io.github.artcraftandroid.pdfcraft.ui.SheetRow
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import io.github.artcraftandroid.pdfcraft.ui.safeTopPadding
import kotlinx.coroutines.withContext

/** The comment types the list shows: the commenting kinds, not attachments, links or pop-ups. */
internal val LISTED = setOf(
    "Text", "FreeText", "Highlight", "Underline", "StrikeOut", "Squiggly", "Ink", "Line", "Square", "Circle",
    "Polygon", "PolyLine", "Stamp", "Caret", "Sound",
)

/** The markups whose row shows a picture of the text they mark. */
private val SNIPPETS = setOf("Highlight", "Underline", "StrikeOut")

/** What a comment did, in the list's italic line (a string resource). */
@StringRes
internal fun commentActionLabel(c: CommentInfo): Int = when (c.subtype) {
    "Highlight" -> R.string.comments_action_highlighted
    "Underline" -> R.string.comments_action_underlined
    "StrikeOut" -> R.string.comments_action_struck_out
    "Ink" -> R.string.comments_action_freehand
    "Line" -> if (c.kind.contains("arrow", ignoreCase = true)) R.string.comments_action_arrow else R.string.comments_action_line
    "Square" -> R.string.comments_action_rectangle
    "Circle" -> R.string.comments_action_oval
    "Polygon" -> if (c.kind.contains("cloud", ignoreCase = true)) R.string.comments_action_cloud else R.string.comments_action_polygon
    "PolyLine" -> R.string.comments_action_joined_lines
    "Stamp" -> R.string.comments_action_stamp
    "Text" -> R.string.comments_action_note
    "FreeText" -> if (c.kind.contains("callout", ignoreCase = true)) R.string.comments_action_callout else R.string.comments_action_text_box
    "Caret" -> if (c.kind.contains("replace", ignoreCase = true)) R.string.comments_action_replacement else R.string.comments_action_insertion
    "Sound" -> R.string.comments_action_voice
    else -> R.string.comments_action_other
}

/** When a comment was made: [today] ("Today"), [yesterday] ("Yesterday"), else the date. */
internal fun commentDateLabel(raw: String?, today: String, yesterday: String, now: Long = System.currentTimeMillis()): String? {
    val t = pdfDateMillis(raw) ?: return null
    fun day(ms: Long) = java.util.Calendar.getInstance().apply { timeInMillis = ms }.let { it.get(java.util.Calendar.YEAR) * 400 + it.get(java.util.Calendar.DAY_OF_YEAR) }
    return when (day(t)) {
        day(now) -> today
        day(now - 24L * 3600 * 1000) -> yesterday
        else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(t))
    }
}

/** "N comment threads" (replies aren't threads of their own): [none] for none, else [count] of them. */
internal fun threadsTitle(n: Int, none: String, count: (Int) -> String): String = if (n == 0) none else count(n)

/** The comment list's menus: the list's own ⋮, a comment's ⋮, and their sub-choices. */
private sealed interface ListMenu {
    data object Overflow : ListMenu
    data object Summary : ListMenu
    data class Comment(val c: CommentInfo) : ListMenu
    data class Status(val c: CommentInfo) : ListMenu
}

/** The review statuses a comment can be given, as `comment_set_status` takes them, and their names. */
private val STATUSES = listOf(
    "none" to R.string.comments_status_none,
    "accepted" to R.string.comments_status_accepted,
    "rejected" to R.string.comments_status_rejected,
    "cancelled" to R.string.comments_status_cancelled,
    "completed" to R.string.comments_status_completed,
)

/** A review status as the engine reports it (`Accepted`, …), in words. */
@StringRes
internal fun statusLabel(status: String): Int = when (status.lowercase()) {
    "accepted" -> R.string.comments_status_accepted
    "rejected" -> R.string.comments_status_rejected
    "cancelled" -> R.string.comments_status_cancelled
    "completed" -> R.string.comments_status_completed
    else -> R.string.comments_status_none
}

/**
 * Comments, full screen as the reference's is on a phone: "N comment threads", the threads
 * grouped under "Page N" headers that fold, each comment with its avatar, author, date, what it did
 * (or a picture of the marked text), its text and, under each thread, Reply. A tap goes to the
 * comment on its page. A search field and chips find comments by text, kind, review status and
 * author (#196, #169, #158); a page's handwriting folds into one "N drawings" row after its
 * written comments. The ⋮ menu shares, saves, imports, summarises, copies and hides comments.
 */
@Composable
fun CommentListScreen(vm: AppViewModel, doc: OpenDocument) {
    val c = pdfColors
    val ctl = doc.controller
    var list by remember { mutableStateOf<List<CommentInfo>?>(null) }
    var statuses by remember { mutableStateOf<Map<Pair<Int, Int>, String>>(emptyMap()) }
    LaunchedEffect(doc.info.generation) {
        list = vm.comments(doc)
        statuses = vm.commentStatuses(doc)
    }
    val folded = remember { mutableStateMapOf<Int, Boolean>() }
    val drawingsOpen = remember { mutableStateMapOf<Int, Boolean>() }
    val snippets = remember(doc.info.generation) { mutableStateMapOf<String, ImageBitmap>() }
    var menu by remember { mutableStateOf<ListMenu?>(null) }
    var searching by remember { mutableStateOf(false) }
    var filters by ctl::commentFilters
    var exporting by remember { mutableStateOf(false) }
    fun close() {
        ctl.sheet = ViewerSheet.None
    }
    BackHandler {
        if (searching) {
            searching = false
            filters = filters.copy(query = "")
        } else close()
    }
    val guest = stringResource(R.string.comments_guest)
    val me = vm.commentAuthor
    val all = list.orEmpty().filter { !it.fillSign }
    val parents = all.filter { it.replyTo == null && it.state == null && it.subtype in LISTED }
    // each thread: its comment, then the replies to it in order
    val threads = remember(list) { parents.map { p -> listOf(p) + all.filter { it.replyTo != null && it.replyTo == p.name && it.state == null } } }
    fun statusOf(cm: CommentInfo) = statuses[cm.page.toInt() to cm.index.toInt()]
    val shown = remember(threads, filters, statuses, me) { filterThreads(threads, filters, me, guest) { statusOf(it) } }
    val authors = remember(threads) { threads.map { authorKey(it.first(), guest) }.distinct().sorted() }
    val filtering = filters != CommentFilters()
    // the page dims what the filters leave out, once the list is closed (#155)
    LaunchedEffect(shown, filtering, list) {
        if (list == null) return@LaunchedEffect
        ctl.dimmedGeneration = doc.info.generation.toLong()
        ctl.dimmedComments = if (filtering) dimmedComments(threads, shown, { it.page.toInt() }, { it.rect }) else emptyList()
    }
    Column(
        Modifier.fillMaxSize().background(Color.Black).safeTopPadding()
            .background(c.navBand).navigationBarsPadding().background(c.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().height(TopBarHeight).drawBehind {
                val line = 1.dp.toPx()
                drawRect(c.divider, Offset(0f, size.height - line), Size(size.width, line))
            }.padding(bottom = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                BarIcon(LucideIcons.ArrowLeft, stringResource(R.string.common_back), ::close, iconSize = 27.dp)
            }
            if (searching) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                BasicTextField(
                    filters.query, { filters = filters.copy(query = it) },
                    singleLine = true,
                    textStyle = TextStyle(color = c.label, fontSize = 17.sp),
                    cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (filters.query.isEmpty()) Text(stringResource(R.string.comments_search_hint), color = c.textTertiary, fontSize = 17.sp)
                            inner()
                        }
                    },
                )
                BarIcon(LucideIcons.X, stringResource(R.string.comments_search_close), {
                    searching = false
                    filters = filters.copy(query = "")
                })
            } else {
                Text(
                    if (list == null) stringResource(R.string.comments_title)
                    else if (filtering) stringResource(R.string.comments_shown, shown.size, threads.size)
                    else threadsTitle(threads.size, stringResource(R.string.comments_none)) { plural(R.plurals.comments_threads, it, it) },
                    color = c.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (threads.isNotEmpty()) BarIcon(LucideIcons.Search, stringResource(R.string.comments_search), { searching = true })
            }
            if (!searching && threads.isNotEmpty()) BarIcon(LucideIcons.FileOutput, stringResource(R.string.digest_title), { exporting = true })
            BarIcon(LucideIcons.EllipsisVertical, stringResource(R.string.comments_more), { menu = ListMenu.Overflow })
        }
        if (exporting) ExportNotesSheet(vm, doc) { exporting = false }
        if (list == null) {
            Text(stringResource(R.string.comments_loading), color = c.label, modifier = Modifier.padding(16.dp))
            return@Column
        }
        if (threads.isEmpty()) {
            EmptyComments(onAdd = if (doc.info.allowsAnnotation) ({
                close()
                vm.enterTool(doc, ViewerTool.Comment)
            }) else null)
            return@Column
        }
        // the chips: kind, then review status and authors when there is a choice
        ChipRow {
            FilterChipText(stringResource(R.string.comments_filter_all), filters.kind == CommentKindFilter.All) { filters = filters.copy(kind = CommentKindFilter.All) }
            FilterChipText(stringResource(R.string.comments_filter_notes), filters.kind == CommentKindFilter.Notes) { filters = filters.copy(kind = CommentKindFilter.Notes) }
            FilterChipText(stringResource(R.string.comments_filter_highlights), filters.kind == CommentKindFilter.Highlights) { filters = filters.copy(kind = CommentKindFilter.Highlights) }
            FilterChipText(stringResource(R.string.comments_filter_drawings), filters.kind == CommentKindFilter.Drawings) { filters = filters.copy(kind = CommentKindFilter.Drawings) }
            if (!me.isNullOrBlank()) FilterChipText(stringResource(R.string.comments_filter_mine), filters.kind == CommentKindFilter.Mine) { filters = filters.copy(kind = CommentKindFilter.Mine) }
        }
        if (statuses.isNotEmpty() || filters.status != CommentStatusFilter.Any) ChipRow {
            for ((f, label) in listOf(
                CommentStatusFilter.Any to R.string.comments_status_any,
                CommentStatusFilter.Open to R.string.comments_status_open,
                CommentStatusFilter.Accepted to R.string.comments_status_accepted,
                CommentStatusFilter.Rejected to R.string.comments_status_rejected,
                CommentStatusFilter.Completed to R.string.comments_status_completed,
            )) FilterChipText(stringResource(label), filters.status == f) { filters = filters.copy(status = f) }
        }
        if (authors.size > 1) ChipRow {
            for (a in authors) {
                val on = a !in filters.hiddenAuthors
                FilterChipText(a, on, icon = if (on) LucideIcons.Eye else LucideIcons.EyeOff) {
                    filters = filters.copy(hiddenAuthors = if (on) filters.hiddenAuthors + a else filters.hiddenAuthors - a)
                }
            }
        }
        if (shown.isEmpty()) {
            Text(
                stringResource(R.string.comments_no_match), color = c.textSecondary, fontSize = 16.sp,
                modifier = Modifier.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center,
            )
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            val pages = shown.groupBy { it.first().page.toInt() }
            pages.entries.forEachIndexed { pageIndex, (page, pageThreads) ->
                item(key = "p$page") {
                    val isFolded = folded[page] == true
                    Row(
                        Modifier.fillMaxWidth()
                            .drawBehind {
                                if (pageIndex > 0) {
                                    val inset = 16.dp.toPx()
                                    drawRect(c.divider, Offset(inset, 0f), Size(size.width - 2 * inset, 2.dp.toPx()))
                                }
                            }
                            .clickable { folded[page] = !isFolded }
                            .padding(start = 16.dp, top = if (pageIndex == 0) 12.dp else 15.dp, end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.sheet_page, page + 1), color = c.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        // the chevron's 40 dp target overlaps the rows around it rather than spacing them
                        Box(Modifier.size(width = 40.dp, height = 22.dp), contentAlignment = Alignment.Center) {
                            Icon(
                                LucideIcons.ChevronUp, stringResource(if (isFolded) R.string.comments_expand else R.string.comments_collapse), tint = c.icon,
                                modifier = Modifier.size(22.dp).rotate(if (isFolded) 180f else 0f),
                            )
                        }
                    }
                }
                if (folded[page] != true) {
                    // the Drawings chip lists each drawing; elsewhere a page's handwriting folds into one row
                    val rows = pageRows(page, pageThreads, expanded = drawingsOpen[page] == true || filters.kind == CommentKindFilter.Drawings)
                    rows.forEachIndexed { rowIndex, row ->
                        when (row) {
                            is CommentListRow.Drawings -> item(key = "d$page") {
                                DrawingsRow(row.threads.size, last = rowIndex == rows.size - 1) { drawingsOpen[page] = true }
                            }
                            is CommentListRow.Thread -> {
                                val thread = row.thread
                                thread.forEachIndexed { i, cm ->
                                    item(key = "c${cm.page}-${cm.index}") {
                                        CommentRow(
                                            vm, doc, cm,
                                            reply = i > 0,
                                            lineAbove = i > 0,
                                            lineBelow = i < thread.size - 1,
                                            replyLink = i == thread.size - 1,
                                            lastOnPage = rowIndex == rows.size - 1 && i == thread.size - 1,
                                            snippet = snippets["${cm.page}-${cm.index}"],
                                            onSnippet = { snippets["${cm.page}-${cm.index}"] = it },
                                            onOpen = {
                                                val parent = thread.first()
                                                close()
                                                val y = parent.rect.getOrNull(1) ?: 0f
                                                doc.viewer.goToPage(parent.page.toInt(), (y - 40f).coerceAtLeast(0f))
                                                vm.selectedComment = doc to parent
                                            },
                                            onReply = { vm.replyPrompt = ReplyPrompt(doc, thread.first()) },
                                            onMenu = { menu = ListMenu.Comment(cm) },
                                            status = if (i == 0) statusOf(cm) else null,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    when (val m = menu) {
        null -> Unit
        ListMenu.Overflow -> PdfSheet(onDismiss = { menu = null }, dim = true) {
            val canAdd = doc.info.allowsAnnotation && doc.info.readOnlyReason == null
            fun pick(action: () -> Unit) {
                menu = null
                action()
            }
            if (threads.isNotEmpty()) {
                SheetRow(LucideIcons.Share2, stringResource(R.string.comments_share), { pick { vm.exportComments(doc, share = true) } })
                SheetRow(LucideIcons.FileDown, stringResource(R.string.comments_save), { pick { vm.exportComments(doc, share = false) } })
            }
            SheetRow(LucideIcons.FileInput, stringResource(R.string.comments_import), { pick { vm.pickCommentsImport(doc) } }, enabled = canAdd)
            if (threads.isNotEmpty()) {
                SheetRow(LucideIcons.FileText, stringResource(R.string.comments_summarize), { menu = ListMenu.Summary }, chevron = true)
                SheetRow(
                    LucideIcons.Copy,
                    stringResource(if (filtering) R.string.comments_copy_shown else R.string.comments_copy_all),
                    { pick { vm.copyComments(doc, shown.map { it.first() }) } },
                )
            }
            if (vm.copiedComments.isNotEmpty()) {
                SheetRow(LucideIcons.ClipboardPaste, stringResource(R.string.comments_paste), { pick { vm.pasteComments(doc) } }, enabled = canAdd)
            }
            val hidden = doc.info.commentsHidden
            SheetRow(
                if (hidden) LucideIcons.Eye else LucideIcons.EyeOff,
                stringResource(if (hidden) R.string.comments_show_all else R.string.comments_hide_all),
                { pick { vm.setCommentsHidden(doc, !hidden) } },
            )
            Spacer(Modifier.height(8.dp))
        }
        ListMenu.Summary -> PdfSheet(onDismiss = { menu = null }, dim = true) {
            SheetHeaderText(stringResource(R.string.comments_summarize_sort))
            for ((sort, label) in listOf(
                "page" to R.string.comments_sort_page, "author" to R.string.comments_sort_author,
                "date" to R.string.comments_sort_date, "type" to R.string.comments_sort_type,
            )) SheetRow(null, stringResource(label), {
                menu = null
                vm.summarizeComments(doc, sort)
            })
            Spacer(Modifier.height(8.dp))
        }
        is ListMenu.Comment -> PdfSheet(onDismiss = { menu = null }, dim = true) {
            val cm = m.c
            val text = cm.contents?.takeIf { it.isNotBlank() }
            val reply = cm.replyTo != null
            if (text != null) {
                SheetRow(LucideIcons.Copy, stringResource(R.string.comments_copy_text), {
                    menu = null
                    vm.copyText(text)
                })
            }
            if (text != null && !cm.locked) {
                SheetRow(LucideIcons.Pencil, stringResource(R.string.comments_edit), {
                    menu = null
                    vm.selectedComment = doc to cm
                })
            }
            if (!reply) {
                SheetRow(LucideIcons.CircleCheck, stringResource(R.string.comments_set_status), { menu = ListMenu.Status(cm) }, chevron = true)
                SheetRow(if (cm.locked) LucideIcons.LockOpen else LucideIcons.Lock, stringResource(if (cm.locked) R.string.comments_unlock else R.string.comments_lock), {
                    menu = null
                    vm.lockComment(doc, cm, !cm.locked)
                })
                if (cm.subtype != "Sound") SheetRow(LucideIcons.CopyPlus, stringResource(R.string.comments_copy_comment), {
                    menu = null
                    vm.copyComments(doc, listOf(cm))
                })
            }
            if (!cm.locked) {
                SheetRow(LucideIcons.Trash2, stringResource(R.string.common_delete), {
                    menu = null
                    vm.deleteComment(doc, cm)
                })
            }
            Spacer(Modifier.height(8.dp))
        }
        is ListMenu.Status -> PdfSheet(onDismiss = { menu = null }, dim = true) {
            SheetHeaderText(stringResource(R.string.comments_set_status))
            val current = statusOf(m.c)?.lowercase() ?: "none"
            for ((status, label) in STATUSES) SheetRow(null, stringResource(label), {
                menu = null
                if (status != current) vm.setCommentStatus(doc, m.c, status)
            }, checked = status == current)
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** A sheet's plain heading (for a choice that follows a menu row). */
@Composable
private fun SheetHeaderText(text: String) {
    Text(text, color = pdfColors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp))
}

/** A row of chips that scrolls sideways when it doesn't fit. */
@Composable
private fun ChipRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** One filter chip: outlined, filled with the pressed colour when on. */
@Composable
private fun FilterChipText(label: String, selected: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    val c = pdfColors
    Row(
        Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(18.dp))
            .background(if (selected) c.pressed else Color.Transparent)
            .border(1.dp, if (selected) c.accent else c.divider, RoundedCornerShape(18.dp))
            .selectable(selected = selected, role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (selected) c.accent else c.icon, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label, color = if (selected) c.accent else c.text, fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp),
        )
    }
}

/** A page's handwriting folded into one row: "12 drawings", which opens them. */
@Composable
private fun DrawingsRow(count: Int, last: Boolean, onExpand: () -> Unit) {
    val c = pdfColors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onExpand)
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = if (last) 12.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(c.pressed), contentAlignment = Alignment.Center) {
            Icon(LucideIcons.PenLine, null, tint = c.icon, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(plural(R.plurals.comments_drawings, count, count), color = c.label, fontSize = 15.sp, fontStyle = FontStyle.Italic, modifier = Modifier.weight(1f))
        Icon(LucideIcons.ChevronDown, stringResource(R.string.comments_expand), tint = c.icon, modifier = Modifier.size(22.dp))
    }
}

@Composable
internal fun CommentRow(
    vm: AppViewModel,
    doc: OpenDocument,
    cm: CommentInfo,
    reply: Boolean,
    lineAbove: Boolean,
    lineBelow: Boolean,
    replyLink: Boolean,
    lastOnPage: Boolean,
    snippet: ImageBitmap?,
    onSnippet: (ImageBitmap) -> Unit,
    onOpen: () -> Unit,
    onReply: () -> Unit,
    onMenu: () -> Unit,
    /** In the comment panel: no action line over text, and the ⋮ menu drawn by the caller. */
    panel: Boolean = false,
    menu: @Composable () -> Unit = {},
    /** Its review status, shown as a badge after the date (the list's first row of a thread). */
    status: String? = null,
) {
    val c = pdfColors
    val density = LocalDensity.current
    val text = cm.contents?.takeIf { it.isNotBlank() }
    val hasSnippet = !reply && cm.subtype in SNIPPETS && cm.rect.size == 4
    // markups with text show their picture instead of the line; anything without text shows the line
    val actionLine = text == null || (!panel && ((!reply && !hasSnippet) || cm.subtype == "Caret"))
    Box(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).drawBehind {
            // the thread's line runs through the avatars, 2 dp clear of each
            val x = 32.dp.toPx()
            val w = 2.dp.toPx()
            val avatarTop = 8.dp.toPx()
            val avatarBottom = avatarTop + 40.dp.toPx()
            if (lineAbove) drawRect(c.divider, Offset(x - w / 2, 0f), Size(w, avatarTop - 2.dp.toPx()))
            if (lineBelow) drawRect(c.divider, Offset(x - w / 2, avatarBottom + 2.dp.toPx()), Size(w, size.height - avatarBottom - 2.dp.toPx()))
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp)) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        cm.author?.takeIf { it.isNotBlank() } ?: stringResource(R.string.comments_guest), color = c.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        commentDateLabel(cm.modified, stringResource(R.string.common_today), stringResource(R.string.common_yesterday))?.let { Text(it, color = c.textTertiary, fontSize = 16.sp) }
                        if (status != null) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(statusLabel(status)), color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(c.pressed).padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                        if (cm.locked && !reply) {
                            Spacer(Modifier.width(6.dp))
                            Icon(LucideIcons.Lock, stringResource(R.string.comments_locked), tint = c.textTertiary, modifier = Modifier.size(15.dp))
                        }
                    }
                }
                Box {
                    BarIcon(LucideIcons.EllipsisVertical, stringResource(R.string.comments_options), onMenu, size = 40.dp)
                    menu()
                }
            }
            if (hasSnippet) {
                Spacer(Modifier.height(12.dp))
                BoxWithConstraints(Modifier.fillMaxWidth().height(16.dp)) {
                    val widthPx = with(density) { maxWidth.toPx() }
                    LaunchedEffect(cm.page, cm.index, doc.info.generation) {
                        if (snippet != null) return@LaunchedEffect
                        val eng = vm.engine ?: return@LaunchedEffect
                        val r = cm.rect
                        val h = (r[3] - r[1]).coerceAtLeast(1f)
                        // rendered so the mark is 30 dp tall, then shown 16 dp tall
                        val scale = (with(density) { 30.dp.toPx() } / h).coerceIn(0.2f, 12f)
                        val pageW = doc.info.pages.getOrNull(cm.page.toInt())?.width ?: return@LaunchedEffect
                        val x = (r[0] * scale).toInt().coerceAtLeast(0)
                        val w = minOf((widthPx * 30f / 16f).toInt(), (pageW * scale).toInt() - x).coerceAtLeast(1)
                        val tile = TileRect(x.toUInt(), (r[1] * scale).toInt().coerceAtLeast(0).toUInt(), w.toUInt(), (h * scale).toInt().coerceAtLeast(1).toUInt())
                        val img = runCatching {
                            withContext(eng.thumbnails) { eng.session.renderPage(doc.id, cm.page, scale, tile).toBitmap().asImageBitmap() }
                        }.getOrNull()
                        if (img != null) onSnippet(img)
                    }
                    if (snippet != null) {
                        Image(snippet, null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillHeight, alignment = Alignment.CenterStart)
                    } else {
                        Box(Modifier.fillMaxSize().background(Color.White))
                    }
                }
            }
            if (actionLine) {
                Text(
                    stringResource(commentActionLabel(cm)), color = c.icon, fontSize = 14.5.sp, fontStyle = FontStyle.Italic,
                    modifier = Modifier.padding(top = if (hasSnippet) 8.dp else 12.dp),
                )
            }
            if (text != null) {
                Text(text, color = c.label, fontSize = 16.sp, lineHeight = 22.5.sp, modifier = Modifier.padding(top = 16.dp))
            }
            if (panel && cm.subtype == "Sound") VoiceCommentPlayer(vm, doc, cm)
            Spacer(Modifier.height(if (replyLink) 16.dp else if (lastOnPage) 12.dp else 8.dp))
            if (replyLink) {
                Text(
                    stringResource(R.string.comments_reply), color = c.toolAccent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.offset(x = (-8).dp).heightIn(min = 40.dp).clip(RoundedCornerShape(4.dp))
                        .clickable(role = Role.Button, onClick = onReply).padding(horizontal = 8.dp, vertical = 10.dp),
                )
                Spacer(Modifier.height(if (lastOnPage) 14.dp else 6.dp))
            }
        }
        Avatar(Modifier.padding(start = 16.dp, top = 12.dp))
    }
}

/** The guest avatar: a lilac disc, its lower right a shade darker. */
@Composable
private fun Avatar(modifier: Modifier) {
    val description = stringResource(R.string.comments_avatar)
    Canvas(modifier.size(32.dp).semantics { contentDescription = description }) {
        val base = Color(0xFF9B80FF)
        drawCircle(base)
        val shade = Path().apply {
            moveTo(size.width, 0f)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        clipPath(Path().apply { addOval(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height)) }) {
            drawPath(shade, Color(base.red * 0.75f, base.green * 0.75f, base.blue * 0.75f))
        }
    }
}

/** No comments yet: a heading, a line on how to add one, and Add comment (when the document allows it). */
@Composable
private fun EmptyComments(onAdd: (() -> Unit)?) {
    val c = pdfColors
    Column(
        Modifier.fillMaxSize().padding(horizontal = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(LucideIcons.MessagesSquare, null, tint = c.textSecondary, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.comments_empty_title), color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.comments_empty_body), color = c.textSecondary, fontSize = 17.sp, textAlign = TextAlign.Center)
        if (onAdd != null) {
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.comments_add), color = c.toolAccent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).border(1.dp, c.toolAccent, RoundedCornerShape(24.dp))
                    .clickable(role = Role.Button, onClick = onAdd).padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
        Spacer(Modifier.height(80.dp))
    }
}

/** More > Export notes: the digest's format, grouping and which comments, then Export. */
@Composable
private fun ExportNotesSheet(vm: AppViewModel, doc: OpenDocument, onDismiss: () -> Unit) {
    val c = pdfColors
    var format by remember { mutableStateOf(io.github.artcraftandroid.pdfcraft.ui.DigestFormat.Markdown) }
    var grouping by remember { mutableStateOf(io.github.artcraftandroid.pdfcraft.engine.DigestGrouping.Page) }
    var filter by remember { mutableStateOf(io.github.artcraftandroid.pdfcraft.engine.DigestFilter.All) }
    io.github.artcraftandroid.pdfcraft.ui.PdfSheet(onDismiss = onDismiss, dim = true) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            io.github.artcraftandroid.pdfcraft.ui.SheetTitle(stringResource(R.string.digest_title))
            DigestChoice(R.string.digest_format, io.github.artcraftandroid.pdfcraft.ui.DigestFormat.entries, format, { it.label }) { format = it }
            if (format != io.github.artcraftandroid.pdfcraft.ui.DigestFormat.Pdf) {
                DigestChoice(R.string.digest_group_by, io.github.artcraftandroid.pdfcraft.engine.DigestGrouping.entries, grouping, {
                    if (it == io.github.artcraftandroid.pdfcraft.engine.DigestGrouping.Page) R.string.digest_by_page else R.string.digest_by_color
                }) { grouping = it }
                DigestChoice(R.string.digest_include, io.github.artcraftandroid.pdfcraft.engine.DigestFilter.entries, filter, {
                    when (it) {
                        io.github.artcraftandroid.pdfcraft.engine.DigestFilter.All -> R.string.digest_all
                        io.github.artcraftandroid.pdfcraft.engine.DigestFilter.Checked -> R.string.digest_checked
                        io.github.artcraftandroid.pdfcraft.engine.DigestFilter.Unresolved -> R.string.digest_unresolved
                    }
                }) { filter = it }
            }
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF1473E6))
                    .clickable(role = Role.Button) { onDismiss(); vm.exportNotes(doc, format, grouping, filter) },
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.digest_export), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            Text(stringResource(R.string.digest_hint), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun <T> DigestChoice(@androidx.annotation.StringRes title: Int, values: List<T>, selected: T, label: (T) -> Int, onPick: (T) -> Unit) {
    val c = pdfColors
    Text(stringResource(title), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
    for (v in values) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.RadioButton) { onPick(v) }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.RadioButton(
                selected = v == selected, onClick = { onPick(v) },
                colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = c.toolAccent, unselectedColor = c.icon),
            )
            Text(stringResource(label(v)), color = c.text, fontSize = 15.sp)
        }
    }
}
