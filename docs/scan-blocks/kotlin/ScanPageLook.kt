package io.github.artcraftandroid.pdfcraft.ui.scan

/**
 * The looks a page can have, in the order the strip shows them (#301, spec S15 section 1).
 * [id] is the name saved in a session and written to the log; [defaultStrength] is the strength
 * a look starts with, 0 to 100; [hasStrength] is false for Original, which has no strength.
 */
enum class ScanLookKind(val id: String, val defaultStrength: Int, val hasStrength: Boolean) {
    Original("original", 0, false),
    Auto("auto", 60, true),
    Colour("colour", 50, true),
    Greyscale("greyscale", 40, true),
    BlackWhite("bw", 75, true),
    Whiteboard("whiteboard", 85, true);

    companion object {
        /** The look with this [id], or null when there is none (missing, or a look of a later build). */
        fun of(id: String?): ScanLookKind? = ScanLookKind.entries.firstOrNull { it.id == id }
    }
}

/**
 * Whether a page is flattened (#302, spec S16): [Auto] lets the app decide, [On] and [Off] are
 * the person's choice. [id] is the name saved in a session and written to the log.
 */
enum class ScanFlattenMode(val id: String) {
    Auto("auto"),
    On("on"),
    Off("off");

    companion object {
        /** The mode with this [id], or null when there is none. */
        fun of(id: String?): ScanFlattenMode? = ScanFlattenMode.entries.firstOrNull { it.id == id }
    }
}

/** The highest and lowest strength: the slider's range (#301). */
private const val STRENGTH_MAX: Int = 100
private const val STRENGTH_MIN: Int = 0

/** A strength kept to the slider's range, so that no saved or set value leaves it. */
private fun clampStrength(value: Int): Int = value.coerceIn(STRENGTH_MIN, STRENGTH_MAX)

/** A flag as the session text writes it: "1" or "0". */
private fun flagText(value: Boolean): String = if (value) "1" else "0"

/**
 * One page's look settings (#301, spec S15 section 1). A page keeps these in the scan session
 * and sends them to the preview and to the save.
 *
 * [strength] is 0 to 100. [keepPaper] keeps the colour of the paper instead of making it white.
 * [userTurns] counts the presses of Rotate, clockwise, and may be any integer; [keepUpright]
 * is set once the person has turned the page, so the app must not turn it again (#339).
 */
