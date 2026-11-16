package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.R

/**
 * What the busy dialog says for one step a watched engine tool reports (#634): the string [res]
 * filled with [args], or no line at all (the job's own message stays).
 */
internal data class JobLine(val res: Int, val args: List<Int> = emptyList())

/** How often the busy dialog asks a watched tool how far it is (#634). */
internal const val JOB_WATCH_MS = 250L

/**
 * The line for one step of `doc_split` (#634): `measuring` page [done] of [total] for a split by
 * size, then `parts`, part [done] of [total]. The engine says how many are done, so the one in
 * hand is [done] + 1, never past [total].
 */
internal fun splitLine(stage: String, done: Int, total: Int): JobLine? = when {
    total <= 0 -> null
    stage == "measuring" -> JobLine(R.string.msg_busy_splitting_measuring, listOf(minOf(done + 1, total), total))
    stage == "parts" -> JobLine(R.string.msg_busy_splitting_part, listOf(minOf(done + 1, total), total))
    else -> null
}

/**
 * The line for one step of `page_extract` (#634): `copying` the pages, `writing` the new file,
 * `checking` that it opens; or `pages`, page [done] of [total] when each page is its own file.
 */
internal fun extractLine(stage: String, done: Int, total: Int): JobLine? = when (stage) {
    "copying" -> JobLine(R.string.msg_busy_extracting_copying)
    "writing" -> JobLine(R.string.msg_busy_extracting_writing)
    "checking" -> JobLine(R.string.msg_busy_extracting_checking)
    "pages" -> if (total > 0) JobLine(R.string.msg_busy_extracting_page, listOf(minOf(done + 1, total), total)) else null
    else -> null
}

/**
 * The line for one step of `doc_export_office` and `doc_export_text` (#634): `pages`, page [done]
 * of [total] being read, then `writing` the file.
 */
internal fun exportLine(stage: String, done: Int, total: Int): JobLine? = when (stage) {
    "pages" -> if (total > 0) JobLine(R.string.msg_busy_exporting_doc_page, listOf(minOf(done + 1, total), total)) else null
    "writing" -> JobLine(R.string.msg_busy_exporting_writing)
    else -> null
}
