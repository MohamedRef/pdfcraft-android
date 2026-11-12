package io.github.artcraftandroid.pdfcraft.ui.scan

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class ScanQuadGeometryTest {
    private val rect = floatArrayOf(0.2f, 0.2f, 0.8f, 0.2f, 0.8f, 0.8f, 0.2f, 0.8f)

    /** A page photographed from below: wide at the bottom, narrow at the top. */
    private val trapezoid = floatArrayOf(0.35f, 0.2f, 0.65f, 0.2f, 0.9f, 0.85f, 0.1f, 0.85f)

    private fun near(expected: FloatArray, actual: FloatArray?, tol: Float = 1e-5f) {
        assertNotNull(actual)
        for (k in expected.indices) assertEquals("value $k", expected[k], actual!![k], tol)
    }

    /** Whether p lies on the line through a and b, in photo proportions. */
    private fun collinear(ax: Float, ay: Float, bx: Float, by: Float, px: Float, py: Float, aspect: Float): Boolean {
        val cross = ((bx - ax) * aspect) * (py - ay) - (by - ay) * ((px - ax) * aspect)
        return abs(cross) < 1e-5f
    }

    @Test
    fun movingTheTopSideOfARectangleDownMovesBothTopCorners() {
        near(floatArrayOf(0.2f, 0.3f, 0.8f, 0.3f, 0.8f, 0.8f, 0.2f, 0.8f), moveSide(rect, 0, 0f, 0.1f, aspect = 0.75f))
        // only the part of the move across the side counts
        near(floatArrayOf(0.2f, 0.3f, 0.8f, 0.3f, 0.8f, 0.8f, 0.2f, 0.8f), moveSide(rect, 0, 0.3f, 0.1f, aspect = 0.75f))
        // the right side, to the right
        near(floatArrayOf(0.2f, 0.2f, 0.85f, 0.2f, 0.85f, 0.8f, 0.2f, 0.8f), moveSide(rect, 1, 0.05f, 0f, aspect = 1.33f))
    }

    @Test
    fun aMovedSidesCornersStayOnTheNeighbouringSides() {
        for (aspect in listOf(0.75f, 1f, 1.333f)) {
            for (side in 0..3) {
                val moved = moveSide(trapezoid, side, 0.02f, -0.03f, aspect)
                assertNotNull("side $side at $aspect", moved)
                val i = side
                val j = (side + 1) % 4
                val prev = (side + 3) % 4
                val next = (side + 2) % 4
                val t = trapezoid
                val m = moved!!
                // corner i on the line from the previous corner through the old corner i
                assertTrue(collinear(t[2 * prev], t[2 * prev + 1], t[2 * i], t[2 * i + 1], m[2 * i], m[2 * i + 1], aspect))
                assertTrue(collinear(t[2 * next], t[2 * next + 1], t[2 * j], t[2 * j + 1], m[2 * j], m[2 * j + 1], aspect))
                // and the moved side keeps its direction
                val before = (t[2 * j] - t[2 * i]) * aspect to t[2 * j + 1] - t[2 * i + 1]
                val after = (m[2 * j] - m[2 * i]) * aspect to m[2 * j + 1] - m[2 * i + 1]
                assertEquals(0f, before.first * after.second - before.second * after.first, 1e-5f)
                // the other two corners did not move
                for (k in listOf(prev, next)) {
                    assertEquals(t[2 * k], m[2 * k], 0f)
                    assertEquals(t[2 * k + 1], m[2 * k + 1], 0f)
                }
            }
        }
    }

    @Test
    fun aSideMoveThatFoldsTheShapeOrLeavesThePhotoIsRefused() {
        // the top side pushed past the bottom one
        assertNull(moveSide(rect, 0, 0f, 0.7f, aspect = 1f))
        // the left side off the photo
        assertNull(moveSide(rect, 3, -0.3f, 0f, aspect = 1f))
        // the top of the trapezoid moved down until its corners would cross
        assertNull(moveSide(trapezoid, 0, 0f, 0.9f, aspect = 1f))
    }

    @Test
    fun aCornerStaysOnThePhotoAndTheShapeStaysConvex() {
        near(floatArrayOf(0f, 0f, 0.8f, 0.2f, 0.8f, 0.8f, 0.2f, 0.8f), moveCorner(rect, 0, -0.5f, -0.5f))
        // top-left dragged past the bottom-right
        assertNull(moveCorner(rect, 0, 0.7f, 0.7f))
        assertFalse(convexQuad(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)))
        assertTrue(convexQuad(rect))
    }

    @Test
    fun aFastDragStopsAtTheLastGoodPlace() {
        val moved = moveTowards(rect, QuadHandle.Side(3), -0.5f, 0f, aspect = 1f)
        // the left side went as far as the photo's edge, and no further
        assertEquals(0f, moved[0], 1e-3f)
        assertEquals(0f, moved[6], 1e-3f)
        // a move that is not allowed at all leaves the shape as it was
        val squashed = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        assertArrayEquals(squashed, moveTowards(squashed, QuadHandle.Side(0), 0f, -0.2f, 1f), 0f)
    }

    @Test
    fun cornersAreFoundBeforeSides() {
        val fit = QuadFit(left = 10f, top = 20f, width = 100f, height = 100f)
        // a tiny page: its top-left corner and its top side's middle are 1 px apart on screen
        val tiny = floatArrayOf(0.5f, 0.5f, 0.52f, 0.5f, 0.52f, 0.52f, 0.5f, 0.52f)
        assertEquals(QuadHandle.Corner(0), hitTest(60.6f, 70f, tiny, fit, cornerRadius = 48f, sideRadius = 48f))
        // on a big page a side's middle is found
        assertEquals(QuadHandle.Side(0), hitTest(60f, 41f, rect, fit, cornerRadius = 24f, sideRadius = 24f))
        assertEquals(QuadHandle.Corner(2), hitTest(89f, 99f, rect, fit, cornerRadius = 24f, sideRadius = 24f))
        // nothing when far from every handle
        assertNull(hitTest(60f, 70f, rect, fit, cornerRadius = 24f, sideRadius = 24f))
    }

    @Test
    fun everyHandleIsReachedByTab() {
        assertEquals(8, QuadHandle.ALL.toSet().size)
    }

    @Test
    fun aReleaseSnapsTheSidesItMoved() {
        assertEquals(listOf(true, false, false, true), sidesToSnap(QuadHandle.Corner(0)))
        assertEquals(listOf(false, true, true, false), sidesToSnap(QuadHandle.Corner(2)))
        assertEquals(listOf(false, false, true, false), sidesToSnap(QuadHandle.Side(2)))
    }

    @Test
    fun holdingStillBeforeLettingGoTurnsSnapOffForThatDrag() {
        assertTrue(snapOnRelease(snapOn = true, stillMs = 0))
        assertTrue(snapOnRelease(snapOn = true, stillMs = SNAP_HOLD_MS - 1))
        assertFalse(snapOnRelease(snapOn = true, stillMs = SNAP_HOLD_MS))
        assertFalse(snapOnRelease(snapOn = false, stillMs = 0))
    }

    @Test
    fun aSnapIsTakenOnlyWhenASideAskedForWasRefined() {
        val asked = sidesToSnap(QuadHandle.Side(0))
        val better = floatArrayOf(0.2f, 0.21f, 0.8f, 0.21f, 0.8f, 0.8f, 0.2f, 0.8f)
        near(better, snapped(asked, listOf(true, false, false, false), better))
        // the side asked for was not refined: the handle stays where it was put
        assertNull(snapped(asked, listOf(false, true, false, false), better))
        // a broken answer is not taken
        assertNull(snapped(asked, listOf(true, false, false, false), floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)))
        assertNull(snapped(asked, listOf(true, false, false, false), floatArrayOf(0f, 0f)))
    }

    @Test
    fun theMagnifierNeverCoversTheHandleNorLeavesTheView() {
        val (w, h, size, gap) = listOf(1080f, 1920f, 360f, 72f)
        val handles = listOf(0f to 0f, w to 0f, w to h, 0f to h, w / 2 to h / 2, 10f to h / 2, w - 5f to 200f)
        for ((hx, hy) in handles) {
            val (x, y) = loupePlacement(hx, hy, w, h, size, gap)
            assertTrue("inside at $hx,$hy", x >= 0f && y >= 0f && x + size <= w && y + size <= h)
            val nx = hx.coerceIn(x, x + size)
            val ny = hy.coerceIn(y, y + size)
            assertTrue("clear of $hx,$hy", hypot(nx - hx, ny - hy) >= gap - 1e-3f)
        }
        // in the open it sits above the finger, centred
        assertEquals(540f - 180f to 960f - 72f - 360f, loupePlacement(540f, 960f, w, h, size, gap))
    }

    @Test
    fun anUprightPointIsFoundInTheStoredFile() {
        // the upright photo's top-left corner, for each way a phone stores a photo
        assertEquals(0f to 0f, uprightToStored(0f, 0f, 1))
        // stored on its side (turn 90° clockwise to stand it up): upright top-left is stored bottom-left
        assertEquals(0f to 1f, uprightToStored(0f, 0f, 6))
        assertEquals(1f to 1f, uprightToStored(0f, 0f, 3))
        // turn 270°: upright top-left is stored top-right
        assertEquals(1f to 0f, uprightToStored(0f, 0f, 8))
        // a point off the middle
        assertEquals(0.25f to 0.9f, uprightToStored(0.1f, 0.25f, 6))
        assertEquals(0.75f to 0.1f, uprightToStored(0.1f, 0.25f, 8))
        assertEquals(0.9f to 0.75f, uprightToStored(0.1f, 0.25f, 3))
        assertEquals(0.1f to 0.25f, uprightToStored(0.1f, 0.25f, 0))
    }

    @Test
    fun aDecodedRegionIsWhereTheMagnifierExpectsItOnceTurned() {
        // a 4000 × 3000 file stored on its side: upright it is 3000 × 4000
        val (sw, sh) = 4000 to 3000
        for (exif in listOf(1, 3, 6, 8)) {
            val (uw, uh) = if (exif == 6 || exif == 8) sh to sw else sw to sh
            for ((x, y) in listOf(0.5f to 0.5f, 0.1f to 0.2f, 0f to 1f)) {
                val r = loupeRegion(x, y, exif, sw, sh, 512)!!
                assertTrue(r.left >= 0 && r.top >= 0 && r.right <= sw && r.bottom <= sh && r.width == 512 && r.height == 512)
                val up = storedToUprightRect(r, exif, sw, sh)
                assertTrue(up.left >= 0 && up.top >= 0 && up.right <= uw && up.bottom <= uh)
                // the point asked for is in the region, centred unless the photo's edge is near
                val (px, py) = x * uw to y * uh
                assertTrue("$exif $x,$y in $up", px >= up.left - 1 && px <= up.right + 1 && py >= up.top - 1 && py <= up.bottom + 1)
                if (x == 0.5f) {
                    assertEquals(px, (up.left + up.right) / 2f, 1f)
                    assertEquals(py, (up.top + up.bottom) / 2f, 1f)
                }
            }
        }
        assertNull(loupeRegion(0.5f, 0.5f, 1, 0, 0, 512))
        // a file smaller than the region is decoded whole
        assertEquals(PixelRect(0, 0, 300, 200), loupeRegion(0.5f, 0.5f, 1, 300, 200, 512))
    }

    @Test
    fun aRegionIsDecodedAgainOnlyAfterAQuarterOfItsSide() {
        assertTrue(loupeRegionFresh(100f, 100f, 228f, 100f, 512))
        assertFalse(loupeRegionFresh(100f, 100f, 229f, 100f, 512))
        assertFalse(loupeRegionFresh(100f, 100f, 100f, -40f, 512))
    }

    @Test
    fun aNudgeIsTheSameDistanceAcrossAndDown() {
        val aspect = 0.75f // a portrait photo, 3 × 4
        val right = nudge(rect, QuadHandle.Corner(2), 1, 0, NUDGE_SHARE, aspect)
        val down = nudge(rect, QuadHandle.Corner(2), 0, 1, NUDGE_SHARE, aspect)
        val across = (right[4] - rect[4]) * aspect
        val along = down[5] - rect[5]
        assertEquals(across, along, 1e-6f)
        // 0.25 % of the photo's longer side
        assertEquals(NUDGE_SHARE, along, 1e-6f)
        // a side nudged moves across itself
        val up = nudge(rect, QuadHandle.Side(0), 0, -4, NUDGE_SHARE_LARGE, aspect)
        assertEquals(0.16f, up[1], 1e-5f)
        assertEquals(0.16f, up[3], 1e-5f)
    }
}
