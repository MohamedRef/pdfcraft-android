package io.github.artcraftandroid.pdfcraft.ui.scan

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import io.github.artcraftandroid.pdfcraft.ui.pdfColors

/*
 * What a document type suggests (#299, section 5): a book page suggests Flatten page (#302), a
 * receipt suggests Grayscale. Shown as a chip beside the document type; nothing changes until
 * the person taps it, because the type is a guess from the page.
 */

/** A suggestion the scan screen can show for a page. */
internal enum class ScanSuggestion(val id: String) {
    Flatten("flatten"),
    Greyscale("greyscale"),
}

/**
 * The suggestions to show for a page: Flatten page when its kind suggests it, the page can be
 * flattened and the person has not chosen either way for it; Grayscale when its kind suggests it
 * and the look is still Clean scan. None with the testing option off.
 */
internal fun scanSuggestions(
    on: Boolean,
    suggestFlatten: Boolean,
    suggestGreyscale: Boolean,
    canFlatten: Boolean,
    flattenChoice: Boolean?,
    cleanScan: Boolean,
): List<ScanSuggestion> = if (!on) emptyList() else buildList {
    if (suggestFlatten && canFlatten && flattenChoice == null) add(ScanSuggestion.Flatten)
    if (suggestGreyscale && cleanScan) add(ScanSuggestion.Greyscale)
}

/** A suggestion was taken: a line in the diagnostics log. */
internal fun logScanSuggestionTaken(index: Int, suggestion: ScanSuggestion) {
    Diag.event(DiagArea.Scan, "suggestion_taken", "index" to index + 1, "suggestion" to suggestion.id)
}

/** The chip for [suggestion]; [onTake] makes the change it suggests. */
@Composable
internal fun ScanSuggestionChip(suggestion: ScanSuggestion, onTake: () -> Unit, modifier: Modifier = Modifier) {
    val c = pdfColors
    val label = when (suggestion) {
        ScanSuggestion.Flatten -> R.string.scan_suggest_flatten
        ScanSuggestion.Greyscale -> R.string.scan_suggest_greyscale
    }
    Text(
        stringResource(label), color = c.accent, fontSize = 14.sp,
        modifier = modifier.clip(CircleShape).border(1.dp, c.accent, CircleShape)
            .clickable(role = Role.Button) { onTake() }.padding(horizontal = 14.dp, vertical = 6.dp),
    )
}
