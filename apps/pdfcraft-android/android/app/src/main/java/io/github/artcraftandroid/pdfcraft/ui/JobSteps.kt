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

/**
 * The line for one step of `doc_print` (#634): `pictures`, page [done] of [total] drawn for Print
 * as image, then `sheets`, sheet [done] of [total], then `writing` the file.
 */
internal fun printLine(stage: String, done: Int, total: Int): JobLine? = when (stage) {
    "pictures" -> if (total > 0) JobLine(R.string.print_busy_picture, listOf(minOf(done + 1, total), total)) else null
    "sheets" -> if (total > 0) JobLine(R.string.print_busy_sheet, listOf(minOf(done + 1, total), total)) else null
    "writing" -> JobLine(R.string.print_busy_writing)
    else -> null
}

/**
 * The line for one step of `doc_compare` (#634): `reading`, page [done] of [total] across both
 * files, `comparing`, then `visual`, page [done] of [total] looked at side by side.
 */
internal fun compareLine(stage: String, done: Int, total: Int): JobLine? = when (stage) {
    "reading" -> if (total > 0) JobLine(R.string.compare_busy_reading, listOf(minOf(done + 1, total), total)) else null
    "comparing" -> JobLine(R.string.compare_busy_comparing)
    "visual" -> if (total > 0) JobLine(R.string.compare_busy_visual, listOf(minOf(done + 1, total), total)) else null
    else -> null
}

/**
 * The line for one step of `doc_combine` (#634): `reading`, file [done] of [total], `opening`,
 * file [done] of [total] opened, then `combining`, `writing`, `checking` and `saving`.
 */
internal fun combineLine(stage: String, done: Int, total: Int): JobLine? = when (stage) {
    "reading" -> if (total > 0) JobLine(R.string.msg_busy_combining_reading, listOf(minOf(done + 1, total), total)) else null
    "opening" -> if (total > 0) JobLine(R.string.msg_busy_combining_opening, listOf(minOf(done + 1, total), total)) else null
    "combining" -> JobLine(R.string.msg_busy_combining_pages)
    "writing", "checking", "saving" -> JobLine(R.string.msg_busy_combining_writing)
    else -> null
}

/**
 * The line for one step of `doc_create_multiple` (#634): `files`, file [done] of [total] made
 * into PDF and copied in, then `writing`.
 */
internal fun createLine(stage: String, done: Int, total: Int): JobLine? = when (stage) {
    "files" -> if (total > 0) JobLine(R.string.msg_busy_creating_file, listOf(minOf(done + 1, total), total)) else null
    "writing" -> JobLine(R.string.msg_busy_combining_writing)
    else -> null
}
