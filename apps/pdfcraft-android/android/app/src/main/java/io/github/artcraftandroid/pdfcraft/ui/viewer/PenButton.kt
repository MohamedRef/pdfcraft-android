package io.github.artcraftandroid.pdfcraft.ui.viewer

/** What holding the S Pen side button while touching the page does (#144). */
enum class PenButtonHold(val key: String) {
    Eraser("eraser"),
    Highlighter("highlighter"),
    None("none");

    companion object {
        fun fromKey(v: String?) = entries.firstOrNull { it.key == v } ?: Eraser
    }
}

/** What a side-button click while hovering (no stroke) does (#144). */
internal enum class PenButtonClick(val key: String) {
    None("none"),
    Undo("undo"),
    TogglePens("toggle");

    companion object {
        fun fromKey(v: String?) = entries.firstOrNull { it.key == v } ?: None
    }
}

/** How a pen-down starts, decided once at contact: a later button press never changes it. */
enum class PenDownMode { Ink, Erase, Highlight }

/**
 * The mode of a gesture that starts with [eraserTip] (the hardware eraser end, which always
 * erases) or a stylus with the side button [buttonHeld] under [hold].
 */
internal fun penDownMode(eraserTip: Boolean, buttonHeld: Boolean, hold: PenButtonHold): PenDownMode = when {
    eraserTip -> PenDownMode.Erase
    !buttonHeld -> PenDownMode.Ink
    hold == PenButtonHold.Eraser -> PenDownMode.Erase
    hold == PenButtonHold.Highlighter -> PenDownMode.Highlight
    else -> PenDownMode.Ink
}

/**
 * Recognises a side-button click while the pen hovers: the button goes down and up again within
 * [maxMs] without the tip touching the screen in between.
 */
internal class PenButtonClickDetector(private val maxMs: Long = 600L) {
    private var downAt: Long? = null

    /** A hover sample; true exactly when it completes a click. */
    fun hover(buttonDown: Boolean, timeMs: Long): Boolean {
        val started = downAt
        return when {
            buttonDown && started == null -> { downAt = timeMs; false }
            !buttonDown && started != null -> { downAt = null; timeMs - started in 0..maxMs }
            else -> false
        }
    }

    /** The tip touched the screen or the pen left: a pending press is not a click. */
    fun reset() {
        downAt = null
    }
}
