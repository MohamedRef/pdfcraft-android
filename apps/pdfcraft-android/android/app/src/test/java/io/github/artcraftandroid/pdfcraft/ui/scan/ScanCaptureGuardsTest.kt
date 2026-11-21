package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.ui.ScanLab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The camera's capture checks (#781): which page a photo is of, a page not known at the shutter,
 * an automatic photo that failed or would repeat, a focus that was replaced, and what the log says
 * of all this. Each fault is shown with the checks off first, as it was, then put right with them on.
 */
class ScanCaptureGuardsTest {
    // three pages that differ by far more than the limit, and one page seen a little differently (under it)
    private val sigA = FloatArray(256) { if (it % 2 == 0) 40f else -40f }
    private val sigB = FloatArray(256) { if (it % 32 < 16) 40f else -40f }
    private val sigOld = FloatArray(256) { if (it % 8 < 4) 40f else -40f }
    private val sigANoisy = FloatArray(256) { sigA[it] + if (it % 3 == 0) 6f else -6f }

    private fun seen(at: Long, ready: Boolean = true, still: Boolean = true, present: Boolean = true, sig: FloatArray? = sigA) =
        ScanAutoEvent.Seen(ScanAutoInputs(at, ready, still, present, sig))

    private fun gone(at: Long) = seen(at, ready = false, present = false, sig = null)

    /** Runs [events] and returns the end state and the times at which a photo was asked for. */
    private fun run(start: ScanAutoState, events: List<ScanAutoEvent>, recorder: ScanAutoRecorder? = null, lines: MutableList<ScanLogLine>? = null): Pair<ScanAutoState, List<Long>> {
        var s = start
        val shots = mutableListOf<Long>()
        for (e in events) {
            val (next, shoot) = s.reduce(e)
            recorder?.step(s, next, e)?.let { lines?.addAll(it) }
            if (shoot) shots += e.at
            s = next
        }
        return s to shots
    }

    private fun every(from: Long, to: Long, step: Long = 50L, make: (Long) -> ScanAutoEvent) = (from..to step step).map(make)

    private fun on() = ScanAutoState(guards = true)

    private fun off() = ScanAutoState(guards = false)

    // ---- the testing option ----

    @Test
    fun theTestingOptionIsOnByDefaultAndSurvivesBeingSaved() {
        val lab = ScanLab()
        assertTrue(lab.captureGuards)
        assertFalse(lab.copy(captureGuards = false).asDesigned)
        assertEquals(ScanLab(captureGuards = false), ScanLab.decode(ScanLab(captureGuards = false).encode()))
        // saved before the option existed: on
        assertTrue(ScanLab.decode("keep=1;finder=auto;camera_buttons=1").captureGuards)
        assertEquals(false, ScanLab(captureGuards = false).diag().toMap()["capture_guards"])
        assertEquals(true, ScanLab().diag().toMap()["capture_guards"])
        assertEquals(false, ScanLab(captureGuards = false).json().getBoolean("capture_guards"))
    }

    @Test
    fun theLimitsAreAsTheyWere() {
        // nothing here was tuned: the phone run sets these, from the log
        assertEquals(500L, ScanAutoRules.READY_MS)
        assertEquals(600L, ScanAutoRules.ARM_MS)
        assertEquals(400L, ScanAutoRules.ABSENT_MS)
        assertEquals(12f, ScanAutoRules.NEW_PAGE_GREY, 0f)
        assertEquals(0.08f, ScanStillness.STILL_RAD_S, 0f)
        assertEquals(6, ScanLiveTracker.STABLE_COUNT)
        assertEquals(2_000L, ScanCameraTiming.FOCUS_FRESH_MS)
        assertEquals(800L, ScanCameraTiming.FOCUS_WAIT_MS)
    }

    // ---- the page a photo is of: the one at the shutter ----

    /** The shutter on page A; the person lays page B down while the photo is written, 1.8 s later; B stays in view. */
    private fun nextPageLaidDownDuringTheCapture(start: ScanAutoState): List<Long> {
        val (capturing, _) = start.reduce(ScanAutoEvent.Shutter(0L, sigA))
        val (during, _) = run(capturing, every(100, 1_700) { seen(it, sig = sigB) })
        // the screen passes the page in view when the file is written, as it always did
        val (waiting, _) = during.reduce(ScanAutoEvent.CaptureEnded(true, sigB, 1_800L))
        assertEquals(ScanAutoPhase.WaitingForNewPage, waiting.phase)
        return run(waiting, every(1_850, 12_000) { seen(it, sig = sigB) }).second
    }

