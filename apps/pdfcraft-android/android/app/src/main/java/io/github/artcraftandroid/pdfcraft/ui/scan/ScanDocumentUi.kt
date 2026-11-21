package io.github.artcraftandroid.pdfcraft.ui.scan

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.BarIcon
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.PdfDialog
import io.github.artcraftandroid.pdfcraft.ui.PdfField
import io.github.artcraftandroid.pdfcraft.ui.ScanPage
import io.github.artcraftandroid.pdfcraft.ui.ScanPageMark
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import io.github.artcraftandroid.pdfcraft.ui.safeTopPadding
import kotlinx.coroutines.flow.first
import kotlin.math.min
import kotlin.math.roundToInt
import io.github.artcraftandroid.pdfcraft.ui.saveScan
import io.github.artcraftandroid.pdfcraft.ui.saveScanToGallery
import io.github.artcraftandroid.pdfcraft.ui.deleteScanPages
import io.github.artcraftandroid.pdfcraft.ui.discardScan
import io.github.artcraftandroid.pdfcraft.ui.importScanPhotos
import io.github.artcraftandroid.pdfcraft.ui.keepScanAfterAsking
import io.github.artcraftandroid.pdfcraft.ui.keepScanPages
import io.github.artcraftandroid.pdfcraft.ui.moveScanPage
import io.github.artcraftandroid.pdfcraft.ui.takeScanPhoto

/**
 * The document screen (#460): the scan's pages in a grid, two across on a phone. A tap opens the
 * page in Enhance; holding a page and dragging moves it, holding it without moving selects it.
 * With pages picked the bar turns them or deletes them. Add page, Import, Share and Save PDF
 * are always in the same places. [onOpen] gets the page tapped, counted from 0.
 *
 * It opens on the pages one under the other (#786, [ScanContinuousPages]), with Edit for the page
 * in sight; Organize pages in the top bar shows the grid, and View pages comes back to the same
 * page. Testing option "Open a finished scan as continuous pages": off is the grid alone.
 */
