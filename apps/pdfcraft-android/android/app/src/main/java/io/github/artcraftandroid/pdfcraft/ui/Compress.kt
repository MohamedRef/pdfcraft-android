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

/** What the busy dialog says while Compress runs (#634), from the step the engine reports. */
sealed class CompressStep {
    /** No count to show: before the first step, and the short steps between and after the counted ones. */
    data object Working : CompressStep()

    /** Reading what page [page] of [pages] draws. */
    data class Reading(val page: Int, val pages: Int) : CompressStep()

    /** Compressing image [image] of [images]. */
    data class Images(val image: Int, val images: Int) : CompressStep()

    /** A target size was not reached: compressing again with stronger settings, run [attempt]. */
    data class Again(val attempt: Int) : CompressStep()
}

/**
 * The step to show for the engine's [stage] of `doc_optimize`, [done] of [total] in it, in run
 * [attempt] (#634). The engine counts from 0 ([done] pages read, about to do image [done]), so the
 * one in hand is [done] + 1, never past [total].
 */
fun compressStep(stage: String, done: Int, total: Int, attempt: Int): CompressStep = when {
    attempt > 1 -> CompressStep.Again(attempt)
    total <= 0 -> CompressStep.Working
    stage == "reading" -> CompressStep.Reading(minOf(done + 1, total), total)
    stage == "images" -> CompressStep.Images(minOf(done + 1, total), total)
    else -> CompressStep.Working
}

/** How often the busy dialog asks a running Compress how far it is (#634). */
const val COMPRESS_WATCH_MS = 250L

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
