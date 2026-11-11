package io.github.artcraftandroid.pdfcraft.ui

/**
 * Compress ▸ Advanced (#172). With [enabled] off the three levels behave exactly as before;
 * on, these replace the level's image settings and add the discard options.
 */
data class CompressOptions(
    val enabled: Boolean = false,
    /** Target size in MB (`target_bytes`), or null for none. */
    val targetMb: Double? = null,
    val ppi: Int = 150,
    val quality: Int = 60,
    /** Optimise for phones: `preset: mobile` (its own image settings). */
    val mobile: Boolean = false,
    val removeInvalidLinks: Boolean = true,
    val discardFormActions: Boolean = false,
    val discardTags: Boolean = false,
    val discardAlternateImages: Boolean = true,
)

/** The level's image settings: (ppi, above ppi, JPEG quality). 0 high, 1 medium, 2 low compression. */
fun levelImages(level: Int): Triple<Int, Int, Int> = when (level) {
    0 -> Triple(72, 108, 40)
    2 -> Triple(300, 450, 80)
    else -> Triple(150, 225, 60)
}

/** The `doc_optimize` arguments (without doc and path) for [level] and [options]. */
fun compressArgs(level: Int, options: CompressOptions): Map<String, Any?> {
    if (!options.enabled) {
        val (ppi, above, quality) = levelImages(level)
        val images = mapOf("downsample" to true, "ppi" to ppi, "above_ppi" to above, "compression" to "jpeg", "quality" to quality)
        return mapOf("color" to images, "gray" to images, "discard_thumbnails" to true, "remove_unreferenced_dests" to true)
    }
    return buildMap {
        if (options.mobile) {
            put("preset", "mobile")
        } else {
            val ppi = options.ppi.coerceIn(36, 1200)
            val images = mapOf("downsample" to true, "ppi" to ppi, "above_ppi" to ppi * 3 / 2, "compression" to "jpeg", "quality" to options.quality.coerceIn(1, 100))
            put("color", images)
            put("gray", images)
        }
        put("discard_thumbnails", true)
        put("remove_unreferenced_dests", true)
        put("remove_invalid_links", options.removeInvalidLinks)
        put("discard_form_actions", options.discardFormActions)
        put("discard_tags", options.discardTags)
        put("discard_alternate_images", options.discardAlternateImages)
        options.targetMb?.takeIf { it > 0 }?.let { put("target_bytes", (it * 1024 * 1024).toLong().coerceAtLeast(1)) }
    }
}
