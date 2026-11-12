package io.github.artcraftandroid.pdfcraft.ui.home

import android.content.Context
import android.net.Uri
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The bundled sample, so a first-time user can try the tools without a file of their own (#202, #345). */
internal object SampleDocument {
    const val ASSET = "sample/sample.pdf"
    const val FILE_NAME = "PdfCraft sample.pdf"

    /** Where the user's own copy of the sample lives: app storage, so edits save in place. */
    fun target(context: Context): File = File(File(context.filesDir, "sample"), FILE_NAME)

    /**
     * Copies the sample to [target], replacing an older copy unless [keep] says it is open.
     * Returns false when the copy could not be written.
     */
    fun copy(context: Context, target: File, keep: Boolean): Boolean {
        if (keep && target.isFile) return true
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, "${target.name}.part")
        context.assets.open(ASSET).use { input -> part.outputStream().use { input.copyTo(it) } }
        return part.renameTo(target) || run { part.delete(); false }
    }
}

/** Open a fresh copy of the sample PDF from an empty Home. */
fun AppViewModel.openSample() {
    val app = getApplication<android.app.Application>()
    val target = SampleDocument.target(app)
    val uri = Uri.fromFile(target)
    // an open copy is never overwritten under its own tab: open() brings that tab to the front
    val alreadyOpen = document?.source == uri || tabs.tabs.any { it.doc?.source == uri }
    viewModelScope.launch {
        val ok = try {
            withContext(Dispatchers.IO) { SampleDocument.copy(app, target, keep = alreadyOpen) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Diag.failure(DiagArea.Home, "sample_copy", e)
            false
        }
        Diag.event(DiagArea.Home, "sample_open", "ok" to ok, "already_open" to alreadyOpen)
        if (ok) open(uri) else tell(app.getString(R.string.home_sample_failed))
    }
}
