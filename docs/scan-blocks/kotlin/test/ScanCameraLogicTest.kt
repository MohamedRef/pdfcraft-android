package io.github.artcraftandroid.pdfcraft.ui.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The in-app scanner camera's decisions (#294, spec S08). Numbers are worked out by hand in the comments. */
class ScanCameraLogicTest {

    // ---- capture resolution (S08 section 3) ----

    @Test
    fun theLargestFourByThreeSizeIsChosen() {
        val sizes = listOf(
            ScanCaptureSize(4032, 3024), // 4:3, 12.2 MP
            ScanCaptureSize(4608, 3456), // 4:3, 15.9 MP
            ScanCaptureSize(4000, 3000), // 4:3, 12.0 MP
            ScanCaptureSize(1920, 1080), // 16:9, not 4:3
        )
        val chosen = chooseScanCaptureSize(sizes)
        assertEquals(ScanCaptureSize(4608, 3456), chosen)
        // 4608 * 3456 = 15 925 248 pixels = 15.925248 MP
        assertEquals(15.925248, chosen!!.megapixels, 0.000001)
    }

    @Test
    fun aPortraitFourByThreeSizeCountsAsFourByThree() {
        // 3024 * 4 = 12096 = 4032 * 3, so this is 4:3 in portrait
        val sizes = listOf(ScanCaptureSize(3024, 4032), ScanCaptureSize(3840, 2160))
        assertEquals(ScanCaptureSize(3024, 4032), chooseScanCaptureSize(sizes))
    }

    @Test
    fun whenNoSizeIsFourByThreeTheLargestOfAllIsChosen() {
        val sizes = listOf(ScanCaptureSize(1920, 1080), ScanCaptureSize(3840, 2160))
        assertEquals(ScanCaptureSize(3840, 2160), chooseScanCaptureSize(sizes))
    }

    @Test
    fun noSizesOrOnlyEmptySizesGiveNothing() {
        assertNull(chooseScanCaptureSize(emptyList()))
        assertNull(chooseScanCaptureSize(listOf(ScanCaptureSize(0, 3000), ScanCaptureSize(4000, 0))))
    }

    @Test
    fun aSizeWithAZeroSideIsIgnoredWhenAnotherIsUsable() {
        val sizes = listOf(ScanCaptureSize(0, 3000), ScanCaptureSize(4000, 3000))
        assertEquals(ScanCaptureSize(4000, 3000), chooseScanCaptureSize(sizes))
    }

    // ---- exposure compensation (S08 section 3) ----

    @Test
    fun aThirdOfAStopGivesMinusOneStep() {
        // -0.33 / 0.3333 = -0.99, rounds to -1
        assertEquals(-1, scanExposureIndex(1.0 / 3.0, -6, 6))
    }

    @Test
    fun aHalfStopGivesMinusOneStep() {
        // -0.33 / 0.5 = -0.66, rounds to -1
        assertEquals(-1, scanExposureIndex(0.5, -6, 6))
    }

    @Test
    fun aSixthOfAStopGivesMinusTwoSteps() {
        // -0.33 / 0.16667 = -1.98, rounds to -2
        assertEquals(-2, scanExposureIndex(1.0 / 6.0, -12, 12))
    }

    @Test
    fun theIndexIsClampedToTheRange() {
        // wanted -1 for a third of a stop; the range -6..-3 does not reach it, so the nearest end, -3
        assertEquals(-3, scanExposureIndex(1.0 / 3.0, -6, -3))
        // the range 0..2 does not reach -1 either, so 0
        assertEquals(0, scanExposureIndex(1.0 / 3.0, 0, 2))
    }

    @Test
    fun aReversedRangeIsReadTheRightWay() {
        assertEquals(-1, scanExposureIndex(1.0 / 3.0, 6, -6))
    }

    @Test
    fun notSupportedGivesNoCompensation() {
        // a range of 0..0 means the camera does not offer compensation
        assertEquals(0, scanExposureIndex(1.0 / 3.0, 0, 0))
    }

