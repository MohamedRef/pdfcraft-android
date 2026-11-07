package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.ui.graphics.Color
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument

/** Pending visual annotation, never persisted separately from the native PDF edit. */
data class PendingMarkupPreview(
    val doc: OpenDocument,
    val page: Int,
    val rects: List<FloatArray>,
    val markup: TextMarkup,
    val color: Color,
    val opacity: Float,
)
