package io.github.artcraftandroid.pdfcraft.ui.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanAutoCaptureTest {
    private val sigA = FloatArray(256) { if (it % 2 == 0) 40f else -40f }
    private val sigB = FloatArray(256) { if (it % 32 < 16) 40f else -40f }

    private fun seen(at: Long, ready: Boolean = true, still: Boolean = true, present: Boolean = true, sig: FloatArray? = sigA) =
        ScanAutoEvent.Seen(ScanAutoInputs(at, ready, still, present, sig))

    /** Runs [events] and returns the end state and the times at which a photo was asked for. */
    private fun run(start: ScanAutoState, events: List<ScanAutoEvent>): Pair<ScanAutoState, List<Long>> {
        var s = start
        val shots = mutableListOf<Long>()
        for (e in events) {
            val (next, shoot) = s.reduce(e)
            if (shoot) shots += e.at
            s = next
        }
        return s to shots
    }

    private fun every(from: Long, to: Long, step: Long = 50L, make: (Long) -> ScanAutoEvent) = (from..to step step).map(make)

    @Test
    fun readyStillPageShootsAfterReadyAndCountdown() {
        val (s, shots) = run(ScanAutoState(), every(0, 1_200) { seen(it) })
        // ready from 0, armed at 500, shot at 1100
        assertEquals(listOf(1_100L), shots)
        assertEquals(ScanAutoPhase.Capturing, s.phase)
        assertTrue(s.auto)
    }

    @Test
    fun theSamePageIsNotShotTwice() {
        val (s0, shots0) = run(ScanAutoState(), every(0, 1_100) { seen(it) })
        assertEquals(1, shots0.size)
        val (s1, _) = s0.reduce(ScanAutoEvent.CaptureEnded(true, sigA, 1_600L))
        assertEquals(ScanAutoPhase.WaitingForNewPage, s1.phase)
        // the same page in view for 10 s: nothing more
        val (s2, shots2) = run(s1, every(1_650, 11_650) { seen(it) })
        assertTrue(shots2.isEmpty())
        assertEquals(ScanAutoPhase.WaitingForNewPage, s2.phase)
    }

    @Test
    fun aPageSwappedInViewIsShot() {
        val waiting = ScanAutoState(ScanAutoPhase.WaitingForNewPage, shotSignature = sigA)
        val (s, shots) = run(waiting, every(0, 1_300) { seen(it, sig = sigB) })
        assertEquals(1, shots.size)
        assertEquals(ScanAutoPhase.Capturing, s.phase)
    }

    @Test
    fun thePageGoneForAWhileCountsAsNew() {
        val waiting = ScanAutoState(ScanAutoPhase.WaitingForNewPage, shotSignature = sigA)
        // gone 300 ms: still waiting
        val (short, _) = run(waiting, every(0, 300) { seen(it, ready = false, present = false, sig = null) })
        assertEquals(ScanAutoPhase.WaitingForNewPage, short.phase)
        val (back, _) = run(short, listOf(seen(350)))
        assertEquals(ScanAutoPhase.WaitingForNewPage, back.phase)
        // gone 400 ms: searching again, and the same page brought back is shot
        val (long, _) = run(waiting, every(0, 400) { seen(it, ready = false, present = false, sig = null) })
        assertEquals(ScanAutoPhase.Searching, long.phase)
        val (_, shots) = run(long, every(450, 1_600) { seen(it) })
        assertEquals(1, shots.size)
    }

    @Test
    fun shakingDuringTheCountdownCancels() {
        val events = every(0, 700) { seen(it) } + seen(750, still = false) + every(800, 1_000) { seen(it) }
        val (s, shots) = run(ScanAutoState(), events)
        assertTrue(shots.isEmpty())
        assertEquals(ScanAutoPhase.Searching, s.phase)
    }

    @Test
    fun notReadyOrNoPageNeverShoots() {
        assertTrue(run(ScanAutoState(), every(0, 5_000) { seen(it, ready = false) }).second.isEmpty())
        assertTrue(run(ScanAutoState(), every(0, 5_000) { seen(it, present = false) }).second.isEmpty())
        assertTrue(run(ScanAutoState(), every(0, 5_000) { seen(it, still = false) }).second.isEmpty())
    }

    @Test
    fun theShutterDuringTheCountdownTakesOnePhoto() {
        val (armed, _) = run(ScanAutoState(), every(0, 700) { seen(it) })
        assertEquals(ScanAutoPhase.Arming, armed.phase)
        val (s, shoot) = armed.reduce(ScanAutoEvent.Shutter(720L))
        assertFalse("the shutter takes the photo itself", shoot)
        assertEquals(ScanAutoPhase.Capturing, s.phase)
        assertFalse(s.auto)
        // the countdown's end does not add a second photo
        val (after, shots) = run(s, every(750, 1_500) { seen(it) })
        assertTrue(shots.isEmpty())
        assertEquals(ScanAutoPhase.Capturing, after.phase)
    }

    @Test
    fun aFailedPhotoSearchesAgain() {
        val capturing = ScanAutoState(ScanAutoPhase.Capturing, auto = true)
        assertEquals(ScanAutoPhase.Searching, capturing.reduce(ScanAutoEvent.CaptureEnded(false, null, 0L)).first.phase)
    }

    @Test
    fun cancellingSkipsThisPage() {
        val (armed, _) = run(ScanAutoState(), every(0, 700) { seen(it) })
        val (s, _) = armed.reduce(ScanAutoEvent.Cancel(sigA, 720L))
        assertEquals(ScanAutoPhase.WaitingForNewPage, s.phase)
        assertTrue(run(s, every(750, 3_000) { seen(it) }).second.isEmpty())
    }

    @Test
    fun countdownProgress() {
        val armed = ScanAutoState(ScanAutoPhase.Arming, armedAt = 1_000L)
        assertEquals(0f, armed.armProgress(1_000L), 1e-6f)
        assertEquals(0.5f, armed.armProgress(1_300L), 1e-6f)
        assertEquals(1f, armed.armProgress(2_000L), 1e-6f)
        assertEquals(0f, ScanAutoState().armProgress(1_300L), 1e-6f)
    }

    // ---- the page's signature ----

    /** A frame of [w] × [h] whose luminance is [f] of the pixel. */
    private fun frame(w: Int, h: Int, f: (Int, Int) -> Int): ByteArray {
        val b = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val v = f(x, y).coerceIn(0, 255).toByte()
            val o = (y * w + x) * 4
            b[o] = v
            b[o + 1] = v
            b[o + 2] = v
            b[o + 3] = -1
        }
        return b
    }

    private val whole = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)

    @Test
    fun signatureIsTheSameUnderMoreOrLessLight() {
        val page = { x: Int, y: Int -> if ((x / 8 + y / 8) % 2 == 0) 180 else 60 }
        val a = scanPageSignature(frame(64, 48, page), 64, 48, 0, whole)
        val brighter = scanPageSignature(frame(64, 48) { x, y -> page(x, y) + 20 }, 64, 48, 0, whole)
        assertNotNull(a)
        assertFalse(scanSignatureDiffers(a, brighter))
    }

    @Test
    fun twoDifferentPagesDiffer() {
        val stripes = scanPageSignature(frame(64, 48) { x, _ -> if (x < 32) 200 else 50 }, 64, 48, 0, whole)
        val bands = scanPageSignature(frame(64, 48) { _, y -> if (y < 24) 200 else 50 }, 64, 48, 0, whole)
        assertTrue(scanSignatureDiffers(stripes, bands))
        assertFalse(scanSignatureDiffers(stripes, null))
    }

    @Test
    fun signatureFollowsTheFramesTurn() {
        // the upright page is dark on its left half; the camera gave it turned 90° (upright = raw turned clockwise)
        val w = 48
        val h = 64
        // raw (x, y) is upright (h - 1 - y, x): upright left half is raw bottom half
        val raw = frame(w, h) { _, y -> if (y >= h / 2) 30 else 220 }
        val turned = scanPageSignature(raw, w, h, 90, whole)
        val upright = scanPageSignature(frame(h, w) { x, _ -> if (x < h / 2) 30 else 220 }, h, w, 0, whole)
        assertNotNull(turned)
        assertFalse(scanSignatureDiffers(turned, upright))
        // and the turned frame's own sample is not the unturned reading of it
        val unturned = scanPageSignature(raw, w, h, 0, whole)
        assertTrue(scanSignatureDiffers(turned, unturned))
    }

    @Test
    fun uprightToFrameForEachTurn() {
        val r = { p: Pair<Float, Float> -> Math.round(p.first * 10) / 10f to Math.round(p.second * 10) / 10f }
        assertEquals(0.2f to 0.7f, r(scanUprightToFrame(0.2f, 0.7f, 0)))
        assertEquals(0.7f to 0.8f, r(scanUprightToFrame(0.2f, 0.7f, 90)))
        assertEquals(0.8f to 0.3f, r(scanUprightToFrame(0.2f, 0.7f, 180)))
        assertEquals(0.3f to 0.2f, r(scanUprightToFrame(0.2f, 0.7f, 270)))
    }

    @Test
    fun badInputHasNoSignature() {
        assertNull(scanPageSignature(ByteArray(10), 4, 4, 0, whole))
        assertNull(scanPageSignature(ByteArray(64), 4, 4, 45, whole))
        assertNull(scanPageSignature(ByteArray(64), 4, 4, 0, floatArrayOf(0f, 0f)))
    }

    // ---- holding still ----

    @Test
    fun stillnessFromTheGyroscope() {
        val s = ScanStillness()
        assertTrue("no gyroscope readings: still", s.still(0L))
        for (t in 0L..300L step 20L) s.add(t, 0.01f, 0.02f, 0.01f)
        assertTrue(s.still(300L))
        s.add(320L, 0.2f, 0f, 0f)
        assertFalse(s.still(320L))
        // the shake leaves the window after 300 ms
        for (t in 340L..700L step 20L) s.add(t, 0.01f, 0f, 0f)
        assertTrue(s.still(700L))
    }

    @Test
    fun captureFactsRecordAutoAndSharpness() {
        val c = ScanCapture("in_app", autoOn = true, autoShot = true, sharpness = 1.2345, sharpnessBlurredUnverified = false, sharpnessMs = 85)
        val d = c.diag().toMap()
        assertEquals(true, d["auto_shot"])
        val j = c.json()
        assertEquals(1.235, j.getDouble("sharpness"), 1e-9)
        assertTrue(ScanCapture("in_app").json().isNull("sharpness"))
        assertEquals(false, ScanCapture("in_app").diag().toMap()["auto_on"])
    }
}
