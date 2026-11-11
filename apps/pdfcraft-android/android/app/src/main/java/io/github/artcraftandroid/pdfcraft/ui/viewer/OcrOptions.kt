package io.github.artcraftandroid.pdfcraft.ui.viewer

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.PdfDialog
import io.github.artcraftandroid.pdfcraft.ui.PdfField
import io.github.artcraftandroid.pdfcraft.ui.PdfSwitch
import io.github.artcraftandroid.pdfcraft.ui.pdfColors

/** Which pages Recognize text reads. */
enum class OcrPages { All, Current, Range }

/**
 * Recognize text's options (#166), kept between runs: the language (one of the bundled models),
 * the pages, straighten and turn upright (deskew + auto_rotate), clean up the scan (denoise +
 * binarize), read pages that already have text again (force_ocr), and whether the result is the
 * same document made searchable or a new document of visible, editable text.
 */
data class OcrOptions(
    val language: String = "en",
    val pages: OcrPages = OcrPages.All,
    val range: String = "",
    val straighten: Boolean = false,
    val cleanUp: Boolean = false,
    val redo: Boolean = false,
    val editable: Boolean = false,
) {
    /** The preprocessing and reading arguments shared by ocr_recognize, ocr_words and ocr_recognize_files. */
    fun readingArgs(): Map<String, Any?> = mapOf(
        "language" to language, "auto_rotate" to straighten, "deskew" to straighten,
        "denoise" to cleanUp, "binarize" to cleanUp, "force_ocr" to redo,
    )

    /**
     * The `ocr_recognize` arguments for a document of [pageCount] pages showing page [current]
     * (0-based); null when the range doesn't name a page of it. [out] is where editable output goes.
     */
    fun recognizeArgs(current: Int, pageCount: Int, out: String?): Map<String, Any?>? {
        val args = readingArgs().toMutableMap()
        when (pages) {
            OcrPages.All -> Unit
            OcrPages.Current -> args["pages"] = listOf(current + 1)
            OcrPages.Range -> args["pages"] = parsePageRange(range, pageCount) ?: return null
        }
        if (editable) {
            args["output_mode"] = "editable"
            args["out"] = out ?: return null
        }
        return args
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("language", language).putString("pages", pages.name).putString("range", range)
            .putBoolean("straighten", straighten).putBoolean("clean", cleanUp).putBoolean("redo", redo).putBoolean("editable", editable)
            .apply()
    }

    companion object {
        private const val PREFS = "ocr_options"

        fun load(context: Context): OcrOptions {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return OcrOptions(
                language = p.getString("language", "en") ?: "en",
                pages = runCatching { OcrPages.valueOf(p.getString("pages", null) ?: "All") }.getOrDefault(OcrPages.All),
                range = p.getString("range", "") ?: "",
                straighten = p.getBoolean("straighten", false),
                cleanUp = p.getBoolean("clean", false),
                redo = p.getBoolean("redo", false),
                editable = p.getBoolean("editable", false),
            )
        }
    }
}

/**
 * Pages typed as "1-3, 5, 8-" (1-based; an open end runs to the last page), sorted and each once;
 * null when empty, malformed, or naming no page of a [pageCount]-page document.
 */
fun parsePageRange(text: String, pageCount: Int): List<Int>? {
    if (pageCount < 1) return null
    val out = sortedSetOf<Int>()
    for (raw in text.split(',', ';')) {
        val part = raw.trim()
        if (part.isEmpty()) continue
        val m = Regex("""^(\d*)\s*[-–]\s*(\d*)$""").find(part)
        val (a, b) = if (m != null) {
            val a = m.groupValues[1].toIntOrNull() ?: 1
            val b = m.groupValues[2].toIntOrNull() ?: pageCount
            if (m.groupValues[1].isEmpty() && m.groupValues[2].isEmpty()) return null
            a to b
        } else {
            val n = part.toIntOrNull() ?: return null
            n to n
        }
        if (a < 1 || b < a) return null
        for (p in a..minOf(b, pageCount)) out.add(p)
    }
    return out.toList().takeIf { it.isNotEmpty() }
}

/** The OCR dictionary file for [language] (one word per line), kept in the app's files. */
fun ocrDictionaryFile(context: Context, language: String): java.io.File =
    java.io.File(context.filesDir, "ocr-dictionary-${language.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "en" }}.txt")

/**
 * Recognize text's options, before it runs. [languages] are the bundled models' (code, name);
 * [batch] leaves out the page choice and editable output, which only a single document has.
 */
@Composable
fun OcrOptionsDialog(
    initial: OcrOptions,
    languages: List<Pair<String, String>>,
    pageCount: Int,
    batch: Boolean = false,
    onDismiss: () -> Unit,
    onRun: (OcrOptions) -> Unit,
) {
    val c = pdfColors
    var o by remember { mutableStateOf(initial.copy(language = initial.language.takeIf { l -> languages.any { it.first == l } } ?: languages.firstOrNull()?.first ?: "en")) }
    val rangeOk = batch || o.pages != OcrPages.Range || parsePageRange(o.range, pageCount) != null
    PdfDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.common_tool_recognize_text),
        confirm = stringResource(R.string.ocr_run),
        confirmEnabled = rangeOk,
        onConfirm = { onRun(o) },
    ) {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            if (languages.size > 1) {
                Label(stringResource(R.string.ocr_language))
                Column(Modifier.selectableGroup()) {
                    for ((code, name) in languages) Choice(name, o.language == code) { o = o.copy(language = code) }
                }
            }
            if (!batch) {
                Label(stringResource(R.string.ocr_pages))
                Column(Modifier.selectableGroup()) {
                    Choice(stringResource(R.string.ocr_pages_all), o.pages == OcrPages.All) { o = o.copy(pages = OcrPages.All) }
                    Choice(stringResource(R.string.ocr_pages_current), o.pages == OcrPages.Current) { o = o.copy(pages = OcrPages.Current) }
                    Choice(stringResource(R.string.ocr_pages_range), o.pages == OcrPages.Range) { o = o.copy(pages = OcrPages.Range) }
                }
                if (o.pages == OcrPages.Range) {
                    PdfField(o.range, { o = o.copy(range = it) }, stringResource(R.string.ocr_pages_range_hint), error = !rangeOk, modifier = Modifier.padding(vertical = 4.dp))
                }
            }
            Toggle(stringResource(R.string.ocr_straighten), o.straighten) { o = o.copy(straighten = it) }
            Toggle(stringResource(R.string.ocr_clean_up), o.cleanUp) { o = o.copy(cleanUp = it) }
            Toggle(stringResource(R.string.ocr_redo_text_pages), o.redo) { o = o.copy(redo = it) }
            if (!batch) {
                Label(stringResource(R.string.ocr_output))
                Column(Modifier.selectableGroup()) {
                    Choice(stringResource(R.string.ocr_output_searchable), !o.editable) { o = o.copy(editable = false) }
                    Choice(stringResource(R.string.ocr_output_editable), o.editable) { o = o.copy(editable = true) }
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, color = pdfColors.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = pdfColors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (selected) LucideIcons.CircleDot else LucideIcons.Circle, null, tint = if (selected) c.accent else c.icon, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = c.text, fontSize = 15.sp)
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = on, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = pdfColors.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        PdfSwitch(on, null)
    }
}
