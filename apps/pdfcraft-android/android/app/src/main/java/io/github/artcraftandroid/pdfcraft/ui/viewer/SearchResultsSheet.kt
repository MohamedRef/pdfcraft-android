package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.core.SearchHit
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import io.github.artcraftandroid.pdfcraft.ui.PdfSheet
import io.github.artcraftandroid.pdfcraft.ui.SheetBackHeader
import io.github.artcraftandroid.pdfcraft.ui.pdfColors

/**
 * Find ▸ Show all results: every hit so far with the line it is on, grouped by page; a tap shows
 * that hit (#184). The list grows while a long document is still being searched.
 */
@Composable
fun SearchResultsSheet(doc: OpenDocument, onShow: (SearchHit) -> Unit) {
    val c = pdfColors
    val ctl = doc.controller
    val search = doc.search
    val hits = search.hits.toList()
    val rows = remember(hits) { searchRows(hits) }
    PdfSheet(onDismiss = { ctl.sheet = ViewerSheet.None }, maxHeightFraction = 0.75f, fill = true) {
        val title = if (search.running) stringResource(R.string.viewer_search_results_running, hits.size)
        else pluralStringResource(R.plurals.viewer_search_results, hits.size, hits.size)
        SheetBackHeader(title, onBack = { ctl.sheet = ViewerSheet.None })
        LazyColumn(Modifier.weight(1f)) {
            items(rows) { row ->
                when (row) {
                    is SearchRow.Page -> Text(
                        stringResource(R.string.sheet_page, row.page + 1),
                        color = c.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth().background(c.surface).padding(start = 20.dp, top = 14.dp, bottom = 4.dp).semantics { heading() },
                    )
                    is SearchRow.Hit -> Column(
                        Modifier.fillMaxWidth()
                            .background(if (row.index == search.current) c.pressed else c.surface)
                            .clickable {
                                search.select(row.index)?.let(onShow)
                                ctl.sheet = ViewerSheet.None
                            }
                            .heightIn(min = 48.dp).padding(horizontal = 20.dp, vertical = 10.dp),
                    ) {
                        Text(emphasize(row.context, search.query, c.text), color = c.text, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** [line] with each case-insensitive occurrence of [query] in bold. */
private fun emphasize(line: String, query: String, color: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    val q = query.trim()
    if (q.isEmpty()) { append(line); return@buildAnnotatedString }
    var at = 0
    while (at < line.length) {
        val i = line.indexOf(q, at, ignoreCase = true)
        if (i < 0) break
        append(line.substring(at, i))
        pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = color))
        append(line.substring(i, i + q.length))
        pop()
        at = i + q.length
    }
    append(line.substring(at))
}
