package io.github.artcraftandroid.pdfcraft.ui.viewer

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.engine.userMessage
import io.github.artcraftandroid.pdfcraft.text as appText
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import io.github.artcraftandroid.pdfcraft.ui.PdfSheet
import io.github.artcraftandroid.pdfcraft.ui.PdfSwitch
import io.github.artcraftandroid.pdfcraft.ui.SheetBackHeader
import io.github.artcraftandroid.pdfcraft.ui.SheetDivider
import io.github.artcraftandroid.pdfcraft.ui.copyIntoCache
import io.github.artcraftandroid.pdfcraft.ui.copyOut
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A difference Compare found: replaced, inserted or deleted text, or (visual) a region that looks different. */
data class CompareChange(
    val kind: String,
    val oldText: String,
    val newText: String,
    /** Where it is in the newer document: 0-based page and a box in top-left-origin points; null for a deletion with nowhere to show. */
    val page: Int?,
    val rect: FloatArray?,
)

/**
 * The box around [rects] (x0, y0, x1, y1 in PDF points), in top-left-origin points of a page
 * [pageHeight] tall (null: the rectangles are already top-left); null when there are none.
 */
fun unionTopLeft(rects: List<FloatArray>, pageHeight: Float?): FloatArray? {
    if (rects.isEmpty()) return null
    var box: FloatArray? = null
    for (r in rects) {
        val top = if (pageHeight != null) pageHeight - maxOf(r[1], r[3]) else minOf(r[1], r[3])
        val bottom = if (pageHeight != null) pageHeight - minOf(r[1], r[3]) else maxOf(r[1], r[3])
        val x0 = minOf(r[0], r[2])
        val x1 = maxOf(r[0], r[2])
        box = box?.let { floatArrayOf(minOf(it[0], x0), minOf(it[1], top), maxOf(it[2], x1), maxOf(it[3], bottom)) } ?: floatArrayOf(x0, top, x1, bottom)
    }
    return box
}

/** What doc_compare found: counts by kind, and each change. */
data class CompareResult(val replaced: Int, val inserted: Int, val deleted: Int, val identical: Boolean, val changes: List<CompareChange>)

/**
 * doc_compare's answer, its rectangles (PDF points, origin bottom-left) turned into top-left
 * points of the newer document's pages, whose heights are [pageHeights]. A change's box is the
 * union of its rectangles on its first page; a deletion shows where the old text was when the
 * newer document has no place for it.
 */
fun parseCompare(o: JSONObject, pageHeights: List<Float>): CompareResult {
    fun side(c: JSONObject?, flip: Boolean): Pair<Int?, FloatArray?> {
        c ?: return null to null
        val page = c.optInt("page", 0).takeIf { it >= 1 }?.minus(1) ?: return null to null
        val list = c.optJSONArray("rects") ?: return page to null
        val rects = (0 until list.length()).mapNotNull { i -> list.optJSONArray(i)?.let { r -> FloatArray(4) { r.optDouble(it).toFloat() } } }
        val box = unionTopLeft(rects, if (flip) pageHeights.getOrNull(page) else null)
        return page to box
    }
    val list = o.optJSONArray("changes")
    val changes = (0 until (list?.length() ?: 0)).mapNotNull { i ->
        val c = list?.optJSONObject(i) ?: return@mapNotNull null
        val new = c.optJSONObject("new")
        val old = c.optJSONObject("old")
        val (page, rect) = side(new, flip = true).takeIf { it.first != null } ?: (side(old, flip = false).first to null)
        CompareChange(c.optString("kind"), old?.optString("text").orEmpty(), new?.optString("text").orEmpty(), page, rect)
    }
    val visual = o.optJSONArray("visual")
    val regions = (0 until (visual?.length() ?: 0)).mapNotNull { i ->
        val v = visual?.optJSONObject(i) ?: return@mapNotNull null
        val page = v.optInt("page", 0) - 1
        val r = v.optJSONArray("rect") ?: return@mapNotNull null
        val h = pageHeights.getOrNull(page) ?: return@mapNotNull null
        CompareChange("visual", "", "", page, floatArrayOf(r.optDouble(0).toFloat(), h - r.optDouble(3).toFloat(), r.optDouble(2).toFloat(), h - r.optDouble(1).toFloat()))
    }
    return CompareResult(o.optInt("replaced"), o.optInt("inserted"), o.optInt("deleted"), o.optBoolean("identical"), changes + regions)
}

/** Compare (#171): the older document open beside the one in the viewer, and what was found. */
class CompareState {
    var newer by mutableStateOf<OpenDocument?>(null)
    var olderId by mutableStateOf<ULong?>(null)
    var olderName by mutableStateOf("")
    var visual by mutableStateOf(false)
    var result by mutableStateOf<CompareResult?>(null)
    var job: Job? = null
}

/** Compare files: [doc] is the newer version; pick the older one, then compare. */
fun AppViewModel.startCompare(doc: OpenDocument) {
    requests.open(arrayOf("application/pdf"), many = false) { uris ->
        val uri = uris.firstOrNull() ?: return@open
        val eng = engine ?: return@open
        val ctx = getApplication<Application>()
        compare.job?.cancel()
        compare.job = viewModelScope.launch {
            closeCompare()
            busy = appText(R.string.compare_busy)
            var file: File? = null
            try {
                file = copyIntoCache(ctx, uri, "compare", ".pdf")
                val path = file.absolutePath
                val id = withContext(NonCancellable) { eng.command { open(path, null).id } }
                compare.newer = doc
                compare.olderId = id
                compare.olderName = files.displayName(uri)
                runCompare(doc)
            } catch (e: Exception) {
                tell(e.userMessage)
                busy = null
            } finally {
                // the engine holds the document's bytes once it is open
                file?.delete()
            }
        }
    }
}

