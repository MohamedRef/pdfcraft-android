package io.github.artcraftandroid.pdfcraft.ui.viewer

import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea

/**
 * Diagnostics log lines for drawing and erasing (docs/ANDROID_DIAGNOSTICS_LOG.md). Each is a
 * summary at the end of a gesture, never a line per pointer event or sample.
 */

/**
 * One `gesture` line per touch on the page with a drawing tool (#74 #81 #84): what started it,
 * the role it ended with, whether a pen took it over, whether it moved past touch slop (a tap
 * when not), the most fingers down, and how many finger events and batched samples it had.
 */
internal class GestureDiag(
    private val startMs: Long,
    private val pointer: ToolPointer,
    private val startRole: GestureRole,
) {
    var penArrived = false
    var fingerEvents = 0
    var fingerSamples = 0

    fun log(route: GestureRoute, touches: Int, endMs: Long, cancelled: Boolean) {
        Diag.event(
            DiagArea.Ink, "gesture",
            "pointer" to pointer,
            "start_role" to startRole,
            "role" to route.role,
            "mode" to route.mode,
            "pen_arrived" to penArrived,
            "moved" to route.moved,
            "touches" to touches,
            "finger_events" to fingerEvents,
            "finger_samples" to fingerSamples,
            "ms" to (endMs - startMs).coerceAtLeast(0),
            "cancelled" to cancelled,
        )
    }
}

/** An eraser gesture ended (#76): its points, how many chunks went early, and the radius in points. */
internal fun logEraseGesture(points: Int, earlyChunks: Int, radiusPt: Float) {
    Diag.event(DiagArea.Ink, "erase", "points" to points, "early_chunks" to earlyChunks, "radius_pt" to radiusPt)
}

/**
 * A finished stroke was cut at its page edge (#75): its points before, the pieces kept and their
 * points. `pieces=0` means the whole stroke was off the page.
 */
internal fun logStrokeClip(pointsIn: Int, pieces: List<InkStroke>) {
    Diag.event(DiagArea.Ink, "stroke_clip", "points_in" to pointsIn, "pieces" to pieces.size, "points_out" to pieces.sumOf { it.points.size })
}

/** Time spent placing a lasso selection while it is dragged (#85), summed over one drag. */
internal class LassoDragDiag {
    private var moves = 0
    private var totalNs = 0L
    private var maxNs = 0L

    inline fun <T> time(block: () -> T): T {
        if (!Diag.enabled()) return block()
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            add(System.nanoTime() - start)
        }
    }

    fun add(ns: Long) {
        moves++
        totalNs += ns
        if (ns > maxNs) maxNs = ns
    }

    /** One `lasso_drag` line for the drag that just ended, then starts afresh. */
    fun end(strokes: Int, saved: Int, moved: Boolean) {
        if (moves > 0) {
            Diag.event(
                DiagArea.Ink, "lasso_drag",
                "moves" to moves,
                "total_us" to totalNs / 1_000,
                "max_us" to maxNs / 1_000,
                "strokes" to strokes,
                "saved" to saved,
                "moved" to moved,
            )
        }
        moves = 0
        totalNs = 0L
        maxNs = 0L
    }
}
