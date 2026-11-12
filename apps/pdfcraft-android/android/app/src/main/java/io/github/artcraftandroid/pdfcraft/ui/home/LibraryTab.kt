package io.github.artcraftandroid.pdfcraft.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.engine.HitKind
import io.github.artcraftandroid.pdfcraft.engine.LibraryItem
import io.github.artcraftandroid.pdfcraft.engine.SearchHit
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.BarIcon
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.PdfDialog
import io.github.artcraftandroid.pdfcraft.ui.PdfField
import io.github.artcraftandroid.pdfcraft.ui.PdfSheet
import io.github.artcraftandroid.pdfcraft.ui.PdfSwitch
import io.github.artcraftandroid.pdfcraft.ui.SheetDivider
import io.github.artcraftandroid.pdfcraft.ui.SheetRow
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import io.github.artcraftandroid.pdfcraft.ui.safeTopPadding
import kotlinx.coroutines.delay

/** The colour labels a library file can carry. */
val LABEL_COLORS = listOf(0xE53935, 0xFB8C00, 0xFDD835, 0x43A047, 0x1E88E5, 0x8E24AA, 0x757575)

/** A smart collection: files chosen by a rule rather than by hand. */
private sealed interface Smart {
    data object Unresolved : Smart
    data object ThisWeek : Smart
    data class Tagged(val tag: String) : Smart
}