data class ScanPageLook(
    val look: ScanLookKind = ScanLookKind.Auto,
    val strength: Int = ScanLookKind.Auto.defaultStrength,
    val keepPaper: Boolean = false,
    val flatten: ScanFlattenMode = ScanFlattenMode.Off,
    val userTurns: Int = 0,
    val keepUpright: Boolean = false,
) {
    /** The same settings with [look], which takes its own default strength. */
    fun with(look: ScanLookKind): ScanPageLook = copy(look = look, strength = look.defaultStrength)

    /** The same settings with [value] as the strength, kept to 0 to 100. */
    fun withStrength(value: Int): ScanPageLook = copy(strength = clampStrength(value))

    /**
     * The same settings turned one quarter. The turns wrap around an Int like any counter, and
     * four quarter turns still make a full turn, because 2^32 is a multiple of four.
     */
    fun rotated(clockwise: Boolean): ScanPageLook =
        copy(userTurns = if (clockwise) userTurns + 1 else userTurns - 1, keepUpright = true)

    /**
     * These settings as one line, "key=value" pairs joined by ';' (#301). The values are the
     * look and flatten names, numbers and 1 or 0, so the line never holds a tab or a newline.
     */
    fun encode(): String =
        "look=" + look.id +
            ";strength=" + clampStrength(strength) +
            ";keep=" + flagText(keepPaper) +
            ";flatten=" + flatten.id +
            ";turns=" + userTurns +
            ";upright=" + flagText(keepUpright)

    companion object {
        /**
         * The settings in [text]. Anything missing or not understood is the default: an unknown
         * look is Auto, a missing strength is that look's default, a strength outside 0 to 100
         * is clamped, and a later build's keys are passed over.
         */
        fun decode(text: String?): ScanPageLook {
            val fields = mutableMapOf<String, String>()
            for (part in text.orEmpty().split(';')) {
                val at = part.indexOf('=')
                if (at <= 0) continue
                fields[part.substring(0, at)] = part.substring(at + 1)
            }
            val look = ScanLookKind.of(fields["look"]) ?: ScanLookKind.Auto
            val strength = fields["strength"]?.toIntOrNull()?.let { clampStrength(it) } ?: look.defaultStrength
            return ScanPageLook(
                look = look,
                strength = strength,
                keepPaper = fields["keep"] == "1",
                flatten = ScanFlattenMode.of(fields["flatten"]) ?: ScanFlattenMode.Off,
                userTurns = fields["turns"]?.toIntOrNull() ?: 0,
                keepUpright = fields["upright"] == "1",
            )
        }

        /**
         * The settings an older build's filter name meant (spec S15 section 1, "Map the old
         * ScanFilter values"). CleanScan, the old default, is Auto at 60. An unknown name is the
         * default.
         */
        fun ofOldFilter(name: String?): ScanPageLook = when (name) {
            "Original" -> ScanPageLook(look = ScanLookKind.Original, strength = 0)
            "CleanScan" -> ScanPageLook(look = ScanLookKind.Auto, strength = 60)
            "AutoColor" -> ScanPageLook(look = ScanLookKind.Colour, strength = 50)
            "Grayscale" -> ScanPageLook(look = ScanLookKind.Greyscale, strength = 40)
            "Whiteboard" -> ScanPageLook(look = ScanLookKind.Whiteboard, strength = 85)
            "LightText" -> ScanPageLook(look = ScanLookKind.BlackWhite, strength = 75)
            else -> ScanPageLook()
        }

        /**
         * The native look name and strength to ask the Rust side for. The names are the ones
         * `ScanSave.kt` already maps (`scanLookOf`). Rust has no Whiteboard look yet, so
         * Whiteboard is asked for as "colour" with its own strength. Original is always 0.
         */
        fun nativeLook(look: ScanPageLook): Pair<String, Int> {
            val name = when (look.look) {
                ScanLookKind.Original -> "original"
                ScanLookKind.Auto -> "auto"
                ScanLookKind.Colour -> "colour"
                ScanLookKind.Whiteboard -> "colour"
                ScanLookKind.Greyscale -> "greyscale"
                ScanLookKind.BlackWhite -> "bw"
            }
            val strength = if (look.look == ScanLookKind.Original) 0 else clampStrength(look.strength)
            return Pair(name, strength)
        }
    }
}

/** Whether two pages have the same look settings: the look, strength, paper and flatten, not the turns. */
private fun sameLook(a: ScanPageLook, b: ScanPageLook): Boolean =
    a.look == b.look && a.strength == b.strength && a.keepPaper == b.keepPaper && a.flatten == b.flatten

/**
 * Whether the pages of a scan do not all have the same look, for the "Apply to all"
 * confirmation (#301). Turns are not part of the look: two pages turned differently have the
 * same look. An empty scan or a single page has no difference.
 */
fun looksDiffer(pages: List<ScanPageLook>): Boolean {
    val first: ScanPageLook = pages.firstOrNull() ?: return false
    return pages.any { page -> !sameLook(page, first) }
}

/**
 * [look] given to every page (#301, spec S15 section 3): its look, strength, paper and flatten.
 * Each page keeps its own turns and its own upright flag, because those belong to the page.
 */
fun applyToAll(pages: List<ScanPageLook>, look: ScanPageLook): List<ScanPageLook> =
    pages.map { page ->
        page.copy(look = look.look, strength = look.strength, keepPaper = look.keepPaper, flatten = look.flatten)
    }
