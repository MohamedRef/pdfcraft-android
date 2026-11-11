package io.github.artcraftandroid.pdfcraft.ui.viewer

import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.core.TileRect
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import kotlin.math.min
import kotlin.math.roundToInt

/** Where a page goes on a screen: its pixels per point, and its top-left corner. */
data class PageFit(val scale: Float, val left: Float, val top: Float)

/** Page [page] (points) as large as fits, centred, on a screen [width] by [height] pixels. */
fun fitPage(page: PageSize, width: Float, height: Float): PageFit? {
    if (page.width <= 0f || page.height <= 0f || width <= 0f || height <= 0f) return null
    val s = min(width / page.width, height / page.height)
    return PageFit(s, (width - page.width * s) / 2f, (height - page.height * s) / 2f)
}

/** The largest page image rendered for an external display, in pixels a side (4K's long side). */
const val EXTERNAL_MAX_SIDE = 3840f

/**
 * While presenting, with a second screen attached (HDMI, DeX, a cast screen), show the page and
 * the laser alone there (#157); the device keeps the controls and says what is on the screen.
 * Nothing happens when no such screen is attached.
 */
@Composable
fun BoxScope.ExternalPresentation(doc: OpenDocument) {
    val context = LocalContext.current
    val host = LocalView.current
    val displays = remember(context) { context.getSystemService(DisplayManager::class.java) }
    var display by remember { mutableStateOf<Display?>(null) }
    DisposableEffect(displays) {
        fun pick() {
            display = displays?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)?.firstOrNull()
        }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) = pick()
            override fun onDisplayRemoved(id: Int) = pick()
            override fun onDisplayChanged(id: Int) = Unit
        }
        pick()
        displays?.registerDisplayListener(listener, null)
        onDispose { displays?.unregisterDisplayListener(listener) }
    }
    val shown = display ?: return
    val current by rememberUpdatedState(doc)
    DisposableEffect(shown) {
        val screen = PagePresentation(context, shown, host) { ExternalPage(current) }
        // a screen that can't be shown on (gone again, or not allowed) leaves the device presenting alone
        val ok = runCatching { screen.show() }.isSuccess
        onDispose { if (ok) runCatching { screen.dismiss() } }
    }
    // the device says where the page is, and what comes next
    val next = (doc.viewer.currentPage + 2).takeIf { it <= doc.viewer.pages.size }
    Text(
        stringResource(R.string.present_on_display, shown.name ?: "") +
            (next?.let { "  ·  " + stringResource(R.string.present_next_page, it) } ?: ""),
        color = Color.White, fontSize = 13.sp,
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** The external screen: a window of its own showing [content], tied to the app's lifecycle. */
private class PagePresentation(
    context: Context,
    display: Display,
    private val host: android.view.View,
    private val content: @Composable () -> Unit,
) : Presentation(context, display) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = ComposeView(context).apply { setContent(content) }
        // Compose in a window of its own needs the activity's lifecycle and saved state
        window?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(host.findViewTreeLifecycleOwner())
            decor.setViewTreeSavedStateRegistryOwner(host.findViewTreeSavedStateRegistryOwner())
        }
        setContentView(view)
    }
}

/** The current page fitted to the external screen, on black, with the laser's trail over it. */
@Composable
private fun ExternalPage(doc: OpenDocument) {
    val viewer = doc.viewer
    val trail = doc.controller.laser
    val page = viewer.currentPage
    val size = viewer.pages.getOrNull(page)
    val dot = with(LocalDensity.current) { 9.dp.toPx() }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val fit = size?.let { fitPage(it, w, h) } ?: return@BoxWithConstraints
        // rendered for this screen, capped so a huge page or screen can't run out of memory
        val scale = min(fit.scale, EXTERNAL_MAX_SIDE / maxOf(size.width, size.height))
        val gen = doc.pageGenerations.of(page)
        val image by produceState<ImageBitmap?>(null, page, scale, gen) {
            value = doc.images.renderRegion(
                page, scale,
                TileRect(0u, 0u, (size.width * scale).roundToInt().coerceAtLeast(1).toUInt(), (size.height * scale).roundToInt().coerceAtLeast(1).toUInt()),
            ) ?: value
        }
        LaunchedEffect(trail, trail.points.isNotEmpty()) {
            while (trail.points.isNotEmpty()) {
                androidx.compose.runtime.withFrameMillis { }
                trail.fade(android.os.SystemClock.uptimeMillis())
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            image?.let {
                drawImage(
                    it, dstOffset = IntOffset(fit.left.roundToInt(), fit.top.roundToInt()),
                    dstSize = IntSize((size.width * fit.scale).roundToInt(), (size.height * fit.scale).roundToInt()),
                )
            }
            drawLaser(trail, dot) { p -> if (p.page == page) Offset(fit.left + p.x * fit.scale, fit.top + p.y * fit.scale) else null }
        }
    }
}
