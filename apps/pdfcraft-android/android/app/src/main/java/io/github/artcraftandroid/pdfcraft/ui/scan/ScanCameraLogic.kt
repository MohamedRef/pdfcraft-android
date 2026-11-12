package io.github.artcraftandroid.pdfcraft.ui.scan

import kotlin.math.roundToInt

/*
 * The in-app scan camera's decisions (#294), as pure functions and a reducer so that they are
 * tested on the JVM: which camera a photo is taken with, the exposure the camera is asked for, and
 * the screen's states between the shutter and the saved file. CameraX itself is driven from
 * ScanCameraScreen, which only carries these decisions out.
 */

/** Which camera Scan takes its photos with: a setting of its own, so the phone's camera app is always one choice away. */
internal enum class ScanCameraChoice(val id: String) {
    /** PdfCraft's own camera screen (#294). */
    InApp("in_app"),

    /** The phone's camera app, as before #294. */
    PhoneApp("phone_app"),
    ;

    companion object {
        fun decode(id: String?): ScanCameraChoice = entries.firstOrNull { it.id == id } ?: InApp
    }
}

/** Where a request for a scan photo goes. */
internal enum class ScanCaptureRoute(val id: String) {
    /** PdfCraft's camera screen. */
    InApp("in_app"),

    /** The phone's camera app (ActivityResultContracts.TakePicture). */
    PhoneApp("phone_app"),

    /** Ask for the camera permission first, then decide again. */
    AskPermission("ask"),

    /**
     * The permission was refused: the camera screen offers Import photos and Open settings. The
     * phone's camera app cannot be started here either, because Android refuses IMAGE_CAPTURE to
     * an app that declares CAMERA and was not granted it.
     */
    Denied("denied"),

    /** The phone has no camera: the camera screen offers Import photos only. */
    ImportOnly("import_only"),
}

/**
 * Where a scan photo request goes, from whether the phone has a camera, whether the CAMERA
 * permission is granted, whether it was already asked for and refused in this request, and the
 * setting.
 */
internal fun scanCaptureRoute(hasCamera: Boolean, granted: Boolean, refused: Boolean, choice: ScanCameraChoice): ScanCaptureRoute = when {
    !hasCamera -> ScanCaptureRoute.ImportOnly
    granted -> if (choice == ScanCameraChoice.PhoneApp) ScanCaptureRoute.PhoneApp else ScanCaptureRoute.InApp
    refused -> ScanCaptureRoute.Denied
    else -> ScanCaptureRoute.AskPermission
}

/** The exposure compensation asked for: a third of a stop under, so white paper keeps faint pencil and stamps. */
internal const val SCAN_EXPOSURE_EV = -1.0 / 3.0

/**
 * The exposure compensation index closest to [targetEv] for a camera whose index moves by
 * [stepEv] stops and allows [range]; 0 when the camera has no compensation (a range of 0..0 or a
 * step that is not positive).
 */
internal fun scanExposureIndex(stepEv: Double, range: IntRange, targetEv: Double = SCAN_EXPOSURE_EV): Int {
    if (range.first >= range.last || !(stepEv > 0.0) || !stepEv.isFinite()) return 0
    val wanted = (targetEv / stepEv).roundToInt()
    return wanted.coerceIn(range.first, range.last)
}

/** The still's size in tenths of a megapixel, as the diagnostics log records it. */
internal fun scanMegapixelsTenths(width: Int, height: Int): Int =
    if (width <= 0 || height <= 0) 0 else (width.toLong() * height / 100_000.0).roundToInt()

/** Whether a capture left a usable file: it exists and is not empty (a capture cut short leaves neither). */
internal fun scanCaptureUsable(exists: Boolean, length: Long): Boolean = exists && length > 0L

/** Timings of the camera screen, in milliseconds. */
internal object ScanCameraTiming {
    /** A focus result older than this is not trusted for a capture: the camera focuses again first. */
    const val FOCUS_FRESH_MS = 2_000L

    /** How long a capture waits for that focus before it goes ahead anyway. */
    const val FOCUS_WAIT_MS = 800L

    /** A tap to focus is let go of after this, and continuous focus takes over. */
    const val TAP_FOCUS_HOLD_MS = 4_000L
}

/** What the camera screen is doing. */
internal enum class ScanCameraPhase { Idle, Focusing, Capturing, Finished }

/** What happened on the camera screen; every event carries its time, so the reducer needs no clock. */
internal sealed interface ScanCameraEvent {
    val at: Long

    /** The shutter was pressed. */
    data class Shutter(override val at: Long) : ScanCameraEvent

    /** The person tapped the viewfinder to focus there (the screen starts that focus itself). */
    data class TapFocus(override val at: Long) : ScanCameraEvent

    /** A focus (tapped or before a capture) ended, [ok] when the camera reported it focused. */
    data class FocusEnded(val ok: Boolean, override val at: Long) : ScanCameraEvent

    /** Time passed: lets a capture stop waiting for focus. */
    data class Tick(override val at: Long) : ScanCameraEvent

    /** The capture ended: [ok] when the photo was written. */
    data class CaptureEnded(val ok: Boolean, override val at: Long) : ScanCameraEvent

    /** Done (or Close) was pressed. */
    data class Done(override val at: Long) : ScanCameraEvent
}

/** What the screen must do after an event. */
internal enum class ScanCameraEffect {
    /** Focus and meter at the middle of the frame. */
    FocusCentre,

