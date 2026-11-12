package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.ui.ScanFilter
import org.json.JSONObject

/**
 * A page's look on the scan screen (#301): which look, and its strength (0–100), null for the
 * look's own. Each page has its own; a new page takes the look chosen last.
 */
data class ScanPageLook(
    val filter: ScanFilter = ScanFilter.CleanScan,
    val strength: Int? = null,
    /** The paper's colour, for the looks that have one (Clean scan and Whiteboard). */
    val paper: ScanPaperChoice = ScanPaperChoice.Auto,
) {
    /** Clean scan and Whiteboard whiten the paper or keep its colour; the other looks have no such choice. */
    val hasPaper: Boolean get() = filter == ScanFilter.CleanScan || filter == ScanFilter.Whiteboard

    /** The strength the page is made at, before any testing option overrides it. */
    val level: Int get() = (strength ?: filter.level).coerceIn(0, 100)

    /** Original has no strength: the slider is hidden for it. */
    val hasStrength: Boolean get() = filter != ScanFilter.Original

    /** A diagnostics word for where the strength came from: the slider ("page") or the look's own ("look"). */
    val strengthSource: String get() = if (strength != null) "page" else "look"

    /** The look as it goes into a kept scan's page file. */
    fun json(perPage: Boolean): JSONObject = JSONObject()
        .put("id", scanFilterId(filter)).put("strength", strength ?: JSONObject.NULL).put("level", level).put("per_page", perPage)
        .put("paper", paper.word)

    companion object {
        /** The look in a session's `look` line ([id] and [strength] as [scanFilterId] and digits); null when it is not one. */
        fun decode(id: String?, strength: String?, paper: String? = null): ScanPageLook? {
            val filter = scanFilterOf(id) ?: return null
            val s = strength?.takeIf { it.isNotEmpty() }?.let { it.toIntOrNull()?.takeIf { v -> v in 0..100 } ?: return null }
            return ScanPageLook(filter, s, ScanPaperChoice.of(paper))
        }
    }
}

/**
 * The paper's colour in Clean scan and Whiteboard (#301): left to the kind of document (white
 * unless the kind keeps it), white, or the paper's own colour evenly lit. White is the kind's
 * usual answer because a photo can't tell coloured paper from coloured light (#298).
 */
enum class ScanPaperChoice(val word: String) {
    Auto("auto"),
    White("white"),
    Keep("keep");

    companion object {
        /** The choice in [word]; Auto for anything else. */
        fun of(word: String?): ScanPaperChoice = entries.firstOrNull { it.word == word } ?: Auto
    }
}

/**
 * A look's word, kept in a scan session and written to the diagnostics log. Fixed here and not
 * taken from the enum's names, so renaming a look in code cannot lose a saved session's choice.
 * The native look alone would not do: with its testing option off, Whiteboard is Auto color's.
 */
fun scanFilterId(filter: ScanFilter): String = when (filter) {
    ScanFilter.Original -> "original"
    ScanFilter.CleanScan -> "clean"
    ScanFilter.AutoColor -> "colour"
    ScanFilter.Grayscale -> "greyscale"
    ScanFilter.Whiteboard -> "whiteboard"
    ScanFilter.LightText -> "bw"
}

fun scanFilterOf(id: String?): ScanFilter? = ScanFilter.entries.firstOrNull { scanFilterId(it) == id }

/**
 * The look a page just taken gets: a retaken page keeps the look of the page it replaces, any
 * other the look of the last page (the one chosen last, in practice), and the first page Clean
 * scan at its own strength. [looks] are the scan's pages' looks in order; [retake] the page replaced.
 */
fun scanLookForNewPage(looks: List<ScanPageLook>, retake: Int?): ScanPageLook =
    retake?.let { looks.getOrNull(it) } ?: looks.lastOrNull() ?: ScanPageLook()

/**
 * The scan's looks once [look] is chosen on page [index]: that page alone, or every page when the
 * testing option for one look per page is off (as before #301). A page out of range changes nothing.
 */
