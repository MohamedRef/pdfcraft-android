package io.github.artcraftandroid.pdfcraft.ui.scan

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/*
 * Auto-capture (#296), as pure code tested on the JVM: when to shoot by itself, how not to shoot
 * the same page twice, and whether the phone is held still. The camera screen feeds it the live
 * outline (#295), the gyroscope and the capture's outcome, and carries out its one effect: take
 * the photo through the same path as the shutter.
 *
 * The issue's retake of a blurred photo is not here: the sharpness measure (#389) does not tell
 * motion blur from a sharp page and its threshold comes from drawn pages, so a photo's sharpness
 * is only recorded (log and kept scans) until real sharp and shaken photos set it.
 */

/** Where auto-capture is. */
internal enum class ScanAutoPhase(val id: String) {
    /** Waiting for a steady, framed page and a still phone. */
    Searching("searching"),

    /** All conditions held: the ring around the shutter fills before the shot. */
    Arming("arming"),

    /** The photo is being taken (by itself or by the shutter). */
    Capturing("capturing"),

    /** The photo is taken: waiting for the page to change, so it is not shot twice. */
    WaitingForNewPage("waiting"),
}

/** Auto-capture's timings and thresholds: the issue's starting values. */
internal object ScanAutoRules {
    /** How long the page must be ready (steady, framed, the phone still) before arming. */
    const val READY_MS = 500L

    /** The ring's countdown before the shot. */
    const val ARM_MS = 600L

    /** How long the page must be gone for the next one to count as new. */
    const val ABSENT_MS = 400L

    /** A page whose 16 × 16 signature differs by more than this many grey levels on average is a new page. */
    const val NEW_PAGE_GREY = 12f
}

/** What auto-capture sees at one moment. */
internal data class ScanAutoInputs(
    val at: Long,
    /** The live advice is Ready: a steady page, well framed. */
    val ready: Boolean,
    /** The phone is held still (gyroscope), or there is no gyroscope. */
    val still: Boolean,
    /** A page outline is shown. */
    val pagePresent: Boolean,
    /** The page's signature ([scanPageSignature]), or null when none is known. */
    val signature: FloatArray?,
)

/** Auto-capture's events. */
internal sealed interface ScanAutoEvent {
    val at: Long

    /** A new moment: what the camera sees now. */
    data class Seen(val inputs: ScanAutoInputs) : ScanAutoEvent {
        override val at: Long get() = inputs.at
    }

    /** The shutter was pressed by hand. */
    data class Shutter(override val at: Long) : ScanAutoEvent

    /** The photo was written ([ok]) or failed; [signature] is the page's at the shot. */
    data class CaptureEnded(val ok: Boolean, val signature: FloatArray?, override val at: Long) : ScanAutoEvent

    /** The ring was tapped: no shot of this page. */
    data class Cancel(val signature: FloatArray?, override val at: Long) : ScanAutoEvent
}

/** Auto-capture's state; see [reduce]. */
internal data class ScanAutoState(
    val phase: ScanAutoPhase = ScanAutoPhase.Searching,
    /** Since when every condition has held (Searching), or null. */
    val readySince: Long? = null,
    /** When the countdown started (Arming). */
    val armedAt: Long = 0L,
    /** The signature of the page last shot or skipped (WaitingForNewPage). */
    val shotSignature: FloatArray? = null,
    /** Since when no page has been seen (WaitingForNewPage), or null. */
    val absentSince: Long? = null,
    /** Whether the shot in progress was started by auto-capture (not the shutter). */
    val auto: Boolean = false,
) {
    /** How far the countdown is, 0 to 1 (Arming only). */
    fun armProgress(at: Long): Float = if (phase != ScanAutoPhase.Arming) 0f else ((at - armedAt).toFloat() / ScanAutoRules.ARM_MS).coerceIn(0f, 1f)

    /**
     * The state after [event], and whether to take the photo now:
     * - Searching → Arming once ready and still for [ScanAutoRules.READY_MS];
     * - Arming → shoot after [ScanAutoRules.ARM_MS], or back to Searching if any condition is lost;
     * - the shutter by hand shoots in any state but Capturing (auto-capture does not shoot again);
     * - after the photo, wait until the page has been gone [ScanAutoRules.ABSENT_MS] or a different
     *   page is in view ([scanSignatureDiffers]) before searching again; a failed photo searches again.
     */
    fun reduce(event: ScanAutoEvent): Pair<ScanAutoState, Boolean> = when (event) {
        is ScanAutoEvent.Seen -> seen(event.inputs)
        is ScanAutoEvent.Shutter -> if (phase == ScanAutoPhase.Capturing) this to false else ScanAutoState(ScanAutoPhase.Capturing, auto = false) to false
        is ScanAutoEvent.CaptureEnded -> when {
            phase != ScanAutoPhase.Capturing -> this to false
            event.ok -> ScanAutoState(ScanAutoPhase.WaitingForNewPage, shotSignature = event.signature) to false
            else -> ScanAutoState() to false
        }
        is ScanAutoEvent.Cancel -> if (phase == ScanAutoPhase.Capturing) this to false else ScanAutoState(ScanAutoPhase.WaitingForNewPage, shotSignature = event.signature) to false
    }

    private fun seen(i: ScanAutoInputs): Pair<ScanAutoState, Boolean> {
        val ok = i.ready && i.still && i.pagePresent
        return when (phase) {
            ScanAutoPhase.Searching -> when {
                !ok -> copy(readySince = null) to false
                readySince == null -> copy(readySince = i.at) to false
                i.at - readySince >= ScanAutoRules.READY_MS -> ScanAutoState(ScanAutoPhase.Arming, armedAt = i.at) to false
                else -> this to false
            }
            ScanAutoPhase.Arming -> when {
                !ok -> ScanAutoState() to false
                i.at - armedAt >= ScanAutoRules.ARM_MS -> ScanAutoState(ScanAutoPhase.Capturing, auto = true) to true
                else -> this to false
            }
            ScanAutoPhase.Capturing -> this to false
            ScanAutoPhase.WaitingForNewPage -> when {
                !i.pagePresent -> {
                    val since = absentSince ?: i.at
                    if (i.at - since >= ScanAutoRules.ABSENT_MS) ScanAutoState() to false else copy(absentSince = since) to false
                }
                scanSignatureDiffers(shotSignature, i.signature) -> ScanAutoState() to false
                else -> copy(absentSince = null) to false
            }
        }
    }
}

