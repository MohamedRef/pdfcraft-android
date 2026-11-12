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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import androidx.compose.ui.graphics.drawscope.rotate
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
    /** Another PDF the link opens (#334); [toPage] is then its page. */
    val file: String? = null,
)

/** A PDF a link can go to: where it is (a URI) and its name. */
data class LinkableFile(val uri: String, val name: String)

/**
 * The relative file name a link to the PDF called [name] carries (#334): the name itself, when it
 * is a `.pdf` with no folder, drive or control character in it (the engine refuses those), else null.
 */
internal fun linkFileName(name: String): String? {
    val n = name.trim()
    if (n.isEmpty() || n.any { it == '/' || it == '\\' || it == ':' || it.isISOControl() }) return null
    return n.takeIf { it.lowercase().endsWith(".pdf") }
}

/** The file name a link names, without folders. */
internal fun linkFileBaseName(file: String): String = file.substringAfterLast('/').substringAfterLast('\\').trim()

/** The file among [files] a link naming [file] opens: the one with that name, ignoring case and folders. */
internal fun linkedFileFor(file: String, files: List<LinkableFile>): LinkableFile? {
    val name = linkFileBaseName(file)
    if (name.isEmpty()) return null
    return files.firstOrNull { it.name.trim().equals(name, ignoreCase = true) }
}

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

/** Where a link goes in its sheet. */
private enum class LinkTargetKind { Page, File, Web }

