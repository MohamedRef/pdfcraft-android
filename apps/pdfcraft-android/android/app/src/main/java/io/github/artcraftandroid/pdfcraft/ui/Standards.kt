package io.github.artcraftandroid.pdfcraft.ui

import org.json.JSONObject

/** One PDF/A rule the document breaks, as `pdfa_verify` reports it. */
data class PdfaIssue(val clause: String, val message: String, val page: Int?, val fixable: Boolean)

/** A `pdfa_verify` or `pdfa_convert` report. */
data class PdfaReport(val level: String, val compliant: Boolean, val declared: String?, val issues: List<PdfaIssue>)

fun parsePdfaReport(o: JSONObject): PdfaReport {
    val a = o.optJSONArray("issues")
    val issues = (0 until (a?.length() ?: 0)).mapNotNull { i ->
        val it = a!!.optJSONObject(i) ?: return@mapNotNull null
        PdfaIssue(
            it.optString("clause"), it.optString("message"),
            if (it.isNull("page")) null else it.optInt("page").takeIf { p -> p > 0 },
            it.optBoolean("fixable", false),
        )
    }
    val declared = o.optJSONObject("declared")?.let { d -> if (d.isNull("pdfa")) null else d.optString("pdfa").ifEmpty { null } }
    return PdfaReport(o.optString("level"), o.optBoolean("compliant", issues.isEmpty()), declared, issues)
}

/** One line of a `print_readiness` report's findings. */
data class PrintFinding(val severity: String, val message: String, val page: Int?)

fun parsePrintFindings(o: JSONObject): List<PrintFinding> {
    val a = o.optJSONArray("findings") ?: return emptyList()
    return (0 until a.length()).mapNotNull { i ->
        val f = a.optJSONObject(i) ?: return@mapNotNull null
        PrintFinding(f.optString("severity", "info"), f.optString("message"), if (f.isNull("page")) null else f.optInt("page").takeIf { it > 0 })
    }
}

/** Fix thin lines: strokes narrower than [narrowerThan] points are given [width] points (both at most 10). */
data class HairlineFix(val narrowerThan: Double = 0.216, val width: Double = 0.72) {
    val valid: Boolean get() = narrowerThan in 0.0..10.0 && width > 0.0 && width <= 10.0
    fun args(pages: List<Int>?): Map<String, Any?> = buildMap {
        put("narrower_than", narrowerThan)
        put("width", width)
        if (pages != null) put("pages", pages)
    }
}

/** The page layouts Print options offers, as `doc_print` names them. */
enum class PrintLayout(val key: String) { Fit("fit"), Actual("actual"), Shrink("shrink"), Multiple("multiple"), Booklet("booklet") }

enum class PrintSubset(val key: String) { All("all"), Odd("odd"), Even("even") }

/** Print options (#185): what `doc_print` lays out before the system print dialog takes over. */
data class PrintOptions(
    /** A range such as "1-3, 6" (page labels allowed); blank: every page. */
    val pages: String = "",
    val subset: PrintSubset = PrintSubset.All,
    val reverse: Boolean = false,
    val layout: PrintLayout = PrintLayout.Fit,
    val perSheet: Int = 2,
    val border: Boolean = false,
    val bookletBinding: String = "left",
    val comments: Boolean = true,
) {
    /** Nothing changed from plain printing: print the document itself, as before. */
    val isDefault: Boolean get() = this == PrintOptions()

    fun args(path: String): Map<String, Any?> = buildMap {
        put("path", path)
        pages.trim().takeIf { it.isNotEmpty() }?.let { put("pages", it) }
        if (subset != PrintSubset.All) put("subset", subset.key)
        if (reverse) put("reverse", true)
        put("layout", layout.key)
        if (layout == PrintLayout.Multiple) {
            put("per_sheet", perSheet.takeIf { it in PER_SHEET } ?: 2)
            put("border", border)
        }
        if (layout == PrintLayout.Booklet) put("binding", bookletBinding)
        put("comments_forms", if (comments) "document-and-markups" else "document")
    }

    companion object {
        val PER_SHEET = listOf(2, 4, 6, 9, 16)
    }
}
