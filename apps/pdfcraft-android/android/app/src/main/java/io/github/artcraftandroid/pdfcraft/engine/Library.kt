package io.github.artcraftandroid.pdfcraft.engine

import java.io.File
import java.security.MessageDigest
import java.text.Normalizer
import org.json.JSONArray
import org.json.JSONObject

/** A folder of the library: virtual, so a file can sit in several without being moved or copied. */
data class LibraryCollection(val id: String, val name: String, val parent: String? = null, val color: Int? = null)

/** A file the user added to the library, where it is (its URI), and how it is organised. */
data class LibraryItem(
    val uri: String,
    val name: String,
    val collections: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    /** A colour label, `0xRRGGBB`. */
    val color: Int? = null,
    val added: Long = 0,
)

/** The library's state, as one JSON file. */
data class LibraryData(val collections: List<LibraryCollection> = emptyList(), val items: List<LibraryItem> = emptyList()) {
    fun item(uri: String) = items.firstOrNull { it.uri == uri }
    fun collection(id: String) = collections.firstOrNull { it.id == id }
    fun children(parent: String?) = collections.filter { it.parent == parent }.sortedBy { it.name.lowercase() }
    fun allTags(): List<String> = items.flatMap { it.tags }.distinct().sortedBy { it.lowercase() }

    /** The items in [id] or in any collection under it. */
    fun itemsIn(id: String): List<LibraryItem> {
        val ids = HashSet<String>()
        fun walk(c: String) { if (ids.add(c)) collections.filter { it.parent == c }.forEach { walk(it.id) } }
        walk(id)
        return items.filter { it.collections.any(ids::contains) }
    }

    /** The collection's names from the top, for a breadcrumb. */
    fun path(id: String): List<LibraryCollection> {
        val out = ArrayList<LibraryCollection>()
        var c = collection(id)
        val seen = HashSet<String>()
        while (c != null && seen.add(c.id)) { out.add(0, c); c = c.parent?.let(::collection) }
        return out
    }

    fun toJson(): JSONObject = JSONObject()
        .put("collections", JSONArray().apply {
            collections.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("parent", it.parent ?: JSONObject.NULL).put("color", it.color ?: JSONObject.NULL)) }
        })
        .put("items", JSONArray().apply {
            items.forEach {
                put(JSONObject().put("uri", it.uri).put("name", it.name).put("collections", JSONArray(it.collections.sorted()))
                    .put("tags", JSONArray(it.tags.sorted())).put("color", it.color ?: JSONObject.NULL).put("added", it.added))
            }
        })

    companion object {
        fun fromJson(o: JSONObject): LibraryData {
            val cols = o.optJSONArray("collections")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val c = a.optJSONObject(i) ?: return@mapNotNull null
                    LibraryCollection(c.optString("id").ifEmpty { return@mapNotNull null }, c.optString("name"), c.optString("parent").takeIf { !c.isNull("parent") && it.isNotEmpty() }, if (c.isNull("color")) null else c.optInt("color"))
                }
            } ?: emptyList()
            val ids = cols.map { it.id }.toSet()
            val items = o.optJSONArray("items")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val c = a.optJSONObject(i) ?: return@mapNotNull null
                    fun set(k: String) = c.optJSONArray(k)?.let { s -> (0 until s.length()).map { s.optString(it) }.filter { it.isNotEmpty() }.toSet() } ?: emptySet()
                    LibraryItem(c.optString("uri").ifEmpty { return@mapNotNull null }, c.optString("name"), set("collections").filter(ids::contains).toSet(), set("tags"), if (c.isNull("color")) null else c.optInt("color"), c.optLong("added"))
                }
            } ?: emptyList()
            // a parent that no longer exists makes the collection top-level
            return LibraryData(cols.map { if (it.parent != null && it.parent !in ids) it.copy(parent = null) else it }, items)
        }
    }
}

/** The library, kept in a JSON file in the app's private files. Small synchronous I/O: callers stay off the main thread. */
class Library(private val file: File) {
    private val lock = Any()

    fun load(): LibraryData = synchronized(lock) { read() }

    fun update(f: (LibraryData) -> LibraryData): LibraryData = synchronized(lock) { f(read()).also(::write) }

