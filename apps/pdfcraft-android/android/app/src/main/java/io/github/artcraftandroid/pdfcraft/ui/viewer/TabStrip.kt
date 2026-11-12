package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntOffset
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import io.github.artcraftandroid.pdfcraft.ui.SplitDrop
import io.github.artcraftandroid.pdfcraft.ui.Snip
import kotlin.math.roundToInt
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.BarIcon
import io.github.artcraftandroid.pdfcraft.ui.DocTab
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import io.github.artcraftandroid.pdfcraft.ui.TabPolicy
import io.github.artcraftandroid.pdfcraft.ui.pdfColors

/** The tab strip's height, under the top bar while two or more documents are open (#138). */
val TabStripHeight = 40.dp

/** Whether [vm] shows the tab strip for its document. */
fun showTabStrip(vm: AppViewModel): Boolean = vm.tabs.tabs.size > 1

/**
 * The open documents as tabs (#138): tap one to bring it to the front with its page, zoom and
 * tool, × to close it (saved first, as Back does), + to open another from Home, and the split
 * button to show another open document beside this one (#139).
 */
@Composable
fun TabStrip(vm: AppViewModel, doc: OpenDocument) {
    val c = pdfColors
    val tabs = vm.tabs.tabs
    val scroll = rememberScrollState()
    Row(
        Modifier.fillMaxWidth().height(TabStripHeight).background(c.surface).padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).horizontalScroll(scroll),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (tab in tabs) TabChip(vm, tab, active = tab.doc != null && tab.doc === doc)
        }
        BarIcon(LucideIcons.Plus, stringResource(R.string.tabs_open_another), { vm.tabs.openAnother() }, size = 40.dp, iconSize = 20.dp)
        SplitButton(vm)
    }
}

@Composable
private fun TabChip(vm: AppViewModel, tab: DocTab, active: Boolean) {
    val c = pdfColors
    val shape = RoundedCornerShape(8.dp)
    val label = if (tab.parked) stringResource(R.string.tabs_parked, tab.title) else tab.title
    Row(
        Modifier.heightIn(min = 32.dp)
            .background(if (active) c.pressed else Color.Transparent, shape)
            .border(1.dp, if (active) c.toolAccent else c.divider, shape)
            .semantics { selected = active }
            .clickable(role = Role.Tab, onClickLabel = label) { vm.tabs.switchTo(tab) }
            .padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            tab.title,
            modifier = Modifier.widthIn(max = 160.dp).semantics { contentDescription = label },
            color = if (active) c.toolAccent else if (tab.parked) c.textSecondary else c.text,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            fontStyle = if (tab.parked) FontStyle.Italic else FontStyle.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        BarIcon(LucideIcons.X, stringResource(R.string.tabs_close, tab.title), { vm.tabs.close(tab) }, size = 32.dp, iconSize = 16.dp)
    }
}