    @Test
    fun aPageLaidDownWhileThePhotoIsWrittenIsStillTakenByItself() {
        // before: page B was recorded as the page shot, so auto-capture waited for it to change and never took it
        assertTrue(nextPageLaidDownDuringTheCapture(off()).isEmpty())
        // with the checks: A was shot, B is another page, and it is taken once
        assertEquals(1, nextPageLaidDownDuringTheCapture(on()).size)
    }

    @Test
    fun anAutomaticPhotoRemembersThePageItFiredOn() {
        val (capturing, shots) = run(on(), every(0, 1_100) { seen(it) })
        assertEquals(listOf(1_100L), shots)
        assertTrue(capturing.auto)
        assertEquals(ScanAutoWhy.FIRED, capturing.why)
        // page B is in view when the file is written
        val (waiting, _) = capturing.reduce(ScanAutoEvent.CaptureEnded(true, sigB, 2_900L))
        assertTrue(scanSignatureDiffers(waiting.shotSignature, sigB))
        assertFalse(scanSignatureDiffers(waiting.shotSignature, sigA))
    }

    // ---- a page not known at the shutter ----

    /** The shutter by hand with no outline shown; the last page the camera had found was an older one. Page A then lies there for ten seconds. */
    private fun shutterWithNoOutline(start: ScanAutoState): Pair<ScanAutoState, List<Long>> {
        val (capturing, _) = start.reduce(ScanAutoEvent.Shutter(0L, null))
        val (waiting, _) = capturing.reduce(ScanAutoEvent.CaptureEnded(true, sigOld, 1_800L))
        return run(waiting, every(1_850, 12_000) { seen(it, sig = sigA) })
    }

    @Test
    fun aPageShotWithNoOutlineIsNotTakenAgainOnceItIsFound() {
        // before: the older page's signature stood for the page shot, the page just shot looked new, and it was taken a second time
        assertEquals(1, shutterWithNoOutline(off()).second.size)
        // with the checks: the first page seen after the shutter stands for the page shot
        val (state, shots) = shutterWithNoOutline(on())
        assertTrue(shots.isEmpty())
        assertEquals(ScanAutoPhase.WaitingForNewPage, state.phase)
        assertEquals(ScanAutoWhy.ADOPTED, state.why)
        // and the next page is still taken
        assertEquals(1, run(state, every(12_050, 14_000) { seen(it, sig = sigB) }).second.size)
    }

    @Test
    fun thePageIsLearnedWhileThePhotoIsStillBeingWritten() {
        val (capturing, _) = on().reduce(ScanAutoEvent.Shutter(0L, null))
        assertNull(capturing.shotSignature)
        // no page, then page A found 300 ms after the shutter, then B laid down
        val (during, _) = run(capturing, listOf(gone(100), seen(300, sig = sigA), seen(900, sig = sigB)))
        assertEquals(ScanAutoPhase.Capturing, during.phase)
        assertFalse(scanSignatureDiffers(during.shotSignature, sigA))
        val (waiting, _) = during.reduce(ScanAutoEvent.CaptureEnded(true, sigB, 1_800L))
        // B is new
        assertEquals(1, run(waiting, every(1_850, 4_000) { seen(it, sig = sigB) }).second.size)
    }

    @Test
    fun aPageNeverSeenAfterTheShutterWaitsUntilTheFrameWasEmpty() {
        val (capturing, _) = on().reduce(ScanAutoEvent.Shutter(0L, null))
        val (waiting, _) = capturing.reduce(ScanAutoEvent.CaptureEnded(true, null, 1_800L))
        val (afterGap, _) = run(waiting, every(1_850, 2_300) { gone(it) })
        assertEquals(ScanAutoPhase.Searching, afterGap.phase)
        assertEquals(ScanAutoWhy.ABSENT, afterGap.why)
    }

    // ---- an automatic photo that failed ----

    @Test
    fun aFailedAutomaticPhotoIsNotFiredAgainEverySecond() {
        fun fires(start: ScanAutoState): Int {
            var s = start
            var fired = 0
            var t = 0L
            // the page lies still for 20 s and every capture fails 300 ms after it starts
            while (t <= 20_000L) {
                val (next, shoot) = s.reduce(seen(t))
                s = next
                if (shoot) {
                    fired++
                    s = s.reduce(ScanAutoEvent.CaptureEnded(false, null, t + 300L)).first
                    t += 300L
                }
                t += 50L
            }
            return fired
        }
        // before: again and again for as long as the fault lasted
        assertTrue(fires(off()) >= 10)
        // with the checks: once; the person presses the shutter, or changes the page
        assertEquals(1, fires(on()))
    }