/** Where a link goes: a page of this document (typed or from its bookmarks), a page of another PDF, or a web address; then Save (and Delete). */
@Composable
fun LinkSheet(vm: AppViewModel, edit: LinkEdit) {
    val c = pdfColors
    val doc = edit.doc
    var kind by remember(edit) {
        mutableStateOf(if (edit.file != null) LinkTargetKind.File else if (edit.url != null) LinkTargetKind.Web else LinkTargetKind.Page)
    }
    var url by remember(edit) { mutableStateOf(edit.url.orEmpty()) }
    var page by remember(edit) { mutableStateOf(edit.toPage?.let { (it + 1).toString() } ?: "") }
    var file by remember(edit) { mutableStateOf(edit.file) }
    var wrong by remember(edit) { mutableStateOf(false) }
    var showBookmarks by remember(edit) { mutableStateOf(false) }
    // PDFs to link to and this document's bookmarks: read once the sheet asks for them
    val files by produceState<List<LinkableFile>?>(null, edit, kind == LinkTargetKind.File) {
        if (kind == LinkTargetKind.File && value == null) value = withContext(Dispatchers.IO) { vm.linkableFiles(doc.source) }
    }
    val bookmarks by produceState<List<Pair<Int, String>>?>(null, edit, showBookmarks) {
        if (showBookmarks && value == null) value = linkBookmarks(vm, doc)
    }
    fun choose(k: LinkTargetKind) {
        if (k != kind) page = ""
        kind = k
        wrong = false
    }
    PdfSheet(onDismiss = { vm.linkEdit = null }, dim = true) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                stringResource(if (edit.index == null) R.string.link_new else R.string.link_edit),
                color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
            SheetRow(LucideIcons.FileText, stringResource(R.string.link_to_page), { choose(LinkTargetKind.Page) }, checked = kind == LinkTargetKind.Page)
            SheetRow(LucideIcons.FileSymlink, stringResource(R.string.link_to_file), { choose(LinkTargetKind.File) }, checked = kind == LinkTargetKind.File)
            SheetRow(LucideIcons.Globe, stringResource(R.string.link_to_web), { choose(LinkTargetKind.Web) }, checked = kind == LinkTargetKind.Web)
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                when (kind) {
                    LinkTargetKind.Page -> {
                        PdfField(
                            page, { page = it.filter(Char::isDigit).take(6); wrong = false }, stringResource(R.string.link_page_hint, doc.pageCount), error = wrong,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                    LinkTargetKind.File -> {
                        val list = files
                        when {
                            list == null -> {}
                            list.isEmpty() -> Text(stringResource(R.string.link_file_none), color = c.textSecondary, fontSize = 14.sp)
                            else -> {
                                Text(stringResource(R.string.link_file_pick), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
                                // the file the link names now, even when it isn't one of these
                                val current = file?.takeIf { f -> list.none { linkFileName(it.name).equals(f, ignoreCase = true) } }
                                if (current != null) FileChoice(current, true) {}
                                for (f in list.take(12)) {
                                    val name = linkFileName(f.name) ?: continue
                                    FileChoice(name, name.equals(file, ignoreCase = true)) { file = name; wrong = false }
                                }
                                Spacer(Modifier.height(8.dp))
                                PdfField(
                                    page, { page = it.filter(Char::isDigit).take(6); wrong = false }, stringResource(R.string.link_file_page_hint), error = wrong,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                )
                                Text(stringResource(R.string.link_file_same_folder), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                    LinkTargetKind.Web -> {
                        PdfField(
                            url, { url = it; wrong = false }, stringResource(R.string.link_web_hint), error = wrong,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                    }
                }
                if (wrong) {
                    val message = when (kind) {
                        LinkTargetKind.Page -> R.string.link_page_wrong
                        LinkTargetKind.File -> R.string.link_file_wrong
                        LinkTargetKind.Web -> R.string.link_web_wrong
                    }
                    Text(stringResource(message), color = c.red, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
            if (kind == LinkTargetKind.Page) {
                SheetRow(LucideIcons.Bookmark, stringResource(R.string.link_from_bookmark), { showBookmarks = !showBookmarks }, chevron = !showBookmarks)
                if (showBookmarks) {
                    val list = bookmarks
                    if (list != null && list.isEmpty()) {
                        Text(stringResource(R.string.link_bookmarks_none), color = c.textSecondary, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                    for ((p, title) in list.orEmpty().take(200)) {
                        SheetRow(null, "$title · ${p + 1}", { page = (p + 1).toString(); wrong = false; showBookmarks = false }, checked = page == (p + 1).toString())
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
            if (edit.index != null) {
                SheetTextButton(stringResource(R.string.common_delete), c.red) { vm.deleteLink(edit) }
            }
            SheetTextButton(stringResource(R.string.common_cancel), c.textSecondary) { vm.linkEdit = null }
            SheetTextButton(stringResource(R.string.link_save), c.accent) {
                when (kind) {
                    LinkTargetKind.Page -> {
                        val p = parseLinkPage(page, doc.pageCount)
                        if (p == null) wrong = true else vm.saveLink(edit, url = null, toPage = p)
                    }
                    LinkTargetKind.File -> {
                        val f = file
                        val p = parseLinkPage(page, Int.MAX_VALUE)
                        if (f == null || p == null) wrong = true else vm.saveLink(edit, url = null, toPage = p, file = f)
                    }
                    LinkTargetKind.Web -> {
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
private fun FileChoice(name: String, checked: Boolean, onClick: () -> Unit) {
    SheetRow(LucideIcons.File, name, onClick, checked = checked, modifier = Modifier.padding(start = 0.dp))
}

/** This document's bookmarks, flattened in reading order: (0-based page, title). */
private suspend fun linkBookmarks(vm: AppViewModel, doc: OpenDocument): List<Pair<Int, String>> {
    val eng = vm.engine ?: return emptyList()
    val o = runCatching { eng.tool("bookmark_list", mapOf("doc" to doc.id)) }.getOrNull() ?: return emptyList()
    return flattenLinkBookmarks(o.optJSONArray("bookmarks"))
}

/** Bookmarks with a page, depth first, as `bookmark_list` nests them. */
internal fun flattenLinkBookmarks(a: org.json.JSONArray?): List<Pair<Int, String>> {
    val out = ArrayList<Pair<Int, String>>()
    fun walk(a: org.json.JSONArray?) {
        a ?: return
        for (i in 0 until a.length()) {
            val b = a.optJSONObject(i) ?: continue
            val title = b.optString("title").trim()
            val page = b.optInt("page", 0)
            if (title.isNotEmpty() && page >= 1) out += (page - 1) to title
            walk(b.optJSONArray("children"))
        }
    }
    walk(a)
    return out
}

@Composable
private fun SheetTextButton(label: String, color: Color, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) { Text(label, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
}

/**
 * Links over handwriting (#334): each link whose area is mostly covered by an ink comment on its
 * page, as (page, link rect). Their badge tells a linked note from plain ink.
 */
internal fun linkedInkRects(
    links: Map<Int, List<io.github.artcraftandroid.pdfcraft.core.LinkInfo>>,
    comments: List<io.github.artcraftandroid.pdfcraft.core.CommentInfo>,
): List<Pair<Int, FloatArray>> {
    if (links.isEmpty()) return emptyList()
    val ink = comments.filter { it.subtype == "Ink" && it.rect.size == 4 && !it.hidden }.groupBy { it.page.toInt() }
    if (ink.isEmpty()) return emptyList()
    val out = ArrayList<Pair<Int, FloatArray>>()
    for ((page, list) in links) {
        val strokes = ink[page] ?: continue
        for (l in list) {
            val r = l.rect
            if (r.size != 4) continue
            val area = (r[2] - r[0]) * (r[3] - r[1])
            if (area <= 0f) continue
            val covered = strokes.any { c ->
                val w = minOf(r[2], c.rect[2]) - maxOf(r[0], c.rect[0])
                val h = minOf(r[3], c.rect[3]) - maxOf(r[1], c.rect[1])
                w > 0f && h > 0f && w * h >= area * 0.5f
            }
            if (covered) out += page to r.toFloatArray()
        }
    }
    return out
}

/** A small chain badge at the top-right corner of each linked note. */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLinkBadges(doc: OpenDocument, badges: List<Pair<Int, FloatArray>>) {
    if (badges.isEmpty()) return
    val radius = 7.dp.toPx()
    val ring = Stroke(1.5.dp.toPx())
    for ((page, r) in badges) {
        val corner = doc.viewer.pageToScreen(page, r[2], r[1])
        if (corner.x < -radius || corner.y < -radius || corner.x > size.width + radius || corner.y > size.height + radius) continue
        drawCircle(Color(0xE62680EB), radius, corner)
        // two links of a chain, at 45°
        val d = radius * 0.28f
        val linkSize = Size(radius * 0.9f, radius * 0.55f)
        rotate(-45f, corner) {
            drawRoundRect(Color.White, Offset(corner.x - linkSize.width + d, corner.y - linkSize.height / 2f), linkSize, androidx.compose.ui.geometry.CornerRadius(linkSize.height / 2f), style = ring)
            drawRoundRect(Color.White, Offset(corner.x - d, corner.y - linkSize.height / 2f), linkSize, androidx.compose.ui.geometry.CornerRadius(linkSize.height / 2f), style = ring)
        }
    }
}
