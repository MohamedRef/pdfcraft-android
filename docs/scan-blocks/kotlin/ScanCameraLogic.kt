package io.github.artcraftandroid.pdfcraft.ui.scan

import kotlin.math.hypot
import kotlin.math.roundToInt

/*
 * The decisions of the in-app scanner camera (#294, spec S08), with no Android import: which
 * still size to take, the exposure step, the 35 mm equivalent focal length, the state of the
 * screen and its reducer, what to offer when there is no camera or no permission, and whether a
 * captured file is kept. The CameraX calls live in ScanCameraScreen.kt.
 */

/** Target exposure compensation in EV: a little under the meter, so white paper keeps its pencil (S08 section 3). */
const val SCAN_EXPOSURE_TARGET_EV: Double = -0.33

/** How long a shutter press may wait for a focus run to finish before the photo is taken anyway (S08 section 3). */
const val SCAN_FOCUS_WAIT_MS: Long = 800L

/** A focus result older than this is stale, so the camera meters again before the photo (S08 section 3). */
const val SCAN_FOCUS_STALE_MS: Long = 2000L

/** A tap-to-focus point is released after this long, so the lens goes back to continuous focus (S08 section 3). */
const val SCAN_TAP_FOCUS_RELEASE_MS: Long = 4000L

/** Ringer mode value for "normal" (AudioManager.RINGER_MODE_NORMAL). Only a normal ringer plays the shutter sound. */
const val SCAN_RINGER_NORMAL: Int = 2

/** Physical width of the 35 mm film frame in millimetres: the diagonal of a 36 x 24 mm frame is 43.27 mm. */
const val SCAN_FULL_FRAME_DIAGONAL_MM: Double = 43.2666

/** A still size the camera offers, in pixels. */
data class ScanCaptureSize(val width: Int, val height: Int) {
    /** The size in megapixels. */
    val megapixels: Double get() = width.toDouble() * height.toDouble() / 1_000_000.0
}

/**
 * The still size to take: the largest 4:3 size the camera offers (either orientation), or, when
 * there is none, the largest size of all. Null for an empty list or sizes that are not positive.
 * The largest size is the one that keeps the most pencil on a page (S08 section 3).
 */
fun chooseScanCaptureSize(sizes: List<ScanCaptureSize>): ScanCaptureSize? {
    val valid = sizes.filter { it.width > 0 && it.height > 0 }
    if (valid.isEmpty()) return null
    val fourByThree = valid.filter { isFourByThree(it) }
    val pool = if (fourByThree.isNotEmpty()) fourByThree else valid
    return pool.maxByOrNull { it.width.toLong() * it.height.toLong() }
}

/** Whether a size is 4:3 in either orientation, exactly (no rounding). */
private fun isFourByThree(size: ScanCaptureSize): Boolean {
    val long = maxOf(size.width, size.height).toLong()
    val short = minOf(size.width, size.height).toLong()
    return long * 3L == short * 4L
}

/**
 * The exposure compensation index for the camera's step and range (S08 section 3):
 * round(-0.33 / step), clamped to [minIndex]..[maxIndex]. A step that is not positive, or a range
 * of 0..0 ("not supported"), gives 0: no compensation.
 */
fun scanExposureIndex(stepEv: Double, minIndex: Int, maxIndex: Int): Int {
    if (minIndex == 0 && maxIndex == 0) return 0
    if (!(stepEv > 0.0) || stepEv.isNaN() || stepEv.isInfinite()) return 0
    val wanted = (SCAN_EXPOSURE_TARGET_EV / stepEv).roundToInt()
    val low = minOf(minIndex, maxIndex)
    val high = maxOf(minIndex, maxIndex)
    return wanted.coerceIn(low, high)
}

/**
 * The 35 mm equivalent focal length in millimetres, from the lens's physical focal length and the
 * sensor's physical width and height in millimetres: focal * 43.27 / sensor diagonal (S08
 * section 7). Null when any value is not positive or is not a finite number.
 */
fun scanFocal35mm(focalMm: Float, sensorWidthMm: Float, sensorHeightMm: Float): Float? {
    val f = focalMm.toDouble()
    val w = sensorWidthMm.toDouble()
    val h = sensorHeightMm.toDouble()
    if (!(f > 0.0 && w > 0.0 && h > 0.0)) return null
    if (f.isInfinite() || w.isInfinite() || h.isInfinite()) return null
    val diagonal = hypot(w, h)
    val result = f * SCAN_FULL_FRAME_DIAGONAL_MM / diagonal
    return result.toFloat()
}

/** Whether the last focus run is too old to trust, or did not find focus, so the camera meters again. */
fun scanNeedsFocusBeforeShot(lastFocusAgeMs: Long, lastFocusOk: Boolean): Boolean =
    !lastFocusOk || lastFocusAgeMs > SCAN_FOCUS_STALE_MS

