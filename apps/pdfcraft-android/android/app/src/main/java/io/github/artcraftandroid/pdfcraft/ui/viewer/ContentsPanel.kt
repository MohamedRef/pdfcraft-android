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
 * Where the contents open (#659): the bottom sheet on a phone, the side panel on a tablet.
 * [Docked] sits beside the page and stays while reading; [Overlay] slides in over the page in a
 * window too narrow to keep the page readable beside it, and closes when an entry is chosen or
 * the page is tapped.
 */
enum class ContentsPlace { Sheet, Overlay, Docked }

/** The page keeps at least this much width beside a docked panel (#659); narrower, the panel slides over it. */
internal const val CONTENTS_DOCK_MIN_PAGE_DP = 480

/** The folded panel's strip at the side of the page (#659). */
internal const val CONTENTS_STRIP_WIDTH_DP = 52

/**
 * Decided by the device, not by the window's width alone (#659): a tablet (smallest width 600 dp
 * or more) never gets the bottom sheet, held upright or sideways; a phone does, also turned
 * sideways, since its short side is still a phone's. A DeX or ChromeOS desktop window counts as a
 * tablet while it is 600 dp wide or more. The panel is docked while the page keeps
 * [CONTENTS_DOCK_MIN_PAGE_DP] beside the contents' 300 dp, so from 780 dp: an 800 dp portrait
 * tablet is docked, a 600 dp window gets the panel over the page. The folded strip is not
 * counted, as an open panel takes its place.
 */
internal fun contentsPlace(widthDp: Int, smallestWidthDp: Int, desktop: Boolean): ContentsPlace {
    val tablet = smallestWidthDp >= 600 || desktop
    if (!tablet || widthDp < 600) return ContentsPlace.Sheet
    return if (widthDp - SidePanelContentsWidth.value.toInt() >= CONTENTS_DOCK_MIN_PAGE_DP) ContentsPlace.Docked else ContentsPlace.Overlay
}

/** The tab the contents open on: the last one used, else Contents, or Bookmarks when the file has no outline. */
internal fun ViewerController.contentsTabToOpen(hasOutline: Boolean): ContentsTab =
    contentsTab?.takeIf { it != ContentsTab.Pages } ?: if (hasOutline) ContentsTab.Contents else ContentsTab.Bookmarks

/**
 * Show the side panel or the sheet on [tab], by [contentsPlace] (#659). A [tab] of Pages opens
 * only the panel; on a phone it has no sheet, so it opens Contents. [from] decides where the
 * sheet's back arrow goes. Returns where they opened, for the log.
 */
internal fun ViewerController.showContents(tab: ContentsTab, from: String): ContentsPlace {
    val place = contentsPlace
    when (place) {
        ContentsPlace.Docked -> { contentsTab = tab; panelOpen = true; overlayOpen = false; sheet = ViewerSheet.None }
        ContentsPlace.Overlay -> { contentsTab = tab; overlayOpen = true; sheet = ViewerSheet.None }
        ContentsPlace.Sheet -> {
            if (tab != ContentsTab.Pages) contentsTab = tab
            bookmarksBack = if (from == "menu") ViewerSheet.More else ViewerSheet.None
            sheet = ViewerSheet.Bookmarks
        }
    }
    return place
}

/**
 * The top bar's Contents button (#659): it opens the contents, and folds the panel when the panel
 * already shows them.
 */
internal fun ViewerController.toggleContents(hasOutline: Boolean): ContentsPlace? {
    val showing = contentsTab?.let { it != ContentsTab.Pages } == true
    when {
        sheet == ViewerSheet.Bookmarks -> sheet = ViewerSheet.None
        contentsPlace == ContentsPlace.Docked && panelOpen && showing -> panelOpen = false
        contentsPlace == ContentsPlace.Overlay && overlayOpen && showing -> overlayOpen = false
        else -> return showContents(contentsTabToOpen(hasOutline), "button")
    }
    return null
}

/**
 * Open the contents from the reading page (#647, #659): in the side panel on a tablet, else in
 * the sheet. [from] is `button`, `menu` or `strip`, for the log; [tab] is the strip's icon tapped,
 * else the tab last used.
 */
internal fun openContents(doc: OpenDocument, from: String, tab: ContentsTab? = null) {
    val ctl = doc.controller
    val place = ctl.showContents(tab ?: ctl.contentsTabToOpen(doc.info.hasOutline), from)
    logContentsOpen(doc, from, place)
}

private fun logContentsOpen(doc: OpenDocument, from: String, place: ContentsPlace) {
    val ctl = doc.controller
    val where = when (place) { ContentsPlace.Docked -> "panel"; ContentsPlace.Overlay -> "overlay"; ContentsPlace.Sheet -> "sheet" }
    Diag.event(
        DiagArea.Viewer, "contents_open", "from" to from, "where" to where, "tab" to (ctl.contentsTab ?: ContentsTab.Contents).name.lowercase(),
        "page" to doc.viewer.currentPage + 1, "width_dp" to ctl.windowWidthDp, "sw_dp" to ctl.windowSmallestWidthDp,
    )
}

/** The top bar's Contents button (#659). */
internal fun toggleContentsFromButton(doc: OpenDocument) {
    val place = doc.controller.toggleContents(doc.info.hasOutline) ?: return
    logContentsOpen(doc, "button", place)
}

/** The panel's state kept for each document between visits (#659): `open:contents`, `closed:pages`. */
internal fun encodeContentsPanel(open: Boolean, tab: ContentsTab?): String =
    (if (open) "open" else "closed") + ":" + (tab ?: ContentsTab.Pages).name.lowercase()

internal fun decodeContentsPanel(s: String?): Pair<Boolean, ContentsTab>? {
    val parts = s?.split(':')?.takeIf { it.size == 2 } ?: return null
    val open = when (parts[0]) { "open" -> true; "closed" -> false; else -> return null }
    val tab = ContentsTab.entries.firstOrNull { it.name.lowercase() == parts[1] } ?: return null
    return open to tab
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
