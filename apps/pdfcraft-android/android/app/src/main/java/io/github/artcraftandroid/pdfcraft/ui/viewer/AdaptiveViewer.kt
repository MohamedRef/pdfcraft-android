package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.BarIcon
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import io.github.artcraftandroid.pdfcraft.ui.pdfColors

/**
 * Keep the existing reader, gestures, selection overlays and tool sheets inside one viewport.
 * On a tablet the side panel (Pages, Contents, Bookmarks) is docked beside the page, never over
 * it, or slides over the page in a window too narrow to dock it; folded, a strip at the side
 * opens it on one of its tabs (#659). Where it goes is [contentsPlace]. Split view, Reading Mode
 * and the full Pages organizer keep the plain viewer; a phone keeps the sheet.
 */
@Composable
fun AdaptiveViewer(vm: AppViewModel, doc: OpenDocument) {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val place = contentsPlace(
        configuration.screenWidthDp,
        configuration.smallestScreenWidthDp,
        isDesktop(context, configuration),
    )
    val ctl = doc.controller
    // Present is the page alone: no strip or panel beside it on a tablet (#742)
    val shown = if (vm.tabs.splitWith == null && !vm.settings.readingMode && !ctl.pagesOpen && !ctl.presenting) place else ContentsPlace.Sheet
    // the top bar's Contents button opens the panel where there is one, the sheet where there is not (#647, #659)
    SideEffect {
        ctl.contentsPlace = shown
        ctl.windowWidthDp = configuration.screenWidthDp
        ctl.windowSmallestWidthDp = configuration.smallestScreenWidthDp
    }
    LaunchedEffect(shown) { if (shown != ContentsPlace.Overlay) ctl.overlayOpen = false }
    val closeOverlay = { ctl.overlayOpen = false }
    Box(Modifier.fillMaxSize()) {
        // Keep ViewerScreen at one composition call site across breakpoint changes. Moving it
        // between compact/expanded branches discards remembered uncommitted ink and tool state.
        Row(Modifier.fillMaxSize()) {
            if (shown != ContentsPlace.Sheet) {
                if (shown == ContentsPlace.Docked && ctl.panelOpen) {
                    SidePanel(vm, doc, onCollapse = { ctl.panelOpen = false }, onChosen = null)
                } else {
                    ContentsStrip(onOpen = { tab -> openContents(doc, "strip", tab) })
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(pdfColors.divider))
            }
            // split view (#139) keeps ViewerScreen at the same call site, the second pane beside it
            Box(Modifier.weight(1f).fillMaxHeight()) { SplitLayout(vm, doc) { ViewerScreen(vm, doc) } }
        }
        if (shown == ContentsPlace.Overlay) {
            BackHandler(enabled = ctl.overlayOpen, onBack = closeOverlay)
            // a tap on the page around the panel closes it, and goes no further (#659)
            AnimatedVisibility(ctl.overlayOpen, enter = fadeIn(), exit = fadeOut()) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = closeOverlay),
                )
            }
            val fromStart = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 1 else -1
            AnimatedVisibility(
                ctl.overlayOpen,
                enter = slideInHorizontally { fromStart * it },
                exit = slideOutHorizontally { fromStart * it },
            ) {
                SidePanel(vm, doc, onCollapse = closeOverlay, onChosen = closeOverlay)
            }
        }
    }
}

/** The tag of the folded panel's strip icon for [tab], for layout tests (#659). */
internal fun contentsStripTag(tab: ContentsTab) = "contents_strip_" + tab.name.lowercase()

/**
 * The folded side panel (#659): a strip at the side of the page with one icon for each of the
 * panel's tabs, so one tap at the side opens it where the reader wants it. The icons sit at the
 * middle of the side, where a hand holding the tablet reaches.
 */
@Composable
internal fun ContentsStrip(onOpen: (ContentsTab) -> Unit) {
    Column(
        Modifier.width(CONTENTS_STRIP_WIDTH_DP.dp).fillMaxHeight().background(pdfColors.surface),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        for (t in listOf(ContentsTab.Pages, ContentsTab.Contents, ContentsTab.Bookmarks)) {
            val label = stringResource(
                when (t) {
                    ContentsTab.Contents -> R.string.sheet_tab_contents
                    ContentsTab.Bookmarks -> R.string.sheet_tab_bookmarks
                    ContentsTab.Pages -> R.string.pages_title
                },
            )
            BarIcon(contentsTabIcon(t), label, onClick = { onOpen(t) }, modifier = Modifier.testTag(contentsStripTag(t)), size = 48.dp)
        }
    }
}

