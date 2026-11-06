package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.BarIcon
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import io.github.artcraftandroid.pdfcraft.ui.PdfSheet
import io.github.artcraftandroid.pdfcraft.ui.SheetBackHeader
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import kotlinx.coroutines.CancellationException

/** Opt-in read-only inspection; never scan a whole large PDF while opening the viewer. */
@Composable
fun BackdropsSheet(vm: AppViewModel, doc: OpenDocument) {
    val batchSize = 24
    var first by remember(doc.id) { mutableStateOf(doc.viewer.currentPage / batchSize * batchSize) }
    val start = first.coerceIn(0, (doc.pageCount - 1).coerceAtLeast(0))
    val end = (start.toLong() + batchSize).coerceAtMost(doc.pageCount.toLong()).toInt()
    val pages = (start until end).toList()
    var found by remember(doc.id, doc.info.generation, start) { mutableStateOf<Map<Int, String>?>(null) }
    var failed by remember(doc.id, doc.info.generation, start) { mutableStateOf(false) }

    LaunchedEffect(doc.id, doc.info.generation, start) {
        try {
            check(vm.document === doc)
            val engine = checkNotNull(vm.engine)
            val result = engine.tool("doc_backdrops", mapOf("doc" to doc.id, "pages" to pages.map { it + 1 }))
            val rows = result.getJSONArray("pages")
            val kinds = buildMap {
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    val page = row.getInt("page") - 1
                    val kind = row.getString("backdrop")
                    check(page in start until end && kind in setOf("fill", "image"))
                    put(page, kind)
                }
            }
            if (vm.document === doc) found = kinds
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }

    PdfSheet(onDismiss = { doc.controller.sheet = ViewerSheet.None }, maxHeightFraction = 0.8f, fill = true) {
        SheetBackHeader(stringResource(R.string.backdrops_title), onBack = { doc.controller.sheet = ViewerSheet.FileDetails })
        Text(stringResource(R.string.backdrops_explanation), color = pdfColors.textSecondary,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BarIcon(LucideIcons.ChevronLeft, stringResource(R.string.backdrops_previous),
                { first = (start - batchSize).coerceAtLeast(0) }, enabled = start > 0)
            Text(stringResource(R.string.backdrops_range, start + 1, end, doc.pageCount),
                color = pdfColors.text, modifier = Modifier.weight(1f))
            BarIcon(LucideIcons.ChevronRight, stringResource(R.string.backdrops_next),
                { first = end }, enabled = end < doc.pageCount)
        }
        LazyColumn(Modifier.weight(1f)) {
            when {
                failed -> item { Text(stringResource(R.string.backdrops_failed), color = pdfColors.textSecondary, modifier = Modifier.padding(18.dp)) }
                found == null -> item { Text(stringResource(R.string.backdrops_loading), color = pdfColors.textSecondary, modifier = Modifier.padding(18.dp)) }
                else -> items(pages, key = { it }) { page ->
                    val label = when (found?.get(page)) {
                        "fill" -> R.string.backdrops_fill
                        "image" -> R.string.backdrops_image
                        else -> R.string.backdrops_none
                    }
                    Text(stringResource(R.string.backdrops_page, page + 1, stringResource(label)),
                        color = pdfColors.text, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp))
                }
            }
        }
    }
}
