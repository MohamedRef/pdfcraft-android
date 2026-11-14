package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.ui.ScanLab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Borders pulled onto the page's edges while dragging (#514). */
class ScanPullTest {
    private val viewW = 1000f
    private val viewH = 1000f
    private val quad = floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)

    private fun shifted(dx: Float) = quad.copyOf().also { it[2] += dx; it[4] += dx }

    @Test
    fun anEdgeCloseByCatchesTheSideAndOneFarAwayDoesNot() {
        val right = QuadHandle.Side(1)
        // 15 px away, the catch is 20 px
        val d = pullCatches(quad, shifted(0.015f), right, 20f, viewW, viewH)
        assertNotNull(d)
        assertEquals(15f, d!!, 0.1f)
        // 40 px away: no catch
        assertNull(pullCatches(quad, shifted(0.04f), right, 20f, viewW, viewH))
        // the finder giving back the same corners is no catch
        assertNull(pullCatches(quad, quad.copyOf(), right, 20f, viewW, viewH))
    }

    @Test
    fun aCaughtHandleIsLetGoOnlyPastTheReleaseDistance() {
        val corner = QuadHandle.Corner(1)
        val caught = ScanPullCaught(shifted(0.01f), 0.9f, 0.1f, "edge", 10f, 0L)
        // 25 px from where it was caught: past the catch (20) but not the release (32), so it stays
        assertFalse(pullLetsGo(quad.copyOf().also { it[2] = 0.925f }, caught, corner, 32f, viewW, viewH))
        assertTrue(pullLetsGo(quad.copyOf().also { it[2] = 0.94f }, caught, corner, 32f, viewW, viewH))
        assertTrue(PULL_RELEASE_DP > PULL_CATCH_DP)
    }

    @Test
    fun aCornerGoesToTheMeetingPointOnlyWhenBothItsSidesWereFound() {
        assertEquals("corner", pullTarget(QuadHandle.Corner(0), listOf(true, false, false, true)))
        assertEquals("edge", pullTarget(QuadHandle.Corner(0), listOf(true, false, false, false)))
        assertEquals("edge", pullTarget(QuadHandle.Side(0), listOf(true, true, true, true)))
    }

    @Test
    fun aSideAtThePhotosBorderSlidesAlongIt() {
        // a slanted right side whose top corner is already as far right as a corner goes
        val q = floatArrayOf(0.1f, 0.1f, QUAD_MAX, 0.1f, 0.8f, 0.9f, 0.1f, 0.9f)
        assertNull(moveSide(q, 1, 0.1f, 0f, 1f))
        val slid = moveSide(q, 1, 0.1f, 0f, 1f, slide = true)
        assertNotNull(slid)
        // the top corner stays on the border, the bottom one follows the finger
        assertEquals(QUAD_MAX, slid!![2], 1e-4f)
        assertTrue(slid[4] > 0.85f)
    }

    @Test
    fun aPullSnapsOnReleaseEvenAfterHoldingStill() {
        assertFalse(snapOnRelease(true, SNAP_HOLD_MS + 100))
        assertTrue(snapOnRelease(true, SNAP_HOLD_MS + 100, pull = true))
        assertFalse(snapOnRelease(false, 0L, pull = true))
    }

    @Test
    fun theLogAndTheKeptPageSayWhatThePullDid() {
        val line = pullDiag("drag", "corner", 12.345f, 400L, "", 3L).toMap()
        assertEquals(12.3, line["distance_dp"])
        assertEquals("corner", line["target"])
        val stats = ScanCropStats(visits = 1, catches = 3, pulledFree = 1, badShapes = 2, sidesOnEdge = listOf(true, true, false, true))
        val diag = stats.diag().toMap()
        assertEquals(3, diag["catches"])
        assertEquals(1, diag["pulled_free"])
        assertEquals(2, diag["bad_shapes"])
        assertEquals(3, diag["sides_on_edge"])
        assertEquals(4, stats.json().getJSONArray("sides_on_edge").length())
        assertEquals(-1, ScanCropStats().diag().toMap()["sides_on_edge"])
        val sum = stats + ScanCropStats(visits = 1, catches = 1)
        assertEquals(4, sum.catches)
        assertEquals(listOf(true, true, false, true), sum.sidesOnEdge)
    }

    @Test
    fun theTestingOptionsAreOnAndRemembered() {
        val lab = ScanLab()
        assertTrue(lab.snapWhileDragging)
        assertEquals(PULL_CATCH_DP, lab.pullCatchDp)
        val set = ScanLab(snapWhileDragging = false, pullCatchDp = 30, pullReleaseDp = 50)
        val back = ScanLab.decode(set.encode())
        assertFalse(back.snapWhileDragging)
        assertEquals(30, back.pullCatchDp)
        assertEquals(50, back.pullReleaseDp)
        assertEquals(false, set.diag().toMap()["snap_while_dragging"])
        assertEquals(30, set.json().getInt("pull_catch_dp"))
        // out of range values keep the default
        assertEquals(PULL_RELEASE_DP, ScanLab.decode("release_dp=999").pullReleaseDp)
        assertTrue(ScanLab.decode("keep=0").snapWhileDragging)
    }
}
