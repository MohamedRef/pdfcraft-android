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

    /** The shutter was pressed by hand. [signature] (#781) is the page's at the press, null when no page is shown then. */
    data class Shutter(override val at: Long, val signature: FloatArray? = null) : ScanAutoEvent

    /** The photo was written ([ok]) or failed; [signature] is the page's at the shot. */
    data class CaptureEnded(val ok: Boolean, val signature: FloatArray?, override val at: Long) : ScanAutoEvent

    /** The ring was tapped: no shot of this page. */
    data class Cancel(val signature: FloatArray?, override val at: Long) : ScanAutoEvent
}

/**
 * Why auto-capture's state last changed (#781), for the log: with these a phone run says what held
 * an automatic photo back, and what let a page be taken again.
 */
internal object ScanAutoWhy {
    const val NONE = "none"

    /** Ready for [ScanAutoRules.READY_MS]: the ring starts. */
    const val ARMED = "armed"

    /** The ring stopped: no page shown, the advice no longer Ready, or the phone moved. */
    const val LOST_PAGE = "lost_page"
    const val LOST_READY = "lost_ready"
    const val LOST_STILL = "lost_still"

    /** The ring filled: the photo is taken by itself. */
    const val FIRED = "fired"

    /** The shutter was pressed by hand. */
    const val SHUTTER = "shutter"

    /** The photo was written. */
    const val TAKEN = "taken"

    /** The photo failed. */
    const val FAILED = "failed"

    /** The ring was tapped. */
    const val SKIPPED = "skipped"

    /** Searching again: the page was gone, or another page is in view. */
    const val ABSENT = "absent"
    const val DIFFERS = "differs"

    /** The page that became ready again is the one last shot: no ring, no photo. */
    const val SAME_PAGE = "same_page"

    /** The page shot was not known at the shutter: the first page seen after it stands for it. */
    const val ADOPTED = "adopted"
}

