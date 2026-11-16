package io.github.artcraftandroid.pdfcraft.ui.scan

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Starting a scan from photos already taken (#617): which shares start a scan, in what order, and what is recorded. */
class ScanStartTest {
    private val send = "android.intent.action.SEND"
    private val sendMany = "android.intent.action.SEND_MULTIPLE"

    @Test
    fun oneSharedPictureIsOnePage() {
        assertEquals(listOf("a"), scanSharedImages(send, "image/jpeg", listOf("a"), emptyList()))
    }

    @Test
    fun severalSharedPicturesKeepTheirOrderOnce() {
        // the clip repeats the streams (as most galleries send them) and adds one more at the end
        assertEquals(listOf("c", "a", "b", "d"), scanSharedImages(sendMany, "image/*", listOf("c", "a", null, "b"), listOf("c", "a", "b", "d")))
    }

    @Test
    fun aSharedPdfOrOtherTypeIsNotAScan() {
        assertNull(scanSharedImages(send, "application/pdf", listOf("a"), emptyList()))
        assertNull(scanSharedImages(send, null, listOf("a"), emptyList()))
        assertNull(scanSharedImages("android.intent.action.VIEW", "image/png", listOf("a"), emptyList()))
    }

    @Test
    fun aShareWithNothingInItIsEmptyNotIgnored() {
        assertEquals(emptyList<String>(), scanSharedImages(sendMany, "IMAGE/PNG", listOf<String?>(null), emptyList()))
    }

    @Test
    fun onlyAPictureWithASizeIsReadable() {
        assertTrue(scanSharedReadable(800, 600))
        assertFalse(scanSharedReadable(-1, -1))
        assertFalse(scanSharedReadable(0, 600))
    }

    @Test
    fun theCaptureSaysHowTheScanStarted() {
        ScanCaptures.startedFrom = ScanStart.Shared
        try {
            ScanCaptures.put("/t/shared.jpg", ScanCapture("shared"))
            val c = ScanCaptures.of("/t/shared.jpg")!!
            assertEquals("shared", c.started)
            assertEquals("shared", JSONObject(c.json().toString()).getString("started"))
            assertTrue(c.diag().any { it.first == "started" && it.second == "shared" })
        } finally {
            ScanCaptures.startedFrom = ScanStart.Camera
        }
        assertEquals(listOf("camera", "import", "shared", "shortcut"), ScanStart.entries.map { it.id })
    }
}
