package io.github.artcraftandroid.pdfcraft.ui

/** A full-screen tool shown over the app. */
sealed class ToolScreen {
    class Compress(val doc: OpenDocument) : ToolScreen()
    class Combine(val doc: OpenDocument?) : ToolScreen()
    class Protect(val doc: OpenDocument) : ToolScreen()
    data object Create : ToolScreen()
    class Crop(val doc: OpenDocument) : ToolScreen()
    class Export(val doc: OpenDocument) : ToolScreen()
    data object Bates : ToolScreen()
    data object OcrBatch : ToolScreen()
    data object Scan : ToolScreen()
    class PageMarks(val doc: OpenDocument) : ToolScreen()
}
