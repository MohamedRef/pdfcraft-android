package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.ui.geometry.Offset

/** What one finished draft stroke saves: plain data, so the command building is unit-testable. */
internal data class InkSaveStroke(
    val page: Int,
    val appearance: InkAppearance,
    val points: List<Offset>,
    val pressures: List<Float>,
    val pressureSensitive: Boolean,
)

/** PDF annotations stay bounded: a longer pressure drawing is split into further annotations. */
internal const val MAX_PRESSURE_INK_POINTS = 4000

/**
 * The `comment_add` argument maps for [strokes], in paint order, grouped by stroke: entry `i`
 * holds the commands of `strokes[i]`. Every stroke is its **own** Ink annotation (#72), so each
 * stroke is one step of the document's undo history whenever it is saved (Done, the eraser, the
 * app going to the background), as it was one step of the Draw tool's own undo before. A pen
 * stroke with pressure is saved as one annotation with a width per point (`widths`), split only
 * past [MAX_PRESSURE_INK_POINTS]; constant-width strokes are unchanged. [hex] formats colours.
 */
internal fun inkSaveCommandGroups(strokes: List<InkSaveStroke>, hex: (Int) -> String): List<List<Map<String, Any?>>> =
    strokes.map { inkSaveCommands(listOf(it), hex) }

/** The `comment_add` argument maps of the strokes of one group (see [inkSaveCommandGroups]). */
internal fun inkSaveCommands(strokes: List<InkSaveStroke>, hex: (Int) -> String): List<Map<String, Any?>> {
    data class Piece(val stroke: InkSaveStroke, val points: List<Offset>, val widths: List<Float>?, val width: Float)
    val pieces = strokes.flatMap { s ->
        val pressure = s.pressureSensitive && s.appearance.style != InkStyle.Highlighter && s.points.size >= 2 &&
            s.pressures.size == s.points.size
        if (pressure) {
            val widths = inkStylePointWidths(s.pressures, s.appearance.width, s.appearance.style)
            s.points.indices.chunked(MAX_PRESSURE_INK_POINTS - 1).map { idx ->
                // Neighbouring pieces share their boundary point, so the line stays continuous.
                val range = idx.first()..minOf(idx.last() + 1, s.points.lastIndex)
                Piece(s, s.points.slice(range), widths.slice(range), 0f)
            }.filter { it.points.size >= 2 }
        } else {
            inkStyleWidthRuns(s.pressures, s.appearance.width, s.pressureSensitive, s.appearance.style).map { run ->
                Piece(s, s.points.subList(run.start, run.end + 1), null, run.width)
            }
        }
    }
    val batches = consecutiveInkBatches(pieces) { p ->
        Triple(p.stroke.page, p.stroke.appearance.copy(width = if (p.widths != null) 0f else p.width), p.widths != null)
    }
    return batches.map { list ->
        val first = list.first()
        val a = first.stroke.appearance
        val command = linkedMapOf<String, Any?>(
            "page" to first.stroke.page + 1, "type" to inkStyleEngineType(a.style),
            "strokes" to list.map { piece -> piece.points.map { listOf(it.x.toDouble(), it.y.toDouble()) } },
            "color" to hex(a.argb), "opacity" to a.opacity.toDouble(),
            "width" to (if (first.widths != null) a.width else first.width).toDouble(),
            "author" to a.author,
        )
        if (first.widths != null) command["widths"] = list.map { piece -> piece.widths!!.map { it.toDouble() } }
        command
    }
}