/** Compare again (after the visual switch changed). */
fun AppViewModel.runCompare(doc: OpenDocument) {
    val eng = engine ?: return
    val older = compare.olderId ?: return
    viewModelScope.launch {
        busy = appText(R.string.compare_busy)
        try {
            val t0 = System.nanoTime()
            val r = eng.tool("doc_compare", mapOf("doc" to doc.id, "other" to older.toLong(), "visual" to compare.visual, "limit" to 2000))
            compare.result = parseCompare(r, doc.info.pages.map { it.height })
            editEvent(doc, "compare", "visual" to compare.visual, "changes" to compare.result?.changes?.size, "identical" to compare.result?.identical, "ms" to (System.nanoTime() - t0) / 1_000_000)
            doc.controller.sheet = ViewerSheet.Compare
        } catch (e: Exception) {
            tell(e.userMessage)
        } finally {
            busy = null
        }
    }
}

/** Add the differences to the newer document as comments (one undo step). */
fun AppViewModel.markCompare(doc: OpenDocument) {
    val older = compare.olderId ?: return
    runTool(doc, "doc_compare_mark", mapOf("other" to older.toLong())) { r ->
        editEvent(doc, "compare_mark", "comments" to r.optInt("comments"))
        tell(appText(R.string.compare_marked, r.optInt("comments")), appText(R.string.common_undo)) { undo(doc) }
    }
}

/** Save the summary report PDF where the user chooses. */
fun AppViewModel.saveCompareReport(doc: OpenDocument) {
    val eng = engine ?: return
    val older = compare.olderId ?: return
    val ctx = getApplication<Application>()
    viewModelScope.launch {
        val out = File(ctx.cacheDir, "out-compare-report-${System.nanoTime()}.pdf")
        try {
            eng.tool("doc_compare_report", mapOf("doc" to doc.id, "other" to older.toLong(), "path" to out.absolutePath))
        } catch (e: Exception) {
            out.delete()
            tell(e.userMessage)
            return@launch
        }
        editEvent(doc, "compare_report")
        requests.create("${doc.name.removeSuffix(".pdf")}_compare.pdf", "application/pdf") { uri ->
            viewModelScope.launch {
                try {
                    if (uri != null) {
                        copyOut(ctx, out, uri)
                        tell(appText(R.string.compare_report_saved))
                    }
                } catch (e: Exception) {
                    tell(e.userMessage)
                } finally {
                    out.delete()
                }
            }
        }
    }
}

/** Close the older document and forget the result. */
suspend fun AppViewModel.closeCompare() {
    val id = compare.olderId ?: return
    compare.olderId = null
    compare.result = null
    compare.newer = null
    val eng = engine ?: return
    runCatching { withContext(NonCancellable) { eng.command { close(id, true) } } }
}

fun AppViewModel.endCompare(doc: OpenDocument) {
    if (doc.controller.sheet == ViewerSheet.Compare) doc.controller.sheet = ViewerSheet.None
    viewModelScope.launch { closeCompare() }
}

private val Blue = Color(0xFF2680EB)
private val Green = Color(0xFF2D9D78)
private val Red = Color(0xFFE34850)

/**
 * The differences, over the newer document: counts by kind, then each change (blue replaced,
 * green inserted, red deleted, grey visual); tap one to go to it. Add as comments, Save report,
 * and the visual comparison switch.
 */
@Composable
fun CompareSheet(vm: AppViewModel, doc: OpenDocument) {
    val c = pdfColors
    val r = vm.compare.result ?: return
    PdfSheet(onDismiss = { vm.endCompare(doc) }, maxHeightFraction = 0.6f, dim = false) {
        SheetBackHeader(stringResource(R.string.compare_title), onBack = { vm.endCompare(doc) })
        Text(stringResource(R.string.compare_against, vm.compare.olderName), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
        Text(
            if (r.identical) stringResource(R.string.compare_identical) else stringResource(R.string.compare_counts, r.replaced, r.inserted, r.deleted),
            color = c.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(enabled = !r.identical, onClick = { vm.markCompare(doc) }) { Text(stringResource(R.string.compare_mark), color = c.accent) }
            TextButton(onClick = { vm.saveCompareReport(doc) }) { Text(stringResource(R.string.compare_report), color = c.accent) }
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = vm.compare.visual, role = Role.Switch) { vm.compare.visual = it; vm.runCompare(doc) }
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.compare_visual), color = c.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            PdfSwitch(vm.compare.visual, null)
        }
        SheetDivider()
        LazyColumn(Modifier.fillMaxWidth()) {
            items(r.changes) { ch ->
                val (color, label) = when (ch.kind) {
                    "replaced" -> Blue to R.string.compare_kind_replaced
                    "inserted" -> Green to R.string.compare_kind_inserted
                    "deleted" -> Red to R.string.compare_kind_deleted
                    else -> Color.Gray to R.string.compare_kind_visual
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = ch.page != null) {
                        ch.page?.let { vm.flashRegion(doc, it, ch.rect) }
                    }.padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(label) + (ch.page?.let { " · " + stringResource(R.string.compare_page, it + 1) } ?: ""),
                            color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        )
                        if (ch.oldText.isNotBlank()) Text("− " + ch.oldText.take(160), color = c.textSecondary, fontSize = 13.sp, maxLines = 2)
                        if (ch.newText.isNotBlank()) Text("+ " + ch.newText.take(160), color = c.text, fontSize = 13.sp, maxLines = 2)
                    }
                }
            }
        }
    }
}
