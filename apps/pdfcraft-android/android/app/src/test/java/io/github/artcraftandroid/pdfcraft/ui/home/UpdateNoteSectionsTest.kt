package io.github.artcraftandroid.pdfcraft.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateNoteSectionsTest {
    @Test fun githubReleaseNotesShowUserFeaturesNotPrAppendix() {
        val notes = """
            # PdfCraft Android 0.0.13-beta (13012)
            ## New features
            - **Draggable annotation toolbar:** Reposition the shortcuts.
            - **Lasso:** Select and move draft ink.
            ## Fixes
            - Fixed the highlight preview.
            ## Improvements
            - Better selection handles.
            ## Known limits and testing
            - Physical S Pen testing is still needed.
            ## Installing
            - Install the APK with Android confirmation.
            ## What's Changed
            - Duplicate release PR.
        """.trimIndent()
        val result = updateNoteSections(notes)
        assertEquals(listOf("New features", "Fixes", "Improvements", "Known limits"), result.map { it.title })
        assertEquals(2, result.first().entries.size)
        assertEquals("Draggable annotation toolbar: Reposition the shortcuts.", result.first().entries.first())
        assertTrue(result.none { group -> group.entries.any { it.contains("Duplicate release PR") || it.contains("Install the APK") } })
    }

    @Test fun markdownReferencesBecomeReadableWithoutLinkMarkup() {
        val notes = "## New features\n- Added [S Pen pressure](https://github.com/org/repo/pull/19) and `Ink`."
        assertEquals("Added S Pen pressure and Ink.", updateNoteSections(notes).single().entries.single())
    }

    @Test fun missingOrHugeNotesStayBounded() {
        assertTrue(updateNoteSections("").isEmpty())
        assertTrue(updateNoteSections("## Installing\n- This isn't a feature.").isEmpty())
        val many = "## Fixes\n" + (1..100).joinToString("\n") { "- Fixed item $it" }
        assertEquals(25, updateNoteSections(many).single().entries.size)
    }
}
