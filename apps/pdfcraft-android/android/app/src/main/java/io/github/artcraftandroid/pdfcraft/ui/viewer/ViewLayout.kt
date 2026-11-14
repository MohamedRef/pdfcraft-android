package io.github.artcraftandroid.pdfcraft.ui.viewer

import io.github.artcraftandroid.pdfcraft.ui.OpenLayout

/** Automatic two pages: when the window is landscape and at least this wide (dp), as a tablet held sideways is. */
const val AUTO_SPREAD_MIN_WIDTH_DP = 840

/**
 * The pages shown side by side in a window [widthDp] × [heightDp]: [chosen] where it fits, or
 * with [auto], two pages in a wide landscape window and one otherwise (#152).
 */
fun shownSpread(chosen: Spread, auto: Boolean, widthDp: Int, heightDp: Int): Spread = when {
    auto -> if (widthDp >= AUTO_SPREAD_MIN_WIDTH_DP && widthDp > heightDp) Spread.TwoUp else Spread.Single
    spreadFits(widthDp) -> chosen
    else -> Spread.Single
}

/**
 * How a document's pages are laid out: the page mode and the spread, or automatic two pages, and
 * whether the continuous view fits every page to the width ([fitEach]: null decides from the
 * pages, #524). Remembered for each document (#152), as text such as "Continuous:TwoUp" or
 * "Continuous:Single:FitEach".
 */
data class ViewLayout(val mode: PageMode, val spread: Spread, val autoSpread: Boolean = false, val fitEach: Boolean? = null) {
    fun encode(): String = "${mode.name}:${if (autoSpread) AUTO else spread.name}" + when (fitEach) {
        null -> ""
        true -> ":$FIT_EACH"
        false -> ":$ONE_SCALE"
    }

    /**
     * The initial view this layout writes into the file, so other readers open it the same way;
     * null when PDF has no such layout (pages in a row, or automatic two pages). PDF has no
     * fit-each-page-to-the-width either: that stays remembered in the app only.
     */
    fun openLayout(): OpenLayout? {
        if (autoSpread) return null
        return when (mode) {
            PageMode.Horizontal -> null
            PageMode.Continuous -> when (spread) {
                Spread.Single -> OpenLayout.Continuous
                Spread.TwoUp -> OpenLayout.TwoUpContinuous
                Spread.TwoUpCover -> OpenLayout.TwoUpContinuousCover
            }
            PageMode.SinglePage -> when (spread) {
                Spread.Single -> OpenLayout.Single
                Spread.TwoUp -> OpenLayout.TwoUp
                Spread.TwoUpCover -> OpenLayout.TwoUpCover
            }
        }
    }

    companion object {
        private const val AUTO = "Auto"
        private const val FIT_EACH = "FitEach"
        private const val ONE_SCALE = "OneScale"

        /** A remembered layout, or null when there is none or it can't be read. */
        fun decode(s: String?): ViewLayout? {
            val parts = s?.split(':')?.takeIf { it.size == 2 || it.size == 3 } ?: return null
            val mode = PageMode.entries.firstOrNull { it.name == parts[0] } ?: return null
            // layouts remembered before #524 have no third part: decided from the pages
            val fit = when (parts.getOrNull(2)) {
                null -> null
                FIT_EACH -> true
                ONE_SCALE -> false
                else -> return null
            }
            if (parts[1] == AUTO) return ViewLayout(mode, Spread.Single, autoSpread = true, fitEach = fit)
            val spread = Spread.entries.firstOrNull { it.name == parts[1] } ?: return null
            return ViewLayout(mode, spread, fitEach = fit)
        }

        /** The layout a file's initial view asks for, or null when it leaves it to the reader. */
        fun of(open: OpenLayout): ViewLayout? = when (open) {
            OpenLayout.Default -> null
            OpenLayout.Single -> ViewLayout(PageMode.SinglePage, Spread.Single)
            OpenLayout.Continuous -> ViewLayout(PageMode.Continuous, Spread.Single)
            OpenLayout.TwoUp -> ViewLayout(PageMode.SinglePage, Spread.TwoUp)
            OpenLayout.TwoUpContinuous -> ViewLayout(PageMode.Continuous, Spread.TwoUp)
            OpenLayout.TwoUpCover -> ViewLayout(PageMode.SinglePage, Spread.TwoUpCover)
            OpenLayout.TwoUpContinuousCover -> ViewLayout(PageMode.Continuous, Spread.TwoUpCover)
        }
    }
}
