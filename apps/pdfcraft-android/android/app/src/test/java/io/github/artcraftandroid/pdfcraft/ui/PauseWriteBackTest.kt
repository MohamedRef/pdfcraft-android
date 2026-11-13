package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.pauseForOwnScreen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A pause for the app's own picker or share sheet saves locally but does not rewrite the original (#97). */
class PauseWriteBackTest {
    @Test
    fun aPauseRightAfterTheAppsOwnLaunchIsForItsOwnScreen() {
        assertTrue(pauseForOwnScreen(now = 10_500, ownLaunchAt = 10_000, finishing = false))
        assertFalse(pauseForOwnScreen(now = 13_000, ownLaunchAt = 10_000, finishing = false))
        assertFalse(pauseForOwnScreen(now = 10_500, ownLaunchAt = 0, finishing = false))
        assertFalse(pauseForOwnScreen(now = 1_000, ownLaunchAt = 0, finishing = false))
        assertFalse(pauseForOwnScreen(now = 10_500, ownLaunchAt = 10_000, finishing = true))
    }

    @Test
    fun onlyAnOwnScreenPauseWithAnOriginalElsewhereWaits() {
        assertTrue(writeBackWaits(true, "content", null, "/data/work.pdf"))
        assertTrue(writeBackWaits(true, "file", "/sdcard/a.pdf", "/data/work.pdf"))
        // a file edited in place: the working-file save already changed it
        assertFalse(writeBackWaits(true, "file", "/data/work.pdf", "/data/work.pdf"))
        assertFalse(writeBackWaits(true, null, null, "/data/work.pdf"))
        // leaving the app, closing, or any other save writes the original as before
        assertFalse(writeBackWaits(false, "content", null, "/data/work.pdf"))
        assertFalse(writeBackWaits(false, "file", "/sdcard/a.pdf", "/data/work.pdf"))
    }
}
