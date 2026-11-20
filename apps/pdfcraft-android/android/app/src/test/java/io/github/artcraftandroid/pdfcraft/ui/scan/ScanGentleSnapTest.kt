package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.ui.ScanLab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The gentle snap on Adjust borders (#773): a closer catch, an easier let-go, no snap after a lift off the edge. */
class ScanGentleSnapTest {
    @Test
    fun aLiftSnapsOnlyAHandleThePullHadOnAnEdge() {
        // on an edge at the lift: its sides are refined there
        assertTrue(snapOnRelease(true, 0L, pull = true, gentle = true, caught = true))
        // pulled free, or never caught: it stays where the finger put it, held still or not
        assertFalse(snapOnRelease(true, 0L, pull = true, gentle = true, caught = false))
        assertFalse(snapOnRelease(true, SNAP_HOLD_MS + 100, pull = true, gentle = true, caught = false))
        // Snap off: never
        assertFalse(snapOnRelease(false, 0L, pull = true, gentle = true, caught = true))
        // off: every lift snaps with the pull, as before
        assertTrue(snapOnRelease(true, SNAP_HOLD_MS + 100, pull = true, gentle = false, caught = false))
        // without the pull the gentle snap changes nothing
        assertTrue(snapOnRelease(true, 0L, pull = false, gentle = true))
        assertFalse(snapOnRelease(true, SNAP_HOLD_MS + 100, pull = false, gentle = true))
    }

    @Test
    fun thePullCatchesCloserAndLetsGoSooner() {
        assertEquals(12, ScanLab().pullCatchInUse)
        assertEquals(20, ScanLab().pullReleaseInUse)
        assertTrue(PULL_RELEASE_DP > PULL_CATCH_DP)
        // off: the distances before #773
        assertEquals(20, ScanLab(gentleSnap = false).pullCatchInUse)
        assertEquals(32, ScanLab(gentleSnap = false).pullReleaseInUse)
        // the testing values still set them with the gentle snap
        assertEquals(16, ScanLab(pullCatchDp = 16).pullCatchInUse)
    }

    @Test
    fun theDistancesSavedAsTheOldDefaultsAreNotRead() {
        // the owner's phone saved 20 and 32 with its options on 2026-11-19, when they were the defaults
        val before = ScanLab.decode("keep=1;catch_dp=20;release_dp=32")
        assertEquals(PULL_CATCH_DP, before.pullCatchDp)
        assertEquals(PULL_RELEASE_DP, before.pullReleaseDp)
        assertTrue(before.gentleSnap)
        val set = ScanLab.decode(ScanLab(pullCatchDp = 16, pullReleaseDp = 28, gentleSnap = false).encode())
        assertEquals(16, set.pullCatchDp)
        assertEquals(28, set.pullReleaseDp)
        assertFalse(set.gentleSnap)
    }

    @Test
    fun theLogSaysWhatTheSnapDid() {
        val off = ScanLab(gentleSnap = false)
        assertEquals(false, off.diag().toMap()["gentle_snap"])
        assertEquals(20, off.diag().toMap()["pull_catch_dp"])
        assertEquals(false, off.json().getBoolean("gentle_snap"))
        assertEquals(32, off.json().getInt("pull_release_dp"))
        assertFalse(off.asDesigned)
        val stats = ScanCropStats(snapsFree = 2) + ScanCropStats(snapsFree = 1)
        assertEquals(3, stats.snapsFree)
        assertEquals(3, stats.diag().toMap()["snaps_free"])
        assertEquals(3, stats.json().getInt("snaps_free"))
    }
}
