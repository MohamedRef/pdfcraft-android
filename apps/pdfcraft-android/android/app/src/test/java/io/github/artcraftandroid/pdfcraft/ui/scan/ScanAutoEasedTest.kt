package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.ui.ScanLab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Auto-capture eased (#296): a dimmer room and a less sure outline still reach Ready. */
class ScanAutoEasedTest {
    private val page = floatArrayOf(0.2f, 0.2f, 0.8f, 0.2f, 0.8f, 0.8f, 0.2f, 0.8f)

    private fun scene(luma: Float) = ScanLiveScene(page, stable = true, meanLuma = luma, cutOff = false, missingMs = 0L)

    private fun seen(points: FloatArray, confidence: Float, at: Long) = ScanLiveDetection(points, confidence, 60f, found = true, cutOff = false, at = at)

    @Test
    fun aRoomAtTheOwnersLightIsReadyOnlyEased() {
        // his frames that found the page sat at a mean luma of 45 to 72 (2026-11-20)
        assertEquals(ScanLiveHint.MoreLight, scanLiveHintFor(scene(50f)))
        assertEquals(ScanLiveHint.Ready, scanLiveHintFor(scene(50f), darkLuma = ScanLiveEase.of(true).darkLuma))
        // a dark room still asks for light
        assertEquals(ScanLiveHint.MoreLight, scanLiveHintFor(scene(30f), darkLuma = ScanLiveEase.of(true).darkLuma))
        assertEquals(ScanLiveHint.Ready, scanLiveHintFor(scene(60f)))
    }

    @Test
    fun anOutlineAtTheOwnersConfidenceIsSteadyOnlyEased() {
        // a quarter of his found frames were at 0.5 to 0.63
        fun steadyAt(confidence: Float, sureFrom: Float): Boolean {
            val t = ScanLiveTracker(sureFrom = sureFrom)
            var last: ScanLiveOutline? = null
            for (i in 0 until 8) last = t.update(seen(page, confidence, 1_000L + i * 100L))
            return last!!.stable
        }
        assertFalse(steadyAt(0.55f, ScanLiveEase.of(false).sureFrom))
        assertTrue(steadyAt(0.55f, ScanLiveEase.of(true).sureFrom))
        assertFalse(steadyAt(0.45f, ScanLiveEase.of(true).sureFrom))
        assertTrue(steadyAt(0.7f, ScanLiveEase.of(false).sureFrom))
    }

    @Test
    fun theLogSaysWhatTheSteadyTestSaw() {
        val t = ScanLiveTracker(sureFrom = 0.5f)
        val moved = page.copyOf().also { it[0] += 0.03f }
        t.update(seen(page, 0.7f, 0L))
        t.update(seen(moved, 0.4f, 100L))
        // long after: a new look, no move counted
        t.update(seen(moved, 0.7f, 2_000L))
        val d = t.diag().toMap()
        assertEquals(3, d["detections"])
        assertEquals(1, d["unsure"])
        assertEquals(1, d["moves"])
        // 0.03 of the width is 1.8 % of the diagonal, over the 1.2 % the steady test allows
        assertEquals(1, d["moves_over"])
        assertEquals(1.8, d["move_p50_pct"])
        assertEquals(-1.0, ScanLiveTracker().diag().toMap()["move_p90_pct"])
    }

    @Test
    fun theLogSaysHowLongEachAdviceWasCalledFor() {
        val times = ScanLiveHintTimes()
        times.add(ScanLiveHint.MoreLight, true, 0L)
        times.add(ScanLiveHint.Ready, false, 300L)
        times.add(ScanLiveHint.Ready, true, 500L)
        times.add(null, true, 600L)
        times.add(null, true, 700L)
        val d = times.diag().toMap()
        assertEquals(300L, d["light_ms"])
        assertEquals(300L, d["ready_ms"])
        assertEquals(200L, d["ready_moving_ms"])
        assertEquals(100L, d["none_ms"])
        assertEquals(0L, d["still_ms"])
    }

    @Test
    fun theLogSaysWhichValuesWereUsed() {
        val eased = ScanLiveEase.of(true).diag(true).toMap()
        assertEquals(true, eased["eased"])
        assertEquals(40.0, eased["light_floor"])
        assertEquals(0.5, eased["sure_from"])
        val before = ScanLiveEase.of(false).diag(false).toMap()
        assertEquals(55.0, before["light_floor"])
        assertEquals(0.6, before["sure_from"] as Double, 1e-6)
    }

    @Test
    fun theTestingOptionIsOnAndRemembered() {
        assertTrue(ScanLab().autoEased)
        val off = ScanLab(autoEased = false)
        assertFalse(ScanLab.decode(off.encode()).autoEased)
        assertFalse(off.asDesigned)
        assertEquals(false, off.diag().toMap()["auto_eased"])
        assertEquals(false, off.json().getBoolean("auto_eased"))
        // a phone that saved its options before #296's change has it on
        assertTrue(ScanLab.decode("keep=1;snap=1").autoEased)
    }
}
