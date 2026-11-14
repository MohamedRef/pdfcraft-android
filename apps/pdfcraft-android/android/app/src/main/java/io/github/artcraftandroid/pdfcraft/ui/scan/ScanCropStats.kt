package io.github.artcraftandroid.pdfcraft.ui.scan

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt
import org.json.JSONObject

/**
 * What happened on the crop screen for one page (#292), for the diagnostics log and a kept scan's
 * page file: counts and times only. A page cropped more than once adds the visits up ([plus]).
 */
data class ScanCropStats(
    /** How many times the screen was left with Done. */
    val visits: Int = 0,
    val cornerDrags: Int = 0,
    val sideDrags: Int = 0,
    /** Releases that asked the edge finder to snap. */
    val snapsAsked: Int = 0,
    /** Of those, how many it moved onto an edge. */
    val snapsTaken: Int = 0,
    /** Releases after holding still, so not snapped. */
    val snapsHeld: Int = 0,
    /** Snaps that failed in the native call. */
    val snapsFailed: Int = 0,
    /** The longest snap, in milliseconds. */
    val snapMsMax: Long = 0,
    /** Keyboard and accessibility moves. */
    val nudges: Int = 0,
    val detects: Int = 0,
    /** Of those, how many found a page. */
    val detectsFound: Int = 0,
    val wholePhoto: Int = 0,
    val resets: Int = 0,
    /** Regions of the full photo decoded for the magnifier. */
    val loupeDecodes: Int = 0,
    /** Whether Snap was on when the screen was left. */
    val snapOn: Boolean = true,
    /** Time on the screen, in milliseconds. */
    val ms: Long = 0,
    /** The largest corner move from where the screen opened, in % of the photo's diagonal. */
    val movedPct: Double = 0.0,
    /** Corners off the photo when the screen was last left with Done (#291), 0 to 4. */
    val cornersOff: Int = 0,
    /** Auto-detect presses that searched with the line detector because the first search had found nothing (#443). */
    val detectsOther: Int = 0,
    /** Auto-detect presses that did not search, because every search had found nothing on the photo (#443). */
    val detectsSkipped: Int = 0,
    /** Handles pulled onto an edge while dragged (#514). */
    val catches: Int = 0,
    /** Of those, how many the finger pulled free again past the release distance. */
    val pulledFree: Int = 0,
    /** Drags that tried a shape that can't be a page. */
    val badShapes: Int = 0,
    /** For the top, right, bottom and left sides: the side ended on an edge found in the photo (by a pull or a snap); null when not known. */
    val sidesOnEdge: List<Boolean>? = null,
    /** Visits made in a pass over several pages (#515). */
    val batchVisits: Int = 0,
) {
    operator fun plus(o: ScanCropStats) = ScanCropStats(
        visits + o.visits, cornerDrags + o.cornerDrags, sideDrags + o.sideDrags, snapsAsked + o.snapsAsked,
        snapsTaken + o.snapsTaken, snapsHeld + o.snapsHeld, snapsFailed + o.snapsFailed, max(snapMsMax, o.snapMsMax),
        nudges + o.nudges, detects + o.detects, detectsFound + o.detectsFound, wholePhoto + o.wholePhoto, resets + o.resets,
        loupeDecodes + o.loupeDecodes, o.snapOn, ms + o.ms, max(movedPct, o.movedPct),
        if (o.visits > 0) o.cornersOff else cornersOff, detectsOther + o.detectsOther, detectsSkipped + o.detectsSkipped,
        catches + o.catches, pulledFree + o.pulledFree, badShapes + o.badShapes, if (o.visits > 0) o.sidesOnEdge ?: sidesOnEdge else sidesOnEdge,
        batchVisits + o.batchVisits,
    )

    /** As diagnostics fields: numbers and booleans only. */
    fun diag(): Array<Pair<String, Any?>> = arrayOf(
        "corner_drags" to cornerDrags, "side_drags" to sideDrags, "snaps_asked" to snapsAsked, "snaps_taken" to snapsTaken,
        "snaps_held" to snapsHeld, "snaps_failed" to snapsFailed, "snap_ms_max" to snapMsMax, "nudges" to nudges,
        "detects" to detects, "detects_found" to detectsFound, "detects_other" to detectsOther,
        "detects_skipped" to detectsSkipped, "whole_photo" to wholePhoto, "resets" to resets,
        "loupe_decodes" to loupeDecodes, "snap" to snapOn, "ms" to ms, "moved_pct" to round3(movedPct),
        "corners_off" to cornersOff, "catches" to catches, "pulled_free" to pulledFree, "bad_shapes" to badShapes,
        "sides_on_edge" to (sidesOnEdge?.count { it } ?: -1), "batch_visits" to batchVisits,
    )

    /** As JSON, for a kept scan's page file. */
    fun json(): JSONObject = JSONObject().apply { diag().forEach { (k, v) -> put(k, v) } }.put("visits", visits)
        // #514: each side, top, right, bottom, left
        .put("sides_on_edge", sidesOnEdge?.let { org.json.JSONArray(it) } ?: JSONObject.NULL)

    private fun round3(v: Double) = if (v.isFinite()) Math.round(v * 1000.0) / 1000.0 else 0.0
}

/** How many of the corners [q] (shares) lie off the photo. */
internal fun cornersOffPhoto(q: FloatArray): Int =
    (0 until q.size / 2).count { i -> q[2 * i] !in 0f..1f || q[2 * i + 1] !in 0f..1f }

/**
 * The largest distance between a corner of [a] and the same corner of [b], in % of the photo's
 * diagonal, for a photo [aspect] (width over height) wide.
 */
internal fun maxCornerMovePct(a: FloatArray, b: FloatArray, aspect: Float): Double {
    if (a.size != 8 || b.size != 8) return 0.0
    val diagonal = sqrt(aspect.toDouble() * aspect + 1.0)
    return (0..3).maxOf { i -> hypot((a[2 * i] - b[2 * i]).toDouble() * aspect, (a[2 * i + 1] - b[2 * i + 1]).toDouble()) } / diagonal * 100.0
}

/**
 * The page number the crop screen's log lines (`crop`, `crop_snap`, `crop_detect`) carry for the
 * page at list position [index] (#445): from 1, like every other scan line.
 */
internal fun scanLogPageNumber(index: Int): Int = index + 1
