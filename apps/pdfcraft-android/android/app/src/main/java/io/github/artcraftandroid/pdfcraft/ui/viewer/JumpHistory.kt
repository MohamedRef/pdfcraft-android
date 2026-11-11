package io.github.artcraftandroid.pdfcraft.ui.viewer

/** A place in the document to return to: a page and how far down it the screen's top was, in points. */
data class JumpSpot(val page: Int, val yPoints: Float)

/**
 * Where jumps (contents, bookmarks, links, Go to page) came from, so the reader can go back to
 * each in turn and forward again, as a browser does. A new jump clears the forward list. At most
 * [limit] places are kept each way; the oldest go first.
 */
class JumpHistory(private val limit: Int = LIMIT) {
    private val back = ArrayDeque<JumpSpot>()
    private val forward = ArrayDeque<JumpSpot>()

    val canGoBack: Boolean get() = back.isNotEmpty()
    val canGoForward: Boolean get() = forward.isNotEmpty()

    /** The place [goBack] returns to, if any. */
    val backTarget: JumpSpot? get() = back.lastOrNull()

    /** The place [goForward] returns to, if any. */
    val forwardTarget: JumpSpot? get() = forward.lastOrNull()

    /** A jump is leaving [from]. */
    fun push(from: JumpSpot) {
        if (back.lastOrNull() != from) back.addLast(from)
        while (back.size > limit) back.removeFirst()
        forward.clear()
    }

    /** Go back from [current]: the place to show, or null when there is none. */
    fun goBack(current: JumpSpot): JumpSpot? {
        val to = back.removeLastOrNull() ?: return null
        forward.addLast(current)
        while (forward.size > limit) forward.removeFirst()
        return to
    }

    /** Go forward from [current] again after [goBack]: the place to show, or null. */
    fun goForward(current: JumpSpot): JumpSpot? {
        val to = forward.removeLastOrNull() ?: return null
        back.addLast(current)
        while (back.size > limit) back.removeFirst()
        return to
    }

    fun clear() {
        back.clear()
        forward.clear()
    }

    companion object {
        const val LIMIT = 10
    }
}

/** Parse a typed page number: the 0-based page, or null when it is not a number from 1 to [pageCount]. */
fun parsePageNumber(text: String, pageCount: Int): Int? {
    val n = text.trim().toIntOrNull() ?: return null
    return if (n in 1..pageCount) n - 1 else null
}
