package io.github.artcraftandroid.pdfcraft.ui.viewer

/** 1-based native page indices, capped to avoid processing entire large PDFs by accident. */
internal const val MAX_SCAN_ENHANCE_PAGES = 12

internal data class ScanEnhancePlan(
    val pages: List<Int>,
    val level: Int,
    val edgeShadows: Boolean,
    val despeckle: Boolean,
)

/** Scope is current page or current plus at most 11 following pages, never an unbounded all-pages operation. */
internal fun scanEnhancePlan(
    pageCount: Int,
    currentPage: Int,
    batch: Boolean,
    level: Int,
    edgeShadows: Boolean,
    despeckle: Boolean,
): ScanEnhancePlan? {
    if (pageCount < 1 || currentPage !in 0 until pageCount || level !in 0..100) return null
    if (level == 0 && !edgeShadows && !despeckle) return null
    val count = if (batch) minOf(MAX_SCAN_ENHANCE_PAGES, pageCount - currentPage) else 1
    val pages = (currentPage + 1 until currentPage + count + 1).toList()
    return ScanEnhancePlan(pages, level, edgeShadows, despeckle)
}

/** Revalidate at submission so a modified/mismatched document never receives arbitrary pages. */
internal fun validScanEnhancePlan(plan: ScanEnhancePlan, pageCount: Int): Boolean =
    pageCount > 0 && plan.level in 0..100 &&
        (plan.level > 0 || plan.edgeShadows || plan.despeckle) &&
        plan.pages.isNotEmpty() && plan.pages.size <= MAX_SCAN_ENHANCE_PAGES &&
        plan.pages.first() in 1..pageCount &&
        plan.pages.last() <= pageCount &&
        plan.pages.zipWithNext().all { (a, b) -> b == a + 1 }
