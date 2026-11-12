package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/** Temporary ink stays whole this long after the pen lifts (#327)… */
const val TEMP_INK_HOLD_MS = 2400L

/** …and is gone by this long after. */
const val TEMP_INK_GONE_MS = 3000L

/** How strongly a temporary stroke shows [sinceLift] ms after the pen lifted (null: still drawing). */
fun tempInkAlpha(sinceLift: Long?): Float = when {
    sinceLift == null || sinceLift <= TEMP_INK_HOLD_MS -> 1f
    else -> (1f - (sinceLift - TEMP_INK_HOLD_MS).toFloat() / (TEMP_INK_GONE_MS - TEMP_INK_HOLD_MS)).coerceIn(0f, 1f)
}

/** One temporary stroke on [page], its points in the page's points. */
class TempStroke(val page: Int) {
    val points = mutableStateListOf<Offset>()

    /** Uptime (ms) the pen lifted, or null while drawing. */
    var liftedAt by mutableStateOf<Long?>(null)
}

/**
 * Ink that is drawn while presenting and fades away on its own a few seconds later (#327). It
 * is never written to the PDF, and an external display draws the same strokes.
 */
class TempInk {
    val strokes = mutableStateListOf<TempStroke>()

    var now by mutableLongStateOf(0L)
        private set

    /** Move the clock to [time] and drop the strokes that have faded. */
    fun fade(time: Long) {
        now = time
        strokes.removeAll { s -> s.liftedAt?.let { time - it >= TEMP_INK_GONE_MS } == true }
    }
}

/** Draw [ink]'s strokes, [width] pixels wide, with [at] placing a page point on this canvas (null: not here). */
fun DrawScope.drawTempInk(ink: TempInk, width: Float, at: (page: Int, Offset) -> Offset?) {
    val colour = Color(0xFFFF2D2D)
    for (s in ink.strokes) {
        val alpha = tempInkAlpha(s.liftedAt?.let { ink.now - it })
        if (alpha <= 0f || s.points.isEmpty()) continue
        val path = Path()
        var started = false
        for (p in s.points) {
            val o = at(s.page, p) ?: continue
            if (started) path.lineTo(o.x, o.y) else path.moveTo(o.x, o.y).also { started = true }
        }
        if (!started) continue
        if (s.points.size == 1) {
            at(s.page, s.points[0])?.let { drawCircle(colour.copy(alpha = alpha), width / 2f, it) }
        } else {
            drawPath(path, colour.copy(alpha = alpha), style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
