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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer

/** One wet-ink segment in screen pixels. */
internal data class WetSegment(val from: Offset, val to: Offset, val width: Float, val argb: Int, val inputAt: Long = 0L)

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

/**
 * The parts of the screen the app draws over the page: bars, the tool rail, the zoom box's pad.
 * The wet-ink surface sits on top of the whole window (#128), so Compose cannot cover it; it
 * leaves these areas out itself, or ink written near a bar shows over its buttons.
 */
internal class WetInkCovers {
    private val rects = HashMap<Any, Rect>()

    /** The covering areas in window pixels, read by the render thread. */
    @Volatile var snapshot: List<Rect> = emptyList()
        private set

    /** Where the wet-ink surface sits in the window. */
    @Volatile var origin: Offset = Offset.Zero

    /** [key]'s area in window pixels, or null when it is gone. Main thread. */
    fun set(key: Any, area: Rect?) {
        val changed = if (area == null || area.isEmpty) rects.remove(key) != null else rects.put(key, area) != area
        if (changed) snapshot = rects.values.toList()
    }

    /** The covering areas in the surface's own pixels. */
    fun inSurface(): List<Rect> = snapshot.map { it.translate(-origin) }
}

/** Marks this element as drawn over the page, so wet ink stays out of it (#128). */
@Composable
internal fun Modifier.coversWetInk(covers: WetInkCovers): Modifier {
    val key = remember { Any() }
    DisposableEffect(covers, key) { onDispose { covers.set(key, null) } }
    return this.onGloballyPositioned { covers.set(key, it.boundsInWindow()) }
}

/** Whether a stroke can use wet ink: opaque, non-marker ink only (translucent ink would double up). */
internal fun wetInkEligible(style: InkStyle, opacity: Float): Boolean = style != InkStyle.Highlighter && opacity >= 0.999f

@Composable
internal fun FrontBufferedInkOverlay(wet: WetInk, covers: WetInkCovers, modifier: Modifier = Modifier) {
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
                            canvas.save()
                            // bars and pads over the page keep their look: no ink over them
                            for (r in covers.inSurface()) canvas.clipOutRect(r.left, r.top, r.right, r.bottom)
                            canvas.drawLine(param.from.x, param.from.y, param.to.x, param.to.y, paint)
                            canvas.restore()
                            if (param.inputAt > 0L) InkLatency.lowLatency.add(android.os.SystemClock.uptimeMillis() - param.inputAt)
                        }

                        override fun onDrawMultiBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, params: Collection<WetSegment>) {
                            // Committed ink is drawn by Compose; the multi-buffered layer stays empty.
                            canvas.drawColor(AndroidColor.TRANSPARENT, BlendMode.CLEAR)
                        }
                    }),
                )
            }
        },
        modifier = modifier.onGloballyPositioned { covers.origin = it.positionInWindow() },
    )
    DisposableEffect(wet) { onDispose { wet.attach(null) } }
}
