package io.github.artcraftandroid.pdfcraft.ui.viewer

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.engine.userMessage
import io.github.artcraftandroid.pdfcraft.text as appText
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.BarIcon
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import io.github.artcraftandroid.pdfcraft.ui.copyIntoCache
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

/** A face the document carries that text_edit can set text in, by its page resource. */
data class DocFont(val name: String, val resource: String)

/** Line spacing (a multiple of the size) and letter spacing (points), as a paragraph is set. */
data class Spacing(val line: Float = 1.2f, val letter: Float = 0f)

const val LINE_SPACING_MIN = 0.8f
const val LINE_SPACING_MAX = 3f
const val LETTER_SPACING_MIN = -2f
const val LETTER_SPACING_MAX = 10f

/** One step of the line-spacing stepper (0.1 of the size), kept in range and to one decimal. */
fun stepLineSpacing(v: Float, up: Boolean): Float =
    (((v * 10f).roundToInt() + if (up) 1 else -1) / 10f).coerceIn(LINE_SPACING_MIN, LINE_SPACING_MAX)

/** One step of the letter-spacing stepper (half a point), kept in range. */
fun stepLetterSpacing(v: Float, up: Boolean): Float =
    (((v * 2f).roundToInt() + if (up) 1 else -1) / 2f).coerceIn(LETTER_SPACING_MIN, LETTER_SPACING_MAX)

/** A spacing figure without a needless ".0". */
fun spacingLabel(v: Float): String = if (v == v.roundToInt().toFloat()) v.roundToInt().toString() else String.format(Locale.ROOT, "%.1f", v)

/** The selectable faces of a text_fonts answer: preview-only faces can't be chosen. */
fun parseDocFonts(o: JSONObject): List<DocFont> {
    val a = o.optJSONArray("fonts") ?: JSONArray()
    return (0 until a.length()).mapNotNull { i ->
        val f = a.optJSONObject(i) ?: return@mapNotNull null
        if (f.optBoolean("preview_only")) return@mapNotNull null
        val res = f.optString("resource").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        DocFont(f.optString("font").ifEmpty { res }, res)
    }.distinctBy { it.resource }
}

/** The "name: reason" lines text_prepare gives for groups it left alone. */
fun refusedGroups(o: JSONObject): List<String> {
    val a = o.optJSONArray("refused_groups") ?: return emptyList()
    return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
}

/** Editing existing text (#167): the page's document fonts, spacing per paragraph, and whether the draft overflows. */
class TextFormatState {
    var fonts by mutableStateOf<List<DocFont>>(emptyList())
    var fontsKey: Pair<OpenDocument, Long>? = null
    val spacing = androidx.compose.runtime.mutableStateMapOf<Pair<Int, Int>, Spacing>()
    var overflow by mutableStateOf<Pair<Int, Int>?>(null)
    var measuring: Job? = null
}

/** The faces of page [page] (0-based) text_edit can use, read once per document generation. */
fun AppViewModel.loadDocFonts(doc: OpenDocument, page: Int, force: Boolean = false) {
    val eng = engine ?: return
    val key = doc to (doc.info.generation.toLong() * 100_000L + page)
    if (!force && editor.textFormat.fontsKey == key) return
    editor.textFormat.fontsKey = key
    viewModelScope.launch {
        editor.textFormat.fonts = runCatching { parseDocFonts(eng.tool("text_fonts", mapOf("doc" to doc.id, "page" to page + 1))) }.getOrDefault(emptyList())
    }
}

/** Pick a TrueType or OpenType file, load it into [para]'s page, and set the paragraph in it. */
fun AppViewModel.importFont(doc: OpenDocument, para: Paragraph) {
    val eng = engine ?: return
    val ctx = getApplication<Application>()
    requests.open(arrayOf("font/ttf", "font/otf", "font/sfnt", "application/x-font-ttf", "application/x-font-otf", "application/font-sfnt", "application/octet-stream"), false) { uris ->
        val uri = uris.firstOrNull() ?: return@open
        viewModelScope.launch {
            val name = displayName(ctx, uri)
            val suffix = if (name?.lowercase()?.endsWith(".otf") == true) ".otf" else ".ttf"
            val file = try { copyIntoCache(ctx, uri, "font", suffix) } catch (e: Exception) { tell(e.userMessage); return@launch }
            try {
                val r = eng.tool("text_font_import", mapOf("doc" to doc.id, "page" to para.page + 1, "path" to file.absolutePath))
                val res = r.optString("resource")
                val face = r.optString("font").ifEmpty { name ?: res }
                editEvent(doc, "font_import", "ok" to res.isNotEmpty(), "otf" to (suffix == ".otf"))
                if (res.isEmpty()) {
                    refresh(doc, para.page)
                    return@launch
                }
                editor.editFont.remove(para.page to para.number)
                editor.styleParagraph(doc, para, mapOf("font_resource" to res))
                tell(appText(R.string.edit_font_imported, face))
                loadDocFonts(doc, para.page, force = true)
            } catch (e: Exception) {
                tell(e.userMessage)
            } finally {
                file.delete()
            }
        }
    }
}

private fun displayName(ctx: Application, uri: Uri): String? = runCatching {
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
}.getOrNull()