    @Test
    fun aStepThatIsNotPositiveGivesNoCompensation() {
        assertEquals(0, scanExposureIndex(0.0, -6, 6))
        assertEquals(0, scanExposureIndex(-0.5, -6, 6))
        assertEquals(0, scanExposureIndex(Double.NaN, -6, 6))
    }

    // ---- 35 mm focal length (S08 section 7) ----

    @Test
    fun theThirtyFiveMillimetreFocalLengthComesFromTheDiagonal() {
        // 4.38 mm lens, 6.4 x 4.8 mm sensor: diagonal 8.0 mm; 4.38 * 43.2666 / 8.0 = 23.688
        val f = scanFocal35mm(4.38f, 6.4f, 4.8f)
        assertNotNull(f)
        assertEquals(23.69f, f!!, 0.05f)
    }

    @Test
    fun aFullFrameSensorGivesTheLensFocalLengthUnchanged() {
        // a 36 x 24 mm sensor has a diagonal of 43.2666 mm, so the factor is 1
        val f = scanFocal35mm(50f, 36f, 24f)
        assertEquals(50f, f!!, 0.01f)
    }

    @Test
    fun aMissingOrBadValueGivesNoFocalLength() {
        assertNull(scanFocal35mm(0f, 6.4f, 4.8f))
        assertNull(scanFocal35mm(4.38f, 0f, 4.8f))
        assertNull(scanFocal35mm(4.38f, 6.4f, -1f))
        assertNull(scanFocal35mm(Float.NaN, 6.4f, 4.8f))
        assertNull(scanFocal35mm(Float.POSITIVE_INFINITY, 6.4f, 4.8f))
    }

    // ---- focus, sound, file (S08 sections 3, 4, 5) ----

    @Test
    fun aFocusResultTwoSecondsOldIsStillGoodButOlderIsNot() {
        assertFalse(scanNeedsFocusBeforeShot(2000L, true))
        assertTrue(scanNeedsFocusBeforeShot(2001L, true))
    }

    @Test
    fun anUnsuccessfulFocusAlwaysNeedsMeteringAgain() {
        assertTrue(scanNeedsFocusBeforeShot(0L, false))
    }

    @Test
    fun theShutterSoundPlaysOnlyOnTheNormalRinger() {
        assertTrue(scanShouldPlayShutter(2))
        assertFalse(scanShouldPlayShutter(0))
        assertFalse(scanShouldPlayShutter(1))
    }

    @Test
    fun aZeroLengthCaptureIsDiscarded() {
        assertFalse(scanKeepCaptureFile(true, 0L))
        assertFalse(scanKeepCaptureFile(false, 100L))
        assertTrue(scanKeepCaptureFile(true, 5L))
    }

    @Test
    fun theFocusWaitIsEightHundredMillisecondsAndTheStaleLimitTwoSeconds() {
        assertEquals(800L, SCAN_FOCUS_WAIT_MS)
        assertEquals(2000L, SCAN_FOCUS_STALE_MS)
        assertEquals(4000L, SCAN_TAP_FOCUS_RELEASE_MS)
    }

    // ---- permission decision table (S08 section 6) ----

    @Test
    fun withNoCameraOnlyImportIsOffered() {
        assertEquals(setOf(ScanAction.IMPORT), scanActions(true, true, false, ScanCameraSetting.IN_APP))
    }

    @Test
    fun withoutThePermissionImportAndOpenSettingsAreOffered() {
        val expected = setOf(ScanAction.IMPORT, ScanAction.OPEN_SETTINGS)
        assertEquals(expected, scanActions(true, false, true, ScanCameraSetting.IN_APP))
        // the phone's camera app is not offered without the permission either
        assertEquals(expected, scanActions(true, false, true, ScanCameraSetting.PHONE_APP))
    }

    @Test
    fun withThePermissionTheSettingChoosesTheCamera() {
        assertEquals(setOf(ScanAction.IN_APP_CAMERA, ScanAction.IMPORT), scanActions(true, true, true, ScanCameraSetting.IN_APP))
        assertEquals(setOf(ScanAction.PHONE_CAMERA_APP, ScanAction.IMPORT), scanActions(true, true, true, ScanCameraSetting.PHONE_APP))
    }

