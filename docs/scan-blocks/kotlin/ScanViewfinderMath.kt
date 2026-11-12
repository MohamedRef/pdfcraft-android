package io.github.artcraftandroid.pdfcraft.ui.scan

// Maps a page outline from the analysed camera frame to positions on the screen (issue #295,
// S09 section 2). Pure arithmetic with no Android imports, so it runs on the JVM under test.
//
// A "share" is a coordinate as a fraction of the frame, 0 to 1, origin at the top left. Corners
// are four (x, y) pairs in the order top left, top right, bottom right, bottom left.

/** Rotation in quarter turns, 0 to 3, from degrees (any multiple of 90; other values round down). */
private fun quarterTurns(rotationDegrees: Int): Int {
    val normalised: Int = ((rotationDegrees % 360) + 360) % 360
    return normalised / 90
}

/**
 * Width over height of the frame once it is turned upright. A quarter turn of 90 or 270 degrees
 * swaps the two sides. Returns 1 for a frame of zero size, so the caller never divides by zero.
 */
fun uprightFrameAspect(frameWidth: Int, frameHeight: Int, rotationDegrees: Int): Float {
    if (frameWidth <= 0 || frameHeight <= 0) return 1f
    val swapped: Boolean = quarterTurns(rotationDegrees) % 2 == 1
    return if (swapped) {
        frameHeight.toFloat() / frameWidth.toFloat()
    } else {
        frameWidth.toFloat() / frameHeight.toFloat()
    }
}

/**
 * One share of the raw frame turned upright. The rotation is clockwise, as Android reports it in
 * ImageInfo.rotationDegrees: a sensor frame rotated by 90 has its top left corner at the top right.
 * Returns [x, y] as shares of the upright frame.
 */
fun uprightShare(shareX: Float, shareY: Float, rotationDegrees: Int): FloatArray {
    return when (quarterTurns(rotationDegrees)) {
        1 -> floatArrayOf(1f - shareY, shareX)
        2 -> floatArrayOf(1f - shareX, 1f - shareY)
        3 -> floatArrayOf(shareY, 1f - shareX)
        else -> floatArrayOf(shareX, shareY)
    }
}

/**
 * One share of the upright frame to a position in the view, in the view's pixels. [frameAspect]
 * is the upright frame's width over its height. With [fill] the frame covers the whole view and is
 * cropped at the edges (a centre crop, like the preview's FILL_CENTER); without it the whole frame
 * sits inside the view with bars at the sides or top and bottom (fit). With [mirrored] the frame is
 * flipped left to right first, as the front camera's preview is. Returns [x, y]; [0, 0] when the
 * view has no size.
 */
fun frameShareToView(
    shareX: Float,
    shareY: Float,
    frameAspect: Float,
    viewWidth: Float,
    viewHeight: Float,
    fill: Boolean,
    mirrored: Boolean,
): FloatArray {
    if (!(viewWidth > 0f && viewHeight > 0f)) return floatArrayOf(0f, 0f)
    val aspect: Float = if (frameAspect > 0f) frameAspect else 1f
    val x: Float = if (mirrored) 1f - shareX else shareX
    // The frame in units of its height: [aspect] wide and 1 high, scaled to fill or fit the view.
    val scale: Float = if (fill) {
        maxOf(viewWidth / aspect, viewHeight)
    } else {
        minOf(viewWidth / aspect, viewHeight)
    }
    val shownWidth: Float = aspect * scale
    val shownHeight: Float = scale
    val left: Float = (viewWidth - shownWidth) / 2f
    val top: Float = (viewHeight - shownHeight) / 2f
    return floatArrayOf(left + x * shownWidth, top + shareY * shownHeight)
}

/**
 * The four corners of a quad, given as shares of the raw analysed frame, to positions in the view.
 * [frameWidth] and [frameHeight] are the raw frame's size in pixels, [rotationDegrees] its rotation
 * (clockwise, to make it upright), and the other arguments as in [frameShareToView].
 * Returns the eight values (x, y pairs, same corner order). Returns an empty array when the input
 * cannot be used: fewer than eight values, or a frame with no size.
 */
fun frameToView(
    corners: FloatArray,
    frameWidth: Int,
    frameHeight: Int,
    rotationDegrees: Int,
    viewWidth: Float,
    viewHeight: Float,
    fill: Boolean,
    mirrored: Boolean,
): FloatArray {
    if (corners.size < 8 || frameWidth <= 0 || frameHeight <= 0) return FloatArray(0)
    val aspect: Float = uprightFrameAspect(frameWidth, frameHeight, rotationDegrees)
    val out = FloatArray(8)
    for (i in 0 until 4) {
        val upright: FloatArray = uprightShare(corners[2 * i], corners[2 * i + 1], rotationDegrees)
        val point: FloatArray = frameShareToView(upright[0], upright[1], aspect, viewWidth, viewHeight, fill, mirrored)
        out[2 * i] = point[0]
        out[2 * i + 1] = point[1]
    }
    return out
}
