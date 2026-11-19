package io.github.artcraftandroid.pdfcraft.ui.viewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #741: the bar of a markup just made (colours, Note, Delete) goes with the tool that made it, and never shows over Present or Read aloud. */
class MarkupFollowUpTest {
    @Test
    fun doneOnTheHighlightToolEndsItsBar() {
        assertTrue(markupFollowUpEnds(previous = ViewerTool.Markup, tool = ViewerTool.None, presenting = false, readingAloud = false))
    }

    @Test
    fun presentAndReadAloudEndIt() {
        assertTrue(markupFollowUpEnds(ViewerTool.None, ViewerTool.None, presenting = true, readingAloud = false))
        assertTrue(markupFollowUpEnds(ViewerTool.None, ViewerTool.None, presenting = false, readingAloud = true))
        assertTrue(markupFollowUpEnds(ViewerTool.Markup, ViewerTool.Markup, presenting = true, readingAloud = false))
    }

    @Test
    fun anotherToolEndsIt() {
        for (tool in ViewerTool.entries.filter { it != ViewerTool.None && it != ViewerTool.Markup }) {
            assertTrue(tool.name, markupFollowUpEnds(ViewerTool.None, tool, presenting = false, readingAloud = false))
        }
    }

    @Test
    fun aMarkupFromTheSelectionMenuKeepsItsBarWhileNothingChanges() {
        // no tool open: the bar by the new markup stays until one of the above (#197)
        assertFalse(markupFollowUpEnds(previous = null, tool = ViewerTool.None, presenting = false, readingAloud = false))
        assertFalse(markupFollowUpEnds(previous = ViewerTool.None, tool = ViewerTool.None, presenting = false, readingAloud = false))
        // made with the Highlight tool, the bar shows above the mark while the tool is open (#650)
        assertFalse(markupFollowUpEnds(previous = ViewerTool.None, tool = ViewerTool.Markup, presenting = false, readingAloud = false))
        assertFalse(markupFollowUpEnds(previous = ViewerTool.Markup, tool = ViewerTool.Markup, presenting = false, readingAloud = false))
    }
}
