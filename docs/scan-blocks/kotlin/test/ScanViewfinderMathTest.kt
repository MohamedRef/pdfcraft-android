package io.github.artcraftandroid.pdfcraft.ui.scan

import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hand-worked values for the frame-to-view mapping (issue #295, S09 section 2). The raw frame is
 * 640 by 480 (landscape). Where the view is 400 by 400, fit gives a 400 by 300 frame with a 50 pixel
 * bar above and below when the frame is upright; a quarter turn gives a 300 by 400 frame with bars
 * at the sides.
 */
class ScanViewfinderMathTest {

    /** The four corners of the whole raw frame: top left, top right, bottom right, bottom left. */
    private val wholeFrame = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)

    private fun assertPoint(expectedX: Float, expectedY: Float, actual: FloatArray, index: Int) {
        val x: Float = actual[2 * index]
        val y: Float = actual[2 * index + 1]
        assertTrue("corner $index expected x $expectedX but was $x", abs(x - expectedX) <= 0.01f)
        assertTrue("corner $index expected y $expectedY but was $y", abs(y - expectedY) <= 0.01f)
    }

    @Test
    fun aCentreShareMapsToTheCentreOfTheViewForEveryRotationAndScale() {
        val centre = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f)
        for (fill in listOf(false, true)) {
            for (rotation in listOf(0, 90, 180, 270)) {
                val mapped: FloatArray = frameToView(centre, 640, 480, rotation, 400f, 400f, fill, false)
                assertPoint(200f, 200f, mapped, 0)
            }
        }
    }

    @Test
    fun fitShowsTheWholeUprightFrameWithBarsAboveAndBelow() {
        // Upright 4:3 frame in a 400 by 400 view: 400 wide, 300 high, starting at y = 50.
        val mapped: FloatArray = frameToView(wholeFrame, 640, 480, 0, 400f, 400f, false, false)
        assertPoint(0f, 50f, mapped, 0)
        assertPoint(400f, 50f, mapped, 1)
        assertPoint(400f, 350f, mapped, 2)
        assertPoint(0f, 350f, mapped, 3)
    }

    @Test
    fun fillCropsTheUprightFrameAtTheSides() {
        // Fill: 533.33 wide, 400 high, starting at x = -66.67. The corners fall outside the view.
        val mapped: FloatArray = frameToView(wholeFrame, 640, 480, 0, 400f, 400f, true, false)
        assertPoint(-66.67f, 0f, mapped, 0)
        assertPoint(466.67f, 400f, mapped, 2)
    }

    @Test
    fun aRawTopLeftCornerGoesToTheTopRightAfterAQuarterTurnClockwise() {
        // 90: the upright frame is 300 by 400 at x = 50. The raw top left goes to the top right.
        val mapped: FloatArray = frameToView(wholeFrame, 640, 480, 90, 400f, 400f, false, false)
        assertPoint(350f, 0f, mapped, 0)
        assertPoint(350f, 400f, mapped, 1)
        assertPoint(50f, 400f, mapped, 2)
        assertPoint(50f, 0f, mapped, 3)
    }

    @Test
    fun aRawTopLeftCornerGoesToTheBottomRightAfterAHalfTurn() {
        // 180: the upright frame is 400 by 300 at y = 50, turned around.
        val mapped: FloatArray = frameToView(wholeFrame, 640, 480, 180, 400f, 400f, false, false)
        assertPoint(400f, 350f, mapped, 0)
        assertPoint(0f, 350f, mapped, 1)
        assertPoint(0f, 50f, mapped, 2)
        assertPoint(400f, 50f, mapped, 3)
    }

    @Test
    fun aRawTopLeftCornerGoesToTheBottomLeftAfterAQuarterTurnAnticlockwise() {
        // 270: the upright frame is 300 by 400 at x = 50. The raw top left goes to the bottom left.
        val mapped: FloatArray = frameToView(wholeFrame, 640, 480, 270, 400f, 400f, false, false)
        assertPoint(50f, 400f, mapped, 0)
        assertPoint(50f, 0f, mapped, 1)
        assertPoint(350f, 0f, mapped, 2)
        assertPoint(350f, 400f, mapped, 3)
    }

    @Test
    fun aRawLeftEdgeMidpointGoesToTheTopMidpointAfterAQuarterTurn() {
        // Raw left edge midpoint (0, 0.5) at 90: upright (0.5, 0), which is the top middle: x = 50 + 150.
        val edge = floatArrayOf(0f, 0.5f, 0f, 0.5f, 0f, 0.5f, 0f, 0.5f)
        val mapped: FloatArray = frameToView(edge, 640, 480, 90, 400f, 400f, false, false)
        assertPoint(200f, 0f, mapped, 0)
    }

    @Test
    fun fitOnAPortraitViewUsesTheUprightFrameShapeAfterAQuarterTurn() {
        // Landscape view 400 by 300, quarter turn: upright frame 0.75 aspect.
        // Fit: 225 wide, 300 high, starting at x = 87.5. Raw top left goes to the top right: (312.5, 0).
        val fit: FloatArray = frameToView(wholeFrame, 640, 480, 90, 400f, 300f, false, false)
        assertPoint(312.5f, 0f, fit, 0)
        // Fill: 400 wide, 533.33 high, starting at y = -116.67. Raw top left goes to (400, -116.67).
        val fill: FloatArray = frameToView(wholeFrame, 640, 480, 90, 400f, 300f, true, false)
        assertPoint(400f, -116.67f, fill, 0)
    }

    @Test
    fun aMirroredFrameFlipsLeftToRightInTheView() {
        // Rotation 0, fit: the raw top left (0, 0) goes to the top right, (400, 50), when mirrored.
        val mapped: FloatArray = frameToView(wholeFrame, 640, 480, 0, 400f, 400f, false, true)
        assertPoint(400f, 50f, mapped, 0)
        assertPoint(0f, 50f, mapped, 1)
    }

    @Test
    fun aMirroredQuarterTurnFlipsTheRotatedFrame() {
        // Quarter turn: raw top left goes to upright (1, 0), which is (350, 0). Mirrored, it is at x = 50.
        val mapped: FloatArray = frameToView(wholeFrame, 640, 480, 90, 400f, 400f, false, true)
        assertPoint(50f, 0f, mapped, 0)
    }

    @Test
    fun badInputGivesAnEmptyArray() {
        val short = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)
        assertTrue("fewer than eight values", frameToView(short, 640, 480, 0, 400f, 400f, false, false).isEmpty())
        assertTrue("zero frame width", frameToView(wholeFrame, 0, 480, 0, 400f, 400f, false, false).isEmpty())
    }
}
