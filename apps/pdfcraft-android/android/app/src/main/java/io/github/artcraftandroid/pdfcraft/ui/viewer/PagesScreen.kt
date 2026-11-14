package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.foundation.selection.selectable
import io.github.artcraftandroid.pdfcraft.ui.undoLabel
import io.github.artcraftandroid.pdfcraft.ui.redoLabel
import io.github.artcraftandroid.pdfcraft.ui.safeTopPadding
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.plural
import io.github.artcraftandroid.pdfcraft.text
import io.github.artcraftandroid.pdfcraft.engine.toBitmap
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.BarIcon
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import io.github.artcraftandroid.pdfcraft.ui.PdfSheet
import io.github.artcraftandroid.pdfcraft.ui.SheetRow
import io.github.artcraftandroid.pdfcraft.ui.SheetTitle
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import kotlinx.coroutines.withContext

/**
 * Pages: the document's pages as a grid (two across upright, four sideways). Viewing, a tap goes
 * to the page. Organizing, pages are picked with their check boxes and turned, deleted,
 * extracted or added to, and moved by holding and dragging.
 */
@Composable
fun PagesScreen(vm: AppViewModel, doc: OpenDocument, onClose: () -> Unit) {
    val c = pdfColors
    val organizing = vm.tools.organizing
    val selected = remember { mutableStateMapOf<Int, Boolean>() }
    val thumbs = remember(doc.info.generation) { mutableStateMapOf<Int, ImageBitmap>() }
    var insertMenu by remember { mutableStateOf(false) }
    // the page a template page goes to, while its paper is being chosen
    var templateAt by remember { mutableStateOf<Int?>(null) }
    // the page being saved as a template, while it is being named
    var savingTemplate by remember { mutableStateOf<Int?>(null) }
    var moreMenu by remember { mutableStateOf(false) }
    var labelsOpen by remember { mutableStateOf(false) }
    val customLabels = remember(doc.info.generation) { io.github.artcraftandroid.pdfcraft.ui.hasCustomLabels(doc.info.pages.map { it.label }) }
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    val columns = if (landscape) 4 else 2
    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = doc.viewer.currentPage)
    // #95: keep the thumbnails of the pages near the ones in view only, not one bitmap (about
    // 465 KB) for every page ever scrolled past; pages scrolled back to render again.
    LaunchedEffect(grid, thumbs) {
        androidx.compose.runtime.snapshotFlow {
            grid.layoutInfo.visibleItemsInfo.let { (it.firstOrNull()?.index ?: 0) to (it.lastOrNull()?.index ?: 0) }
        }.collect { (first, last) ->
            val window = thumbnailWindow(first, last)
            thumbs.keys.filter { it !in window }.forEach { thumbs.remove(it) }
        }
    }
    // dragging a page: its index and where it would drop
    var dragFrom by remember { mutableStateOf<Int?>(null) }
    var dragTo by remember { mutableStateOf<Int?>(null) }
    val exporting = vm.tools.imageExport
    fun close() {
        vm.tools.organizing = false
        vm.docTools.splitOpen = false
        vm.tools.imageExport = null
        selected.clear()
        onClose()
    }
    // the tick leaves organizing for the grid of pages; back from the grid returns to the document
    fun back() {
        if (exporting != null) {
            close()
        } else if (organizing) {
            vm.tools.organizing = false
            selected.clear()
        } else {
            close()
        }
    }
    BackHandler(onBack = ::back)
    // holding and dragging moves pages, which nothing on screen shows: said the first few times (#199)
    LaunchedEffect(Unit) {
        if (exporting == null && doc.info.allowsAssembly && doc.pageCount > 1 && vm.settings.toolPrefs.takeToolHint("pages.drag")) vm.tell(text(R.string.pages_drag_hint))
    }
    // organizing starts with the page being read ticked, as the reference's does
    LaunchedEffect(organizing) { if (organizing && selected.isEmpty()) selected[doc.viewer.currentPage] = true }
    val picked = selected.filterValues { it }.keys.sorted()
    val canDrag = doc.info.allowsAssembly && doc.pageCount > 1
    // black where the camera is (the status bar stays hidden) and the viewer's band behind the
    // navigation buttons, as the reference's Pages has
    Column(
        Modifier.fillMaxSize().background(Color.Black).safeTopPadding()
            .background(c.navBand).navigationBarsPadding().background(c.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().height(TopBarHeight).drawBehind {
                val line = 1.dp.toPx()
                drawRect(c.divider, androidx.compose.ui.geometry.Offset(0f, size.height - line), androidx.compose.ui.geometry.Size(size.width, line))
            }.padding(bottom = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                if (organizing) {
                    BarIcon(LucideIcons.Check, stringResource(if (picked.isEmpty()) R.string.common_done else R.string.common_back), ::back, tint = c.toolAccent)
                } else {
                    BarIcon(LucideIcons.ArrowLeft, stringResource(R.string.common_back), ::back, iconSize = 27.dp)
                }
            }
            Text(
                if (organizing && picked.isNotEmpty()) pluralStringResource(R.plurals.pages_selected_count, picked.size, picked.size) else if (organizing) stringResource(R.string.pages_select) else stringResource(R.string.pages_title),
                color = c.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
            )
            // Select starts organizing in place (#199); organizing, Select all / Select none
            if (!organizing && exporting == null && doc.info.allowsAssembly) {
                androidx.compose.material3.TextButton(onClick = { vm.tools.organizing = true }) {
                    Text(stringResource(R.string.pages_select_action), color = c.toolAccent, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(6.dp))
            }
            if (organizing || exporting != null) {
                val all = picked.size == doc.pageCount
                androidx.compose.material3.TextButton(onClick = {
                    selected.clear()
                    if (!all) for (i in 0 until doc.pageCount) selected[i] = true
                }) { Text(stringResource(if (all) R.string.pages_select_none else R.string.pages_select_all), color = c.toolAccent) }
            }
            if (organizing && exporting == null) {
                UndoMenuButton(doc.info.undo != null, doc.info.redo != null, { vm.undo(doc) }, { vm.redo(doc) }, doc.info.undoLabel, doc.info.redoLabel)
                Spacer(Modifier.width(6.dp))
            }
        }
        // the reference's grid: two 136 x 162 dp cards across (four sideways), 24 dp apart, each
        // holding its page fitted into 100 x 116 dp, with the page number under it
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val side = if (landscape) 54.dp else 24.dp
            val gap = if (landscape) 22.dp else 24.dp
            val cardW = minOf(136.dp, (maxWidth - side * 2 - gap * (columns - 1)) / columns)
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = grid,
                contentPadding = PaddingValues(start = side, end = side, top = 12.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(doc.info.pages, key = { i, _ -> "${doc.info.generation}-$i" }) { i, p ->
                    LaunchedEffect(i, doc.info.generation) {
                        if (thumbs[i] == null) {
                            val eng = vm.engine ?: return@LaunchedEffect
                            val scale = 300f / maxOf(p.width, p.height).coerceAtLeast(1f)
                            val img = runCatching { withContext(eng.thumbnails) { eng.session.renderPage(doc.id, i.toUInt(), scale, null).toBitmap().asImageBitmap() } }.getOrNull()
                            if (img != null) thumbs[i] = img
                        }
                    }
                    val isSel = selected[i] == true
                    val isCurrent = !organizing && i == doc.viewer.currentPage
                    val highlighted = isSel || isCurrent || dragTo == i && dragFrom != i
                    val pageLabel = stringResource(R.string.pages_page, i + 1)
                    Column(
                        Modifier
                            .graphicsLayer { alpha = if (dragFrom == i) 0.4f else 1f }
                            .then(
                                // holding a page and dragging it moves it, in either mode
                                if (canDrag) {
                                    Modifier.pointerInput(i, doc.info.generation) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                dragFrom = i
                                                dragTo = i
                                            },
                                            onDragEnd = {
                                                val from = dragFrom
                                                val to = dragTo
                                                dragFrom = null
                                                dragTo = null
                                                if (from != null && to != null && to != from) {
                                                    // engine positions count before the move: dropping after a later page lands one further on
                                                    val target = if (to > from) to + 2 else to + 1
                                                    vm.runTool(doc, "page_move", mapOf("pages" to listOf(from + 1), "to" to target))
                                                }
                                            },
                                            onDragCancel = {
                                                dragFrom = null
                                                dragTo = null
                                            },
                                        ) { change, _ ->
                                            change.consume()
                                            val pos = change.position
                                            val info = grid.layoutInfo
                                            val me = info.visibleItemsInfo.firstOrNull { it.index == i } ?: return@detectDragGesturesAfterLongPress
                                            val x = me.offset.x + pos.x
                                            val y = me.offset.y + pos.y
                                            info.visibleItemsInfo.firstOrNull { it.offset.x <= x && x <= it.offset.x + it.size.width && it.offset.y <= y && y <= it.offset.y + it.size.height }
                                                ?.let { dragTo = it.index }
                                        }
                                    }
                                } else {
                                    Modifier
                                },
                            )
                            .clickable {
                                if (organizing) {
                                    selected[i] = !isSel
                                } else {
                                    doc.viewer.goToPage(i)
                                    close()
                                }
                            }
                            .semantics { contentDescription = pageLabel },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.size(cardW, 162.dp).clip(RoundedCornerShape(5.dp)).background(c.pageCard)
                                .border(if (highlighted) 2.dp else 1.dp, if (highlighted) c.toolAccent else c.pageCard, RoundedCornerShape(5.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            val ratio = (p.width / p.height.coerceAtLeast(1f)).coerceIn(0.2f, 5f)
                            // fitted into 100 x 116 dp, keeping the page's shape
                            val w = if (ratio > 100f / 116f) 100.dp else 116.dp * ratio
                            Box(Modifier.size(w, w / ratio).background(Color.White)) {
                                thumbs[i]?.let { Image(it, null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                            }
                            if (organizing) {
                                Box(
                                    Modifier.align(Alignment.TopStart).padding(8.dp).size(18.dp).clip(RoundedCornerShape(2.dp))
                                        .background(if (isSel) c.toolAccent else Color.Transparent)
                                        .border(2.dp, if (isSel) c.toolAccent else c.textSecondary, RoundedCornerShape(2.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isSel) Icon(LucideIcons.Check, null, tint = Color.White, modifier = Modifier.size(12.dp))
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(if (customLabels) p.label.ifEmpty { "${i + 1}" } else "${i + 1}", color = c.icon, maxLines = 1, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 17.sp)
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
        val enabled = picked.isNotEmpty()
        val barItems = organizeBarItems(exporting = exporting != null, organizing = organizing)
        OrganizeBar(barItems, organizing, enabled = { item ->
            when (item) {
                OrganizeBarItem.Save, OrganizeBarItem.Share, OrganizeBarItem.RotateLeft, OrganizeBarItem.RotateRight -> enabled
                OrganizeBarItem.Duplicate -> enabled && doc.info.allowsAssembly
                OrganizeBarItem.Delete -> enabled && picked.size < doc.pageCount
                OrganizeBarItem.Split -> doc.pageCount > 1
                else -> true
            }
        }) { item ->
            when (item) {
                // picking the pages to export: save them to the phone's pictures, or share them
                OrganizeBarItem.Save -> exporting?.let { vm.tools.writeImages(doc, it, picked, share = false) }
                OrganizeBarItem.Share -> exporting?.let { vm.tools.writeImages(doc, it, picked, share = true) }
                OrganizeBarItem.Insert -> insertMenu = true
                OrganizeBarItem.Duplicate -> vm.docTools.duplicatePages(doc, picked)
                OrganizeBarItem.Delete -> {
                    // no confirmation step: the deletion says what it did and offers Undo at once (#199)
                    val count = picked.size
                    vm.runTool(doc, "page_delete", mapOf("pages" to picked.map { it + 1 })) {
                        vm.tell(plural(R.plurals.pages_deleted, count, count), text(R.string.common_undo)) { vm.undo(doc) }
                    }
                    selected.clear()
                }
                OrganizeBarItem.RotateLeft -> vm.runTool(doc, "page_rotate", mapOf("pages" to picked.map { it + 1 }, "degrees" to -90))
                OrganizeBarItem.RotateRight -> vm.runTool(doc, "page_rotate", mapOf("pages" to picked.map { it + 1 }, "degrees" to 90))
                OrganizeBarItem.More -> moreMenu = true
                OrganizeBarItem.EditPdf -> {
                    close()
                    vm.enterTool(doc, ViewerTool.Edit)
                }
                OrganizeBarItem.Crop -> {
                    close()
                    vm.runQuickTool(doc, QuickTool.Crop)
                }
                OrganizeBarItem.Split -> vm.docTools.splitOpen = true
            }
        }
    }
    if (moreMenu) {
        PdfSheet(onDismiss = { moreMenu = false }, dim = true) {
            SheetTitle(stringResource(R.string.pages_more))
            SheetRow(LucideIcons.FileOutput, stringResource(R.string.pages_extract), { moreMenu = false; vm.tools.extractPages(doc, picked) }, enabled = picked.isNotEmpty())
            val assemble = doc.info.allowsAssembly
            SheetRow(LucideIcons.Replace, stringResource(R.string.pages_replace_with), { moreMenu = false; vm.docTools.pickReplaceFile(doc, picked) }, enabled = assemble && picked.isNotEmpty())
            SheetRow(LucideIcons.ArrowUpDown, stringResource(R.string.pages_reverse_order), { moreMenu = false; vm.docTools.reversePages(doc, picked) }, enabled = assemble && doc.pageCount > 1)
            SheetRow(LucideIcons.Tag, stringResource(R.string.pages_labels), { moreMenu = false; labelsOpen = true }, enabled = assemble)
            SheetRow(LucideIcons.Scissors, stringResource(R.string.pages_split), { moreMenu = false; vm.docTools.splitOpen = true }, enabled = doc.pageCount > 1)
            Spacer(Modifier.height(12.dp))
        }
    }
    if (labelsOpen) PageLabelsSheet(vm, doc, picked) { labelsOpen = false }
    if (vm.docTools.splitOpen) SplitSheet(vm, doc, picked) { vm.docTools.splitOpen = false }
    vm.docTools.replacePrompt?.takeIf { it.doc === doc }?.let { ReplacePagesDialog(vm, it) }
    if (insertMenu) {
        // where the new pages go, as the reference asks: around the selection, or at either end
        var after by remember { mutableStateOf(true) }
        val first = picked.firstOrNull()
        val last = picked.lastOrNull()
        val at = when {
            first == null || last == null -> if (after) doc.pageCount + 1 else 1
            after -> last + 2
            else -> first + 1
        }
        PdfSheet(onDismiss = { insertMenu = false }, dim = true) {
            SheetTitle(stringResource(R.string.pages_insert))
            val c = pdfColors
            for (option in listOf(false, true)) {
                val label = when {
                    first == null || last == null -> stringResource(if (option) R.string.pages_at_end else R.string.pages_at_start)
                    option -> stringResource(R.string.pages_after_page, last + 1)
                    else -> stringResource(R.string.pages_before_page, first + 1)
                }
                Row(
                    Modifier.fillMaxWidth().selectable(selected = after == option, role = Role.RadioButton) { after = option }.padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.RadioButton(
                        selected = after == option, onClick = null,
                        colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = c.toolAccent, unselectedColor = c.icon),
                    )
                    Text(label, color = c.text, fontSize = 16.sp)
                }
            }
            io.github.artcraftandroid.pdfcraft.ui.SheetDivider()
            SheetRow(LucideIcons.File, stringResource(R.string.pages_blank_page), { insertMenu = false; vm.runTool(doc, "page_insert_blank", mapOf("at" to at)) })
            SheetRow(LucideIcons.BookOpen, stringResource(R.string.notebook_template_page), { insertMenu = false; templateAt = at })
            SheetRow(LucideIcons.FileStack, stringResource(R.string.pages_from_file), { insertMenu = false; vm.pickInsertFile(doc, at) })
            SheetRow(LucideIcons.Camera, stringResource(R.string.pages_from_scan), { insertMenu = false; vm.insertScanAt = doc to at; vm.startScan() })
            if (first != null && first == last) {
                io.github.artcraftandroid.pdfcraft.ui.SheetDivider()
                SheetRow(LucideIcons.BookmarkPlus, stringResource(R.string.notebook_save_as_template, first + 1), { insertMenu = false; savingTemplate = first })
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    templateAt?.let { at ->
        io.github.artcraftandroid.pdfcraft.ui.home.TemplatePageSheet(vm, io.github.artcraftandroid.pdfcraft.engine.NotebookTemplate(), onDismiss = { templateAt = null }) { page ->
            templateAt = null
            vm.study.insertNotebookPage(doc, page, at)
        }
    }
    savingTemplate?.let { index ->
        var name by remember { mutableStateOf("") }
        io.github.artcraftandroid.pdfcraft.ui.PdfDialog(
            onDismiss = { savingTemplate = null }, title = stringResource(R.string.notebook_save_template_title),
            confirm = stringResource(R.string.common_save),
            onConfirm = { savingTemplate = null; vm.study.savePageAsTemplate(doc, index, name.ifBlank { doc.title }) },
        ) { io.github.artcraftandroid.pdfcraft.ui.PdfField(name, { name = it.take(60) }, stringResource(R.string.notebook_name_hint)) }
    }
}

/** The buttons of the Pages screen's bar ([organizeBarItems] picks which show). */
internal enum class OrganizeBarItem(val icon: ImageVector, val label: Int) {
    Save(LucideIcons.Save, R.string.common_save),
    Share(LucideIcons.Share2, R.string.common_share),
    Insert(LucideIcons.FilePlus, R.string.pages_insert_page),
    Duplicate(LucideIcons.CopyPlus, R.string.pages_duplicate),
    Delete(LucideIcons.Trash2, R.string.common_delete),
    RotateLeft(LucideIcons.RotateCcw, R.string.pages_rotate_left),
    RotateRight(LucideIcons.RotateCw, R.string.pages_rotate_right),
    More(LucideIcons.Ellipsis, R.string.pages_more),
    EditPdf(LucideIcons.FilePenLine, R.string.common_tool_edit_pdf),
    Crop(LucideIcons.Crop, R.string.common_tool_crop),
    Split(LucideIcons.Scissors, R.string.pages_split_short),
}

/** The bar while exporting pages as pictures, while organizing, or else (Edit PDF, Crop, Split). */
internal fun organizeBarItems(exporting: Boolean, organizing: Boolean): List<OrganizeBarItem> = when {
    exporting -> listOf(OrganizeBarItem.Save, OrganizeBarItem.Share)
    organizing -> listOf(
        OrganizeBarItem.Insert, OrganizeBarItem.Duplicate, OrganizeBarItem.Delete,
        OrganizeBarItem.RotateLeft, OrganizeBarItem.RotateRight, OrganizeBarItem.More,
    )
    else -> listOf(OrganizeBarItem.EditPdf, OrganizeBarItem.Crop, OrganizeBarItem.Split)
}

/**
 * The Pages screen's bar: its buttons share the width (#538), each name on up to two lines. Before
 * #538 each was as wide as its one-line name; in German or French the six of Organize came to more
 * than a 360 dp screen and More, the last, was left with no width.
 */
@Composable
internal fun OrganizeBar(
    items: List<OrganizeBarItem>,
    organizing: Boolean,
    enabled: (OrganizeBarItem) -> Boolean = { true },
    onItem: (OrganizeBarItem) -> Unit,
) {
    Row(
        // at least the bar's height, taller when a large font setting enlarges the labels (sp, as the reference's)
        Modifier.fillMaxWidth().heightIn(min = BottomBarHeight).barTopLine().background(pdfColors.surface).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(if (organizing) 0.dp else 20.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (item in items) OrganizeItem(item.icon, stringResource(item.label), enabled(item), barShare(max = 88.dp)) { onItem(item) }
    }
}

@Composable
private fun OrganizeItem(icon: ImageVector, label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = pdfColors
    val tint = if (enabled) c.icon else c.icon.copy(alpha = 0.35f)
    // the reference's items: a 40 dp icon box over an 11 sp label; the name wraps onto a second line when long
    Column(
        modifier.heightIn(min = BottomBarHeight).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }.padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp)) }
        io.github.artcraftandroid.pdfcraft.ui.WordWrapLabel(label, if (enabled) c.label else c.label.copy(alpha = 0.35f), size = 10.5.sp, lineHeight = 13.sp)
    }
}

/** Thumbnails kept on each side of the pages in view. */
internal const val THUMBNAIL_MARGIN = 24

/** The pages whose thumbnails the Pages grid keeps while pages [first]..[last] are in view (#95). */
internal fun thumbnailWindow(first: Int, last: Int): IntRange =
    (minOf(first, last) - THUMBNAIL_MARGIN).coerceAtLeast(0)..(maxOf(first, last) + THUMBNAIL_MARGIN)