    // ---- reducer (S08 section 5) ----

    private val ready = ScanCameraState(permission = ScanPermission.GRANTED, camera = ScanCameraPhase.READY)

    @Test
    fun permissionAnswersSetTheStateForEachCase() {
        assertEquals(ScanPermission.GRANTED, reduceScanCamera(ready, ScanCameraEvent.PermissionAnswered(true, false)).permission)
        assertEquals(
            ScanPermission.DENIED,
            reduceScanCamera(ScanCameraState(), ScanCameraEvent.PermissionAnswered(false, false)).permission,
        )
        assertEquals(
            ScanPermission.DENIED_FOR_GOOD,
            reduceScanCamera(ScanCameraState(), ScanCameraEvent.PermissionAnswered(false, true)).permission,
        )
    }

    @Test
    fun cameraStartAndFailureSetThePhase() {
        val starting = ScanCameraState(permission = ScanPermission.GRANTED)
        assertEquals(ScanCameraPhase.READY, reduceScanCamera(starting, ScanCameraEvent.CameraStarted).camera)
        assertEquals(ScanCameraPhase.FAILED, reduceScanCamera(starting, ScanCameraEvent.CameraFailed).camera)
    }

    @Test
    fun aCaptureWhileFocusingIsQueuedThenTakenWhenFocusEnds() {
        val focusing = ready.copy(shutter = ScanShutterPhase.FOCUSING)
        val queued = reduceScanCamera(focusing, ScanCameraEvent.ShutterTapped)
        assertTrue(queued.shotQueued)
        assertEquals(ScanShutterPhase.FOCUSING, queued.shutter)
        val taken = reduceScanCamera(queued, ScanCameraEvent.FocusEnded(true))
        assertEquals(ScanShutterPhase.CAPTURING, taken.shutter)
        assertFalse(taken.shotQueued)
    }

    @Test
    fun aFocusTimeoutAlsoReleasesAQueuedCapture() {
        // the screen sends FocusEnded(false) when SCAN_FOCUS_WAIT_MS has run out
        val queued = ready.copy(shutter = ScanShutterPhase.FOCUSING, shotQueued = true)
        val taken = reduceScanCamera(queued, ScanCameraEvent.FocusEnded(false))
        assertEquals(ScanShutterPhase.CAPTURING, taken.shutter)
    }

    @Test
    fun focusEndingWithNoQueuedShotGoesBackToIdle() {
        val focusing = ready.copy(shutter = ScanShutterPhase.FOCUSING)
        assertEquals(ScanShutterPhase.IDLE, reduceScanCamera(focusing, ScanCameraEvent.FocusEnded(true)).shutter)
    }

    @Test
    fun aSecondShutterTapDuringCaptureIsIgnored() {
        val capturing = ready.copy(shutter = ScanShutterPhase.CAPTURING)
        assertEquals(capturing, reduceScanCamera(capturing, ScanCameraEvent.ShutterTapped))
        val saving = ready.copy(shutter = ScanShutterPhase.SAVING)
        assertEquals(saving, reduceScanCamera(saving, ScanCameraEvent.ShutterTapped))
    }

    @Test
    fun theShutterDoesNothingBeforeTheCameraIsReady() {
        val starting = ScanCameraState(permission = ScanPermission.GRANTED)
        assertEquals(starting, reduceScanCamera(starting, ScanCameraEvent.ShutterTapped))
    }

    @Test
    fun anErrorReturnsToIdleWhenDismissed() {
        val capturing = reduceScanCamera(ready, ScanCameraEvent.ShutterTapped)
        assertEquals(ScanShutterPhase.CAPTURING, capturing.shutter)
        val failed = reduceScanCamera(capturing, ScanCameraEvent.CaptureFailed)
        assertEquals(ScanShutterPhase.ERROR, failed.shutter)
        assertEquals(ScanShutterPhase.IDLE, reduceScanCamera(failed, ScanCameraEvent.ErrorDismissed).shutter)
    }