    @Test
    fun afterAFailedAutomaticPhotoTheShutterAndANewPageStillWork() {
        val (capturing, _) = run(on(), every(0, 1_100) { seen(it) })
        val (failed, _) = capturing.reduce(ScanAutoEvent.CaptureEnded(false, null, 1_400L))
        assertEquals(ScanAutoPhase.WaitingForNewPage, failed.phase)
        assertEquals(ScanAutoWhy.FAILED, failed.why)
        assertEquals(ScanAutoPhase.Capturing, failed.reduce(ScanAutoEvent.Shutter(1_500L, sigA)).first.phase)
        assertEquals(1, run(failed, every(1_450, 3_000) { seen(it, sig = sigB) }).second.size)
        // a photo by hand that failed is looked for again, as before: auto-capture may try it once
        val (byHand, _) = on().reduce(ScanAutoEvent.Shutter(0L, sigA))
        assertEquals(ScanAutoPhase.Searching, byHand.reduce(ScanAutoEvent.CaptureEnded(false, null, 300L)).first.phase)
    }

    // ---- the same page, looking different for a moment ----

    /** Page A was shot and lies there; a hand passes over it for 200 ms; A lies there for ten more seconds. */
    private fun handOverThePage(start: ScanAutoState): Pair<ScanAutoState, List<Long>> {
        val (capturing, _) = start.reduce(ScanAutoEvent.Shutter(0L, sigA))
        val (waiting, _) = capturing.reduce(ScanAutoEvent.CaptureEnded(true, sigA, 1_800L))
        val events = every(1_850, 3_000) { seen(it) } + every(3_050, 3_250) { seen(it, ready = false, sig = sigB) } + every(3_300, 13_300) { seen(it) }
        return run(waiting, events)
    }

    @Test
    fun aHandOverThePageDoesNotTakeItASecondTime() {
        // before: the hand made the page look new, and the page was taken again once the hand had gone
        assertEquals(1, handOverThePage(off()).second.size)
        // with the checks: the page that becomes ready again is the one last shot
        val (state, shots) = handOverThePage(on())
        assertTrue(shots.isEmpty())
        assertEquals(ScanAutoPhase.WaitingForNewPage, state.phase)
        assertEquals(ScanAutoWhy.SAME_PAGE, state.why)
    }

    @Test
    fun theSamePageSeenALittleDifferentlyIsStillTheSamePage() {
        val waiting = ScanAutoState(ScanAutoPhase.WaitingForNewPage, shotSignature = sigA, guards = true)
        assertNotNull(scanSignatureDiff(sigA, sigANoisy))
        assertTrue(scanSignatureDiff(sigA, sigANoisy)!! < ScanAutoRules.NEW_PAGE_GREY)
        assertTrue(run(waiting, every(0, 10_000) { seen(it, sig = sigANoisy) }).second.isEmpty())
    }

    @Test
    fun aNewPageIsTakenAsPromptlyAsBefore() {
        for (start in listOf(off(), on())) {
            val (capturing, _) = start.reduce(ScanAutoEvent.Shutter(0L, sigA))
            val (waiting, _) = capturing.reduce(ScanAutoEvent.CaptureEnded(true, sigA, 1_800L))
            // page B from 2 000: ready 500 ms, the ring 600 ms
            val (_, shots) = run(waiting, every(2_000, 4_000) { seen(it, sig = sigB) })
            assertEquals(listOf(3_150L), shots)
        }
    }

    @Test
    fun thePageLiftedAndLaidDownAgainIsNewAsBefore() {
        for (start in listOf(off(), on())) {
            val (capturing, _) = start.reduce(ScanAutoEvent.Shutter(0L, sigA))
            val (waiting, _) = capturing.reduce(ScanAutoEvent.CaptureEnded(true, sigA, 1_800L))
            val (_, shots) = run(waiting, every(1_850, 2_300) { gone(it) } + every(2_350, 4_000) { seen(it) })
            assertEquals(1, shots.size)
        }
    }

    @Test
    fun afterAHandAndThenAnEmptyFrameThePageLaidDownIsNew() {
        // a hand made the page look different (searching again, the page shot remembered); then the frame was empty
        val searching = ScanAutoState(ScanAutoPhase.Searching, guards = true, lastShot = sigA, why = ScanAutoWhy.DIFFERS)
        val (short, _) = run(searching, every(0, 300) { gone(it) })
        assertNotNull(short.lastShot)
        val (long, _) = run(searching, every(0, 400) { gone(it) })
        assertNull(long.lastShot)
        assertEquals(1, run(long, every(450, 2_000) { seen(it) }).second.size)
        // not empty long enough: still the page last shot, and not taken
        assertTrue(run(short, every(350, 2_000) { seen(it) }).second.isEmpty())
    }

