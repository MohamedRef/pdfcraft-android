package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.core.AttachmentInfo
import io.github.artcraftandroid.pdfcraft.core.LayerInfo
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.AttachPrompt
import io.github.artcraftandroid.pdfcraft.ui.BarIcon
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.OUTLINE_COLORS
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import io.github.artcraftandroid.pdfcraft.ui.OutlineMove
import io.github.artcraftandroid.pdfcraft.ui.OutlineRow
import io.github.artcraftandroid.pdfcraft.ui.PdfDialog
import io.github.artcraftandroid.pdfcraft.ui.PdfField
import io.github.artcraftandroid.pdfcraft.ui.PdfSheet
import io.github.artcraftandroid.pdfcraft.ui.PdfSwitch
import io.github.artcraftandroid.pdfcraft.ui.SheetBackHeader
import io.github.artcraftandroid.pdfcraft.ui.SheetDivider
import io.github.artcraftandroid.pdfcraft.ui.SheetRow
import io.github.artcraftandroid.pdfcraft.ui.outlineMoveArgs
import io.github.artcraftandroid.pdfcraft.ui.pdfColors

/** Why the document can't take edits to its attachments or bookmarks, or null when it can. */
@Composable
private fun lockedReason(doc: OpenDocument): String? = when {
    doc.info.readOnlyReason != null -> doc.info.readOnlyReason
    doc.info.signed -> stringResource(R.string.panels_locked_signed)
    !doc.writable -> stringResource(R.string.panels_locked_read_only)
    else -> null
}

/**
 * Layers: the optional content groups with a switch each, Show all and Reset. A view setting
 * (as the desktop's eye icons): pages are drawn again, the file is not changed.
 */
