package io.github.artcraftandroid.pdfcraft.ui.scan

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import io.github.artcraftandroid.pdfcraft.ui.pdfColors

/** The outline's colour once the page is steady: a green in both themes (the app has no success colour). */
private val StableGreen: Color = Color(0xFF2D9D78)

/** Opacity of the filled page area under the outline (S09 section 5: 18%). */
private const val FILL_ALPHA: Float = 0.18f

/** Stroke width of the outline while the page is moving, in dp. */
private const val MOVING_STROKE_DP: Float = 2f

/** Stroke width of the outline once the page is steady, in dp (S09 section 5: 3 dp). */
private const val STABLE_STROKE_DP: Float = 3f

/** Radius of the dot at each corner, in dp. */
private const val CORNER_DOT_DP: Float = 4f

/**
 * Draws the live page outline over the camera preview (issue #295, S09 section 5). [outline] holds
 * its corners as shares of the upright analysed frame. [frameAspect], [fill] and [mirrored] say how
 * the preview is shown, so the corners land on the page: the same values as the mapping in
 * ScanViewfinderMath. Draws nothing when there is no page. The outline is accent-coloured, and
 * green and thicker once the page is steady.
 */
@Composable
internal fun ScanOutlineOverlay(
    outline: ScanOutline,
    frameAspect: Float,
    fill: Boolean,
    mirrored: Boolean,
    modifier: Modifier = Modifier,
) {
    val corners: FloatArray = outline.corners ?: return
    val c = pdfColors
    val colour: Color = if (outline.stable) StableGreen else c.accent
    val strokeDp: Float = if (outline.stable) STABLE_STROKE_DP else MOVING_STROKE_DP
    Canvas(modifier) {
        val points: List<Offset> = (0 until 4).map { i ->
            val p: FloatArray = frameShareToView(corners[2 * i], corners[2 * i + 1], frameAspect, size.width, size.height, fill, mirrored)
            Offset(p[0], p[1])
        }
        val path = Path()
        path.moveTo(points[0].x, points[0].y)
        for (i in 1 until 4) path.lineTo(points[i].x, points[i].y)
        path.close()
        drawPath(path, colour, alpha = FILL_ALPHA * outline.alpha)
        drawPath(path, colour, alpha = outline.alpha, style = Stroke(width = strokeDp.dp.toPx()))
        for (p in points) {
            drawCircle(colour, CORNER_DOT_DP.dp.toPx(), p, alpha = outline.alpha)
        }
    }
}
