package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.SELECTION_MENU_FIRST_ROWS
import io.github.artcraftandroid.pdfcraft.ui.viewer.SELECTION_MENU_ICONS_DP
import io.github.artcraftandroid.pdfcraft.ui.viewer.SELECTION_MENU_PADDING_DP
import io.github.artcraftandroid.pdfcraft.ui.viewer.SELECTION_MENU_ROW_DP
import io.github.artcraftandroid.pdfcraft.ui.viewer.SelectionAction
import io.github.artcraftandroid.pdfcraft.ui.viewer.SelectionAction.*
import io.github.artcraftandroid.pdfcraft.ui.viewer.SelectionMenuEntry
import io.github.artcraftandroid.pdfcraft.ui.viewer.selectionFindQuery
import io.github.artcraftandroid.pdfcraft.ui.viewer.selectionMenuEntries
import io.github.artcraftandroid.pdfcraft.ui.viewer.selectionMenuRows
import io.github.artcraftandroid.pdfcraft.ui.viewer.splitSelectionMenu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The menu by selected text (#523): its order, what is offered, and its height. */
class SelectionMenuTest {
    private val all = selectionMenuRows(canCopy = true, canAnnotate = true, canEditText = true, canFind = true)

    @Test fun copyIsTheFirstRowThenTheMostUsedThenTheEditingRows() {
        assertEquals(Copy, all.first())
        assertEquals(listOf(Copy, SelectAll, Find, Share, Replace, Insert, EditText, Card, Cloze, ReadAloud), all)
    }

    @Test fun rowsFollowWhatTheDocumentAllows() {
        // no copying: neither Copy nor Share (sharing is copying out of the PDF)
        val noCopy = selectionMenuRows(canCopy = false, canAnnotate = true, canEditText = true, canFind = true)
        assertEquals(SelectAll, noCopy.first())
        assertTrue(Copy !in noCopy && Share !in noCopy)
        val readOnly = selectionMenuRows(canCopy = true, canAnnotate = false, canEditText = false, canFind = false)
        assertEquals(listOf(Copy, SelectAll, Share, Card, Cloze, ReadAloud), readOnly)
    }

    @Test fun onAPhoneTheFourMostUsedShowAndTheRestAreBehindMore() {
        // a 360 x 640 dp phone: about 560 dp of page area, so 280 dp for the menu
        val (shown, more) = splitSelectionMenu(all, 280f)
        assertEquals(listOf(Copy, SelectAll, Find, Share), shown)
        assertEquals(all.drop(SELECTION_MENU_FIRST_ROWS), more)
    }

    @Test fun theMenuIsNeverTallerThanHalfThePageArea() {
        for (half in listOf(80f, 140f, 200f, 280f, 400f, 900f)) {
            val (shown, more) = splitSelectionMenu(all, half)
            // More is an icon in the icon row: it adds no height
            val height = SELECTION_MENU_ICONS_DP + SELECTION_MENU_PADDING_DP + shown.size * SELECTION_MENU_ROW_DP
            assertTrue("$half dp: $height dp", shown.isEmpty() || height <= half)
            assertEquals(all, shown + more)
            // order is kept: what shows is always the start of the list
            assertEquals(all.take(shown.size), shown)
        }
        // a short landscape page: Copy still comes first
        assertEquals(listOf(Copy, SelectAll), splitSelectionMenu(all, 180f).first)
    }

    @Test fun aShortListNeedsNoMore() {
        val few = listOf(Copy, SelectAll, Card)
        assertEquals(few to emptyList<SelectionAction>(), splitSelectionMenu(few, 400f))
    }

    @Test fun findUsesTheWordsOnOneLine() {
        assertEquals("cell membrane potential", selectionFindQuery("  cell\nmembrane \t potential \n"))
        assertTrue(selectionFindQuery("x".repeat(500)).length <= 120)
        assertEquals("", selectionFindQuery(" \n "))
    }

    @Test fun otherAppsTextActionsComeBehindMoreAfterTheAppsOwnRows() {
        val (shown, more) = selectionMenuEntries(all, otherApps = 2, canCopy = true, maxHeightDp = 280f)
        assertEquals(listOf(Copy, SelectAll, Find, Share).map { SelectionMenuEntry.Own(it) }, shown)
        assertEquals(all.drop(4).map { SelectionMenuEntry.Own(it) } + listOf(SelectionMenuEntry.OtherApp(0), SelectionMenuEntry.OtherApp(1)), more)
        // even when the app's own rows all fit, the other apps' actions still sit behind More
        val few = listOf(Copy, SelectAll)
        val (s2, m2) = selectionMenuEntries(few, otherApps = 1, canCopy = true, maxHeightDp = 900f)
        assertEquals(few.map { SelectionMenuEntry.Own(it) }, s2)
        assertEquals(listOf(SelectionMenuEntry.OtherApp(0)), m2)
    }

    @Test fun otherAppsTextActionsNeedCopyingAllowed() {
        val rows = selectionMenuRows(canCopy = false, canAnnotate = true, canEditText = true, canFind = true)
        val (shown, more) = selectionMenuEntries(rows, otherApps = 3, canCopy = false, maxHeightDp = 280f)
        assertTrue((shown + more).none { it is SelectionMenuEntry.OtherApp })
    }
}
