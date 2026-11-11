package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.core.DocumentProperties
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import io.github.artcraftandroid.pdfcraft.ui.OpenLayout
import io.github.artcraftandroid.pdfcraft.ui.OpenPanel
import io.github.artcraftandroid.pdfcraft.ui.OpenZoom
import io.github.artcraftandroid.pdfcraft.ui.PdfDialog
import io.github.artcraftandroid.pdfcraft.ui.PdfField
import io.github.artcraftandroid.pdfcraft.ui.SheetRow
import io.github.artcraftandroid.pdfcraft.ui.customKeyValid
import io.github.artcraftandroid.pdfcraft.ui.metadataChanges
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import io.github.artcraftandroid.pdfcraft.ui.percentFromReply
import kotlinx.coroutines.launch

/** Whether metadata, the initial view or flattening can change [doc] now. */
internal fun canEditDocument(doc: OpenDocument): Boolean = doc.writable && !doc.info.signed && doc.info.readOnlyReason == null && !doc.info.preparing

/** File details' tools: Edit properties, Opens with, and Word count (counted on demand). */
@Composable
fun FileDetailsTools(vm: AppViewModel, doc: OpenDocument, properties: DocumentProperties?) {
    val c = pdfColors
    var editing by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf(false) }
    var count by remember(doc.info.generation) { mutableStateOf<String?>(null) }
    var counting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val editable = canEditDocument(doc)
    SheetRow(LucideIcons.SquarePen, stringResource(R.string.properties_edit), { editing = true }, enabled = editable)
    SheetRow(LucideIcons.BookOpen, stringResource(R.string.properties_opens_with), { opening = true }, enabled = editable)
    val words = stringResource(R.string.properties_word_count)
    val countLabel = count?.let { "$words: $it" } ?: if (counting) "$words…" else words
    SheetRow(LucideIcons.Hash, countLabel, {
        if (counting) return@SheetRow
        counting = true
        scope.launch {
            val t = vm.wordCount(doc)
            counting = false
            if (t != null) count = vm.wordCountLabel(t.optLong("words"), t.optLong("characters"))
        }
    }, enabled = doc.info.allowsCopying && !doc.info.preparing)
    if (!editable) Text(stringResource(R.string.panels_locked_read_only), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
    if (editing) EditPropertiesDialog(vm, doc, properties) { editing = false }
    if (opening) InitialViewDialog(vm, doc) { opening = false }
}

