package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.showReadOnlyNotice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The viewer says changes can't reach the original as soon as there are any (#191). */
class ReadOnlyNoticeTest {
    @Test
    fun shownOnlyForEditsToAFileThatCannotBeWrittenBack() {
        assertTrue(showReadOnlyNotice(writable = false, hasSource = true, edited = true, closed = false))
        // nothing changed yet: nothing to warn about
        assertFalse(showReadOnlyNotice(writable = false, hasSource = true, edited = false, closed = false))
        assertFalse(showReadOnlyNotice(writable = true, hasSource = true, edited = true, closed = false))
        assertFalse(showReadOnlyNotice(writable = false, hasSource = false, edited = true, closed = false))
        // closed by the reader for this visit
        assertFalse(showReadOnlyNotice(writable = false, hasSource = true, edited = true, closed = true))
    }
}
