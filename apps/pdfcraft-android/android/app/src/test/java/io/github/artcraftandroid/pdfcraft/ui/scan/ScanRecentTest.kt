package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.ui.ScanFilter
import io.github.artcraftandroid.pdfcraft.ui.ScanLab
import io.github.artcraftandroid.pdfcraft.ui.ScanSession
import io.github.artcraftandroid.pdfcraft.ui.scanTestPageJson
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Recent scans (#461): the last 10 scans kept with their photos and page settings, opened and saved again. */
class ScanRecentTest {
    @get:Rule val tmp = TemporaryFolder()

    private val quad = floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)

    private fun photo(dir: File, name: String, bytes: Int = 100): File = File(dir.apply { mkdirs() }, name).apply { writeBytes(ByteArray(bytes) { it.toByte() }) }

    private fun session(name: String, vararg photos: File, look: ScanPageLook = ScanPageLook()) =
        ScanSession(null, null, false, name, photos.mapIndexed { i, f -> ScanSession.Page(f.absolutePath, quad, quad, turns = i % 4, preset = "text", look = look, flatten = i == 0) })

    @Test
    fun theOldestGoBeyondTheLimitButNeverTheOneJustKept() {
        val saved = (1..12).associate { "s$it" to it.toLong() * 1000 }
        assertEquals(listOf("s2", "s1"), scanRecentToPrune(saved, 10))
        // the one just kept stays even if its clock says it is older
        assertEquals(listOf("s3", "s2"), scanRecentToPrune(saved + ("s1" to 1000L), 10, keep = "s1"))
        assertEquals(emptyList<String>(), scanRecentToPrune(saved, 12))
        assertEquals(10, SCAN_RECENT_MAX)
    }

    @Test
    fun aBookSpreadsPhotoIsKeptOnce() {
        val pages = listOf(ScanSession.Page("/a.jpg", quad, null, book = "left"), ScanSession.Page("/a.jpg", quad, null, book = "right"), ScanSession.Page("/b.jpg", quad, null))
        assertEquals(listOf("/a.jpg", "/b.jpg"), scanRecentPhotos(pages))
    }

    @Test
    fun aScanIsKeptWithItsSettingsAndOpenedAgainAsACopy() {
        val cam = tmp.newFolder("camera")
        val store = ScanRecentStore(File(tmp.root, "recent"))
        val look = ScanPageLook(ScanFilter.Grayscale, 60)
        val a = photo(cam, "a.jpg", 300)
        val b = photo(cam, "b.jpg", 200)
        val kept = store.keep(null, session("Receipt", a, b, look = look), now = 5_000L)
        // the photos were moved, not copied
        assertFalse(a.exists())
        assertFalse(b.exists())
        assertFalse(kept.updated)
        assertEquals(2, kept.photos)
        assertEquals(1, kept.kept)
        assertEquals(0, kept.removed)
        val list = store.list()
        assertEquals(1, list.size)
        assertEquals("Receipt", list[0].name)
        assertEquals(2, list[0].pages)
        assertEquals(5_000L, list[0].savedAt)
        assertTrue(list[0].bytes >= 500)
        assertNotNull(list[0].firstPhoto)

        val work = tmp.newFolder("work")
        val opened = store.open(kept.id, work)!!
        assertEquals(0, opened.missing)
        assertEquals(2, opened.session.pages.size)
        val p = opened.session.pages[1]
        assertTrue(p.path.startsWith(work.absolutePath))
        assertEquals(200L, File(p.path).length())
        assertArrayEquals(quad, p.corners, 0f)
        assertEquals(1, p.turns)
        assertEquals("text", p.preset)
        assertEquals(look, p.look)
        assertEquals(true, opened.session.pages[0].flatten)
        // the kept scan stays as it was while the copy is edited
        assertEquals(2, store.list()[0].pages)
    }

    @Test
    fun theFirstPageAsMadeIsKeptForTheList() {
        // #494: the list's picture is the first page as made, when the queue had made it
        val cam = tmp.newFolder("camera")
        val store = ScanRecentStore(File(tmp.root, "recent"))
        val made = photo(tmp.newFolder("made"), "page-1.jpg", 50)
        val kept = store.keep(null, session("Letter", photo(cam, "a.jpg")), now = 1_000L, cover = made)
        val cover = store.list()[0].cover
        assertNotNull(cover)
        assertEquals(50L, cover!!.length())
        // the made page is copied, the queue's own file stays for the scan screen until it clears
        assertTrue(made.exists())
        // saved again with no page made: the old picture goes and the photo is shown
        val opened = store.open(kept.id, tmp.newFolder("work"))!!
        store.keep(kept.id, opened.session, now = 2_000L)
        assertNull(store.list()[0].cover)
        assertNotNull(store.list()[0].firstPhoto)
    }

    @Test
    fun savingAnOpenedScanReplacesItsOwnEntry() {
        val cam = tmp.newFolder("camera")
        val store = ScanRecentStore(File(tmp.root, "recent"))
        val first = store.keep(null, session("Letter", photo(cam, "a.jpg"), photo(cam, "b.jpg")), now = 1_000L)
        val opened = store.open(first.id, tmp.newFolder("work"))!!
        // one page deleted on the document screen, then saved again
        val again = store.keep(first.id, opened.session.copy(pages = opened.session.pages.take(1)), now = 2_000L)
        assertTrue(again.updated)
        assertEquals(first.id, again.id)
        assertEquals(1, again.photos)
        val list = store.list()
        assertEquals(1, list.size)
        assertEquals(1, list[0].pages)
        assertEquals(2_000L, list[0].savedAt)
        // the photos of the scan as it was before are gone from its folder
        assertEquals(3, File(File(tmp.root, "recent"), first.id).listFiles()!!.size)
    }

    @Test
    fun anEleventhScanRemovesTheOldestAndDeleteAllEmptiesTheList() {
        val cam = tmp.newFolder("camera")
        val store = ScanRecentStore(File(tmp.root, "recent"))
        val ids = (1..11).map { n -> store.keep(null, session("Scan $n", photo(cam, "p$n.jpg")), now = n * 1000L) }
        assertEquals(1, ids.last().removed)
        assertEquals(10, ids.last().kept)
        val names = store.list().map { it.name }
        assertEquals(10, names.size)
        assertEquals("Scan 11", names.first())
        assertFalse("Scan 1" in names)
        val (count, bytes) = store.deleteAll()
        assertEquals(10, count)
        assertTrue(bytes > 0)
        assertEquals(0, store.list().size)
    }

    @Test
    fun aMissingPhotoIsCountedAndAnUnknownScanIsNull() {
        val cam = tmp.newFolder("camera")
        val store = ScanRecentStore(File(tmp.root, "recent"))
        val kept = store.keep(null, session("Two", photo(cam, "a.jpg"), photo(cam, "b.jpg")), now = 1L)
        File(File(tmp.root, "recent"), kept.id).listFiles()!!.first { it.name.endsWith(".jpg") }.delete()
        val opened = store.open(kept.id, tmp.newFolder("work"))!!
        assertEquals(1, opened.missing)
        assertEquals(1, opened.session.pages.size)
        assertNull(store.open("nothing", tmp.newFolder("work2")))
        assertTrue(store.delete(kept.id) > 0)
        assertEquals(0, store.list().size)
    }

    @Test
    fun theLogLinesSayWhatHappened() {
        val keep = scanRecentKeepDiag(ScanRecentKept("x", 3, 9_000L, updated = true, kept = 10, removed = 1), pages = 4, ms = 12L).toMap()
        assertEquals(4, keep["pages"])
        assertEquals(3, keep["photos"])
        assertEquals(9_000L, keep["bytes"])
        assertEquals(true, keep["updated"])
        assertEquals(10, keep["kept"])
        assertEquals(1, keep["removed"])
        assertEquals(10, keep["max"])
        val open = scanRecentOpenDiag(pages = 3, missing = 1, savedAt = 0L, now = 7_200_000L, ms = 5L).toMap()
        assertEquals(2L, open["age_h"])
        assertEquals(1, open["missing"])
        val del = scanRecentDeleteDiag(4, 100L, all = true).toMap()
        assertEquals(4, del["count"])
        assertEquals(true, del["all"])
    }

    @Test
    fun aKeptPageFileSaysWhetherTheScanWasOpenedFromRecentScans() {
        fun json(from: Boolean) = JSONObject(scanTestPageJson("t", true, quad, quad, null, null, null, false, ScanLab(), null, null, fromRecent = from))
        assertTrue(json(true).getBoolean("from_recent"))
        assertFalse(json(false).getBoolean("from_recent"))
    }

    @Test
    fun theTestingOptionIsOnAndRoundTrips() {
        assertTrue(ScanLab().recentScans)
        val off = ScanLab(recentScans = false)
        assertFalse(ScanLab.decode(off.encode()).recentScans)
        assertEquals(false, off.diag().toMap()["recent_scans"])
        assertFalse(off.asDesigned)
    }
}
