package io.github.artcraftandroid.pdfcraft.ui.viewer

/**
 * The rows of the menu by selected text (#523), in the order they show: what a selection is used
 * for most (Copy, Select all, Find in document, Share) first, then the rows that change the
 * document, then flashcards and Read aloud. [diag] is the `action` the diagnostics log records.
 */
enum class SelectionAction(val diag: String) {
    Copy("copy"),
    SelectAll("select_all"),
    Find("find"),
    Share("share"),
    Replace("replace"),
    Insert("insert"),
    EditText("edit_text"),
    Card("card"),
    Cloze("cloze"),
    ReadAloud("read_aloud"),
}

/** The rows offered for a selection, in menu order. */
fun selectionMenuRows(canCopy: Boolean, canAnnotate: Boolean, canEditText: Boolean, canFind: Boolean): List<SelectionAction> =
    SelectionAction.entries.filter {
        when (it) {
            // sharing the text is copying it out of the PDF, so it follows the same permission
            SelectionAction.Copy, SelectionAction.Share -> canCopy
            SelectionAction.Find -> canFind
            SelectionAction.Replace, SelectionAction.Insert -> canAnnotate
            SelectionAction.EditText -> canEditText
            else -> true
        }
    }

/** The rows shown before More: at most this many, the most used ones. */
const val SELECTION_MENU_FIRST_ROWS = 4

/** Heights (dp) the menu is laid out with: the icon row with its labels, a text row, the padding. */
const val SELECTION_MENU_ICONS_DP = 62
const val SELECTION_MENU_ROW_DP = 48
const val SELECTION_MENU_PADDING_DP = 17

/**
 * The rows shown at once, and those behind More (an icon at the end of the icon row, so it takes
 * no row). The menu is never taller than [maxHeightDp] (half the page area): rows that do not fit
 * move behind More, and the rows used less always do when there are more than fit.
 */
fun splitSelectionMenu(rows: List<SelectionAction>, maxHeightDp: Float): Pair<List<SelectionAction>, List<SelectionAction>> {
    val room = ((maxHeightDp - SELECTION_MENU_ICONS_DP - SELECTION_MENU_PADDING_DP) / SELECTION_MENU_ROW_DP).toInt().coerceAtLeast(0)
    // everything fits with no More
    if (rows.size <= minOf(room, SELECTION_MENU_FIRST_ROWS)) return rows to emptyList()
    val shown = minOf(SELECTION_MENU_FIRST_ROWS, room, rows.size)
    return rows.take(shown) to rows.drop(shown)
}

/** The selected words as a Find query: whitespace (line breaks too) made single spaces, kept short. */
fun selectionFindQuery(text: String, maxChars: Int = 120): String =
    text.trim().replace(Regex("\\s+"), " ").take(maxChars).trimEnd()
