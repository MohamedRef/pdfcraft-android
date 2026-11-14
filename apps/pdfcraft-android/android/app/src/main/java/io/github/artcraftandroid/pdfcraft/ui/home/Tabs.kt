package io.github.artcraftandroid.pdfcraft.ui.home

import io.github.artcraftandroid.pdfcraft.ui.safeTopPadding
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.BarIcon
import io.github.artcraftandroid.pdfcraft.ui.Inter
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.PdfSwitch
import io.github.artcraftandroid.pdfcraft.ui.SheetDivider
import io.github.artcraftandroid.pdfcraft.ui.SheetRow
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import io.github.artcraftandroid.pdfcraft.ui.viewer.QuickTool
import io.github.artcraftandroid.pdfcraft.ui.viewer.ToolCategory
import io.github.artcraftandroid.pdfcraft.ui.viewer.startToolFromHome

/** Files: the places files come from; On this device lists every PDF on the phone by folder. */
@Composable
fun FilesTab(vm: AppViewModel, onBrowse: () -> Unit) {
    val c = pdfColors
    if (vm.home.filesShowLocal) {
        OnThisDevice(vm)
        return
    }
    Column(Modifier.fillMaxSize().safeTopPadding()) {
        // a large title, as the expanded bar of the reference shows it
        Box(Modifier.fillMaxWidth().height(128.dp), contentAlignment = Alignment.BottomStart) {
            Text(stringResource(R.string.home_tab_files), color = c.label, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, bottom = 40.dp))
        }
        LocationRow(LucideIcons.Smartphone, stringResource(R.string.home_on_this_device)) { vm.home.filesShowLocal = true }
        LocationRow(LucideIcons.Image, stringResource(R.string.home_photos)) { vm.home.pickPhotosToPdf() }
        LocationRow(LucideIcons.FolderOpen, stringResource(R.string.home_look_in_other_folders), onBrowse)
    }
}

@Composable
private fun LocationRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = pdfColors
    Row(Modifier.fillMaxWidth().heightIn(min = 62.dp).clickable(onClick = onClick).padding(start = 16.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = c.icon, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, color = c.text, fontSize = 17.sp, modifier = Modifier.weight(1f))
    }
}