/** Auto-capture's state; see [reduce]. */
internal data class ScanAutoState(
    val phase: ScanAutoPhase = ScanAutoPhase.Searching,
    /** Since when every condition has held (Searching), or null. */
    val readySince: Long? = null,
    /** When the countdown started (Arming). */
    val armedAt: Long = 0L,
    /** The signature of the page last shot or skipped (WaitingForNewPage); with [guards], also of the page being shot (Capturing). */
    val shotSignature: FloatArray? = null,
    /** Since when no page has been seen (WaitingForNewPage), or null. */
    val absentSince: Long? = null,
    /** Whether the shot in progress was started by auto-capture (not the shutter). */
    val auto: Boolean = false,
    /**
     * The capture checks (#781), a testing option: the page shot is the one in view at the shutter
     * and not the one in view when the file is written; a page not known then is learned from the
     * first one seen after; an automatic photo that failed is not tried again by itself; and a page
     * that looked different for a moment (a hand over it) is not taken a second time. Off: as before.
     */
    val guards: Boolean = false,
    /** With [guards]: the page last shot, kept while searching again after a difference, so that page is not armed for again. */
    val lastShot: FloatArray? = null,
    /** Why the state last changed ([ScanAutoWhy]), for the log. */
    val why: String = ScanAutoWhy.NONE,
) {
    /** How far the countdown is, 0 to 1 (Arming only). */
    fun armProgress(at: Long): Float = if (phase != ScanAutoPhase.Arming) 0f else ((at - armedAt).toFloat() / ScanAutoRules.ARM_MS).coerceIn(0f, 1f)

    /** A new state that keeps only the testing option. */
    private fun move(phase: ScanAutoPhase, why: String, armedAt: Long = 0L, shotSignature: FloatArray? = null, auto: Boolean = false, lastShot: FloatArray? = null) =
        ScanAutoState(phase, armedAt = armedAt, shotSignature = shotSignature, auto = auto, guards = guards, lastShot = lastShot, why = why)

    /**
     * The state after [event], and whether to take the photo now:
     * - Searching → Arming once ready and still for [ScanAutoRules.READY_MS];
     * - Arming → shoot after [ScanAutoRules.ARM_MS], or back to Searching if any condition is lost;
     * - the shutter by hand shoots in any state but Capturing (auto-capture does not shoot again);
     * - after the photo, wait until the page has been gone [ScanAutoRules.ABSENT_MS] or a different
     *   page is in view ([scanSignatureDiffers]) before searching again; a failed photo searches again.
     *
     * With [guards] (#781), four things differ, none of them a threshold:
     * - the page shot is the one in view when the photo was asked for ([ScanAutoEvent.Shutter.signature],
     *   or what the ring saw), not the one in view when the file is written about two seconds later,
     *   by when the next page may already lie there and would then never be taken by itself;
     * - a page not known at the shutter (no outline was shown) is learned from the first page seen
     *   after it, instead of an older page's signature, which made the page just shot look new;
     * - an automatic photo that failed waits for a new page, so a camera that keeps failing is not
     *   fired again every second; the shutter by hand still takes it;
     * - searching again because the page looked different, the page last shot is remembered: when
     *   it is that page that becomes ready (a hand passed over it), there is no ring and no photo.
     *   A page that was gone for [ScanAutoRules.ABSENT_MS] is new, as before.
     */
    fun reduce(event: ScanAutoEvent): Pair<ScanAutoState, Boolean> = when (event) {
        is ScanAutoEvent.Seen -> seen(event.inputs)
        is ScanAutoEvent.Shutter ->
            if (phase == ScanAutoPhase.Capturing) this to false
            else move(ScanAutoPhase.Capturing, ScanAutoWhy.SHUTTER, shotSignature = if (guards) event.signature else null) to false
        is ScanAutoEvent.CaptureEnded -> when {
            phase != ScanAutoPhase.Capturing -> this to false
            event.ok -> move(ScanAutoPhase.WaitingForNewPage, ScanAutoWhy.TAKEN, shotSignature = if (guards) shotSignature else event.signature) to false
            guards && auto -> move(ScanAutoPhase.WaitingForNewPage, ScanAutoWhy.FAILED, shotSignature = shotSignature) to false
            else -> move(ScanAutoPhase.Searching, ScanAutoWhy.FAILED) to false
        }
        is ScanAutoEvent.Cancel ->
            if (phase == ScanAutoPhase.Capturing) this to false
            else move(ScanAutoPhase.WaitingForNewPage, ScanAutoWhy.SKIPPED, shotSignature = event.signature) to false
    }

    /** With [guards]: whether the page in view ([signature]) is the one last shot. A page whose signature is not known is taken for that one. */
    private fun shotAlready(signature: FloatArray?): Boolean = guards && lastShot != null && !scanSignatureDiffers(lastShot, signature)

    private fun seen(i: ScanAutoInputs): Pair<ScanAutoState, Boolean> {
        val ok = i.ready && i.still && i.pagePresent
        return when (phase) {
            ScanAutoPhase.Searching -> {
                // the page last shot is forgotten once the frame has been empty long enough: what comes next is new
                val gone = when {
                    lastShot == null || i.pagePresent -> null
                    else -> absentSince ?: i.at
                }
                val forget = gone != null && i.at - gone >= ScanAutoRules.ABSENT_MS
                val base = if (lastShot == null) this else copy(absentSince = if (forget) null else gone, lastShot = if (forget) null else lastShot)
                when {
                    !ok -> base.copy(readySince = null) to false
                    readySince == null -> base.copy(readySince = i.at) to false
                    i.at - readySince < ScanAutoRules.READY_MS -> base to false
                    shotAlready(i.signature) -> move(ScanAutoPhase.WaitingForNewPage, ScanAutoWhy.SAME_PAGE, shotSignature = lastShot) to false
                    else -> move(ScanAutoPhase.Arming, ScanAutoWhy.ARMED, armedAt = i.at, lastShot = lastShot) to false
                }
            }
            ScanAutoPhase.Arming -> when {
                !ok -> move(
                    ScanAutoPhase.Searching,
                    when {
                        !i.pagePresent -> ScanAutoWhy.LOST_PAGE
                        !i.ready -> ScanAutoWhy.LOST_READY
                        else -> ScanAutoWhy.LOST_STILL
                    },
                    lastShot = lastShot,
                ) to false
                i.at - armedAt < ScanAutoRules.ARM_MS -> this to false
                shotAlready(i.signature) -> move(ScanAutoPhase.WaitingForNewPage, ScanAutoWhy.SAME_PAGE, shotSignature = lastShot) to false
                else -> move(ScanAutoPhase.Capturing, ScanAutoWhy.FIRED, shotSignature = if (guards) i.signature else null, auto = true) to true
            }
            // the page shot was not known at the shutter: the first one seen since stands for it
            ScanAutoPhase.Capturing ->
                if (guards && shotSignature == null && i.pagePresent && i.signature != null) copy(shotSignature = i.signature, why = ScanAutoWhy.ADOPTED) to false
                else this to false
            ScanAutoPhase.WaitingForNewPage -> when {
                !i.pagePresent -> {
                    val since = absentSince ?: i.at
                    if (i.at - since >= ScanAutoRules.ABSENT_MS) move(ScanAutoPhase.Searching, ScanAutoWhy.ABSENT) to false else copy(absentSince = since) to false
                }
                guards && shotSignature == null ->
                    if (i.signature != null) copy(shotSignature = i.signature, absentSince = null, why = ScanAutoWhy.ADOPTED) to false
                    else copy(absentSince = null) to false
                scanSignatureDiffers(shotSignature, i.signature) -> move(ScanAutoPhase.Searching, ScanAutoWhy.DIFFERS, lastShot = if (guards) shotSignature else null) to false
                else -> copy(absentSince = null) to false
            }
        }
    }
}

/** The signature's side: 16 × 16 samples of the page. */
internal const val SCAN_SIGNATURE_SIDE = 16

/**
 * The reach, in pixels of the analysis frame, of the averaged signature that is only measured
 * (#781): each of its samples is the mean of the pixels within this far of the sample's place,
 * every second one. One pixel per sample, as the signature in use takes, lands on a stroke or
 * beside it as the outline trembles; on the photos of the owner's kept scan of 2026-11-21 shrunk to the frame's
 * size that made the same page differ by up to 12 grey levels and a page and the next by as little
 * as 9, while the averaged one gave up to 3 against at least 5. Whether live frames say the same is
 * for the phone run: `camera_auto_rearm` reports both.
 */
