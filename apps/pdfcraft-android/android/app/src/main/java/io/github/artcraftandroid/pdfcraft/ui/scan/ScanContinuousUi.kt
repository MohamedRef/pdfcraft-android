package io.github.artcraftandroid.pdfcraft.ui.scan

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.ScanPage
import io.github.artcraftandroid.pdfcraft.ui.ScanPageMark
import io.github.artcraftandroid.pdfcraft.ui.pdfColors

/**
 * The scan's pages one under the other (#786): each as wide as the screen, up to
 * [SCAN_DOC_PAGE_MAX_DP] on a tablet, in its own proportions, with room between them and
 * "Page n of m" for the one in sight. It opens on the page the document screen was last on. A tap
 * on a page opens it in Enhance ([onOpen], counted from 0); [onCurrent] gets the page in sight.
 *
 * Memory: only the pages on screen (and the one the list prepares next) are composed. Each reads
 * its kept result at the size it is drawn, through the page queue's tile cache
 * ([io.github.artcraftandroid.pdfcraft.ui.SCAN_TILE_CACHE_BYTES], shared with the grid), and lets
 * go of it when it scrolls away, so a scan of any length holds a screenful and the cache.
 */
@Composable
internal fun ScanContinuousPages(vm: AppViewModel, pages: List<ScanPage>, modifier: Modifier, onCurrent: (Int) -> Unit, onOpen: (Int) -> Unit) {
    val docView = vm.scanSteps.docView
    val keys = pages.map { it.key }
    val start = remember { scanAnchorIndex(docView.anchor, keys) }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = start)
    val current by remember(list) {
        derivedStateOf {
            val info = list.layoutInfo
            scanCurrentPage(
                info.visibleItemsInfo.map { ScanDocSpan(it.index, it.offset, it.size) },
                info.viewportStartOffset, info.viewportEndOffset, !list.canScrollBackward, !list.canScrollForward,
            )
        }
    }
    // Review pages on the camera (#780) while these pages are already here: go to the page it asks for
    val jumps = docView.jumps
    LaunchedEffect(jumps) { if (jumps > 0) list.scrollToItem(scanAnchorIndex(docView.anchor, keys)) }
    val total = pages.size
    val shownAt = (current ?: start).coerceIn(0, (total - 1).coerceAtLeast(0))
    LaunchedEffect(shownAt, keys) { onCurrent(shownAt) }
    val lab = vm.scanLab.options
    val background = lab.backgroundPages && lab.preview
    Box(modifier) {
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 56.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize(),
        ) {
            // by the page's own key, so a page keeps its place in the list when others move or go
            itemsIndexed(pages, key = { _, p -> p.key }) { i, p ->
                ScanContinuousPage(vm, p, i, total, background) { onOpen(i) }
            }
        }
        if (total > 0) {
            Text(
                stringResource(R.string.tools_page_of, shownAt + 1, total), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * One page of the continuous view (#786): above it, what is not as it will be saved (being
 * prepared, waiting, failed with Try again, or the photo as taken) and Check for borders the app
 * is unsure of (#758); then the page, which a tap opens in Enhance.
 */
@Composable
private fun ScanContinuousPage(vm: AppViewModel, page: ScanPage, index: Int, total: Int, background: Boolean, onOpen: () -> Unit) {
    val c = pdfColors
    val lab = vm.scanLab.options
    val queue = vm.pageQueue
    val docView = vm.scanSteps.docView
    val state = scanDocPageState(background, queue.readyOf(page) != null, queue.mark(page) == ScanPageMark.Working, queue.failedOf(page))
    // what the page showed when it was first drawn, and when it was made, for a kept scan's page file
    LaunchedEffect(page.key) { docView.pageShown(page.key, state, SystemClock.elapsedRealtime()) }
    val ready = state == ScanDocPageState.Ready
    LaunchedEffect(page.key, ready) { if (ready) docView.pageReady(page.key, SystemClock.elapsedRealtime()) }
    val stateLabel = when (state) {
        ScanDocPageState.Ready -> null
        ScanDocPageState.Working -> R.string.scan_page_state_working
        ScanDocPageState.Waiting -> R.string.scan_page_state_waiting
        ScanDocPageState.Failed -> R.string.scan_page_state_failed
        ScanDocPageState.Photo -> R.string.scan_page_state_photo
    }
    val stateText = if (stateLabel != null) stringResource(stateLabel) else null
    val turns = scanTurnShown(page.turns, vm.scanAutoTurns(index))
    // the proportions of what is drawn: the page as made or its first picture, else the photo inside its corners, turned
    val shown = queue.tileOf(page)
    val aspect = if (shown != null) {
        scanDocPageAspect(shown.thumb.width, shown.thumb.height)
    } else {
        val region = scanThumbRegion(page.corners, page.thumb.width, page.thumb.height)
        scanDocPageAspect(region[2], region[3], turns)
    }
    val pageLabel = stringResource(R.string.tools_page_of, index + 1, total)
    val editLabel = stringResource(R.string.scan_edit_page_edit)
    val shape = RoundedCornerShape(4.dp)
    Column(Modifier.widthIn(max = SCAN_DOC_PAGE_MAX_DP.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (stateText != null) {
            ScanPageStatusRow(
                working = state == ScanDocPageState.Working, warning = state == ScanDocPageState.Failed, text = stateText,
                action = if (state == ScanDocPageState.Failed) stringResource(R.string.scan_page_retry) else null,
                onAction = { queue.retry(index, page) },
            )
        }
        if (scanDocPageChecks(scanPageUnsure(page), lab.checkUnsure, lab.batchBorders)) {
            ScanPageStatusRow(
                working = false, warning = true, text = stringResource(R.string.scan_page_unsure), action = stringResource(R.string.scan_batch_check),
                onAction = {
                    // the borders pass comes back to this page
                    docView.anchor = ScanDocAnchor(page.key, index)
                    vm.startScanBatch(ScanBatchWhat.Borders, setOf(page.key), "page")
                },
            )
        }
        Box(
            Modifier.fillMaxWidth().aspectRatio(aspect).clip(shape).background(c.surfaceRaised).border(1.dp, c.divider, shape)
                .clickable(onClickLabel = editLabel, role = Role.Button, onClick = onOpen)
                .semantics {
                    contentDescription = pageLabel
                    if (stateText != null) stateDescription = stateText
                },
        ) {
            // read at the size it is drawn, and only while it is on screen (#494's tile cache)
            ScanDocumentThumb(vm, page, turns, Modifier.fillMaxSize())
            // a spread's halves keep their names (#303)
            page.book?.let { ScanBookLabel(it, Modifier.align(Alignment.TopStart)) }
        }
    }
}

/**
 * A line above a page in the continuous view (#786): what the page is waiting for or what is
 * doubtful about it, and one thing to do about it. At least 48 dp high, and the words wrap, so it
 * holds at large text sizes.
 */
@Composable
private fun ScanPageStatusRow(working: Boolean, warning: Boolean, text: String, action: String?, onAction: () -> Unit) {
    val c = pdfColors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.surfaceRaised).heightIn(min = 48.dp).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            working -> CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
            warning -> Icon(LucideIcons.TriangleAlert, null, tint = c.icon, modifier = Modifier.size(18.dp))
            else -> Icon(LucideIcons.Clock, null, tint = c.icon, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(text, color = c.text, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
        if (action != null) TextButton(onClick = onAction) { Text(action, color = c.accent) }
    }
}

/**
 * Back to camera (#780), above the document screen's bar while the pages are under review from the
 * camera: the one filled button of that width, so the way back is one tap and cannot be missed.
 * The camera comes back as it was left, with its page count and what Undo knows. As wide as the
 * screen up to a comfortable width, at least 48 dp high, and its words wrap at large text sizes.
 */
@Composable
internal fun ScanReviewReturn(onBack: () -> Unit) {
    val c = pdfColors
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
        Button(
            onClick = onBack,
            colors = ButtonDefaults.buttonColors(containerColor = c.button, contentColor = Color.White),
            shape = RoundedCornerShape(24.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.widthIn(max = SCAN_DOC_PAGE_MAX_DP.dp).fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Icon(LucideIcons.Camera, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.scan_review_back_camera), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, textAlign = TextAlign.Center)
        }
    }
}
