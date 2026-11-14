package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.QuickTool
import io.github.artcraftandroid.pdfcraft.ui.viewer.QuickToolbarPrefs
import io.github.artcraftandroid.pdfcraft.ui.viewer.ToolCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home ▸ Tools and the viewer's toolbar with More tools hold the same tools (#539). */
class ToolListsTest {
    private val name: (QuickTool) -> String = { it.name }

    @Test
    fun homeToolsAndTheViewerHoldTheSameTools() {
        val home = QuickTool.listed(null, "", name)
        val viewer = QuickTool.DEFAULT_BAR + QuickToolbarPrefs.complete(emptyList(), QuickTool.DEFAULT_BAR)
        assertEquals(home.toSet(), viewer.toSet())
        assertEquals(QuickTool.entries.size, home.size)
        assertEquals(QuickTool.entries.size, viewer.size)
        // the tools the review found in only one of the two lists
        val onceMissing = listOf(
            QuickTool.Draw, QuickTool.Highlight, QuickTool.Text, QuickTool.Redact, QuickTool.PrepareForm, QuickTool.Measure,
            QuickTool.Accessibility, QuickTool.NoteMargin, QuickTool.Ocr, QuickTool.ScanEnhance, QuickTool.OcrReview, QuickTool.Handwriting,
            QuickTool.Compare, QuickTool.Bates, QuickTool.OcrBatch, QuickTool.Split, QuickTool.PageMarks, QuickTool.ScanRecent,
            QuickTool.Unlock, QuickTool.Protect,
        )
        for (t in onceMissing) assertTrue("$t on Home", t in home)
        for (t in onceMissing) assertTrue("$t in the viewer", t in viewer)
    }

    @Test
    fun allIsByCategoryThenByName() {
        val all = QuickTool.listed(null, "", name)
        val expected = QuickTool.entries.sortedWith(compareBy<QuickTool>({ it.category.ordinal }, { it != QuickTool.Scan }, { it.name.lowercase() }))
        assertEquals(expected, all)
        // every category has tools, and the scanner's come first
        for (c in ToolCategory.entries) assertTrue("$c has tools", all.any { it.category == c })
        assertEquals(QuickTool.Scan, all.first())
    }

    @Test
    fun aCategoryShowsOnlyItsTools() {
        val pages = QuickTool.listed(ToolCategory.Pages, "", name)
        assertTrue(pages.isNotEmpty())
        assertTrue(pages.all { it.category == ToolCategory.Pages })
        assertTrue(QuickTool.Split in pages && QuickTool.Organize in pages)
    }

    @Test
    fun searchMatchesNamesAndCategories() {
        assertEquals(listOf(QuickTool.Compare), QuickTool.listed(null, " compare ", name))
        assertEquals(listOf(QuickTool.Redact), QuickTool.listed(null, "REDACT", name))
        // a category's name finds its tools
        val byCategory = QuickTool.listed(null, "pages", name, { it.key })
        assertEquals(QuickTool.listed(ToolCategory.Pages, "", name), byCategory)
        assertTrue(QuickTool.listed(null, "no such tool", name).isEmpty())
    }

    @Test
    fun scanIsFirstOnHomeAndStartsWithoutAFile() {
        assertEquals(QuickTool.Scan, QuickTool.HOME_START.first())
        assertTrue(!QuickTool.Scan.needsFile)
        assertEquals(QuickTool.HOME_START.size, QuickTool.HOME_START.toSet().size)
        // tools that work on one file ask for it first; the rest start right away
        assertTrue(QuickTool.Draw.needsFile && QuickTool.Redact.needsFile && QuickTool.Compare.needsFile)
        assertTrue(QuickTool.WITHOUT_FILE.none { it.needsFile })
    }
}