@Composable
fun LayersSheet(vm: AppViewModel, doc: OpenDocument) {
    val c = pdfColors
    val ctl = doc.controller
    var layers by remember { mutableStateOf<List<LayerInfo>?>(null) }
    // the document's own default, kept from the first listing for Reset
    var initial by remember { mutableStateOf<List<Boolean>?>(null) }
    var busy by remember { mutableStateOf(false) }
    suspend fun reload() {
        val l = vm.docTools.layers(doc)
        layers = l
        if (initial == null) initial = doc.controller.initialLayers ?: l.map { it.visible }.also { doc.controller.initialLayers = it }
    }
    LaunchedEffect(doc) { reload() }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(refresh) { if (refresh > 0) reload() }
    fun set(changes: List<Pair<Int, Boolean>>) {
        if (changes.isEmpty()) return
        busy = true
        var left = changes.size
        for ((i, v) in changes) vm.docTools.setLayer(doc, i + 1, v) {
            if (--left == 0) { busy = false; refresh++ }
        }
    }
    PdfSheet(onDismiss = { ctl.sheet = ViewerSheet.None }, maxHeightFraction = 0.7f) {
        SheetBackHeader(stringResource(R.string.sheet_layers), onBack = { ctl.sheet = ViewerSheet.More })
        val list = layers
        if (list == null) {
            Text("…", color = c.textSecondary, modifier = Modifier.padding(20.dp))
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.layers_show_all), color = if (busy) c.textSecondary else c.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(enabled = !busy, role = Role.Button) {
                        set(list.withIndex().filter { !it.value.visible }.map { it.index to true })
                    }.padding(12.dp),
                )
                Text(
                    stringResource(R.string.layers_reset), color = if (busy) c.textSecondary else c.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(enabled = !busy, role = Role.Button) {
                        val first = initial ?: return@clickable
                        set(list.withIndex().filter { (i, l) -> first.getOrNull(i) != null && first[i] != l.visible }.map { (i, _) -> i to first[i] })
                    }.padding(12.dp),
                )
            }
            SheetDivider()
            LazyColumn {
                items(list.size) { i ->
                    val l = list[i]
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = !busy, role = Role.Switch) { set(listOf(i to !l.visible)) }
                            .padding(start = 20.dp, end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (l.visible) LucideIcons.Eye else LucideIcons.EyeOff, null, tint = c.icon, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(l.name.ifBlank { stringResource(R.string.layers_unnamed, i + 1) }, color = c.text, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        PdfSwitch(l.visible, onChange = { v -> if (!busy) set(listOf(i to v)) })
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Attachments: the embedded files with their sizes. A tap opens one; a long press offers Delete
 * (Undo in the snackbar). Add file attaches any file, with an optional description.
 */
@Composable
fun AttachmentsPanel(vm: AppViewModel, doc: OpenDocument) {
    val c = pdfColors
    val ctl = doc.controller
    var list by remember { mutableStateOf<List<AttachmentInfo>?>(null) }
    LaunchedEffect(doc.info.generation) { list = vm.attachments(doc) }
    val files = list?.filter { !it.onPage }
    val locked = lockedReason(doc)
    PdfSheet(onDismiss = { ctl.sheet = ViewerSheet.None }, maxHeightFraction = 0.7f) {
        SheetBackHeader(stringResource(R.string.sheet_attachments), onBack = { ctl.sheet = ViewerSheet.More })
        SheetRow(LucideIcons.Paperclip, stringResource(R.string.attachments_add), { vm.docTools.pickAttachment(doc) }, enabled = locked == null)
        if (locked != null) {
            Text(locked, color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
        SheetDivider()
        when {
            files == null -> Text("…", color = c.textSecondary, modifier = Modifier.padding(20.dp))
            files.isEmpty() -> Text(stringResource(R.string.attachments_empty), color = c.textSecondary, fontSize = 15.sp, modifier = Modifier.padding(20.dp))
            else -> LazyColumn {
                items(files) { a -> AttachmentRow(vm, doc, a, canDelete = locked == null) }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun AttachmentRow(vm: AppViewModel, doc: OpenDocument, a: AttachmentInfo, canDelete: Boolean) {
    val c = pdfColors
    val ctl = doc.controller
    var menu by remember { mutableStateOf(false) }
    val deleteLabel = stringResource(R.string.common_delete)
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = { ctl.sheet = ViewerSheet.None; vm.openAttachment(doc, a.name) }, onLongClick = { if (canDelete) menu = true })
            .semantics {
                if (canDelete) customActions = listOf(CustomAccessibilityAction(deleteLabel) { vm.docTools.deleteAttachment(doc, a.name); true })
            }
            .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(LucideIcons.FileText, null, tint = c.icon, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(a.name, color = c.text, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val details = listOfNotNull(a.size?.let { formatSize(it.toLong()) }, a.description?.takeIf { it.isNotBlank() }).joinToString(" • ")
            if (details.isNotEmpty()) Text(details, color = c.textSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (canDelete) Box {
            BarIcon(LucideIcons.EllipsisVertical, stringResource(R.string.attachments_options, a.name), { menu = true })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.menu) {
                DropdownMenuItem(text = { Text(deleteLabel, color = c.text) }, onClick = { menu = false; vm.docTools.deleteAttachment(doc, a.name) })
            }
        }
    }
}

/** Add file: the attachment's name (fixed) and an optional description. */
@Composable
fun AttachDialog(vm: AppViewModel, prompt: AttachPrompt) {
    val c = pdfColors
    var description by remember { mutableStateOf("") }
    PdfDialog(
        onDismiss = { vm.docTools.cancelAttach() },
        title = stringResource(R.string.attachments_add),
        confirm = stringResource(R.string.attachments_attach),
        onConfirm = { vm.docTools.attach(prompt, description) },
    ) {
        Column {
            Text(prompt.name, color = c.text, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            PdfField(description, { description = it.take(500) }, stringResource(R.string.attachments_description))
        }
    }
}

/** What the Edit contents dialogs are asking for. */
private sealed class OutlinePrompt {
    data class Add(val parent: List<Int>?) : OutlinePrompt()
    data class Rename(val row: OutlineRow) : OutlinePrompt()
    data class Style(val row: OutlineRow) : OutlinePrompt()
}

/**
 * Edit contents: every embedded bookmark, indented by level and in its own colour and style. Each
 * has a menu: Rename, Go to current page, Style, Add child, Move up / down / in / out (also
 * TalkBack actions), Delete. Each change is one undo step and the list follows it.
 */
@Composable
fun EditContentsSheet(vm: AppViewModel, doc: OpenDocument) {
    val c = pdfColors
    val ctl = doc.controller
    var rows by remember { mutableStateOf<List<OutlineRow>?>(null) }
    LaunchedEffect(doc.info.generation) { rows = vm.docTools.outlineRows(doc) }
    var prompt by remember { mutableStateOf<OutlinePrompt?>(null) }
    val locked = lockedReason(doc)
    PdfSheet(onDismiss = { ctl.sheet = ViewerSheet.None }, maxHeightFraction = 0.85f, fill = true) {
        SheetBackHeader(stringResource(R.string.contents_edit_title), onBack = { ctl.sheet = ViewerSheet.Bookmarks }, trailing = {
            UndoMenuButton(doc.info.undo != null, doc.info.redo != null, { vm.undo(doc) }, { vm.redo(doc) }, doc.info.undo, doc.info.redo)
        })
        if (locked != null) {
            Text(locked, color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
        SheetRow(LucideIcons.BookmarkPlus, stringResource(R.string.contents_add_current), { prompt = OutlinePrompt.Add(null) }, enabled = locked == null)
        if (doc.info.tagged) {
            SheetRow(LucideIcons.Heading, stringResource(R.string.contents_from_tags), { vm.docTools.editBookmark(doc, "bookmark_from_structure", null) }, enabled = locked == null)
        }
        SheetDivider()
        val all = rows
        when {
            all == null -> Text("…", color = c.textSecondary, modifier = Modifier.padding(20.dp))
            all.isEmpty() -> Text(stringResource(R.string.sheet_no_contents), color = c.textSecondary, fontSize = 15.sp, modifier = Modifier.padding(20.dp))
            else -> LazyColumn(Modifier.weight(1f)) {
                items(all, key = { it.path.joinToString(".") }) { row ->
                    OutlineEditRow(vm, doc, all, row, enabled = locked == null) { prompt = it }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    when (val p = prompt) {
        is OutlinePrompt.Add -> TitleDialog(
            stringResource(if (p.parent == null) R.string.contents_add_current else R.string.contents_add_child),
            pageLabelOf(doc, doc.viewer.currentPage)?.let { stringResource(R.string.contents_default_title_label, it) } ?: stringResource(R.string.contents_default_title, doc.viewer.currentPage + 1),
            onDismiss = { prompt = null },
        ) { title ->
            prompt = null
            val args = mutableMapOf<String, Any?>("title" to title, "page" to doc.viewer.currentPage + 1)
            if (p.parent != null) args["parent"] = p.parent
            vm.docTools.editBookmark(doc, "bookmark_add", null, args)
        }
        is OutlinePrompt.Rename -> TitleDialog(stringResource(R.string.common_rename), p.row.title, onDismiss = { prompt = null }) { title ->
            prompt = null
            vm.docTools.editBookmark(doc, "bookmark_rename", p.row.path, mapOf("title" to title))
        }
        is OutlinePrompt.Style -> StyleDialog(p.row, onDismiss = { prompt = null }) { color, bold, italic ->
            prompt = null
            vm.docTools.editBookmark(doc, "bookmark_set_style", p.row.path, mapOf("color" to color.map { it.toDouble() }, "bold" to bold, "italic" to italic))
        }
        null -> Unit
    }
}

private fun rowColor(row: OutlineRow, fallback: Color): Color =
    row.color?.takeIf { it.size == 3 && it.any { v -> v > 0f } }?.let { Color(it[0], it[1], it[2]) } ?: fallback

@Composable
private fun OutlineEditRow(vm: AppViewModel, doc: OpenDocument, all: List<OutlineRow>, row: OutlineRow, enabled: Boolean, onPrompt: (OutlinePrompt) -> Unit) {
    val c = pdfColors
    var menu by remember { mutableStateOf(false) }
    val moves = OutlineMove.entries.mapNotNull { m -> outlineMoveArgs(all, row.path, m)?.let { m to it } }
    val moveLabels = mapOf(
        OutlineMove.Up to stringResource(R.string.contents_move_up),
        OutlineMove.Down to stringResource(R.string.contents_move_down),
        OutlineMove.In to stringResource(R.string.contents_move_in),
        OutlineMove.Out to stringResource(R.string.contents_move_out),
    )
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .clickable {
                row.page?.let { p ->
                    doc.controller.rememberJump(doc.viewer)
                    doc.viewer.goToPage(p)
                }
            }
            .semantics {
                if (enabled) customActions = moves.map { (m, args) -> CustomAccessibilityAction(moveLabels.getValue(m)) { vm.docTools.editBookmark(doc, "bookmark_move", null, args); true } }
            }
            .padding(start = 16.dp + (row.depth.coerceAtMost(8) * 16).dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                row.title, color = rowColor(row, c.text), fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                fontWeight = if (row.bold) FontWeight.Bold else FontWeight.Normal, fontStyle = if (row.italic) FontStyle.Italic else FontStyle.Normal,
            )
            row.page?.let { Text(stringResource(R.string.sheet_page, it + 1), color = c.textSecondary, fontSize = 13.sp) }
        }
        if (enabled) Box {
            BarIcon(LucideIcons.EllipsisVertical, stringResource(R.string.contents_options, row.title), { menu = true })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.menu) {
                @Composable
                fun item(label: String, onClick: () -> Unit) = DropdownMenuItem(text = { Text(label, color = c.text) }, onClick = { menu = false; onClick() })
                item(stringResource(R.string.common_rename)) { onPrompt(OutlinePrompt.Rename(row)) }
                item(stringResource(R.string.contents_set_current_page, doc.viewer.currentPage + 1)) {
                    vm.docTools.editBookmark(doc, "bookmark_set_page", row.path, mapOf("page" to doc.viewer.currentPage + 1))
                }
                item(stringResource(R.string.contents_style)) { onPrompt(OutlinePrompt.Style(row)) }
                item(stringResource(R.string.contents_add_child)) { onPrompt(OutlinePrompt.Add(row.path)) }
                for ((m, args) in moves) item(moveLabels.getValue(m)) { vm.docTools.editBookmark(doc, "bookmark_move", null, args) }
                item(stringResource(R.string.common_delete)) { vm.docTools.editBookmark(doc, "bookmark_delete", row.path) }
            }
        }
    }
}

@Composable
private fun TitleDialog(title: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    PdfDialog(onDismiss = onDismiss, title = title, confirm = stringResource(R.string.common_ok), onConfirm = { onDone(text.trim()) }, confirmEnabled = text.isNotBlank()) {
        PdfField(text, { text = it.take(500) }, stringResource(R.string.contents_title_hint))
    }
}

@Composable
private fun StyleDialog(row: OutlineRow, onDismiss: () -> Unit, onDone: (List<Float>, Boolean, Boolean) -> Unit) {
    val c = pdfColors
    var color by remember { mutableStateOf(row.color ?: OUTLINE_COLORS.first()) }
    var bold by remember { mutableStateOf(row.bold) }
    var italic by remember { mutableStateOf(row.italic) }
    PdfDialog(onDismiss = onDismiss, title = stringResource(R.string.contents_style), confirm = stringResource(R.string.common_ok), onConfirm = { onDone(color, bold, italic) }) {
        Column {
            Text(
                row.title, color = rowColor(row.copy(color = color), c.text), fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OUTLINE_COLORS.forEachIndexed { i, rgb ->
                    val swatch = if (i == 0) c.text else Color(rgb[0], rgb[1], rgb[2])
                    val label = stringResource(R.string.contents_color_n, i + 1)
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).background(swatch)
                            .border(if (rgb == color) 3.dp else 1.dp, if (rgb == color) c.accent else c.divider, CircleShape)
                            .clickable(role = Role.RadioButton) { color = rgb }
                            .semantics { contentDescription = label },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.contents_bold), color = c.text, modifier = Modifier.weight(1f))
                PdfSwitch(bold, onChange = { bold = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.contents_italic), color = c.text, modifier = Modifier.weight(1f))
                PdfSwitch(italic, onChange = { italic = it })
            }
        }
    }
}
