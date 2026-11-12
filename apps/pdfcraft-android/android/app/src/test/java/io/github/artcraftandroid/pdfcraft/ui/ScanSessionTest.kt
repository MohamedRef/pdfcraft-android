package io.github.artcraftandroid.pdfcraft.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanSessionTest {
    private val crop = floatArrayOf(0.1f, 0.1f, 0.9f, 0.12f, 0.88f, 0.95f, 0.08f, 0.9f)

    @Test
    fun aScanComesBackAsItWas() {
        val s = ScanSession(
            shot = "/cache/camera/scan-3.jpg", retake = 1, forImage = false, name = "Scan 2026-10-29",
            pages = listOf(ScanSession.Page("/cache/camera/scan-1.jpg", crop, crop), ScanSession.Page("/cache/camera/scan-2.jpg", ScanPage.WHOLE, null)),
        )
        val back = ScanSession.decode(s.encode())!!
        assertEquals("/cache/camera/scan-3.jpg", back.shot)
        assertEquals(1, back.retake)
        assertEquals(false, back.forImage)
        assertEquals("Scan 2026-10-29", back.name)
        assertEquals(listOf("/cache/camera/scan-1.jpg", "/cache/camera/scan-2.jpg"), back.pages.map { it.path })
        assertArrayEquals(crop, back.pages[0].corners, 0f)
        assertArrayEquals(crop, back.pages[0].found, 0f)
        assertArrayEquals(ScanPage.WHOLE, back.pages[1].corners, 0f)
        assertNull(back.pages[1].found)
    }

    @Test
    fun aPhotoForAddImageWithNoPagesComesBack() {
        val back = ScanSession.decode(ScanSession("/cache/camera/image-1.jpg", null, true, "", emptyList()).encode())!!
        assertEquals("/cache/camera/image-1.jpg", back.shot)
        assertNull(back.retake)
        assertTrue(back.forImage)
        assertTrue(back.pages.isEmpty())
    }

    @Test
    fun aNameCannotBreakTheLines() {
        val back = ScanSession.decode(ScanSession(null, null, false, "two\nlines\tand a tab", emptyList()).encode())!!
        assertEquals("two lines and a tab", back.name)
        assertNull(back.shot)
    }

    @Test
    fun anythingElseIsNoScanAndABadPageIsLeftOut() {
        assertNull(ScanSession.decode(null))
        assertNull(ScanSession.decode(""))
        assertNull(ScanSession.decode("something else\nshot\t/x.jpg"))
        val back = ScanSession.decode("pdfcraft-scan 1\npage\t/a.jpg\t1,2,3\t\npage\t/b.jpg\t0,0,1,0,1,1,0,1\tNaN,0,1,0,1,1,0,1\npage\t\t0,0,1,0,1,1,0,1\t")!!
        assertEquals(listOf("/b.jpg"), back.pages.map { it.path })
        assertNull(back.pages[0].found)
    }

    @Test
    fun aPagesOwnTurnComesBackAndAnOlderSessionStillDoes() {
        val s = ScanSession(null, null, false, "Scan", listOf(ScanSession.Page("/c/a.jpg", crop, null, 3), ScanSession.Page("/c/b.jpg", crop, crop, null), ScanSession.Page("/c/c.jpg", crop, null, 0)))
        val back = ScanSession.decode(s.encode())!!
        assertEquals(listOf(3, null, 0), back.pages.map { it.turns })
        // a session written before pages could be turned by hand (#339) comes back, with no turns
        val old = "pdfcraft-scan 1\nshot\t\nretake\t\nimage\t0\nname\tScan\npage\t/c/a.jpg\t" + crop.joinToString(",") + "\t\n"
        val older = ScanSession.decode(old)!!
        assertEquals(listOf("/c/a.jpg"), older.pages.map { it.path })
        assertNull(older.pages[0].turns)
        // a turn that is not 0 to 3 is no turn
        // (the page line goes on with the chosen kind of document, #299, empty here)
        assertNull(ScanSession.decode(s.encode().replace("\t3\t\n", "\t7\t\n"))!!.pages[0].turns)
    }

    @Test
    fun aKindOfDocumentChosenForAPageComesBack() {
        val s = ScanSession(null, null, false, "Scan", listOf(ScanSession.Page("/c/a.jpg", crop, null, null, "receipt"), ScanSession.Page("/c/b.jpg", crop, null, 1, null)))
        val back = ScanSession.decode(s.encode())!!
        assertEquals(listOf("receipt", null), back.pages.map { it.preset })
        assertEquals(listOf(null, 1), back.pages.map { it.turns })
        // a word that could break the line, or is not one, is left out
        val odd = ScanSession.decode(ScanSession(null, null, false, "Scan", listOf(ScanSession.Page("/c/a.jpg", crop, null, null, "Re\tceipt"))).encode())!!
        assertEquals(1, odd.pages.size)
        assertNull(odd.pages[0].preset)
    }

    @Test
    fun eachPagesLookAndTheFlattenSwitchComeBack() {
        val clean = io.github.artcraftandroid.pdfcraft.ui.scan.ScanPageLook()
        val bw = io.github.artcraftandroid.pdfcraft.ui.scan.ScanPageLook(ScanFilter.LightText, 90)
        val board = io.github.artcraftandroid.pdfcraft.ui.scan.ScanPageLook(ScanFilter.Whiteboard, null)
        val s = ScanSession(
            null, null, false, "Scan",
            listOf(
                ScanSession.Page("/c/a.jpg", crop, null, look = clean),
                ScanSession.Page("/c/b.jpg", crop, null, 1, "receipt", look = bw),
                ScanSession.Page("/c/c.jpg", crop, null, look = board),
                ScanSession.Page("/c/d.jpg", crop, null),
            ),
            flatten = true,
        )
        val back = ScanSession.decode(s.encode())!!
        assertEquals(listOf(clean, bw, board, null), back.pages.map { it.look })
        assertTrue(back.flatten)
        // the other columns are untouched by the look lines
        assertEquals(listOf(null, 1, null, null), back.pages.map { it.turns })
        assertEquals("receipt", back.pages[1].preset)
        assertEquals(false, ScanSession.decode(s.copy(flatten = false).encode())!!.flatten)
    }

    @Test
    fun aBuildFromBeforeLooksPerPageReadsANewSessionAndANewBuildReadsAnOldOne() {
        val s = ScanSession(null, null, false, "Scan", listOf(ScanSession.Page("/c/a.jpg", crop, crop, 2, look = io.github.artcraftandroid.pdfcraft.ui.scan.ScanPageLook(ScanFilter.Grayscale, 10))), flatten = true)
        val text = s.encode()
        // the version did not change, so an older build accepts it, and it ignores lines it doesn't know:
        // with the look and flatten lines taken out the pages are what it reads
        assertTrue(text.startsWith("pdfcraft-scan 2\n"))
        val stripped = text.lines().filterNot { it.startsWith("look\t") || it.startsWith("flatten\t") }.joinToString("\n")
        val old = ScanSession.decode(stripped)!!
        assertEquals(listOf("/c/a.jpg"), old.pages.map { it.path })
        assertEquals(2, old.pages[0].turns)
        // and a session written before #301 has no looks (the default look) and Flatten off
        assertNull(old.pages[0].look)
        assertEquals(false, old.flatten)
    }

    @Test
    fun aLookLineBelongsOnlyToThePageJustBeforeIt() {
        val good = "0,0,1,0,1,1,0,1"
        val text = "pdfcraft-scan 2\nlook\tbw\t50\npage\t/a.jpg\t1,2\t\nlook\tgreyscale\t\npage\t/b.jpg\t$good\t\nlook\tmoon\t\npage\t/c.jpg\t$good\t\nlook\tcolour\t300\npage\t/d.jpg\t$good\t\nlook\toriginal\t\nlook\tbw\t5\n"
        val back = ScanSession.decode(text)!!
        // the first look has no page before it; /a.jpg is malformed, so the look after it goes nowhere
        assertEquals(listOf("/b.jpg", "/c.jpg", "/d.jpg"), back.pages.map { it.path })
        // a look that is not one (/b.jpg), or a strength out of range (/c.jpg), is no look
        assertNull(back.pages[0].look)
        assertNull(back.pages[1].look)
        // one look line per page: the second is ignored
        assertEquals(io.github.artcraftandroid.pdfcraft.ui.scan.ScanPageLook(ScanFilter.Original), back.pages[2].look)
    }
}