/** The signature's side: 16 × 16 samples of the page. */
internal const val SCAN_SIGNATURE_SIDE = 16

/**
 * A page's signature (#296): 16 × 16 luminance samples of the inside of [quad] (8 shares of the
 * upright frame), with the mean taken off, so the same page under a little more or less light
 * keeps nearly the same signature. [rgba] is the frame as the camera gave it, [width] × [height]
 * packed, turned [rotation] degrees clockwise from upright (CameraX's rotationDegrees). The quad
 * is sampled by bilinear interpolation of its corners, a close enough stand-in for its
 * perspective at this size. Null when the input does not fit.
 */
internal fun scanPageSignature(rgba: ByteArray, width: Int, height: Int, rotation: Int, quad: FloatArray): FloatArray? {
    if (width <= 0 || height <= 0 || rgba.size < width * height * 4 || quad.size != 8) return null
    if (rotation !in setOf(0, 90, 180, 270)) return null
    val n = SCAN_SIGNATURE_SIDE
    val out = FloatArray(n * n)
    var sum = 0f
    for (j in 0 until n) for (i in 0 until n) {
        // a little inside the quad, at the middle of each cell
        val s = 0.05f + 0.9f * (i + 0.5f) / n
        val t = 0.05f + 0.9f * (j + 0.5f) / n
        val ux = (1 - s) * (1 - t) * quad[0] + s * (1 - t) * quad[2] + s * t * quad[4] + (1 - s) * t * quad[6]
        val uy = (1 - s) * (1 - t) * quad[1] + s * (1 - t) * quad[3] + s * t * quad[5] + (1 - s) * t * quad[7]
        val (rx, ry) = scanUprightToFrame(ux, uy, rotation)
        val x = (rx * width).toInt().coerceIn(0, width - 1)
        val y = (ry * height).toInt().coerceIn(0, height - 1)
        val o = (y * width + x) * 4
        val l = 0.299f * (rgba[o].toInt() and 0xFF) + 0.587f * (rgba[o + 1].toInt() and 0xFF) + 0.114f * (rgba[o + 2].toInt() and 0xFF)
        out[j * n + i] = l
        sum += l
    }
    val mean = sum / out.size
    for (k in out.indices) out[k] -= mean
    return out
}

/** A point in shares of the upright frame, in shares of the frame as the camera gave it (turned [rotation] clockwise to be upright). */
internal fun scanUprightToFrame(ux: Float, uy: Float, rotation: Int): Pair<Float, Float> = when (rotation) {
    90 -> uy to 1f - ux
    180 -> 1f - ux to 1f - uy
    270 -> 1f - uy to ux
    else -> ux to uy
}

/** Whether two signatures are of different pages: their mean absolute difference is over [ScanAutoRules.NEW_PAGE_GREY]. Unknown is not different. */
internal fun scanSignatureDiffers(a: FloatArray?, b: FloatArray?): Boolean {
    if (a == null || b == null || a.size != b.size || a.isEmpty()) return false
    var d = 0f
    for (k in a.indices) d += abs(a[k] - b[k])
    return d / a.size > ScanAutoRules.NEW_PAGE_GREY
}

/**
 * Whether the phone is held still: the largest rotation rate over the last [WINDOW_MS] is under
 * [STILL_RAD_S] radians a second (the issue's values). Fed by the gyroscope; a phone without one
 * counts as still, and the outline's steadiness decides alone.
 */
internal class ScanStillness {
    private val samples = ArrayDeque<Pair<Long, Float>>()

    fun add(at: Long, x: Float, y: Float, z: Float) {
        samples.addLast(at to sqrt(x * x + y * y + z * z))
        while (samples.isNotEmpty() && at - samples.first().first > WINDOW_MS) samples.removeFirst()
    }

    /** Still at [at]: readings cover the window and none is too fast. No readings at all: still (no gyroscope). */
    fun still(at: Long): Boolean {
        if (samples.isEmpty()) return true
        if (at - samples.last().first > WINDOW_MS) return true
        var m = 0f
        for ((t, w) in samples) if (at - t <= WINDOW_MS) m = max(m, w)
        return m < STILL_RAD_S
    }

    companion object {
        const val WINDOW_MS = 300L
        const val STILL_RAD_S = 0.08f
    }
}
