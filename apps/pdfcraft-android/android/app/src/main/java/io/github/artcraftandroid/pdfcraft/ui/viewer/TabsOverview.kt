package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.engine.toBitmap
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.BarIcon
import io.github.artcraftandroid.pdfcraft.ui.DocTab
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.PdfBadge
import io.github.artcraftandroid.pdfcraft.ui.PdfDialog
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import io.github.artcraftandroid.pdfcraft.ui.safeTopPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The layer of every open document, and one of its cards, for layout tests (#669). */
const val TABS_OVERVIEW_TAG = "tabs_overview"
const val TAB_CARD_TAG = "tab_card"

/** The longer side of a card's page, in pixels: sharp on a card about 170 dp wide, small to draw. */
internal const val TAB_THUMB_SIDE = 360

/** A card is at least this wide: two columns on a 384 dp phone, four or five on a tablet. */
internal val TabCardMinWidth = 150.dp

/**
 * How many cards fit a row of a window [widthDp] wide: cards [TabCardMinWidth] wide at least, 12 dp
 * between them and at the sides, as the grid lays them out. Never less than one.
 */
internal fun tabOverviewColumns(widthDp: Int): Int = ((widthDp - 12) / (TabCardMinWidth.value.toInt() + 12)).coerceAtLeast(1)

/**
 * Every open document as a card (#669), as a browser shows its tabs: the page each is on, its
 * name and "Page n of m", the one in front marked. A tap brings a document to the front and the
 * layer goes; ✕ closes one (saved first, as on the strip); Back closes the layer. Over Home or
 * the viewer.
 */
@Composable
fun TabsOverview(vm: AppViewModel) {
    val c = pdfColors
    val tabs = vm.tabs.tabs
    val front = vm.document
    androidx.activity.compose.BackHandler { vm.tabs.closeOverview() }
    // the last document was closed from here: nothing is left to choose
    LaunchedEffect(tabs.isEmpty()) { if (tabs.isEmpty()) vm.tabs.closeOverview() }
    // a document that was not among these came to the front (another app's Open with): it shows, not these cards
    val shownFor = remember { tabs.map { it.key }.toSet() }
    LaunchedEffect(front) {
        val now = vm.tabs.active
        if (now != null && now.key !in shownFor) vm.tabs.closeOverview()
    }
    var asking by remember { mutableStateOf(false) }
    // what Close others (or, from Home, Close all) would close
    val others = tabs.count { it.doc == null || it.doc !== front }
    val closeMany = stringResource(if (front != null) R.string.tabs_close_others else R.string.tabs_close_all)
    Column(
        Modifier.fillMaxSize().background(c.desk)
            // a tap between the cards or on the title stays here: Home or the viewer underneath gets none
            .pointerInput(Unit) { detectTapGestures { } }
            .safeTopPadding().navigationBarsPadding().testTag(TABS_OVERVIEW_TAG),
    ) {
        Row(Modifier.fillMaxWidth().height(56.dp).background(c.surface), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                BarIcon(LucideIcons.ArrowLeft, stringResource(R.string.common_back), { vm.tabs.closeOverview() })
            }
            Text(
                pluralStringResource(R.plurals.tabs_open_count, tabs.size, tabs.size),
                color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (others > 0) {
                TextButton(onClick = { vm.tabs.askedCloseAll(); asking = true }) {
                    Text(
                        closeMany,
                        color = c.toolAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
                    )
                }
            }
            BarIcon(LucideIcons.FolderOpen, stringResource(R.string.tabs_open_file), {
                vm.tabs.closeOverview()
                vm.tabs.openFile("overview")
            })
            BarIcon(LucideIcons.Plus, stringResource(R.string.tabs_open_another), {
                vm.tabs.closeOverview()
                // from the viewer this goes Home and keeps the document in its tab; Home is already there
                if (vm.document != null) vm.tabs.openAnother()
            })
            Spacer(Modifier.width(8.dp))
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(TabCardMinWidth),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // the place is part of the key: a grid must never hold two equal keys, whatever the tabs' own are
            items(tabs.size, key = { "$it:${tabs.getOrNull(it)?.key}" }) { i ->
                tabs.getOrNull(i)?.let { tab -> TabCard(vm, tab, active = tab.doc != null && tab.doc === front) }
            }
        }
    }
    if (asking) {
        // how many, and what happens to them: nothing closes until the person says so (#636)
        PdfDialog(
            onDismiss = { asking = false },
            title = pluralStringResource(R.plurals.tabs_close_all_title, others, others),
            confirm = closeMany,
            onConfirm = { asking = false; vm.tabs.closeAll() },
        ) { Text(stringResource(R.string.tabs_close_all_body), color = c.text, fontSize = 14.sp) }
    }
}

@Composable
private fun TabCard(vm: AppViewModel, tab: DocTab, active: Boolean) {
    val c = pdfColors
    val shape = RoundedCornerShape(12.dp)
    val doc = tab.doc
    val label = if (tab.parked) stringResource(R.string.tabs_parked, tab.title) else tab.title
    Column(
        Modifier.fillMaxWidth()
            .testTag(TAB_CARD_TAG)
            .clip(shape)
            .background(c.surface)
            .border(if (active) 2.dp else 1.dp, if (active) c.toolAccent else c.divider, shape)
            .semantics { selected = active }
            .clickable(role = Role.Tab, onClickLabel = label) { vm.tabs.chooseFromOverview(tab) },
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                tab.title,
                modifier = Modifier.weight(1f).semantics { contentDescription = label },
                color = if (active) c.toolAccent else c.text, fontSize = 13.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            BarIcon(LucideIcons.X, stringResource(R.string.tabs_close, tab.title), { vm.tabs.close(tab) }, size = 40.dp, iconSize = 16.dp)
        }
        TabThumb(vm, tab, Modifier.fillMaxWidth().aspectRatio(0.8f))
        Text(
            if (doc != null) stringResource(R.string.viewer_page_of, doc.viewer.currentPage.coerceIn(0, (doc.pageCount - 1).coerceAtLeast(0)) + 1, doc.pageCount)
            else stringResource(R.string.tabs_overview_parked),
            color = c.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

/**
 * A card's picture: the page an open document is on, drawn by the engine on its thumbnail queue;
 * the first page of one closed to save memory, from the file list's kept thumbnails (none for a
 * very large file, which is not read just for this); the PDF badge until it is drawn or when it
 * cannot be.
 */
@Composable
private fun TabThumb(vm: AppViewModel, tab: DocTab, modifier: Modifier) {
    val c = pdfColors
    val doc = tab.doc
    var img by remember(tab.key, doc) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(tab.key, doc, doc?.info?.generation) {
        img = if (doc != null) {
            val eng = vm.engine ?: return@LaunchedEffect
            val page = doc.viewer.currentPage.coerceIn(0, (doc.pageCount - 1).coerceAtLeast(0))
            val size = doc.info.pages.getOrNull(page) ?: return@LaunchedEffect
            val scale = TAB_THUMB_SIDE / maxOf(size.width, size.height).coerceAtLeast(1f)
            runCatching { withContext(eng.thumbnails) { eng.session.renderPage(doc.id, page.toUInt(), scale, null).toBitmap().asImageBitmap() } }.getOrNull()
        } else if (tab.key.startsWith("content:") || tab.key.startsWith("file:")) {
            runCatching {
                val path = withContext(Dispatchers.IO) { vm.home.thumbPathFor(tab.key) }
                path?.let { vm.home.thumbnails.load(it, TAB_THUMB_SIDE) }?.asImageBitmap()
            }.getOrNull()
        } else {
            null
        }
    }
    Box(modifier.background(if (img != null) Color.White else c.surfaceRaised), contentAlignment = Alignment.Center) {
        val shown = img
        if (shown != null) Image(shown, null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
        else PdfBadge(44)
    }
}

/**
 * The button that opens the overview: the number of open documents in a small frame, as a browser
 * draws its tab count. [label] names it for TalkBack.
 */
@Composable
internal fun TabCountButton(count: Int, label: String, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 46.dp, onClick: () -> Unit) {
    val c = pdfColors
    Box(
        modifier.size(size).clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(24.dp).border(1.6.dp, c.icon, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
            Text(tabCountText(count), color = c.icon, fontSize = if (count > 9) 10.sp else 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
        }
    }
}

/** The number in the count button: as it is up to 99, then "99+" (the frame holds no more). */
internal fun tabCountText(count: Int): String = if (count > 99) "99+" else count.coerceAtLeast(0).toString()
