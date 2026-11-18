package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.core.ToolJob
import io.github.artcraftandroid.pdfcraft.engine.PdfTextStyle
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.JOB_WATCH_MS
import io.github.artcraftandroid.pdfcraft.ui.JobLine
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import io.github.artcraftandroid.pdfcraft.ui.PdfSheet
import io.github.artcraftandroid.pdfcraft.ui.SheetBackHeader
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import io.github.artcraftandroid.pdfcraft.ui.stylesLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Deliberately opt-in: text_styles can read the first 5,000 pages. No work on PDF opening. */
@Composable
internal fun TextStylesSheet(vm: AppViewModel, doc: OpenDocument) {
    val scope = rememberCoroutineScope()
    val generation = doc.info.generation
    var styles by remember(doc.id, generation) { mutableStateOf<List<PdfTextStyle>?>(null) }
    var selected by remember(doc.id, generation) { mutableStateOf<List<Int>>(emptyList()) }
    var loadedGeneration by remember(doc.id) { mutableStateOf<ULong?>(null) }
    var loading by remember(doc.id) { mutableStateOf(false) }
    var failed by remember(doc.id, generation) { mutableStateOf(false) }
    // Analyze's run (#723): its job says the page being read, and Cancel, or the sheet closing,
    // stops the engine at its next page.
    var running by remember(doc.id) { mutableStateOf<ToolJob?>(null) }
    var line by remember(doc.id) { mutableStateOf<JobLine?>(null) }
    var stopAsked by remember(doc.id) { mutableStateOf(false) }
    var stopped by remember(doc.id, generation) { mutableStateOf(false) }
    DisposableEffect(doc.id) { onDispose { running?.cancel() } }
    // Changing generation invalidates the list and selection above. A running analysis also
    // compares its captured generation before publishing or enabling an edit.
    LaunchedEffect(doc.id, generation) { loadedGeneration = null }
    val canAnalyze = vm.document === doc && doc.info.allowsCopying && doc.info.readOnlyReason == null
    val canEdit = canAnalyze && doc.writable && !doc.info.signed && doc.info.allowsAssembly

    PdfSheet(onDismiss = { doc.controller.sheet = ViewerSheet.None }, maxHeightFraction = 0.85f, fill = true) {
        SheetBackHeader(stringResource(R.string.text_styles_title), onBack = { doc.controller.sheet = ViewerSheet.Bookmarks })
        Text(stringResource(R.string.text_styles_explanation), color = pdfColors.textSecondary,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
        Text(stringResource(R.string.text_styles_scope), color = pdfColors.textSecondary,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
        TextButton(enabled = canAnalyze && !loading, onClick = {
            scope.launch {
                loading = true
                failed = false
                stopped = false
                stopAsked = false
                styles = null
                selected = emptyList()
                loadedGeneration = null
                val job = ToolJob()
                running = job
                val watching = launch {
                    while (true) {
                        val p = job.progress()
                        if (p.steps > 0uL) line = stylesLine(p.stage, p.done.toInt(), p.total.toInt())
                        delay(JOB_WATCH_MS)
                    }
                }
                val started = System.nanoTime()
                var outcome = "failed"
                try {
                    val engine = checkNotNull(vm.engine)
                    val result = engine.tool("text_styles", mapOf("doc" to doc.id), job)
                    outcome = "finished"
                    check(vm.document === doc && doc.info.generation == generation)
                    val rows = result.getJSONArray("styles")
                    // The pinned native engine already returns at most 200 styles.
                    styles = (0 until rows.length()).map { i ->
                        val row = rows.getJSONObject(i)
                        PdfTextStyle(row.getString("font"), row.getDouble("size"), row.getBoolean("bold"),
                            row.getBoolean("italic"), row.getInt("lines"), row.getInt("pages"), row.getString("sample"))
                    }
                    loadedGeneration = generation
                } catch (e: CancellationException) {
                    if (outcome != "finished") outcome = "stopped"
                    throw e
                } catch (_: Exception) {
                    if (outcome != "finished" && job.isCancelled()) {
                        outcome = "stopped"
                        stopped = true
                    } else {
                        failed = true
                    }
                } finally {
                    watching.cancel()
                    val p = job.progress()
                    vm.jobEnd("text_styles", doc, outcome, started, doc.info.fileSize.toLong(), doc.pageCount,
                        p.stage, p.done.toInt(), p.total.toInt(), p.percent.toInt())
                    running = null
                    line = null
                    loading = false
                }
            }
        }) { Text(stringResource(R.string.text_styles_scan)) }
        if (!canAnalyze || !canEdit) {
            Text(stringResource(R.string.text_styles_unavailable), color = pdfColors.textSecondary,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
        }
        LazyColumn(Modifier.weight(1f)) {
            when {
                loading -> item {
                    Column(Modifier.padding(18.dp)) {
                        Text(line?.let { stringResource(it.res, *it.args.toTypedArray()) } ?: stringResource(R.string.text_styles_loading))
                        val job = running
                        if (job != null) {
                            TextButton(enabled = !stopAsked, onClick = { stopAsked = true; job.cancel() }) {
                                Text(stringResource(if (stopAsked) R.string.busy_cancelling else R.string.common_cancel))
                            }
                        }
                    }
                }
                stopped -> item { Text(stringResource(R.string.text_styles_stopped), modifier = Modifier.padding(18.dp)) }
                failed -> item { Text(stringResource(R.string.text_styles_failed), modifier = Modifier.padding(18.dp)) }
                styles?.isEmpty() == true -> item { Text(stringResource(R.string.text_styles_empty), modifier = Modifier.padding(18.dp)) }
                else -> {
                    itemsIndexed(styles.orEmpty()) { index, style ->
                        val level = selected.indexOf(index) + 1
                        val enabled = !loading && canEdit && (level > 0 || selected.size < 9)
                        Row(Modifier.fillMaxWidth().toggleable(value = level > 0, enabled = enabled, role = Role.Checkbox) {
                            selected = if (level > 0) selected - index else selected + index
                        }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = level > 0, onCheckedChange = null, enabled = enabled)
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(stringResource(R.string.text_styles_description, style.font.take(256), style.size.toString(), style.lines, style.pages),
                                    color = pdfColors.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Text(style.sample.take(256), color = pdfColors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
                                    fontStyle = if (style.italic) FontStyle.Italic else FontStyle.Normal)
                                if (level > 0) Text(stringResource(R.string.text_styles_level, level), color = pdfColors.accent)
                            }
                        }
                    }
                }
            }
        }
        TextButton(enabled = canEdit && !loading && vm.tools.busy == null && selected.isNotEmpty() && loadedGeneration == generation, onClick = {
            val chosen = styles ?: return@TextButton
            vm.tools.createStyleBookmarks(doc, generation, selected.map { chosen[it] })
        }) { Text(stringResource(R.string.text_styles_create)) }
    }
}