@Composable
private fun EditPropertiesDialog(vm: AppViewModel, doc: OpenDocument, properties: DocumentProperties?, onDismiss: () -> Unit) {
    val c = pdfColors
    val info = doc.info
    val before = remember {
        mapOf("Title" to info.title, "Author" to info.author, "Subject" to info.subject, "Keywords" to info.keywords) +
            properties?.customMetadata.orEmpty().associate { it.name to it.value }
    }
    val values = remember { mutableStateListOf<Pair<String, String>>().apply { before.forEach { (k, v) -> add(k to (v ?: "")) } } }
    var newKey by remember { mutableStateOf("") }
    var newValue by remember { mutableStateOf("") }
    val newKeyOk = newKey.isEmpty() || customKeyValid(newKey) && values.none { it.first == newKey }
    val labels = mapOf(
        "Title" to stringResource(R.string.file_details_pdf_title), "Author" to stringResource(R.string.file_details_author),
        "Subject" to stringResource(R.string.file_details_subject), "Keywords" to stringResource(R.string.file_details_keywords),
    )
    PdfDialog(
        onDismiss = onDismiss, title = stringResource(R.string.properties_edit), confirm = stringResource(R.string.common_save),
        confirmEnabled = newKeyOk,
        onConfirm = {
            val after = values.toMap().toMutableMap()
            if (newKey.isNotEmpty() && newValue.isNotBlank()) after[newKey] = newValue
            vm.setDocInfo(doc, metadataChanges(before, after))
            onDismiss()
        },
    ) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            values.forEachIndexed { i, (k, v) ->
                Text(labels[k] ?: k, color = c.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = if (i == 0) 0.dp else 8.dp, bottom = 4.dp))
                PdfField(v, { values[i] = k to it.take(2000) }, labels[k] ?: k)
            }
            Text(stringResource(R.string.properties_add_custom), color = c.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
            PdfField(newKey, { newKey = it.take(64) }, stringResource(R.string.properties_custom_name), error = !newKeyOk)
            Spacer(Modifier.height(6.dp))
            PdfField(newValue, { newValue = it.take(2000) }, stringResource(R.string.properties_custom_value))
            Text(stringResource(R.string.properties_empty_removes), color = c.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun InitialViewDialog(vm: AppViewModel, doc: OpenDocument, onDismiss: () -> Unit) {
    val c = pdfColors
    var loaded by remember { mutableStateOf(false) }
    var layout by remember { mutableStateOf(OpenLayout.Default) }
    var zoom by remember { mutableStateOf<OpenZoom?>(OpenZoom.Default) }
    var percent by remember { mutableStateOf<Double?>(null) }
    var panel by remember { mutableStateOf(OpenPanel.Page) }
    var page by remember { mutableStateOf("1") }
    var original by remember { mutableStateOf<Map<String, Any?>>(emptyMap()) }
    LaunchedEffect(Unit) {
        val v = vm.initialView(doc) ?: return@LaunchedEffect onDismiss()
        layout = OpenLayout.fromReply(v.optString("layout"))
        zoom = OpenZoom.fromReply(v.optString("magnification"))
        percent = percentFromReply(v.optString("magnification"))
        panel = OpenPanel.fromReply(v.optString("navigation"))
        page = v.optInt("page", 1).toString()
        original = mapOf("layout" to layout.arg, "magnification" to zoom?.arg, "navigation" to panel.arg, "page" to v.optInt("page", 1))
        loaded = true
    }
    val p = page.toIntOrNull()
    PdfDialog(
        onDismiss = onDismiss, title = stringResource(R.string.properties_opens_with), confirm = stringResource(R.string.common_save),
        confirmEnabled = loaded && p != null && p in 1..doc.pageCount,
        onConfirm = {
            val now = mapOf("layout" to layout.arg, "magnification" to zoom?.arg, "navigation" to panel.arg, "page" to p)
            vm.setInitialView(doc, now.filter { (k, v) -> v != null && original[k] != v })
            onDismiss()
        },
    ) {
        if (!loaded) {
            Text("…", color = c.textSecondary)
            return@PdfDialog
        }
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.properties_open_page), color = c.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
            NumberField(page, { page = it }, stringResource(R.string.properties_open_page))
            Text(stringResource(R.string.properties_open_layout), color = c.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
            val layoutNames = listOf(
                R.string.properties_layout_default, R.string.properties_layout_single, R.string.properties_layout_continuous, R.string.properties_layout_two_up,
                R.string.properties_layout_two_up_continuous, R.string.properties_layout_two_up_cover, R.string.properties_layout_two_up_continuous_cover,
            )
            OpenLayout.entries.forEachIndexed { i, l -> Choice(layout == l, stringResource(layoutNames[i])) { layout = l } }
            Text(stringResource(R.string.properties_open_zoom), color = c.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
            if (zoom == null && percent != null) Choice(true, "${percent!!.toInt()} %") {}
            val zoomNames = listOf(
                R.string.properties_zoom_default, R.string.properties_zoom_actual, R.string.properties_zoom_fit_page,
                R.string.properties_zoom_fit_width, R.string.properties_zoom_fit_height, R.string.properties_zoom_fit_visible,
            )
            OpenZoom.entries.forEachIndexed { i, z -> Choice(zoom == z, stringResource(zoomNames[i])) { zoom = z } }
            Text(stringResource(R.string.properties_open_panel), color = c.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
            val panelNames = listOf(
                R.string.properties_panel_page, R.string.sheet_tab_contents, R.string.sheet_pages, R.string.sheet_attachments, R.string.sheet_layers,
            )
            OpenPanel.entries.forEachIndexed { i, n -> Choice(panel == n, stringResource(panelNames[i])) { panel = n } }
        }
    }
}

/** Flatten: comments and/or form fields become part of the page; undoable until the file is saved. */
@Composable
fun FlattenDialog(vm: AppViewModel, doc: OpenDocument) {
    val c = pdfColors
    var comments by remember { mutableStateOf(true) }
    var fields by remember { mutableStateOf(doc.info.fields > 0u) }
    PdfDialog(
        onDismiss = { vm.flattenPrompt = null }, title = stringResource(R.string.flatten_title), confirm = stringResource(R.string.flatten_action),
        confirmEnabled = comments || fields, destructive = true,
        onConfirm = { vm.flatten(doc, comments, fields) },
    ) {
        Column {
            Text(stringResource(R.string.flatten_warning), color = c.textSecondary, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            for ((label, checked, set) in listOf(
                Triple(stringResource(R.string.flatten_comments), comments) { v: Boolean -> comments = v },
                Triple(stringResource(R.string.flatten_fields), fields) { v: Boolean -> fields = v },
            )) {
                Row(Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { set(!checked) }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked, { set(it) }, colors = CheckboxDefaults.colors(checkedColor = c.toolAccent))
                    Text(label, color = c.text, fontSize = 16.sp)
                }
            }
        }
    }
}
