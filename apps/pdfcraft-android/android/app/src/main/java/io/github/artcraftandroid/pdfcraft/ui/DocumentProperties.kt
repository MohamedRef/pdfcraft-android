package io.github.artcraftandroid.pdfcraft.ui

/** The standard metadata entries Edit properties offers, as `doc_set_info` names them. */
val STANDARD_INFO_KEYS = listOf("Title", "Author", "Subject", "Keywords")

/**
 * A custom property name `doc_set_info` accepts: not a standard entry, at most 127 bytes, with no
 * spaces, PDF delimiters, `/` or `#`.
 */
fun customKeyValid(name: String): Boolean {
    if (name.isEmpty() || name.toByteArray(Charsets.UTF_8).size > 127) return false
    if (name in STANDARD_INFO_KEYS || name in setOf("Creator", "Producer", "CreationDate", "ModDate", "Trapped")) return false
    return name.none { it.isWhitespace() || it in "()<>[]{}/%#" || it.isISOControl() }
}

/**
 * The `doc_set_info` calls that take metadata from [before] to [after]: only changed entries, an
 * entry removed by an empty value. Values are trimmed; standard entries come first, in order.
 */
fun metadataChanges(before: Map<String, String?>, after: Map<String, String>): List<Map<String, Any?>> {
    val keys = (STANDARD_INFO_KEYS.filter { it in after } + after.keys.filter { it !in STANDARD_INFO_KEYS }.sorted())
    return keys.mapNotNull { k ->
        val new = after.getValue(k).trim()
        val old = before[k]?.trim().orEmpty()
        if (new == old) null else mapOf("key" to k, "value" to new)
    }
}

/** Initial view layouts: `doc_initial_view`'s argument and the name its reply uses. */
enum class OpenLayout(val arg: String, val reply: String) {
    Default("default", "Default"),
    Single("single", "SinglePage"),
    Continuous("continuous", "SinglePageContinuous"),
    TwoUp("two_up", "TwoUp"),
    TwoUpContinuous("two_up_continuous", "TwoUpContinuous"),
    TwoUpCover("two_up_cover", "TwoUpCoverPage"),
    TwoUpContinuousCover("two_up_continuous_cover", "TwoUpContinuousCoverPage"),
    ;
    companion object { fun fromReply(s: String?) = entries.firstOrNull { it.reply == s } ?: Default }
}

/** Initial view magnifications (a percentage is kept as it is unless another is picked). */
enum class OpenZoom(val arg: String, val reply: String) {
    Default("default", "Default"),
    Actual("actual", "ActualSize"),
    FitPage("fit_page", "FitPage"),
    FitWidth("fit_width", "FitWidth"),
    FitHeight("fit_height", "FitHeight"),
    FitVisible("fit_visible", "FitVisible"),
    ;
    companion object {
        /** The zoom a reply names, or null for a percentage (`Percent(150.0)`). */
        fun fromReply(s: String?) = entries.firstOrNull { it.reply == s } ?: if (s?.startsWith("Percent(") == true) null else Default
    }
}

/** Initial view panels: what opens beside the page. */
enum class OpenPanel(val arg: String, val reply: String) {
    Page("page", "PageOnly"),
    Bookmarks("bookmarks", "Bookmarks"),
    Pages("pages", "Pages"),
    Attachments("attachments", "Attachments"),
    Layers("layers", "Layers"),
    ;
    companion object { fun fromReply(s: String?) = entries.firstOrNull { it.reply == s } ?: Page }
}

/** A percentage magnification from a reply such as `Percent(150.0)`, else null. */
fun percentFromReply(s: String?): Double? = s?.removePrefix("Percent(")?.removeSuffix(")")?.takeIf { s.startsWith("Percent(") }?.toDoubleOrNull()
