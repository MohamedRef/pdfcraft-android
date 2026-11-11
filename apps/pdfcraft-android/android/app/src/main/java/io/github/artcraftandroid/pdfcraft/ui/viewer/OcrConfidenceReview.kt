package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import io.github.artcraftandroid.pdfcraft.text as appText
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

/** At most this many words are listed for review (suspects first). */
internal const val MAX_OCR_REVIEW_VISIBLE = 100

/** A recognised word: its text, confidence, whether to look at it first, and its 1-based [word] number on the page. */
internal data class OcrConfidenceWord(
    val text: String,
    val confidence: Double?,
    val suspect: Boolean,
    val word: Int = 0,
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
    /** The engine's kept review, for ocr_review_apply (0: none). */
    val review: Int = 0,
)

/**
 * Reads bounded UI previews, but scans the complete native page result for suspect words. A word
 * in the review [dictionary] (lower case) is never a suspect.
 */
internal fun parseOcrConfidenceSummary(result: JSONObject, requestedPage: Int, dictionary: Set<String> = emptySet()): OcrConfidenceSummary? {
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
        val known = text.lowercase().trim { !it.isLetterOrDigit() } in dictionary
        val flagged = !known && (entry.optBoolean("suspect", false) || (confidence != null && confidence < 70.0))
        if (flagged) suspects++
        if (text.isBlank()) continue
        val target = if (flagged) low else other
        if (target.size < MAX_OCR_REVIEW_VISIBLE) {
            target.add(OcrConfidenceWord(text, confidence, flagged, i + 1))
        }
    }
    val kept = prioritizeOcrConfidenceWords(low + other)
    val skipped = item.optString("skipped").takeIf { it.isNotBlank() }
    return OcrConfidenceSummary(requestedPage, words?.length() ?: 0, suspects, kept, skipped, result.optInt("review", 0))
}

/**
 * Review recognised text (OCR Verify) on the current page: suspect words first; tap a word to
 * correct it (empty removes it), add it to the language's dictionary so later reviews don't flag
 * it, then Apply corrections, which adds the page's text in one undo step. Leaving discards the
 * review.
 */
@Composable
internal fun OcrConfidenceReviewSheet(vm: AppViewModel, doc: OpenDocument) {
    val ctl = doc.controller
    val page = doc.viewer.currentPage + 1
    val generation = doc.info.generation
    val context = androidx.compose.ui.platform.LocalContext.current
    val options = remember { OcrOptions.load(context) }
    var result by remember(doc.id, page, generation) { mutableStateOf<OcrConfidenceSummary?>(null) }
    var loading by remember(doc.id, page, generation) { mutableStateOf(true) }
    var error by remember(doc.id, page, generation) { mutableStateOf<String?>(null) }
    val corrections = remember(doc.id, page, generation) { androidx.compose.runtime.mutableStateMapOf<Int, String>() }
    var editingWord by remember(doc.id, page, generation) { mutableStateOf<OcrConfidenceWord?>(null) }
    var applied by remember(doc.id, page, generation) { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val fallback = stringResource(R.string.ocr_review_error)

    LaunchedEffect(doc.id, page, generation) {
        loading = true
        result = null
        error = null
        try {
            val dictionary = vm.ocrDictionary(options.language)
            val response = vm.startOcrReview(doc, page, options)
            if (response != null) {
                result = parseOcrConfidenceSummary(response, page, dictionary)
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
    // a review left unapplied is let go, so the engine doesn't keep it
    androidx.compose.runtime.DisposableEffect(doc.id, page, generation) {
        onDispose { result?.review?.takeIf { it > 0 && !applied }?.let { vm.discardOcrReview(doc, it) } }
    }
    PdfSheet(onDismiss = { ctl.sheet = ViewerSheet.None }, maxHeightFraction = 0.86f) {
        SheetBackHeader(stringResource(R.string.ocr_review_title), onBack = { ctl.sheet = ViewerSheet.More }) {
            val r = result
            androidx.compose.material3.TextButton(
                enabled = r != null && r.review > 0 && r.totalWords > 0 && !applied,
                onClick = {
                    val rv = r?.review ?: return@TextButton
                    applied = true
                    vm.applyOcrReview(doc, rv, corrections.mapKeys { (w, _) -> page to w }) { ctl.sheet = ViewerSheet.None }
                },
            ) { Text(stringResource(R.string.ocr_review_apply), color = pdfColors.accent) }
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.ocr_review_scope, page), color = pdfColors.textSecondary)
            Text(stringResource(R.string.ocr_review_how), color = pdfColors.textSecondary)
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
                        val fixed = corrections[word.word]
                        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { editingWord = word },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(fixed?.ifEmpty { stringResource(R.string.ocr_review_removed) } ?: word.text, color = pdfColors.text,
                                    fontWeight = if (fixed != null) FontWeight.SemiBold else FontWeight.Normal)
                                if (fixed != null) Text(stringResource(R.string.ocr_review_was, word.text), color = pdfColors.textSecondary, fontSize = 12.sp)
                            }
                            Spacer(Modifier.padding(5.dp))
                            Text(word.confidence?.let { "${it.toInt()}%" }
                                ?: stringResource(R.string.ocr_review_unknown), color = if (word.suspect) pdfColors.red else pdfColors.textSecondary)
                        }
                    }
                }
            }
        }
    }
    editingWord?.let { w ->
        var value by remember(w) { mutableStateOf(corrections[w.word] ?: w.text) }
        io.github.artcraftandroid.pdfcraft.ui.PdfDialog(
            onDismiss = { editingWord = null },
            title = stringResource(R.string.ocr_review_correct),
            confirm = stringResource(R.string.common_ok),
            onConfirm = {
                if (value.trim() == w.text) corrections.remove(w.word) else corrections[w.word] = value.trim()
                editingWord = null
            },
        ) {
            io.github.artcraftandroid.pdfcraft.ui.PdfField(value, { value = it }, w.text)
            androidx.compose.material3.TextButton(
                enabled = value.isNotBlank(),
                onClick = {
                    val word = value.trim()
                    scope.launch { if (vm.addToOcrDictionary(options.language, word)) vm.tell(appText(R.string.ocr_review_added, word)) }
                },
            ) { Text(stringResource(R.string.ocr_review_add_dictionary), color = pdfColors.accent) }
        }
    }
}
