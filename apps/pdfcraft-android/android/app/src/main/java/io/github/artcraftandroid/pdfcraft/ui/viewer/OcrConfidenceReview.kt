package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import io.github.artcraftandroid.pdfcraft.ui.PdfSheet
import io.github.artcraftandroid.pdfcraft.ui.SheetBackHeader
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import org.json.JSONObject

/** OCR confidence inspection is read-only; no retained native review, file write or Undo entry. */
internal const val MAX_OCR_REVIEW_VISIBLE = 100

internal data class OcrConfidenceWord(
    val text: String,
    val confidence: Double?,
    val suspect: Boolean,
)

/** Suspect words first, and a bounded number of ordinary words after them. */
internal fun prioritizeOcrConfidenceWords(
    words: List<OcrConfidenceWord>,
    limit: Int = MAX_OCR_REVIEW_VISIBLE,
): List<OcrConfidenceWord> {
    if (limit <= 0) return emptyList()
    return (words.filter { it.suspect }.take(limit) +
        words.filterNot { it.suspect }.take(limit)).take(limit)
}

internal data class OcrConfidenceSummary(
    val page: Int,
    val totalWords: Int,
    val suspectWords: Int,
    val visibleWords: List<OcrConfidenceWord>,
    val skipped: String?,
)

/**
 * Reads bounded UI previews, but scans the complete native page result for suspect words.
 * Does not retain the engine's session-local review handle or PDF content across screens.
 */
internal fun parseOcrConfidenceSummary(result: JSONObject, requestedPage: Int): OcrConfidenceSummary? {
    if (requestedPage < 1) return null
    val pages = result.optJSONArray("pages") ?: return null
    var page: JSONObject? = null
    for (i in 0 until pages.length()) {
        val candidate = pages.optJSONObject(i) ?: continue
        if (candidate.optInt("page", -1) == requestedPage) {
            page = candidate
            break
        }
    }
    val item = page ?: return null
    val words = item.optJSONArray("words")
    val low = ArrayList<OcrConfidenceWord>(MAX_OCR_REVIEW_VISIBLE)
    val other = ArrayList<OcrConfidenceWord>(MAX_OCR_REVIEW_VISIBLE)
    var suspects = 0
    if (words != null) for (i in 0 until words.length()) {
        val entry = words.optJSONObject(i) ?: continue
        val text = entry.optString("text").take(120).trim()
        val confidence = entry.optDouble("confidence", Double.NaN).takeIf { it.isFinite() && it in 0.0..100.0 }
        val flagged = entry.optBoolean("suspect", false) || (confidence != null && confidence < 70.0)
        if (flagged) suspects++
        if (text.isBlank()) continue
        val target = if (flagged) low else other
        if (target.size < MAX_OCR_REVIEW_VISIBLE) {
            target.add(OcrConfidenceWord(text, confidence, flagged))
        }
    }
    val kept = prioritizeOcrConfidenceWords(low + other)
    val skipped = item.optString("skipped").takeIf { it.isNotBlank() }
    return OcrConfidenceSummary(requestedPage, words?.length() ?: 0, suspects, kept, skipped)
}

/** A small, opt-in page-scoped bridge to upstream v0.19.0's native ocr_words. */
@Composable
internal fun OcrConfidenceReviewSheet(vm: AppViewModel, doc: OpenDocument) {
    val ctl = doc.controller
    val page = doc.viewer.currentPage + 1
    val generation = doc.info.generation
    var result by remember(doc.id, page, generation) { mutableStateOf<OcrConfidenceSummary?>(null) }
    var loading by remember(doc.id, page, generation) { mutableStateOf(true) }
    var error by remember(doc.id, page, generation) { mutableStateOf<String?>(null) }
    val fallback = stringResource(R.string.ocr_review_error)

    LaunchedEffect(doc.id, page, generation) {
        loading = true
        result = null
        error = null
        try {
            val response = vm.inspectOcrConfidence(doc, page)
            if (response != null) {
                result = parseOcrConfidenceSummary(response, page)
                if (result == null) error = fallback
            } else error = fallback
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message?.take(250) ?: fallback
        } finally {
            loading = false
        }
    }
    PdfSheet(onDismiss = { ctl.sheet = ViewerSheet.None }, maxHeightFraction = 0.86f) {
        SheetBackHeader(stringResource(R.string.ocr_review_title), onBack = { ctl.sheet = ViewerSheet.More })
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.ocr_review_scope, page), color = pdfColors.textSecondary)
            Text(stringResource(R.string.ocr_review_read_only), color = pdfColors.textSecondary)
            if (loading) Text(stringResource(R.string.ocr_review_loading), color = pdfColors.text)
            if (error != null) Text(error ?: fallback, color = pdfColors.red)
            result?.let { summary ->
                Text(stringResource(R.string.ocr_review_counts, summary.totalWords, summary.suspectWords),
                    color = pdfColors.text, fontWeight = FontWeight.SemiBold)
                if (summary.skipped != null) {
                    Text(stringResource(R.string.ocr_review_skipped, summary.skipped), color = pdfColors.textSecondary)
                } else if (summary.totalWords == 0) {
                    Text(stringResource(R.string.ocr_review_empty), color = pdfColors.textSecondary)
                } else {
                    if (summary.totalWords > summary.visibleWords.size) {
                        Text(stringResource(R.string.ocr_review_limited, summary.visibleWords.size), color = pdfColors.textSecondary)
                    }
                    for (word in summary.visibleWords) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(word.text, color = pdfColors.text, modifier = Modifier.weight(1f))
                            Spacer(Modifier.padding(5.dp))
                            Text(word.confidence?.let { "${it.toInt()}%" }
                                ?: stringResource(R.string.ocr_review_unknown), color = if (word.suspect) pdfColors.red else pdfColors.textSecondary)
                        }
                    }
                }
            }
        }
    }
}
