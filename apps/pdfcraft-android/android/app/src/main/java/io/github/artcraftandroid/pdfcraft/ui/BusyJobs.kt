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
        "msg_busy_combining", "msg_busy_creating_pdf", // files read in one by one †, then the engine's steps file by file † (#634)
        "msg_busy_setting_password", // Set a password: opening a copy, protecting, saving; between them and inside the saving † (#634, #646)
        "msg_busy_saving_files", // files saved to a folder one by one †
        "msg_busy_compressing", // Compress: the engine reports pages read and images done, and stops when asked † (#634)
        "msg_busy_splitting", // Split: part by part (and page by page measuring for a size), and inside a part † (#634, #646)
        "msg_busy_extracting", // Extract pages: copying page by page, writing, checking; between and inside them † (#634, #646)
        "msg_busy_exporting_to", // Export to Word, Excel, PowerPoint, RTF or text: page by page, then writing † (#634)
        "print_busy", // Print options: pages drawn for Print as image, sheet by sheet, then writing † (#634)
        "compare_busy", // Compare: both files' pages read, the comparing, page by page for visual † (#634)
        "scan_enhance_busy", // Enhance scanned pages (at most 12): image by image † (#646)
        "text_styles_creating", // Contents from headings: the pages read for the headings †, then one edit (#652)
    )

    /**
     * Jobs that neither count nor stop, with the reason (#646): each is one call into the PDF
     * library that changes or writes one thing whole, or a few short ones. They show the time
     * taken, and a large file's note, like every job.
     */
    val notCounted = mapOf(
        "msg_busy_signing" to "one signing call: the signature is written whole or not at all",
        "msg_busy_creating_id" to "one key made",
        "msg_busy_preparing_ocr" to "the recognition models copied once, on first use",
        "comments_exporting" to "one call: the comments, not the pages",
        "comments_importing" to "one call, one undo step",
        "comments_summarizing" to "one call: a page per group of comments, not per page of the document",
        "measure_exporting" to "one call: the measurements, not the pages",
        "backup_restoring" to "one backup file copied back",
        "cards_busy_export" to "one file written",
        "digest_busy" to "one file written",
        "edit_script_applying" to "one batch of edits, undone whole on failure",
        "handwriting_busy" to "one page",
        "notebook_busy_creating" to "one document made",
        "notebook_busy_saving_template" to "one page",
        "sheet_copy_embedded_bookmarks_busy" to "one call: the bookmarks, not the pages",
        "sheet_export_embedded_bookmarks" to "one call: the bookmarks, not the pages",
        "sheet_import_embedded_bookmarks" to "one call, one undo step",
    )
}
