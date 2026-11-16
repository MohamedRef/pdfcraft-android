package io.github.artcraftandroid.pdfcraft.ui

/**
 * Every job that shows the busy dialog, by its message (#614), and what it can show. A job is
 * either counted (page n of m, or file n of m, in the dialog's bar) or on [notCounted] with the
 * reason. Every job shows the time taken after a few seconds; one working on a large file also
 * says so. BusyJobsTest fails when a new job is on neither list.
 */
internal object BusyJobs {
    /** Jobs that report their count at least once per page or file; those marked † also offer Cancel between them. */
    val counted = setOf(
        "msg_busy_recognizing", "msg_busy_recognizing_page", // text recognition, page by page †
        "msg_busy_cleaning_page", "msg_busy_finishing_page", "msg_busy_saving_scan", "scan_busy_preparing", // a scan's pages †
        "scan_gallery_busy", // a scan's pages to the gallery †
        "msg_busy_exporting_images", // Export to images, page by page †; Images in the PDF is one call (below)
        "msg_busy_combining", "msg_busy_creating_pdf", // files read in one by one †, then one call that cannot count
        "msg_busy_saving_files", // files saved to a folder one by one †
        "msg_busy_compressing", // Compress: the engine reports pages read and images done, and stops when asked † (#634)
    )

    /** Jobs that can neither count nor stop, with the reason: each is one call into the PDF library, or a few short ones. */
    val notCounted = mapOf(
        "msg_busy_splitting" to "one call: doc_split",
        "msg_busy_exporting_to" to "one call: doc_export_office or doc_export_text",
        "msg_busy_extracting" to "one call: page_extract",
        "print_busy" to "one call: doc_print",
        "msg_busy_setting_password" to "a copy opened, protected and saved: three calls",
        "msg_busy_signing" to "one signing call",
        "msg_busy_creating_id" to "one key made",
        "msg_busy_preparing_ocr" to "the recognition models copied once",
        "comments_exporting" to "one call",
        "comments_importing" to "one call",
        "comments_summarizing" to "one call",
        "measure_exporting" to "one call",
        "compare_busy" to "one call: doc_compare",
        "backup_restoring" to "one backup copied back",
        "cards_busy_export" to "one file written",
        "digest_busy" to "one file written",
        "edit_script_applying" to "one batch of edits, undone whole on failure",
        "handwriting_busy" to "one page",
        "notebook_busy_creating" to "one document made",
        "notebook_busy_saving_template" to "one page",
        "scan_enhance_busy" to "one call on the open pages",
        "sheet_copy_embedded_bookmarks_busy" to "one call",
        "sheet_export_embedded_bookmarks" to "one call",
        "sheet_import_embedded_bookmarks" to "one call",
        "text_styles_creating" to "one call",
    )
}
