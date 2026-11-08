package io.github.artcraftandroid.pdfcraft.ui.viewer

/** PDF page points, not display pixels: size stays stable across zoom levels. */
internal const val ERASER_RADIUS_MIN = 2f
internal const val ERASER_RADIUS_MAX = 32f
internal const val ERASER_RADIUS_DEFAULT = 8f

/** Sanitize both corrupted stored preferences and user changes before native command dispatch. */
internal fun normalizedEraserRadius(value: Float): Float =
    if (value.isFinite()) value.coerceIn(ERASER_RADIUS_MIN, ERASER_RADIUS_MAX) else ERASER_RADIUS_DEFAULT