/** On this device: every PDF found on the phone, newest first, under its folder's name. */
@Composable
private fun OnThisDevice(vm: AppViewModel) {
    val c = pdfColors
    BackHandler { vm.home.filesShowLocal = false }
    LaunchedEffect(Unit) { vm.home.scanLocal() }
    var sort by remember { mutableStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().safeTopPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) { BarIcon(LucideIcons.ArrowLeft, stringResource(R.string.common_back), { vm.home.filesShowLocal = false }) }
            Text(stringResource(R.string.home_on_this_device), color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            BarIcon(LucideIcons.RotateCw, stringResource(R.string.home_refresh), { vm.home.scanLocal(force = true) })
            Box {
                BarIcon(LucideIcons.EllipsisVertical, stringResource(R.string.common_more_options), { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.surface) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.home_sort_by_name)) }, onClick = { sort = 1; menu = false }, trailingIcon = { if (sort == 1) Icon(LucideIcons.Check, null, tint = c.accent) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.home_sort_by_date)) }, onClick = { sort = 0; menu = false }, trailingIcon = { if (sort == 0) Icon(LucideIcons.Check, null, tint = c.accent) })
                    HorizontalDivider(color = c.divider)
                    DropdownMenuItem(text = { Text(stringResource(R.string.home_add_folder)) }, onClick = { menu = false; vm.home.requestFileAccess() }, leadingIcon = { Icon(LucideIcons.FolderPlus, null, tint = c.icon) })
                    // the folders listed here, each with a way to stop listing it
                    for ((tree, name) in vm.home.localFolders()) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.home_stop_listing, name)) }, onClick = { menu = false; vm.home.removeLocalFolder(tree) }, leadingIcon = { Icon(LucideIcons.Folder, null, tint = c.icon) })
                    }
                }
            }
        }
        val rows = vm.home.localRows().let { if (sort == 1) it.sortedBy { r -> r.name.lowercase() } else it }
        // what PdfCraft saved itself shows before any folder is added
        if (!vm.home.hasFileAccess && rows.isEmpty()) {
            EmptyState(LucideIcons.Smartphone, stringResource(R.string.home_device_files_title), stringResource(R.string.home_device_files_body_long), stringResource(R.string.home_add_folder)) { vm.home.requestFileAccess() }
            return@Column
        }
        if (rows.isEmpty()) {
            if (vm.home.scanning) EmptyState(LucideIcons.FileText, stringResource(R.string.home_looking_for_pdfs), "")
            else EmptyState(LucideIcons.FileText, stringResource(R.string.home_no_pdfs_title), stringResource(R.string.home_no_pdfs_body), stringResource(R.string.home_add_folder)) { vm.home.requestFileAccess() }
            return@Column
        }
        LazyColumn(Modifier.weight(1f)) {
            val groups = rows.groupBy { it.location }
            for ((folder, list) in groups) {
                item(key = "h-$folder") {
                    val internalStorage = stringResource(R.string.home_internal_storage)
                    Text(folder.uppercase().ifEmpty { internalStorage }, color = c.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
                }
                items(list, key = { it.key }) { r -> FileListRow(vm, r) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/**
 * Tools: every tool of the app from the one list the viewer uses too (#539), with a search box,
 * by category (All, or one), as three small tiles across; All is ordered by category, then name.
 */
@Composable
fun ToolsTab(vm: AppViewModel) {
    val c = pdfColors
    var cat by remember { mutableStateOf<ToolCategory?>(null) }
    var query by remember { mutableStateOf("") }
    val names = QuickTool.entries.associateWith { stringResource(it.title) }
    val categoryNames = ToolCategory.entries.associateWith { stringResource(it.label) }
    val shown = QuickTool.listed(cat, query, { names.getValue(it) }, { categoryNames.getValue(it) })
    val searchHint = stringResource(R.string.home_search_tools)
    Column(Modifier.fillMaxSize().safeTopPadding()) {
        Text(stringResource(R.string.home_tab_tools), color = c.label, fontSize = 27.sp, lineHeight = 35.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp).clip(CircleShape).background(c.surfaceRaised).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(LucideIcons.Search, null, tint = c.textSecondary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = query, onValueChange = { query = it.take(60) }, singleLine = true,
                textStyle = TextStyle(fontFamily = Inter, fontSize = 16.sp, color = c.text), cursorBrush = SolidColor(c.accent),
                modifier = Modifier.weight(1f).semantics { contentDescription = searchHint },
                decorationBox = { inner -> Box { if (query.isEmpty()) Text(searchHint, color = c.textSecondary, fontSize = 16.sp); inner() } },
            )
            if (query.isNotEmpty()) BarIcon(LucideIcons.X, stringResource(R.string.common_clear), { query = "" }, size = 32.dp)
        }
        // text tabs with a 2 dp underline under the chosen one
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            for (k in listOf<ToolCategory?>(null) + ToolCategory.entries) {
                Column(Modifier.clickable(role = Role.Tab) { cat = k }.width(androidx.compose.foundation.layout.IntrinsicSize.Max)) {
                    Text(k?.let { categoryNames.getValue(it) } ?: stringResource(R.string.home_category_all), color = if (k == cat) c.label else c.textTertiary, fontSize = 17.sp, lineHeight = 25.sp, fontWeight = if (k == cat) FontWeight.SemiBold else FontWeight.Normal)
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (k == cat) c.label else Color.Transparent))
                }
            }
        }
        if (shown.isEmpty()) {
            EmptyState(LucideIcons.Search, stringResource(R.string.home_no_tools), stringResource(R.string.home_no_tools_body))
            return@Column
        }
        LazyVerticalGrid(
            GridCells.Adaptive(80.dp), modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // All: a heading over each category's tools
            val grouped = shown.groupBy { it.category }
            for ((category, list) in grouped) {
                if (cat == null) item(key = "h-${category.key}", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Text(categoryNames.getValue(category), color = c.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 0.dp))
                }
                items(list, key = { it.id }) { t -> ToolCell(t, names.getValue(t)) { vm.startToolFromHome(t) } }
            }
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(16.dp)) }
        }
    }
}

