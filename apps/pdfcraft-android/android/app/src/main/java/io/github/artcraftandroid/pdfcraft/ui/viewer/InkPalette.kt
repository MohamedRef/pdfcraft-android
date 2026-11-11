package io.github.artcraftandroid.pdfcraft.ui.viewer

import org.json.JSONArray
import org.json.JSONObject

/**
 * A named set of pen colours (#149), e.g. "Biology". Each colour may carry its own name, which
 * TalkBack reads instead of the hex value. Palettes are kept in the app's preferences and can be
 * shared as a small JSON file.
 */
internal data class InkPalette(val name: String, val colors: List<PaletteColor>) {
    data class PaletteColor(val argb: Int, val name: String? = null)

    companion object {
        const val MAX_PALETTES = 32
        const val MAX_COLORS = 64
        const val MAX_NAME = 60
        /** An exported palette is a few kilobytes; anything far larger is not one. */
        const val MAX_FILE_BYTES = 64 * 1024
        const val FORMAT = "pdfcraft-ink-palette"
        const val VERSION = 1
    }
}

/** Keys of the two built-in palettes; user palettes are keyed by their name, which never equals these. */
internal const val PALETTE_STANDARD = "\u0000standard"
internal const val PALETTE_COLOR_BLIND = "\u0000colorblind"
internal val RESERVED_PALETTE_KEYS = listOf(PALETTE_STANDARD, PALETTE_COLOR_BLIND)

/** Okabe–Ito colours, distinguishable with the common forms of colour blindness, shipped as a palette (#149). */
internal val ColorBlindSafeColors: List<Int> = listOf(
    0xFF000000.toInt(), 0xFFE69F00.toInt(), 0xFF56B4E9.toInt(), 0xFF009E73.toInt(),
    0xFFF0E442.toInt(), 0xFF0072B2.toInt(), 0xFFD55E00.toInt(), 0xFFCC79A7.toInt(),
)

private fun cleanName(raw: String?): String? =
    raw?.filter { !it.isISOControl() }?.trim()?.take(InkPalette.MAX_NAME)?.takeIf { it.isNotEmpty() }

/** The palette as it is exported: `{"format":"pdfcraft-ink-palette","version":1,"name":…,"colors":[{"color":"#AARRGGBB","name":…}]}`. */
internal fun InkPalette.toJson(): JSONObject = JSONObject()
    .put("format", InkPalette.FORMAT)
    .put("version", InkPalette.VERSION)
    .put("name", name)
    .put("colors", JSONArray().also { arr ->
        colors.forEach { c ->
            arr.put(JSONObject().put("color", "#%08X".format(c.argb)).also { o -> c.name?.let { o.put("name", it) } })
        }
    })

/** Why a palette file was not imported. */
internal enum class PaletteImportError { NotAPalette, NewerVersion, NoColors }

/**
 * Reads one exported palette. Unknown fields are ignored, colours that do not parse are skipped,
 * names are cleaned and shortened, and the list is cut to [InkPalette.MAX_COLORS]. A file is
 * refused only when it is not a palette, is from a newer format, or has no usable colour.
 */
internal fun parseInkPalette(text: String): Result<InkPalette> {
    val obj = runCatching { JSONObject(text) }.getOrNull()
        ?: return Result.failure(PaletteImport(PaletteImportError.NotAPalette))
    if (obj.optString("format") != InkPalette.FORMAT) return Result.failure(PaletteImport(PaletteImportError.NotAPalette))
    if (obj.optInt("version", 0) > InkPalette.VERSION) return Result.failure(PaletteImport(PaletteImportError.NewerVersion))
    val arr = obj.optJSONArray("colors") ?: return Result.failure(PaletteImport(PaletteImportError.NoColors))
    val colors = (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val argb = parseHexColor(o.optString("color")) ?: return@mapNotNull null
        InkPalette.PaletteColor(argb, cleanName(o.optString("name").takeIf { o.has("name") }))
    }.distinctBy { it.argb }.take(InkPalette.MAX_COLORS)
    if (colors.isEmpty()) return Result.failure(PaletteImport(PaletteImportError.NoColors))
    return Result.success(InkPalette(cleanName(obj.optString("name")) ?: "", colors))
}

internal class PaletteImport(val reason: PaletteImportError) : Exception(reason.name)

/** All saved palettes as one preferences string. */
internal fun encodePalettes(list: List<InkPalette>): String = JSONArray().also { arr -> list.forEach { arr.put(it.toJson()) } }.toString()

/** The saved palettes; a damaged entry is dropped rather than losing the others. */
internal fun decodePalettes(text: String?): List<InkPalette> {
    if (text.isNullOrBlank()) return emptyList()
    val arr = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        arr.optJSONObject(i)?.toString()?.let { parseInkPalette(it).getOrNull() }
    }.take(InkPalette.MAX_PALETTES)
}

/** A name not used by another palette: "Biology", then "Biology 2", "Biology 3"… */
internal fun uniquePaletteName(wanted: String, taken: Collection<String>): String {
    val base = cleanName(wanted) ?: "Palette"
    if (base !in taken) return base
    var n = 2
    while ("$base $n" in taken) n++
    return "$base $n"
}

/** A file name for the exported palette: letters, digits, spaces, '-' and '_' only. */
internal fun paletteFileName(name: String): String {
    val safe = name.map { if (it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_') it else '_' }.joinToString("").trim()
    return (safe.ifEmpty { "palette" }).take(48) + ".json"
}

/** HSV (hue 0–360, saturation and value 0–1) to an opaque ARGB colour, as android.graphics.Color.HSVToColor does. */
internal fun hsvToArgb(h: Float, s: Float, v: Float): Int {
    val hue = ((h % 360f) + 360f) % 360f
    val sat = s.coerceIn(0f, 1f)
    val value = v.coerceIn(0f, 1f)
    val c = value * sat
    val x = c * (1 - kotlin.math.abs((hue / 60f) % 2f - 1))
    val m = value - c
    val (r, g, b) = when ((hue / 60f).toInt()) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    fun ch(f: Float) = Math.round((f + m) * 255f).coerceIn(0, 255)
    return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
}

/** ARGB to HSV (hue 0–360, saturation and value 0–1); alpha is ignored. */
internal fun argbToHsv(argb: Int): FloatArray {
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val d = max - min
    val h = when {
        d == 0f -> 0f
        max == r -> 60f * (((g - b) / d) % 6f)
        max == g -> 60f * ((b - r) / d + 2f)
        else -> 60f * ((r - g) / d + 4f)
    }.let { if (it < 0f) it + 360f else it }
    val s = if (max == 0f) 0f else d / max
    return floatArrayOf(h, s, max)
}