/** The side panel's width with the pages' short rows, and with the contents' or bookmarks' titles (#647). */
internal val SidePanelPagesWidth = 204.dp
internal val SidePanelContentsWidth = 300.dp

/**
 * The tablet's side panel (#647): the page in view over three tabs, Pages (the page navigator it
 * always was), Contents and Bookmarks, the same lists as the phone's sheet. It stays open while
 * reading; choosing an entry goes to its page and leaves the panel as it is.
 */
@Composable
private fun SidePanel(vm: AppViewModel, doc: OpenDocument, onCollapse: () -> Unit, onChosen: (() -> Unit)?) {
    val c = pdfColors
    val ctl = doc.controller
    val current = doc.viewer.currentPage.coerceIn(0, doc.pageCount - 1)
    val tab = ctl.contentsTab ?: ContentsTab.Pages
    val go: (Int) -> Unit = { page ->
        ctl.rememberJump(doc.viewer)
        doc.viewer.goToPage(page)
        // over the page, the panel goes once an entry is chosen; docked, it stays (#659)
        onChosen?.invoke()
    }
    Column(Modifier.width(if (tab == ContentsTab.Pages) SidePanelPagesWidth else SidePanelContentsWidth).fillMaxHeight().background(c.surface)) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.viewer_page_of, current + 1, doc.pageCount),
                modifier = Modifier.weight(1f),
                color = c.text,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                maxLines = 1,
            )
            if (tab == ContentsTab.Contents) ContentsManageMenu(vm, doc)
            BarIcon(
                LucideIcons.ChevronLeft,
                stringResource(R.string.viewer_hide_page_navigator),
                onClick = onCollapse,
            )
        }
        ContentsTabs(listOf(ContentsTab.Pages, ContentsTab.Contents, ContentsTab.Bookmarks), tab, icons = true) { ctl.contentsTab = it }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
        Column(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                ContentsTab.Pages -> PanelPages(doc, current, Modifier.weight(1f).fillMaxWidth(), onChosen)
                ContentsTab.Contents -> ContentsTabBody(vm, doc, outlineOf(vm, doc), Modifier.weight(1f), go)
                ContentsTab.Bookmarks -> BookmarksTabBody(vm, doc, Modifier.weight(1f), go)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            val viewer = doc.viewer
            val focus = Offset(viewer.viewW / 2f, viewer.viewH / 2f)
            BarIcon(
                LucideIcons.Minus,
                stringResource(R.string.viewer_zoom_out),
                onClick = { viewer.zoomStep(-1, focus) },
                enabled = viewer.canZoomOut,
                size = 42.dp,
            )
            TextButton(onClick = {
                val page = viewer.currentPage
                viewer.zoomTo(1f, focus)
                if (viewer.mode == PageMode.SinglePage) viewer.goToPage(page)
            }) {
                Text(stringResource(R.string.viewer_fit_width), fontSize = 12.sp, color = c.text)
            }
            BarIcon(
                LucideIcons.Plus,
                stringResource(R.string.viewer_zoom_in),
                onClick = { viewer.zoomStep(1, focus) },
                enabled = viewer.canZoomIn,
                size = 42.dp,
            )
        }
    }
}

/** The Pages tab: every page by its number, the one in view marked. */
@Composable
private fun PanelPages(doc: OpenDocument, current: Int, modifier: Modifier, onChosen: (() -> Unit)?) {
    val c = pdfColors
    val list = rememberLazyListState()
    // Large documents are virtualized: do not request thumbnails or open another engine session.
    LaunchedEffect(current, doc.pageCount) {
        if (list.layoutInfo.visibleItemsInfo.none { it.index == current }) list.scrollToItem(current)
    }
    LazyColumn(
        modifier = modifier,
        state = list,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(doc.pageCount) { index ->
            val selected = index == current
            Text(
                stringResource(R.string.pages_page, index + 1),
                modifier = Modifier.fillMaxWidth()
                    .background(if (selected) c.pressed else Color.Transparent, RoundedCornerShape(6.dp))
                    .clickable { doc.viewer.goToPage(index); onChosen?.invoke() }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                color = if (selected) c.toolAccent else c.text,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 14.sp,
            )
        }
    }
}
