package io.github.artcraftandroid.pdfcraft.ui.scan

import org.json.JSONObject

/**
 * Which scanner takes the photos (#307, spec S21). PdfCraft's own scanner is the default; Google's
 * scanner (ML Kit's document scanner, run by Google Play services) is an option for comparison.
 * [id] is the word kept in the app's preferences and written to the diagnostics log.
 */
enum class ScanEngine(val id: String) {
    Own("own"),
    Google("google");

    companion object {
        /** The engine named by a stored [id]; anything missing or not understood is [Own]. */
        fun fromId(id: String?): ScanEngine = entries.firstOrNull { it.id == id } ?: Own
    }
}

/**
 * The decision of which engine runs. Google's scanner is used only when the person chose it and
 * Google Play services is on the device; otherwise PdfCraft's scanner runs, as it always has.
 */
fun engineFor(choice: ScanEngine, playServices: Boolean): ScanEngine =
    if (choice == ScanEngine.Google && playServices) ScanEngine.Google else ScanEngine.Own

/** How Google's scanner is set up for one scan (spec S21 section 3). */
enum class GoogleScanMode(val id: String) {
    /** Only the basic capture and crop. */
    Base("base"),

    /** The basic flow with its filters. */
    BaseWithFilter("base_with_filter"),

    /** The full flow, with the ML-based clean-up such as finger removal. The default. */
    Full("full"),
}

/**
 * The options for one scan through Google's scanner. [pageLimit] null means no limit. The
 * scanner returns JPEG pages only: PdfCraft builds the PDF itself, so OCR and naming work as for
 * its own scans. [galleryImportAllowed] lets the person pick photos from the gallery in that flow.
 */
data class GoogleScanOptions(
    val pageLimit: Int? = null,
    val galleryImportAllowed: Boolean = true,
    val mode: GoogleScanMode = GoogleScanMode.Full,
)

/** Why starting Google's scanner failed. The id is the word written to the diagnostics log. */
enum class GoogleStartFailure(val id: String) {
    /** ML Kit's UNSUPPORTED error: the device has too little RAM for the scanner (below 1.7 GB). */
    Unsupported("unsupported"),

    /** Any other failure to start: Google Play services missing, or the scanner unavailable. */
    Other("other"),
}

/** What the app does after Google's scanner could not start. */
enum class GoogleStartAction(val id: String) {
    /** Not available: show that Google's scanner is not available, and open PdfCraft's camera. */
    OwnCameraWithNotice("own_camera_notice"),

    /** This device cannot run it: show that, and set the scanner back to PdfCraft. */
    SwitchBackWithNotice("switch_back_notice"),
}

/** The action after a failure to start Google's scanner (spec S21 section 3). */
fun onGoogleStartFailure(failure: GoogleStartFailure): GoogleStartAction = when (failure) {
    GoogleStartFailure.Unsupported -> GoogleStartAction.SwitchBackWithNotice
    GoogleStartFailure.Other -> GoogleStartAction.OwnCameraWithNotice
}

/** What the app does when Google's scanner returns (spec S21 section 3). */
enum class GoogleResultAction(val id: String) {
    /** The person left Google's scanner without a result: back to where they were, no message. */
    Cancelled("cancelled"),

    /** Every returned page was copied: add them as scan pages. */
    AddPages("add_pages"),

    /** The result had no pages, or one page could not be copied: nothing is added; show a message. */
    Failed("failed"),
}

/**
 * The action once Google's scanner returns. [pagesReturned] is the number of pages in the result,
 * [pagesCopied] how many of them were copied into the app. A partly copied scan is not added,
 * so no page goes missing without a message.
 */
fun onGoogleResult(resultOk: Boolean, pagesReturned: Int, pagesCopied: Int): GoogleResultAction = when {
    !resultOk -> GoogleResultAction.Cancelled
    pagesReturned > 0 && pagesCopied == pagesReturned -> GoogleResultAction.AddPages
    else -> GoogleResultAction.Failed
}

/** How a judged page's fault is named in the comparison (spec S21 section 4). */
enum class ComparisonFault(val id: String) {
    None("none"),
    Edges("edges"),
    Shadow("shadow"),
    Sharpness("sharpness"),
    Colour("colour"),
    Flatness("flatness"),
}

/**
 * One line of the comparison between the two scanners (spec S21 section 4): for [document], the
 * [engine]'s result, whether its corners needed a hand adjustment, its [rank] in the blind
 * ranking (1 is best, 3 is worst), and the [fault] that made it worse, when it ranked last.
 * Holds numbers, booleans and short words only, so [json] can go to the diagnostics log.
 */
data class ScanComparisonRecord(
    val document: Int,
    val engine: ScanEngine,
    val cornersAdjusted: Boolean,
    val rank: Int,
    val fault: ComparisonFault,
) {
    /** True when the document number is not negative and the rank is one of the three ranks. */
    val valid: Boolean get() = document >= 0 && rank in 1..3

    /** The record as JSON: keys in lower case with underscores, values numbers, booleans and words. */
    fun json(): JSONObject = JSONObject()
        .put("document", document)
        .put("engine", engine.id)
        .put("corners_adjusted", cornersAdjusted)
        .put("rank", rank)
        .put("fault", fault.id)
}
