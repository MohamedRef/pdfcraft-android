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
        assertNull(ScanSession.decode(s.encode().replace("\t3\n", "\t7\n"))!!.pages[0].turns)
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
}
