package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** A point of the laser pointer's trail: on which page, where in its points, and when (uptime ms). */
data class LaserPoint(val page: Int, val x: Float, val y: Float, val time: Long)

/**
 * The laser pointer's trail while presenting, kept in page points so the device's screen and an
 * external display (#157) draw the same trail, each at its own size.
 */
class LaserTrail {
    val points = mutableStateListOf<LaserPoint>()

    /** The uptime (ms) the trail is drawn at. */
    var now by mutableLongStateOf(0L)
        private set

    fun add(p: LaserPoint) {
        points.add(p)
        now = p.time
    }

    /** Move the clock to [time] and drop what has faded. */
    fun fade(time: Long) {
        now = time
        points.removeAll { time - it.time > LASER_FADE_MS }
    }
}

/** How long a bit of the laser's trail takes to fade away. */
const val LASER_FADE_MS = 800L

/** How strongly a trail point [age] ms old still shows: 1 when new, 0 once faded. */
fun laserAlpha(age: Long): Float = (1f - age.toFloat() / LASER_FADE_MS).coerceIn(0f, 1f)

/** Presentation mode's page turn for a tap at [x] on a screen [width] wide: the outer thirds turn. */
fun presentationTap(x: Float, width: Float): Int = when {
    width <= 0f -> 0
    x < width / 3f -> -1
    x > width * 2f / 3f -> 1
    else -> 0
}

/**
 * Presentation mode over the page (#157): a tap on the left or right third turns the page, a
 * finger's drag is a laser pointer whose trail fades within [LASER_FADE_MS], and a pen writes
 * temporary ink that fades a few seconds after it lifts (#327). Nothing is written to the PDF.
 */
@Composable
fun BoxScope.PresentationLayer(viewer: ViewerState, trail: LaserTrail, ink: TempInk) {
    val dot = with(LocalDensity.current) { 7.dp.toPx() }
    val inkWidth = with(LocalDensity.current) { 3.dp.toPx() }
    // keep the trail and the ink fading while there is any
    LaunchedEffect(trail, ink, trail.points.isNotEmpty() || ink.strokes.isNotEmpty()) {
        while (trail.points.isNotEmpty() || ink.strokes.isNotEmpty()) {
            withFrameMillis { }
            val now = android.os.SystemClock.uptimeMillis()
            trail.fade(now)
            ink.fade(now)
        }
    }
    Canvas(
        Modifier.fillMaxSize().pointerInput(viewer, trail, ink) {
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                val down = awaitFirstDown()
                var laser = false
                // the page the drag started on: the trail stays on it, held to its edges
                val page = viewer.screenToPage(down.position, nearest = true)?.page ?: viewer.currentPage
                // a pen writes temporary ink from its first touch
                val pen = down.type == androidx.compose.ui.input.pointer.PointerType.Stylus
                val stroke = if (pen) TempStroke(page).also { s ->
                    viewer.screenToPageOf(page, down.position)?.let { s.points.add(Offset(it.x, it.y)) }
                    ink.strokes.add(s)
                    down.consume()
                } else null
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (stroke != null) {
                        if (!change.pressed) {
                            stroke.liftedAt = change.uptimeMillis
                            break
                        }
                        viewer.screenToPageOf(page, change.position)?.let { stroke.points.add(Offset(it.x, it.y)) }
                        change.consume()
                        continue
                    }
                    if (!change.pressed) {
                        if (!laser) presentationTap(change.position.x, size.width.toFloat()).takeIf { it != 0 }?.let(viewer::turnPage)
                        break
                    }
                    if (!laser && (change.position - down.position).getDistance() > slop) laser = true
                    if (laser) viewer.screenToPageOf(page, change.position)?.let { trail.add(LaserPoint(page, it.x, it.y, change.uptimeMillis)) }
                    change.consume()
                }
            }
        },
    ) {
        drawTempInk(ink, inkWidth) { page, p -> viewer.pageToScreen(page, p.x, p.y) }
        drawLaser(trail, dot) { p -> viewer.pageToScreen(p.page, p.x, p.y) }
    }
}

/** Draw [trail] fading, with [at] placing a point on this canvas (null: not shown here). */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLaser(trail: LaserTrail, dot: Float, at: (LaserPoint) -> Offset?) {
    val red = Color(0xFFFF2D2D)
    val now = trail.now
    val points = trail.points
    for (i in 1 until points.size) {
        val a = points[i - 1]
        val b = points[i]
        if (a.page != b.page) continue
        val alpha = laserAlpha(now - b.time)
        val pa = at(a) ?: continue
        val pb = at(b) ?: continue
        if (alpha > 0f) drawLine(red.copy(alpha = alpha * 0.6f), pa, pb, strokeWidth = dot, cap = StrokeCap.Round)
    }
    points.lastOrNull()?.let { p ->
        val o = at(p) ?: return@let
        val alpha = laserAlpha(now - p.time)
        drawCircle(red.copy(alpha = alpha * 0.35f), dot * 2.2f, o)
        drawCircle(red.copy(alpha = alpha), dot, o)
    }
}
