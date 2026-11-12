package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.app.Application
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.core.MobileException
import io.github.artcraftandroid.pdfcraft.engine.DocumentFiles
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import io.github.artcraftandroid.pdfcraft.engine.Engine
import io.github.artcraftandroid.pdfcraft.engine.userMessage
import io.github.artcraftandroid.pdfcraft.plural
import io.github.artcraftandroid.pdfcraft.text
import io.github.artcraftandroid.pdfcraft.ui.viewer.FillElement
import io.github.artcraftandroid.pdfcraft.ui.viewer.LinkFlash
import io.github.artcraftandroid.pdfcraft.ui.viewer.PagePoint
import io.github.artcraftandroid.pdfcraft.ui.viewer.TextPrompt
import io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerTool
import io.github.artcraftandroid.pdfcraft.ui.viewer.editEvent
import io.github.artcraftandroid.pdfcraft.ui.viewer.engineType
import io.github.artcraftandroid.pdfcraft.ui.viewer.hex
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SigningController internal constructor(private val vm: AppViewModel) {
    private val app: Application get() = vm.getApplication()

    // ---- Fill & Sign -------------------------------------------------------------------------

    /** The element a tap places. */
    var fillElement by mutableStateOf(io.github.artcraftandroid.pdfcraft.ui.viewer.FillElement.Text)

    /** Fill & Sign text waiting to be typed. */
    var fillTextPrompt by mutableStateOf<TextPrompt?>(null)

    val signatures = io.github.artcraftandroid.pdfcraft.ui.viewer.Signatures(app.filesDir)

    /** Bumped when a saved signature is cleared or replaced, so the panel redraws. */
    var signaturesChanged by mutableStateOf(0)

    /** A signature or initials being made. */
    var signaturePad by mutableStateOf<io.github.artcraftandroid.pdfcraft.ui.viewer.SignaturePad?>(null)

    /** A signature taken from an image (Image tab), before Done. */
    var importedSignature by mutableStateOf<android.graphics.Bitmap?>(null)

    /** Place [element] at [at] on its page. */
    fun placeFill(doc: OpenDocument, element: io.github.artcraftandroid.pdfcraft.ui.viewer.FillElement, at: PagePoint, text: String? = null) {
        editEvent(doc, "sign_fill_place", "element" to element.engineType)
        if (element == FillElement.Box) {
            // a rounded box around what was tapped, in the Fill & Sign colour: drawn as one ink stroke
            val r = vm.settings.toolPrefs.fillTextSize
            vm.runTool(doc, "comment_add", mapOf(
                "page" to at.page + 1, "type" to "ink",
                "strokes" to listOf(roundedBox(at.x, at.y, r * 4f, r * 2f, r * 0.6f)),
                "color" to androidx.compose.ui.graphics.Color(vm.settings.toolPrefs.fillColor).hex(), "width" to 1.5, "author" to vm.commenting.author,
            ))
            return
        }
        val args = mutableMapOf<String, Any?>(
            "page" to at.page + 1, "type" to element.engineType, "at" to listOf(at.x.toDouble(), at.y.toDouble()), "author" to vm.commenting.author,
        )
        when (element) {
            FillElement.Text -> args["text"] = text ?: return
            FillElement.Signature, FillElement.Initials -> args["path"] = signatures.fileFor(element == FillElement.Initials).absolutePath
            else -> Unit
        }
        vm.runTool(doc, "fill_sign_add", args)
    }

    /** The outline of a [w] x [h] box with corners of radius [r], centred on ([cx], [cy]), as stroke points. */
    private fun roundedBox(cx: Float, cy: Float, w: Float, h: Float, r: Float): List<List<Double>> {
        val pts = mutableListOf<List<Double>>()
        val l = cx - w / 2f
        val t = cy - h / 2f
        val corners = listOf(Triple(l + w - r, t + r, -90f), Triple(l + w - r, t + h - r, 0f), Triple(l + r, t + h - r, 90f), Triple(l + r, t + r, 180f))
        for ((x, y, start) in corners) {
            for (k in 0..6) {
                val a = Math.toRadians((start + k * 15f).toDouble())
                pts += listOf(x + r * Math.cos(a), y + r * Math.sin(a))
            }
        }
        pts += pts.first()
        return pts
    }

    fun postFillText(prompt: TextPrompt, text: String) {
        fillTextPrompt = null
        if (text.isBlank()) return
        placeFill(prompt.doc, io.github.artcraftandroid.pdfcraft.ui.viewer.FillElement.Text, prompt.at, text)
    }

    /** A signature or initials was made: save it (if asked) and place it where the page was tapped. */
    fun finishSignature(pad: io.github.artcraftandroid.pdfcraft.ui.viewer.SignaturePad, bitmap: android.graphics.Bitmap, save: Boolean) {
        signaturePad = null
        val imported = importedSignature != null
        importedSignature = null
        vm.viewModelScope.launch {
            val target = if (save) signatures.fileFor(pad.initials) else File(app.cacheDir, "sign-${System.nanoTime()}.png")
            withContext(Dispatchers.IO) { target.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
            signaturesChanged++
                fillElement = if (pad.initials) FillElement.Initials else FillElement.Signature
            val doc = pad.doc
            val at = pad.at
            if (doc != null) editEvent(doc, "sign_drawn", "initials" to pad.initials, "saved" to save, "placed" to (at != null), "imported" to imported)
            if (doc != null && at != null) {
                vm.runTool(doc, "fill_sign_add", mapOf(
                    "page" to at.page + 1, "type" to if (pad.initials) "initials" else "signature",
                    "at" to listOf(at.x.toDouble(), at.y.toDouble()), "path" to target.absolutePath, "author" to vm.commenting.author,
                ))
            } else {
                vm.tell(text(if (pad.initials) R.string.msg_place_initials else R.string.msg_place_signature))
            }
        }
    }

    /** An image chosen for a signature: made transparent where it is near white, then shown on the pad. */
    fun importSignatureImage(uri: Uri?) {
        if (uri == null) return
        vm.viewModelScope.launch {
            importedSignature = withContext(Dispatchers.IO) {
                runCatching {
                    val src = app.contentResolver.openInputStream(uri).use { android.graphics.BitmapFactory.decodeStream(it) } ?: return@runCatching null
                    val scale = minOf(1f, 1200f / maxOf(src.width, src.height))
                    val bmp = android.graphics.Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true)
                        .copy(android.graphics.Bitmap.Config.ARGB_8888, true)
                    val px = IntArray(bmp.width * bmp.height)
                    bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    for (i in px.indices) {
                        val p = px[i]
                        val l = (((p shr 16) and 0xFF) * 3 + ((p shr 8) and 0xFF) * 6 + (p and 0xFF)) / 10
                        // paper becomes transparent, ink keeps its strength
                        val a = ((235 - l) * 255 / 120).coerceIn(0, 255)
                        px[i] = (a shl 24) or (p and 0xFFFFFF)
                    }
                    bmp.setPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    bmp
                }.getOrNull()
            }
            if (importedSignature == null) vm.tell(text(R.string.msg_image_unreadable))
        }
    }

    // ---- Digital signatures: signing with a digital ID, and what a document's signatures say ----

    /** The digital IDs the reader added or made (lazy: the engine's start may need them first). */
    val digitalIds by lazy { io.github.artcraftandroid.pdfcraft.engine.DigitalIds(app.filesDir) }

    /** The signers the reader chose to trust, given to the engine when it starts. */
    val trustedCerts by lazy { io.github.artcraftandroid.pdfcraft.engine.TrustedCertificates(app.filesDir) }

    /** The document a digital signature's box is being drawn on (Sign ▸ Sign with a digital ID). */
    var certPlacing by mutableStateOf<OpenDocument?>(null)

    /** Sign with a digital ID, open for where the signature goes. */
    var certSign by mutableStateOf<io.github.artcraftandroid.pdfcraft.ui.viewer.CertTarget?>(null)

    /** Why the last signing failed (a wrong password), shown on the Sign screen. */
    var certSignError by mutableStateOf<String?>(null)

    /** The digital ID chosen on the Sign screen. */
    var chosenId by mutableStateOf<io.github.artcraftandroid.pdfcraft.engine.DigitalId?>(null)

    /** A picked digital ID file, waiting for its password. */
    var idImport by mutableStateOf<io.github.artcraftandroid.pdfcraft.ui.viewer.IdImport?>(null)
        internal set

    /** Create a digital ID is open. */
    var idCreate by mutableStateOf(false)

    /** An empty signature field was tapped: draw a signature there, or sign it with a digital ID. */
    var signFieldChoice by mutableStateOf<Pair<OpenDocument, io.github.artcraftandroid.pdfcraft.core.WidgetInfo>?>(null)

    /** Draw the box for a new digital signature on [doc], in Fill & Sign. */
    fun startCertPlacing(doc: OpenDocument) {
        if (!doc.info.readOnlyReason.isNullOrEmpty()) {
            editEvent(doc, "sign_cert_target", "placement" to "read_only")
            vm.tell(text(R.string.msg_sign_read_only))
            return
        }
        if (doc.controller.tool != ViewerTool.FillSign) vm.enterTool(doc, ViewerTool.FillSign)
        doc.controller.signPanel = false
        vm.commenting.selectedFill = null
        certPlacing = doc
    }

    /** Sign with no box on the page (an invisible signature, on the current page): on to choosing the ID. */
    fun signInvisibly(doc: OpenDocument) {
        certPlacing = null
        editEvent(doc, "sign_cert_target", "placement" to "invisible")
        openCertSign(io.github.artcraftandroid.pdfcraft.ui.viewer.CertTarget(doc, null, doc.viewer.currentPage, null))
    }

    /**
     * View signed version: the revision signature [s] signs, saved as its own file and opened as a
     * separate document, so what was signed can be seen without the later changes.
     */
    fun openSignedVersion(doc: OpenDocument, s: io.github.artcraftandroid.pdfcraft.ui.viewer.SignatureEntry) {
        val eng = vm.engine ?: return
        vm.viewModelScope.launch {
            var copy: Int? = null
            try {
                val list = eng.tool("doc_revisions", mapOf("doc" to doc.id)).optJSONArray("revisions")
                val revisions = (0 until (list?.length() ?: 0)).mapNotNull { i ->
                    val o = list?.optJSONObject(i) ?: return@mapNotNull null
                    val by = o.optJSONArray("signed_by")
                    o.optInt("revision") to (0 until (by?.length() ?: 0)).map { by!!.optString(it) }
                }
                val revision = io.github.artcraftandroid.pdfcraft.ui.viewer.signedRevision(s.field, revisions)
                editEvent(doc, "sign_signed_version", "revisions" to revisions.size, "found" to (revision != null))
                if (revision == null) {
                    vm.tell(text(R.string.sign_no_signed_version))
                    return@launch
                }
                copy = eng.tool("doc_open_revision", mapOf("doc" to doc.id, "revision" to revision)).optInt("doc")
                val dir = File(app.cacheDir, "attachments/${System.nanoTime()}").apply { mkdirs() }
                val out = File(dir, DocumentFiles.safeName(text(R.string.sign_signed_version_name, doc.name.removeSuffix(".pdf"), s.field) + ".pdf"))
                eng.tool("doc_save", mapOf("doc" to copy, "path" to out.absolutePath, "full" to true))
                vm.open(Uri.fromFile(out))
            } catch (e: Exception) {
                vm.tell(e.userMessage)
            } finally {
                copy?.let { c -> runCatching { eng.tool("doc_close", mapOf("doc" to c, "discard_changes" to true)) } }
            }
        }
    }

    /** The box was drawn: on to choosing the ID. */
    fun placeCert(doc: OpenDocument, page: Int, rect: FloatArray) {
        certPlacing = null
        editEvent(doc, "sign_cert_target", "placement" to "box")
        openCertSign(io.github.artcraftandroid.pdfcraft.ui.viewer.CertTarget(doc, null, page, rect))
    }

    fun signFieldWithId(doc: OpenDocument, w: io.github.artcraftandroid.pdfcraft.core.WidgetInfo) {
        editEvent(doc, "sign_cert_target", "placement" to "field")
        openCertSign(io.github.artcraftandroid.pdfcraft.ui.viewer.CertTarget(doc, w.field, w.page.toInt(), null))
    }

    /** A drawn (Fill & Sign) signature in signature field [w], as a tap on it did before digital IDs. */
    fun drawSignatureInField(doc: OpenDocument, w: io.github.artcraftandroid.pdfcraft.core.WidgetInfo) {
        signaturePad = io.github.artcraftandroid.pdfcraft.ui.viewer.SignaturePad(false, doc, PagePoint(w.page.toInt(), (w.rect[0] + w.rect[2]) / 2f - 40f, (w.rect[1] + w.rect[3]) / 2f - 10f))
    }

    private fun openCertSign(t: io.github.artcraftandroid.pdfcraft.ui.viewer.CertTarget) {
        certSignError = null
        val ids = digitalIds.all
        if (chosenId !in ids) chosenId = ids.lastOrNull()
        certSign = t
    }

    fun pickDigitalId() = vm.pickFiles(FilePickKind.DigitalId)

    /** The digital ID file's password: add it (when the password opens it), or give up (null). */
    fun answerIdImport(password: String?) {
        val imp = idImport ?: return
        idImport = null
        if (password == null) {
            imp.file.delete()
            idEvent("sign_id_import", "result" to "cancelled")
            return
        }
        vm.viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { io.github.artcraftandroid.pdfcraft.core.digitalIdInfo(imp.file.absolutePath, password) }
                val problem = info.problem
                if (problem != null) {
                    imp.file.delete()
                    idEvent("sign_id_import", "result" to "cannot_sign")
                    vm.tell(text(R.string.msg_id_cannot_sign, problem))
                    return@launch
                }
                val id = withContext(Dispatchers.IO) { digitalIds.add(imp.file, info) }
                imp.file.delete()
                chosenId = id
                idEvent("sign_id_import", "result" to "added", "ids" to digitalIds.all.size)
                vm.tell(text(R.string.msg_id_added, id.name))
            } catch (e: MobileException.WrongPassword) {
                idEvent("sign_id_import", "result" to "wrong_password")
                idImport = io.github.artcraftandroid.pdfcraft.ui.viewer.IdImport(imp.file, imp.name, wrong = true)
            } catch (e: MobileException) {
                imp.file.delete()
                idEvent("sign_id_import", "result" to "unusable")
                vm.tell(text(R.string.msg_id_unusable, e.userMessage))
            }
        }
    }

    /** Make a self-signed digital ID (2048-bit RSA, valid 5 years) and choose it. */
    fun createDigitalId(name: String, email: String, organization: String, password: String) {
        val eng = vm.engine ?: return
        vm.viewModelScope.launch {
            vm.tools.busy = text(R.string.msg_busy_creating_id)
            val tmp = File(app.cacheDir, "new-id-${System.nanoTime()}.p12")
            val started = System.nanoTime()
            try {
                eng.tool("sign_id_create", mapOf(
                    "name" to name.trim(), "email" to email.trim().ifEmpty { null }, "organization" to organization.trim().ifEmpty { null },
                    "password" to password, "path" to tmp.absolutePath,
                ))
                val info = withContext(Dispatchers.IO) { io.github.artcraftandroid.pdfcraft.core.digitalIdInfo(tmp.absolutePath, password) }
                chosenId = withContext(Dispatchers.IO) { digitalIds.add(tmp, info) }
                idCreate = false
                idEvent("sign_id_create", "ok" to true, "ms" to (System.nanoTime() - started) / 1_000_000)
                vm.tell(text(R.string.msg_id_created))
            } catch (e: MobileException) {
                idEvent("sign_id_create", "ok" to false, "ms" to (System.nanoTime() - started) / 1_000_000)
                vm.tell(toolMessage(e, "sign_id_create"))
            } finally {
                tmp.delete()
                vm.tools.busy = null
            }
        }
    }

    fun removeDigitalId(id: io.github.artcraftandroid.pdfcraft.engine.DigitalId) {
        digitalIds.remove(id)
        idEvent("sign_id_remove", "ids" to digitalIds.all.size)
        if (chosenId == id) chosenId = digitalIds.all.lastOrNull()
    }

    /**
     * Sign [t]'s document with [id]: the engine signs it as it is now (with any changes not yet
     * saved) and the signed file replaces the working copy, then goes back where it came from.
     */
    fun signWithId(
        t: io.github.artcraftandroid.pdfcraft.ui.viewer.CertTarget,
        id: io.github.artcraftandroid.pdfcraft.engine.DigitalId,
        password: String,
        look: io.github.artcraftandroid.pdfcraft.ui.viewer.CertLook,
        reason: String,
        location: String,
        options: io.github.artcraftandroid.pdfcraft.ui.viewer.CertOptions = io.github.artcraftandroid.pdfcraft.ui.viewer.CertOptions(),
    ) {
        val eng = vm.engine ?: return
        val doc = t.doc
        vm.viewModelScope.launch {
            vm.tools.busy = text(R.string.msg_busy_signing)
            val started = System.nanoTime()
            val placement = when { t.field != null -> "field"; t.rect != null -> "box"; else -> "invisible" }
            fun signEvent(result: String, vararg more: Pair<String, Any?>) = editEvent(
                doc, "sign_document", "placement" to placement, "look" to look.name.lowercase(),
                "reason" to reason.isNotBlank(), "location" to location.isNotBlank(),
                "ms" to (System.nanoTime() - started) / 1_000_000, "result" to result, *more,
            )
            try {
                val args = mutableMapOf<String, Any?>(
                    "doc" to doc.id, "id" to id.file.absolutePath, "password" to password, "out" to doc.file.absolutePath,
                    "reason" to reason.trim().ifEmpty { null }, "location" to location.trim().ifEmpty { null },
                )
                args += io.github.artcraftandroid.pdfcraft.ui.viewer.certPlacementArgs(t.field, t.page, t.rect, options)
                // an invisible signature draws nothing, so it takes no picture
                if ("field" in args || "rect" in args) when (look) {
                    io.github.artcraftandroid.pdfcraft.ui.viewer.CertLook.Name -> Unit
                    io.github.artcraftandroid.pdfcraft.ui.viewer.CertLook.Typed -> args["graphic"] = mapOf("text" to id.name)
                    io.github.artcraftandroid.pdfcraft.ui.viewer.CertLook.Drawn -> args["graphic"] = mapOf("image" to signatures.signature.absolutePath)
                }
                // The signer rewrites the working copy: never while a save streams it to the
                // provider, and only after the marker that keeps it on a crash (#103).
                vm.saveMutex.withLock {
                    val src = doc.source
                    val wasPending = src != null && withContext(Dispatchers.IO) { vm.files.hasPendingWriteBack(src) }
                    val before = doc.file.length() to doc.file.lastModified()
                    vm.markBeforeWorkingCopyChanges(doc)
                    try {
                        eng.tool("sign_document", args)
                    } catch (e: Exception) {
                        // A refused signature (wrong password) leaves the copy as it was: drop the
                        // marker this attempt added. A changed copy keeps it and is saved later.
                        val unchanged = (doc.file.length() to doc.file.lastModified()) == before
                        if (src != null && !wasPending && unchanged) {
                            withContext(Dispatchers.IO) { runCatching { vm.files.clearPendingWriteBack(src) } }
                        } else {
                            doc.notWrittenBack = true
                        }
                        throw e
                    }
                    doc.notWrittenBack = true
                }
                certSign = null
                certSignError = null
                if (doc.controller.tool == ViewerTool.FillSign) vm.leaveTool(doc)
                vm.refresh(doc)
                // the working copy is signed; the original gets it now, or a copy does
                val saved = vm.save(doc, quiet = true)
                signEvent("signed", "saved" to saved)
                if (saved) {
                    vm.tell(text(R.string.msg_signed_saved))
                } else {
                    vm.tell(text(R.string.msg_signed_not_saved, doc.name), text(R.string.common_save_copy)) { vm.saveCopyAs(doc) }
                }
            } catch (e: MobileException) {
                val m = toolMessage(e, "sign_document")
                signEvent(if (m.contains("password is incorrect", ignoreCase = true)) "wrong_password" else "refused")
                certSignError = if (m.contains("password is incorrect", ignoreCase = true)) text(R.string.common_wrong_password) else m
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // e.g. the recovery marker could not be written: nothing was signed
                signEvent("error")
                vm.tell(e.userMessage)
            } finally {
                vm.tools.busy = null
            }
        }
    }

    /** An `[edit]` line about the digital IDs, not about one document: counts and results only. */
    private fun idEvent(name: String, vararg fields: Pair<String, Any?>) {
        if (Diag.enabled()) Diag.event(DiagArea.Edit, name, *fields)
    }

    /** A tool's refusal without the tool's name in front, as a sentence. */
    private fun toolMessage(e: Throwable, tool: String): String =
        e.userMessage.removePrefix("$tool: ").replaceFirstChar { it.uppercase() }.let { if (it.lastOrNull()?.isLetterOrDigit() == true) "$it." else it }

    /** What the document's signatures say (only asked for when it has signatures or signature fields). */
    internal suspend fun loadSignatures(eng: Engine, doc: OpenDocument) {
        if (!doc.info.signed && doc.info.fields == 0u) {
            doc.signatures = emptyList()
            return
        }
        val generation = doc.info.generation
        val result = try { io.github.artcraftandroid.pdfcraft.ui.viewer.parseSignatures(eng.tool("sign_list", mapOf("doc" to doc.id))) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { return }
        if (vm.document === doc && generation == doc.info.generation) doc.signatures = result
        val signed = result.filter { it.signed }
        editEvent(
            doc, "sign_list", "fields" to result.size, "signed" to signed.size,
            "valid" to signed.count { it.status == "valid" }, "unknown" to signed.count { it.status == "unknown" },
            "invalid" to signed.count { it.status == "invalid" },
        )
    }

    /** On opening a signed document: whether its signatures hold, with the details a tap away. */
    internal fun announceSignatures(doc: OpenDocument) {
        val signed = doc.signatures.filter { it.signed }
        if (signed.isEmpty()) return
        val one = signed.singleOrNull()
        // the local `text` hides the text() function here: called by its full name
        val text = when {
            signed.any { it.status == "invalid" } -> io.github.artcraftandroid.pdfcraft.text(if (one != null) R.string.msg_sig_invalid else R.string.msg_sig_some_invalid)
            signed.all { it.status == "valid" } -> if (one != null) {
                one.signer?.let { io.github.artcraftandroid.pdfcraft.text(R.string.msg_sig_valid, it) } ?: io.github.artcraftandroid.pdfcraft.text(R.string.msg_sig_valid_unknown)
            } else {
                plural(R.plurals.msg_sig_all_valid, signed.size, signed.size)
            }
            one != null -> one.signer?.let { io.github.artcraftandroid.pdfcraft.text(R.string.msg_sig_unverified, it) } ?: io.github.artcraftandroid.pdfcraft.text(R.string.msg_sig_unverified_unknown)
            else -> io.github.artcraftandroid.pdfcraft.text(R.string.msg_sig_some_unverified)
        }
        vm.tell(text, io.github.artcraftandroid.pdfcraft.text(R.string.msg_sig_details)) { doc.controller.sheet = io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerSheet.Signatures }
    }

    /** Show signature [s] on its page, flashing its box. */
    fun showSignature(doc: OpenDocument, s: io.github.artcraftandroid.pdfcraft.ui.viewer.SignatureEntry) {
        val page = s.page ?: return
        val rect = s.rect ?: return
        doc.viewer.goToPage(page, (rect[1] - 60f).coerceAtLeast(0f))
        vm.viewModelScope.launch {
            delay(250)
            doc.linkFlash = LinkFlash(page, rect)
            delay(600)
            doc.linkFlash = null
        }
    }

    /** Trust the certificate that made signature [s], on this device: signatures by it then validate. */
    fun trustSigner(doc: OpenDocument, s: io.github.artcraftandroid.pdfcraft.ui.viewer.SignatureEntry) {
        val eng = vm.engine ?: return
        val sha = s.cert?.sha256 ?: return
        vm.viewModelScope.launch {
            try {
                val der = eng.command { signerCertificate(doc.id, s.field) } ?: return@launch
                withContext(Dispatchers.IO) { trustedCerts.add(der, sha) }
                applyTrust(eng)
                editEvent(doc, "sign_trust", "trusted" to true, "count" to withContext(Dispatchers.IO) { trustedCerts.files().size })
                loadSignatures(eng, doc)
                vm.tell(s.signer?.let { text(R.string.msg_signer_trusted, it) } ?: text(R.string.msg_signer_trusted_unknown))
            } catch (e: MobileException) {
                vm.tell(e.userMessage)
            }
        }
    }

    fun untrustSigner(doc: OpenDocument, s: io.github.artcraftandroid.pdfcraft.ui.viewer.SignatureEntry) {
        val eng = vm.engine ?: return
        val sha = s.cert?.sha256 ?: return
        vm.viewModelScope.launch {
            withContext(Dispatchers.IO) { trustedCerts.remove(sha) }
            runCatching { applyTrust(eng) }
            editEvent(doc, "sign_trust", "trusted" to false, "count" to withContext(Dispatchers.IO) { trustedCerts.files().size })
            loadSignatures(eng, doc)
        }
    }

    /** Give the engine the trusted signers' certificates (it revalidates open documents). */
    internal suspend fun applyTrust(eng: Engine) {
        val paths = withContext(Dispatchers.IO) { trustedCerts.files().map { it.absolutePath } }
        eng.tool("sign_trust", mapOf("clear" to true, "paths" to paths))
    }
}
