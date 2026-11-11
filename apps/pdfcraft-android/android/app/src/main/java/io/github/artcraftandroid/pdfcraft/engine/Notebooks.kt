package io.github.artcraftandroid.pdfcraft.engine

import java.io.File
import org.json.JSONObject

/** What a notebook's next page looks like: a built-in paper, or a page the user saved as a template. */
sealed interface NotebookPage {
    data class Builtin(val template: NotebookTemplate) : NotebookPage
    /** A one-page PDF in the app's private templates folder, by file name. */
    data class User(val fileName: String) : NotebookPage

    fun toJson(): JSONObject = when (this) {
        is Builtin -> template.toJson()
        is User -> JSONObject().put("user", fileName)
    }

    companion object {
        fun fromJson(o: JSONObject?): NotebookPage? {
            o ?: return null
            if (o.has("user")) return o.optString("user").takeIf { NotebookTemplates.validName(it) }?.let(::User)
            return NotebookTemplate.fromJson(o)?.let(::Builtin)
        }
    }
}

/**
 * Which documents are notebooks and what their pages look like, so "add page" repeats the
 * template: a small JSON map from the document's URI, in the app's private files. Small
 * synchronous reads and writes; callers stay off the main thread.
 */
class Notebooks(private val file: File) {
    private val lock = Any()

    fun get(uri: String): NotebookPage? = synchronized(lock) { NotebookPage.fromJson(read().optJSONObject(uri)) }

    fun put(uri: String, page: NotebookPage) = synchronized(lock) {
        val all = read()
        all.put(uri, page.toJson())
        // a few hundred notebooks at most; drop the oldest entries past that
        while (all.length() > MAX) all.remove(all.keys().next())
        write(all)
    }

    fun remove(uri: String) = synchronized(lock) { write(read().apply { remove(uri) }) }

    private fun read(): JSONObject = runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }

    private fun write(o: JSONObject) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(file)) { file.writeText(o.toString()); tmp.delete() }
    }

    companion object { const val MAX = 500 }
}

/** The pages a user saved as templates: one-page PDFs in a private folder. */
class NotebookTemplates(val dir: File) {
    fun list(): List<String> = dir.listFiles { f -> f.isFile && f.extension == "pdf" }.orEmpty().map { it.name }.sorted()

    fun file(name: String): File? = if (validName(name)) File(dir, name).takeIf { it.isFile } else null

    /** A new file for a template called [title] (a number added when the name is taken). */
    fun newFile(title: String): File {
        dir.mkdirs()
        val base = DocumentFiles.safeName(title.ifBlank { "Template" }).removeSuffix(".pdf").take(60).ifBlank { "Template" }
        var f = File(dir, "$base.pdf")
        var i = 2
        while (f.exists()) f = File(dir, "$base ${i++}.pdf")
        return f
    }

    fun delete(name: String) { file(name)?.delete() }

    companion object {
        /** A bare file name in the folder: no path separators, no way out of it. */
        fun validName(name: String): Boolean =
            name.endsWith(".pdf") && name.length <= 80 && '/' !in name && '\\' !in name && name != ".pdf" && !name.startsWith(".")
    }
}
