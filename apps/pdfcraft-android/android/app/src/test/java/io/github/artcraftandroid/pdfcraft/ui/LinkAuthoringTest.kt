package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.linkIndexFor
import io.github.artcraftandroid.pdfcraft.ui.viewer.normalizeLinkUrl
import io.github.artcraftandroid.pdfcraft.ui.viewer.parseLinkPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkAuthoringTest {
    @Test
    fun typedAddressesBecomeWebOrMailLinks() {
        assertEquals("https://example.com", normalizeLinkUrl(" example.com "))
        assertEquals("https://example.com:8080/a", normalizeLinkUrl("example.com:8080/a"))
        assertEquals("http://example.com/x?y=1", normalizeLinkUrl("http://example.com/x?y=1"))
        assertEquals("mailto:ann@example.com", normalizeLinkUrl("ann@example.com"))
        assertEquals("mailto:ann@example.com", normalizeLinkUrl("mailto:ann@example.com"))
        // nothing the viewer would refuse to follow
        assertNull(normalizeLinkUrl("javascript:alert(1)"))
        assertNull(normalizeLinkUrl("file:///sdcard/a.pdf"))
        assertNull(normalizeLinkUrl("https:"))
        assertNull(normalizeLinkUrl("two words.com"))
        assertNull(normalizeLinkUrl("nodot"))
        assertNull(normalizeLinkUrl(""))
    }

    @Test
    fun pageNumbersAreInTheDocument() {
        assertEquals(39, parseLinkPage("40", 120))
        assertNull(parseLinkPage("0", 120))
        assertNull(parseLinkPage("121", 120))
        assertNull(parseLinkPage("x", 120))
    }

    @Test
    fun anExistingLinkIsFoundByItsRectangle() {
        val links = listOf(
            Triple(1, 1, listOf(50f, 60f, 150f, 80f)),
            Triple(1, 2, listOf(50f, 100f, 150f, 120f)),
            Triple(2, 1, listOf(50f, 100f, 150f, 120f)),
        )
        assertEquals(2, linkIndexFor(links, 0, listOf(50.2f, 99.8f, 150f, 120.4f)))
        assertEquals(1, linkIndexFor(links, 1, listOf(50f, 100f, 150f, 120f)))
        assertNull(linkIndexFor(links, 0, listOf(10f, 10f, 20f, 20f)))
        assertNull(linkIndexFor(links, 2, listOf(50f, 100f, 150f, 120f)))
    }
}
