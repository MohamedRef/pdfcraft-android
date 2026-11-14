package io.github.artcraftandroid.pdfcraft.ui

import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.core.MobileException
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import io.github.artcraftandroid.pdfcraft.engine.DocumentFiles
import io.github.artcraftandroid.pdfcraft.engine.Engine
import io.github.artcraftandroid.pdfcraft.engine.userMessage
import io.github.artcraftandroid.pdfcraft.text
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/*
 * Unlock PDF (#517): a copy of each chosen PDF without its password and restrictions, in
 * Downloads as `<name>_unlocked.pdf`; the files themselves stay as they are. A file that needs a
 * password to open is unlocked only with it (asked once, and it can go to the other files); no
 * password is ever guessed. A file with restrictions only is unlocked without the permissions
 * password (the owner's choice, 14 Nov). A password is never logged, kept after the tool closes,
 * or put into a name or a message.
 */

/** What came of one file. */
internal enum class UnlockResult(val id: String) {
    /** Its open password was removed (and any restrictions with it). */
    Password("unlocked"),
    /** It opened without a password; its restrictions were removed. */
    Restrictions("restrictions_removed"),
    /** It had no security: nothing to remove, nothing written. */
    NotProtected("not_protected"),
    /** Its open password was not given. */
    SkippedPassword("skipped_password"),
    /** It is signed and the person chose not to unlock it. */
    SkippedSigned("skipped_signed"),
    Failed("failed"),
    ;

    val unlocked: Boolean get() = this == Password || this == Restrictions
    val skipped: Boolean get() = this == NotProtected || this == SkippedPassword || this == SkippedSigned
}

/** One file of the list: chosen from storage ([uri]) or the document open in the viewer ([doc]). */
internal data class UnlockFile(val name: String, val uri: Uri? = null, val doc: OpenDocument? = null, val result: UnlockResult? = null, val reason: String? = null)

/** Whether a file whose security says [security] (doc_info's `security`) restricts anything. */
internal fun unlockRestricted(security: JSONObject?): Boolean {
    if (security == null || !security.optBoolean("protected")) return false
    return security.optString("printing", "high") != "high" ||
        listOf("modify", "assemble", "extract_pages", "copy", "annotate", "fill_forms", "accessibility").any { !security.optBoolean(it, true) }
}

/** The name of a file's unlocked copy: its own name without `.pdf`, then `_unlocked.pdf`. */
internal fun unlockOutName(name: String): String = DocumentFiles.safeName(name).removeSuffix(".pdf").removeSuffix(".PDF").ifBlank { "document" } + "_unlocked.pdf"

/** The counts at the end: unlocked, skipped, failed, and the failed files' names. */
internal data class UnlockSummary(val unlocked: Int, val skipped: Int, val failed: Int, val failedNames: List<String>)

internal fun unlockSummary(files: List<UnlockFile>): UnlockSummary = UnlockSummary(
    files.count { it.result?.unlocked == true },
    files.count { it.result?.skipped == true },
    files.count { it.result == UnlockResult.Failed },
    files.filter { it.result == UnlockResult.Failed }.map { it.name },
)

/** The log's `unlock_file` line: no name, no password. */
internal fun unlockFileDiag(encrypted: Boolean, hadOpenPassword: Boolean, hadRestrictions: Boolean, signed: Boolean, result: UnlockResult, ms: Long, reason: String?): Array<Pair<String, Any?>> =
    arrayOf(
        "encrypted" to encrypted, "had_open_password" to hadOpenPassword, "had_restrictions" to hadRestrictions, "signed" to signed,
        "result" to result.id, "ms" to ms, "reason" to (reason ?: "none"),
    )

/** The log's `unlock_run` line. */
internal fun unlockRunDiag(files: List<UnlockFile>, neededPassword: Int, wrongPassword: Int, ms: Long, cancelled: Boolean): Array<Pair<String, Any?>> {
    val s = unlockSummary(files)
    return arrayOf(
        "files" to files.size, "unlocked" to s.unlocked, "needed_password" to neededPassword, "wrong_password" to wrongPassword,
        "restrictions_only" to files.count { it.result == UnlockResult.Restrictions }, "skipped" to s.skipped, "failed" to s.failed,
        "ms" to ms, "cancelled" to cancelled,
    )
}

/** The question for a file's open password: [wrong] when the last one tried did not open it. */
internal class UnlockAsk(val name: String, val wrong: Boolean, val answer: CompletableDeferred<UnlockAnswer?>)

