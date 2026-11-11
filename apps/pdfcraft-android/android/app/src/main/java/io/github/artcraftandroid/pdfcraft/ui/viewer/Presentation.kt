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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** A point of the laser pointer's trail: where, and when (uptime ms). */
data class LaserPoint(val at: Offset, val time: Long)

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
 * Presentation mode over the page (#157): a tap on the left or right third turns the page, and a
 * drag with a finger or pen is a laser pointer whose trail fades within [LASER_FADE_MS]. Nothing
 * is written to the PDF.
 */
@Composable
fun BoxScope.PresentationLayer(viewer: ViewerState) {
    val trail = remember { mutableStateListOf<LaserPoint>() }
    var now by remember { mutableLongStateOf(0L) }
    val dot = with(LocalDensity.current) { 7.dp.toPx() }
    // keep the trail fading while there is one
    LaunchedEffect(trail.isNotEmpty()) {
        while (trail.isNotEmpty()) {
            withFrameMillis { }
            now = android.os.SystemClock.uptimeMillis()
            trail.removeAll { now - it.time > LASER_FADE_MS }
        }
    }
    Canvas(
        Modifier.fillMaxSize().pointerInput(viewer) {
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                val down = awaitFirstDown()
                var laser = false
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        if (!laser) presentationTap(change.position.x, size.width.toFloat()).takeIf { it != 0 }?.let(viewer::turnPage)
                        break
                    }
                    if (!laser && (change.position - down.position).getDistance() > slop) laser = true
                    if (laser) {
                        trail.add(LaserPoint(change.position, change.uptimeMillis))
                        now = change.uptimeMillis
                    }
                    change.consume()
                }
            }
        },
    ) {
        val red = Color(0xFFFF2D2D)
        for (i in 1 until trail.size) {
            val a = trail[i - 1]
            val b = trail[i]
            val alpha = laserAlpha(now - b.time)
            if (alpha > 0f) drawLine(red.copy(alpha = alpha * 0.6f), a.at, b.at, strokeWidth = dot, cap = StrokeCap.Round)
        }
        trail.lastOrNull()?.let { p ->
            val alpha = laserAlpha(now - p.time)
            drawCircle(red.copy(alpha = alpha * 0.35f), dot * 2.2f, p.at)
            drawCircle(red.copy(alpha = alpha), dot, p.at)
        }
    }
}
