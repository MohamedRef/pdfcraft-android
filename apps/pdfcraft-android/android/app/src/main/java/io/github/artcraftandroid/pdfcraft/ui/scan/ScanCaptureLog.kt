package io.github.artcraftandroid.pdfcraft.ui.scan

/*
 * What a phone run needs to see of the camera's capture decisions (#781), as pure code tested on
 * the JVM: what the live outline said when a photo was asked for (not when its file was written),
 * what auto-capture did between two photos and why, and how far the page's signature moved while
 * it waited for a new page. Nothing here decides anything: it only counts and reports, in numbers,
 * booleans and short lowercase words.
 */

/** One line for the diagnostics log: its name and its fields. */
internal typealias ScanLogLine = Pair<String, Array<Pair<String, Any?>>>

/**
 * What the live outline said at one moment (#295): whether a page was shown, whether it was
 * steady, the advice on the screen ([hint], which follows the scene 400 ms late so it does not
 * flicker), the advice the scene called for at that moment ([wanted]), and the outline.
 */
internal data class ScanLiveSnap(
    val found: Boolean = false,
    val stable: Boolean = false,
    val hint: String = "none",
    val wanted: String = "none",
    val quad: FloatArray? = null,
)

/**
 * The live outline's facts kept with a photo (#781). A capture takes about two seconds on the
 * owner's phone, and by the time its file is written the page may have been lifted or the next one
 * laid down: with the capture checks on, the facts are those of the moment the capture started
 * ([atShot]), so the outline kept with the photo, inside which its sharpness is measured, is the
 * outline of the page in the photo. Off, or when none was taken then: as before, those of the
 * moment the file was written ([atEnd]).
 */
internal fun scanLiveSnapKept(guards: Boolean, atShot: ScanLiveSnap?, atEnd: ScanLiveSnap): ScanLiveSnap = if (guards && atShot != null) atShot else atEnd

/**
 * Follows auto-capture's state from one camera opening to its closing (#781) and says what to
 * write to the log:
 * - `camera_auto_phase` on every change of phase, with why ([ScanAutoWhy]);
 * - `camera_auto_rearm` each time a wait for a new page ends: why (`absent`, `differs`, `shutter`,
 *   `skipped`, or `closed` when the camera closed first), how long it waited, how many times the
 *   page in view was compared with the page shot, and how far apart their signatures were (the
 *   last comparison, the middle one and the largest, in grey levels, against the limit), and the
 *   same for the averaged signature that is only measured. A page left lying for ten seconds
 *   shows how far the same page drifts; a page change shows how far a new one is: a limit belongs
 *   between the two, for whichever signature keeps them apart;
 * - `camera_auto_summary` when the camera closes: how often each thing happened.
 */
internal class ScanAutoRecorder {
    private val counts = LinkedHashMap<String, Int>()
    private var waitFrom: Long? = null
    private val diffs = ArrayList<Float>()
    private var lastDiff: Float? = null

    // the same comparisons with the averaged signature, which is only measured
    private val meanDiffs = ArrayList<Float>()
    private var lastMeanDiff: Float? = null

    private fun count(key: String) {
        counts[key] = (counts[key] ?: 0) + 1
    }

    /** [event] took auto-capture from [before] to [after]: the lines to log. */
    fun step(before: ScanAutoState, after: ScanAutoState, event: ScanAutoEvent): List<ScanLogLine> {
        val lines = ArrayList<ScanLogLine>()
        // every condition held for the first time since they last did not
        if (before.phase == ScanAutoPhase.Searching && after.phase == ScanAutoPhase.Searching && before.readySince == null && after.readySince != null) count("ready")
        // a look at the page while waiting: how far it is from the page shot
        if (event is ScanAutoEvent.Seen && before.phase == ScanAutoPhase.WaitingForNewPage && event.inputs.pagePresent) {
            scanSignatureDiff(before.shotSignature, event.inputs.signature)?.let { d ->
                lastDiff = d
                if (diffs.size < MAX_LOOKS) diffs.add(d)
            }
        }
        val changed = after.phase != before.phase
        if (changed || after.why != before.why) {
            count(after.why)
            // the shutter by hand: where auto-capture was when the person did not wait for it
            if (after.why == ScanAutoWhy.SHUTTER) count("shutter_" + before.phase.id)
        }
        if (changed) {
            if (before.phase == ScanAutoPhase.WaitingForNewPage) lines.add("camera_auto_rearm" to waitEnded(after.why, event.at))
            if (after.phase == ScanAutoPhase.WaitingForNewPage) {
                waitFrom = event.at
                diffs.clear()
                lastDiff = null
                meanDiffs.clear()
                lastMeanDiff = null
            }
            lines.add("camera_auto_phase" to arrayOf<Pair<String, Any?>>("phase" to after.phase.id, "why" to after.why, "skipped" to (after.why == ScanAutoWhy.SKIPPED), "guards" to after.guards))
        }
        return lines
    }

    /**
     * While waiting for a new page: how far the page in view is from the page shot by the averaged
     * signature ([SCAN_SIGNATURE_MEAN_PX]), null when either is not known. Told before [step] for
     * the same look, so the look that ends the wait is counted.
     */
    fun meanLook(diff: Float?) {
        if (diff == null || waitFrom == null) return
        lastMeanDiff = diff
        if (meanDiffs.size < MAX_LOOKS) meanDiffs.add(diff)
    }

