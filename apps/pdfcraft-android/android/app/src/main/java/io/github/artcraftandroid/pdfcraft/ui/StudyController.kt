package io.github.artcraftandroid.pdfcraft.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.plural
import io.github.artcraftandroid.pdfcraft.text
import io.github.artcraftandroid.pdfcraft.engine.DocumentFiles
import io.github.artcraftandroid.pdfcraft.engine.Engine
import io.github.artcraftandroid.pdfcraft.engine.review
import io.github.artcraftandroid.pdfcraft.engine.toBitmap
import io.github.artcraftandroid.pdfcraft.engine.userMessage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StudyController internal constructor(private val vm: AppViewModel) {
    private val app: Application get() = vm.getApplication()

    // ---- Flashcards ---------------------------------------------------------------------------

    val flashcards = io.github.artcraftandroid.pdfcraft.engine.Flashcards(File(app.filesDir, "flashcards.json"))
    private val fsrs = io.github.artcraftandroid.pdfcraft.engine.Fsrs()

    /** A card being written (from a selection, or by hand in a deck). */
    var cardDraft by mutableStateOf<CardDraft?>(null)

    /** The review session showing: its deck (a document URI, or null for every deck). */
    var reviewing by mutableStateOf<ReviewSession?>(null)

    /** Cards due now per deck, for Home and the viewer. */
    var dueCounts by mutableStateOf<Map<String, Int>>(emptyMap())
        private set

    fun refreshDue() {
        vm.viewModelScope.launch { dueCounts = withContext(Dispatchers.IO) { flashcards.dueCounts(System.currentTimeMillis()) } }
    }

    /**
     * Selection > Make card: the selected text on the front, linked to its page. With [cloze] the
     * front is the selection's line with the selected words as the blank to fill in.
     */
    fun makeCardFromSelection(doc: OpenDocument, cloze: Boolean) {
        val uri = doc.source?.toString() ?: run { vm.tell(text(R.string.cards_need_saved_file)); return }
        val eng = vm.engine ?: return
        vm.viewModelScope.launch {
            val sel = doc.selection.selection ?: return@launch
            val page = sel.range.page.toInt()
            val picked = doc.selection.text()?.trim()?.replace(Regex("\\s+"), " ") ?: return@launch
            var front = picked
            if (cloze) {
                val first = sel.rects.firstOrNull()
                val line = first?.let { r ->
                    runCatching { eng.tool("text_lines", mapOf("doc" to doc.id, "page" to page + 1)) }.getOrNull()?.optJSONArray("lines")?.let { a ->
                        val cy = (r[1] + r[3]) / 2
                        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.firstOrNull { l ->
                            val b = l.optJSONArray("rect") ?: return@firstOrNull false
                            cy >= b.optDouble(1) && cy <= b.optDouble(3)
                        }?.optString("text")
                    }
                }
                front = io.github.artcraftandroid.pdfcraft.engine.Flashcards.clozeOf(line, picked)
            }
            doc.selection.clear()
            cardDraft = CardDraft(uri, doc.info.name, page, front, "", null)
        }
    }

    fun newCard(deck: String, deckName: String) { cardDraft = CardDraft(deck, deckName, -1, "", "", null) }

    fun editCard(card: io.github.artcraftandroid.pdfcraft.engine.Flashcard) { cardDraft = CardDraft(card.deck, card.deckName, card.page, card.front, card.back, card.id) }

    fun saveCard(d: CardDraft) {
        cardDraft = null
        if (d.front.isBlank()) return
        vm.viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val old = d.id?.let { id -> flashcards.all().firstOrNull { it.id == id } }
                flashcards.put(
                    old?.copy(front = d.front.trim(), back = d.back.trim())
                        ?: io.github.artcraftandroid.pdfcraft.engine.Flashcard(
                            id = java.util.UUID.randomUUID().toString(), deck = d.deck, deckName = d.deckName,
                            front = d.front.trim(), back = d.back.trim(), page = d.page, created = System.currentTimeMillis(),
                        ),
                )
            }
            refreshDue()
            if (d.id == null) vm.tell(text(R.string.cards_saved))
            reviewing?.let { reload(it) }
        }
    }

    fun deleteCard(id: String) {
        vm.viewModelScope.launch {
            withContext(Dispatchers.IO) { flashcards.delete(id) }
            refreshDue()
            reviewing?.let { reload(it) }
        }
    }

    /** Review the cards due in [deck] (every deck when null). */
    fun startReview(deck: String?, deckName: String) {
        val r = ReviewSession(deck, deckName)
        reviewing = r
        reload(r)
    }

    private fun reload(r: ReviewSession) {
        vm.viewModelScope.launch {
            val (due, all) = withContext(Dispatchers.IO) {
                flashcards.due(System.currentTimeMillis(), r.deck) to (if (r.deck != null) flashcards.deck(r.deck) else flashcards.all())
            }
            if (reviewing === r) { r.queue.clear(); r.queue.addAll(due); r.all = all }
        }
    }

    /** The next due dates [card] would get for each rating (for the buttons). */
    fun previewIntervals(card: io.github.artcraftandroid.pdfcraft.engine.Flashcard): Map<io.github.artcraftandroid.pdfcraft.engine.Rating, Long> {
        val now = System.currentTimeMillis()
        return io.github.artcraftandroid.pdfcraft.engine.Rating.entries.associateWith { fsrs.review(card, it, now).due - now }
    }

    fun rate(r: ReviewSession, card: io.github.artcraftandroid.pdfcraft.engine.Flashcard, rating: io.github.artcraftandroid.pdfcraft.engine.Rating) {
        val next = fsrs.review(card, rating, System.currentTimeMillis())
        r.queue.remove(card)
        // forgotten cards come back at the end of this session
        if (rating == io.github.artcraftandroid.pdfcraft.engine.Rating.Again) r.queue.add(next)
        r.reviewed++
        vm.viewModelScope.launch {
            withContext(Dispatchers.IO) { flashcards.put(next) }
            refreshDue()
        }
    }

    /** Open a card's source page. */
    fun openCardSource(card: io.github.artcraftandroid.pdfcraft.engine.Flashcard) {
        reviewing = null
        if (vm.document?.source?.toString() == card.deck) { if (card.page >= 0) vm.document?.viewer?.goToPage(card.page); return }
        pendingSearch = if (card.page >= 0) card.page to "" else null
        vm.open(Uri.parse(card.deck))
    }

    /** Export a deck (or every card) as Anki-importable TSV to Downloads. */
    fun exportCards(deck: String?, deckName: String) = vm.job(text(R.string.cards_busy_export)) { _ ->
        val cards = withContext(Dispatchers.IO) { if (deck != null) flashcards.deck(deck) else flashcards.all() }
        if (cards.isEmpty()) { vm.tell(text(R.string.cards_none)); return@job }
        val out = vm.tempFile("txt")
        withContext(Dispatchers.IO) { out.writeText(io.github.artcraftandroid.pdfcraft.engine.Flashcards.toAnkiTsv(cards)) }
        vm.deliver(out, DocumentFiles.safeName(text(R.string.cards_file_name, deckName.removeSuffix(".pdf"))).removeSuffix(".pdf") + ".txt", "text/plain")
    }

    /** Import cards from a CSV/TSV/TXT file into [deck]. */
    fun importCards(deck: String, deckName: String) {
        cardImportDeck = deck to deckName
        cardImportRequests.trySend(Unit)
    }

    private var cardImportDeck: Pair<String, String>? = null
    val cardImportRequests = Channel<Unit>(Channel.CONFLATED)

    fun onCardFilePicked(uri: Uri?) {
        val (deck, name) = cardImportDeck ?: return
        cardImportDeck = null
        uri ?: return
        vm.viewModelScope.launch {
            val n = withContext(Dispatchers.IO) {
                runCatching {
                    val text = app.contentResolver.openInputStream(uri)?.use { s ->
                        String(s.readNBytes(8 * 1024 * 1024), Charsets.UTF_8).removePrefix("\uFEFF")
                    } ?: return@runCatching 0
                    val now = System.currentTimeMillis()
                    val cards = io.github.artcraftandroid.pdfcraft.engine.Flashcards.parseDelimited(text).take(10_000).map { (f, b) ->
                        io.github.artcraftandroid.pdfcraft.engine.Flashcard(java.util.UUID.randomUUID().toString(), deck, name, f, b, created = now)
                    }
                    flashcards.putAll(cards)
                    cards.size
                }.getOrDefault(-1)
            }
            if (n < 0) vm.tell(text(R.string.msg_that_file_unreadable)) else vm.tell(text(R.string.cards_imported, n))
            refreshDue()
            reviewing?.let { reload(it) }
        }
    }

    // ---- Library ------------------------------------------------------------------------------

    val library = io.github.artcraftandroid.pdfcraft.engine.Library(File(app.filesDir, "library.json"))
    val libraryIndex = io.github.artcraftandroid.pdfcraft.engine.LibraryIndex(File(app.filesDir, "library-index"))

    var libraryData by mutableStateOf(io.github.artcraftandroid.pdfcraft.engine.LibraryData())
        private set

    /** Library items whose file can no longer be read (permission revoked, file gone). */
    var libraryUnavailable by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Items with unresolved comments (from the index), for the smart collection. */
    var libraryUnresolved by mutableStateOf<Set<String>>(emptySet())
        private set

    /** The collection Add files puts new files into. */
    var libraryAddTo: String? = null

    /** Searching inside library documents is on (it builds an index in the background). */
    var libraryIndexing by mutableStateOf(vm.settings.appPrefs.getBoolean("libraryIndex", false))
        private set

    /** The document being indexed now, if any. */
    var indexingName by mutableStateOf<String?>(null)
        private set

    private var indexJob: kotlinx.coroutines.Job? = null

    fun refreshLibrary() {
        vm.viewModelScope.launch {
            val (data, gone, unresolved) = withContext(Dispatchers.IO) {
                val d = library.load()
                val granted = runCatching { app.contentResolver.persistedUriPermissions.map { it.uri.toString() }.toSet() }.getOrDefault(emptySet())
                val gone = d.items.filter { i ->
                    val u = Uri.parse(i.uri)
                    when (u.scheme) {
                        "file" -> !File(u.path ?: "").canRead()
                        "content" -> i.uri !in granted && vm.files.size(u) == null
                        else -> true
                    }
                }.map { it.uri }.toSet()
                val unresolved = d.items.filter { (libraryIndex.get(it.uri)?.unresolvedComments ?: 0) > 0 }.map { it.uri }.toSet()
                Triple(d, gone, unresolved)
            }
            libraryData = data
            libraryUnavailable = gone
            libraryUnresolved = unresolved
        }
    }

    private fun libraryEdit(f: (io.github.artcraftandroid.pdfcraft.engine.Library) -> Unit) {
        vm.viewModelScope.launch {
            withContext(Dispatchers.IO) { f(library) }
            refreshLibrary()
        }
    }

    fun addCollection(name: String, parent: String?) = libraryEdit { it.addCollection(name, parent) }
    fun renameCollection(id: String, name: String) = libraryEdit { it.renameCollection(id, name) }
    fun deleteCollection(id: String) = libraryEdit { it.deleteCollection(id) }
    fun setItemCollections(uri: String, ids: Set<String>) = libraryEdit { it.setItem(uri) { i -> i.copy(collections = ids) } }
    fun setItemTags(uri: String, tags: Set<String>) = libraryEdit { l -> l.setItem(uri) { i -> i.copy(tags = tags.map(io.github.artcraftandroid.pdfcraft.engine.Library::cleanTag).filter { it.isNotEmpty() }.toSet()) } }
    fun setItemColor(uri: String, color: Int?) = libraryEdit { it.setItem(uri) { i -> i.copy(color = color) } }

    fun removeFromLibrary(uri: String) = libraryEdit { l ->
        l.removeItem(uri)
        libraryIndex.remove(uri)
    }

    /** Add a file that is already open or in Recent (its grant is kept) to the library. */
    fun addToLibrary(uri: String, name: String, collection: String? = null) = libraryEdit { l ->
        runCatching { vm.files.keepAccess(Uri.parse(uri)) }
        l.addItem(uri, name, System.currentTimeMillis(), collection)
    }

    /** Library > Add files: pick PDFs; they stay where they are (only lasting read access is taken). */
    fun pickLibraryFiles(collection: String?) {
        libraryAddTo = collection
        vm.pickFiles(FilePickKind.Library)
    }

    internal fun onLibraryPicked(uris: List<Uri>) {
        val to = libraryAddTo
        libraryAddTo = null
        libraryEdit { l ->
            for (u in uris) {
                runCatching { vm.files.keepAccess(u) }
                l.addItem(u.toString(), runCatching { vm.files.displayNameRaw(u) }.getOrNull() ?: u.lastPathSegment.orEmpty(), System.currentTimeMillis(), to)
            }
        }
        indexLibrary()
    }

    fun updateLibraryIndexing(on: Boolean) {
        libraryIndexing = on
        vm.settings.appPrefs.edit().putBoolean("libraryIndex", on).apply()
        if (on) indexLibrary() else indexJob?.cancel()
    }

    /** Settings: delete the search index (the library itself stays). */
    fun wipeLibraryIndex() {
        indexJob?.cancel()
        vm.viewModelScope.launch {
            withContext(Dispatchers.IO) { libraryIndex.wipe() }
            refreshLibrary()
            vm.tell(text(R.string.library_index_wiped))
        }
    }

    /** Whether the battery allows indexing now: charging, or at least 20 %. */
    private fun batteryAllowsIndexing(): Boolean {
        val bm = app.getSystemService(android.os.BatteryManager::class.java) ?: return true
        val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return bm.isCharging || level !in 0 until 20
    }

    /**
     * Index the library's documents that changed since they were indexed: their text, outline and
     * comments, one document at a time on the engine's queue, only while no document is open (so
     * reading is never slowed) and the battery allows it. Each document is read from a private
     * copy that is deleted afterwards.
     */
    fun indexLibrary() {
        if (!libraryIndexing || indexJob?.isActive == true || vm.document != null) return
        val eng = vm.engine ?: return
        indexJob = vm.viewModelScope.launch {
            try {
                val items = withContext(Dispatchers.IO) { library.load().items }
                for (item in items) {
                    if (vm.document != null || vm.opening || !batteryAllowsIndexing()) break
                    val uri = Uri.parse(item.uri)
                    val signature = withContext(Dispatchers.IO) {
                        val size = runCatching { vm.files.size(uri) }.getOrNull() ?: return@withContext null
                        "$size:${runCatching { vm.files.lastModified(uri) }.getOrNull() ?: 0}"
                    } ?: continue
                    if (withContext(Dispatchers.IO) { libraryIndex.has(item.uri, signature) }) continue
                    indexingName = item.name
                    val copy = File(app.cacheDir, "index-${System.nanoTime()}.pdf")
                    try {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching {
                                app.contentResolver.openInputStream(uri)?.use { input ->
                                    copy.outputStream().use { out ->
                                        val buf = ByteArray(64 * 1024)
                                        var total = 0L
                                        while (true) {
                                            val n = input.read(buf)
                                            if (n < 0) break
                                            total += n
                                            // very large files are left out of the index
                                            if (total > MAX_INDEX_BYTES) return@runCatching false
                                            out.write(buf, 0, n)
                                        }
                                    }
                                    true
                                } ?: false
                            }.getOrDefault(false)
                        }
                        if (!ok || vm.document != null) continue
                        val id = runCatching { eng.tool("doc_open", mapOf("path" to copy.absolutePath)).optLong("doc") }.getOrNull() ?: continue
                        try {
                            val text = runCatching { eng.tool("text_extract", mapOf("doc" to id)) }.getOrNull()
                            val pages = text?.optJSONArray("pages")?.let { a -> (0 until a.length()).map { a.optJSONObject(it)?.optString("text").orEmpty() } } ?: emptyList()
                            val outline = runCatching { eng.tool("bookmark_list", mapOf("doc" to id)) }.getOrNull()?.let(::flattenOutline) ?: emptyList()
                            val comments = runCatching { eng.tool("comment_list", mapOf("doc" to id)) }.getOrNull()?.let(::flattenComments) ?: emptyList()
                            withContext(Dispatchers.IO) {
                                libraryIndex.put(io.github.artcraftandroid.pdfcraft.engine.IndexedDoc(item.uri, item.name, signature, pages, outline, comments))
                            }
                        } finally {
                            runCatching { eng.tool("doc_close", mapOf("doc" to id, "discard_changes" to true)) }
                        }
                    } finally {
                        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { copy.delete() }
                    }
                }
                withContext(Dispatchers.IO) { libraryIndex.retain(library.load().items.map { it.uri }.toSet()) }
            } finally {
                indexingName = null
                refreshLibrary()
            }
        }
    }

    private fun flattenOutline(o: org.json.JSONObject): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        fun walk(a: org.json.JSONArray?) {
            a ?: return
            for (i in 0 until a.length()) {
                val b = a.optJSONObject(i) ?: continue
                val title = b.optString("title")
                if (title.isNotEmpty()) out += (b.optInt("page", 1) - 1).coerceAtLeast(0) to title
                walk(b.optJSONArray("children"))
            }
        }
        walk(o.optJSONArray("bookmarks"))
        return out
    }

    private fun flattenComments(o: org.json.JSONObject): List<Triple<Int, String, Boolean>> {
        val out = ArrayList<Triple<Int, String, Boolean>>()
        val arr = o.optJSONArray("comments") ?: return out
        val done = setOf("Accepted", "Completed", "Cancelled", "Rejected")
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val page = (c.optInt("page", 1) - 1).coerceAtLeast(0)
            val unresolved = c.isNull("status") || c.optString("status") !in done
            c.optString("contents").takeIf { !c.isNull("contents") && it.isNotBlank() }?.let { out += Triple(page, it, unresolved) }
            val replies = c.optJSONArray("replies") ?: continue
            for (k in 0 until replies.length()) {
                val r = replies.optJSONObject(k) ?: continue
                r.optString("contents").takeIf { !r.isNull("contents") && it.isNotBlank() }?.let { out += Triple(page, it, false) }
            }
        }
        return out
    }

    /** Library search results for the query, off the main thread. */
    suspend fun searchLibrary(query: String, uris: Collection<String>?): List<io.github.artcraftandroid.pdfcraft.engine.SearchHit> = withContext(Dispatchers.IO) {
        val q = io.github.artcraftandroid.pdfcraft.engine.LibraryIndex.fold(query.trim())
        val data = library.load()
        val scope = uris?.toSet()
        // names of every library file (indexed or not), then the index
        val names = data.items.filter { (scope == null || it.uri in scope) && q.length >= 2 && io.github.artcraftandroid.pdfcraft.engine.LibraryIndex.fold(it.name).contains(q) }
            .map { io.github.artcraftandroid.pdfcraft.engine.SearchHit(it.uri, it.name, -1, io.github.artcraftandroid.pdfcraft.engine.HitKind.Name, it.name, io.github.artcraftandroid.pdfcraft.engine.LibraryIndex.fold(it.name).indexOf(q), q.length) }
        val inside = if (libraryIndexing) libraryIndex.search(query, scope ?: data.items.map { it.uri }) else emptyList()
        names + inside.filter { it.kind != io.github.artcraftandroid.pdfcraft.engine.HitKind.Name }
    }

    /** After opening, go to this page and search this text (a library search hit). */
    var pendingSearch: Pair<Int, String>? = null

    fun openHit(hit: io.github.artcraftandroid.pdfcraft.engine.SearchHit, query: String) {
        pendingSearch = if (hit.page >= 0 && hit.kind == io.github.artcraftandroid.pdfcraft.engine.HitKind.Text) hit.page to query.trim() else null
        if (hit.page >= 0 && hit.kind != io.github.artcraftandroid.pdfcraft.engine.HitKind.Text) pendingSearch = hit.page to ""
        vm.open(Uri.parse(hit.uri))
    }

    // ---- Study sessions -------------------------------------------------------------------------

    val studyStats = io.github.artcraftandroid.pdfcraft.engine.StudyStats(File(app.filesDir, "study.json"))
    private val studyClock = io.github.artcraftandroid.pdfcraft.engine.StudyClock()
    internal var studyDoc: OpenDocument? = null
    private var studyPendingMs = 0L
    private var studyPendingNotes = 0
    private val studyPendingPages = HashSet<Int>()
    private var studyLastFlush = 0L

    /** Home's Continue studying card: the documents studied most recently, and this week's minutes. */
    var studyRecent by mutableStateOf<List<io.github.artcraftandroid.pdfcraft.engine.DocStudy>>(emptyList())
        private set
    var studyWeek by mutableStateOf<List<Long>>(emptyList())
        private set

    fun refreshStudy() {
        vm.viewModelScope.launch {
            val (recent, week) = withContext(Dispatchers.IO) {
                studyStats.all().filter { it.activeMs > 0 }.take(3) to studyStats.week(java.time.LocalDate.now())
            }
            studyRecent = recent
            studyWeek = week
        }
    }

    /**
     * The reader touched the screen: count the time since their last touch (at most two minutes,
     * so a phone left open stops counting) toward the open document, and note the page.
     */
    fun studyActivity() {
        val doc = vm.document ?: return
        if (doc.info.preparing) return
        val now = android.os.SystemClock.uptimeMillis()
        if (studyDoc !== doc) { flushStudy(); studyClock.pause(now); studyDoc = doc }
        studyPendingMs += studyClock.activity(now)
        studyPendingPages += doc.viewer.currentPage
        if (now - studyLastFlush > 30_000) flushStudy()
    }

    /** An annotation was added to the open document (counted in its study record). */
    internal fun studyNote(doc: OpenDocument) { if (studyDoc === doc) studyPendingNotes++ }

    /** The viewer left the front (or the document closed): stop the clock and save what was counted. */
    fun pauseStudy() {
        studyPendingMs += studyClock.pause(android.os.SystemClock.uptimeMillis())
        flushStudy()
    }

    private fun flushStudy() {
        val doc = studyDoc ?: return
        val uri = doc.source?.toString() ?: return
        studyLastFlush = android.os.SystemClock.uptimeMillis()
        if (studyPendingMs == 0L && studyPendingPages.isEmpty() && studyPendingNotes == 0) return
        val ms = studyPendingMs
        val pages = studyPendingPages.toSet()
        val notes = studyPendingNotes
        studyPendingMs = 0
        studyPendingNotes = 0
        studyPendingPages.clear()
        val name = doc.info.name
        val count = doc.pageCount
        val last = doc.viewer.currentPage
        vm.viewModelScope.launch(Dispatchers.IO) { runCatching { studyStats.record(uri, name, count, pages, last, ms, System.currentTimeMillis(), notes) } }
    }

    /** Settings: forget every study statistic. */
    fun clearStudyStats() {
        studyPendingMs = 0
        studyPendingPages.clear()
        studyPendingNotes = 0
        vm.viewModelScope.launch {
            withContext(Dispatchers.IO) { studyStats.clear() }
            refreshStudy()
            vm.tell(text(R.string.study_stats_cleared))
        }
    }

    /** The focus timer (Pomodoro), wall-clock based so it outlives rotation and the process. */
    var focusTimer by mutableStateOf(io.github.artcraftandroid.pdfcraft.FocusTimerAlarm.load(app))
        internal set

    /** Asks the activity for permission to show the timer's notification (Android 13+). */
    val notificationRequests = Channel<Unit>(Channel.CONFLATED)

    fun updateFocusTimer(t: io.github.artcraftandroid.pdfcraft.engine.FocusTimer) {
        val app = app
        if (t.running && !focusTimer.active && android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !vm.settings.appPrefs.getBoolean("focusNotificationAsked", false)
        ) {
            vm.settings.appPrefs.edit().putBoolean("focusNotificationAsked", true).apply()
            notificationRequests.trySend(Unit)
        }
        focusTimer = t
        io.github.artcraftandroid.pdfcraft.FocusTimerAlarm.apply(app, t)
    }

    /** Called every second while the timer shows: move to the next phase when one ends. */
    fun tickFocusTimer() {
        val now = System.currentTimeMillis()
        val next = focusTimer.tick(now)
        if (next != focusTimer) {
            focusTimer = next
            io.github.artcraftandroid.pdfcraft.FocusTimerAlarm.apply(app, next, now, alert = true)
            vm.tell(text(if (next.onBreak) R.string.focus_break_started else R.string.focus_work_started))
        }
    }

    // ---- Study digest -------------------------------------------------------------------------

    /**
     * Export [doc]'s highlights, notes and drawings as a review sheet: Markdown (with ink drawings
     * as PNGs beside it in a ZIP), CSV, or the engine's PDF comment summary. Runs off the main
     * thread on the engine's queue; the file goes to Downloads.
     */
    fun exportNotes(doc: OpenDocument, format: DigestFormat, grouping: io.github.artcraftandroid.pdfcraft.engine.DigestGrouping, filter: io.github.artcraftandroid.pdfcraft.engine.DigestFilter) =
        vm.job(text(R.string.digest_busy)) { eng ->
            val SD = io.github.artcraftandroid.pdfcraft.engine.StudyDigest
            val base = doc.title.ifBlank { "Document" }
            if (format == DigestFormat.Pdf) {
                val out = vm.tempFile("pdf")
                eng.tool("comments_summarize", mapOf("doc" to doc.id, "sort" to "page", "out" to out.absolutePath))
                vm.deliver(out, DocumentFiles.safeName(text(R.string.digest_file_name, base)))
                return@job
            }
            val entries = SD.filter(SD.parse(eng.tool("comment_list", mapOf("doc" to doc.id))), filter)
            if (entries.isEmpty()) { vm.tell(text(R.string.digest_nothing)); return@job }
            // the text each highlight marks: its note when the highlight was made from a selection here, else the text under it
            for (e in entries) {
                if (!e.isMarkup) continue
                val under = runCatching {
                    eng.tool("text_extract", mapOf("doc" to doc.id, "pages" to listOf(e.page), "rect" to e.rect.toList()))
                        .optJSONArray("pages")?.optJSONObject(0)?.optString("text")?.trim()
                }.getOrNull()?.takeIf { it.isNotEmpty() }
                e.marked = under ?: e.note
            }
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            val labels = io.github.artcraftandroid.pdfcraft.engine.DigestLabels(
                page = text(R.string.digest_page), noColor = text(R.string.digest_no_color),
                status = text(R.string.digest_status), drawing = text(R.string.digest_drawing),
                colorNames = mapOf(
                    "yellow" to text(R.string.digest_yellow), "green" to text(R.string.digest_green), "blue" to text(R.string.digest_blue),
                    "pink" to text(R.string.digest_pink), "red" to text(R.string.digest_red), "orange" to text(R.string.digest_orange),
                    "purple" to text(R.string.digest_purple), "black" to text(R.string.digest_black), "grey" to text(R.string.digest_grey),
                ),
            )
            val fileLink = doc.info.name.ifBlank { "$base.pdf" }
            when (format) {
                DigestFormat.Csv -> {
                    val out = vm.tempFile("csv")
                    withContext(Dispatchers.IO) { out.writeText("\uFEFF" + SD.csv(entries)) }
                    vm.deliver(out, DocumentFiles.safeName(text(R.string.digest_file_name, base)).removeSuffix(".pdf") + ".csv", "text/csv")
                }
                DigestFormat.Markdown -> {
                    val md = SD.markdown(text(R.string.digest_heading, base, stamp), fileLink, entries, grouping, labels)
                    val out = vm.tempFile("md")
                    withContext(Dispatchers.IO) { out.writeText(md) }
                    vm.deliver(out, DocumentFiles.safeName(text(R.string.digest_file_name, base)).removeSuffix(".pdf") + ".md", "text/markdown")
                }
                DigestFormat.MarkdownImages -> {
                    // each drawing cropped from its page at 2x, beside the notes in one ZIP
                    val images = LinkedHashMap<String, ByteArray>()
                    for ((i, e) in entries.withIndex()) {
                        if (!e.isInk) continue
                        val page = doc.info.pages.getOrNull(e.page - 1) ?: continue
                        val scale = 2f
                        val pad = 6f
                        val x0 = ((e.rect[0] - pad).coerceAtLeast(0f) * scale).toInt()
                        val y0 = ((e.rect[1] - pad).coerceAtLeast(0f) * scale).toInt()
                        val x1 = ((e.rect[2] + pad).coerceAtMost(page.width) * scale).toInt()
                        val y1 = ((e.rect[3] + pad).coerceAtMost(page.height) * scale).toInt()
                        if (x1 <= x0 || y1 <= y0 || (x1 - x0).toLong() * (y1 - y0) > 16_000_000L) continue
                        val png = runCatching {
                            val bmp = withContext(eng.thumbnails) {
                                eng.session.renderPage(doc.id, (e.page - 1).toUInt(), scale, io.github.artcraftandroid.pdfcraft.core.TileRect(x0.toUInt(), y0.toUInt(), (x1 - x0).toUInt(), (y1 - y0).toUInt())).toBitmap()
                            }
                            java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it); bmp.recycle() }.toByteArray()
                        }.getOrNull() ?: continue
                        val name = "images/page-${e.page}-${i + 1}.png"
                        images[name] = png
                        e.image = name
                    }
                    val md = SD.markdown(text(R.string.digest_heading, base, stamp), fileLink, entries, grouping, labels)
                    val notesName = DocumentFiles.safeName(text(R.string.digest_file_name, base)).removeSuffix(".pdf")
                    val out = vm.tempFile("zip")
                    withContext(Dispatchers.IO) {
                        java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { zip ->
                            zip.putNextEntry(java.util.zip.ZipEntry("$notesName.md")); zip.write(md.toByteArray()); zip.closeEntry()
                            for ((name, bytes) in images) { zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
                        }
                    }
                    vm.deliver(out, "$notesName.zip", "application/zip")
                }
                DigestFormat.Pdf -> Unit
            }
        }

    // ---- Notebooks ----------------------------------------------------------------------------

    /** Home's New notebook sheet is showing. */
    var newNotebookOpen by mutableStateOf(false)

    /** The pages the user saved as templates (file names), refreshed off the main thread. */
    var userTemplates by mutableStateOf<List<String>>(emptyList())
        private set

    fun refreshUserTemplates() {
        vm.viewModelScope.launch { userTemplates = withContext(Dispatchers.IO) { vm.notebookTemplates.list() } }
    }

    /** A one-page PDF of [page] in the cache, for inserting (the caller deletes it). */
    private suspend fun notebookPageFile(page: io.github.artcraftandroid.pdfcraft.engine.NotebookPage): File? = withContext(Dispatchers.IO) {
        when (page) {
            is io.github.artcraftandroid.pdfcraft.engine.NotebookPage.Builtin ->
                vm.tempFile("pdf").apply { writeBytes(io.github.artcraftandroid.pdfcraft.engine.NotebookPdf.write(page.template)) }
            is io.github.artcraftandroid.pdfcraft.engine.NotebookPage.User ->
                vm.notebookTemplates.file(page.fileName)?.let { src -> vm.tempFile("pdf").also { src.copyTo(it, overwrite = true) } }
        }
    }

    /** Make a notebook of [page] called [title] in Downloads and open it in Draw mode. */
    fun createNotebook(page: io.github.artcraftandroid.pdfcraft.engine.NotebookPage, title: String) {
        newNotebookOpen = false
        vm.viewModelScope.launch {
            vm.tools.busy = text(R.string.notebook_busy_creating)
            try {
                val out = notebookPageFile(page) ?: run { vm.tell(text(R.string.notebook_template_missing)); return@launch }
                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH.mm", java.util.Locale.US).format(java.util.Date())
                val name = DocumentFiles.safeName(title.trim().ifEmpty { text(R.string.notebook_default_name, stamp) })
                val uri = withContext(Dispatchers.IO) { vm.files.saveToDownloads(out, name).also { out.delete() } }
                withContext(Dispatchers.IO) { vm.notebooks.put(uri.toString(), page) }
                vm.pendingTool = io.github.artcraftandroid.pdfcraft.ui.home.FileTool.Draw
                vm.open(uri)
            } catch (e: Exception) {
                vm.tell(e.userMessage)
            } finally {
                vm.tools.busy = null
            }
        }
    }

    /** What [doc]'s pages look like, if it was made as a notebook here. */
    suspend fun notebookPageOf(doc: OpenDocument): io.github.artcraftandroid.pdfcraft.engine.NotebookPage? {
        val key = doc.source?.toString() ?: return null
        return withContext(Dispatchers.IO) { vm.notebooks.get(key) }
    }

    /**
     * Insert a page of [page] so it becomes page [at] (1-based; default: after the last), then
     * show it. One undo step removes it. Without a template the page is blank, sized like its
     * neighbour.
     */
    fun insertNotebookPage(doc: OpenDocument, page: io.github.artcraftandroid.pdfcraft.engine.NotebookPage?, at: Int = doc.pageCount + 1) {
        if (!doc.writable || !doc.info.allowsAnnotation) { vm.tell(text(R.string.notebook_cannot_add_page)); return }
        vm.viewModelScope.launch {
            val chosen = page ?: notebookPageOf(doc)
            val show = { doc.viewer.goToPage(at - 1) }
            if (chosen == null) {
                vm.runTool(doc, "page_insert_blank", mapOf("at" to at)) { show() }
                return@launch
            }
            val file = notebookPageFile(chosen) ?: run { vm.tell(text(R.string.notebook_template_missing)); return@launch }
            vm.runTool(doc, "page_insert_file", mapOf("path" to file.absolutePath, "at" to at), onSettled = { file.delete() }) { show() }
        }
    }

    /** Save page [index] (0-based) of [doc] as a template called [title] for new notebooks and pages. */
    fun savePageAsTemplate(doc: OpenDocument, index: Int, title: String) = vm.job(text(R.string.notebook_busy_saving_template)) { eng ->
        val out = withContext(Dispatchers.IO) { vm.notebookTemplates.newFile(title) }
        try {
            eng.tool("page_extract", mapOf("doc" to doc.id, "pages" to listOf(index + 1), "out" to out.absolutePath))
        } catch (e: Exception) {
            out.delete()
            throw e
        }
        refreshUserTemplates()
        vm.tell(text(R.string.notebook_template_saved, out.nameWithoutExtension))
    }

    fun deleteUserTemplate(name: String) {
        vm.viewModelScope.launch {
            withContext(Dispatchers.IO) { vm.notebookTemplates.delete(name) }
            refreshUserTemplates()
        }
    }
}