/** Set [para]'s line and letter spacing. */
fun AppViewModel.spaceParagraph(doc: OpenDocument, para: Paragraph, s: Spacing) {
    editor.textFormat.spacing[para.page to para.number] = s
    editEvent(doc, "text_spacing", "line" to s.line, "letter" to s.letter)
    editor.styleParagraph(doc, para, mapOf("line_spacing" to s.line.toDouble(), "char_spacing" to s.letter.toDouble()))
}

/** A moment after typing stops, measure the draft as Apply would set it, to warn before it overflows. */
fun AppViewModel.measureDraft(doc: OpenDocument, para: Paragraph, text: String) {
    val eng = engine ?: return
    editor.textFormat.measuring?.cancel()
    val key = para.page to para.number
    if (text == para.text) {
        if (editor.textFormat.overflow == key) editor.textFormat.overflow = null
        return
    }
    editor.textFormat.measuring = viewModelScope.launch {
        delay(500)
        val r = runCatching { eng.tool("text_edit_layout", mapOf("doc" to doc.id, "page" to para.page + 1, "paragraph" to para.number, "text" to text)) }.getOrNull() ?: return@launch
        if (r.optBoolean("overflow") && editor.textFormat.overflow != key) editEvent(doc, "text_overflow", "page" to para.page + 1)
        editor.textFormat.overflow = if (r.optBoolean("overflow")) key else editor.textFormat.overflow.takeIf { it != key }
    }
}

/**
 * Make the text inside grouped artwork on [at]'s page editable (text_prepare), then open the
 * paragraph under [at]; groups the engine leaves alone are named with its reasons.
 */
fun AppViewModel.prepareGroupedText(doc: OpenDocument, at: PagePoint) {
    editor.editImage = null
    runTool(doc, "text_prepare", mapOf("page" to at.page + 1)) { r ->
        val refused = refusedGroups(r)
        val prepared = r.optInt("prepared_groups")
        editEvent(doc, "text_prepare", "prepared" to prepared, "refused" to refused.size)
        editor.paragraphs.remove(at.page)
        viewModelScope.launch {
            editor.loadParagraphs(doc, at.page)
            val hit = editor.paragraphs[at.page].orEmpty().firstOrNull { p -> at.x in (p.box[0] - 4)..(p.box[2] + 4) && at.y in (p.box[1] - 4)..(p.box[3] + 4) }
            if (hit != null) editor.editSelection = hit
            when {
                refused.isNotEmpty() -> tell(appText(R.string.edit_grouped_refused, refused.joinToString("; ")))
                prepared > 0 -> tell(appText(R.string.edit_grouped_ready))
            }
        }
    }
}

/** The page's own faces, then Add font from file. */
@Composable
internal fun DocFontsSection(vm: AppViewModel, doc: OpenDocument, para: Paragraph) {
    val c = pdfColors
    Text(stringResource(R.string.edit_fonts_in_document), color = c.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
    for (f in vm.editor.textFormat.fonts) {
        val chosen = para.font.equals(f.name, ignoreCase = true)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) {
                vm.editor.editFont.remove(para.page to para.number)
                vm.editor.styleParagraph(doc, para, mapOf("font_resource" to f.resource))
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(f.name, color = c.label, fontSize = 17.sp, modifier = Modifier.weight(1f))
            if (chosen) Icon(LucideIcons.Check, null, tint = c.textSecondary, modifier = Modifier.size(22.dp))
        }
    }
    if (vm.editor.textFormat.fonts.isEmpty()) Text(stringResource(R.string.edit_fonts_none), color = c.textSecondary, fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp))
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { vm.importFont(doc, para) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(LucideIcons.Plus, null, tint = c.toolAccent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.edit_font_from_file), color = c.toolAccent, fontSize = 16.sp)
    }
}

/** Line spacing and letter spacing, each a stepper; every step is applied (and undoable) at once. */
@Composable
internal fun SpacingPanel(vm: AppViewModel, doc: OpenDocument, para: Paragraph) {
    val s = vm.editor.textFormat.spacing[para.page to para.number] ?: Spacing()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Stepper(
            stringResource(R.string.edit_line_spacing), spacingLabel(s.line) + "×",
            s.line > LINE_SPACING_MIN, s.line < LINE_SPACING_MAX,
            { vm.spaceParagraph(doc, para, s.copy(line = stepLineSpacing(s.line, false))) },
            { vm.spaceParagraph(doc, para, s.copy(line = stepLineSpacing(s.line, true))) },
        )
        Stepper(
            stringResource(R.string.edit_letter_spacing), stringResource(R.string.edit_points, spacingLabel(s.letter)),
            s.letter > LETTER_SPACING_MIN, s.letter < LETTER_SPACING_MAX,
            { vm.spaceParagraph(doc, para, s.copy(letter = stepLetterSpacing(s.letter, false))) },
            { vm.spaceParagraph(doc, para, s.copy(letter = stepLetterSpacing(s.letter, true))) },
        )
    }
}

@Composable
private fun Stepper(label: String, value: String, canDown: Boolean, canUp: Boolean, onDown: () -> Unit, onUp: () -> Unit) {
    val c = pdfColors
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = c.label, fontSize = 15.sp, modifier = Modifier.weight(1f))
        BarIcon(LucideIcons.Minus, stringResource(R.string.edit_spacing_less, label), onDown, enabled = canDown)
        Text(value, color = c.label, fontSize = 15.sp, modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        BarIcon(LucideIcons.Plus, stringResource(R.string.edit_spacing_more, label), onUp, enabled = canUp)
    }
}