    @Test
    fun theShutterByHandTakesTheSamePageAgainOnPurpose() {
        val waiting = ScanAutoState(ScanAutoPhase.WaitingForNewPage, shotSignature = sigA, guards = true)
        val (s, shoot) = waiting.reduce(ScanAutoEvent.Shutter(100L, sigA))
        assertFalse("the shutter takes the photo itself", shoot)
        assertEquals(ScanAutoPhase.Capturing, s.phase)
        assertFalse(s.auto)
        // and auto-capture adds none while it is taken, nor after
        val (during, shots) = run(s, every(150, 1_800) { seen(it) })
        assertTrue(shots.isEmpty())
        val (after, _) = during.reduce(ScanAutoEvent.CaptureEnded(true, sigA, 1_900L))
        assertTrue(run(after, every(1_950, 12_000) { seen(it) }).second.isEmpty())
    }

    @Test
    fun aSkipWaitsForANewPageWithTheChecksToo() {
        val (armed, _) = run(on(), every(0, 700) { seen(it) })
        assertEquals(ScanAutoPhase.Arming, armed.phase)
        val (s, _) = armed.reduce(ScanAutoEvent.Cancel(sigA, 720L))
        assertEquals(ScanAutoWhy.SKIPPED, s.why)
        assertTrue(run(s, every(750, 5_000) { seen(it) }).second.isEmpty())
        assertEquals(1, run(s, every(750, 3_000) { seen(it, sig = sigB) }).second.size)
    }

    @Test
    fun whyTheRingStoppedIsSaid() {
        val (armed, _) = run(on(), every(0, 700) { seen(it) })
        assertEquals(ScanAutoWhy.ARMED, armed.why)
        assertEquals(ScanAutoWhy.LOST_STILL, armed.reduce(seen(750, still = false)).first.why)
        assertEquals(ScanAutoWhy.LOST_READY, armed.reduce(seen(750, ready = false)).first.why)
        assertEquals(ScanAutoWhy.LOST_PAGE, armed.reduce(seen(750, ready = false, present = false, sig = null)).first.why)
        // the option stays through every change
        assertTrue(armed.guards)
        assertTrue(armed.reduce(seen(750, still = false)).first.guards)
        assertFalse(run(off(), every(0, 700) { seen(it) }).first.guards)
    }

    // ---- a focus that another one replaced ----

    private fun cam(start: ScanCameraState, vararg events: ScanCameraEvent): Pair<ScanCameraState, List<ScanCameraEffect>> {
        var state = start
        val effects = mutableListOf<ScanCameraEffect>()
        for (e in events) {
            val (next, fx) = state.reduce(e)
            state = next
            effects += fx
        }
        return state to effects
    }

    @Test
    fun aTapWhileAPhotoWaitsForItsFocusDoesNotLetThePhotoGoEarly() {
        // the shutter with no fresh focus: the photo waits for the focus at the middle
        val (waiting, fx) = cam(ScanCameraState(), ScanCameraEvent.Shutter(0L))
        assertEquals(listOf(ScanCameraEffect.FocusCentre), fx)
        val first = waiting.focusSeq
        // the person taps the viewfinder: the screen starts that focus, which ends the first one as not focused
        val (tapped, _) = cam(waiting, ScanCameraEvent.TapFocus(200L))
        assertTrue(tapped.focusSeq != first)

        // before (no number): the first focus ending let the photo go, with the lens on its way to the tapped place
        val (early, fxEarly) = cam(tapped, ScanCameraEvent.FocusEnded(false, 210L))
        assertEquals(listOf(ScanCameraEffect.Capture), fxEarly)
        assertEquals(ScanCameraPhase.Capturing, early.phase)

        // with the checks: the replaced focus's end is not acted on, and is not remembered as the last focus
        val (still, fxStill) = cam(tapped, ScanCameraEvent.FocusEnded(false, 210L, seq = first))
        assertTrue(fxStill.isEmpty())
        assertEquals(ScanCameraPhase.Focusing, still.phase)
        assertTrue(still.captureQueued)
        assertNull(still.lastFocusAt)
        // the tapped focus ends: now the photo is taken, focused
        val (taken, fxTaken) = cam(still, ScanCameraEvent.FocusEnded(true, 600L, seq = tapped.focusSeq))
        assertEquals(listOf(ScanCameraEffect.Capture), fxTaken)
        assertEquals(ScanCaptureVia.Focused, taken.via)
        assertEquals(600L, taken.focusWaitMs)
        assertTrue(taken.lastFocusOk)
    }