fun scanChooseLook(looks: List<ScanPageLook>, index: Int, look: ScanPageLook, perPage: Boolean): List<ScanPageLook> = when {
    index !in looks.indices -> looks
    !perPage -> looks.map { look }
    else -> looks.mapIndexed { i, l -> if (i == index) look else l }
}

/** Apply to all: every page takes page [index]'s look and strength (its corners, turn and kind stay its own). */
fun scanApplyLookToAll(looks: List<ScanPageLook>, index: Int): List<ScanPageLook> {
    val from = looks.getOrNull(index) ?: return looks
    return looks.map { from }
}

/** Apply to all asks first when it would change a page: some page's look differs from page [index]'s. */
fun scanApplyLookNeedsConfirm(looks: List<ScanPageLook>, index: Int): Boolean {
    val from = looks.getOrNull(index) ?: return false
    return looks.any { it != from }
}

/** The pages strip marks a page whose look is not the first page's. */
fun scanLookDiffers(looks: List<ScanPageLook>, index: Int): Boolean =
    index > 0 && index in looks.indices && looks[index] != looks[0]

/** How many pages have a look of their own (not the first page's): for the diagnostics log. */
fun scanLooksMixed(looks: List<ScanPageLook>): Int = looks.indices.count { scanLookDiffers(looks, it) }

/** The slider's value as a whole strength, 0–100, as the page keeps it. */
fun scanSliderStrength(value: Float): Int = if (value.isFinite()) Math.round(value).coerceIn(0, 100) else 0

/**
 * The looks strip's order of work (#301): Original first, because press-and-hold compares with it,
 * then the look shown, then the others as the strip lists them. Each look appears once.
 */
fun scanTileOrder(selected: ScanFilter): List<ScanFilter> =
    (listOf(ScanFilter.Original, selected) + ScanFilter.entries).distinct()

/**
 * The image a tile of the strip shows (#301): every look at its own strength, except the look the
 * page has, whose tile is the page's own preview (at the page's strength) once that is ready.
 */
fun <T> scanTileImage(tile: ScanFilter, page: ScanPageLook, tiles: Map<ScanFilter, T>, shown: T?): T? =
    if (tile == page.filter && shown != null) shown else tiles[tile]

/** What the main area shows while a finger is held on it (#301): the Original look when it is ready, else nothing new. */
fun scanCompareShows(holding: Boolean, haveOriginal: Boolean, look: ScanFilter): Boolean =
    holding && haveOriginal && look != ScanFilter.Original

/** How many pages went into the PDF as photographed because their processing failed (#301): the final message counts them. */
fun scanUnenhancedCount(processed: List<Boolean>): Int = processed.count { !it }


/** The zoom after a double-tap on the preview (#301): in to [tap] from the whole page, back to the whole page from anywhere else. */
fun scanZoomAfterDoubleTap(zoom: Float, tap: Float): Float = if (zoom > 1.01f) 1f else tap

/** A zoom kept between the whole page (1) and [max]; anything that is not a number is the whole page. */
fun scanZoomClamp(zoom: Float, max: Float): Float = if (zoom.isFinite()) zoom.coerceIn(1f, max) else 1f

/**
 * How far the zoomed page may be moved, so its edge never comes inside the box: at [zoom] on a
 * box [width] × [height] it can move `(zoom − 1) / 2` of the box each way. Returns the offset kept.
 */
fun scanPanClamp(x: Float, y: Float, zoom: Float, width: Float, height: Float): Pair<Float, Float> {
    val mx = ((zoom - 1f) * width / 2f).coerceAtLeast(0f)
    val my = ((zoom - 1f) * height / 2f).coerceAtLeast(0f)
    fun keep(v: Float, m: Float) = if (v.isFinite()) v.coerceIn(-m, m) else 0f
    return keep(x, mx) to keep(y, my)
}

/** Whether the sharper page is wanted: zoomed in at least [from], with the testing option on. */
fun scanWantsDetail(zoom: Float, from: Float, enabled: Boolean): Boolean = enabled && zoom >= from
