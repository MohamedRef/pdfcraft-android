package io.github.artcraftandroid.pdfcraft.ui.scan

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.pdfColors

/** The least zoom: the whole page, fitted. */
private const val MIN_ZOOM: Float = 1f

/** The most zoom a pinch can reach (about four times the fitted page). */
private const val MAX_ZOOM: Float = 4f

/** The zoom a double tap goes to from the fitted page (a close look at the text). */
private const val DOUBLE_TAP_ZOOM: Float = 2.5f

/**
 * The page as it will be saved, fitted in the space the screen gives it (issue #301, spec S15 section 3).
 *
 * [placeholder] (the camera's photo) shows only until the first render; after that [image] stays on
 * screen while the next one is drawn. Press and hold shows [original] for as long as the finger is
 * down, if it is there yet. Double tap zooms to a close look or back to the fitted page; pinch
 * zooms between 1 and 4 times, and a zoomed page pans with one finger, never past its edges.
 */
@Composable
internal fun ScanPreviewPane(
    image: ImageBitmap?,
    placeholder: ImageBitmap,
    original: ImageBitmap?,
    modifier: Modifier,
) {
    val c = pdfColors
    var holding by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(MIN_ZOOM) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var paneSize by remember { mutableStateOf(IntSize.Zero) }
    val shown: ImageBitmap = if (holding && original != null) original else (image ?: placeholder)
    val description = stringResource(R.string.scan_preview_description)
    val holdHint = stringResource(R.string.scan_preview_hold_hint)
    Box(
        modifier
            .background(c.desk)
            .onSizeChanged { paneSize = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { _ ->
                        holding = true
                        try {
                            tryAwaitRelease()
                        } finally {
                            holding = false
                        }
                    },
                    onDoubleTap = { _ ->
                        zoom = if (zoom > MIN_ZOOM) MIN_ZOOM else DOUBLE_TAP_ZOOM
                        pan = Offset.Zero
                    },
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, panChange, zoomChange, _ ->
                    val next = (zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    zoom = next
                    pan = clampPan(pan + panChange, next, paneSize)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = shown,
            contentDescription = description,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom
                scaleY = zoom
                translationX = pan.x
                translationY = pan.y
            },
            contentScale = ContentScale.Fit,
        )
        if (original != null) {
            Text(
                holdHint,
                color = c.textSecondary,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
            )
        }
    }
}

/**
 * [pan] kept inside the page's edges at [zoom]: a page zoomed to twice its size can move half its
 * width to either side of centre, and no further. At zoom 1 the page cannot move at all.
 */
private fun clampPan(pan: Offset, zoom: Float, size: IntSize): Offset {
    val maxX = size.width * (zoom - 1f) / 2f
    val maxY = size.height * (zoom - 1f) / 2f
    return Offset(pan.x.coerceIn(-maxX, maxX), pan.y.coerceIn(-maxY, maxY))
}