    @Test
    fun aPhotoWaitingForAReplacedFocusStillGoesWhenTheWaitRunsOut() {
        val (waiting, _) = cam(ScanCameraState(), ScanCameraEvent.Shutter(0L), ScanCameraEvent.TapFocus(200L))
        val (kept, fx) = cam(waiting, ScanCameraEvent.FocusEnded(false, 210L, seq = 1), ScanCameraEvent.Tick(700L))
        assertTrue(fx.isEmpty())
        val (taken, fx2) = cam(kept, ScanCameraEvent.Tick(ScanCameraTiming.FOCUS_WAIT_MS))
        assertEquals(listOf(ScanCameraEffect.Capture), fx2)
        assertEquals(ScanCaptureVia.Timeout, taken.via)
        assertEquals(ScanCameraTiming.FOCUS_WAIT_MS, taken.focusWaitMs)
    }

    @Test
    fun twoTapsInARowEndOnTheSecondFocus() {
        val (two, _) = cam(ScanCameraState(), ScanCameraEvent.TapFocus(0L), ScanCameraEvent.TapFocus(100L))
        assertEquals(2, two.focusSeq)
        val (afterFirst, _) = cam(two, ScanCameraEvent.FocusEnded(false, 110L, seq = 1))
        assertEquals(ScanCameraPhase.Focusing, afterFirst.phase)
        val (afterSecond, _) = cam(afterFirst, ScanCameraEvent.FocusEnded(true, 500L, seq = 2))
        assertEquals(ScanCameraPhase.Idle, afterSecond.phase)
        assertTrue(afterSecond.focusFresh(600L))
    }

    @Test
    fun howACaptureStartedIsKept() {
        // fresh focus: at once
        val focused = ScanCameraState(lastFocusAt = 0L, lastFocusOk = true)
        val (fresh, fx) = cam(focused, ScanCameraEvent.Shutter(1_000L))
        assertEquals(listOf(ScanCameraEffect.Capture), fx)
        assertEquals(ScanCaptureVia.Fresh, fresh.via)
        assertEquals(0L, fresh.focusWaitMs)
        // a focus that ended without focusing
        val (unfocused, _) = cam(ScanCameraState(), ScanCameraEvent.Shutter(5_000L), ScanCameraEvent.FocusEnded(false, 5_350L, seq = 1))
        assertEquals(ScanCaptureVia.Unfocused, unfocused.via)
        assertEquals(350L, unfocused.focusWaitMs)
        // the shutter during a tapped focus: the wait counts from the shutter, not from the tap
        val (tap, _) = cam(ScanCameraState(), ScanCameraEvent.TapFocus(0L), ScanCameraEvent.Shutter(300L), ScanCameraEvent.FocusEnded(true, 450L, seq = 1))
        assertEquals(ScanCaptureVia.Focused, tap.via)
        assertEquals(150L, tap.focusWaitMs)
        assertEquals(ScanCaptureVia.None, ScanCameraState().via)
        val ids = ScanCaptureVia.entries.map { it.id }
        assertTrue(ids.all { Regex("[a-z_]+").matches(it) })
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun aPhotosFactsSayHowItCameAbout() {
        val (state, _) = cam(ScanCameraState(), ScanCameraEvent.Shutter(10_000L), ScanCameraEvent.FocusEnded(true, 10_400L, seq = 1))
        val facts = scanShotFacts(state, endedAt = 11_800L, lastEndedAt = 6_000L, autoPhase = "waiting", signatureKnown = true, guards = true)
        assertEquals(ScanShotFacts("focused", focusWaitMs = 400L, shotMs = 1_400L, sinceShotMs = 4_000L, autoPhase = "waiting", signatureKnown = true, guards = true), facts)
        // the first photo of a camera has none before it
        assertEquals(-1L, scanShotFacts(state, 11_800L, null, "searching", false, true).sinceShotMs)
        // nothing known (a photo that did not come through the reducer): -1, not a made-up time
        val unknown = scanShotFacts(ScanCameraState(), 11_800L, null, "off", false, false)
        assertEquals(-1L, unknown.focusWaitMs)
        assertEquals(-1L, unknown.shotMs)
        assertEquals("none", unknown.via)

        val capture = ScanCapture("in_app", liveWanted = "still", shot = facts)
        val line = capture.diag().toMap()
        assertEquals("still", line["live_wanted"])
        assertEquals("focused", line["focus_via"])
        assertEquals(400L, line["focus_wait_ms"])
        assertEquals(1_400L, line["shot_ms"])
        assertEquals(4_000L, line["since_shot_ms"])
        assertEquals("waiting", line["auto_phase"])
        assertEquals(true, line["signature_known"])
        assertEquals(true, line["capture_guards"])
        val json = capture.json()
        assertEquals("focused", json.getString("focus_via"))
        assertEquals(400L, json.getLong("focus_wait_ms"))
        assertEquals(1_400L, json.getLong("shot_ms"))
        assertEquals(4_000L, json.getLong("since_shot_ms"))
        assertEquals("waiting", json.getString("auto_phase"))
        assertTrue(json.getBoolean("signature_known"))
        assertEquals("still", json.getString("live_wanted"))
        // an import has none of it
        assertEquals("none", ScanCapture("import").diag().toMap()["focus_via"])
        assertEquals(-1L, ScanCapture("import").json().getLong("shot_ms"))
    }

    // ---- the outline kept with a photo ----

    @Test
    fun theOutlineKeptWithAPhotoIsTheOneAtTheShot() {
        val quadA = floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)
        val atShot = ScanLiveSnap(found = true, stable = true, hint = "ready", wanted = "ready", quad = quadA)
        // the page was lifted while the file was written
        val atEnd = ScanLiveSnap(found = false, stable = false, hint = "ready", wanted = "point", quad = null)
        assertTrue(scanLiveSnapKept(true, atShot, atEnd) === atShot)
        // off, or nothing taken at the shot: as before
        assertTrue(scanLiveSnapKept(false, atShot, atEnd) === atEnd)
        assertTrue(scanLiveSnapKept(true, null, atEnd) === atEnd)
    }

