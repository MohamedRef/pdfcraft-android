package io.github.artcraftandroid.pdfcraft.ui.viewer

/**
 * View settings ▸ Reading colours: the engine remaps text and line art to these colours and
 * clears the page to the background, leaving photos as they are (unlike Night mode, which inverts
 * the whole page). A view setting: the file never changes.
 */
enum class ReadingColors(val engineMode: String, val text: Long, val background: Long) {
    Off("off", 0xFF000000, 0xFFFFFFFF),
    BlackOnWhite("black-on-white", 0xFF000000, 0xFFFFFFFF),
    WhiteOnBlack("white-on-black", 0xFFFFFFFF, 0xFF000000),
    YellowOnBlack("yellow-on-black", 0xFFFFFF00, 0xFF000000),
    GreenOnBlack("green-on-black", 0xFF00FF00, 0xFF000000),
    ;

    companion object {
        fun fromPref(value: String?): ReadingColors = entries.firstOrNull { it.engineMode == value } ?: Off
    }
}