    /** The ring filled but the camera could not take the photo then (it was busy, or finishing). */
    fun refused() = count("refused")

    /** The camera closes in [state] at [at]: a wait still open, then the summary. */
    fun close(state: ScanAutoState, at: Long): List<ScanLogLine> {
        val lines = ArrayList<ScanLogLine>()
        if (state.phase == ScanAutoPhase.WaitingForNewPage && waitFrom != null) lines.add("camera_auto_rearm" to waitEnded("closed", at))
        lines.add("camera_auto_summary" to summary(state.guards))
        return lines
    }

    private fun waitEnded(why: String, at: Long): Array<Pair<String, Any?>> {
        val sorted = diffs.sorted()
        fun grey(v: Float?): Double = if (v == null) -1.0 else Math.round(v * 10.0) / 10.0
        val fields = arrayOf<Pair<String, Any?>>(
            "why" to why, "wait_ms" to (at - (waitFrom ?: at)).coerceAtLeast(0L), "looks" to sorted.size,
            "diff_last" to grey(lastDiff), "diff_p50" to grey(sorted.getOrNull((sorted.size - 1) / 2)), "diff_max" to grey(sorted.lastOrNull()),
            "limit" to grey(ScanAutoRules.NEW_PAGE_GREY),
            // the averaged signature: measured beside the one in use, with no limit of its own yet
            "mean_looks" to meanDiffs.size, "mean_diff_last" to grey(lastMeanDiff),
            "mean_diff_p50" to grey(meanDiffs.sorted().getOrNull((meanDiffs.size - 1) / 2)), "mean_diff_max" to grey(meanDiffs.maxOrNull()),
        )
        waitFrom = null
        diffs.clear()
        lastDiff = null
        meanDiffs.clear()
        lastMeanDiff = null
        return fields
    }

    /** How often each thing happened, always the same fields in the same order. */
    fun summary(guards: Boolean): Array<Pair<String, Any?>> =
        arrayOf<Pair<String, Any?>>("guards" to guards) + KEYS.map { it to (counts[it] ?: 0) }

    companion object {
        /** The most comparisons kept for one wait: at ten a second, more than a minute. */
        const val MAX_LOOKS = 1_000

        /** The summary's counts: `ready`, each [ScanAutoWhy], `refused`, and the shutter by hand by the phase it found. */
        val KEYS = listOf(
            "ready", ScanAutoWhy.ARMED, ScanAutoWhy.LOST_PAGE, ScanAutoWhy.LOST_READY, ScanAutoWhy.LOST_STILL, ScanAutoWhy.FIRED, "refused",
            ScanAutoWhy.SAME_PAGE, ScanAutoWhy.TAKEN, ScanAutoWhy.FAILED, ScanAutoWhy.SKIPPED, ScanAutoWhy.ADOPTED, ScanAutoWhy.ABSENT, ScanAutoWhy.DIFFERS,
            ScanAutoWhy.SHUTTER, "shutter_searching", "shutter_arming", "shutter_waiting",
        )
    }
}

/**
 * How one photo came about (#781), for the `camera` line and a kept scan's page file: how the
 * capture started and how long it waited for its focus, how long the capture itself took, how long
 * after the photo before it, and where auto-capture was when the photo was asked for.
 */
internal data class ScanShotFacts(
    /** [ScanCaptureVia]'s id. */
    val via: String = "none",
    /** From the shutter to the capture starting; -1 when not known. */
    val focusWaitMs: Long = -1L,
    /** From the capture starting to its file written; -1 when not known. */
    val shotMs: Long = -1L,
    /** From the photo before it being written to this shutter; -1 for the first photo of a camera. */
    val sinceShotMs: Long = -1L,
    /** Auto-capture's phase when the photo was asked for (`searching`, `arming`, `waiting`); `off` without auto-capture. */
    val autoPhase: String = "off",
    /** Whether the page's signature was known when the photo was asked for. */
    val signatureKnown: Boolean = false,
    /** Whether the capture checks were on. */
    val guards: Boolean = false,
)

/**
 * The facts of a photo: [state] is the camera's when its file was written, at [endedAt]; the photo
 * before it was written at [lastEndedAt] (null for the first). [autoPhase] and [signatureKnown] are
 * of the moment the photo was asked for.
 */
internal fun scanShotFacts(state: ScanCameraState, endedAt: Long, lastEndedAt: Long?, autoPhase: String, signatureKnown: Boolean, guards: Boolean): ScanShotFacts {
    val known = state.via != ScanCaptureVia.None && state.captureAt > 0L
    return ScanShotFacts(
        via = state.via.id,
        focusWaitMs = if (known) state.focusWaitMs else -1L,
        shotMs = if (known) (endedAt - state.captureAt).coerceAtLeast(0L) else -1L,
        sinceShotMs = if (lastEndedAt != null && state.shutterAt > 0L) (state.shutterAt - lastEndedAt).coerceAtLeast(0L) else -1L,
        autoPhase = autoPhase, signatureKnown = signatureKnown, guards = guards,
    )
}