    // ---- what the log says ----

    @Test
    fun theLogSaysEveryChangeAndWhy() {
        val recorder = ScanAutoRecorder()
        val lines = mutableListOf<ScanLogLine>()
        val (capturing, _) = run(on(), every(0, 1_100) { seen(it) }, recorder, lines)
        val phases = lines.filter { it.first == "camera_auto_phase" }.map { it.second.toMap() }
        assertEquals(listOf("arming", "capturing"), phases.map { it["phase"] })
        assertEquals(listOf("armed", "fired"), phases.map { it["why"] })
        assertTrue(phases.all { it["guards"] == true && it["skipped"] == false })
        lines.clear()
        // the photo is written: the wait for a new page, which the loop never logged before
        run(capturing, listOf(ScanAutoEvent.CaptureEnded(true, null, 2_900L)), recorder, lines)
        assertEquals("waiting", lines.single().second.toMap()["phase"])
        assertEquals("taken", lines.single().second.toMap()["why"])
    }

    @Test
    fun aWaitForANewPageSaysHowFarThePageDrifted() {
        val recorder = ScanAutoRecorder()
        val lines = mutableListOf<ScanLogLine>()
        val waiting = ScanAutoState(ScanAutoPhase.Capturing, shotSignature = sigA, guards = true)
        val drift = scanSignatureDiff(sigA, sigANoisy)!!.toDouble()
        val jump = scanSignatureDiff(sigA, sigB)!!.toDouble()
        // the same page for two seconds, seen a little differently every other look; then page B
        val events = listOf<ScanAutoEvent>(ScanAutoEvent.CaptureEnded(true, null, 1_000L)) +
            every(1_100, 3_000, 100) { seen(it, sig = if (it % 200L == 0L) sigANoisy else sigA) } + seen(3_100, sig = sigB)
        val (state, _) = run(waiting, events, recorder, lines)
        assertEquals(ScanAutoPhase.Searching, state.phase)
        val rearm = lines.single { it.first == "camera_auto_rearm" }.second.toMap()
        assertEquals("differs", rearm["why"])
        assertEquals(2_100L, rearm["wait_ms"])
        assertEquals(21, rearm["looks"])
        assertEquals(jump, rearm["diff_last"] as Double, 0.06)
        assertEquals(jump, rearm["diff_max"] as Double, 0.06)
        // half the looks were the page as shot (0), half a little off: the middle one is one of those two
        assertTrue((rearm["diff_p50"] as Double) <= drift + 0.06)
        assertEquals(12.0, rearm["limit"] as Double, 1e-9)
        // the line order: the wait's end, then the new phase
        assertEquals(listOf("camera_auto_rearm", "camera_auto_phase"), lines.takeLast(2).map { it.first })
    }

