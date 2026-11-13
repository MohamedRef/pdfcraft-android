package io.github.artcraftandroid.pdfcraft.ui.scan

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The camera's modes, zoom and badge (#457). */
class ScanCameraModesTest {
    @Test
    fun aNewInstallStartsInOnePageAndAModeIsRemembered() {
        assertEquals(ScanCameraMode.Single, ScanCameraMode.decode(null))
        assertEquals(ScanCameraMode.Single, ScanCameraMode.decode("moon"))
        assertEquals(ScanCameraMode.Batch, ScanCameraMode.decode("batch"))
        assertEquals(ScanCameraMode.Single, ScanCameraMode.decode("single"))
        // Book is book mode's own switch, not a remembered mode
        assertEquals(ScanCameraMode.Single, ScanCameraMode.decode("book"))
        assertEquals(3, ScanCameraMode.entries.map { it.id }.toSet().size)
    }

    @Test
    fun bookModeOnShowsBook() {
        assertEquals(ScanCameraMode.Book, scanCameraModeShown(ScanCameraMode.Single, bookOn = true))
        assertEquals(ScanCameraMode.Book, scanCameraModeShown(ScanCameraMode.Batch, bookOn = true))
        assertEquals(ScanCameraMode.Batch, scanCameraModeShown(ScanCameraMode.Batch, bookOn = false))
        assertEquals(ScanCameraMode.Single, scanCameraModeShown(ScanCameraMode.Book, bookOn = false))
    }

    @Test
    fun onePageClosesTheCameraAfterThePhoto() {
        assertFalse(scanCameraKeepsOpen(bar = true, mode = ScanCameraMode.Single, retaking = false, doneQueued = false))
        assertTrue(scanCameraKeepsOpen(bar = true, mode = ScanCameraMode.Batch, retaking = false, doneQueued = false))
        assertTrue(scanCameraKeepsOpen(bar = true, mode = ScanCameraMode.Book, retaking = false, doneQueued = false))
        // a retake is one photo, and Done during the photo closes it, in every mode
        for (m in ScanCameraMode.entries) {
            assertFalse(scanCameraKeepsOpen(bar = true, mode = m, retaking = true, doneQueued = false))
            assertFalse(scanCameraKeepsOpen(bar = true, mode = m, retaking = false, doneQueued = true))
        }
    }

    @Test
    fun theOldBarKeepsTakingPhotosAsBefore() {
        // the testing option off: no modes, every photo added until Done, as before #457
        assertTrue(scanCameraKeepsOpen(bar = false, mode = ScanCameraMode.Single, retaking = false, doneQueued = false))
        assertFalse(scanCameraKeepsOpen(bar = false, mode = ScanCameraMode.Single, retaking = true, doneQueued = false))
        assertEquals("batch", scanCameraModeId(bar = false, mode = ScanCameraMode.Single))
        assertEquals("book", scanCameraModeId(bar = false, mode = ScanCameraMode.Book))
        assertEquals("single", scanCameraModeId(bar = true, mode = ScanCameraMode.Single))
    }

    @Test
    fun zoomStaysBetweenOneAndTheCamerasMost() {
        assertEquals(1f, ScanZoom.clamp(0.5f, 8f))
        assertEquals(3f, ScanZoom.clamp(3f, 8f))
        assertEquals(8f, ScanZoom.clamp(20f, 8f))
        assertEquals(1f, ScanZoom.clamp(Float.NaN, 8f))
        // a camera that reports no zoom, or nonsense, stays at 1×
        assertEquals(1f, ScanZoom.clamp(2f, 1f))
        assertEquals(1f, ScanZoom.clamp(2f, Float.NaN))
    }

    @Test
    fun theButtonGoesBetweenOneAndTwo() {
        assertEquals(2f, ScanZoom.toggled(1f, 8f))
        assertEquals(1f, ScanZoom.toggled(2f, 8f))
        // pinched past 2×: the button goes back to 1×; pinched between: to 2×
        assertEquals(1f, ScanZoom.toggled(3.4f, 8f))
        assertEquals(2f, ScanZoom.toggled(1.4f, 8f))
        // a camera whose most is under 2× goes to its most
        assertEquals(1.6f, ScanZoom.toggled(1f, 1.6f))
        assertEquals("1×", ScanZoom.label(1f))
        assertEquals("2×", ScanZoom.label(2.01f))
        assertEquals("1.5×", ScanZoom.label(1.5f))
        assertEquals(1.37, ScanZoom.rounded(1.3712f), 0.0)
    }

    @Test
    fun theBadgeCountsThePages() {
        assertEquals("1", scanCountBadge(1))
        assertEquals("99", scanCountBadge(99))
        assertEquals("99+", scanCountBadge(140))
        assertEquals("0", scanCountBadge(-2))
    }

    @Test
    fun theLogAndThePageFileGetTheModeZoomAndGrid() {
        val c = ScanCapture("in_app", mode = "single", zoom = 2.0, grid = true)
        val fields = c.diag().toMap()
        assertEquals("single", fields["mode"])
        assertEquals(2.0, fields["zoom"])
        assertEquals(true, fields["grid"])
        val json = c.json()
        assertEquals("single", json.getString("mode"))
        assertEquals(2.0, json.getDouble("zoom"), 0.0)
        assertTrue(json.getBoolean("grid"))
        // an import has no camera mode
        assertEquals("none", ScanCapture("import").diag().toMap()["mode"])
        assertEquals(JSONObject.NULL, ScanCapture("import").json().get("mode"))
    }
}
