package io.github.artcraftandroid.pdfcraft.ui.scan

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanLiveLogicTest {
    // ---- frames ----

    @Test
    fun packedRowsAreCopiedWhole() {
        val src = ByteBuffer.wrap(ByteArray(2 * 2 * 4) { it.toByte() })
        val dst = ByteArray(16)
        assertTrue(scanCopyRgbaRows(src, 8, 2, 2, dst))
        assertArrayEquals(ByteArray(16) { it.toByte() }, dst)
        assertEquals(0, src.position())
    }

    @Test
    fun paddedRowsDropTheirPadding() {
        // 2 pixels a row (8 bytes) and 4 bytes of padding; the last row has no padding after it
        val bytes = ByteArray(12 + 8)
        for (i in 0 until 8) bytes[i] = (i + 1).toByte()
        for (i in 8 until 12) bytes[i] = -1
        for (i in 0 until 8) bytes[12 + i] = (i + 11).toByte()
        val dst = ByteArray(16)
        assertTrue(scanCopyRgbaRows(ByteBuffer.wrap(bytes), 12, 2, 2, dst))
        assertArrayEquals(ByteArray(8) { (it + 1).toByte() } + ByteArray(8) { (it + 11).toByte() }, dst)
    }

    @Test
    fun framesThatDoNotFitAreRefused() {
        assertFalse(scanCopyRgbaRows(ByteBuffer.wrap(ByteArray(15)), 8, 2, 2, ByteArray(16)))
        assertFalse(scanCopyRgbaRows(ByteBuffer.wrap(ByteArray(16)), 4, 2, 2, ByteArray(16)))
        assertFalse(scanCopyRgbaRows(ByteBuffer.wrap(ByteArray(16)), 8, 2, 2, ByteArray(15)))
        assertFalse(scanCopyRgbaRows(ByteBuffer.wrap(ByteArray(16)), 8, 0, 2, ByteArray(16)))
    }

    // ---- placing the outline ----

    @Test
    fun centreMapsToCentreAndCornersInsideTheLetterbox() {
        // a portrait 3:4 frame on a tall 1080 × 2000 view: 1080 × 1440, 280 px above and below
        val c = scanFrameShareToView(0.5f, 0.5f, 3f / 4f, 1080f, 2000f)
        assertEquals(540f, c.x, 0.01f)
        assertEquals(1000f, c.y, 0.01f)
        val tl = scanFrameShareToView(0f, 0f, 3f / 4f, 1080f, 2000f)
        assertEquals(0f, tl.x, 0.01f)
        assertEquals(280f, tl.y, 0.01f)
        val br = scanFrameShareToView(1f, 1f, 3f / 4f, 1080f, 2000f)
        assertEquals(1080f, br.x, 0.01f)
        assertEquals(1720f, br.y, 0.01f)
        // a wide view: bands left and right instead
        val wl = scanFrameShareToView(0f, 0f, 3f / 4f, 2000f, 1000f)
        assertEquals(625f, wl.x, 0.01f)
        assertEquals(0f, wl.y, 0.01f)
        // a landscape 4:3 frame on a portrait view
        val ls = scanFrameShareToView(1f, 1f, 4f / 3f, 900f, 1600f)
        assertEquals(900f, ls.x, 0.01f)
        assertEquals(800f + 337.5f, ls.y, 0.01f)
    }

    // ---- the tracker ----

    private val page = floatArrayOf(0.2f, 0.2f, 0.8f, 0.2f, 0.8f, 0.8f, 0.2f, 0.8f)

    private fun det(points: FloatArray, at: Long, confidence: Float = 0.9f, found: Boolean = true) = ScanLiveDetection(points, confidence, 120f, found, false, at)

    private fun jitter(points: FloatArray, k: Int): FloatArray = FloatArray(8) { i -> points[i] + (if ((i + k) % 2 == 0) 0.004f else -0.004f) }

    @Test
    fun smallJitterIsSmoothedAndSteady() {
        val t = ScanLiveTracker()
        var out: ScanLiveOutline? = null
        for (k in 0 until 8) out = t.update(det(jitter(page, k), k * 80L))
        assertNotNull(out!!.points)
        assertTrue(out.stable)
        for (i in 0 until 8) assertEquals(page[i], out.points!![i], 0.006f)
    }

    @Test
    fun notSteadyBeforeSixDetectionsOver400Ms() {
        val t = ScanLiveTracker()
        var out: ScanLiveOutline? = null
        for (k in 0 until 6) out = t.update(det(page, k * 50L))
        assertFalse("6 detections over 250 ms", out!!.stable)
        out = t.update(det(page, 450L))
        assertTrue(out.stable)
    }

    @Test
    fun unsureDetectionsAreNotSteady() {
        val t = ScanLiveTracker()
        var out: ScanLiveOutline? = null
        for (k in 0 until 8) out = t.update(det(page, k * 100L, confidence = 0.5f))
        assertFalse(out!!.stable)
    }

    @Test
    fun aJumpToAnotherPageIsTakenAtOnce() {
        val t = ScanLiveTracker()
        for (k in 0 until 8) t.update(det(page, k * 70L))
        val other = floatArrayOf(0.05f, 0.05f, 0.5f, 0.05f, 0.5f, 0.6f, 0.05f, 0.6f)
        val out = t.update(det(other, 600L))
        assertArrayEquals(other, out.points, 1e-6f)
        assertFalse(out.stable)
    }

    @Test
    fun aShortDropoutKeepsTheOutlineThenItFades() {
        val t = ScanLiveTracker()
        for (k in 0 until 8) t.update(det(page, k * 70L))
        val last = 7 * 70L
        val kept = t.update(det(page, last + 300, found = false))
        assertNotNull(kept.points)
        assertFalse(kept.fading)
        val fading = t.at(last + 400)
        assertNotNull(fading.points)
        assertTrue(fading.fading)
        assertNull(t.at(last + 1_000).points)
    }

    @Test
    fun cornersRenamedByTheDetectorKeepTheirOrder() {
        val t = ScanLiveTracker()
        t.update(det(page, 0L))
        // the same page named from its top-right corner
        val turned = floatArrayOf(0.8f, 0.2f, 0.8f, 0.8f, 0.2f, 0.8f, 0.2f, 0.2f)
        val out = t.update(det(turned, 50L))
        assertArrayEquals(page, out.points, 1e-6f)
    }

    // ---- hints ----

    private fun scene(points: FloatArray? = page, stable: Boolean = false, luma: Float = 120f, cutOff: Boolean = false, missingMs: Long = 0L) =
        ScanLiveScene(points, stable, luma, cutOff, missingMs)

    @Test
    fun eachHintCanBeReached() {
        assertNull(scanLiveHintFor(scene(points = null, missingMs = 1_000L)))
        assertEquals(ScanLiveHint.PointAtDocument, scanLiveHintFor(scene(points = null, missingMs = 1_500L)))
        assertEquals(ScanLiveHint.MoreLight, scanLiveHintFor(scene(luma = 40f)))
        assertEquals(ScanLiveHint.FitPage, scanLiveHintFor(scene(cutOff = true)))
        assertEquals(ScanLiveHint.FitPage, scanLiveHintFor(scene(points = floatArrayOf(0.01f, 0.2f, 0.8f, 0.2f, 0.8f, 0.8f, 0.2f, 0.8f))))
        assertEquals(ScanLiveHint.MoveCloser, scanLiveHintFor(scene(points = floatArrayOf(0.4f, 0.4f, 0.6f, 0.4f, 0.6f, 0.6f, 0.4f, 0.6f))))
        // a strong trapezoid: the top side much shorter than the bottom
        assertEquals(ScanLiveHint.HoldFlatter, scanLiveHintFor(scene(points = floatArrayOf(0.4f, 0.1f, 0.6f, 0.1f, 0.95f, 0.9f, 0.05f, 0.9f))))
        assertEquals(ScanLiveHint.HoldStill, scanLiveHintFor(scene()))
        assertEquals(ScanLiveHint.Ready, scanLiveHintFor(scene(stable = true)))
    }

    @Test
    fun hintsAreOrderedByImportance() {
        // dark and cut off: light first
        assertEquals(ScanLiveHint.MoreLight, scanLiveHintFor(scene(luma = 30f, cutOff = true)))
        // small and cut off: fit first
        assertEquals(ScanLiveHint.FitPage, scanLiveHintFor(scene(points = floatArrayOf(0.0f, 0.0f, 0.2f, 0.0f, 0.2f, 0.2f, 0.0f, 0.2f))))
    }

    @Test
    fun aNewHintWaits400Ms() {
        val h = ScanLiveHintHolder()
        assertNull(h.update(ScanLiveHint.HoldStill, 0L))
        assertEquals(ScanLiveHint.HoldStill, h.update(ScanLiveHint.HoldStill, 400L))
        // a flicker to Ready for 300 ms does not show
        assertEquals(ScanLiveHint.HoldStill, h.update(ScanLiveHint.Ready, 500L))
        assertEquals(ScanLiveHint.HoldStill, h.update(ScanLiveHint.HoldStill, 800L))
        assertEquals(ScanLiveHint.HoldStill, h.update(ScanLiveHint.Ready, 900L))
        assertEquals(ScanLiveHint.Ready, h.update(ScanLiveHint.Ready, 1_300L))
    }

    // ---- what is recorded ----

    @Test
    fun theOutlineAtTheShutterIsRecorded() {
        val c = ScanCapture("in_app", live = true, liveFound = true, liveStable = true, liveHint = ScanLiveHint.Ready.id, previewQuad = page)
        val diag = c.diag().toMap()
        assertEquals(true, diag["live_stable"])
        assertEquals("ready", diag["live_hint"])
        for ((_, v) in diag) assertTrue(v is Number || v is Boolean || (v is String && v.matches(Regex("[a-z_]+"))))
        val json = c.json()
        assertEquals(0.8, json.getJSONArray("preview_quad").getDouble(2), 1e-9)
        assertTrue(ScanCapture("phone_app").json().isNull("preview_quad"))
        // every hint's id is a short lowercase word, as the log allows
        for (h in ScanLiveHint.entries) assertTrue(h.id.matches(Regex("[a-z]+")))
    }
}
