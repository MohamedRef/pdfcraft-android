package io.github.artcraftandroid.pdfcraft.ui.viewer

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.PixelFormat
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer

/** One wet-ink segment in screen pixels. */
internal data class WetSegment(val from: Offset, val to: Offset, val width: Float, val argb: Int)

/**
 * Low-latency wet ink (#128): the segment being written is drawn straight into the display's
 * front buffer, so it shows before the next Compose frame. Compose still draws (and saves) the
 * stroke; the front buffer is cleared when the stroke ends. Used only when the setting is on.
 */
internal class WetInk {
    private var renderer: CanvasFrontBufferedRenderer<WetSegment>? = null

    internal fun attach(r: CanvasFrontBufferedRenderer<WetSegment>?) {
        renderer?.release(true)
        renderer = r
    }

    fun segment(s: WetSegment) {
        if (s.width.isFinite() && s.width > 0f) renderer?.renderFrontBufferedLayer(s)
    }

    /** The stroke ended or was cancelled: Compose has it now, so the front buffer is wiped. */
    fun clear() {
        renderer?.cancel()
        renderer?.clear()
    }
}

/** Whether a stroke can use wet ink: opaque, non-marker ink only (translucent ink would double up). */
internal fun wetInkEligible(style: InkStyle, opacity: Float): Boolean = style != InkStyle.Highlighter && opacity >= 0.999f

@Composable
internal fun FrontBufferedInkOverlay(wet: WetInk, modifier: Modifier = Modifier) {
    val paint = remember {
        Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    }
    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                setZOrderOnTop(true)
                holder.setFormat(PixelFormat.TRANSLUCENT)
                isClickable = false
                isFocusable = false
                wet.attach(
                    CanvasFrontBufferedRenderer(this, object : CanvasFrontBufferedRenderer.Callback<WetSegment> {
                        override fun onDrawFrontBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, param: WetSegment) {
                            paint.color = param.argb
                            paint.strokeWidth = param.width
                            canvas.drawLine(param.from.x, param.from.y, param.to.x, param.to.y, paint)
                        }

                        override fun onDrawMultiBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, params: Collection<WetSegment>) {
                            // Committed ink is drawn by Compose; the multi-buffered layer stays empty.
                            canvas.drawColor(AndroidColor.TRANSPARENT, BlendMode.CLEAR)
                        }
                    }),
                )
            }
        },
        modifier = modifier,
    )
    DisposableEffect(wet) { onDispose { wet.attach(null) } }
}