@Composable
internal fun ScanDocumentScreen(vm: AppViewModel, onOpen: (Int) -> Unit) {
    val c = pdfColors
    val pages = vm.scanPages
    val keys = pages.map { it.key }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Select pages in ⋮ opens the selection with nothing chosen yet, for whoever does not find the hold
    var choosing by remember { mutableStateOf(false) }
    var askingDiscard by remember { mutableStateOf(false) }
    // the size choice before Save PDF (false) or Share (true) (#505)
    var sizing by remember { mutableStateOf<Boolean?>(null) }
    var askingDelete by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    // the continuous pages or the grid (#786): kept with the steps' state, so it is the same after Enhance
    val docView = vm.scanSteps.docView
    val continuous = vm.scanLab.options.continuousPages
    val view = scanDocViewShown(docView.chosen, continuous)
    val grid = rememberLazyGridState()
    // the page the continuous view has in sight: Edit opens it
    var currentPage by remember { mutableStateOf(scanAnchorIndex(docView.anchor, keys)) }
    fun showView(to: ScanDocView, by: String) {
        if (to == view) return
        // leaving the grid: the page it comes back to when that one is in sight, else the first one in sight
        if (view == ScanDocView.Grid) {
            val at = scanAnchorFromGrid(scanAnchorIndex(docView.anchor, keys), grid.layoutInfo.visibleItemsInfo.map { it.index })
            docView.anchor = ScanDocAnchor(keys.getOrNull(at), at)
        }
        docView.show(to)
        Diag.event(DiagArea.Scan, "doc_view", *scanDocViewDiag(to, view, scanAnchorIndex(docView.anchor, keys), pages.size, by))
    }
    // a page opened in Enhance is the one both views come back to (#786)
    fun openPage(index: Int) {
        docView.anchor = ScanDocAnchor(keys.getOrNull(index), index)
        onOpen(index)
    }
    // a page deleted or retaken meanwhile is no longer chosen
    LaunchedEffect(keys) { picked = scanSelectionKept(picked, keys) }
    val selecting = choosing || picked.isNotEmpty()
    // a scan opened from its PDF keeps that file's name (#513)
    val renamable = vm.insertScanAt == null && vm.scanEdit.target == null
    // Review pages on the camera (#780): back to the camera that is waiting, by the button ([by] `button`) or Back (`back`)
    fun backToCamera(by: String) {
        vm.scanSteps.endReview(by, pages.size)
        vm.takeScanPhoto()
    }
    val back = {
        when {
            selecting -> {
                picked = emptySet()
                choosing = false
            }
            // the pages are under review from the camera (#780): Back goes back to it, and ends nothing
            vm.scanSteps.reviewing -> backToCamera("back")
            // with pages, Back asks first (#456)
            scanBackAsks(pages.size, vm.scanLab.options) -> askingDiscard = true
            else -> vm.discardScan()
        }
    }
    BackHandler(onBack = back)
    fun toggle(key: String) {
        picked = if (key in picked) picked - key else picked + key
    }
    // the selection opened by Select: with passes over several pages (#515) every page starts ticked, ready for Borders
    fun startSelecting() {
        // pages are chosen in the grid (#786)
        showView(ScanDocView.Grid, "select")
        choosing = true
        if (vm.scanLab.options.batchBorders) picked = keys.toSet()
    }
    Column(Modifier.fillMaxSize().background(c.surface).safeTopPadding().navigationBarsPadding().background(c.surface)) {
        Row(Modifier.fillMaxWidth().height(io.github.artcraftandroid.pdfcraft.ui.viewer.TopBarHeight), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                BarIcon(
                    if (selecting) LucideIcons.X else LucideIcons.ArrowLeft,
                    stringResource(if (selecting) R.string.scan_doc_clear else R.string.common_back), back, iconSize = 27.dp,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (selecting) {
                Text(
                    pluralStringResource(R.plurals.scan_doc_selected, picked.size, picked.size), color = c.label, fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                )
                BarIcon(LucideIcons.ListChecks, stringResource(R.string.scan_doc_select_all), { picked = keys.toSet() }, enabled = picked.size < keys.size)
                Spacer(Modifier.width(4.dp))
            } else {
                // the name, tap to change it (a scan going into an open document has none)
                val name = vm.scanName.ifBlank { stringResource(R.string.common_tool_scan) }
                val nameLabel = stringResource(R.string.tools_scan_name_description, name)
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                        .then(if (renamable) Modifier.clickable(role = Role.Button) { naming = true }.semantics { contentDescription = nameLabel } else Modifier)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(name, color = c.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (renamable) {
                        Spacer(Modifier.width(8.dp))
                        Icon(LucideIcons.Pencil, null, tint = c.icon, modifier = Modifier.size(18.dp))
                    }
                }
                val reach = vm.scanLab.options.toolsInReach
                // Select in sight, for whoever does not know about the hold (#516)
                if (scanDocSelectShown(reach)) {
                    androidx.compose.material3.TextButton(onClick = {
                        vm.scanToolOpened(ScanReachTool.SelectPages, "bar")
                        startSelecting()
                    }) { Text(stringResource(R.string.scan_doc_select_short), color = c.accent, fontSize = 15.sp, maxLines = 1) }
                }
                // the grid for putting pages in order, or back to the pages one under the other (#786)
                if (continuous) {
                    val toGrid = view == ScanDocView.Pages
                    BarIcon(
                        if (toGrid) LucideIcons.LayoutGrid else LucideIcons.GalleryVerticalEnd,
                        stringResource(if (toGrid) R.string.scan_doc_view_grid else R.string.scan_doc_view_pages),
                        { showView(if (toGrid) ScanDocView.Grid else ScanDocView.Pages, "button") }, size = 48.dp,
                    )
                }
                Box {
                    BarIcon(LucideIcons.EllipsisVertical, stringResource(R.string.scan_enhance_more), { menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        for (item in scanDocMenuItems(reach)) {
                            val label = when (item) {
                                ScanDocMenuItem.Select -> R.string.scan_doc_select
                                ScanDocMenuItem.Rename -> R.string.scan_enhance_rename
                                // every page into the gallery's PdfCraft album (#470); selected pages from the selection's bar
                                ScanDocMenuItem.Gallery -> R.string.scan_gallery_save_all
                                ScanDocMenuItem.ExtractText -> R.string.scan_doc_extract_text
                                ScanDocMenuItem.Print -> R.string.sheet_print
                            }
                            val enabled = when (item) {
                                ScanDocMenuItem.Select -> true
                                ScanDocMenuItem.Rename -> renamable
                                else -> pages.isNotEmpty()
                            }
                            DropdownMenuItem(text = { Text(stringResource(label)) }, enabled = enabled, onClick = {
                                menu = false
                                when (item) {
                                    ScanDocMenuItem.Select -> {
                                        vm.scanToolOpened(ScanReachTool.SelectPages, "menu")
                                        startSelecting()
                                    }
                                    ScanDocMenuItem.Rename -> {
                                        vm.scanToolOpened(ScanReachTool.Rename, "menu")
                                        naming = true
                                    }
                                    ScanDocMenuItem.Gallery -> {
                                        vm.scanToolOpened(ScanReachTool.Gallery, "menu")
                                        vm.saveScanToGallery(null)
                                    }
                                    // the scan made as Save would make it, without saving it (#516)
                                    ScanDocMenuItem.ExtractText -> {
                                        vm.scanToolOpened(ScanReachTool.ExtractText, "menu")
                                        vm.saveScan(size = "best", out = ScanOut.Text)
                                    }
                                    ScanDocMenuItem.Print -> {
                                        vm.scanToolOpened(ScanReachTool.Print, "menu")
                                        vm.saveScan(size = "best", out = ScanOut.Print)
                                    }
                                }
                            })
                        }
                    }
                }
                Spacer(Modifier.width(4.dp))
            }
        }
        // a reminder that the scanner is not as it is meant to be used: a testing option is on (#288)
        if (!vm.scanLab.options.asDesigned) {
            Text(stringResource(R.string.scan_lab_active), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        }
        // the grid's own hint: the continuous pages say what a tap does to TalkBack, and have Edit
        if (view == ScanDocView.Grid) {
            Text(stringResource(R.string.scan_doc_hint), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        // after an import of several photos (#515): their borders checked in one pass
        val offer = vm.scanBatch.offer
        if (offer > 0 && !selecting && vm.scanLab.options.batchBorders && !vm.scanReviewOfferShown()) {
            ScanBatchOfferRow(
                offer,
                onCheck = {
                    val imported = keys.filter { it !in vm.scanBatch.offerBefore }.toSet()
                    vm.startScanBatch(ScanBatchWhat.Borders, imported.ifEmpty { keys.toSet() }, "offer")
                },
                onDismiss = {
                    vm.scanBatch.offer = 0
                    Diag.event(DiagArea.Scan, "batch_offer_dismissed", "pages" to offer)
                },
            )
        }
        // pages whose page wasn't found or is unsure (#758): those, and only those, checked in one pass
        // with the review (#783) by each page's review state, and before the import's offer
        val unsure = vm.scanReviewPages()
        val unsureKeys = unsure.map { it.key }.toSet()
        val unsureLab = vm.scanLab.options
        if (!selecting && scanUnsureOffers(unsureLab.checkUnsure, unsureLab.batchBorders, unsureLab.steps, unsureKeys, vm.scanBatch.unsureDismissed, if (unsureLab.reviewBorders) 0 else offer)) {
            LaunchedEffect(unsureKeys) {
                Diag.event(DiagArea.Scan, "unsure_offer", *scanUnsureOfferDiag(unsure.size, unsure.count { it.found == null }, pages.size))
            }
            ScanBatchOfferRow(
                unsure.size,
                onCheck = { vm.startScanBatch(ScanBatchWhat.Borders, unsureKeys, "unsure") },
                onDismiss = {
                    vm.scanBatch.unsureDismissed = vm.scanBatch.unsureDismissed + unsureKeys
                    Diag.event(DiagArea.Scan, "unsure_offer_dismissed", "pages" to unsureKeys.size)
                },
                text = R.plurals.scan_unsure_offer,
            )
        }
        // every page made in the background here too (#494): a page rotated from the selection bar,
        // or imported, is made again without opening Enhance
        val lab = vm.scanLab.options
        val autoUpright = ScanUprightSetting.get(vm.settings.appPrefs)
        LaunchedEffect(pages.toList(), lab, autoUpright, vm.scanCanFlatten) { vm.pageQueue.kick() }
        // what the tiles showed when the screen opened, once per opening (#494)
        LaunchedEffect(Unit) {
            val opened = android.os.SystemClock.elapsedRealtime()
            val background = lab.backgroundPages && lab.preview
            val at = scanAnchorIndex(docView.anchor, keys)
            Diag.event(DiagArea.Scan, "doc_shown", *scanDocShownDiag(pages.size, pages.count { vm.pageQueue.madeOf(it) != null }, background, view.id, docView.route, at))
            // how long until the page the screen opened on was the page as made, then every page (#786)
            val openedOn = pages.getOrNull(at)
            if (continuous && background && openedOn != null) {
                val openedAs = scanDocPageState(true, vm.pageQueue.readyOf(openedOn) != null, vm.pageQueue.mark(openedOn) == ScanPageMark.Working, vm.pageQueue.failedOf(openedOn))
                // null: the page went (deleted or retaken) before it was made
                val ready: Boolean? = snapshotFlow { vm.scanPages.firstOrNull { it.key == openedOn.key }?.let { p -> vm.pageQueue.readyOf(p) != null } }.first { it != false }
                if (ready == true) {
                    Diag.event(DiagArea.Scan, "doc_page_ready", *scanDocPageReadyDiag(at, vm.scanPages.size, android.os.SystemClock.elapsedRealtime() - opened, view, openedAs))
                }
                snapshotFlow { vm.scanPages.all { vm.pageQueue.readyOf(it) != null || vm.pageQueue.failedOf(it) } }.first { it }
                Diag.event(DiagArea.Scan, "doc_pages_ready", *scanDocAllReadyDiag(vm.scanPages.size, vm.scanPages.count { vm.pageQueue.failedOf(it) }, android.os.SystemClock.elapsedRealtime() - opened, view))
            }
        }
        // how many pages are ready while some are still being made (#465, #494)
        vm.pageQueue.preparing(pages)?.let { (ready, total) ->
            Text(stringResource(R.string.scan_pages_preparing, ready, total), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        }
        // the grid opens on the page the continuous view was on, or the page last opened (#786)
        LaunchedEffect(view, continuous) {
            if (continuous && view == ScanDocView.Grid) {
                val at = scanAnchorIndex(docView.anchor, keys)
                // once the grid is laid out, so a page already in sight moves nothing
                if (at > 0) {
                    snapshotFlow { grid.layoutInfo.visibleItemsInfo.isNotEmpty() }.first { it }
                    if (grid.layoutInfo.visibleItemsInfo.none { it.index == at }) grid.scrollToItem(at)
                }
            }
        }
        var dragFrom by remember { mutableStateOf<Int?>(null) }
        var dragTo by remember { mutableStateOf<Int?>(null) }
        var dragged by remember { mutableStateOf(false) }
        val haptic = LocalHapticFeedback.current
        val looks = pages.map { it.look }
        val earlier = stringResource(R.string.scan_doc_move_earlier)
        val later = stringResource(R.string.scan_doc_move_later)
        val openLabel = stringResource(R.string.scan_doc_open)
        val selectLabel = stringResource(R.string.scan_doc_choose)
        if (view == ScanDocView.Pages) {
            ScanContinuousPages(
                vm, pages, Modifier.weight(1f).fillMaxWidth(),
                onCurrent = { at ->
                    currentPage = at
                    docView.anchor = ScanDocAnchor(keys.getOrNull(at), at)
                },
                onOpen = { openPage(it) },
            )
        } else LazyVerticalGrid(
            // two across on a phone, more on a wide screen
            columns = GridCells.Adaptive(148.dp),
            state = grid,
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            itemsIndexed(pages, key = { _, p -> p.key }) { i, p ->
                val own = scanLookDiffers(looks, i)
                val isSel = p.key in picked
                val target = dragTo == i && dragFrom != null && dragFrom != i
                val label = if (own) stringResource(R.string.scan_page_own_look, i + 1) else stringResource(R.string.tools_page_number, i + 1)
                Column(
                    Modifier
                        .graphicsLayer { alpha = if (dragFrom == i && dragged) 0.4f else 1f }
                        .pointerInput(i, p.key) {
                            // holding a page: dragging moves it, letting go without moving selects it
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    dragFrom = i
                                    dragTo = i
                                    dragged = false
                                },
                                onDragEnd = {
                                    val from = dragFrom
                                    val to = dragTo
                                    val moved = dragged
                                    dragFrom = null
                                    dragTo = null
                                    dragged = false
                                    if (from != null && to != null && to != from) {
                                        vm.moveScanPage(from, to, "drag")
                                    } else if (!moved) {
                                        toggle(p.key)
                                    }
                                },
                                onDragCancel = {
                                    dragFrom = null
                                    dragTo = null
                                    dragged = false
                                },
                            ) { change, amount ->
                                change.consume()
                                if (amount.getDistance() > 0f) dragged = true
                                val info = grid.layoutInfo
                                val me = info.visibleItemsInfo.firstOrNull { it.index == i } ?: return@detectDragGesturesAfterLongPress
                                val cells = info.visibleItemsInfo.map { it.index to intArrayOf(it.offset.x, it.offset.y, it.size.width, it.size.height) }
                                scanDropIndex(cells, me.offset.x + change.position.x, me.offset.y + change.position.y)?.let { dragTo = it }
                            }
                        }
                        .clickable(onClickLabel = if (selecting) selectLabel else openLabel) { if (selecting) toggle(p.key) else openPage(i) }
                        .semantics(mergeDescendants = true) {
                            contentDescription = label
                            if (selecting) selected = isSel
                            // TalkBack cannot drag: the page moves one place at a time (#460)
                            customActions = listOfNotNull(
                                scanMoveTarget(i, -1, pages.size)?.let { to -> CustomAccessibilityAction(earlier) { vm.moveScanPage(i, to, "talkback"); true } },
                                scanMoveTarget(i, 1, pages.size)?.let { to -> CustomAccessibilityAction(later) { vm.moveScanPage(i, to, "talkback"); true } },
                            )
                            onLongClick(selectLabel) { toggle(p.key); true }
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val highlighted = isSel || target
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(6.dp)).background(c.surfaceRaised)
                            .border(if (highlighted) 3.dp else 1.dp, if (highlighted) c.accent else c.divider, RoundedCornerShape(6.dp)),
                    ) {
                        // the page as made once the queue made it, else the photo with its waiting / working mark (#494)
                        ScanDocumentThumb(vm, p, scanTurnShown(p.turns, vm.scanAutoTurns(i)), Modifier.fillMaxSize().padding(8.dp))
                        when (vm.pageQueue.mark(p)) {
                            ScanPageMark.Ready -> Unit
                            ScanPageMark.Waiting -> Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).size(16.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).border(1.5.dp, Color.White, CircleShape))
                            ScanPageMark.Working -> Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).size(22.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                                androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), color = Color.White, strokeWidth = 1.5.dp)
                            }
                        }
                        // a spread's halves keep their names (#303)
                        p.book?.let { ScanBookLabel(it, Modifier.align(Alignment.TopStart)) }
                        // a page whose borders still need a check says so, also once the offer is closed (#783)
                        if (unsureLab.reviewBorders && unsureLab.checkUnsure && p.key in unsureKeys) ScanReviewBadge(Modifier.align(Alignment.BottomStart))
                        if (selecting) {
                            Box(
                                Modifier.align(Alignment.TopEnd).padding(8.dp).size(22.dp).clip(CircleShape)
                                    .background(if (isSel) c.accent else Color.Black.copy(alpha = 0.25f))
                                    .border(2.dp, Color.White, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isSel) Icon(LucideIcons.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
                            }
                        } else if (own) {
                            // the dot of a page whose look is not the others' (#301)
                            Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(10.dp).clip(CircleShape).background(c.accent).border(1.dp, Color.White, CircleShape))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("${i + 1}", color = c.icon, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        // Review pages on the camera (#780): one tap back to the camera that is waiting
        if (vm.scanSteps.reviewing && !selecting) ScanReviewReturn { backToCamera("button") }
        val chosen = scanSelectedIndexes(picked, keys)
        ScanDocumentBar(
            selecting, chosen.size, vm.insertScanAt == null, pages.isNotEmpty(),
            onRotate = {
                vm.scanToolOpened(ScanReachTool.Rotate, "selection")
                chosen.forEach { vm.rotateScanPage(it) }
            },
            // borders and looks for the chosen pages in one pass (#515)
            batch = vm.scanLab.options.batchBorders,
            onBorders = {
                vm.scanToolOpened(ScanReachTool.Borders, "selection")
                vm.startScanBatch(ScanBatchWhat.Borders, picked, "selection")
            },
            onLook = {
                vm.scanToolOpened(ScanReachTool.Look, "selection")
                vm.startScanBatch(ScanBatchWhat.Look, picked, "selection")?.let { first ->
                    picked = emptySet()
                    choosing = false
                    openPage(first)
                }
            },
            onDelete = {
                vm.scanToolOpened(ScanReachTool.Delete, "selection")
                askingDelete = true
            },
            onGallery = {
                vm.scanToolOpened(ScanReachTool.Gallery, "selection")
                vm.saveScanToGallery(chosen)
            },
            onAdd = { vm.takeScanPhoto() },
            onImport = { vm.importScanPhotos() },
            // the size choice first (#505), unless a testing option sets one for every save
            onShare = { if (vm.scanLab.options.size != null) vm.saveScan(share = true) else sizing = true },
            onSave = { if (vm.scanLab.options.size != null) vm.saveScan() else sizing = false },
            // a scan opened from its PDF goes back into it (#513)
            saveLabel = if (vm.scanEdit.target != null) R.string.scan_edit_save else R.string.tools_save_pdf,
            // Edit for the page in sight in the continuous view (#786); in the grid a tap on a page opens it
            onEdit = if (view == ScanDocView.Pages) {
                { if (pages.isNotEmpty()) openPage(currentPage.coerceIn(0, pages.lastIndex)) }
            } else {
                null
            },
        )
    }
    sizing?.let { share -> ScanSizeSheet(vm, share) { sizing = null } }
    if (askingDiscard) {
        ScanDiscardDialog(
            pages.size,
            onKeep = { askingDiscard = false; vm.keepScanAfterAsking() },
            onDiscard = { askingDiscard = false; vm.discardScan(asked = true) },
        )
    }
    if (askingDelete) {
        val chosen = scanSelectedIndexes(picked, keys)
        PdfDialog(
            onDismiss = { askingDelete = false; vm.keepScanPages(chosen.size) },
            title = pluralStringResource(R.plurals.scan_doc_delete_title, chosen.size, chosen.size),
            confirm = stringResource(R.string.common_delete),
            onConfirm = {
                askingDelete = false
                picked = emptySet()
                choosing = false
                vm.deleteScanPages(chosen)
            },
            destructive = true,
        ) {
            Text(stringResource(R.string.scan_doc_delete_body), color = c.text, fontSize = 15.sp)
        }
    }
    if (naming) {
        var name by remember { mutableStateOf(vm.scanName) }
        PdfDialog(
            onDismiss = { naming = false }, title = stringResource(R.string.common_file_name),
            confirm = stringResource(R.string.common_ok), confirmEnabled = name.isNotBlank(), onConfirm = { vm.scanName = name.trim(); naming = false },
        ) {
            PdfField(name, { name = it }, stringResource(R.string.common_file_name))
        }
    }
}

/**
 * A page's tile (#494): the page as made by the background queue, whole and as it was made (the
 * turn is in it), read at the tile's size; before it is made, its first picture (#785), or without
 * one the part of the photo inside its corners, turned as it will be saved. While a page is made
 * again, its first picture as it is now is shown, or without one its last result stays.
 */
@Composable
internal fun ScanDocumentThumb(vm: AppViewModel, page: ScanPage, photoTurns: Int, modifier: Modifier) {
    // its first picture while the page is not made as it is now (#785)
    val m = vm.pageQueue.tileOf(page)
    val tile = scanDocTile(m != null, photoTurns)
    BoxWithConstraints(modifier) {
        if (m != null && tile.made) {
            val w = constraints.maxWidth.coerceAtLeast(1)
            val h = constraints.maxHeight.coerceAtLeast(1)
            // the small thumbnail at once, the sharp one when it is read
            val image by produceState(m.thumb, m.file, w, h) { value = vm.pageQueue.tileImage(m, w, h) ?: m.thumb }
            Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            ScanPhotoTile(page, tile.turns, Modifier.fillMaxSize())
        }
    }
}

/**
 * A page's first picture on the scan screen (#785), fitted whole while the page itself is made: its
 * small thumbnail at once, the picture when it is read. [label] says which page it is.
 */
@Composable
internal fun ScanFirstPicture(vm: AppViewModel, first: io.github.artcraftandroid.pdfcraft.ui.ScanMadePage, label: String, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val w = constraints.maxWidth.coerceAtLeast(1)
        val h = constraints.maxHeight.coerceAtLeast(1)
        val image by produceState(first.thumb, first.file, w, h) { value = vm.pageQueue.tileImage(first, w, h) ?: first.thumb }
        Image(image, label, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
}

/** The part of the photo inside a page's corners, turned by [turns] quarters. */
@Composable
private fun ScanPhotoTile(page: ScanPage, turns: Int, modifier: Modifier) {
    val thumb: ImageBitmap = page.thumb
    val region = remember(page.corners, thumb) { scanThumbRegion(page.corners, thumb.width, thumb.height) }
    Canvas(modifier) {
        val q = ((turns % 4) + 4) % 4
        val (sw, sh) = region[2].toFloat() to region[3].toFloat()
        // a quarter turn swaps the room the page has
        val k = if (q % 2 == 1) min(size.width / sh, size.height / sw) else min(size.width / sw, size.height / sh)
        val dw = (sw * k).roundToInt().coerceAtLeast(1)
        val dh = (sh * k).roundToInt().coerceAtLeast(1)
        rotate(q * 90f) {
            drawImage(
                thumb, srcOffset = IntOffset(region[0], region[1]), srcSize = IntSize(region[2], region[3]),
                dstOffset = IntOffset(((size.width - dw) / 2f).roundToInt(), ((size.height - dh) / 2f).roundToInt()), dstSize = IntSize(dw, dh),
            )
        }
    }
}

/**
 * The document screen's bottom bar (#460): Add page, Import, Share and a filled Save PDF; with
 * pages selected, Rotate, Save to gallery (#470) and Delete for them. [canShare] is false for a scan going into an open
 * document, which is not shared on its own.
 */
@Composable
internal fun ScanDocumentBar(
    selecting: Boolean,
    chosen: Int,
    canShare: Boolean,
    hasPages: Boolean,
    onRotate: () -> Unit,
    onDelete: () -> Unit,
    onGallery: () -> Unit,
    onAdd: () -> Unit,
    onImport: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    /** Save PDF, or "Save to this PDF" for a scan opened from its PDF (#513). */
    saveLabel: Int = R.string.tools_save_pdf,
    /** Borders and Look for the chosen pages (#515). */
    batch: Boolean = false,
    onBorders: () -> Unit = {},
    onLook: () -> Unit = {},
    /** Edit for the page in sight in the continuous view (#786); null leaves the button out. */
    onEdit: (() -> Unit)? = null,
) {
    val c = pdfColors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val share = Modifier.weight(1f)
        if (selecting) {
            if (batch) {
                ScanBarButton(LucideIcons.Crop, stringResource(R.string.scan_enhance_borders), chosen > 0, share, onBorders)
                ScanBarButton(LucideIcons.Palette, stringResource(R.string.scan_batch_look), chosen > 0, share, onLook)
            }
            ScanBarButton(LucideIcons.RotateCw, stringResource(R.string.scan_rotate), chosen > 0, share, onRotate)
            ScanBarButton(LucideIcons.Image, stringResource(R.string.scan_gallery_save), chosen > 0, share, onGallery)
            ScanBarButton(LucideIcons.Trash2, stringResource(R.string.common_delete), chosen > 0, share, onDelete)
        } else {
            ScanBarButton(LucideIcons.Camera, stringResource(R.string.tools_add_page), true, share, onAdd)
            ScanBarButton(LucideIcons.Images, stringResource(R.string.scan_camera_import_short), true, share, onImport)
            if (onEdit != null) ScanBarButton(LucideIcons.Pencil, stringResource(R.string.scan_edit_page_edit), hasPages, share, onEdit)
            if (canShare) ScanBarButton(LucideIcons.Share2, stringResource(R.string.common_share), hasPages, share, onShare)
            Button(
                onClick = onSave,
                enabled = hasPages,
                colors = ButtonDefaults.buttonColors(containerColor = c.button, contentColor = Color.White, disabledContainerColor = c.pressed),
                shape = RoundedCornerShape(24.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                // as wide as its words, so "PDF speichern" or "Enregistrer le PDF" is never cut; the icons share the rest.
                // With Edit on the bar (#786) there are four icons: Save takes two of six shares at most and its
                // words wrap, so each icon keeps 48 dp on a 320 dp screen ("Save to this PDF" is the long one)
                modifier = if (onEdit != null) {
                    Modifier.padding(horizontal = 4.dp).weight(2f, fill = false).heightIn(min = 48.dp)
                } else {
                    Modifier.padding(horizontal = 4.dp).widthIn(max = 176.dp).height(48.dp)
                },
            ) {
                Text(
                    stringResource(saveLabel), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = if (onEdit != null) 2 else 1,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
            }
        }
    }
}
