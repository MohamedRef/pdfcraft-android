package io.github.artcraftandroid.pdfcraft.ui

import android.content.Context
import android.net.Uri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext

/**
 * The system pickers the editing tools need (a picture, files, a folder, a place to save), asked
 * for by the view model and opened by the activity. Each request keeps its own answer handler,
 * so a result can only reach the tool that asked; a new request of the same kind replaces an
 * unanswered one, whose handler then gets nothing.
 */
class FileRequests {
    /** Save a new file named [name] of type [mime]. */
    data class Create(val name: String, val mime: String)

    /** Choose one file ([many] false) or several, of these types. */
    class Open(val mimes: Array<String>, val many: Boolean)

    val creates = Channel<Create>(Channel.BUFFERED)
    val opens = Channel<Open>(Channel.BUFFERED)
    val images = Channel<Unit>(Channel.BUFFERED)
    val folders = Channel<Unit>(Channel.BUFFERED)

    private var created: ((Uri?) -> Unit)? = null
    private var opened: ((List<Uri>) -> Unit)? = null
    private var image: ((Uri?) -> Unit)? = null
    private var folder: ((Uri?) -> Unit)? = null

    fun create(name: String, mime: String, then: (Uri?) -> Unit) {
        created = then
        creates.trySend(Create(name, mime))
    }

    fun open(mimes: Array<String>, many: Boolean, then: (List<Uri>) -> Unit) {
        opened = then
        opens.trySend(Open(mimes, many))
    }

    fun image(then: (Uri?) -> Unit) {
        image = then
        images.trySend(Unit)
    }

    fun folder(then: (Uri?) -> Unit) {
        folder = then
        folders.trySend(Unit)
    }

    fun onCreated(uri: Uri?) = created.also { created = null }?.invoke(uri)
    fun onOpened(uris: List<Uri>) = opened.also { opened = null }?.invoke(uris)
    fun onImage(uri: Uri?) = image.also { image = null }?.invoke(uri)
    fun onFolder(uri: Uri?) = folder.also { folder = null }?.invoke(uri)
}

/** Copy [uri]'s bytes into a new file in the app's cache, named `out-` (swept by CacheCleanup if left behind), [prefix], a unique part and [suffix]. */
suspend fun copyIntoCache(context: Context, uri: Uri, prefix: String, suffix: String = ""): File = withContext(Dispatchers.IO) {
    val f = File(context.cacheDir, "out-$prefix-${System.nanoTime()}$suffix")
    try {
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "unreadable" }
            f.outputStream().use { input.copyTo(it) }
        }
    } catch (e: Exception) {
        f.delete()
        throw e
    }
    f
}

/** Write [file]'s bytes to [uri] (a document the user created), replacing what was there. */
suspend fun copyOut(context: Context, file: File, uri: Uri) = withContext(Dispatchers.IO) {
    context.contentResolver.openOutputStream(uri, "wt").use { out ->
        requireNotNull(out) { "unwritable" }
        file.inputStream().use { it.copyTo(out) }
    }
}

/** The file extension's MIME type, for a file the engine wrote. */
fun mimeFor(file: File): String = when (file.extension.lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "pdf" -> "application/pdf"
    "html", "htm" -> "text/html"
    "json" -> "application/json"
    "txt" -> "text/plain"
    else -> "application/octet-stream"
}
