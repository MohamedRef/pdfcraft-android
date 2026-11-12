package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.ui.ScanLab
import io.github.artcraftandroid.pdfcraft.ui.ScanPage
import io.github.artcraftandroid.pdfcraft.ui.ScanSession
import io.github.artcraftandroid.pdfcraft.ui.scanTestPageJson
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Book mode (#303): two pages from one photo, their order, the shared photo file, and what is logged and kept. */
class ScanBookTest {
    private val spread = floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)
    private val left = floatArrayOf(0.1f, 0.1f, 0.47f, 0.1f, 0.47f, 0.9f, 0.1f, 0.9f)
    private val right = floatArrayOf(0.49f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.49f, 0.9f)
    private val cut = ScanBookCut(true, left, right, gutterFound = true, gutterTop = 0.47f, gutterBottom = 0.48f, confidence = 0.8f)

    @Test
    fun aSpreadGivesTwoPagesLeftFirstWhenReadingLeftToRight() {
        val pages = scanBookPages(spread, cut, ScanBookOrder.LeftToRight)
        assertEquals(2, pages.size)
        assertArrayEquals(left, pages[0].first, 0f)
        assertArrayEquals(right, pages[1].first, 0f)
        assertEquals(listOf("left", "right"), pages.map { it.second?.side })
        assertEquals(ScanBookOrder.LeftToRight, pages[0].second?.order)
    }

    @Test
    fun rightToLeftPutsTheRightPageFirst() {
        val pages = scanBookPages(spread, cut, ScanBookOrder.RightToLeft)
        assertArrayEquals(right, pages[0].first, 0f)
        assertArrayEquals(left, pages[1].first, 0f)
        assertEquals(listOf("right", "left"), pages.map { it.second?.side })
    }

    @Test
    fun aPhotoThatIsNotASpreadStaysOnePage() {
        for (none in listOf(null, cut.copy(split = false, left = FloatArray(0), right = FloatArray(0)), cut.copy(right = floatArrayOf(Float.NaN, 0f, 1f, 0f, 1f, 1f, 0f, 1f)))) {
            val pages = scanBookPages(spread, none, ScanBookOrder.RightToLeft)
            assertEquals(1, pages.size)
            assertSame(spread, pages[0].first)
            assertNull(pages[0].second)
        }
    }

    @Test
    fun theOrderStartsFromTheLayoutDirection() {
        assertEquals(ScanBookOrder.RightToLeft, ScanBookOrder.default(rtl = true))
        assertEquals(ScanBookOrder.LeftToRight, ScanBookOrder.default(rtl = false))
        assertEquals(ScanBookOrder.RightToLeft, ScanBookOrder.of("rtl"))
        assertNull(ScanBookOrder.of("up"))
    }

    @Test
    fun retakingHalfASpreadReplacesBothHalves() {
        // pages as (photo, name); a spread's halves share their photo
        val pages = listOf("a" to "a", "b" to "b-left", "b" to "b-right", "c" to "c")
        val same = { x: Pair<String, String>, y: Pair<String, String> -> x.first == y.first }
        assertEquals(listOf("a", "d-left", "d-right", "c"), scanReplacePage(pages, 2, listOf("d" to "d-left", "d" to "d-right"), same).map { it.second })
        assertEquals(listOf("a", "d", "c"), scanReplacePage(pages, 1, listOf("d" to "d"), same).map { it.second })
        // a page of its own is replaced alone, and a retake of nothing adds at the end
        assertEquals(listOf("d", "b-left", "b-right", "c"), scanReplacePage(pages, 0, listOf("d" to "d"), same).map { it.second })
        assertEquals(listOf("a", "b-left", "b-right", "c", "d"), scanReplacePage(pages, null, listOf("d" to "d"), same).map { it.second })
        assertEquals(listOf("a", "b-left", "b-right", "c", "d"), scanReplacePage(pages, 9, listOf("d" to "d"), same).map { it.second })
    }

    @Test
    fun aPhotoIsDeletedOnlyWhenNoPageUsesIt() {
        val before = listOf("/c/a.jpg", "/c/b.jpg", "/c/b.jpg", "/c/c.jpg")
        // one half of the spread deleted: its photo stays for the other half
        assertTrue(scanUnusedFiles(before, listOf("/c/a.jpg", "/c/b.jpg", "/c/c.jpg")).isEmpty())
        // the other half too: now it goes
        assertEquals(setOf("/c/b.jpg"), scanUnusedFiles(listOf("/c/a.jpg", "/c/b.jpg", "/c/c.jpg"), listOf("/c/a.jpg", "/c/c.jpg")))
        assertEquals(setOf("/c/b.jpg"), scanUnusedFiles(before, listOf("/c/a.jpg", "/c/d.jpg", "/c/d.jpg", "/c/c.jpg")))
    }

    @Test
    fun aRestoredScanKeepsBothHalvesOfASpread() {
        val halves = scanBookPages(spread, cut, ScanBookOrder.RightToLeft)
        val session = ScanSession(
            null, null, false, "Book",
            halves.map { (corners, half) -> ScanSession.Page("/cache/camera/scan-1.jpg", corners, corners, book = half?.encode()) } +
                ScanSession.Page("/cache/camera/scan-2.jpg", ScanPage.WHOLE, null),
        )
        val back = ScanSession.decode(session.encode())!!
        assertEquals(listOf("/cache/camera/scan-1.jpg", "/cache/camera/scan-1.jpg", "/cache/camera/scan-2.jpg"), back.pages.map { it.path })
        assertArrayEquals(right, back.pages[0].corners, 0f)
        assertArrayEquals(left, back.pages[1].corners, 0f)
        assertEquals(halves[0].second, ScanBookHalf.decode(back.pages[0].book))
        assertEquals(halves[1].second, ScanBookHalf.decode(back.pages[1].book))
        assertNull(back.pages[2].book)
        // a session written before book mode (four fields a page) still reads
        val old = "pdfcraft-scan 1\nshot\t\nretake\t\nimage\t0\nname\tOld\npage\t/c/x.jpg\t${ScanPage.WHOLE.joinToString(",")}\t\n"
        assertNull(ScanSession.decode(old)!!.pages.single().book)
    }

    @Test
    fun aHalfThatIsNotUnderstoodIsAPageOfItsOwn() {
        assertNull(ScanBookHalf.decode(null))
        assertNull(ScanBookHalf.decode(""))
        assertNull(ScanBookHalf.decode("middle,1,0.5,0.5,0.5,ltr"))
        assertNull(ScanBookHalf.decode("left,1,0.5,NaN,0.5,ltr"))
        assertNull(ScanBookHalf.decode("left,1,0.5,0.5,0.5,up"))
    }

    @Test
    fun theLogSaysWhatBookModeDecidedInWordsAndNumbers() {
        val fields = scanBookDiag(true, true, ScanBookOrder.RightToLeft, cut, 140).toMap()
        assertEquals(true, fields["book"])
        assertEquals("rtl", fields["book_order"])
        assertEquals(true, fields["book_split"])
        assertEquals(true, fields["gutter_found"])
        assertEquals(0.47, fields["gutter_top"] as Double, 1e-6)
        assertEquals(0.8, fields["gutter_confidence"] as Double, 1e-6)
        assertEquals(140L, fields["book_ms"])
        for (value in fields.values) assertTrue(value is Boolean || value is Long || value is Double || (value is String && value.all { it in 'a'..'z' }))
        assertEquals(false, scanBookDiag(true, false, ScanBookOrder.LeftToRight, null, 0).toMap()["book_split"])
        assertEquals(mapOf("book" to false), scanBookDiag(false, true, ScanBookOrder.LeftToRight, cut, 0).toMap())
    }

    @Test
    fun theGutterSearchIsATestingOption() {
        val lab = ScanLab()
        assertTrue(lab.bookGutter)
        assertFalse(lab.copy(bookGutter = false).asDesigned)
        assertEquals(ScanLab(bookGutter = false), ScanLab.decode(ScanLab(bookGutter = false).encode()))
        assertEquals(false, ScanLab(bookGutter = false).diag().toMap()["book_gutter"])
        assertEquals(false, ScanLab(bookGutter = false).json().getBoolean("book_gutter"))
        // options saved before book mode get the gutter search on
        assertTrue(ScanLab.decode("keep=1;finder=auto").bookGutter)
    }

    @Test
    fun aKeptPageSaysWhichHalfOfASpreadItIs() {
        val half = scanBookPages(spread, cut, ScanBookOrder.RightToLeft)[0].second
        val json = JSONObject(scanTestPageJson("1.0 (1)", true, right, right, null, "auto", 60, false, ScanLab(), null, null, book = half))
        val book = json.getJSONObject("book")
        assertEquals("right", book.getString("half"))
        assertEquals("rtl", book.getString("order"))
        assertTrue(book.getBoolean("gutter_found"))
        assertEquals(0.47, book.getDouble("gutter_top"), 1e-6)
        assertEquals(0.48, book.getDouble("gutter_bottom"), 1e-6)
        assertEquals(0.8, book.getDouble("confidence"), 1e-6)
        assertTrue(JSONObject(scanTestPageJson("1.0 (1)", true, spread, spread, null, "auto", 60, false, ScanLab(), null, null)).isNull("book"))
    }

    @Test
    fun autoDetectOnAHalfKeepsTheSameHalfOfTheSpreadFoundAgain() {
        val pages = scanBookPages(spread, cut, ScanBookOrder.RightToLeft)
        val right = pages[0].second!!
        val leftHalf = pages[1].second!!
        val again = cut.copy(left = floatArrayOf(0.12f, 0.1f, 0.46f, 0.1f, 0.46f, 0.9f, 0.12f, 0.9f))
        assertArrayEquals(again.right, scanBookRecut(right, again)!!, 0f)
        assertArrayEquals(again.left, scanBookRecut(leftHalf, again)!!, 0f)
        // the spread found is not two pages: the corners stay
        assertNull(scanBookRecut(right, null))
        assertNull(scanBookRecut(right, again.copy(split = false)))
    }
}
