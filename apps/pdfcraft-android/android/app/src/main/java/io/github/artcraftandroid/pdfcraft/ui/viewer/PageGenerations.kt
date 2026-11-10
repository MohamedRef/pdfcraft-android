package io.github.artcraftandroid.pdfcraft.ui.viewer

/**
 * Which edit generation each page's pixels belong to. A comment tool that names one page changes
 * only that page, so only its images are rendered again; every other page keeps its rendered
 * pages and tiles. Anything else (undo, page edits, tools without a page) renews every page.
 */
data class PageGenerations(val base: Long, val pages: Map<Int, Long> = emptyMap()) {
    fun of(page: Int): Long = pages[page] ?: base

    /**
     * After the document reached [generation]: renew only [touchedPage] (0-based) when the page
     * layout is unchanged and the page exists, else every page.
     */
    fun next(generation: Long, touchedPage: Int?, pageCount: Int, pagesChanged: Boolean): PageGenerations = when {
        generation == base && pages.isEmpty() -> this
        touchedPage != null && !pagesChanged && touchedPage in 0 until pageCount && generation > of(touchedPage) ->
            copy(pages = pages + (touchedPage to generation))
        else -> PageGenerations(generation)
    }
}

/** Engine tools whose `page` argument names the only page they change: comments on it. */
private val PAGE_LOCAL_TOOLS = setOf(
    "comment_add", "comment_edit", "comment_erase", "comment_delete", "comment_reply",
    "comment_set_status", "comment_mark", "comment_lock",
)

/** The one 0-based page [name] with [args] changes, or `null` when it may change others. */
fun pageTouchedBy(name: String, args: Map<String, Any?>): Int? {
    if (name !in PAGE_LOCAL_TOOLS) return null
    val page = when (val p = args["page"]) {
        is Int -> p
        is Long -> p.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
        is Number -> p.toDouble().takeIf { it == kotlin.math.floor(it) && it >= 1.0 && it <= Int.MAX_VALUE }?.toInt()
        else -> null
    } ?: return null
    return (page - 1).takeIf { it >= 0 }
}

/** The one page every call of a batch changes, or `null`. */
fun pageTouchedByBatch(name: String, argsList: List<Map<String, Any?>>): Int? {
    val pages = argsList.map { pageTouchedBy(name, it) ?: return null }.toSet()
    return pages.singleOrNull()
}