/**
 * The library: virtual collections (nested, a file can be in several, nothing is moved or
 * copied), tags, colour labels and smart collections, and search across names and, once
 * indexing is on, the text, bookmarks and comments inside the documents.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryTab(vm: AppViewModel) {
    val c = pdfColors
    val data = vm.study.libraryData
    var current by remember { mutableStateOf<String?>(null) }
    var smart by remember { mutableStateOf<Smart?>(null) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchHit>?>(null) }
    var menu by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf<Pair<String?, String>?>(null) }
    var collectionMenu by remember { mutableStateOf<String?>(null) }
    var itemSheet by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.study.refreshLibrary(); vm.study.indexLibrary() }
    LaunchedEffect(query, vm.study.indexingName) {
        if (query.trim().length < 2) { results = null; return@LaunchedEffect }
        delay(250)
        results = vm.study.searchLibrary(query, current?.let { id -> data.itemsIn(id).map { it.uri } })
    }
    if (current != null && data.collection(current!!) == null) current = null
    BackHandler(enabled = current != null || smart != null) {
        if (smart != null) smart = null else current = data.collection(current!!)?.parent
    }
    Column(Modifier.fillMaxSize().safeTopPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (current != null || smart != null) {
                BarIcon(LucideIcons.ArrowLeft, stringResource(R.string.common_back), { if (smart != null) smart = null else current = data.collection(current!!)?.parent })
                Spacer(Modifier.width(4.dp))
            }
            val title = when (val s = smart) {
                Smart.Unresolved -> stringResource(R.string.library_smart_unresolved)
                Smart.ThisWeek -> stringResource(R.string.library_smart_week)
                is Smart.Tagged -> "#" + s.tag
                null -> current?.let { data.collection(it)?.name } ?: stringResource(R.string.library_title)
            }
            Text(title, color = c.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (smart == null) Box {
                BarIcon(LucideIcons.Plus, stringResource(R.string.library_add), { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.surface) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.library_new_collection)) }, onClick = { menu = false; naming = null to "" })
                    DropdownMenuItem(text = { Text(stringResource(R.string.library_add_files)) }, onClick = { menu = false; vm.study.pickLibraryFiles(current) })
                }
            }
        }
        PdfField(query, { query = it.take(100) }, stringResource(R.string.library_search_hint), Modifier.padding(horizontal = 16.dp))
        vm.study.indexingName?.let {
            Text(stringResource(R.string.library_indexing, it.removeSuffix(".pdf")), color = c.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val shown = results
        LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
            if (shown != null) {
                if (shown.isEmpty()) item {
                    EmptyState(LucideIcons.Search, stringResource(R.string.library_no_results), stringResource(if (vm.study.libraryIndexing) R.string.library_no_results_body else R.string.library_no_results_names_only))
                }
                items(shown, key = { "${it.uri}|${it.page}|${it.kind}|${it.start}|${it.snippet.hashCode()}" }) { hit -> HitRow(vm, hit, query) }
                return@LazyColumn
            }
            if (current == null && smart == null) {
                item { IndexSwitch(vm) }
                item {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmartChip(stringResource(R.string.library_smart_unresolved)) { smart = Smart.Unresolved }
                        SmartChip(stringResource(R.string.library_smart_week)) { smart = Smart.ThisWeek }
                        for (t in data.allTags()) SmartChip("#$t") { smart = Smart.Tagged(t) }
                    }
                }
            }
            val files: List<LibraryItem> = when (val s = smart) {
                Smart.Unresolved -> data.items.filter { it.uri in vm.study.libraryUnresolved }
                Smart.ThisWeek -> {
                    val since = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
                    val opened = vm.recentList.filter { it.opened >= since }.map { it.uri }.toSet()
                    data.items.filter { it.uri in opened || it.added >= since }
                }
                is Smart.Tagged -> data.items.filter { s.tag in it.tags }
                null -> if (current == null) data.items.filter { it.collections.isEmpty() } else data.items.filter { current in it.collections }
            }.sortedBy { it.name.lowercase() }
            if (smart == null) {
                items(data.children(current), key = { "c" + it.id }) { col ->
                    val count = data.itemsIn(col.id).size
                    Row(
                        Modifier.fillMaxWidth().combinedClickable(onClick = { current = col.id }, onLongClick = { collectionMenu = col.id })
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(LucideIcons.Folder, null, tint = c.icon, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(col.name, color = c.text, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(count.toString(), color = c.textSecondary, fontSize = 13.sp)
                        BarIcon(LucideIcons.EllipsisVertical, stringResource(R.string.common_more_options), { collectionMenu = col.id }, size = 40.dp)
                    }
                }
            }
            items(files, key = { "f" + it.uri }) { item -> ItemRow(vm, item, onMenu = { itemSheet = item.uri }) }
            if (files.isEmpty() && (smart != null || data.children(current).isEmpty())) item {
                if (data.items.isEmpty() && current == null && smart == null) {
                    EmptyState(LucideIcons.Layers, stringResource(R.string.library_empty_title), stringResource(R.string.library_empty_body), stringResource(R.string.library_add_files)) { vm.study.pickLibraryFiles(null) }
                } else {
                    EmptyState(LucideIcons.Layers, stringResource(R.string.library_collection_empty), stringResource(R.string.library_collection_empty_body))
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    naming?.let { (id, initial) ->
        var name by remember(id) { mutableStateOf(initial) }
        PdfDialog(
            onDismiss = { naming = null },
            title = stringResource(if (id == null) R.string.library_new_collection else R.string.common_rename),
            confirm = stringResource(if (id == null) R.string.notebook_create else R.string.common_save),
            confirmEnabled = name.isNotBlank(),
            onConfirm = { naming = null; if (id == null) vm.study.addCollection(name, current) else vm.study.renameCollection(id, name) },
        ) { PdfField(name, { name = it.take(80) }, stringResource(R.string.notebook_name_hint)) }
    }
    collectionMenu?.let { id ->
        var confirmDelete by remember { mutableStateOf(false) }
        PdfSheet(onDismiss = { collectionMenu = null }, dim = true) {
            SheetRow(LucideIcons.Pencil, stringResource(R.string.common_rename), { collectionMenu = null; naming = id to (data.collection(id)?.name ?: "") })
            SheetRow(LucideIcons.Trash2, stringResource(R.string.library_delete_collection), { confirmDelete = true }, tint = c.red)
            Spacer(Modifier.height(12.dp))
        }
        if (confirmDelete) PdfDialog(
            onDismiss = { confirmDelete = false }, title = stringResource(R.string.library_delete_collection),
            confirm = stringResource(R.string.common_delete), destructive = true,
            onConfirm = { confirmDelete = false; collectionMenu = null; vm.study.deleteCollection(id) },
        ) { Text(stringResource(R.string.library_delete_collection_body), color = c.text, fontSize = 14.sp) }
    }
    itemSheet?.let { uri -> data.item(uri)?.let { LibraryItemSheet(vm, it) { itemSheet = null } } ?: run { itemSheet = null } }
}

@Composable
private fun IndexSwitch(vm: AppViewModel) {
    val c = pdfColors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Switch) { vm.study.updateLibraryIndexing(!vm.study.libraryIndexing) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.library_index_switch), color = c.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            PdfSwitch(vm.study.libraryIndexing, null)
        }
        Text(stringResource(R.string.library_index_body), color = c.textSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun SmartChip(label: String, onClick: () -> Unit) {
    val c = pdfColors
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier.heightIn(min = 36.dp).clip(shape).border(1.dp, c.textSecondary, shape).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = c.label, fontSize = 14.sp, maxLines = 1) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemRow(vm: AppViewModel, item: LibraryItem, onMenu: () -> Unit) {
    val c = pdfColors
    val unavailable = item.uri in vm.study.libraryUnavailable
    val unavailableText = stringResource(R.string.library_unavailable)
    Row(
        Modifier.fillMaxWidth().combinedClickable(
            onClick = { if (unavailable) vm.tell(unavailableText) else vm.open(android.net.Uri.parse(item.uri)) },
            onLongClick = onMenu,
        ).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(item.color?.let { Color(0xFF000000 or it.toLong()) } ?: Color.Transparent))
        Spacer(Modifier.width(10.dp))
        Icon(LucideIcons.FileText, null, tint = if (unavailable) c.textSecondary else c.icon, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name.removeSuffix(".pdf"), color = if (unavailable) c.textSecondary else c.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = buildList {
                if (unavailable) add(unavailableText)
                addAll(item.tags.sorted().map { "#$it" })
            }.joinToString("  ")
            if (line.isNotEmpty()) Text(line, color = if (unavailable) c.red else c.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        BarIcon(LucideIcons.EllipsisVertical, stringResource(R.string.common_more_options), onMenu, size = 40.dp)
    }
}

@Composable
private fun HitRow(vm: AppViewModel, hit: SearchHit, query: String) {
    val c = pdfColors
    val where = when (hit.kind) {
        HitKind.Name -> stringResource(R.string.library_hit_name)
        HitKind.Text -> stringResource(R.string.library_hit_page, hit.page + 1)
        HitKind.Comment -> stringResource(R.string.library_hit_comment, hit.page + 1)
        HitKind.Outline -> stringResource(R.string.library_hit_bookmark, hit.page + 1)
    }
    Column(Modifier.fillMaxWidth().clickable { vm.study.openHit(hit, query) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(hit.name.removeSuffix(".pdf"), color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(where, color = c.accent, fontSize = 12.sp)
        }
        if (hit.kind != HitKind.Name) {
            val s = hit.snippet
            val start = hit.start.coerceIn(0, s.length)
            val end = (hit.start + hit.length).coerceIn(start, s.length)
            Text(buildAnnotatedString {
                append(s.substring(0, start))
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold, background = Color(0x66FFEB3B)))
                append(s.substring(start, end))
                pop()
                append(s.substring(end))
            }, color = c.textSecondary, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A library file's collections, tags and colour label. */
