package io.github.artcraftandroid.pdfcraft.ui.scan

import android.os.SystemClock
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import java.util.concurrent.atomic.AtomicInteger

/**
 * The analysis frames for the live outline (#295): about 640 × 480, RGBA, only the latest kept,
 * so a slow detection drops frames instead of holding the preview back.
 */
internal fun scanLiveAnalysis(): ImageAnalysis = ImageAnalysis.Builder()
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
    .setResolutionSelector(
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build(),
    )
    .build()

/**
 * Finds the page on each analysis frame with the native single-look detector, on the analysis
 * executor. The frame is copied (rows packed) and closed before the detector runs. One frame in
 * [SAMPLE] is written to the diagnostics log; the screen gets every answer through [onResult].
 */
internal class ScanLiveAnalyzer(private val onResult: (ScanLiveDetection) -> Unit) : ImageAnalysis.Analyzer {
    private var buffer = ByteArray(0)
    private val frames = AtomicInteger(0)
    private val found = AtomicInteger(0)
    private var totalMs = 0L
    private var maxMs = 0L

    override fun analyze(image: ImageProxy) {
        val w = image.width
        val h = image.height
        val rotation = image.imageInfo.rotationDegrees
        val plane = image.planes.firstOrNull()
        if (buffer.size != w * h * 4) buffer = ByteArray(w * h * 4)
        val copied = plane != null && scanCopyRgbaRows(plane.buffer, plane.rowStride, w, h, buffer)
        image.close()
        if (!copied) return
        val started = SystemClock.elapsedRealtime()
        val quad = runCatching { io.github.artcraftandroid.pdfcraft.core.scanDetectFrame(buffer, w.toUInt(), h.toUInt(), rotation.toUInt()) }.getOrNull() ?: return
        val ms = SystemClock.elapsedRealtime() - started
        val n = frames.incrementAndGet()
        if (quad.found) found.incrementAndGet()
        totalMs += ms
        if (ms > maxMs) maxMs = ms
        if (n % SAMPLE == 1) {
            Diag.event(
                DiagArea.Scan, "live_frame", "frame" to n, "ms" to ms, "native_ms" to quad.ms.toLong(), "width" to w, "height" to h, "rotation" to rotation,
                "found" to quad.found, "confidence" to quad.confidence.toDouble(), "luma" to quad.meanLuma.toDouble(), "cut_off" to quad.cutOff,
            )
        }
        onResult(ScanLiveDetection(quad.points.toFloatArray(), quad.confidence, quad.meanLuma, quad.found, quad.cutOff, SystemClock.elapsedRealtime()))
    }

    /** One line for the whole time the camera showed: frames looked at, how many found the page, the average and longest call. */
    fun logSummary(shownMs: Long) {
        val n = frames.get()
        if (n == 0) return
        Diag.event(DiagArea.Scan, "live_summary", "frames" to n, "found" to found.get(), "avg_ms" to totalMs / n, "max_ms" to maxMs, "shown_ms" to shownMs)
    }

    private companion object {
        const val SAMPLE = 10
    }
}

/** The outline over the viewfinder: filled lightly, outlined, corners marked; green once steady, faint while fading. */
@Composable
internal fun ScanLiveOverlay(outline: ScanLiveOutline?, accent: Color, modifier: Modifier = Modifier) {
    val points = outline?.points?.takeIf { it.size == 8 } ?: return
    val color = if (outline.stable) READY else accent
    val alpha = if (outline.fading) 0.4f else 1f
    Canvas(modifier) {
        val corners = (0 until 4).map { i ->
            val p = scanFrameShareToView(points[2 * i], points[2 * i + 1], 3f / 4f, size.width, size.height)
            Offset(p.x, p.y)
        }
        val path = Path().apply {
            moveTo(corners[0].x, corners[0].y)
            for (i in 1 until 4) lineTo(corners[i].x, corners[i].y)
            close()
        }
        drawPath(path, color.copy(alpha = 0.18f * alpha))
        drawPath(path, color.copy(alpha = alpha), style = Stroke(width = 3.dp.toPx()))
        for (c in corners) drawCircle(color.copy(alpha = alpha), radius = 5.dp.toPx(), center = c)
    }
}

/** The text of each hint. */
internal fun scanLiveHintText(hint: ScanLiveHint): Int = when (hint) {
    ScanLiveHint.PointAtDocument -> R.string.scan_live_point
    ScanLiveHint.MoreLight -> R.string.scan_live_light
    ScanLiveHint.FitPage -> R.string.scan_live_fit
    ScanLiveHint.MoveCloser -> R.string.scan_live_closer
    ScanLiveHint.HoldFlatter -> R.string.scan_live_flatter
    ScanLiveHint.HoldStill -> R.string.scan_live_still
    ScanLiveHint.Ready -> R.string.scan_live_ready
}

private val READY = Color(0xFF4CAF50)
