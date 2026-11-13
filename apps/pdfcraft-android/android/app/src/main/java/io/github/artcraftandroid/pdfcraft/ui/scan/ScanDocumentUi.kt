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
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The document screen (#460): the scan's pages in a grid, two across on a phone. A tap opens the
 * page in Enhance; holding a page and dragging moves it, holding it without moving selects it.
 * With pages picked the bar turns them or deletes them. Add page, Import, Share and Save PDF
 * are always in the same places. [onOpen] gets the page tapped, counted from 0.
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
    var askingDelete by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    // a page deleted or retaken meanwhile is no longer chosen
    LaunchedEffect(keys) { picked = scanSelectionKept(picked, keys) }
    val selecting = choosing || picked.isNotEmpty()
    val renamable = vm.insertScanAt == null
    val back = {
        when {
            selecting -> {
                picked = emptySet()
                choosing = false
            }
            // with pages, Back asks first (#456)
            scanBackAsks(pages.size, vm.scanLab.options) -> askingDiscard = true
            else -> vm.discardScan()
        }
    }
    BackHandler(onBack = back)
    fun toggle(key: String) {
        picked = if (key in picked) picked - key else picked + key
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
                Box {
                    BarIcon(LucideIcons.EllipsisVertical, stringResource(R.string.scan_enhance_more), { menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.scan_doc_select)) }, onClick = {
                            menu = false
                            choosing = true
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.scan_enhance_rename)) }, enabled = renamable, onClick = {
                            menu = false
                            naming = true
                        })
                        // every page into the gallery's PdfCraft album (#470); selected pages from the selection's bar
                        DropdownMenuItem(text = { Text(stringResource(R.string.scan_gallery_save_all)) }, enabled = pages.isNotEmpty(), onClick = {
                            menu = false
                            vm.saveScanToGallery(null)
                        })
                    }
                }
                Spacer(Modifier.width(4.dp))
            }
        }
        // a reminder that the scanner is not as it is meant to be used: a testing option is on (#288)
        if (!vm.scanLab.options.asDesigned) {
            Text(stringResource(R.string.scan_lab_active), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        }
        Text(stringResource(R.string.scan_doc_hint), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        // every page made in the background here too (#494): a page rotated from the selection bar,
        // or imported, is made again without opening Enhance
        val lab = vm.scanLab.options
        val autoUpright = ScanUprightSetting.get(vm.settings.appPrefs)
        LaunchedEffect(pages.toList(), lab, autoUpright, vm.scanCanFlatten) { vm.pageQueue.kick() }
        // what the tiles showed when the screen opened, once per opening (#494)
        LaunchedEffect(Unit) {
            Diag.event(DiagArea.Scan, "doc_shown", *scanDocShownDiag(pages.size, pages.count { vm.pageQueue.madeOf(it) != null }, lab.backgroundPages && lab.preview))
        }
        // how many pages are ready while some are still being made (#465, #494)
        vm.pageQueue.preparing(pages)?.let { (ready, total) ->
            Text(stringResource(R.string.scan_pages_preparing, ready, total), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        }
        val grid = rememberLazyGridState()
        var dragFrom by remember { mutableStateOf<Int?>(null) }
        var dragTo by remember { mutableStateOf<Int?>(null) }
        var dragged by remember { mutableStateOf(false) }
        val haptic = LocalHapticFeedback.current
        val looks = pages.map { it.look }
        val earlier = stringResource(R.string.scan_doc_move_earlier)
        val later = stringResource(R.string.scan_doc_move_later)
        val openLabel = stringResource(R.string.scan_doc_open)
        val selectLabel = stringResource(R.string.scan_doc_choose)
        LazyVerticalGrid(
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
                        .clickable(onClickLabel = if (selecting) selectLabel else openLabel) { if (selecting) toggle(p.key) else onOpen(i) }
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
        val chosen = scanSelectedIndexes(picked, keys)
        ScanDocumentBar(
            selecting, chosen.size, vm.insertScanAt == null, pages.isNotEmpty(),
            onRotate = { chosen.forEach { vm.rotateScanPage(it) } },
            onDelete = { askingDelete = true },
            onGallery = { vm.saveScanToGallery(chosen) },
            onAdd = { vm.takeScanPhoto() },
            onImport = { vm.importScanPhotos() },
            onShare = { vm.saveScan(share = true) },
            onSave = { vm.saveScan() },
        )
    }
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
 * turn is in it), read at the tile's size; before it is made, the part of the photo inside its
 * corners, turned as it will be saved. While a page is made again, its last result stays.
 */
@Composable
private fun ScanDocumentThumb(vm: AppViewModel, page: ScanPage, photoTurns: Int, modifier: Modifier) {
    val m = vm.pageQueue.madeOf(page)
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
) {
    val c = pdfColors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val share = Modifier.weight(1f)
        if (selecting) {
            ScanBarButton(LucideIcons.RotateCw, stringResource(R.string.scan_rotate), chosen > 0, share, onRotate)
            ScanBarButton(LucideIcons.Image, stringResource(R.string.scan_gallery_save), chosen > 0, share, onGallery)
            ScanBarButton(LucideIcons.Trash2, stringResource(R.string.common_delete), chosen > 0, share, onDelete)
        } else {
            ScanBarButton(LucideIcons.Camera, stringResource(R.string.tools_add_page), true, share, onAdd)
            ScanBarButton(LucideIcons.Images, stringResource(R.string.scan_camera_import_short), true, share, onImport)
            if (canShare) ScanBarButton(LucideIcons.Share2, stringResource(R.string.common_share), hasPages, share, onShare)
            Button(
                onClick = onSave,
                enabled = hasPages,
                colors = ButtonDefaults.buttonColors(containerColor = c.button, contentColor = Color.White, disabledContainerColor = c.pressed),
                shape = RoundedCornerShape(24.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                // as wide as its words, so "PDF speichern" or "Enregistrer le PDF" is never cut; the icons share the rest
                modifier = Modifier.padding(horizontal = 4.dp).widthIn(max = 176.dp).height(48.dp),
            ) { Text(stringResource(R.string.tools_save_pdf), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}