@Composable
private fun LibraryItemSheet(vm: AppViewModel, item: LibraryItem, onDismiss: () -> Unit) {
    val c = pdfColors
    val data = vm.study.libraryData
    var tags by remember(item.uri) { mutableStateOf(item.tags.sorted().joinToString(", ")) }
    PdfSheet(onDismiss = { vm.study.setItemTags(item.uri, tags.split(',', '،').toSet()); onDismiss() }, dim = true, maxHeightFraction = 0.85f) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text(item.name.removeSuffix(".pdf"), color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(16.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.library_label), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val none = stringResource(R.string.library_no_label)
                Box(
                    Modifier.size(36.dp).clip(CircleShape).border(2.dp, if (item.color == null) c.accent else c.divider, CircleShape)
                        .clickable(role = Role.RadioButton) { vm.study.setItemColor(item.uri, null) }.semantics { contentDescription = none },
                    contentAlignment = Alignment.Center,
                ) { Icon(LucideIcons.X, null, tint = c.icon, modifier = Modifier.size(16.dp)) }
                for (col in LABEL_COLORS) {
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF000000 or col.toLong()))
                            .border(3.dp, if (item.color == col) c.text else Color.Transparent, CircleShape)
                            .clickable(role = Role.RadioButton) { vm.study.setItemColor(item.uri, col) }
                            .semantics { contentDescription = "#%06X".format(col) },
                    )
                }
            }
            Text(stringResource(R.string.library_tags), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 6.dp))
            PdfField(tags, { tags = it.take(300) }, stringResource(R.string.library_tags_hint), Modifier.padding(horizontal = 16.dp))
            if (data.collections.isNotEmpty()) {
                Text(stringResource(R.string.library_collections), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
                for (col in data.collections.sortedBy { data.path(it.id).joinToString("/") { p -> p.name.lowercase() } }) {
                    val inIt = col.id in item.collections
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { vm.study.setItemCollections(item.uri, if (inIt) item.collections - col.id else item.collections + col.id) }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(checked = inIt, onCheckedChange = { vm.study.setItemCollections(item.uri, if (inIt) item.collections - col.id else item.collections + col.id) })
                        Text(data.path(col.id).joinToString(" / ") { it.name }, color = c.text, fontSize = 15.sp)
                    }
                }
            }
            SheetDivider()
            SheetRow(LucideIcons.X, stringResource(R.string.library_remove), { vm.study.removeFromLibrary(item.uri); onDismiss() }, tint = c.red)
        }
    }
}