/** A password typed, and whether it goes to the other files too; null answers mean Skip. */
internal class UnlockAnswer(val password: String, val forOthers: Boolean)

/** "This file is signed": unlock it anyway (true) or skip it. */
internal class UnlockSignedAsk(val name: String, val answer: CompletableDeferred<Boolean>)

/** The tool's state, kept by the view model while it is open. */
internal class UnlockState {
    val files = mutableStateListOf<UnlockFile>()
    var running by mutableStateOf(false)
    var progress by mutableStateOf<Pair<Int, Int>?>(null)
    var summary by mutableStateOf<UnlockSummary?>(null)
    var cancelled by mutableStateOf(false)
    var asking by mutableStateOf<UnlockAsk?>(null)
    var askingSigned by mutableStateOf<UnlockSignedAsk?>(null)
    var job: Job? = null

    /** The password to try on the other files ("Use this password for the other files"); only in memory, only while the tool runs. */
    var shared: String? = null
}

/** Unlock PDF opens, with [doc] (the document open in the viewer) as its first file when given. */
internal fun AppViewModel.openUnlock(doc: OpenDocument?) {
    if (unlock.running) return
    unlock.files.clear()
    unlock.summary = null
    unlock.cancelled = false
    unlock.shared = null
    if (doc != null) unlock.files += UnlockFile(doc.title.ifBlank { "document" }.let { if (it.endsWith(".pdf", true)) it else "$it.pdf" }, doc = doc)
    tools.toolScreen = ToolScreen.Unlock
}

internal fun AppViewModel.addUnlockFiles() {
    if (unlock.running) return
    requests.open(arrayOf("application/pdf"), many = true) { uris ->
        for (u in uris.distinct()) if (unlock.files.none { it.uri == u }) unlock.files += UnlockFile(files.displayNameRaw(u).take(180), uri = u)
        unlock.summary = null
    }
}

/** The tool closes: nothing of a password stays. */
internal fun AppViewModel.closeUnlock() {
    if (unlock.running) {
        unlock.job?.cancel()
        return
    }
    unlock.shared = null
    unlock.files.clear()
    tools.toolScreen = null
}

internal fun AppViewModel.cancelUnlock() {
    unlock.job?.cancel()
}

