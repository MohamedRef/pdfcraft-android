package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.core.OutlineEntry
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.BarIcon
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import io.github.artcraftandroid.pdfcraft.ui.pdfColors

/** The tabs of the contents sheet and of the tablet's side panel (#647). */
enum class ContentsTab { Contents, Bookmarks, Pages }

/** The row of the section the reader is in, for layout tests. */
const val CONTENTS_CURRENT_TAG = "contents_current"

/**
 * Open the contents from the reading page (#647): in the side panel where the window has one
 * (a tablet in landscape), else in the sheet. The tab last used comes back; the first time it is
 * Contents, or Bookmarks when the file has no outline. [from] is `button` or `menu`, for the log.
 */
internal fun openContents(doc: OpenDocument, from: String) {
    val ctl = doc.controller
    val tab = ctl.contentsTab?.takeIf { it != ContentsTab.Pages } ?: if (doc.info.hasOutline) ContentsTab.Contents else ContentsTab.Bookmarks
    ctl.contentsTab = tab
    val panel = ctl.contentsInPanel
    Diag.event(DiagArea.Viewer, "contents_open", "from" to from, "where" to if (panel) "panel" else "sheet", "tab" to tab.name.lowercase(), "page" to doc.viewer.currentPage + 1)
    if (panel) {
        ctl.panelOpen = true
        ctl.sheet = ViewerSheet.None
    } else {
        ctl.bookmarksBack = if (from == "menu") ViewerSheet.More else ViewerSheet.None
        ctl.sheet = ViewerSheet.Bookmarks
    }
}

/**
 * The contents as a tree that opens in place (#647), one list for the phone's sheet and the
 * tablet's panel: a chevron opens a chapter where it stands, the page each entry goes to is at
 * the end of its row, and the section the reader is in is marked and in view when the list
 * first shows. A tap on an entry goes to its page ([onOpen], 0-based); an entry that goes
 * nowhere opens or folds instead.
 */
@Composable
internal fun ContentsList(doc: OpenDocument, all: List<OutlineEntry>, modifier: Modifier = Modifier, onOpen: (Int) -> Unit) {
    val c = pdfColors
    val levels = remember(all) { all.map { it.level.toInt() } }
    val pages = remember(all) { all.map { it.page?.toInt() } }
    val current = outlineCurrent(pages, doc.viewer.currentPage)
    // the way to the reader's section is open when the list first shows; after that the
    // reader's own opening and folding stands
    var expanded by remember(all) { mutableStateOf(outlineAncestors(levels, outlineCurrent(pages, doc.viewer.currentPage)).toSet()) }
    val rows = remember(all, expanded) { outlineVisible(levels, expanded) }
    val list = rememberLazyListState()
    LaunchedEffect(all) {
        val at = rows.indexOf(outlineCurrent(pages, doc.viewer.currentPage))
        // a row or two of what comes before stays in view above it
        if (at > 1) list.scrollToItem(at - 1)
        Diag.event(DiagArea.Viewer, "contents_listed", "entries" to all.size, "shown" to rows.size, "current" to (at >= 0))
    }
    val customLabels = remember(doc.info.generation) { io.github.artcraftandroid.pdfcraft.ui.hasCustomLabels(doc.info.pages.map { it.label }) }
    val locale = LocalConfiguration.current.locales[0]
    LazyColumn(modifier, state = list) {
        items(rows, key = { it }) { i ->
            val e = all[i]
            val here = i == current
            val parent = outlineHasChildren(levels, i)
            val open = i in expanded
            val toggle = { expanded = outlineToggled(levels, expanded, i) }
            val page = e.page?.toInt()
            Row(
                Modifier.fillMaxWidth()
                    .then(if (here) Modifier.testTag(CONTENTS_CURRENT_TAG) else Modifier)
                    .background(if (here) c.pressed else Color.Transparent)
                    .semantics { selected = here }
                    .clickable(enabled = page != null || parent, role = Role.Button) { if (page != null) onOpen(page) else toggle() }
                    .padding(start = (4 + outlineIndentDp(levels[i])).dp, end = 16.dp)
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (parent) {
                    BarIcon(
                        if (open) LucideIcons.ChevronDown else LucideIcons.ChevronRight,
                        stringResource(if (open) R.string.sheet_collapse_entry else R.string.sheet_expand_entry, e.title),
                        toggle, size = 48.dp, iconSize = 20.dp,
                    )
                } else {
                    Spacer(Modifier.width(48.dp))
                }
                Text(
                    e.title,
                    color = if (here) c.toolAccent else c.text,
                    fontSize = if (levels[i] <= 0) 16.sp else 15.sp,
                    fontWeight = if (here || levels[i] <= 0) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
                if (page != null) {
                    val label = (if (customLabels) doc.info.pages.getOrNull(page)?.label?.takeIf { it.isNotEmpty() } else null)
                        ?: String.format(locale, "%d", page + 1)
                    Text(label, color = if (here) c.toolAccent else c.textSecondary, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
    }
}

/** The outline of [doc], read again when the document changes; null until it is read. */
@Composable
internal fun outlineOf(vm: AppViewModel, doc: OpenDocument): List<OutlineEntry>? {
    var outline by remember(doc) { mutableStateOf<List<OutlineEntry>?>(null) }
    LaunchedEffect(doc, doc.info.generation) { outline = vm.outline(doc) }
    return outline
}
