package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.BarIcon
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import io.github.artcraftandroid.pdfcraft.ui.pdfColors

/**
 * Expanded-width windows can spare room for a persistent page navigator. Do not turn a phone in
 * landscape into a tablet: its short side is still phone-sized. DeX and ChromeOS are exceptions
 * because their resizable desktop windows can have a phone-sized minimum configuration.
 *
 * 840 dp is the expanded-width window breakpoint. Calculate from current window width, not the
 * physical display size, so split screen and DeX window resizing both restore the compact reader.
 */
internal fun showTabletPageRail(widthDp: Int, smallestWidthDp: Int, desktop: Boolean): Boolean =
    widthDp >= 840 && (smallestWidthDp >= 600 || desktop)

/**
 * Keep the existing reader, gestures, selection overlays and tool sheets inside one viewport.
 * The navigator occupies its own pane, never an overlay on PDF text or a second PDF renderer.
 * In small windows, Reading Mode and the full Pages organizer, the original viewer is unchanged.
 */
@Composable
fun AdaptiveViewer(vm: AppViewModel, doc: OpenDocument) {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val wide = showTabletPageRail(
        configuration.screenWidthDp,
        configuration.smallestScreenWidthDp,
        isDesktop(context, configuration),
    )
    var railOpen by remember(doc) { mutableStateOf(true) }
    if (!wide || doc.pageCount < 2 || vm.readingMode || doc.controller.pagesOpen) {
        ViewerScreen(vm, doc)
    } else {
        Row(Modifier.fillMaxSize()) {
            if (railOpen) {
                ExpandedPageRail(doc, onCollapse = { railOpen = false })
            } else {
                Column(
                    Modifier.width(52.dp).fillMaxHeight().background(pdfColors.surface),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BarIcon(
                        LucideIcons.ChevronRight,
                        stringResource(R.string.viewer_show_page_navigator),
                        onClick = { railOpen = true },
                    )
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(pdfColors.divider))
            Box(Modifier.weight(1f).fillMaxHeight()) { ViewerScreen(vm, doc) }
        }
    }
}

@Composable
private fun ExpandedPageRail(doc: OpenDocument, onCollapse: () -> Unit) {
    val c = pdfColors
    val current = doc.viewer.currentPage.coerceIn(0, doc.pageCount - 1)
    val list = rememberLazyListState()
    // Large documents are virtualized: do not request thumbnails or open another engine session.
    LaunchedEffect(current, doc.pageCount) {
        if (list.layoutInfo.visibleItemsInfo.none { it.index == current }) list.scrollToItem(current)
    }
    Column(Modifier.width(204.dp).fillMaxHeight().background(c.surface)) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.pages_title),
                modifier = Modifier.weight(1f),
                color = c.text,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
            BarIcon(
                LucideIcons.ChevronLeft,
                stringResource(R.string.viewer_hide_page_navigator),
                onClick = onCollapse,
            )
        }
        Text(
            stringResource(R.string.viewer_page_of, current + 1, doc.pageCount),
            Modifier.padding(start = 16.dp, bottom = 10.dp),
            color = c.textSecondary,
            fontSize = 12.sp,
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
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
                        .clickable { doc.viewer.goToPage(index) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    color = if (selected) c.toolAccent else c.text,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 14.sp,
                )
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