internal const val SCAN_SIGNATURE_MEAN_PX = 8

/**
 * A page's signature (#296): 16 × 16 luminance samples of the inside of [quad] (8 shares of the
 * upright frame), with the mean taken off, so the same page under a little more or less light
 * keeps nearly the same signature. [rgba] is the frame as the camera gave it, [width] × [height]
 * packed, turned [rotation] degrees clockwise from upright (CameraX's rotationDegrees). The quad
 * is sampled by bilinear interpolation of its corners, a close enough stand-in for its
 * perspective at this size. Null when the input does not fit. With [reach] over 0 each sample is
 * the mean of the pixels within [reach] of its place, every second one ([SCAN_SIGNATURE_MEAN_PX]).
 */
internal fun scanPageSignature(rgba: ByteArray, width: Int, height: Int, rotation: Int, quad: FloatArray, reach: Int = 0): FloatArray? {
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
        var l = 0f
        if (reach <= 0) {
            val o = (y * width + x) * 4
            l = 0.299f * (rgba[o].toInt() and 0xFF) + 0.587f * (rgba[o + 1].toInt() and 0xFF) + 0.114f * (rgba[o + 2].toInt() and 0xFF)
        } else {
            var count = 0
            var dy = -reach
            while (dy <= reach) {
                var dx = -reach
                while (dx <= reach) {
                    val o = ((y + dy).coerceIn(0, height - 1) * width + (x + dx).coerceIn(0, width - 1)) * 4
                    l += 0.299f * (rgba[o].toInt() and 0xFF) + 0.587f * (rgba[o + 1].toInt() and 0xFF) + 0.114f * (rgba[o + 2].toInt() and 0xFF)
                    count++
                    dx += 2
                }
                dy += 2
            }
            l /= count
        }
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

/** How far two signatures are apart: their mean absolute difference in grey levels, or null when either is not known or they do not fit. */
internal fun scanSignatureDiff(a: FloatArray?, b: FloatArray?): Float? {
    if (a == null || b == null || a.size != b.size || a.isEmpty()) return null
    var d = 0f
    for (k in a.indices) d += abs(a[k] - b[k])
    return d / a.size
}

/** Whether two signatures are of different pages: their mean absolute difference is over [ScanAutoRules.NEW_PAGE_GREY]. Unknown is not different. */
internal fun scanSignatureDiffers(a: FloatArray?, b: FloatArray?): Boolean {
    val d = scanSignatureDiff(a, b) ?: return false
    return d > ScanAutoRules.NEW_PAGE_GREY
}

/**
 * Whether the phone is held still: the largest rotation rate over the last [WINDOW_MS] is under
 * [STILL_RAD_S] radians a second (the issue's values). Fed by the gyroscope; a phone without one
 * counts as still, and the outline's steadiness decides alone.
 */
internal class ScanStillness {
    private val samples = ArrayDeque<Pair<Long, Float>>()

    // every reading's rate while the camera showed, in steps of [BIN_RAD_S], for the log (#781)
    private val bins = IntArray(BINS + 1)
    private var readings = 0
    private var over = 0

    fun add(at: Long, x: Float, y: Float, z: Float) {
        val rate = sqrt(x * x + y * y + z * z)
        if (rate.isFinite()) {
            readings++
            if (rate >= STILL_RAD_S) over++
            bins[(rate / BIN_RAD_S).toInt().coerceIn(0, BINS)]++
        }
        samples.addLast(at to rate)
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

    /**
     * What the gyroscope read while the camera showed (#781), for setting [STILL_RAD_S] from a phone
     * held as the owner holds it: how many readings, the share at or over the limit in %, and the
     * rate half and nine tenths of the readings were under (rad/s, to [BIN_RAD_S]; -1 with no reading).
     */
    fun diag(): Array<Pair<String, Any?>> {
        fun under(share: Float): Double {
            if (readings == 0) return -1.0
            val wanted = (readings * share).toInt().coerceAtLeast(1)
            var seen = 0
            for (k in bins.indices) {
                seen += bins[k]
                if (seen >= wanted) return Math.round((k + 1) * BIN_RAD_S * 1000.0) / 1000.0
            }
            return Math.round((BINS + 1) * BIN_RAD_S * 1000.0) / 1000.0
        }
        return arrayOf(
            "gyro_readings" to readings, "gyro_over_pct" to (if (readings == 0) -1 else Math.round(over * 100.0 / readings).toInt()),
            "gyro_p50" to under(0.5f), "gyro_p90" to under(0.9f), "gyro_limit" to Math.round(STILL_RAD_S * 1000.0) / 1000.0,
        )
    }

    companion object {
        const val WINDOW_MS = 300L
        const val STILL_RAD_S = 0.08f

        /** The log's rate steps, and how many: readings past the last step are counted in it. */
        const val BIN_RAD_S = 0.01f
        const val BINS = 50
    }
}
