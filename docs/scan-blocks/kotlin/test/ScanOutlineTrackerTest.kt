package io.github.artcraftandroid.pdfcraft.ui.scan

import kotlin.math.abs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the outline tracker (issue #295, S09 sections 3 and 4). The frame is 4:3, the tracker's
 * default. A page at 0.2 to 0.8 in each direction is 0.6 of the frame wide and high: its quad
 * covers 36% of the frame, its sides are 0.8 and 0.6 frame heights, and it touches no edge. Frames
 * come 33 ms apart unless a test says otherwise.
 */
class ScanOutlineTrackerTest {

    /** A quad from its top left and bottom right corners. */
    private fun pageAt(left: Float, top: Float, right: Float, bottom: Float): FloatArray {
        return floatArrayOf(left, top, right, top, right, bottom, left, bottom)
    }

    private val page: FloatArray = pageAt(0.2f, 0.2f, 0.8f, 0.8f)

    private fun assertCorners(expected: FloatArray, actual: FloatArray?, tolerance: Float) {
        assertTrue("the outline has no corners", actual != null)
        val got: FloatArray = actual ?: FloatArray(0)
        for (i in 0 until 8) {
            assertTrue("value $i expected ${expected[i]} but was ${got[i]}", abs(got[i] - expected[i]) <= tolerance)
        }
    }

    /** Largest change of any coordinate between two quads. */
    private fun largestStep(a: FloatArray, b: FloatArray): Float {
        var largest = 0f
        for (i in 0 until 8) {
            val d: Float = abs(a[i] - b[i])
            if (d > largest) largest = d
        }
        return largest
    }

    /** A fixed-seed 64-bit linear congruential generator, so the noise is the same on every run. */
    private class Lcg(seed: Long) {
        private var state: Long = seed

        /** The next value, from -amplitude to +amplitude. */
        fun signed(amplitude: Float): Float {
            state = state * 6364136223846793005L + 1442695040888963407L
            val unit: Float = (state ushr 40).toInt() / 16777216f
            return (2f * unit - 1f) * amplitude
        }
    }

    @Test
    fun theFirstDetectionIsShownAtOnceWithNoSmoothing() {
        val tracker = ScanOutlineTracker()
        val out: ScanOutline = tracker.onFrame(page, 0.9f, 100f, 0L)
        assertCorners(page, out.corners, 1e-6f)
        assertTrue(out.alpha == 1f)
        assertFalse(out.stable)
    }

    @Test
    fun eachCornerMovesAlphaOfTheWayTowardANearbyDetection() {
        // Every corner moves 0.02 to the right: 0.2 + 0.55 * 0.02 = 0.211 for the top left corner.
        val tracker = ScanOutlineTracker()
        tracker.onFrame(page, 0.9f, 100f, 0L)
        val shifted: FloatArray = pageAt(0.22f, 0.2f, 0.82f, 0.8f)
        val out: ScanOutline = tracker.onFrame(shifted, 0.9f, 100f, 33L)
        assertCorners(pageAt(0.211f, 0.2f, 0.811f, 0.8f), out.corners, 1e-4f)
    }

    @Test
    fun aJumpToAnotherPageIsFollowedAtOnceAndStartsStabilityAgain() {
        // Corners move 0.3 to the right: 0.3 * 1.333 / 1.667 = 0.24 of the diagonal, above 0.15.
        val tracker = ScanOutlineTracker()
        tracker.onFrame(page, 0.9f, 100f, 0L)
        val other: FloatArray = pageAt(0.5f, 0.2f, 0.9f, 0.8f)
        val out: ScanOutline = tracker.onFrame(other, 0.9f, 100f, 33L)
        assertCorners(other, out.corners, 1e-6f)
        assertFalse(out.stable)
    }

    @Test
    fun aDetectionAfterAGapOfMoreThanHalfASecondIsTakenAtOnce() {
        // Last detection at 0 ms, the next at 600 ms: older than 500 ms, so 0.22 with no smoothing.
        val tracker = ScanOutlineTracker()
        tracker.onFrame(page, 0.9f, 100f, 0L)
        val shifted: FloatArray = pageAt(0.22f, 0.2f, 0.82f, 0.8f)
        val out: ScanOutline = tracker.onFrame(shifted, 0.9f, 100f, 600L)
        assertCorners(shifted, out.corners, 1e-6f)
    }

    @Test
    fun aJitteringPageMovesLessThanHalfAsMuchOnScreenAsItDoesInTheDetections() {
        // Noise of plus or minus 2% on every coordinate, seed 7, 60 frames. Movement is measured
        // from frame 10 on, as the largest coordinate change between consecutive frames.
        val tracker = ScanOutlineTracker()
        val noise = Lcg(7L)
        var rawTotal = 0f
        var outTotal = 0f
        var counted = 0
        var previousRaw: FloatArray? = null
        var previousOut: FloatArray? = null
        for (frame in 0 until 60) {
            val det = FloatArray(8) { j -> page[j] + noise.signed(0.02f) }
            val out: FloatArray? = tracker.onFrame(det, 0.9f, 100f, frame * 33L).corners
            val prevRaw: FloatArray? = previousRaw
            val prevOut: FloatArray? = previousOut
            if (frame >= 10 && prevRaw != null && prevOut != null && out != null) {
                rawTotal += largestStep(det, prevRaw)
                outTotal += largestStep(out, prevOut)
                counted++
            }
            previousRaw = det
            previousOut = out
        }
        val rawAverage: Float = rawTotal / counted
        val outAverage: Float = outTotal / counted
        println("jitter: raw average step $rawAverage, outline average step $outAverage, ratio ${outAverage / rawAverage}")
        assertTrue("the outline should move less than half as much as the detections", outAverage < 0.5f * rawAverage)
    }

    @Test
    fun aPageJitteringByHalfAPercentIsStableAfterFourHundredMilliseconds() {
        // Noise of plus or minus 0.5%: two consecutive detections differ by at most 1.0% of the
        // diagonal, under the 1.2% limit. Six detections 100 ms apart span 500 ms.
        val tracker = ScanOutlineTracker()
        val noise = Lcg(11L)
        var at400: ScanOutline? = null
        var at500: ScanOutline? = null
        var at800: ScanOutline? = null
        var at900: ScanOutline? = null
        for (i in 0..9) {
            val det = FloatArray(8) { j -> page[j] + noise.signed(0.005f) }
            val out: ScanOutline = tracker.onFrame(det, 0.9f, 100f, i * 100L)
            if (i == 4) at400 = out
            if (i == 5) at500 = out
            if (i == 8) at800 = out
            if (i == 9) at900 = out
        }
        assertFalse("five detections are not yet enough", at400?.stable ?: true)
        assertTrue("six detections spanning 500 ms are stable", at500?.stable ?: false)
        assertTrue("the hint is still hold still at 800 ms", at800?.hint == ScanHint.HOLD_STILL)
        assertTrue("the hint is ready at 900 ms, 400 ms after the page became stable", at900?.hint == ScanHint.READY)
    }

    @Test
    fun aDropoutOfThreeHundredMillisecondsKeepsTheOutlineSolid() {
        val tracker = ScanOutlineTracker()
        tracker.onFrame(page, 0.9f, 100f, 0L)
        val out: ScanOutline = tracker.onFrame(null, 0f, 100f, 300L)
        assertTrue(out.corners != null)
        assertTrue(out.alpha == 1f)
    }

    @Test
    fun aLostPageFadesAfterTheHoldAndIsGoneAfterTheFade() {
        // Hold 350 ms, then fade over 200 ms: at 400 ms the alpha is 1 - 50 / 200 = 0.75; gone by 550 ms.
        val tracker = ScanOutlineTracker()
        tracker.onFrame(page, 0.9f, 100f, 0L)
        val at400: ScanOutline = tracker.onFrame(null, 0f, 100f, 400L)
        assertTrue("alpha at 400 ms should be 0.75 but was ${at400.alpha}", abs(at400.alpha - 0.75f) <= 1e-4f)
        assertTrue(at400.corners != null)
        val at600: ScanOutline = tracker.onFrame(null, 0f, 100f, 600L)
        assertTrue(at600.corners == null)
        assertTrue(at600.alpha == 0f)
    }

    @Test
    fun aQuadWithItsCornersOneIndexRoundIsRealignedAndNotMoved() {
        // The same page with its corners listed from top right: the tracker puts them back.
        val tracker = ScanOutlineTracker()
        tracker.onFrame(page, 0.9f, 100f, 0L)
        val rotated = floatArrayOf(0.8f, 0.2f, 0.8f, 0.8f, 0.2f, 0.8f, 0.2f, 0.2f)
        val out: ScanOutline = tracker.onFrame(rotated, 0.9f, 100f, 33L)
        assertCorners(page, out.corners, 1e-4f)
    }

    @Test
    fun noPageForOneAndAHalfSecondsAsksToPointAtTheDocument() {
        // No page since the start: the hint is none until 1500 ms, then point at the document
        // once that has held 400 ms, so it shows at 1900 ms and not at 1800 ms.
        val tracker = ScanOutlineTracker()
        var atOneFourHundred: ScanOutline? = null
        var atOneEightHundred: ScanOutline? = null
        var atOneNineHundred: ScanOutline? = null
        for (i in 0..19) {
            val t: Long = i * 100L
            val out: ScanOutline = tracker.onFrame(null, 0f, 100f, t)
            if (t == 1400L) atOneFourHundred = out
            if (t == 1800L) atOneEightHundred = out
            if (t == 1900L) atOneNineHundred = out
        }
        assertTrue(atOneFourHundred?.hint == ScanHint.NONE)
        assertTrue(atOneEightHundred?.hint == ScanHint.NONE)
        assertTrue(atOneNineHundred?.hint == ScanHint.POINT_AT_DOCUMENT)
    }

    @Test
    fun aDarkFrameAsksForMoreLightAfterTheHoldTime() {
        // Mean luma 40 for a page at every frame: MORE_LIGHT shows at 400 ms, not at 300 ms.
        val tracker = ScanOutlineTracker()
        var at300: ScanOutline? = null
        var at400: ScanOutline? = null
        for (i in 0..4) {
            val t: Long = i * 100L
            val out: ScanOutline = tracker.onFrame(page, 0.9f, 40f, t)
            if (t == 300L) at300 = out
            if (t == 400L) at400 = out
        }
        assertTrue(at300?.hint == ScanHint.NONE)
        assertTrue(at400?.hint == ScanHint.MORE_LIGHT)
    }

    @Test
    fun aPageTouchingTheEdgeAsksToFitTheWholePage() {
        val touching: FloatArray = pageAt(0.005f, 0.2f, 0.8f, 0.8f)
        val tracker = ScanOutlineTracker()
        var at400: ScanOutline? = null
        for (i in 0..4) {
            val out: ScanOutline = tracker.onFrame(touching, 0.9f, 100f, i * 100L)
            if (i == 4) at400 = out
        }
        assertTrue(at400?.hint == ScanHint.FIT_WHOLE_PAGE)
    }

    @Test
    fun aSmallPageAsksToMoveCloser() {
        // 0.2 by 0.2 of the frame: 4% of the frame, under 22%.
        val small: FloatArray = pageAt(0.4f, 0.4f, 0.6f, 0.6f)
        val tracker = ScanOutlineTracker()
        var at400: ScanOutline? = null
        for (i in 0..4) {
            val out: ScanOutline = tracker.onFrame(small, 0.9f, 100f, i * 100L)
            if (i == 4) at400 = out
        }
        assertTrue(at400?.hint == ScanHint.MOVE_CLOSER)
    }

    @Test
    fun aTrapezoidPageAsksToHoldThePhoneFlatter() {
        // Top side 0.6 of the width (0.8 frame heights), bottom side 0.2 (0.267): ratio 3, over 1.7.
        // Area 0.24 of the frame, over 22%, and no edge touched.
        val trapezoid = floatArrayOf(0.2f, 0.2f, 0.8f, 0.2f, 0.6f, 0.8f, 0.4f, 0.8f)
        val tracker = ScanOutlineTracker()
        var at400: ScanOutline? = null
        for (i in 0..4) {
            val out: ScanOutline = tracker.onFrame(trapezoid, 0.9f, 100f, i * 100L)
            if (i == 4) at400 = out
        }
        assertTrue(at400?.hint == ScanHint.HOLD_FLATTER)
    }

    @Test
    fun aOneFrameFlickerDoesNotChangeTheShownHint() {
        // Same page, dark (MORE_LIGHT) at every frame except one bright frame at 500 ms. The
        // bright frame would call for READY, but it lasts one frame, so the hint stays MORE_LIGHT.
        val tracker = ScanOutlineTracker()
        for (i in 0..10) {
            val t: Long = i * 100L
            val luma: Float = if (t == 500L) 100f else 40f
            val out: ScanOutline = tracker.onFrame(page, 0.9f, luma, t)
            if (t >= 400L) assertTrue("hint at $t ms", out.hint == ScanHint.MORE_LIGHT)
        }
    }

    @Test
    fun aNewHintWaitsForItsFourHundredMillisecondHold() {
        // Dark for 0 to 400 ms, so MORE_LIGHT shows at 400 ms. Then bright with the page still:
        // READY is desired from 500 ms (stable), and shows at 900 ms, not at 800 ms.
        val tracker = ScanOutlineTracker()
        var at800: ScanOutline? = null
        var at900: ScanOutline? = null
        for (i in 0..9) {
            val t: Long = i * 100L
            val luma: Float = if (t < 500L) 40f else 100f
            val out: ScanOutline = tracker.onFrame(page, 0.9f, luma, t)
            if (t == 800L) at800 = out
            if (t == 900L) at900 = out
        }
        assertTrue("still MORE_LIGHT at 800 ms", at800?.hint == ScanHint.MORE_LIGHT)
        assertTrue("READY at 900 ms", at900?.hint == ScanHint.READY)
    }
}
