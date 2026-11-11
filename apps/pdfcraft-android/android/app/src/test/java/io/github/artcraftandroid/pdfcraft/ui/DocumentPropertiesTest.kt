package io.github.artcraftandroid.pdfcraft.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentPropertiesTest {
    @Test fun onlyChangedEntriesAreWritten() {
        val before = mapOf("Title" to "Report", "Author" to null, "Subject" to "x", "Keywords" to "a", "Project" to "Old")
        val after = mapOf("Title" to "Report ", "Author" to "Mo", "Subject" to "", "Keywords" to "a", "Project" to "New", "Client" to "ACME")
        assertEquals(
            listOf(
                mapOf("key" to "Author", "value" to "Mo"),
                mapOf("key" to "Subject", "value" to ""),
                mapOf("key" to "Client", "value" to "ACME"),
                mapOf("key" to "Project", "value" to "New"),
            ),
            metadataChanges(before, after),
        )
        assertEquals(emptyList<Map<String, Any?>>(), metadataChanges(before, mapOf("Title" to "Report")))
    }

    @Test fun customNamesFollowTheEngineRules() {
        assertTrue(customKeyValid("ProjectCode"))
        assertTrue(customKeyValid("Kunde-Nr."))
        assertFalse(customKeyValid("Project code"))
        assertFalse(customKeyValid("a/b"))
        assertFalse(customKeyValid("a#b"))
        assertFalse(customKeyValid("Title"))
        assertFalse(customKeyValid(""))
        assertFalse(customKeyValid("x".repeat(128)))
    }

    @Test fun initialViewRepliesMapBack() {
        assertEquals(OpenLayout.TwoUpCover, OpenLayout.fromReply("TwoUpCoverPage"))
        assertEquals(OpenLayout.Default, OpenLayout.fromReply("Unknown"))
        assertEquals(OpenZoom.FitWidth, OpenZoom.fromReply("FitWidth"))
        assertNull(OpenZoom.fromReply("Percent(150.0)"))
        assertEquals(150.0, percentFromReply("Percent(150.0)")!!, 0.0)
        assertNull(percentFromReply("FitPage"))
        assertEquals(OpenPanel.Bookmarks, OpenPanel.fromReply("Bookmarks"))
    }
}