/** Whether the shutter sound plays: only when the ringer is on normal (not silent, not vibrating). */
fun scanShouldPlayShutter(ringerMode: Int): Boolean = ringerMode == SCAN_RINGER_NORMAL

/** Whether a captured file is kept: it must exist and must hold bytes. A zero-length file is discarded (S08 section 5). */
fun scanKeepCaptureFile(exists: Boolean, length: Long): Boolean = exists && length > 0L

/** Whether the app has the camera permission, and whether it may still ask. */
enum class ScanPermission {
    /** Not asked yet. */
    UNASKED,

    /** Refused once; the system dialog can be shown again. */
    DENIED,

    /** Refused for good ("don't ask again"): only Settings can grant it. */
    DENIED_FOR_GOOD,

    /** Granted. */
    GRANTED,
}

/** Whether the camera of the screen has started. */
enum class ScanCameraPhase {
    STARTING,
    READY,
    FAILED,
}

/** The shutter's phase. [ERROR] shows a message and goes back to [IDLE] when the message is dismissed. */
enum class ScanShutterPhase {
    IDLE,
    FOCUSING,
    CAPTURING,
    SAVING,
    ERROR,
}

/** The state of the scanner camera screen (S08 section 5). */
data class ScanCameraState(
    val permission: ScanPermission = ScanPermission.UNASKED,
    val camera: ScanCameraPhase = ScanCameraPhase.STARTING,
    val shutter: ScanShutterPhase = ScanShutterPhase.IDLE,
    /** A shutter press made while a focus run is going: the photo is taken when the run ends. */
    val shotQueued: Boolean = false,
    /** Done was tapped during a capture: the screen finishes when nothing is in flight. */
    val finishRequested: Boolean = false,
    val torchOn: Boolean = false,
    val zoomRatio: Float = 1f,
    /** Tap-to-focus point as shares of the preview, 0 to 1 each; null when none. */
    val focusX: Float? = null,
    val focusY: Float? = null,
    /** Pages taken in this visit to the camera. */
    val pagesTaken: Int = 0,
)

/** What the screen tells the reducer. */
sealed interface ScanCameraEvent {
    /** The system permission dialog answered. [permanently] is true when the person ticked "don't ask again". */
    data class PermissionAnswered(val granted: Boolean, val permanently: Boolean) : ScanCameraEvent

    /** The camera is bound and streaming. */
    data object CameraStarted : ScanCameraEvent

    /** Binding the camera failed. */
    data object CameraFailed : ScanCameraEvent

    /** The shutter was tapped. */
    data object ShutterTapped : ScanCameraEvent

    /** A focus run ended: [found] is true when the lens locked. Also used when [SCAN_FOCUS_WAIT_MS] ran out. */
    data class FocusEnded(val found: Boolean) : ScanCameraEvent

    /** A tap on the preview to focus at ([x], [y]), shares 0 to 1. */
    data class TapFocus(val x: Float, val y: Float) : ScanCameraEvent

    /** The still was written to disk. [kept] is false when the file was empty or missing. */
    data class PhotoTaken(val kept: Boolean) : ScanCameraEvent

    /** Taking the still failed. */
    data object CaptureFailed : ScanCameraEvent

    /** The page was handed to the screen's save path (the file is written, the thumbnail moved). */
    data object SaveDone : ScanCameraEvent

    /** The error message was dismissed. */
    data object ErrorDismissed : ScanCameraEvent

    /** Torch button tapped. */
    data object TorchTapped : ScanCameraEvent

    /** Pinch changed the zoom to [ratio], which is kept within [min]..[max]. */
    data class ZoomChanged(val ratio: Float, val min: Float, val max: Float) : ScanCameraEvent

    /** Done was tapped. */
    data object DoneTapped : ScanCameraEvent
}

/**
 * The reducer: the next state after [event]. Pure; it never throws. Rules (S08 section 5):
 * a shutter press while focusing is queued; a second press while a photo is in flight is
 * ignored; an error goes to [ScanShutterPhase.ERROR] and back to idle when dismissed; Done during
 * a capture waits for it.
 */