/** One tool in Tools: its tinted tile over its name, the whole cell one button. */
@Composable
private fun ToolCell(t: QuickTool, name: String, onClick: () -> Unit) {
    val c = pdfColors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onClick).padding(vertical = 6.dp, horizontal = 2.dp).semantics { contentDescription = name },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(5.dp)).background(tint(t.hue, c.dark)), contentAlignment = Alignment.Center) {
            Icon(t.icon, null, tint = t.hue, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(name, color = c.label, fontSize = 12.sp, lineHeight = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Search: file names on the phone and in Recent, with the reference's sort choices. */
@Composable
fun SearchTab(vm: AppViewModel) {
    val c = pdfColors
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.home.scanLocal() }
    val searchHint = stringResource(R.string.home_search_your_files)
    // the orders results can be sorted in, by [sort]
    val sorts = listOf(R.string.home_sort_relevant, R.string.home_sort_name_az, R.string.home_sort_name_za, R.string.home_sort_oldest, R.string.home_sort_newest)
    Column(Modifier.fillMaxSize().safeTopPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clip(CircleShape).background(c.surfaceRaised).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(LucideIcons.Search, null, tint = c.textSecondary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { vm.settings.addRecentSearch(query) }),
                textStyle = TextStyle(fontFamily = Inter, fontSize = 16.sp, color = c.text), cursorBrush = SolidColor(c.accent),
                modifier = Modifier.weight(1f).semantics { contentDescription = searchHint },
                decorationBox = { inner -> Box { if (query.isEmpty()) Text(searchHint, color = c.textSecondary, fontSize = 16.sp); inner() } },
            )
            if (query.isNotEmpty()) BarIcon(LucideIcons.X, stringResource(R.string.common_clear), { query = "" }, size = 32.dp)
        }
        if (query.isBlank()) {
            val recent = vm.settings.recentSearches
            if (recent.isEmpty()) {
                EmptyState(LucideIcons.Search, searchHint, stringResource(R.string.home_search_empty_body))
                return@Column
            }
            // the last searches, to run again with a tap
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.home_recent_searches), color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.common_clear), color = c.toolAccent, fontSize = 15.sp, modifier = Modifier.clickable { vm.settings.clearRecentSearches() }.padding(12.dp))
            }
            LazyColumn(Modifier.weight(1f)) {
                items(recent, key = { it }) { q ->
                    Row(Modifier.fillMaxWidth().height(52.dp).clickable { query = q }.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(LucideIcons.Clock, null, tint = c.icon, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(16.dp))
                        Text(q, color = c.text, fontSize = 16.sp, maxLines = 1, modifier = Modifier.weight(1f))
                        BarIcon(LucideIcons.X, stringResource(R.string.home_remove_search, q), { vm.settings.removeRecentSearch(q) }, size = 40.dp)
                    }
                }
            }
            return@Column
        }
        // a search left in place for a moment counts as one made
        LaunchedEffect(query) {
            kotlinx.coroutines.delay(2000)
            if (vm.home.searchFiles(query).isNotEmpty()) vm.settings.addRecentSearch(query)
        }
        val results = vm.home.searchFiles(query).let { list ->
            when (sort) {
                1 -> list.sortedBy { it.name.lowercase() }
                2 -> list.sortedByDescending { it.name.lowercase() }
                3 -> list.sortedBy { it.date }
                4 -> list.sortedByDescending { it.date }
                // most relevant: names starting with the search first, then the newest
                else -> list.sortedWith(compareBy<FileRow> { !it.name.lowercase().startsWith(query.trim().lowercase()) }.thenByDescending { it.date })
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(pluralStringResource(R.plurals.home_search_results, results.size, results.size), color = c.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Box {
                Row(Modifier.clickable { menu = true }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(sorts[sort]), color = c.label, fontSize = 15.sp)
                    Spacer(Modifier.width(4.dp))
                    Icon(LucideIcons.ArrowUpDown, null, tint = c.icon, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.surface) {
                    sorts.forEachIndexed { i, s ->
                        DropdownMenuItem(text = { Text(stringResource(s)) }, onClick = { sort = i; menu = false }, trailingIcon = { if (sort == i) Icon(LucideIcons.Check, null, tint = c.accent) })
                    }
                }
            }
        }
        if (results.isEmpty()) {
            // nothing by name: the same words, inside the documents (#539)
            EmptyState(LucideIcons.FileSearch, stringResource(R.string.home_no_results), stringResource(R.string.home_no_results_body), stringResource(R.string.home_search_inside_library)) { vm.home.searchInLibrary(query) }
        } else {
            LazyColumn(Modifier.weight(1f)) { items(results, key = { it.key }) { r -> FileListRow(vm, r) } }
        }
    }
}
