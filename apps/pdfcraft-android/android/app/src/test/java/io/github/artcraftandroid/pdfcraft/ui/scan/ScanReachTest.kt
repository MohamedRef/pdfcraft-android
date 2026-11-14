package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.ui.ScanFilter
import io.github.artcraftandroid.pdfcraft.ui.ScanLab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every tool in reach (#516): the taps from the document screen to each tool. A change that puts
 * a tool deeper fails here.
 */
class ScanReachTest {
    /** The most taps each tool may take with the option on: two, three for a control inside Adjust or a Markup tool. */
    private val most = mapOf(
        ScanReachTool.Look to 2, ScanReachTool.Borders to 2, ScanReachTool.Rotate to 2, ScanReachTool.Retake to 2,
        ScanReachTool.Delete to 2, ScanReachTool.Markup to 2, ScanReachTool.MarkupTool to 3, ScanReachTool.AdjustControl to 3,
        ScanReachTool.ApplyAll to 3, ScanReachTool.ResetLook to 3, ScanReachTool.Compare to 3, ScanReachTool.Rename to 3,
        ScanReachTool.SelectPages to 1, ScanReachTool.Gallery to 2, ScanReachTool.ExtractText to 2, ScanReachTool.Print to 2,
        ScanReachTool.RecentScan to 1,
    )

    @Test
    fun everyToolIsWithinItsTapsWithTheOptionOn() {
        assertEquals(ScanReachTool.entries.toSet(), most.keys)
        for (tool in ScanReachTool.entries) {
            val taps = scanReach(tool, inReach = true).taps
            assertNotNull("$tool is not there before Save PDF", taps)
            assertTrue("$tool takes $taps taps, at most ${most[tool]}", taps!! <= most.getValue(tool))
        }
        // the accepted rule: a tool that changes a page is at most two taps, three inside Adjust or Markup
        for (tool in ScanReachTool.entries.filter { it.changesPage }) {
            val limit = if (tool == ScanReachTool.AdjustControl || tool == ScanReachTool.MarkupTool || tool == ScanReachTool.ApplyAll || tool == ScanReachTool.ResetLook) 3 else 2
            assertTrue(tool.id, scanReach(tool, true).taps!! <= limit)
        }
    }

    @Test
    fun offTheToolsAreWhereTheyWere() {
        // as the issue's table had them at db1cae27
        assertEquals(ScanReach(3, "more"), scanReach(ScanReachTool.Markup, inReach = false))
        assertEquals(4, scanReach(ScanReachTool.MarkupTool, inReach = false).taps)
        assertEquals(ScanReach(2, "bar"), scanReach(ScanReachTool.Delete, inReach = false))
        assertEquals(ScanReach(2, "menu"), scanReach(ScanReachTool.SelectPages, inReach = false))
        assertEquals(ScanReach(3, "tools"), scanReach(ScanReachTool.RecentScan, inReach = false))
        assertNull(scanReach(ScanReachTool.ExtractText, inReach = false).taps)
        assertNull(scanReach(ScanReachTool.Print, inReach = false).taps)
    }

    @Test
    fun markupTakesDeletesPlaceOnTheBar() {
        val on = scanEnhanceBarItems(inReach = true, markup = true)
        assertEquals(listOf(ScanEnhanceBarItem.Borders, ScanEnhanceBarItem.Markup, ScanEnhanceBarItem.Rotate, ScanEnhanceBarItem.Retake, ScanEnhanceBarItem.More), on)
        assertTrue(scanDeleteOnTopBar(inReach = true, markup = true))
        assertEquals(ScanReach(2, "top"), scanReach(ScanReachTool.Delete, inReach = true))
        // five icons, so nothing scrolls sideways
        assertEquals(5, on.size)
        // Markup is not in More when it is on the bar; Clear markup stays there
        assertFalse(scanEnhanceMoreShown(ScanEnhanceMore.Markup, markup = true, markupOnBar = true))
        assertTrue(scanEnhanceMoreShown(ScanEnhanceMore.ClearMarkup, markup = true, markupOnBar = true))
        assertTrue(scanEnhanceMoreShown(ScanEnhanceMore.Markup, markup = true, markupOnBar = false))
        // with Markup's own option off the bar is as before and Delete stays on it
        val noMarkup = scanEnhanceBarItems(inReach = true, markup = false)
        assertEquals(scanEnhanceBarItems(inReach = false, markup = false), noMarkup)
        assertFalse(scanDeleteOnTopBar(inReach = true, markup = false))
        assertNull(scanReach(ScanReachTool.Markup, inReach = true, markup = false).taps)
    }

    @Test
    fun theDocumentMenuHoldsWhatIsDoneToAWholeScan() {
        assertEquals(
            listOf(ScanDocMenuItem.Select, ScanDocMenuItem.Rename, ScanDocMenuItem.Gallery, ScanDocMenuItem.ExtractText, ScanDocMenuItem.Print),
            scanDocMenuItems(inReach = true),
        )
        assertEquals(listOf(ScanDocMenuItem.Select, ScanDocMenuItem.Rename, ScanDocMenuItem.Gallery), scanDocMenuItems(inReach = false))
        // Sign, Lock and To Word stay in the viewer (the owner's choice)
        assertFalse(ScanDocMenuItem.entries.any { it.id in setOf("sign", "lock", "word", "to_word") })
        assertTrue(scanDocSelectShown(true))
        assertFalse(scanDocSelectShown(false))
    }

    @Test
    fun markupOpensOnTheToolLastUsed() {
        val all = ScanMarkupTool.entries.toList()
        assertEquals(ScanMarkupTool.Highlighter, scanMarkupStartTool(ScanMarkupTool.Highlighter, all, inReach = true))
        assertEquals(ScanMarkupTool.Pen, scanMarkupStartTool(ScanMarkupTool.Highlighter, all, inReach = false))
        assertEquals(ScanMarkupTool.Pen, scanMarkupStartTool(null, all, inReach = true))
        // a page in black and white has no highlighter: the pen then
        val bilevel = scanMarkupTools(ScanFilter.LightText)
        assertEquals(ScanMarkupTool.Pen, scanMarkupStartTool(ScanMarkupTool.Highlighter, bilevel, inReach = true))
        assertEquals(ScanMarkupTool.Erase, scanMarkupStartTool(ScanMarkupTool.Erase, bilevel, inReach = true))
    }

    @Test
    fun theLogLineSaysTheToolTheWayAndTheTaps() {
        val m = scanToolOpenDiag(ScanReachTool.Markup, "bar", inReach = true, markup = true).toMap()
        assertEquals("markup", m["tool"])
        assertEquals("bar", m["from"])
        assertEquals(2, m["taps"])
        assertEquals(true, m["in_reach"])
        assertEquals(-1, scanToolOpenDiag(ScanReachTool.Print, "menu", inReach = false, markup = true).toMap()["taps"])
        assertEquals(ScanReachTool.Markup, scanMoreTool(ScanEnhanceMore.Markup))
        assertNull(scanMoreTool(ScanEnhanceMore.AddPage))
    }

    @Test
    fun extractedTextIsJoinedAndKeptSmallEnoughToSend() {
        assertEquals("one\n\nthree", scanTextJoin(listOf(" one ", "", "three\n")))
        assertEquals("", scanTextJoin(listOf("", "  ")))
        val long = "a".repeat(SCAN_TEXT_MAX - 1) + "😀" + "b"
        val cut = scanTextForIntent(long)
        assertEquals(SCAN_TEXT_MAX - 1, cut.length)
        assertEquals("short", scanTextForIntent("short"))
    }

    @Test
    fun theOptionIsOnByDefaultAndRoundTrips() {
        assertTrue(ScanLab().toolsInReach)
        val off = ScanLab(toolsInReach = false)
        assertFalse(off.asDesigned)
        assertEquals("0", off.encode().let { e -> e.split(';', ',', '&').firstOrNull { it.startsWith("reach=") }?.substringAfter("=") })
        assertEquals(off, ScanLab.decode(off.encode()))
    }
}
