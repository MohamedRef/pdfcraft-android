package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.ui.ScanFilter
import io.github.artcraftandroid.pdfcraft.ui.ScanLab
import io.github.artcraftandroid.pdfcraft.ui.ScanSession
import io.github.artcraftandroid.pdfcraft.ui.scanTestPageJson
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A saved scan edited from its PDF in the viewer (#513): the link, the refusals, and how pages go back. */
class ScanViewerEditTest {
    @get:Rule val tmp = TemporaryFolder()

    private val quad = floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)
    private val moved = floatArrayOf(0.2f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)

    private fun page(path: String, corners: FloatArray = quad, look: ScanPageLook = ScanPageLook(), turns: Int? = null, preset: String? = null): ScanEditPage {
        val line = ScanSession.Page(path, corners, quad, turns = turns, preset = preset, look = look)
        return ScanEditPage(path, scanEditSignature(line), corners)
    }

    @Test
    fun onlyAKeptScansIdIsTakenFromAFile() {
        assertEquals("scan-1731571200000-4242", scanLinkId(listOf("Company" to "x", SCAN_LINK_KEY to " scan-1731571200000-4242 ")))
        assertNull(scanLinkId(listOf("Company" to "x")))
        // anything that is not an id the store makes is not followed, so a file can't name another folder
        assertNull(scanLinkId(listOf(SCAN_LINK_KEY to "../scan-1-2")))
        assertNull(scanLinkId(listOf(SCAN_LINK_KEY to "scan-1-2/x")))
        assertNull(scanLinkId(listOf(SCAN_LINK_KEY to "")))
        assertFalse(scanLinkIdValid("scan--1-2"))
        assertTrue(scanLinkIdValid(ScanRecentStore(tmp.root).newId(5_000L)))
    }

    @Test
    fun editingIsRefusedWithAReason() {
        assertEquals(ScanEditRefusal.Off, scanEditRefusal(false, "scan-1-1", true, 3, 3, false))
        assertEquals(ScanEditRefusal.NotAScan, scanEditRefusal(true, null, false, 0, 3, false))
        assertEquals(ScanEditRefusal.Gone, scanEditRefusal(true, "scan-1-1", false, 0, 3, false))
        assertEquals(ScanEditRefusal.PagesChanged, scanEditRefusal(true, "scan-1-1", true, 3, 4, false))
        assertEquals(ScanEditRefusal.Busy, scanEditRefusal(true, "scan-1-1", true, 3, 3, true))
        assertNull(scanEditRefusal(true, "scan-1-1", true, 3, 3, false))
    }

    @Test
    fun aNewLookReplacesThatPageInPlaceAndKeepsItsComments() {
        val before = listOf(page("/a"), page("/b"), page("/c"))
        val after = listOf(page("/a"), page("/b", look = ScanPageLook(ScanFilter.Grayscale, 40)), page("/c"))
        val plan = scanReplacePlan(before, after)
        assertTrue(plan.inPlace)
        assertEquals(listOf<ScanReplaceStep>(ScanReplaceStep.Replace(listOf(2), listOf(2))), plan.steps)
        assertEquals(listOf(2), plan.changed)
        // only the look changed: nothing to ask about comments
        assertEquals(emptyList<Int>(), plan.moved)
        // the document type is not where the page sits either
        assertEquals(emptyList<Int>(), scanReplacePlan(before, listOf(page("/a", preset = "receipt"), page("/b"), page("/c"))).moved)
    }

    @Test
    fun newBordersOrATurnMoveThePage() {
        val before = listOf(page("/a"), page("/b"))
        val plan = scanReplacePlan(before, listOf(page("/a", corners = moved), page("/b", turns = 1)))
        assertTrue(plan.inPlace)
        assertEquals(listOf(1, 2), plan.changed)
        assertEquals(listOf(1, 2), plan.moved)
        assertEquals(2, scanCommentsOnMoved(listOf(1, 1, 3), plan.moved))
    }

    @Test
    fun nothingChangedLeavesThePdfAlone() {
        val pages = listOf(page("/a"), page("/b"))
        val plan = scanReplacePlan(pages, pages.map { it.copy() })
        assertTrue(plan.empty)
        assertTrue(plan.inPlace)
    }

    @Test
    fun aRetakeIsAnotherPhotoAtTheSamePlace() {
        val plan = scanReplacePlan(listOf(page("/a"), page("/b")), listOf(page("/a"), page("/new")))
        assertFalse(plan.inPlace)
        assertEquals(listOf<ScanReplaceStep>(ScanReplaceStep.Replace(listOf(2), listOf(2))), plan.steps)
        assertEquals(listOf(2), plan.moved)
    }

    @Test
    fun pagesAddedGoAtTheEndAndPagesDeletedGoFromTheEnd() {
        val a = page("/a")
        val b = page("/b")
        val c = page("/c")
        val added = scanReplacePlan(listOf(a, b), listOf(a, b, c))
        assertFalse(added.inPlace)
        assertEquals(listOf<ScanReplaceStep>(ScanReplaceStep.Insert(3, listOf(3))), added.steps)
        // a page deleted from the middle: the ones after it move up a place
        val deleted = scanReplacePlan(listOf(a, b, c), listOf(a, c))
        assertEquals(listOf(ScanReplaceStep.Replace(listOf(2), listOf(2)), ScanReplaceStep.Delete(listOf(3))), deleted.steps)
        assertEquals(listOf(2), deleted.moved)
        // two pages swapped
        val swapped = scanReplacePlan(listOf(a, b), listOf(b, a))
        assertEquals(listOf<ScanReplaceStep>(ScanReplaceStep.Replace(listOf(1, 2), listOf(1, 2))), swapped.steps)
        assertEquals(listOf(1, 2), swapped.moved)
    }

    @Test
    fun theLogLinesHoldCountsOnly() {
        assertEquals(listOf("linked" to true, "pages" to 3), scanLinkDiag(true, 3).toList())
        val opened = scanViewerEditDiag(2, "kept", savedAt = 0L, now = 7_200_000L).toMap()
        assertEquals(2, opened["index"])
        assertEquals("kept", opened["source"])
        assertEquals(2L, opened["age_h"])
        val plan = scanReplacePlan(listOf(page("/a"), page("/b")), listOf(page("/a", corners = moved), page("/b"), page("/c")))
        val replaced = scanReplaceDiag(plan, 1000L, 1200L, commentsOnPages = 1, commentsRemoved = false, ms = 30L).toMap()
        assertEquals(1, replaced["pages"])
        assertEquals(false, replaced["in_place"])
        assertEquals(1, replaced["added"])
        assertEquals(0, replaced["deleted"])
        assertEquals(1000L, replaced["bytes_before"])
        assertEquals(1200L, replaced["bytes_after"])
        assertEquals(1, replaced["comments_on_pages"])
    }

    @Test
    fun theTestingOptionIsOnAndRemembered() {
        assertTrue(ScanLab().editFromViewer)
        val off = ScanLab(editFromViewer = false)
        assertFalse(ScanLab.decode(off.encode()).editFromViewer)
        assertFalse(off.asDesigned)
        assertEquals(false, off.diag().toMap()["edit_from_viewer"])
        assertEquals(false, off.json().getBoolean("edit_from_viewer"))
        // an older preference line without the key keeps the default
        assertTrue(ScanLab.decode("keep=0").editFromViewer)
    }

    @Test
    fun thePageFileSaysTheScanWasOpenedFromItsPdf() {
        val json = JSONObject(scanTestPageJson("t", true, quad, quad, null, "auto", 60, false, ScanLab(), null, null, viewerSource = "kept"))
        assertTrue(json.getBoolean("edited_from_viewer"))
        assertEquals("kept", json.getString("source"))
        val plain = JSONObject(scanTestPageJson("t", true, quad, quad, null, "auto", 60, false, ScanLab(), null, null))
        assertFalse(plain.getBoolean("edited_from_viewer"))
        assertTrue(plain.isNull("source"))
    }

    @Test
    fun aScanIsKeptUnderTheIdNamedInItsPdfAndKnowsItsFile() {
        val cam = tmp.newFolder("camera")
        val store = ScanRecentStore(File(tmp.root, "recent"))
        val id = store.newId(9_000L)
        val photo = File(cam, "a.jpg").apply { writeBytes(ByteArray(10)) }
        val kept = store.keep(id, ScanSession(null, null, false, "Letter", listOf(ScanSession.Page(photo.absolutePath, quad, quad))), now = 9_000L)
        assertEquals(id, kept.id)
        assertFalse(kept.updated)
        assertEquals(1 to 9_000L, store.pagesOf(id))
        assertNull(store.pagesOf("scan-1-1"))
        assertNull(store.pagesOf("../x"))
        store.link(id, "content://downloads/1", 1)
        val link = File(File(tmp.root, "recent"), "$id/link.txt")
        assertEquals("content://downloads/1\n1\n", link.readText())
        // saved again from the viewer: the same entry, and the link stays
        val opened = store.open(id, tmp.newFolder("work"))
        assertNotNull(opened)
        opened!!
        val again = store.keep(id, opened.session, now = 10_000L)
        assertEquals(id, again.id)
        assertTrue(again.updated)
        assertTrue(link.isFile)
    }

    @Test
    fun aPicturePageIsReplacedWhereItSitsInThePdf() {
        val before = listOf(page("/a"))
        val plan = scanReplacePlan(before, listOf(page("/a", corners = moved), page("/b"))).at(5)
        assertEquals(listOf(ScanReplaceStep.Replace(listOf(5), listOf(1)), ScanReplaceStep.Insert(6, listOf(2))), plan.steps)
        assertEquals(listOf(5), plan.changed)
        assertEquals(listOf(5), plan.moved)
        val deleted = scanReplacePlan(listOf(page("/a"), page("/b")), listOf(page("/a"))).at(3)
        assertEquals(listOf<ScanReplaceStep>(ScanReplaceStep.Delete(listOf(4))), deleted.steps)
        // from the first page nothing moves
        val same = scanReplacePlan(before, listOf(page("/a", corners = moved)))
        assertEquals(same, same.at(1))
    }

    @Test
    fun onlyOnePictureOverThePageIsEditedAsAScan() {
        val page = floatArrayOf(0f, 0f, 595f, 842f)
        assertTrue(scanPictureCovers(listOf(page), 595f, 842f))
        // a small margin around the picture is fine
        assertTrue(scanPictureCovers(listOf(floatArrayOf(10f, 10f, 585f, 832f)), 595f, 842f))
        // a picture spilling past the page counts only what is on it
        assertTrue(scanPictureCovers(listOf(floatArrayOf(-20f, -20f, 615f, 862f)), 595f, 842f))
        // half the page, or two pictures: text and pictures mixed, not a scan
        assertFalse(scanPictureCovers(listOf(floatArrayOf(0f, 0f, 595f, 421f)), 595f, 842f))
        assertFalse(scanPictureCovers(listOf(page, page), 595f, 842f))
        assertFalse(scanPictureCovers(emptyList(), 595f, 842f))
    }
}
