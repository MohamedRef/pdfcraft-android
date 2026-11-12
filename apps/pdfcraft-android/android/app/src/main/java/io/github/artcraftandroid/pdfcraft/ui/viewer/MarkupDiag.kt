package io.github.artcraftandroid.pdfcraft.ui.viewer

import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument

/**
 * Diagnostics lines for comments, markup, links, measuring and study tape/quiz (the `markup`
 * area, docs/ANDROID_DIAGNOSTICS_LOG.md). Only counts, sizes, kinds and outcomes: never a
 * comment's text, a link's address, a measurement's label or the highlighted words.
 * Failed engine tools are already logged by `Engine.tool` (`[tool]`, `[error]`).
 */
internal object MarkupDiag {
    private fun e(name: String, doc: OpenDocument, vararg fields: Pair<String, Any?>) =
        Diag.event(DiagArea.Markup, name, "doc" to doc.diagNumber, *fields)

    // comments list (#196 #169 #158)
    fun commentsExport(doc: OpenDocument, share: Boolean, bytes: Long, ms: Long) = e("comments_export", doc, "share" to share, "bytes" to bytes, "ms" to ms)
    fun commentsImport(doc: OpenDocument, kind: String, bytes: Long, added: Int?, ms: Long) =
        e("comments_import", doc, "kind" to kind, "bytes" to bytes, "added" to added, "ms" to ms)
    fun commentsSummary(doc: OpenDocument, sort: String, ms: Long) = e("comments_summary", doc, "sort" to sort, "ms" to ms)
    fun commentsCopy(doc: OpenDocument, comments: Int, pages: Int) = e("comments_copy", doc, "comments" to comments, "pages" to pages)
    fun commentsPaste(doc: OpenDocument, pages: Int, skipped: Int) = e("comments_paste", doc, "pages" to pages, "skipped_pages" to skipped)
    fun commentStatus(doc: OpenDocument, status: String) = e("comment_status", doc, "status" to status.lowercase())
    fun commentLock(doc: OpenDocument, locked: Boolean) = e("comment_lock", doc, "locked" to locked)

    // markup (#197 #134 #170 #133)
    fun followUp(doc: OpenDocument, action: String) = e("markup_follow_up", doc, "action" to action)
    fun areaHighlight(doc: OpenDocument, rect: FloatArray) = e("area_highlight", doc, "w_pt" to (rect[2] - rect[0]).toInt(), "h_pt" to (rect[3] - rect[1]).toInt())
    fun textEdit(doc: OpenDocument, kind: String, lines: Int) = e("text_suggestion", doc, "kind" to kind, "lines" to lines)
    fun shape(doc: OpenDocument, type: String, points: Int) = e("shape", doc, "type" to type, "points" to points)

    // links (#179 #159)
    fun link(doc: OpenDocument, action: String, target: String?, from: String) = e("link", doc, "action" to action, "target" to target, "from" to from)
    fun linksFromUrls(doc: OpenDocument, created: Int, ms: Long) = e("links_from_urls", doc, "created" to created, "ms" to ms)
    fun linksRemoved(doc: OpenDocument) = e("links_remove", doc)

    // measure (#135 #180)
    fun measurePoint(doc: OpenDocument, kind: String, snapped: Boolean, points: Int, ms: Long) =
        e("measure_point", doc, "kind" to kind, "snapped" to snapped, "points" to points, "snap_ms" to ms)
    fun measureSaved(doc: OpenDocument, kind: String, points: Int) = e("measure_saved", doc, "kind" to kind, "points" to points)
    fun measureScale(doc: OpenDocument, method: String, unit: String) = e("measure_scale", doc, "method" to method, "unit" to unit.lowercase())
    fun measureExport(doc: OpenDocument, count: Int) = e("measure_export", doc, "count" to count)

    // study (#143)
    fun tapeAdd(doc: OpenDocument, color: Int) = e("tape_add", doc, "color" to TAPE_COLORS.indexOf(color))
    fun tapeToggle(doc: OpenDocument, lifted: Boolean, via: String) = e("tape_toggle", doc, "lifted" to lifted, "via" to via)
    fun tapeAll(doc: OpenDocument, lifted: Boolean, tapes: Int) = e("tape_all", doc, "lifted" to lifted, "tapes" to tapes)
    fun tapeRemoveAll(doc: OpenDocument, tapes: Int, pages: Int) = e("tape_remove_all", doc, "tapes" to tapes, "pages" to pages)
    fun quizStart(doc: OpenDocument, scope: String, items: Int) = e("quiz_start", doc, "scope" to scope, "items" to items)
    fun quizEnd(doc: OpenDocument, q: QuizSession?) =
        e("quiz_end", doc, "items" to (q?.items?.size ?: 0), "answered" to (q?.answers?.size ?: 0), "known" to (q?.known ?: 0))
}