    @Test
    fun aWaitThatEndsWithTheShutterOrTheCameraClosingIsSaidToo() {
        val recorder = ScanAutoRecorder()
        val lines = mutableListOf<ScanLogLine>()
        val capturing = ScanAutoState(ScanAutoPhase.Capturing, shotSignature = sigA, guards = true)
        // ten seconds on the same page, then the shutter by hand
        val events = listOf<ScanAutoEvent>(ScanAutoEvent.CaptureEnded(true, null, 0L)) + every(100, 10_000, 100) { seen(it) } + ScanAutoEvent.Shutter(10_100L, sigA)
        val (again, _) = run(capturing, events, recorder, lines)
        val byHand = lines.single { it.first == "camera_auto_rearm" }.second.toMap()
        assertEquals("shutter", byHand["why"])
        assertEquals(10_100L, byHand["wait_ms"])
        assertEquals(100, byHand["looks"])
        assertEquals(0.0, byHand["diff_max"] as Double, 1e-9)
        // that photo is written, and the camera closes while it waits
        val (waiting, _) = run(again, listOf(ScanAutoEvent.CaptureEnded(true, null, 12_000L)), recorder, lines)
        val closing = recorder.close(waiting, 15_000L)
        assertEquals(listOf("camera_auto_rearm", "camera_auto_summary"), closing.map { it.first })
        val closed = closing.first().second.toMap()
        assertEquals("closed", closed["why"])
        assertEquals(3_000L, closed["wait_ms"])
        assertEquals(0, closed["looks"])
        assertEquals(-1.0, closed["diff_max"] as Double, 1e-9)
        // closing twice does not say the wait twice
        assertEquals(listOf("camera_auto_summary"), recorder.close(waiting, 15_100L).map { it.first })
    }

