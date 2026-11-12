package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.artcraftandroid.pdfcraft.core.SearchHit
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import io.github.artcraftandroid.pdfcraft.engine.Engine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Find in the document: the hits of the current query, found page by page from the page the
 * reader is on and wrapping round, so the first hit shows quickly in a long document. The hits
 * are kept in page order whatever order they were found in.
 */
@Stable
class SearchState(private val engine: Engine, private val doc: ULong, private val scope: CoroutineScope) {
    var query by mutableStateOf("")
        private set
    var caseSensitive by mutableStateOf(false)
    var wholeWords by mutableStateOf(false)

    /** Hits in reading order. */
    val hits = mutableStateListOf<SearchHit>()

    /** The hit the view shows (an index into [hits]), or -1. */
    var current by mutableIntStateOf(-1)
        private set

    /** Pages searched so far, and whether the search is still going. */
    var searched by mutableIntStateOf(0)
        private set
    var running by mutableStateOf(false)
        private set

    private var job: Job? = null

    /**
     * Search for [text] over [pageCount] pages starting at [fromPage]; [onFirst] is called with
     * the first hit found (so the view can go there).
     */
    fun run(text: String, pageCount: Int, fromPage: Int, onFirst: (SearchHit) -> Unit) {
        job?.cancel()
        query = text
        hits.clear()
        current = -1
        searched = 0
        if (text.isBlank() || pageCount <= 0) {
            running = false
            return
        }
        running = true
        val case = caseSensitive
        val whole = wholeWords
        job = scope.launch {
            var announced = false
            // #87 #88: one summary line per search, also when a new search or clear() stops it
            val started = android.os.SystemClock.elapsedRealtime()
            var firstHitMs = -1L
            var finished = false
            var pagesRead = 0
            var hitCount = 0
            try {
                for (k in 0 until pageCount) {
                    if (!isActive) return@launch
                    val page = (fromPage + k) % pageCount
                    val found = runCatching {
                        withContext(engine.documentText) { engine.session.findInPage(doc, page.toUInt(), text, case, whole) }
                    }.getOrDefault(emptyList())
                    if (found.isNotEmpty()) {
                        // keep reading order: insert this page's hits where they belong
                        val at = insertionPoint(hits, page)
                        hits.addAll(at, found)
                        hitCount += found.size
                        if (current >= at) current += found.size
                        if (!announced) {
                            announced = true
                            firstHitMs = android.os.SystemClock.elapsedRealtime() - started
                            current = at
                            onFirst(found.first())
                        }
                    }
                    searched = k + 1
                    pagesRead = k + 1
                }
                finished = true
                running = false
            } finally {
                if (Diag.enabled()) {
                    Diag.event(
                        DiagArea.Text, "search", "pages" to pageCount, "searched" to pagesRead, "hits" to hitCount,
                        "ms" to android.os.SystemClock.elapsedRealtime() - started, "first_hit_ms" to firstHitMs, "finished" to finished,
                    )
                }
            }
        }
    }

    /** Step to the next (or previous) hit, wrapping round; the hit to show. */
    fun step(forward: Boolean): SearchHit? {
        if (hits.isEmpty()) return null
        current = if (current < 0) 0 else (current + if (forward) 1 else -1).mod(hits.size)
        return hits[current]
    }

    fun select(index: Int): SearchHit? {
        if (index !in hits.indices) return null
        current = index
        return hits[index]
    }

    fun clear() {
        job?.cancel()
        query = ""
        hits.clear()
        current = -1
        searched = 0
        running = false
    }

    val currentHit: SearchHit? get() = hits.getOrNull(current)
}

/**
 * The pages checked for text when a search finds nothing: every page of a short document, else
 * [max] pages spread evenly from the first to the last.
 */
fun searchTextSample(pageCount: Int, max: Int = 40): List<Int> {
    if (pageCount <= 0) return emptyList()
    if (pageCount <= max) return (0 until pageCount).toList()
    return (0 until max).map { i -> (i.toLong() * (pageCount - 1) / (max - 1)).toInt() }.distinct()
}

/** The results list's rows: a page heading before the first hit of each page, then the hits. */
sealed interface SearchRow {
    data class Page(val page: Int, val count: Int) : SearchRow
    data class Hit(val index: Int, val page: Int, val context: String) : SearchRow
}

fun searchRows(hits: List<SearchHit>): List<SearchRow> = buildList {
    var i = 0
    while (i < hits.size) {
        val page = hits[i].range.page.toInt()
        var j = i
        while (j < hits.size && hits[j].range.page.toInt() == page) j++
        add(SearchRow.Page(page, j - i))
        for (k in i until j) add(SearchRow.Hit(k, page, hits[k].context.trim()))
        i = j
    }
}

/** Where hits of [page] go in [hits], which are in page order: after every hit of an earlier or the same page (#98). */
internal fun insertionPoint(hits: List<SearchHit>, page: Int): Int {
    var lo = 0
    var hi = hits.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (hits[mid].range.page.toInt() > page) hi = mid else lo = mid + 1
    }
    return lo
}
