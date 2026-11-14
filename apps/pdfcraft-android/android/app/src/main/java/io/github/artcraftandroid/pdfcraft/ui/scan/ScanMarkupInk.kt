package io.github.artcraftandroid.pdfcraft.ui.scan

/*
 * Markup's ink (#520), behind the testing option `markupInk`: the engine is asked to draw the page
 * only for the strokes that need it (the eraser's paper and edge fills, whose colours are the
 * engine's to find), the pen, the highlighter and the Color fill are drawn on the screen as the
 * engine will draw them, and a second eraser takes back the person's own strokes.
 */

/** Whether a stroke of [tool] needs the engine to be seen as it will be saved: the paper and edge fills. */
internal fun scanMarkNeedsEngine(tool: ScanMarkTool): Boolean = tool == ScanMarkTool.Erase || tool == ScanMarkTool.EraseEdge

/**
 * The strokes the engine draws the page with: with [ink], those up to the last that needs the
 * engine (a pen stroke before it stays under it, as it will be saved), the others drawn on the
 * screen above; without, all of them, as before.
 */
internal fun scanMarkupEngineStrokes(strokes: List<ScanStrokeMark>, ink: Boolean): List<ScanStrokeMark> {
    if (!ink) return strokes
    val last = strokes.indexOfLast { scanMarkNeedsEngine(it.tool) }
    return if (last < 0) emptyList() else strokes.subList(0, last + 1).toList()
}

/**
 * The strokes of [now] the screen draws over the engine's drawing made of [drawn]. With [ink],
 * those after the part the two share, in the same frame as any change; without, [scanMarkupPending].
 */
internal fun scanMarkupOnScreen(drawn: List<ScanStrokeMark>?, now: List<ScanStrokeMark>, ink: Boolean): List<ScanStrokeMark> {
    if (!ink) return scanMarkupPending(drawn, now)
    if (drawn == null) return emptyList()
    var same = 0
    while (same < drawn.size && same < now.size && drawn[same] == now[same]) same++
    return now.subList(same, now.size).toList()
}

/** Whether the second eraser can take back a stroke of [tool]: the person's own ink, not the paper fills. */
internal fun scanMarkRemovable(tool: ScanMarkTool): Boolean = tool == ScanMarkTool.Pen || tool == ScanMarkTool.Highlighter || tool == ScanMarkTool.EraseColour

/**
 * The strokes of [strokes] that the second eraser at ([x], [y]) (shares of the frame) with a
 * radius [radius] (a share of the longer side) touches. [aspect] is the frame's width over its
 * height, so distances are measured on the page and not in shares.
 */
internal fun scanStrokesTouched(strokes: List<ScanStrokeMark>, x: Float, y: Float, radius: Float, aspect: Float): Set<Int> {
    if (aspect <= 0f) return emptySet()
    // shares of the width and height in shares of the longer side
    val kx = if (aspect >= 1f) 1f else aspect
    val ky = if (aspect >= 1f) 1f / aspect else 1f
    val px = x * kx
    val py = y * ky
    val out = HashSet<Int>()
    for ((i, s) in strokes.withIndex()) {
        if (!scanMarkRemovable(s.tool)) continue
        val reach = s.width / 2f + radius
        val p = s.points
        val n = p.size / 3
        if (n == 0) continue
        if (n == 1) {
            if (hypot(p[0] * kx - px, p[1] * ky - py) <= reach) out += i
            continue
        }
        for (k in 1 until n) {
            if (segmentDistance(px, py, p[(k - 1) * 3] * kx, p[(k - 1) * 3 + 1] * ky, p[k * 3] * kx, p[k * 3 + 1] * ky) <= reach) {
                out += i
                break
            }
        }
    }
    return out
}

private fun hypot(dx: Float, dy: Float): Float = kotlin.math.sqrt(dx * dx + dy * dy)

private fun segmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = bx - ax
    val dy = by - ay
    val len = dx * dx + dy * dy
    val t = if (len <= 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / len).coerceIn(0f, 1f)
    return hypot(px - (ax + t * dx), py - (ay + t * dy))
}

/** The pen's width at [pressure]: from [SCAN_PEN_MIN_WIDTH] of [full] at none to [full] at full, as the engine draws it. */
internal fun scanPenWidth(full: Float, pressure: Float): Float = full * (SCAN_PEN_MIN_WIDTH + (1f - SCAN_PEN_MIN_WIDTH) * pressure.coerceIn(0f, 1f))

/** A pen at no pressure is this share of its full width: the engine's `PEN_MIN_WIDTH` in scan/src/markup.rs. */
internal const val SCAN_PEN_MIN_WIDTH = 0.35f

/**
 * The live stroke's samples (x, y, pressure …) in a growing array, so a move adds three numbers
 * and copies nothing. [version] changes with each sample, for the screen to draw again.
 */
internal class ScanInkPoints {
    var data = FloatArray(96)
        private set
    var size = 0
        private set

    val count: Int get() = size / 3

    fun add(x: Float, y: Float, pressure: Float) {
        if (size + 3 > data.size) data = data.copyOf(data.size * 2)
        data[size] = x
        data[size + 1] = y
        data[size + 2] = pressure
        size += 3
    }

    fun toArray(): FloatArray = data.copyOf(size)
}

/** What a Markup session's ink did, for `markup_done` (#520) and the kept page file. */
internal class ScanInkStats {
    var engineDraws = 0
    var engineMsMax = 0L
    var undoMsMax = 0L
    var removed = 0
    var framesOver16 = 0
    var strokes = 0
    var samples = 0

    fun diag(ink: Boolean): Array<Pair<String, Any?>> = arrayOf(
        "ink" to ink, "engine_draws" to engineDraws, "engine_ms_max" to engineMsMax, "undo_ms_max" to undoMsMax,
        "marks_removed" to removed, "frames_over_16ms" to framesOver16, "samples" to samples, "ink_strokes" to strokes,
    )
}

/** One stroke's line (#520): its tool, its samples, how long it took and its sample rate; whether a pen drew it. */
internal fun scanInkStrokeDiag(tool: String, samples: Int, ms: Long, stylus: Boolean, framesOver16: Int): Array<Pair<String, Any?>> = arrayOf(
    "tool" to tool, "samples" to samples, "ms" to ms,
    "rate_hz" to (if (ms > 0) Math.round(samples * 1000.0 / ms) else 0L),
    "stylus" to stylus, "path" to "normal", "frames_over_16ms" to framesOver16,
)