@Composable
private fun SplitButton(vm: AppViewModel) {
    val candidates = vm.tabs.splitCandidates()
    var choosing by remember { mutableStateOf(false) }
    val splitting = vm.tabs.splitWith != null
    Box {
        BarIcon(
            LucideIcons.Columns2,
            stringResource(if (splitting) R.string.tabs_split_close else R.string.tabs_split),
            {
                when {
                    splitting -> vm.tabs.closeSplit()
                    candidates.size == 1 -> vm.tabs.split(candidates[0])
                    else -> choosing = true
                }
            },
            selected = splitting,
            enabled = splitting || candidates.isNotEmpty(),
            size = 40.dp, iconSize = 20.dp,
        )
        DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
            Text(
                stringResource(R.string.tabs_split_choose),
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = pdfColors.textSecondary, fontSize = 12.sp,
            )
            for (tab in candidates) {
                DropdownMenuItem(text = { Text(tab.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = {
                    choosing = false
                    vm.tabs.split(tab)
                })
            }
        }
    }
}

/**
 * The viewer with split view (#139): the document in front keeps the whole editing viewer
 * ([main]); the other open document shows beside it (or below it on a tall window) with its own
 * scroll and zoom. Tapping that pane, or Edit here, moves editing to it while both panes keep
 * their places. In the other pane an area can be marked and dragged onto the document in front:
 * its text becomes a text box (an image when it has none) with a link back to the source page.
 * The divider drags.
 */
@Composable
fun SplitLayout(vm: AppViewModel, doc: OpenDocument, main: @Composable () -> Unit) {
    val tabs = vm.tabs
    val other = tabs.splitWith?.takeIf { it !== doc && !vm.readingMode && !doc.controller.pagesOpen && !doc.controller.presenting }
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val side = TabPolicy.sideBySide(config.screenWidthDp, config.screenHeightDp)
    if (other == null) {
        Box(Modifier.fillMaxSize()) { main() }
        return
    }
    var total by remember { mutableStateOf(1f) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    var mainBounds by remember { mutableStateOf<Rect?>(null) }
    var ghost by remember { mutableStateOf<Offset?>(null) }
    // the panes keep their places: the document that was in the first pane stays there
    val docFirst = tabs.splitFirst !== other
    val fraction = TabPolicy.clampSplit(tabs.splitFraction)
    val drag = rememberDraggableState { delta ->
        if (total > 0f) tabs.splitFraction = TabPolicy.clampSplit(tabs.splitFraction + delta / total)
    }
    val c = pdfColors
    val dropOffPage = stringResource(R.string.tabs_drop_off_page)
    fun dropAt(window: Offset) {
        val snip = tabs.snip ?: return
        val bounds = mainBounds ?: return
        if (!bounds.contains(window)) {
            Diag.event(DiagArea.Viewer, "split_drop", "doc" to doc.diagNumber, "other" to snip.doc.diagNumber, "kind" to "cancelled")
            return
        }
        val at = doc.viewer.screenToPage(window - bounds.topLeft)
        if (at == null) vm.tell(dropOffPage) else tabs.drop(snip, at)
    }
    val mainPane: @Composable (Modifier) -> Unit = { m ->
        Box(m.clipToBounds().onGloballyPositioned { mainBounds = it.boundsInWindow() }) { main() }
    }
    val otherPane: @Composable (Modifier) -> Unit = { m ->
        Box(m.clipToBounds()) { SplitPane(vm, other, side, onGhost = { ghost = it }, onDrop = { dropAt(it) }) }
    }
    val divider = @Composable {
        val dm = if (side) Modifier.width(12.dp).fillMaxHeight() else Modifier.height(12.dp).fillMaxWidth()
        Box(
            dm.background(c.surface)
                .draggable(drag, if (side) Orientation.Horizontal else Orientation.Vertical,
                    onDragStopped = { Diag.event(DiagArea.Viewer, "split_resize", "doc" to doc.diagNumber, "percent" to (tabs.splitFraction * 100).toInt()) }),
            contentAlignment = Alignment.Center,
        ) {
            Box((if (side) Modifier.width(4.dp).height(36.dp) else Modifier.height(4.dp).width(36.dp)).background(c.divider, RoundedCornerShape(2.dp)))
        }
    }
    Box(Modifier.fillMaxSize().onGloballyPositioned { rootOrigin = it.positionInWindow() }) {
        if (side) {
            Row(Modifier.fillMaxSize().onSizeChanged { total = it.width.toFloat() }) {
                if (docFirst) mainPane(Modifier.weight(fraction).fillMaxHeight()) else otherPane(Modifier.weight(fraction).fillMaxHeight())
                divider()
                if (docFirst) otherPane(Modifier.weight(1f - fraction).fillMaxHeight()) else mainPane(Modifier.weight(1f - fraction).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize().onSizeChanged { total = it.height.toFloat() }) {
                if (docFirst) mainPane(Modifier.weight(fraction).fillMaxWidth()) else otherPane(Modifier.weight(fraction).fillMaxWidth())
                divider()
                if (docFirst) otherPane(Modifier.weight(1f - fraction).fillMaxWidth()) else mainPane(Modifier.weight(1f - fraction).fillMaxWidth())
            }
        }
        // the dragged area follows the finger over both panes
        ghost?.let { g ->
            val local = g - rootOrigin
            SnipChip(Modifier.offset { IntOffset(local.x.roundToInt() - 24, local.y.roundToInt() - 24) })
        }
    }
    LaunchedEffect(other, side) {
        Diag.event(DiagArea.Viewer, "split_shown", "doc" to doc.diagNumber, "other" to other.diagNumber, "side_by_side" to side, "width_dp" to config.screenWidthDp, "height_dp" to config.screenHeightDp)
    }
}

@Composable
private fun SnipChip(modifier: Modifier = Modifier) {
    val c = pdfColors
    Row(
        modifier.background(c.toolAccent, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(LucideIcons.Scissors, null, tint = Color.White, modifier = Modifier.width(16.dp).height(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.tabs_snip_drag), color = Color.White, fontSize = 13.sp, maxLines = 1)
    }
}

/**
 * The other pane: another open document, scrolled and zoomed on its own. A tap moves editing
 * here; in Copy an area mode a drag marks an area, which then drags onto the other pane.
 */
@Composable
private fun SplitPane(vm: AppViewModel, doc: OpenDocument, side: Boolean, onGhost: (Offset?) -> Unit, onDrop: (Offset) -> Unit) {
    val c = pdfColors
    val tabs = vm.tabs
    val density = LocalDensity.current
    val statusPx = maxOf(WindowInsets.statusBars.getTop(density), WindowInsets.displayCutout.getTop(density))
    val navPx = WindowInsets.navigationBars.getBottom(density)
    // its page area is its own: no bars over it
    LaunchedEffect(doc) { doc.viewer.setPadding(0f, 0f) }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        if (side) Spacer(Modifier.fillMaxWidth().height(with(density) { statusPx.toDp() }))
        Row(
            Modifier.fillMaxWidth().height(TabStripHeight).background(c.surface).padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(doc.title, color = c.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (tabs.snipping) stringResource(R.string.tabs_snip_hint)
                    else stringResource(R.string.viewer_page_of, doc.viewer.currentPage.coerceIn(0, (doc.pageCount - 1).coerceAtLeast(0)) + 1, doc.pageCount),
                    color = c.textSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            BarIcon(LucideIcons.Scissors, stringResource(R.string.tabs_snip), {
                tabs.snipping = !tabs.snipping
                if (tabs.snipping) tabs.snip = null
            }, selected = tabs.snipping, size = 40.dp, iconSize = 18.dp)
            TextButton(onClick = { tabs.swapSplit() }) { Text(stringResource(R.string.tabs_split_edit_here), color = c.toolAccent, fontSize = 13.sp) }
            BarIcon(LucideIcons.X, stringResource(R.string.tabs_split_close), { tabs.closeSplit() }, size = 40.dp, iconSize = 18.dp)
        }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            var dragFrom by remember(doc) { mutableStateOf<Offset?>(null) }
            var dragTo by remember(doc) { mutableStateOf<Offset?>(null) }
            PageCanvas(
                state = doc.viewer,
                images = doc.images,
                generations = doc.pageGenerations,
                look = CanvasLook(background = c.desk, pageShadow = c.pageEdge, invert = doc.controller.nightMode),
                gap = 4.dp,
                gesturesEnabled = !tabs.snipping,
                // a tap moves editing to this pane, as a pen or finger on the other pane does
                onTap = { _, _ -> if (tabs.snip == null) tabs.swapSplit() else tabs.snip = null },
            )
            if (tabs.snipping) {
                Box(
                    Modifier.fillMaxSize().pointerInput(doc) {
                        detectDragGestures(
                            onDragStart = { dragFrom = it; dragTo = it },
                            onDrag = { change, _ -> dragTo = change.position },
                            onDragCancel = { dragFrom = null; dragTo = null },
                            onDragEnd = {
                                val a = dragFrom
                                val b = dragTo
                                dragFrom = null
                                dragTo = null
                                val start = a?.let { doc.viewer.screenToPage(it, nearest = true) }
                                val end = if (start != null && b != null) doc.viewer.screenToPageOf(start.page, b) else null
                                val area = if (start != null && end != null) SplitDrop.area(start.x, start.y, end.x, end.y) else null
                                if (start != null && area != null) {
                                    tabs.snip = Snip(doc, start.page, area)
                                    tabs.snipping = false
                                    Diag.event(DiagArea.Viewer, "split_snip", "doc" to doc.diagNumber, "page" to start.page + 1)
                                }
                            },
                        )
                    },
                )
            }
            val snip = tabs.snip?.takeIf { it.doc === doc }
            val accent = c.toolAccent
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val a = dragFrom
                val b = dragTo
                if (a != null && b != null) {
                    drawRect(accent, Offset(minOf(a.x, b.x), minOf(a.y, b.y)), Size(kotlin.math.abs(a.x - b.x), kotlin.math.abs(a.y - b.y)), style = Stroke(2.dp.toPx()))
                }
                // a back-link's area (#379) flashes here too
                doc.linkFlash?.let { f ->
                    val fa = doc.viewer.pageToScreen(f.page, f.rect[0], f.rect[1])
                    val fb = doc.viewer.pageToScreen(f.page, f.rect[2], f.rect[3])
                    drawRect(Color(0x66B36B2E), fa, Size(fb.x - fa.x, fb.y - fa.y))
                }
                if (snip != null) {
                    val tl = doc.viewer.pageToScreen(snip.page, snip.rect[0], snip.rect[1])
                    val br = doc.viewer.pageToScreen(snip.page, snip.rect[2], snip.rect[3])
                    drawRect(accent.copy(alpha = 0.15f), tl, Size(br.x - tl.x, br.y - tl.y))
                    drawRect(accent, tl, Size(br.x - tl.x, br.y - tl.y), style = Stroke(2.dp.toPx()))
                }
            }
            if (snip != null) {
                val tl = doc.viewer.pageToScreen(snip.page, snip.rect[0], snip.rect[1])
                val br = doc.viewer.pageToScreen(snip.page, snip.rect[2], snip.rect[3])
                var chipOrigin by remember { mutableStateOf(Offset.Zero) }
                var ghost by remember { mutableStateOf(Offset.Zero) }
                SnipChip(
                    Modifier.offset { IntOffset(((tl.x + br.x) / 2f).roundToInt() - 60, ((tl.y + br.y) / 2f).roundToInt() - 18) }
                        .onGloballyPositioned { chipOrigin = it.positionInWindow() }
                        .pointerInput(snip) {
                            detectDragGestures(
                                onDragStart = { ghost = chipOrigin + it; onGhost(ghost) },
                                onDrag = { change, amount -> change.consume(); ghost += amount; onGhost(ghost) },
                                onDragCancel = { onGhost(null) },
                                onDragEnd = { onGhost(null); onDrop(ghost) },
                            )
                        },
                )
            }
        }
        if (!side) Spacer(Modifier.fillMaxWidth().height(with(density) { navPx.toDp() }))
    }
}

/**
 * On Home with documents still open in tabs (#138): go back to them, or close them all.
 */
@Composable
fun OpenTabsBar(vm: AppViewModel, modifier: Modifier = Modifier) {
    val c = pdfColors
    val count = vm.tabs.tabs.size
    if (count == 0) return
    Row(
        modifier.background(c.surfaceRaised, RoundedCornerShape(12.dp)).border(1.dp, c.divider, RoundedCornerShape(12.dp))
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            androidx.compose.ui.res.pluralStringResource(R.plurals.tabs_open_count, count, count),
            color = c.text, fontSize = 14.sp, modifier = Modifier.padding(vertical = 10.dp),
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = { vm.tabs.closeOthers() }) { Text(stringResource(R.string.tabs_close_all), color = c.textSecondary, fontSize = 13.sp) }
        TextButton(onClick = { vm.tabs.resume() }) { Text(stringResource(R.string.tabs_resume), color = c.toolAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
    }
}
