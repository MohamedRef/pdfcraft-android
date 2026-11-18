package io.github.artcraftandroid.pdfcraft.ui.viewer

/**
 * Colour, width and opacity that one pen type remembers (#187). Each of the five pens keeps its
 * own values, so switching Ball → Marker → Ball never restyles either pen.
 *
 * Width is the base width before the style factor of [inkStyleWidthRuns]; opacity is the stored
 * value before [inkStyleOpacity] (so a marker at 100 % still draws at the marker's 40 %).
 */
internal data class InkPenSettings(val argb: Int, val width: Float, val opacity: Float) {
    companion object {
        const val MIN_WIDTH = 0.1f
        const val MAX_WIDTH = 20f
        const val MIN_OPACITY = 0.25f

        fun sanitized(argb: Int, width: Float, opacity: Float) = InkPenSettings(
            argb,
            width.takeIf { it.isFinite() }?.coerceIn(MIN_WIDTH, MAX_WIDTH) ?: MIN_WIDTH,
            opacity.takeIf { it.isFinite() }?.coerceIn(MIN_OPACITY, 1f) ?: 1f,
        )

        /** Defaults that make sense per pen: black ball, calligraphy pen and pencil, blue fountain, red brush, yellow wide marker. */
        fun default(style: InkStyle): InkPenSettings = when (style) {
            InkStyle.Ballpoint -> InkPenSettings(0xFF000000.toInt(), 1f, 1f)
            InkStyle.Fountain -> InkPenSettings(0xFF0000FF.toInt(), 1.5f, 1f)
            InkStyle.Calligraphy -> InkPenSettings(0xFF000000.toInt(), 3f, 1f)
            InkStyle.Pencil -> InkPenSettings(0xFF444444.toInt(), 1.5f, 1f)
            InkStyle.Brush -> InkPenSettings(0xFFE52237.toInt(), 3f, 1f)
            InkStyle.Highlighter -> InkPenSettings(0xFFFFD100.toInt(), 6f, 1f)
        }

        /**
         * What a pen whose colour was never changed writes with on dark paper (#610): the default
         * colours above are black, grey, blue and red, which do not show on a Dark notebook's
         * paper. Each of these has a contrast of at least 4.5 to 1 against it.
         */
        fun defaultOnDark(style: InkStyle): Int = when (style) {
            InkStyle.Ballpoint -> 0xFFFFFFFF.toInt()
            InkStyle.Fountain -> 0xFF8AB4FF.toInt()
            InkStyle.Calligraphy -> 0xFFFFFFFF.toInt()
            InkStyle.Pencil -> 0xFFD0D0D0.toInt()
            InkStyle.Brush -> 0xFFFF6B7A.toInt()
            InkStyle.Highlighter -> default(style).argb
        }

        fun colorKey(style: InkStyle) = "ink.${style.key}.color"
        fun widthKey(style: InkStyle) = "ink.${style.key}.width"
        fun opacityKey(style: InkStyle) = "ink.${style.key}.opacity"

        /** The single shared values used before #187. */
        const val LEGACY_COLOR = "ink.color"
        const val LEGACY_WIDTH = "ink.width"
        const val LEGACY_OPACITY = "ink.opacity"
        const val LEGACY_STYLE = "ink.style"
        val LEGACY_DEFAULT = InkPenSettings(0xFFE52237.toInt(), 1f, 1f)
    }
}

/**
 * The colour a pen writes with on this paper: its own, except that on dark paper a pen still at its
 * default colour writes with [InkPenSettings.defaultOnDark]. A colour the owner chose is never changed.
 */
internal fun inkColorOnPaper(argb: Int, style: InkStyle, darkPaper: Boolean): Int =
    if (darkPaper && argb == InkPenSettings.default(style).argb) InkPenSettings.defaultOnDark(style) else argb

/** The few typed reads that loading pen settings needs, so the migration is unit-testable. */
internal interface InkPrefsReader {
    fun contains(key: String): Boolean
    fun int(key: String, default: Int): Int
    fun float(key: String, default: Float): Float
    fun string(key: String): String?
}

/**
 * Loads every pen's settings. A pen with stored per-pen keys uses them. Otherwise, when the user
 * had the old shared values, the pen they last used inherits those values (so its look does not
 * change on update) and the other pens start from their defaults.
 */
internal fun loadInkPenSettings(read: InkPrefsReader): Map<InkStyle, InkPenSettings> {
    val hadLegacy = listOf(
        InkPenSettings.LEGACY_COLOR, InkPenSettings.LEGACY_WIDTH, InkPenSettings.LEGACY_OPACITY, InkPenSettings.LEGACY_STYLE,
    ).any { read.contains(it) }
    val lastUsed = InkStyle.fromKey(read.string(InkPenSettings.LEGACY_STYLE)) ?: InkStyle.Ballpoint
    return InkStyle.entries.associateWith { style ->
        val base = if (hadLegacy && style == lastUsed) {
            InkPenSettings(
                read.int(InkPenSettings.LEGACY_COLOR, InkPenSettings.LEGACY_DEFAULT.argb),
                read.float(InkPenSettings.LEGACY_WIDTH, InkPenSettings.LEGACY_DEFAULT.width),
                read.float(InkPenSettings.LEGACY_OPACITY, InkPenSettings.LEGACY_DEFAULT.opacity),
            )
        } else InkPenSettings.default(style)
        InkPenSettings.sanitized(
            read.int(InkPenSettings.colorKey(style), base.argb),
            read.float(InkPenSettings.widthKey(style), base.width),
            read.float(InkPenSettings.opacityKey(style), base.opacity),
        )
    }
}