    @Test
    fun aZeroLengthPhotoIsAnErrorAndAddsNoPage() {
        val capturing = ready.copy(shutter = ScanShutterPhase.CAPTURING)
        val result = reduceScanCamera(capturing, ScanCameraEvent.PhotoTaken(false))
        assertEquals(ScanShutterPhase.ERROR, result.shutter)
        assertEquals(0, result.pagesTaken)
    }

    @Test
    fun aKeptPhotoAddsAPageAndIsSavedThenIdle() {
        val capturing = ready.copy(shutter = ScanShutterPhase.CAPTURING)
        val saving = reduceScanCamera(capturing, ScanCameraEvent.PhotoTaken(true))
        assertEquals(ScanShutterPhase.SAVING, saving.shutter)
        assertEquals(1, saving.pagesTaken)
        assertEquals(ScanShutterPhase.IDLE, reduceScanCamera(saving, ScanCameraEvent.SaveDone).shutter)
    }

    @Test
    fun doneDuringACaptureWaitsForIt() {
        val capturing = ready.copy(shutter = ScanShutterPhase.CAPTURING)
        val asked = reduceScanCamera(capturing, ScanCameraEvent.DoneTapped)
        assertTrue(asked.finishRequested)
        assertFalse(scanShouldFinish(asked))
        val saving = reduceScanCamera(asked, ScanCameraEvent.PhotoTaken(true))
        assertFalse(scanShouldFinish(saving))
        val idle = reduceScanCamera(saving, ScanCameraEvent.SaveDone)
        assertTrue(scanShouldFinish(idle))
    }

    @Test
    fun doneWhileQueuedWaitsForTheQueuedPhoto() {
        val queued = ready.copy(shutter = ScanShutterPhase.FOCUSING, shotQueued = true, finishRequested = true)
        assertFalse(scanShouldFinish(queued))
    }

    @Test
    fun doneWhenIdleFinishesAtOnce() {
        assertTrue(scanShouldFinish(reduceScanCamera(ready, ScanCameraEvent.DoneTapped)))
    }

    @Test
    fun aTapToFocusStartsFocusingAtThatPoint() {
        val tapped = reduceScanCamera(ready, ScanCameraEvent.TapFocus(0.25f, 0.75f))
        assertEquals(0.25f, tapped.focusX!!, 0.0001f)
        assertEquals(0.75f, tapped.focusY!!, 0.0001f)
        assertEquals(ScanShutterPhase.FOCUSING, tapped.shutter)
    }

    @Test
    fun aTapToFocusIsKeptOnTheFrame() {
        val tapped = reduceScanCamera(ready, ScanCameraEvent.TapFocus(1.5f, -0.2f))
        assertEquals(1f, tapped.focusX!!, 0.0001f)
        assertEquals(0f, tapped.focusY!!, 0.0001f)
    }

    @Test
    fun theTorchTogglesOnlyWhenTheCameraIsReady() {
        val on = reduceScanCamera(ready, ScanCameraEvent.TorchTapped)
        assertTrue(on.torchOn)
        assertFalse(reduceScanCamera(on, ScanCameraEvent.TorchTapped).torchOn)
        val starting = ScanCameraState()
        assertFalse(reduceScanCamera(starting, ScanCameraEvent.TorchTapped).torchOn)
    }

    @Test
    fun zoomIsKeptWithinTheCameraRange() {
        assertEquals(3f, reduceScanCamera(ready, ScanCameraEvent.ZoomChanged(5f, 1f, 3f)).zoomRatio, 0.0001f)
        assertEquals(1f, reduceScanCamera(ready, ScanCameraEvent.ZoomChanged(0.5f, 1f, 3f)).zoomRatio, 0.0001f)
        assertEquals(2f, reduceScanCamera(ready, ScanCameraEvent.ZoomChanged(2f, 1f, 3f)).zoomRatio, 0.0001f)
    }
}