    @Test
    fun theAveragedSignatureIsMeasuredBesideTheOneInUse() {
        // a page of thin lines, three pixels apart, and the same page with its outline one pixel further right
        val w = 480
        val h = 640
        val frame = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val v = (if (x % 3 == 0) 40 else 220).toByte()
            val o = (y * w + x) * 4
            frame[o] = v
            frame[o + 1] = v
            frame[o + 2] = v
            frame[o + 3] = -1
        }
        val quad = floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)
        val nudged = FloatArray(8) { if (it % 2 == 0) quad[it] + 1f / w else quad[it] }
        val point = scanSignatureDiff(scanPageSignature(frame, w, h, 0, quad), scanPageSignature(frame, w, h, 0, nudged))!!
        val mean = scanSignatureDiff(
            scanPageSignature(frame, w, h, 0, quad, SCAN_SIGNATURE_MEAN_PX), scanPageSignature(frame, w, h, 0, nudged, SCAN_SIGNATURE_MEAN_PX),
        )!!
        // one pixel per sample lands on a line or beside it; the mean around each sample hardly moves
        assertTrue("point $point", point > 30f)
        assertTrue("mean $mean", mean < 12f)
        assertTrue(mean < point / 3f)
        // the signature in use is the one it always was, and a reach past the frame's edge reads no pixel outside it
        assertEquals(scanPageSignature(frame, w, h, 0, quad)!!.toList(), scanPageSignature(frame, w, h, 0, quad, 0)!!.toList())
        assertNotNull(scanPageSignature(frame, w, h, 90, floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), SCAN_SIGNATURE_MEAN_PX))

        // and the log reports both while a page is waited for
        val recorder = ScanAutoRecorder()
        val lines = mutableListOf<ScanLogLine>()
        // not waiting yet: nothing is kept
        recorder.meanLook(9f)
        var s = ScanAutoState(ScanAutoPhase.Capturing, shotSignature = sigA, guards = true)
        s = run(s, listOf(ScanAutoEvent.CaptureEnded(true, null, 0L)), recorder, lines).first
        for (k in 1..4) {
            recorder.meanLook(k.toFloat())
            recorder.meanLook(null)
            s = run(s, listOf(seen(k * 100L)), recorder, lines).first
        }
        recorder.meanLook(7.5f)
        run(s, listOf(seen(500L, sig = sigB)), recorder, lines)
        val rearm = lines.single { it.first == "camera_auto_rearm" }.second.toMap()
        assertEquals(5, rearm["mean_looks"])
        assertEquals(7.5, rearm["mean_diff_last"] as Double, 1e-9)
        assertEquals(7.5, rearm["mean_diff_max"] as Double, 1e-9)
        assertEquals(3.0, rearm["mean_diff_p50"] as Double, 1e-9)
    }

    @Test
    fun theSummaryCountsWhatHappened() {
        val recorder = ScanAutoRecorder()
        // ready, armed, the phone moves, ready and armed again, fired, written; then a hand, the same page, the shutter
        val events = every(0, 700) { seen(it) } + seen(750, still = false) + every(800, 2_000) { seen(it) } +
            ScanAutoEvent.CaptureEnded(true, null, 3_800L) + every(3_850, 4_000) { seen(it) } + seen(4_050, ready = false, sig = sigB) +
            every(4_100, 5_000) { seen(it) } + ScanAutoEvent.Shutter(5_050L, sigA)
        val (state, shots) = run(on(), events, recorder)
        assertEquals(1, shots.size)
        recorder.refused()
        val summary = recorder.summary(state.guards).toMap()
        assertEquals(true, summary["guards"])
        assertEquals(3, summary["ready"])
        assertEquals(2, summary["armed"])
        assertEquals(1, summary["lost_still"])
        assertEquals(0, summary["lost_ready"])
        assertEquals(1, summary["fired"])
        assertEquals(1, summary["taken"])
        assertEquals(1, summary["differs"])
        assertEquals(1, summary["same_page"])
        assertEquals(1, summary["shutter"])
        assertEquals(1, summary["shutter_waiting"])
        assertEquals(0, summary["shutter_arming"])
        assertEquals(1, summary["refused"])
        // always the same fields, in the same order, all of them words and counts
        assertEquals(listOf("guards") + ScanAutoRecorder.KEYS, ScanAutoRecorder().summary(false).map { it.first })
        assertTrue(ScanAutoRecorder.KEYS.all { Regex("[a-z_]+").matches(it) })
        assertEquals(ScanAutoRecorder.KEYS.size, ScanAutoRecorder.KEYS.toSet().size)
    }

    @Test
    fun theSteadyTestSaysHowFastDetectionsCameAndWhyRunsEnded() {
        val page = floatArrayOf(0.2f, 0.2f, 0.8f, 0.2f, 0.8f, 0.8f, 0.2f, 0.8f)
        val moved = floatArrayOf(0.5f, 0.2f, 0.95f, 0.2f, 0.95f, 0.8f, 0.5f, 0.8f)
        val tracker = ScanLiveTracker()
        fun found(at: Long, points: FloatArray = page, confidence: Float = 0.9f) = tracker.update(ScanLiveDetection(points, confidence, 120f, found = true, cutOff = false, at = at))
        // five detections 200 ms apart: sure, but one short of what steady asks for
        for (k in 0 until 5) assertFalse(found(k * 200L).stable)
        var d = tracker.diag().toMap()
        assertEquals(200L, d["gap_p50_ms"])
        assertEquals(5, d["run_max"])
        assertEquals(0, d["steady_times"])
        assertEquals(ScanLiveTracker.STABLE_COUNT, d["steady_needs"])
        // the sixth makes it steady, once
        assertTrue(found(1_000L).stable)
        assertTrue(found(1_200L).stable)
        d = tracker.diag().toMap()
        assertEquals(1, d["steady_times"])
        assertEquals(7, d["run_max"])
        // a detection 900 ms later: the run ends for the pause
        assertFalse(found(2_100L).stable)
        // and the page jumping ends the next one
        found(2_300L)
        assertFalse(found(2_500L, moved).stable)
        // an unsure detection ends a run without a jump or a pause
        found(2_700L, moved, confidence = 0.3f)
        d = tracker.diag().toMap()
        assertEquals(1, d["runs_stale"])
        assertEquals(1, d["runs_jumped"])
        assertEquals(1, d["gaps_over"])
        assertEquals(200L, d["gap_p90_ms"])
        assertEquals(900L, d["gap_max_ms"])
        assertEquals(7, d["run_max"])
        // nothing seen: -1, not a made-up time
        assertEquals(-1L, ScanLiveTracker().diag().toMap()["gap_p50_ms"])
    }

    @Test
    fun theGyroscopesReadingsAreSummedUpForTheLog() {
        val none = ScanStillness().diag().toMap()
        assertEquals(0, none["gyro_readings"])
        assertEquals(-1, none["gyro_over_pct"])
        assertEquals(-1.0, none["gyro_p50"] as Double, 1e-9)
        val s = ScanStillness()
        // 80 readings of a steady hand, 20 of a moving one
        for (k in 0 until 80) s.add(k * 10L, 0.02f, 0f, 0f)
        for (k in 80 until 100) s.add(k * 10L, 0f, 0.2f, 0f)
        val d = s.diag().toMap()
        assertEquals(100, d["gyro_readings"])
        assertEquals(20, d["gyro_over_pct"])
        assertEquals(0.03, d["gyro_p50"] as Double, 1e-9)
        assertEquals(0.21, d["gyro_p90"] as Double, 1e-9)
        assertEquals(0.08, d["gyro_limit"] as Double, 1e-9)
        // the decision itself is as it was
        assertFalse(s.still(1_000L))
        assertTrue(ScanStillness().still(0L))
    }
}
