package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.core.CommentInfo
import io.github.artcraftandroid.pdfcraft.core.LinkInfo
import io.github.artcraftandroid.pdfcraft.ui.viewer.LinkableFile
import io.github.artcraftandroid.pdfcraft.ui.viewer.flattenLinkBookmarks
import io.github.artcraftandroid.pdfcraft.ui.viewer.linkFileName
import io.github.artcraftandroid.pdfcraft.ui.viewer.linkIndexFor
import io.github.artcraftandroid.pdfcraft.ui.viewer.linkedFileFor
import io.github.artcraftandroid.pdfcraft.ui.viewer.linkedInkRects
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

    @Test
    fun linksToAnotherPdfUseItsPlainName() {
        assertEquals("Chapter 2.pdf", linkFileName(" Chapter 2.pdf "))
        assertEquals("NOTES.PDF", linkFileName("NOTES.PDF"))
        assertNull(linkFileName("photo.jpg"))
        assertNull(linkFileName("a/b.pdf"))
        assertNull(linkFileName("c:x.pdf"))
        assertNull(linkFileName(""))
        val files = listOf(LinkableFile("content://1", "Notes.pdf"), LinkableFile("content://2", "Chapter 2.pdf"))
        assertEquals("content://1", linkedFileFor("notes.pdf", files)?.uri)
        assertEquals("content://2", linkedFileFor("sub/Chapter 2.pdf", files)?.uri)
        assertEquals("content://2", linkedFileFor("..\\Chapter 2.pdf", files)?.uri)
        assertNull(linkedFileFor("missing.pdf", files))
        assertNull(linkedFileFor("", files))
    }

    @Test
    fun bookmarksFlattenInReadingOrder() {
        val json = org.json.JSONArray(
            """[{"title":"One","page":1,"children":[{"title":"One.a","page":3}]},{"title":"No page"},{"title":" Two ","page":9}]""",
        )
        assertEquals(listOf(0 to "One", 2 to "One.a", 8 to "Two"), flattenLinkBookmarks(json))
        assertEquals(emptyList<Pair<Int, String>>(), flattenLinkBookmarks(null))
    }

    @Test
    fun linksOverHandwritingGetABadge() {
        fun link(vararg r: Float) = LinkInfo(rect = r.toList(), uri = null, page = 1u, top = null, file = null, filePage = 0u)
        fun ink(page: Int, vararg r: Float, subtype: String = "Ink") = CommentInfo(
            page = page.toUInt(), index = 0u, subtype = subtype, kind = subtype, author = null, contents = null, modified = null, rect = r.toList(),
            color = null, name = null, replyTo = null, state = null, locked = false, fillSign = false, opacity = 1f, width = null, hidden = false, points = emptyList(),
        )
        val links = mapOf(0 to listOf(link(10f, 10f, 50f, 30f), link(200f, 200f, 240f, 220f)), 1 to listOf(link(10f, 10f, 50f, 30f)))
        val badges = linkedInkRects(links, listOf(ink(0, 5f, 5f, 45f, 35f), ink(1, 45f, 25f, 90f, 90f), ink(0, 200f, 200f, 240f, 220f, subtype = "Square")))
        assertEquals(listOf(0), badges.map { it.first })
        assertEquals(listOf(10f, 10f, 50f, 30f), badges.single().second.toList())
        assertEquals(emptyList<Pair<Int, FloatArray>>(), linkedInkRects(emptyMap(), emptyList()))
    }
}
