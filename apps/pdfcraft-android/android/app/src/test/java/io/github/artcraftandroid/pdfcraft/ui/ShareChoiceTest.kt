package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.ShareChoice
import org.junit.Assert.assertEquals
import org.junit.Test

/** Share's choices (#205): the saved one comes back, and Share PDF stays first and the default. */
class ShareChoiceTest {
    @Test
    fun savedChoicesRoundTripAndUnknownOnesFallBackToSharePdf() {
        for (c in ShareChoice.entries) assertEquals(c, ShareChoice.of(c.id))
        assertEquals(ShareChoice.Pdf, ShareChoice.of(null))
        assertEquals(ShareChoice.Pdf, ShareChoice.of("original"))
        assertEquals(ShareChoice.Pdf, ShareChoice.entries.first())
    }
}
