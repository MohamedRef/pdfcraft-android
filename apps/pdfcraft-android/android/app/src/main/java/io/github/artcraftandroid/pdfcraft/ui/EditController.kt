package io.github.artcraftandroid.pdfcraft.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.core.MobileException
import io.github.artcraftandroid.pdfcraft.engine.Engine
import io.github.artcraftandroid.pdfcraft.engine.userMessage
import io.github.artcraftandroid.pdfcraft.text
import io.github.artcraftandroid.pdfcraft.ui.viewer.PagePoint
import io.github.artcraftandroid.pdfcraft.ui.viewer.TextPrompt
import io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerTool
import io.github.artcraftandroid.pdfcraft.ui.viewer.prepareGroupedText
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class EditController internal constructor(private val vm: AppViewModel) {
    private val app: Application get() = vm.getApplication()

    // ---- Edit PDF ----------------------------------------------------------------------------

    var editMode by mutableStateOf(io.github.artcraftandroid.pdfcraft.ui.viewer.EditMode.Select)

    /** Each page's paragraphs (0-based page), read when Edit PDF shows the page. */
    val paragraphs = androidx.compose.runtime.mutableStateMapOf<Int, List<io.github.artcraftandroid.pdfcraft.ui.viewer.Paragraph>>()
    private var paragraphsGen = -1L

    /** The paragraph tapped once (outlined), and the one open in the editor. */
    var editSelection by mutableStateOf<io.github.artcraftandroid.pdfcraft.ui.viewer.Paragraph?>(null)
    var editing by mutableStateOf<io.github.artcraftandroid.pdfcraft.ui.viewer.Paragraph?>(null)

    suspend fun loadParagraphs(doc: OpenDocument, page: Int) {
        val eng = vm.engine ?: return
        if (paragraphsGen != doc.info.generation.toLong()) {
            paragraphs.clear()
            paragraphsGen = doc.info.generation.toLong()
        }
        if (paragraphs.containsKey(page)) return
        val r = runCatching { eng.tool("text_paragraphs", mapOf("doc" to doc.id, "page" to page + 1)) }.getOrNull() ?: return
        val arr = r.optJSONArray("paragraphs") ?: return
        paragraphs[page] = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val rect = o.optJSONArray("rect") ?: return@mapNotNull null
            val font = o.optString("font")
            io.github.artcraftandroid.pdfcraft.ui.viewer.Paragraph(
                page = page, number = o.optInt("paragraph"), text = o.optString("text"),
                box = FloatArray(4) { k -> rect.optDouble(k).toFloat() }, font = font, size = o.optDouble("size", 12.0).toFloat(),
                bold = if (o.has("bold")) o.optBoolean("bold") else font.contains("Bold", true) || font.contains("Black", true) || font.contains("Heavy", true),
                italic = if (o.has("italic")) o.optBoolean("italic") else font.contains("Italic", true) || font.contains("Oblique", true),
                color = o.optString("color").takeIf { it.startsWith("#") },
            )
        }
        editSelection?.takeIf { it.page == page }?.let { sel -> editSelection = paragraphs[page]?.firstOrNull { it.number == sel.number } }
        editing?.takeIf { it.page == page }?.let { sel -> editing = paragraphs[page]?.firstOrNull { it.number == sel.number } }
    }

    /** A tap in Edit PDF: finish typing, place new text, or select the paragraph or picture under it. */
    fun editTap(doc: OpenDocument, at: PagePoint?) {
        editing?.let { commitInlineEdit(doc) }
        editImage = null
        imageEdit.crop = null
        if (at == null) {
            editSelection = null
            return
        }
        if (editMode == io.github.artcraftandroid.pdfcraft.ui.viewer.EditMode.AddText) {
            editMode = io.github.artcraftandroid.pdfcraft.ui.viewer.EditMode.Select
            editSelection = null
            addTextPrompt = TextPrompt(doc, at)
            return
        }
        if (editMode == io.github.artcraftandroid.pdfcraft.ui.viewer.EditMode.AddImage) {
            editMode = io.github.artcraftandroid.pdfcraft.ui.viewer.EditMode.Select
            editSelection = null
            addImageAt(doc, at)
            return
        }
        val hit = paragraphs[at.page].orEmpty().firstOrNull { p -> at.x in (p.box[0] - 4)..(p.box[2] + 4) && at.y in (p.box[1] - 4)..(p.box[3] + 4) }
        editSelection = hit
        if (hit == null) selectImageAt(doc, at)
    }

    /** The picture selected in Edit PDF. */
    var editImage by mutableStateOf<io.github.artcraftandroid.pdfcraft.ui.viewer.EditImage?>(null)

    /** The picture tools' state: a crop being framed, the Rotate dialog (#161). */
    val imageEdit = io.github.artcraftandroid.pdfcraft.ui.viewer.ImageEditState()

    /** Editing existing text: document fonts, spacing and the overflow warning (#167). */
    val textFormat = io.github.artcraftandroid.pdfcraft.ui.viewer.TextFormatState()

    /** Several objects selected together in Edit PDF, and the copied count (#162). */
    val objectSelect = io.github.artcraftandroid.pdfcraft.ui.viewer.ObjectSelectState()

    /** Formatting the engine doesn't report back, kept per paragraph (page to number) while editing. */
    val editFont = androidx.compose.runtime.mutableStateMapOf<Pair<Int, Int>, String>()
    val editAlign = androidx.compose.runtime.mutableStateMapOf<Pair<Int, Int>, String>()
    val editUnderline = androidx.compose.runtime.mutableStateMapOf<Pair<Int, Int>, Boolean>()

    /** Typing in a paragraph: whether it opened with everything selected, and the text so far. */
    var editSelectAll = false
    var editDraft: String? = null
    var editTextSelection: Pair<Int, Int>? = null
    var editComposing by mutableStateOf(false)

    fun startInlineEdit(para: io.github.artcraftandroid.pdfcraft.ui.viewer.Paragraph, selectAll: Boolean) {
        editSelectAll = selectAll
        editDraft = para.text
        editComposing = false
        editTextSelection = if (selectAll) 0 to para.text.length else para.text.length to para.text.length
        editSelection = para
        editing = para
    }

    /** Write the paragraph being typed in into the page (when it changed) and stop typing. */
    fun commitInlineEdit(doc: OpenDocument) {
        val para = editing ?: return
        val text = editDraft ?: para.text
        editing = null
        editDraft = null
        editTextSelection = null
        editComposing = false
        if (text != para.text) vm.runTool(doc, "text_edit", mapOf("page" to para.page + 1, "paragraph" to para.number, "text" to text))
    }

    private fun paraArgs(para: io.github.artcraftandroid.pdfcraft.ui.viewer.Paragraph) = mapOf<String, Any?>("page" to para.page + 1, "paragraph" to para.number)

    /** Make [para] a list of [style] (or plain again), with any typing so far. */
    fun listParagraph(doc: OpenDocument, para: io.github.artcraftandroid.pdfcraft.ui.viewer.Paragraph, style: io.github.artcraftandroid.pdfcraft.ui.viewer.ListStyle) {
        val typing = editing != null && editing?.number == para.number && editing?.page == para.page
        val base = if (typing) editDraft ?: para.text else para.text
        val text = io.github.artcraftandroid.pdfcraft.ui.viewer.ListStyle.apply(base, style)
        if (text == base) return
        if (typing) editDraft = text
        vm.runTool(doc, "text_edit", paraArgs(para) + ("text" to text))
    }

    /** A formatting change to [para], applied to the page at once (with any typing so far). */
    fun styleParagraph(doc: OpenDocument, para: io.github.artcraftandroid.pdfcraft.ui.viewer.Paragraph, style: Map<String, Any?>) {
        val typed = editDraft?.takeIf { editing != null && it != para.text }
        vm.runTool(doc, "text_edit", paraArgs(para) + style + (if (typed != null) mapOf("text" to typed) else emptyMap()))
    }

    /** Unicode script toggle for selected text or a paragraph; no uncommitted draft is lost. */
    fun scriptParagraph(doc: OpenDocument, para: io.github.artcraftandroid.pdfcraft.ui.viewer.Paragraph, script: String) {
        val typing = editing
        if (script !in setOf("superscript", "subscript") || (typing != null &&
            (typing.number != para.number || typing.page != para.page || editDraft != para.text || editComposing)) || vm.document !== doc ||
            !doc.writable || doc.info.signed || doc.info.readOnlyReason != null) {
            vm.tell(text(R.string.edit_script_unavailable))
            return
        }
        val range = if (typing != null) {
            val (anchor, focus) = editTextSelection ?: (0 to 0)
            try { io.github.artcraftandroid.pdfcraft.engine.nativeUtf8Range(para.text, anchor, focus) }
            catch (_: IllegalArgumentException) { vm.tell(text(R.string.edit_script_unavailable)); return }
        } else null
        // Only an unchanged draft reaches here, so dismissing the inline editor loses no typing.
        editing = null
        editDraft = null
        editTextSelection = null
        editComposing = false
        val generation = doc.info.generation
        vm.job(text(R.string.edit_script_applying)) { eng ->
            if (vm.document !== doc || editing != null || !doc.writable) {
                vm.tell(text(R.string.edit_script_unavailable))
                return@job
            }
            val raw = eng.command {
                val current = document(doc.id)
                require(current.generation == generation && !current.signed && current.readOnlyReason == null) {
                    text(R.string.edit_script_unavailable)
                }
                if (range != null) {
                    val listed = JSONObject(execute("text_paragraphs", Engine.toJson(
                        mapOf("doc" to doc.id, "page" to para.page + 1)).toString())).getJSONArray("paragraphs")
                    val row = (0 until listed.length()).map { listed.getJSONObject(it) }
                        .firstOrNull { it.getInt("paragraph") == para.number }
                    // A range is valid only against the exact native editor text, not display
                    // text normalized differently by PDF extraction or a stale local draft.
                    require(row != null && row.optString("editor_text", row.optString("text")) == para.text) {
                        text(R.string.edit_script_unavailable)
                    }
                }
                execute("text_script", Engine.toJson(paraArgs(para) + mapOf("doc" to doc.id, "script" to script) +
                    (range?.let { mapOf("range" to it) } ?: emptyMap())).toString())
            }
            vm.refresh(doc)
            val warnings = JSONObject(raw).optJSONArray("warnings")
            for (i in 0 until minOf(warnings?.length() ?: 0, 3)) vm.tell(warnings!!.getString(i).take(500))
        }
    }

    /** Move [para] by ([dx], [dy]) points (right and down on screen). */
    fun moveParagraph(doc: OpenDocument, para: io.github.artcraftandroid.pdfcraft.ui.viewer.Paragraph, dx: Float, dy: Float) {
        vm.runTool(doc, "text_edit", paraArgs(para) + mapOf("dx" to dx.toDouble(), "dy" to -dy.toDouble()))
    }

    /** Rewrap [para] to [width] points, moving it [shift] points right first (dragged from the left). */
    fun resizeParagraph(doc: OpenDocument, para: io.github.artcraftandroid.pdfcraft.ui.viewer.Paragraph, width: Float, shift: Float) {
        val args = paraArgs(para) + ("width" to width.toDouble()) + (if (shift != 0f) mapOf("dx" to shift.toDouble()) else emptyMap())
        vm.runTool(doc, "text_edit", args)
    }

    fun deleteParagraph(doc: OpenDocument, para: io.github.artcraftandroid.pdfcraft.ui.viewer.Paragraph) {
        editSelection = null
        editing = null
        vm.runTool(doc, "text_edit", paraArgs(para) + ("text" to ""))
    }

    /** The page's colour just outside [para], for typing over it. */
    suspend fun paperColor(doc: OpenDocument, para: io.github.artcraftandroid.pdfcraft.ui.viewer.Paragraph): androidx.compose.ui.graphics.Color? {
        val eng = vm.engine ?: return null
        val x = (para.box[0] - 4f).coerceAtLeast(0f).toInt().toUInt()
        val y = (para.box[1] - 4f).coerceAtLeast(0f).toInt().toUInt()
        val img = runCatching {
            withContext(eng.interactive) { eng.session.renderPage(doc.id, para.page.toUInt(), 1f, io.github.artcraftandroid.pdfcraft.core.TileRect(x, y, 2u, 2u)) }
        }.getOrNull() ?: return null
        val px = img.rgba
        if (px.size < 4) return null
        return androidx.compose.ui.graphics.Color(px[0].toInt() and 0xff, px[1].toInt() and 0xff, px[2].toInt() and 0xff)
    }

    private fun selectImageAt(doc: OpenDocument, at: PagePoint) {
        vm.viewModelScope.launch {
            val img = imageUnder(doc, at) ?: return@launch
            editImage = img
            // text drawn inside grouped artwork has no paragraph until the page is prepared (#167)
            if (img.kind == "form") {
                vm.tell(text(R.string.edit_grouped_text), text(R.string.edit_grouped_make_editable)) {
                    vm.prepareGroupedText(doc, at)
                }
            }
        }
    }

    /** The picture drawn under [at], topmost first, if any. */
    private suspend fun imageUnder(doc: OpenDocument, at: PagePoint): io.github.artcraftandroid.pdfcraft.ui.viewer.EditImage? {
        val eng = vm.engine ?: return null
        val r = runCatching { eng.tool("page_images", mapOf("doc" to doc.id, "page" to at.page + 1)) }.getOrNull() ?: return null
        val arr = r.optJSONArray("images") ?: return null
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.optJSONObject(i) ?: continue
            val rect = (o.optJSONArray("visible_rect") ?: o.optJSONArray("rect")) ?: continue
            val box = FloatArray(4) { k -> rect.optDouble(k).toFloat() }
            if (at.x in box[0]..box[2] && at.y in box[1]..box[3]) {
                return io.github.artcraftandroid.pdfcraft.ui.viewer.editImageFrom(at.page, o)
            }
        }
        return null
    }

    /** The menu a long press on an empty part of a page opens. */
    var pageMenu by mutableStateOf<io.github.artcraftandroid.pdfcraft.ui.viewer.PageMenu?>(null)

    /**
     * A long press where no word is: on a picture, Edit PDF with the picture selected (the
     * reference's picture menu leads to editing it); elsewhere, the page menu at that point.
     */
    fun longPressPage(doc: OpenDocument, at: PagePoint, screen: androidx.compose.ui.geometry.Offset) {
        val eng = vm.engine ?: return
        vm.viewModelScope.launch {
            val img = imageUnder(doc, at)
            if (img != null) {
                vm.enterTool(doc, io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerTool.Edit)
                if (doc.controller.tool == io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerTool.Edit) editImage = img
                return@launch
            }
            val glyphs = runCatching { withContext(eng.commands) { eng.session.glyphCount(doc.id, at.page.toUInt()) } }.getOrDefault(1u)
            pageMenu = io.github.artcraftandroid.pdfcraft.ui.viewer.PageMenu(doc, at, screen, pageHasText = glyphs > 0u)
        }
    }

    /** Put [img] in [rect] (points): a move or a resize. */
    fun placeImage(doc: OpenDocument, img: io.github.artcraftandroid.pdfcraft.ui.viewer.EditImage, rect: FloatArray) {
        editImage = img.copy(rect = rect)
        vm.runTool(doc, "image_edit", mapOf("page" to img.page + 1, "image" to img.number, "action" to "move", "rect" to rect.map { it.toDouble() }))
    }

    fun turnImage(doc: OpenDocument, img: io.github.artcraftandroid.pdfcraft.ui.viewer.EditImage, quarters: Int) {
        vm.runTool(doc, "image_edit", mapOf("page" to img.page + 1, "image" to img.number, "action" to "rotate", "quarters" to quarters)) {
            // the turned picture keeps its centre; read its new box
            selectImageAt(doc, PagePoint(img.page, (img.rect[0] + img.rect[2]) / 2f, (img.rect[1] + img.rect[3]) / 2f))
        }
    }

    fun deleteImage(doc: OpenDocument, img: io.github.artcraftandroid.pdfcraft.ui.viewer.EditImage) {
        editImage = null
        vm.runTool(doc, "image_edit", mapOf("page" to img.page + 1, "image" to img.number, "action" to "delete"))
    }

    /** New page text being typed in Edit PDF (Add text). */
    var addTextPrompt by mutableStateOf<TextPrompt?>(null)

    fun postAddText(prompt: TextPrompt, text: String) {
        addTextPrompt = null
        if (text.isBlank()) return
        vm.runTool(prompt.doc, "page_add_text", mapOf(
            "page" to prompt.at.page + 1, "text" to text, "at" to listOf(prompt.at.x.toDouble(), prompt.at.y.toDouble()),
            "size" to 12.0, "font" to "helvetica",
        ))
    }

    /** Add image: the activity picks a picture, then [addImage] places it on the current page. */
    val imagePicks = Channel<OpenDocument>(Channel.BUFFERED)
    internal var imageFor: OpenDocument? = null

    /** Where the picture being picked goes (centred there); null: the current page as before. */
    internal var imageAt: PagePoint? = null

    fun pickEditImage(doc: OpenDocument) {
        imageFor = doc
        imagePicks.trySend(doc)
    }

    /** Add image at a tapped point (Edit PDF's Add image mode, or the page menu): first, from where. */
    fun addImageAt(doc: OpenDocument, at: PagePoint) {
        imageSource = doc to at
    }

    /** Asking where the picture comes from (photos or the camera), as the reference's menu does. */
    var imageSource by mutableStateOf<Pair<OpenDocument, PagePoint>?>(null)

    fun addImageFrom(camera: Boolean) {
        val (doc, at) = imageSource ?: return
        imageSource = null
        imageAt = at
        if (!camera) {
            pickEditImage(doc)
            return
        }
        imageFor = doc
        val dir = File(app.cacheDir, "camera").apply { mkdirs() }
        val f = File(dir, "image-${System.nanoTime()}.jpg")
        vm.shotFile = f
        imageShot = true
        vm.cameraShots.trySend(f)
    }

    /** The camera's photo is for Add image, not a scan. */
    internal var imageShot = false

    fun addImage(uri: Uri?) {
        val doc = imageFor ?: return
        imageFor = null
        if (uri == null) return
        vm.viewModelScope.launch {
            try {
                val name = vm.files.displayName(uri).removeSuffix(".pdf")
                val f = File(app.cacheDir, "image-${System.nanoTime()}-${name}")
                withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { text(R.string.msg_picture_unreadable) }
                        f.outputStream().use { input.copyTo(it) }
                    }
                }
                if (name.lowercase().endsWith(".gif")) {
                    vm.tell(text(R.string.msg_gif_not_supported))
                    return@launch
                }
                val eng = vm.engine ?: return@launch
                val at = imageAt
                imageAt = null
                val args = mutableMapOf<String, Any?>("doc" to doc.id, "page" to (at?.page ?: doc.viewer.currentPage) + 1, "path" to f.absolutePath)
                // centred on the tapped point, a third of the page wide, kept on the page
                val page = at?.let { doc.info.pages.getOrNull(it.page) }
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(f.absolutePath, bounds)
                if (at != null && page != null && bounds.outWidth > 0 && bounds.outHeight > 0) {
                    var w = page.width / 3f
                    var h = w * bounds.outHeight / bounds.outWidth
                    if (h > page.height / 2f) {
                        h = page.height / 2f
                        w = h * bounds.outWidth / bounds.outHeight
                    }
                    val x0 = (at.x - w / 2f).coerceIn(0f, (page.width - w).coerceAtLeast(0f))
                    val y0 = (at.y - h / 2f).coerceIn(0f, (page.height - h).coerceAtLeast(0f))
                    args["rect"] = listOf(x0, y0, x0 + w, y0 + h).map { it.toDouble() }
                }
                eng.tool("page_add_image", args)
                vm.refresh(doc)
            } catch (e: Exception) {
                vm.tell(e.userMessage)
            }
        }
    }

    // ---- Forms -------------------------------------------------------------------------------

    /** The field being filled in. */
    var fieldEdit by mutableStateOf<io.github.artcraftandroid.pdfcraft.ui.viewer.FieldEdit?>(null)

    /** The top bar's Reset and arrows while a text field is filled. */
    var fieldActions by mutableStateOf<io.github.artcraftandroid.pdfcraft.ui.viewer.FieldActions?>(null)

    /**
     * A tap at [at]: fill the form field there, if any (a check box or radio button toggles at
     * once). Returns whether there was one.
     */
    fun tapField(doc: OpenDocument, at: PagePoint?): Boolean {
        if (at == null || doc.widgets.isEmpty()) return false
        val w = doc.widgets.firstOrNull { w ->
            w.page.toInt() == at.page && w.rect.size == 4 && at.x in w.rect[0]..w.rect[2] && at.y in w.rect[1]..w.rect[3]
        } ?: return false
        if (w.readOnly) return true
        if (!doc.info.allowsFormFilling) {
            vm.tell(text(R.string.msg_tool_blocked_security))
            return true
        }
        when (w.kind) {
            "checkbox" -> fillField(doc, w.field, !w.on)
            "radio" -> w.export?.let { fillField(doc, w.field, it) }
            "text", "combo", "list" -> fieldEdit = io.github.artcraftandroid.pdfcraft.ui.viewer.FieldEdit(doc, w)
            // a signed field shows its signature's details; an empty one, a drawn or a digital signature
            "signature" -> if (doc.signatures.any { it.field == w.field && it.signed }) {
                doc.controller.sheet = io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerSheet.Signatures
            } else {
                vm.signing.signFieldChoice = doc to w
            }
            "button" -> pressButton(doc, w)
            else -> return false
        }
        return true
    }

    /**
     * A form button: does what its release action says, as far as a reader does on a phone:
     * clear the form (or the fields it names), open a web address after asking, go to a page, or
     * run its script. Sending the form online isn't done.
     */
    private fun pressButton(doc: OpenDocument, w: io.github.artcraftandroid.pdfcraft.core.WidgetInfo) {
        val eng = vm.engine ?: return
        vm.viewModelScope.launch {
            val r = runCatching { eng.tool("form_actions", mapOf("doc" to doc.id, "field" to w.field)) }.getOrNull() ?: return@launch
            val acts = r.optJSONArray("actions") ?: return@launch
            val all = (0 until acts.length()).mapNotNull { acts.optJSONObject(it) }
            val act = all.firstOrNull { it.optString("trigger") == "mouse_up" } ?: all.firstOrNull() ?: return@launch
            when {
                act.has("reset") -> {
                    val names = act.optJSONArray("reset")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                    vm.runTool(doc, "form_reset", if (names.isEmpty()) emptyMap() else mapOf("fields" to names)) { vm.tell(text(R.string.msg_form_cleared)) }
                }
                act.has("url") -> vm.linkPrompt = act.optString("url")
                act.has("page") -> doc.viewer.goToPage((act.optInt("page") - 1).coerceIn(0, doc.pageCount - 1))
                act.has("submit") -> vm.tell(text(R.string.msg_button_submit))
                act.has("javascript") -> vm.runTool(doc, "js_run", mapOf("field" to w.field, "script" to act.optString("javascript"))) { scriptOutput(doc, it) }
            }
        }
    }

    fun fillField(doc: OpenDocument, field: String, value: Any) {
        val eng = vm.engine ?: return
        vm.viewModelScope.launch {
            try {
                val r = eng.tool("form_fill", mapOf("doc" to doc.id, "values" to mapOf(field to value)))
                vm.refresh(doc)
                scriptOutput(doc, r)
            } catch (e: MobileException) {
                // the field refused the value (its format, or its own script's check): said in a
                // dialog, in the form's own words
                showScriptAlert(e.userMessage)
            }
        }
    }

    /** What a form's scripts said (their alerts), shown one at a time. */
    val scriptAlerts = androidx.compose.runtime.mutableStateListOf<String>()

    private fun showScriptAlert(message: String) {
        // a script that alerts in a loop can't queue dialogs without end
        if (message.isNotBlank() && scriptAlerts.size < MAX_SCRIPT_ALERTS) scriptAlerts.add(message)
    }

    fun dismissScriptAlert() {
        if (scriptAlerts.isNotEmpty()) scriptAlerts.removeAt(0)
    }

    /**
     * What a form's scripts said and asked of the viewer, from a tool's result: alerts are shown, a
     * page is shown, a web address asks first and printing prints; sending the form online isn't done.
     */
    private fun scriptOutput(doc: OpenDocument, r: JSONObject) {
        // a script that failed: kept out of sight, as the reference keeps it, but in the log
        r.optString("error").takeIf { it.isNotEmpty() && it != "null" }?.let { android.util.Log.w("PdfCraft", "form script: $it") }
        r.optJSONArray("alerts")?.let { a -> for (i in 0 until a.length()) showScriptAlert(a.optString(i)) }
        val requests = r.optJSONArray("requests") ?: return
        for (i in 0 until requests.length()) {
            val q = requests.optJSONObject(i) ?: continue
            when {
                q.has("page") -> doc.viewer.goToPage((q.optInt("page") - 1).coerceIn(0, doc.pageCount - 1))
                q.has("url") -> vm.linkPrompt = q.optString("url")
                q.has("print") -> vm.print(doc)
                q.has("submit") -> vm.tell(text(R.string.msg_button_submit))
            }
        }
    }

    /** Move to the next (or previous) fillable text or choice field in reading order. */
    fun nextField(doc: OpenDocument, from: io.github.artcraftandroid.pdfcraft.core.WidgetInfo, forward: Boolean) {
        vm.viewModelScope.launch {
            vm.loadAllWidgets(doc)
            if (vm.document !== doc || !doc.widgetsComplete) return@launch
            // the document's own tab order (Prepare form ▸ Tab order) leads; fields it doesn't name keep reading order
            val order = runCatching {
                val t = vm.engine?.tool("form_tab_order", mapOf("doc" to doc.id))?.optJSONArray("tab_order")
                if (t == null) emptyList() else (0 until t.length()).map { t.getString(it) }
            }.getOrDefault(emptyList())
            val fillable = io.github.artcraftandroid.pdfcraft.ui.viewer.inTabOrder(
                doc.widgets.filter { !it.readOnly && (it.kind == "text" || it.kind == "combo" || it.kind == "list") }, order,
            ) { it.field }
            val i = fillable.indexOfFirst { it.field == from.field && it.page == from.page && it.rect == from.rect }
            val next = fillable.getOrNull(if (forward) i + 1 else i - 1)
            fieldEdit = next?.let {
                if (it.page.toInt() != doc.viewer.currentPage) doc.viewer.goToPage(it.page.toInt(), (it.rect[1] - 60f).coerceAtLeast(0f))
                io.github.artcraftandroid.pdfcraft.ui.viewer.FieldEdit(doc, it)
            }
        }
    }

    // ---- Form auto-suggestions -----------------------------------------------------------------

    var formSuggestions by mutableStateOf(vm.appPrefs.getBoolean("formSuggestions", true))
        private set

    fun updateFormSuggestions(on: Boolean) {
        formSuggestions = on
        vm.appPrefs.edit().putBoolean("formSuggestions", on).apply()
    }

    /** Words typed into form fields before, newest first. */
    fun suggestionsFor(prefix: String): List<String> {
        if (!formSuggestions || prefix.isBlank()) return emptyList()
        val all = vm.appPrefs.getString("suggestions", "").orEmpty().split('\n').filter { it.isNotBlank() }
        return all.filter { it.startsWith(prefix, ignoreCase = true) && !it.equals(prefix, ignoreCase = true) }.take(3)
    }

    fun rememberSuggestion(value: String) {
        if (!formSuggestions || value.isBlank() || value.length > 200) return
        val all = vm.appPrefs.getString("suggestions", "").orEmpty().split('\n').filter { it.isNotBlank() && it != value }
        vm.appPrefs.edit().putString("suggestions", (listOf(value) + all).take(200).joinToString("\n")).apply()
    }

    fun clearFormSuggestions() {
        vm.appPrefs.edit().remove("suggestions").apply()
        vm.tell(text(R.string.msg_suggestions_cleared))
    }
}
