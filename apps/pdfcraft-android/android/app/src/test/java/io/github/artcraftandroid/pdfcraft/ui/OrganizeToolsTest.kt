package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.labelPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganizeToolsTest {
    private val front = listOf("i", "ii", "iii", "1", "2", "3", "A-1")

    @Test fun plainNumbersAreNotCustomLabels() {
        assertFalse(hasCustomLabels(listOf("1", "2", "3")))
        assertTrue(hasCustomLabels(front))
    }

    @Test fun goToPageMatchesLabelsBeforeNumbers() {
        assertEquals(1, pageForEntry(front, "ii"))
        assertEquals(1, pageForEntry(front, "II"))
        assertEquals(6, pageForEntry(front, "a-1"))
        // "1" is the page labelled 1 (the fourth page), as in Acrobat
        assertEquals(3, pageForEntry(front, "1"))
        // no label "7": the seventh page
        assertEquals(6, pageForEntry(front, "7"))
        assertNull(pageForEntry(front, "8"))
        assertNull(pageForEntry(front, "0"))
        assertNull(pageForEntry(front, " "))
        assertEquals(2, pageForEntry(listOf("1", "2", "3"), " 3 "))
    }

    @Test fun splitModesMapToEngineArguments() {
        assertEquals(mapOf("every" to 2), SplitMode.Every(2).args())
        // the first page is no cut; pages are 1-based, sorted, once each
        assertEquals(mapOf("before" to listOf(3, 5)), SplitMode.Before(listOf(4, 0, 2, 4)).args())
        assertEquals(mapOf("bookmarks" to true), SplitMode.AtBookmarks.args())
        assertEquals(mapOf("max_mb" to 2.5), SplitMode.MaxSize(2.5).args())
    }

    @Test fun splitNeedsMoreThanOnePart() {
        assertFalse(SplitMode.Every(5).usable(5))
        assertTrue(SplitMode.Every(2).usable(5))
        assertFalse(SplitMode.Before(listOf(0)).usable(5))
        assertTrue(SplitMode.Before(listOf(0, 3)).usable(5))
        assertFalse(SplitMode.MaxSize(0.0).usable(5))
    }

    @Test fun labelPreviewReadsAsThePdfWill() {
        assertEquals("1, 2, 3, …", labelPreview(LabelStyle.Decimal, "", 1))
        assertEquals("iv, v, vi, …", labelPreview(LabelStyle.LowerRoman, "", 4))
        assertEquals("A-1, A-2, A-3, …", labelPreview(LabelStyle.Decimal, "A-", 1))
        assertEquals("Y, Z, AA, …", labelPreview(LabelStyle.UpperAlpha, "", 25))
        assertEquals("Cover, Cover, Cover", labelPreview(LabelStyle.None, "Cover", 1))
    }
}