    fun addCollection(name: String, parent: String?, id: String = newId()): LibraryData = update { d ->
        d.copy(collections = d.collections + LibraryCollection(id, name.trim().take(80).ifEmpty { "Collection" }, parent?.takeIf { d.collection(it) != null }))
    }

    fun renameCollection(id: String, name: String) = update { d -> d.copy(collections = d.collections.map { if (it.id == id) it.copy(name = name.trim().take(80).ifEmpty { it.name }) else it }) }

    /** Remove [id] and the collections under it; files stay where they are (only their membership goes). */
    fun deleteCollection(id: String) = update { d ->
        val gone = HashSet<String>()
        fun walk(c: String) { if (gone.add(c)) d.collections.filter { it.parent == c }.forEach { walk(it.id) } }
        walk(id)
        d.copy(collections = d.collections.filter { it.id !in gone }, items = d.items.map { it.copy(collections = it.collections - gone) })
    }

    fun addItem(uri: String, name: String, now: Long, collection: String? = null) = update { d ->
        val old = d.item(uri)
        val item = (old ?: LibraryItem(uri, name, added = now)).let { if (collection != null && d.collection(collection) != null) it.copy(collections = it.collections + collection) else it }
        d.copy(items = d.items.filter { it.uri != uri } + item)
    }

    fun setItem(uri: String, f: (LibraryItem) -> LibraryItem) = update { d -> d.copy(items = d.items.map { if (it.uri == uri) f(it) else it }) }

    fun removeItem(uri: String) = update { d -> d.copy(items = d.items.filter { it.uri != uri }) }

    private fun read(): LibraryData = runCatching { LibraryData.fromJson(JSONObject(file.readText())) }.getOrElse { LibraryData() }

    private fun write(d: LibraryData) {
        val text = d.toJson().toString()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { file.writeText(text); tmp.delete() }
    }

    companion object {
        fun newId(): String = java.util.UUID.randomUUID().toString().take(12)
        fun cleanTag(t: String): String = t.trim().removePrefix("#").replace(Regex("\\s+"), " ").take(40)
    }
}

/** Where a search hit is. */
enum class HitKind { Text, Comment, Outline, Name }

data class SearchHit(val uri: String, val name: String, val page: Int, val kind: HitKind, val snippet: String, val start: Int, val length: Int)

/** One document's searchable text: its pages, outline titles and comments. */
data class IndexedDoc(
    val uri: String,
    val name: String,
    /** What the file was when indexed (size and modified time), to know when to index again. */
    val signature: String,
    val pages: List<String>,
    val outline: List<Pair<Int, String>> = emptyList(),
    /** (page, text, unresolved) of each comment and reply. */
    val comments: List<Triple<Int, String, Boolean>> = emptyList(),
) {
    val unresolvedComments: Int get() = comments.count { it.third }

    fun toJson(): JSONObject = JSONObject().put("uri", uri).put("name", name).put("sig", signature)
        .put("pages", JSONArray(pages))
        .put("outline", JSONArray().apply { outline.forEach { (p, t) -> put(JSONArray().put(p).put(t)) } })
        .put("comments", JSONArray().apply { comments.forEach { (p, t, u) -> put(JSONArray().put(p).put(t).put(u)) } })

    companion object {
        fun fromJson(o: JSONObject): IndexedDoc? {
            val uri = o.optString("uri").ifEmpty { return null }
            val pages = o.optJSONArray("pages")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
            val outline = o.optJSONArray("outline")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONArray(it)?.let { e -> e.optInt(0) to e.optString(1) } } } ?: emptyList()
            val comments = o.optJSONArray("comments")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONArray(it)?.let { e -> Triple(e.optInt(0), e.optString(1), e.optBoolean(2)) } } } ?: emptyList()
            return IndexedDoc(uri, o.optString("name"), o.optString("sig"), pages, outline, comments)
        }
    }
}

/**
 * The library's full-text index: one JSON file per document in a private folder, searched by a
 * plain scan of folded (lower-case, accent-free) text. Bounded by [maxChars] per document so a
 * huge PDF cannot fill the phone.
 */
class LibraryIndex(private val dir: File, private val maxChars: Int = 4_000_000) {
    private fun fileFor(uri: String) = File(dir, sha(uri) + ".json")