/** Unlock every file of the list in turn: progress "N of M", Cancel between files, then the counts. */
internal fun AppViewModel.runUnlock() {
    val eng = engine ?: return
    val s = unlock
    if (s.running || s.files.isEmpty()) return
    val ctx = getApplication<android.app.Application>()
    s.running = true
    s.summary = null
    s.cancelled = false
    for (i in s.files.indices) s.files[i] = s.files[i].copy(result = null, reason = null)
    val started = SystemClock.elapsedRealtime()
    var needed = 0
    var wrong = 0
    s.job = viewModelScope.launch {
        val dir = File(ctx.cacheDir, "out-unlock-${System.nanoTime()}").apply { mkdirs() }
        var lastOut: Pair<Uri, String>? = null
        try {
            for (i in s.files.indices) {
                ensureActive()
                s.progress = (i + 1) to s.files.size
                val f = s.files[i]
                val one = unlockOne(eng, f, dir, s.files.size == 1)
                if (one.neededPassword) needed++
                wrong += one.wrongPasswords
                s.files[i] = f.copy(result = one.result, reason = one.reason)
                if (one.out != null) lastOut = one.out
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            s.cancelled = true
        } finally {
            s.asking?.answer?.cancel()
            s.askingSigned?.answer?.cancel()
            s.asking = null
            s.askingSigned = null
            s.shared = null
            s.progress = null
            s.running = false
            s.summary = unlockSummary(s.files)
            Diag.event(DiagArea.Pages, "unlock_run", *unlockRunDiag(s.files, needed, wrong, SystemClock.elapsedRealtime() - started, s.cancelled))
            withContext(NonCancellable + Dispatchers.IO) { dir.deleteRecursively() }
        }
        // one file: the usual "Saved in Downloads" with Open
        lastOut?.let { (uri, name) -> if (s.files.size == 1 && !s.cancelled) tell(text(R.string.msg_saved_downloads, name), text(R.string.common_open)) { open(uri) } }
    }
}

private class UnlockOne(val result: UnlockResult, val reason: String?, val neededPassword: Boolean, val wrongPasswords: Int, val out: Pair<Uri, String>?)

/** One file: opened (asking for its password when it needs one), checked, unlocked and saved to Downloads. */
private suspend fun AppViewModel.unlockOne(eng: Engine, f: UnlockFile, dir: File, single: Boolean): UnlockOne {
    val s = unlock
    val ctx = getApplication<android.app.Application>()
    val started = SystemClock.elapsedRealtime()
    var neededPassword = false
    var wrongPasswords = 0
    var encrypted = false
    var restricted = false
    var signed = false
    fun done(result: UnlockResult, reason: String? = null, out: Pair<Uri, String>? = null): UnlockOne {
        Diag.event(DiagArea.Pages, "unlock_file", *unlockFileDiag(encrypted, neededPassword, restricted, signed, result, SystemClock.elapsedRealtime() - started, reason?.let { "engine" }))
        return UnlockOne(result, reason, neededPassword, wrongPasswords, out)
    }
    val input = try {
        val doc = f.doc
        if (doc != null) {
            save(doc)
            savedFile(doc, "unlock")
        } else {
            copyIntoCache(ctx, f.uri ?: return done(UnlockResult.Failed, "missing"), "unlock-in", ".pdf")
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        return done(UnlockResult.Failed, e.userMessage.replace(dir.absolutePath, ""))
    }
    var copy: Long? = null
    try {
        // the open document's own password first; the one given for the other files once it asks
        var tryPassword: String? = f.doc?.password?.takeIf { it.isNotEmpty() }
        if (tryPassword != null) neededPassword = true
        var triedShared = false
        while (copy == null) {
            try {
                copy = eng.tool("doc_open", mapOf("path" to input.absolutePath, "password" to tryPassword)).optLong("doc")
            } catch (e: MobileException.NeedsPassword) {
                neededPassword = true
                val shared = s.shared
                tryPassword = if (shared != null && !triedShared) shared.also { triedShared = true }
                    else askUnlockPassword(f.name, wrong = false) ?: return done(UnlockResult.SkippedPassword)
            } catch (e: MobileException.WrongPassword) {
                neededPassword = true
                wrongPasswords++
                tryPassword = askUnlockPassword(f.name, wrong = true) ?: return done(UnlockResult.SkippedPassword)
            }
        }
        val security = eng.tool("doc_info", mapOf("doc" to copy)).optJSONObject("security")
        encrypted = security?.optBoolean("protected") == true
        restricted = unlockRestricted(security)
        if (!encrypted) return done(UnlockResult.NotProtected)
        signed = runCatching { eng.tool("sign_list", mapOf("doc" to copy)).optInt("signed") > 0 }.getOrDefault(false)
        if (signed && !askUnlockSigned(f.name)) return done(UnlockResult.SkippedSigned)
        val out = File(dir, "unlocked-${System.nanoTime()}.pdf")
        withContext(NonCancellable) {
            eng.tool("doc_unprotect", mapOf("doc" to copy, "restrictions" to true))
            eng.tool("doc_save", mapOf("doc" to copy, "path" to out.absolutePath, "full" to true))
        }
        val name = unlockOutName(f.name)
        val uri = withContext(Dispatchers.IO) { files.saveToDownloads(out, name).also { out.delete() } }
        return done(if (neededPassword) UnlockResult.Password else UnlockResult.Restrictions, out = uri to name)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        return done(UnlockResult.Failed, e.userMessage.replace(dir.absolutePath, "").take(240))
    } finally {
        copy?.let { id -> withContext(NonCancellable) { runCatching { eng.tool("doc_close", mapOf("doc" to id, "discard_changes" to true)) } } }
        if (f.doc == null) withContext(NonCancellable + Dispatchers.IO) { input.delete() }
    }
}

/** Ask for [name]'s open password; null when the person skips the file. */
private suspend fun AppViewModel.askUnlockPassword(name: String, wrong: Boolean): String? {
    val answer = CompletableDeferred<UnlockAnswer?>()
    unlock.asking = UnlockAsk(name, wrong, answer)
    val a = try {
        answer.await()
    } finally {
        unlock.asking = null
    }
    if (a != null && a.forOthers) unlock.shared = a.password
    return a?.password
}

private suspend fun AppViewModel.askUnlockSigned(name: String): Boolean {
    val answer = CompletableDeferred<Boolean>()
    unlock.askingSigned = UnlockSignedAsk(name, answer)
    return try {
        answer.await()
    } finally {
        unlock.askingSigned = null
    }
}
