package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import io.github.artcraftandroid.pdfcraft.ui.PdfField
import io.github.artcraftandroid.pdfcraft.ui.PdfSheet
import io.github.artcraftandroid.pdfcraft.ui.SheetRow
import io.github.artcraftandroid.pdfcraft.ui.pdfColors

/**
 * A link being made or changed (#179, #159): on [page] (0-based) over [rect] (points, top-left
 * origin). [index] is the existing link's 1-based position from `link_list`, null for a new one;
 * [url] or [toPage] (0-based) is where it goes now.
 */
class LinkEdit(
    val doc: OpenDocument,
    val page: Int,
    val rect: FloatArray,
    val index: Int? = null,
    val url: String? = null,
    val toPage: Int? = null,
    val from: String = "box",
)

/**
 * A typed web address as a link target: `https://` is added when no scheme is given; only web
 * and mail addresses are accepted (null otherwise), as the viewer only follows those.
 */
internal fun normalizeLinkUrl(raw: String): String? {
    val s = raw.trim()
    if (s.isEmpty() || s.any { it.isWhitespace() || it.isISOControl() }) return null
    val scheme = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):").find(s)?.groupValues?.get(1)?.lowercase()
    return when {
        scheme == null -> if (s.contains('@') && !s.contains('/')) "mailto:$s" else if (s.contains('.')) "https://$s" else null
        scheme == "http" || scheme == "https" -> s.takeIf { it.length > scheme.length + 3 && it.substring(scheme.length + 1).startsWith("//") }
        scheme == "mailto" -> s.takeIf { it.length > 7 }
        // "example.com:8080" has no scheme of its own
        s.substringAfter(':').firstOrNull()?.isDigit() == true -> "https://$s"
        else -> null
    }
}

/** A typed page number (1-based) as a 0-based page of a [pageCount]-page document, or null. */
internal fun parseLinkPage(raw: String, pageCount: Int): Int? = raw.trim().toIntOrNull()?.takeIf { it in 1..pageCount }?.minus(1)

/**
 * The 1-based `link_list` index of the link on [page] (0-based) whose rect is [rect]: links are
 * matched by their rectangle, the nearest within a point. [links] are (page 1-based, index, rect).
 */
internal fun linkIndexFor(links: List<Triple<Int, Int, List<Float>>>, page: Int, rect: List<Float>): Int? =
    links.filter { it.first == page + 1 && it.third.size == 4 && rect.size == 4 }
        .minByOrNull { (_, _, r) -> r.indices.sumOf { kotlin.math.abs(r[it] - rect[it]).toDouble() } }
        ?.takeIf { (_, _, r) -> r.indices.all { kotlin.math.abs(r[it] - rect[it]) <= 1f } }
        ?.second

/** Link mode in Edit PDF: drag a box to link it, tap an existing link to change or delete it. */
@Composable
fun LinkLayer(vm: AppViewModel, doc: OpenDocument) {
    var start by remember(doc) { mutableStateOf<PagePoint?>(null) }
    var end by remember(doc) { mutableStateOf<PagePoint?>(null) }
    Box(
        Modifier.fillMaxSize().toolGestures(
            doc,
            onDown = { p, _, _ ->
                start = doc.viewer.screenToPage(p)
                end = start
            },
            onMove = { p, _, _ ->
                val s = start ?: return@toolGestures
                end = doc.viewer.screenToPageOf(s.page, p) ?: end
            },
            onUp = { p, moved ->
                val s = start
                val e = end
                start = null
                end = null
                if (s == null) return@toolGestures
                val rect = if (moved && e != null) areaRect(s, e) else null
                if (rect != null) vm.linkEdit = LinkEdit(doc, s.page, rect)
                else vm.editLinkAt(doc, doc.viewer.screenToPage(p) ?: s)
            },
            onCancel = {
                start = null
                end = null
            },
        ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // existing links, so they can be found and tapped
            for ((page, links) in doc.links) {
                for (l in links) {
                    if (l.rect.size != 4) continue
                    val a = doc.viewer.pageToScreen(page, l.rect[0], l.rect[1])
                    val b = doc.viewer.pageToScreen(page, l.rect[2], l.rect[3])
                    drawRect(Color(0x332680EB), a, Size(b.x - a.x, b.y - a.y))
                    drawRect(Color(0xFF2680EB), a, Size(b.x - a.x, b.y - a.y), style = Stroke(1.dp.toPx()))
                }
            }
            val s = start
            val e = end
            if (s != null && e != null) {
                val r = areaRect(s, e) ?: return@Canvas
                val a = doc.viewer.pageToScreen(s.page, r[0], r[1])
                val b = doc.viewer.pageToScreen(s.page, r[2], r[3])
                drawRect(Color(0x332680EB), a, Size(b.x - a.x, b.y - a.y))
                drawRect(Color(0xFF2680EB), Offset(a.x, a.y), Size(b.x - a.x, b.y - a.y), style = Stroke(2.dp.toPx()))
            }
        }
    }
}

private fun areaRect(a: PagePoint, b: PagePoint): FloatArray? = areaHighlightRect(a.x, a.y, b.x, b.y)

/** Where a link goes: a web address or a page of this document, then Save (and Delete for an existing one). */
@Composable
fun LinkSheet(vm: AppViewModel, edit: LinkEdit) {
    val c = pdfColors
    val doc = edit.doc
    var toPage by remember(edit) { mutableStateOf(edit.url == null) }
    var url by remember(edit) { mutableStateOf(edit.url.orEmpty()) }
    var page by remember(edit) { mutableStateOf(edit.toPage?.let { (it + 1).toString() } ?: "") }
    var wrong by remember(edit) { mutableStateOf(false) }
    PdfSheet(onDismiss = { vm.linkEdit = null }, dim = true) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                stringResource(if (edit.index == null) R.string.link_new else R.string.link_edit),
                color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
        }
        SheetRow(LucideIcons.FileText, stringResource(R.string.link_to_page), { toPage = true; wrong = false }, checked = toPage)
        SheetRow(LucideIcons.Globe, stringResource(R.string.link_to_web), { toPage = false; wrong = false }, checked = !toPage)
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (toPage) {
                PdfField(
                    page, { page = it.filter(Char::isDigit).take(6); wrong = false }, stringResource(R.string.link_page_hint, doc.pageCount), error = wrong,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            } else {
                PdfField(
                    url, { url = it; wrong = false }, stringResource(R.string.link_web_hint), error = wrong,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
            if (wrong) Text(stringResource(if (toPage) R.string.link_page_wrong else R.string.link_web_wrong), color = c.red, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (edit.index != null) {
                    SheetTextButton(stringResource(R.string.common_delete), c.red) { vm.deleteLink(edit) }
                }
                SheetTextButton(stringResource(R.string.common_cancel), c.textSecondary) { vm.linkEdit = null }
                SheetTextButton(stringResource(R.string.link_save), c.accent) {
                    if (toPage) {
                        val p = parseLinkPage(page, doc.pageCount)
                        if (p == null) wrong = true else vm.saveLink(edit, url = null, toPage = p)
                    } else {
                        val u = normalizeLinkUrl(url)
                        if (u == null) wrong = true else vm.saveLink(edit, url = u, toPage = null)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SheetTextButton(label: String, color: Color, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) { Text(label, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
}