    fun has(uri: String, signature: String): Boolean = runCatching { JSONObject(fileFor(uri).readText()).optString("sig") == signature }.getOrDefault(false)

    fun get(uri: String): IndexedDoc? = runCatching { IndexedDoc.fromJson(JSONObject(fileFor(uri).readText())) }.getOrNull()

    fun put(doc: IndexedDoc) {
        dir.mkdirs()
        var left = maxChars
        val pages = doc.pages.map { p -> val t = p.take(left.coerceAtLeast(0)); left -= t.length; t }
        val text = doc.copy(pages = pages).toJson().toString()
        val f = fileFor(doc.uri)
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
    }

    fun remove(uri: String) { fileFor(uri).delete() }

    /** Remove every index file but those of [keep]. */
    fun retain(keep: Set<String>) {
        val names = keep.map { sha(it) + ".json" }.toSet()
        dir.listFiles()?.forEach { if (it.name !in names) it.delete() }
    }

    fun wipe() { dir.deleteRecursively() }

    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0

    /** Hits for [query] in the documents of [uris] (all indexed ones when null), at most [limit]. */
    fun search(query: String, uris: Collection<String>?, limit: Int = 200): List<SearchHit> {
        val q = fold(query.trim())
        if (q.length < 2) return emptyList()
        val files = uris?.map(::fileFor) ?: dir.listFiles { f -> f.extension == "json" }?.toList().orEmpty()
        val hits = ArrayList<SearchHit>()
        for (f in files) {
            val doc = runCatching { IndexedDoc.fromJson(JSONObject(f.readText())) }.getOrNull() ?: continue
            hits += searchDoc(doc, q, limit - hits.size)
            if (hits.size >= limit) break
        }
        return hits
    }

    companion object {
        /** Lower case without accents, keeping each character's position (one char in, one out). */
        fun fold(s: String): String {
            val sb = StringBuilder(s.length)
            for (ch in s) {
                val d = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD)
                sb.append((d.firstOrNull { Character.getType(it) != Character.NON_SPACING_MARK.toInt() } ?: ch).lowercaseChar())
            }
            return sb.toString()
        }

        fun snippet(text: String, at: Int, length: Int, room: Int = 60): Triple<String, Int, Int> {
            val from = (at - room).coerceAtLeast(0).let { s -> text.lastIndexOf(' ', s).takeIf { it in (s - 15)..s }?.plus(1) ?: s }
            val to = (at + length + room).coerceAtMost(text.length).let { e -> text.indexOf(' ', e).takeIf { it in e..(e + 15) } ?: e }
            val prefix = if (from > 0) "…" else ""
            val raw = text.substring(from, to).replace('\n', ' ')
            return Triple(prefix + raw + if (to < text.length) "…" else "", prefix.length + at - from, length)
        }

        /** Hits for folded query [q] in [doc]: one per page (its first match), then outline and comments. */
        fun searchDoc(doc: IndexedDoc, q: String, limit: Int): List<SearchHit> {
            val out = ArrayList<SearchHit>()
            if (fold(doc.name).contains(q)) out += SearchHit(doc.uri, doc.name, 0, HitKind.Name, doc.name, fold(doc.name).indexOf(q), q.length)
            for ((p, title) in doc.outline) {
                if (out.size >= limit) return out
                val i = fold(title).indexOf(q)
                if (i >= 0) out += SearchHit(doc.uri, doc.name, p, HitKind.Outline, title, i, q.length)
            }
            for ((p, text, _) in doc.comments) {
                if (out.size >= limit) return out
                val i = fold(text).indexOf(q)
                if (i >= 0) { val (s, a, l) = snippet(text, i, q.length); out += SearchHit(doc.uri, doc.name, p, HitKind.Comment, s, a, l) }
            }
            for ((p, text) in doc.pages.withIndex()) {
                if (out.size >= limit) return out
                val i = fold(text).indexOf(q)
                if (i >= 0) { val (s, a, l) = snippet(text, i, q.length); out += SearchHit(doc.uri, doc.name, p, HitKind.Text, s, a, l) }
            }
            return out
        }

        private fun sha(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
    }
}