    /** Take the photo. */
    Capture,

    /** Leave the camera screen. */
    Finish,

    /** Tell the person the photo could not be taken; the camera stays usable. */
    ShowError,
}

/** The camera screen's state; see [reduce]. */
internal data class ScanCameraState(
    val phase: ScanCameraPhase = ScanCameraPhase.Idle,
    /** When the current focus started (Focusing only). */
    val focusStarted: Long = 0L,
    /** A capture waits for the focus in progress. */
    val captureQueued: Boolean = false,
    /** Done was pressed during a capture: the screen closes once the capture ends. */
    val doneQueued: Boolean = false,
    /** When the last focus ended, and whether it focused; null before the first. */
    val lastFocusAt: Long? = null,
    val lastFocusOk: Boolean = false,
) {
    /** Whether the shutter can be pressed now (it is shown as busy otherwise). */
    val shutterReady: Boolean get() = phase == ScanCameraPhase.Idle || (phase == ScanCameraPhase.Focusing && !captureQueued)

    /** Whether the last focus is recent and good enough to capture without focusing again. */
    fun focusFresh(at: Long): Boolean = lastFocusOk && lastFocusAt != null && at - lastFocusAt <= ScanCameraTiming.FOCUS_FRESH_MS

    /**
     * The state after [event], and what the screen must do:
     * - the shutter captures at once when the last focus is fresh, otherwise it focuses at the
     *   middle first and captures when that focus ends or [ScanCameraTiming.FOCUS_WAIT_MS] pass;
     * - a shutter press during a capture, or while a capture already waits, is ignored;
     * - a failed capture returns to Idle with an error shown, and the camera stays usable;
     * - Done during a capture waits for it, so a photo being written is not lost.
     */
    fun reduce(event: ScanCameraEvent): Pair<ScanCameraState, List<ScanCameraEffect>> = when (event) {
        is ScanCameraEvent.Shutter -> when {
            phase == ScanCameraPhase.Finished || phase == ScanCameraPhase.Capturing || captureQueued -> this to emptyList()
            phase == ScanCameraPhase.Focusing -> copy(captureQueued = true) to emptyList()
            focusFresh(event.at) -> copy(phase = ScanCameraPhase.Capturing) to listOf(ScanCameraEffect.Capture)
            else -> copy(phase = ScanCameraPhase.Focusing, focusStarted = event.at, captureQueued = true) to listOf(ScanCameraEffect.FocusCentre)
        }
        is ScanCameraEvent.TapFocus -> when (phase) {
            ScanCameraPhase.Idle -> copy(phase = ScanCameraPhase.Focusing, focusStarted = event.at) to emptyList()
            // a new tap moves the focus; a capture already waiting keeps waiting, from the old start
            else -> this to emptyList()
        }
        is ScanCameraEvent.FocusEnded -> {
            val focused = copy(lastFocusAt = event.at, lastFocusOk = event.ok)
            when {
                phase != ScanCameraPhase.Focusing -> focused to emptyList()
                captureQueued -> focused.copy(phase = ScanCameraPhase.Capturing, captureQueued = false) to listOf(ScanCameraEffect.Capture)
                else -> focused.copy(phase = ScanCameraPhase.Idle) to emptyList()
            }
        }
        is ScanCameraEvent.Tick -> when {
            phase == ScanCameraPhase.Focusing && captureQueued && event.at - focusStarted >= ScanCameraTiming.FOCUS_WAIT_MS ->
                copy(phase = ScanCameraPhase.Capturing, captureQueued = false) to listOf(ScanCameraEffect.Capture)
            else -> this to emptyList()
        }
        is ScanCameraEvent.CaptureEnded -> when {
            phase != ScanCameraPhase.Capturing -> this to emptyList()
            doneQueued -> copy(phase = ScanCameraPhase.Finished, doneQueued = false) to (if (event.ok) emptyList() else listOf(ScanCameraEffect.ShowError)) + ScanCameraEffect.Finish
            event.ok -> copy(phase = ScanCameraPhase.Idle) to emptyList()
            else -> copy(phase = ScanCameraPhase.Idle) to listOf(ScanCameraEffect.ShowError)
        }
        is ScanCameraEvent.Done -> when (phase) {
            ScanCameraPhase.Capturing -> copy(doneQueued = true) to emptyList()
            ScanCameraPhase.Finished -> this to emptyList()
            else -> copy(phase = ScanCameraPhase.Finished, captureQueued = false) to listOf(ScanCameraEffect.Finish)
        }
    }
}

/**
 * The quarter turn the phone is held at, in degrees clockwise from portrait (0, 90, 180 or 270),
 * from an orientation sensor reading in degrees; null when the phone lies flat (the reading is
 * unknown) so the last turn is kept. Each turn takes the readings within 45° of it.
 */
internal fun scanDeviceTurn(sensorDegrees: Int): Int? =
    if (sensorDegrees < 0) null else ((sensorDegrees % 360 + 45) / 90 % 4) * 90

/**
 * The display rotation a photo is stored for, as `Surface.ROTATION_*` (0 to 3), when the phone is
 * turned [turnDegrees] clockwise: the sensor's clockwise turn is the display's counter-clockwise one.
 */
internal fun scanTargetRotation(turnDegrees: Int): Int = when (turnDegrees) {
    90 -> 3
    180 -> 2
    270 -> 1
    else -> 0
}
