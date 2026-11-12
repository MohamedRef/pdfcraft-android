package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.ui.ScanLab
import io.github.artcraftandroid.pdfcraft.ui.scanTestPageJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanCameraLogicTest {
    // ---- which camera ----

    @Test
    fun routeFollowsCameraPermissionAndSetting() {
        val inApp = ScanCameraChoice.InApp
        val phone = ScanCameraChoice.PhoneApp
        assertEquals(ScanCaptureRoute.InApp, scanCaptureRoute(hasCamera = true, granted = true, refused = false, choice = inApp))
        assertEquals(ScanCaptureRoute.PhoneApp, scanCaptureRoute(hasCamera = true, granted = true, refused = false, choice = phone))
        // not granted yet: asked first, for either camera (the camera app is refused too while CAMERA is declared and not granted)
        assertEquals(ScanCaptureRoute.AskPermission, scanCaptureRoute(hasCamera = true, granted = false, refused = false, choice = inApp))
        assertEquals(ScanCaptureRoute.AskPermission, scanCaptureRoute(hasCamera = true, granted = false, refused = false, choice = phone))
        // refused: never the camera app, which would fail
        assertEquals(ScanCaptureRoute.Denied, scanCaptureRoute(hasCamera = true, granted = false, refused = true, choice = phone))
        assertEquals(ScanCaptureRoute.Denied, scanCaptureRoute(hasCamera = true, granted = false, refused = true, choice = inApp))
        // no camera at all: import only, whatever else
        for (granted in listOf(true, false)) for (refused in listOf(true, false)) for (c in ScanCameraChoice.entries) {
            assertEquals(ScanCaptureRoute.ImportOnly, scanCaptureRoute(hasCamera = false, granted = granted, refused = refused, choice = c))
        }
    }

    @Test
    fun choiceDecodesAndDefaultsToTheInAppCamera() {
        assertEquals(ScanCameraChoice.PhoneApp, ScanCameraChoice.decode("phone_app"))
        assertEquals(ScanCameraChoice.InApp, ScanCameraChoice.decode("in_app"))
        assertEquals(ScanCameraChoice.InApp, ScanCameraChoice.decode(null))
        assertEquals(ScanCameraChoice.InApp, ScanCameraChoice.decode("something else"))
    }

    // ---- exposure ----

    @Test
    fun exposureIndexIsAThirdOfAStopUnder() {
        assertEquals(-1, scanExposureIndex(1.0 / 3.0, -12..12))
        // half stops: -0.67 rounds to -1 (half a stop under)
        assertEquals(-1, scanExposureIndex(0.5, -4..4))
        // sixth stops: two steps
        assertEquals(-2, scanExposureIndex(1.0 / 6.0, -24..24))
        // whole stops: the nearest is no compensation
        assertEquals(0, scanExposureIndex(1.0, -2..2))
    }

    @Test
    fun exposureIndexStaysInTheCamerasRange() {
        assertEquals(1, scanExposureIndex(1.0 / 3.0, 1..6))
        assertEquals(-1, scanExposureIndex(1.0 / 6.0, -1..3))
    }

    @Test
    fun exposureIndexIsZeroWithoutCompensation() {
        assertEquals(0, scanExposureIndex(1.0 / 3.0, 0..0))
        assertEquals(0, scanExposureIndex(0.0, -6..6))
        assertEquals(0, scanExposureIndex(Double.NaN, -6..6))
        assertEquals(0, scanExposureIndex(-0.5, -6..6))
    }

    // ---- the still ----

    @Test
    fun megapixelsAreTenths() {
        assertEquals(120, scanMegapixelsTenths(4000, 3000))
        assertEquals(499, scanMegapixelsTenths(8160, 6120))
        assertEquals(0, scanMegapixelsTenths(0, 3000))
        assertEquals(0, scanMegapixelsTenths(-1, -1))
    }

    @Test
    fun anEmptyOrMissingCaptureIsNotUsed() {
        assertFalse(scanCaptureUsable(exists = true, length = 0L))
        assertFalse(scanCaptureUsable(exists = false, length = 1234L))
        assertTrue(scanCaptureUsable(exists = true, length = 1L))
    }

    @Test
    fun deviceTurnSnapsToQuarterTurns() {
        assertEquals(0, scanDeviceTurn(0))
        assertEquals(0, scanDeviceTurn(44))
        assertEquals(90, scanDeviceTurn(45))
        assertEquals(90, scanDeviceTurn(120))
        assertEquals(180, scanDeviceTurn(200))
        assertEquals(270, scanDeviceTurn(300))
        assertEquals(0, scanDeviceTurn(350))
        assertNull(scanDeviceTurn(-1))
        // clockwise device turns are counter-clockwise display rotations (Surface.ROTATION_*)
        assertEquals(0, scanTargetRotation(0))
        assertEquals(3, scanTargetRotation(90))
        assertEquals(2, scanTargetRotation(180))
        assertEquals(1, scanTargetRotation(270))
    }

    // ---- the screen's states ----

    private fun ScanCameraState.run(vararg events: ScanCameraEvent): Pair<ScanCameraState, List<ScanCameraEffect>> {
        var s = this
        val effects = mutableListOf<ScanCameraEffect>()
        for (e in events) {
            val (next, fx) = s.reduce(e)
            s = next
            effects += fx
        }
        return s to effects
    }

    @Test
    fun shutterWithAFreshFocusCapturesAtOnce() {
        val focused = ScanCameraState(lastFocusAt = 1_000L, lastFocusOk = true)
        val (s, fx) = focused.run(ScanCameraEvent.Shutter(2_500L))
        assertEquals(ScanCameraPhase.Capturing, s.phase)
        assertEquals(listOf(ScanCameraEffect.Capture), fx)
    }

    @Test
    fun shutterWithAStaleOrFailedFocusFocusesFirst() {
        for (start in listOf(ScanCameraState(), ScanCameraState(lastFocusAt = 0L, lastFocusOk = true), ScanCameraState(lastFocusAt = 9_900L, lastFocusOk = false))) {
            val (s, fx) = start.run(ScanCameraEvent.Shutter(10_000L))
            assertEquals(ScanCameraPhase.Focusing, s.phase)
            assertTrue(s.captureQueued)
            assertEquals(listOf(ScanCameraEffect.FocusCentre), fx)
            // the capture goes once the focus ends
            val (after, fx2) = s.run(ScanCameraEvent.FocusEnded(true, 10_300L))
            assertEquals(ScanCameraPhase.Capturing, after.phase)
            assertEquals(listOf(ScanCameraEffect.Capture), fx2)
        }
    }

    @Test
    fun captureWaitingForFocusGoesAheadAfter800Ms() {
        val (s, fx) = ScanCameraState().run(ScanCameraEvent.Shutter(0L), ScanCameraEvent.Tick(400L), ScanCameraEvent.Tick(799L))
        assertEquals(ScanCameraPhase.Focusing, s.phase)
        assertEquals(listOf(ScanCameraEffect.FocusCentre), fx)
        val (after, fx2) = s.run(ScanCameraEvent.Tick(800L))
        assertEquals(ScanCameraPhase.Capturing, after.phase)
        assertEquals(listOf(ScanCameraEffect.Capture), fx2)
        // the focus ending late does not capture a second time
        val (late, fx3) = after.run(ScanCameraEvent.FocusEnded(true, 900L))
        assertEquals(ScanCameraPhase.Capturing, late.phase)
        assertTrue(fx3.isEmpty())
    }

    @Test
    fun captureQueuedDuringATapFocusWaits() {
        val (s, fx) = ScanCameraState().run(ScanCameraEvent.TapFocus(0L), ScanCameraEvent.Shutter(100L))
        assertEquals(ScanCameraPhase.Focusing, s.phase)
        assertTrue(s.captureQueued)
        assertTrue(fx.isEmpty())
        val (after, fx2) = s.run(ScanCameraEvent.FocusEnded(false, 300L))
        assertEquals(ScanCameraPhase.Capturing, after.phase)
        assertEquals(listOf(ScanCameraEffect.Capture), fx2)
        assertFalse(after.lastFocusOk)
    }

    @Test
    fun secondShutterTapsAreIgnored() {
        val focused = ScanCameraState(lastFocusAt = 0L, lastFocusOk = true)
        val (s, fx) = focused.run(ScanCameraEvent.Shutter(100L), ScanCameraEvent.Shutter(150L), ScanCameraEvent.Shutter(200L))
        assertEquals(listOf(ScanCameraEffect.Capture), fx)
        assertFalse(s.shutterReady)
        // also while a capture waits for focus
        val (w, fxw) = ScanCameraState().run(ScanCameraEvent.Shutter(0L), ScanCameraEvent.Shutter(10L), ScanCameraEvent.FocusEnded(true, 50L), ScanCameraEvent.Shutter(60L))
        assertEquals(listOf(ScanCameraEffect.FocusCentre, ScanCameraEffect.Capture), fxw)
        assertEquals(ScanCameraPhase.Capturing, w.phase)
    }

    @Test
    fun aFailedCaptureReturnsToIdleWithAnError() {
        val focused = ScanCameraState(lastFocusAt = 0L, lastFocusOk = true)
        val (s, fx) = focused.run(ScanCameraEvent.Shutter(100L), ScanCameraEvent.CaptureEnded(false, 600L))
        assertEquals(ScanCameraPhase.Idle, s.phase)
        assertEquals(listOf(ScanCameraEffect.Capture, ScanCameraEffect.ShowError), fx)
        assertTrue(s.shutterReady)
    }

    @Test
    fun doneDuringACaptureWaitsForIt() {
        val focused = ScanCameraState(lastFocusAt = 0L, lastFocusOk = true)
        val (s, fx) = focused.run(ScanCameraEvent.Shutter(100L), ScanCameraEvent.Done(200L))
        assertEquals(ScanCameraPhase.Capturing, s.phase)
        assertTrue(s.doneQueued)
        assertEquals(listOf(ScanCameraEffect.Capture), fx)
        val (after, fx2) = s.run(ScanCameraEvent.CaptureEnded(true, 700L))
        assertEquals(ScanCameraPhase.Finished, after.phase)
        assertEquals(listOf(ScanCameraEffect.Finish), fx2)
        // nothing more happens once finished
        assertTrue(after.run(ScanCameraEvent.Shutter(800L), ScanCameraEvent.Done(900L)).second.isEmpty())
    }

    @Test
    fun doneWhenIdleFinishesAtOnce() {
        val (s, fx) = ScanCameraState().run(ScanCameraEvent.Done(0L))
        assertEquals(ScanCameraPhase.Finished, s.phase)
        assertEquals(listOf(ScanCameraEffect.Finish), fx)
    }

    // ---- what is recorded ----

    @Test
    fun captureFactsGoToTheLogAndThePageFile() {
        val c = ScanCapture("in_app", megapixelsTenths = 500, width = 8160, height = 6120, exposureIndex = -1, exposureStepMilli = 333, torch = true, focused = true, rotation = 90, captureMs = 640L)
        val diag = c.diag().toMap()
        assertEquals("in_app", diag["source"])
        assertEquals(500, diag["mp10"])
        assertEquals(-1, diag["exposure_index"])
        assertEquals(640L, diag["capture_ms"])
        // only numbers, booleans and short lowercase words reach the log
        for ((_, v) in diag) assertTrue(v is Number || v is Boolean || (v is String && v.matches(Regex("[a-z_]+"))))

        val page = JSONObject(scanTestPageJson("1 (2)", true, floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), null, null, "auto", 60, false, ScanLab(), null, null, capture = c.json()))
        val capture = page.getJSONObject("capture")
        assertEquals("in_app", capture.getString("source"))
        assertEquals(8160, capture.getInt("width"))
        assertEquals(90, capture.getInt("rotation"))
        assertTrue(capture.getBoolean("torch"))
        // a photo whose capture is not known says so
        val unknown = JSONObject(scanTestPageJson("1 (2)", false, floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), null, null, null, null, false, ScanLab(), null, null))
        assertTrue(unknown.isNull("capture"))
    }

    @Test
    fun capturesAreFoundByPath() {
        ScanCaptures.put("/cache/camera/scan-1.jpg", ScanCapture("import"))
        assertEquals("import", ScanCaptures.of("/cache/camera/scan-1.jpg")?.source)
        assertNull(ScanCaptures.of("/cache/camera/scan-2.jpg"))
    }
}
