package io.github.artcraftandroid.pdfcraft.ui.viewer

import io.github.artcraftandroid.pdfcraft.core.CommentInfo

/** The Comments list's kind chips (#196): what sort of comment a thread starts with. */
enum class CommentKindFilter { All, Notes, Highlights, Drawings, Mine }

/** The Comments list's review-status chips (#169). [Open] is a comment nobody has set a status on. */
enum class CommentStatusFilter { Any, Open, Accepted, Rejected, Completed }

/** Comments drawn by hand or with a shape tool: what fills a page of handwritten notes. */
private val DRAWINGS = setOf("Ink", "Line", "Square", "Circle", "Polygon", "PolyLine")

/** Text markups: what Highlights shows. */
private val HIGHLIGHTS = setOf("Highlight", "Underline", "StrikeOut", "Squiggly")

/** A drawing with nothing written on it: one of the rows a page of handwriting fills the list with. */
internal fun isDrawing(c: CommentInfo): Boolean = c.subtype in DRAWINGS && c.contents.isNullOrBlank()

/** One thread in the list: the comment and its replies (status replies left out). */
typealias CommentThread = List<CommentInfo>

/**
 * The review status [status] (as `comment_list` reports it: `Accepted`, `Rejected`, `Cancelled`,
 * `Completed`, or none) passes [filter].
 */
internal fun statusPasses(status: String?, filter: CommentStatusFilter): Boolean = when (filter) {
    CommentStatusFilter.Any -> true
    CommentStatusFilter.Open -> status.isNullOrBlank() || status.equals("None", ignoreCase = true)
    CommentStatusFilter.Accepted -> status.equals("Accepted", ignoreCase = true)
    CommentStatusFilter.Rejected -> status.equals("Rejected", ignoreCase = true)
    CommentStatusFilter.Completed -> status.equals("Completed", ignoreCase = true)
}

/** [thread] passes the kind chip [kind]; Mine is the comments [me] wrote (the saved author name). */
internal fun kindPasses(thread: CommentThread, kind: CommentKindFilter, me: String?): Boolean {
    val c = thread.first()
    return when (kind) {
        CommentKindFilter.All -> true
        CommentKindFilter.Highlights -> c.subtype in HIGHLIGHTS
        CommentKindFilter.Drawings -> c.subtype in DRAWINGS || c.subtype == "Stamp"
        CommentKindFilter.Notes -> c.subtype !in HIGHLIGHTS && c.subtype !in DRAWINGS && c.subtype != "Stamp" ||
            // a drawing someone wrote on is a comment too
            (c.subtype in DRAWINGS && !c.contents.isNullOrBlank())
        CommentKindFilter.Mine -> !me.isNullOrBlank() && thread.any { it.author?.trim().equals(me.trim(), ignoreCase = true) }
    }
}

/** [thread] holds [query] in a comment's or reply's text, or its author (case and accents aside). */
internal fun queryMatches(thread: CommentThread, query: String): Boolean {
    val q = foldForSearch(query.trim())
    if (q.isEmpty()) return true
    return thread.any { c ->
        foldForSearch(c.contents.orEmpty()).contains(q) || foldForSearch(c.author.orEmpty()).contains(q)
    }
}

/** Lower case without accents, so "resume" finds "Résumé". */
internal fun foldForSearch(s: String): String =
    java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

/** What the list's filters keep. [hiddenAuthors]: the authors whose chips are off (#158). */
data class CommentFilters(
    val query: String = "",
    val kind: CommentKindFilter = CommentKindFilter.All,
    val status: CommentStatusFilter = CommentStatusFilter.Any,
    val hiddenAuthors: Set<String> = emptySet(),
)

/** The name shown for a comment's author ([guest] when it has none): what the author chips use. */
internal fun authorKey(c: CommentInfo, guest: String): String = c.author?.trim()?.takeIf { it.isNotEmpty() } ?: guest

/**
 * The threads the list shows under [f]. [statusOf] gives a thread's review status; [me] is the
 * saved author name.
 */
internal fun filterThreads(
    threads: List<CommentThread>,
    f: CommentFilters,
    me: String?,
    guest: String,
    statusOf: (CommentInfo) -> String?,
): List<CommentThread> = threads.filter { t ->
    authorKey(t.first(), guest) !in f.hiddenAuthors &&
        kindPasses(t, f.kind, me) &&
        statusPasses(statusOf(t.first()), f.status) &&
        queryMatches(t, f.query)
}

/** A row of one page's part of the list: a thread, or several drawings folded into one row. */
sealed interface CommentListRow {
    data class Thread(val thread: CommentThread) : CommentListRow
    data class Drawings(val page: Int, val threads: List<CommentThread>) : CommentListRow
}

/**
 * One page's threads as rows. Unless [expanded], drawings nobody wrote on or replied to fold into
 * one "N drawings" row after the page's written comments, so a page of handwriting doesn't bury
 * the few notes on it. A single drawing keeps its own row.
 */
internal fun pageRows(page: Int, threads: List<CommentThread>, expanded: Boolean): List<CommentListRow> {
    if (expanded) return threads.map { CommentListRow.Thread(it) }
    val (plain, rest) = threads.partition { it.size == 1 && isDrawing(it.first()) }
    if (plain.size < 2) return threads.map { CommentListRow.Thread(it) }
    return rest.map { CommentListRow.Thread(it) } + CommentListRow.Drawings(page, plain)
}

/**
 * The review status of each comment in a `comment_list` result, by (0-based page, 0-based
 * index). Comments nobody set a status on are left out.
 */
internal fun parseCommentStatuses(r: org.json.JSONObject): Map<Pair<Int, Int>, String> {
    val list = r.optJSONArray("comments") ?: return emptyMap()
    val out = HashMap<Pair<Int, Int>, String>()
    for (i in 0 until list.length()) {
        val c = list.optJSONObject(i) ?: continue
        val status = c.optString("status", "").takeIf { it.isNotBlank() && it != "null" && !it.equals("None", ignoreCase = true) } ?: continue
        val page = c.optInt("page", 0)
        val index = c.optInt("index", 0)
        if (page < 1 || index < 1) continue
        out[(page - 1) to (index - 1)] = status
    }
    return out
}