fun reduceScanCamera(state: ScanCameraState, event: ScanCameraEvent): ScanCameraState = when (event) {
    is ScanCameraEvent.PermissionAnswered -> state.copy(
        permission = when {
            event.granted -> ScanPermission.GRANTED
            event.permanently -> ScanPermission.DENIED_FOR_GOOD
            else -> ScanPermission.DENIED
        },
    )
    ScanCameraEvent.CameraStarted -> state.copy(camera = ScanCameraPhase.READY)
    ScanCameraEvent.CameraFailed -> state.copy(camera = ScanCameraPhase.FAILED, shutter = ScanShutterPhase.IDLE, shotQueued = false)
    ScanCameraEvent.ShutterTapped -> shutter(state)
    is ScanCameraEvent.FocusEnded -> focusEnded(state)
    is ScanCameraEvent.TapFocus -> if (state.camera != ScanCameraPhase.READY) state else state.copy(
        focusX = event.x.coerceIn(0f, 1f),
        focusY = event.y.coerceIn(0f, 1f),
        shutter = if (state.shutter == ScanShutterPhase.IDLE) ScanShutterPhase.FOCUSING else state.shutter,
    )
    is ScanCameraEvent.PhotoTaken -> photoTaken(state, event.kept)
    ScanCameraEvent.CaptureFailed -> state.copy(shutter = ScanShutterPhase.ERROR, shotQueued = false)
    ScanCameraEvent.SaveDone -> if (state.shutter == ScanShutterPhase.SAVING) state.copy(shutter = ScanShutterPhase.IDLE) else state
    ScanCameraEvent.ErrorDismissed -> if (state.shutter == ScanShutterPhase.ERROR) state.copy(shutter = ScanShutterPhase.IDLE) else state
    ScanCameraEvent.TorchTapped -> if (state.camera == ScanCameraPhase.READY) state.copy(torchOn = !state.torchOn) else state
    is ScanCameraEvent.ZoomChanged -> state.copy(zoomRatio = event.ratio.coerceIn(event.min, event.max))
    ScanCameraEvent.DoneTapped -> state.copy(finishRequested = true)
}

/** Shutter press: starts a capture, queues one behind focus, or is ignored while a photo is in flight. */
private fun shutter(state: ScanCameraState): ScanCameraState {
    if (state.camera != ScanCameraPhase.READY) return state
    return when (state.shutter) {
        ScanShutterPhase.IDLE, ScanShutterPhase.ERROR -> state.copy(shutter = ScanShutterPhase.CAPTURING)
        ScanShutterPhase.FOCUSING -> state.copy(shotQueued = true)
        ScanShutterPhase.CAPTURING, ScanShutterPhase.SAVING -> state
    }
}

/** A focus run ended: a queued press now takes its photo; otherwise focusing returns to idle. */
private fun focusEnded(state: ScanCameraState): ScanCameraState {
    if (state.shutter != ScanShutterPhase.FOCUSING) return state
    return if (state.shotQueued) {
        state.copy(shutter = ScanShutterPhase.CAPTURING, shotQueued = false)
    } else {
        state.copy(shutter = ScanShutterPhase.IDLE)
    }
}

/** The still is on disk: a kept file counts as a page and moves on to saving; an empty one is dropped. */
private fun photoTaken(state: ScanCameraState, kept: Boolean): ScanCameraState {
    if (state.shutter != ScanShutterPhase.CAPTURING) return state
    return state.copy(
        shutter = if (kept) ScanShutterPhase.SAVING else ScanShutterPhase.ERROR,
        pagesTaken = if (kept) state.pagesTaken + 1 else state.pagesTaken,
    )
}

/** Whether the screen should now finish: Done was asked for and no photo is in flight or queued. */
fun scanShouldFinish(state: ScanCameraState): Boolean =
    state.finishRequested && (state.shutter == ScanShutterPhase.IDLE || state.shutter == ScanShutterPhase.ERROR) && !state.shotQueued

/** A permission row of the screen: what the screen can offer (S08 section 6). */
enum class ScanAction {
    /** The in-app camera. */
    IN_APP_CAMERA,

    /** The phone's camera app, through the older TakePicture path. */
    PHONE_CAMERA_APP,

    /** Photos from the system picker (no permission needed). */
    IMPORT,

    /** The app's settings page, where the permission can be granted. */
    OPEN_SETTINGS,
}

/** The Settings choice of the camera: the in-app camera or the phone's camera app. */
enum class ScanCameraSetting {
    IN_APP,
    PHONE_APP,
}

/**
 * The actions the screen offers (S08 section 6, the permission decision table):
 * no camera on the device: Import only; the permission not granted: Import and Open settings;
 * granted: the camera the Settings choice names, and Import. The phone's camera app is offered
 * only while the permission is granted, as Settings says.
 */
fun scanActions(declared: Boolean, granted: Boolean, hasCamera: Boolean, setting: ScanCameraSetting): Set<ScanAction> {
    if (!hasCamera) return setOf(ScanAction.IMPORT)
    if (declared && !granted) return setOf(ScanAction.IMPORT, ScanAction.OPEN_SETTINGS)
    return when (setting) {
        ScanCameraSetting.PHONE_APP -> setOf(ScanAction.PHONE_CAMERA_APP, ScanAction.IMPORT)
        ScanCameraSetting.IN_APP -> setOf(ScanAction.IN_APP_CAMERA, ScanAction.IMPORT)
    }
}
