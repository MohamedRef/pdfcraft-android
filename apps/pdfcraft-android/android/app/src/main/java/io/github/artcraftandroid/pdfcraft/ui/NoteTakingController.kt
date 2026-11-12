package io.github.artcraftandroid.pdfcraft.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.plural
import io.github.artcraftandroid.pdfcraft.text
import io.github.artcraftandroid.pdfcraft.engine.userMessage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea

class NoteTakingController internal constructor(private val vm: AppViewModel) {
    private val app: Application get() = vm.getApplication()

    // ---- Note margins (#146) ----

    /** Which pages have a note margin (one per page), from the engine. */
    suspend fun pageMargins(doc: OpenDocument): List<Boolean> {
        val eng = vm.engine ?: return emptyList()
        return runCatching {
            val r = eng.tool("page_margin", mapOf("doc" to doc.id))
            val a = r.getJSONArray("margins")
            List(a.length()) { a.getBoolean(it) }
        }.getOrDefault(emptyList())
    }

    /**
     * Room for notes on [side] of [pages] (0-based; every page when empty), [fraction] of the
     * first page's width (or height, below). One undo step; the content keeps its place.
     */
    fun addNoteMargin(doc: OpenDocument, pages: List<Int>, side: MarginSide, fraction: Float) {
        if (!doc.writable) { vm.tell(text(R.string.margin_read_only)); return }
        val first = doc.info.pages.getOrNull(pages.firstOrNull() ?: 0) ?: return
        val right = if (side != MarginSide.Bottom) (first.width * fraction).toDouble() else 0.0
        val bottom = if (side != MarginSide.Right) (first.height * fraction).toDouble() else 0.0
        val args = buildMap<String, Any?> {
            put("add", listOf(0.0, bottom, right, 0.0))
            if (pages.isNotEmpty()) put("pages", pages.map { it + 1 })
        }
        vm.runTool(doc, "page_margin", args) { r ->
            Diag.event(
                DiagArea.Study, "margin_add", "doc" to doc.diagNumber, "side" to side, "size_pct" to (fraction * 100).toInt(),
                "pages" to (if (pages.isEmpty()) doc.pageCount else pages.size), "with_margin" to r.optJSONArray("margins")?.let { a -> (0 until a.length()).count { a.optBoolean(it) } },
            )
            vm.tell(text(R.string.margin_added))
        }
    }

    /** Take off the note margins of [pages] (every page when empty), back to the original size. */
    fun removeNoteMargins(doc: OpenDocument, pages: List<Int>) {
        if (!doc.writable) { vm.tell(text(R.string.margin_read_only)); return }
        val args = buildMap<String, Any?> {
            put("remove", true)
            if (pages.isNotEmpty()) put("pages", pages.map { it + 1 })
        }
        vm.runTool(doc, "page_margin", args) { r ->
            Diag.event(
                DiagArea.Study, "margin_remove", "doc" to doc.diagNumber, "pages" to (if (pages.isEmpty()) doc.pageCount else pages.size),
                "changed" to r.optBoolean("changed", true),
            )
            vm.tell(text(R.string.margin_removed))
        }
    }

    // ---- Handwriting to text (#151) ----

    /**
     * Read the handwriting on page [page] (0-based): the ink comments with no text of their own
     * are pasted on a blank page of the same size, read by the engine's OCR there (so printed
     * text under them is not read too), and each gets the words that lie in it as its text,
     * which the comment list, search across documents and other PDF readers show.
     */
    fun recognizeHandwriting(doc: OpenDocument, page: Int) {
        val eng = vm.engine ?: return
        if (!doc.writable) { vm.tell(text(R.string.margin_read_only)); return }
        doc.commitTool?.invoke()
        vm.viewModelScope.launch {
            vm.tools.busy = text(R.string.msg_busy_preparing_ocr)
            withContext(Dispatchers.IO) { runCatching { eng.prepareOcrModels(app) } }
            val status = runCatching { eng.tool("ocr_status") }.getOrNull()
            if (status?.optBoolean("available", false) != true) {
                Diag.event(DiagArea.Study, "handwriting", "doc" to doc.diagNumber, "outcome" to "ocr_missing")
                vm.tools.busy = null
                vm.tell(text(R.string.msg_ocr_not_installed))
                return@launch
            }
            vm.tools.busy = text(R.string.handwriting_busy)
            val blank = vm.tempFile("pdf")
            var copy: Long? = null
            try {
                val inks = io.github.artcraftandroid.pdfcraft.engine.Handwriting.inksWithoutText(eng.tool("comment_list", mapOf("doc" to doc.id, "page" to page + 1)))
                if (inks.isEmpty()) {
                    Diag.event(DiagArea.Study, "handwriting", "doc" to doc.diagNumber, "outcome" to "no_new_ink")
                    vm.tell(text(R.string.handwriting_none)); return@launch
                }
                val xfdf = eng.tool("comments_copy", mapOf("doc" to doc.id, "comments" to inks.map { listOf(page + 1, it.index) })).getString("xfdf")
                val size = doc.info.pages[page]
                withContext(Dispatchers.IO) { blank.writeBytes(io.github.artcraftandroid.pdfcraft.engine.Handwriting.blankPdf(size.width.toDouble(), size.height.toDouble())) }
                copy = eng.tool("doc_open", mapOf("path" to blank.absolutePath)).optLong("doc")
                // centred where they were, so their boxes on the blank page are the ones listed
                val cx = (inks.minOf { it.x0 } + inks.maxOf { it.x1 }) / 2
                val cy = (inks.minOf { it.y0 } + inks.maxOf { it.y1 }) / 2
                eng.tool("comments_paste", mapOf("doc" to copy, "page" to 1, "xfdf" to xfdf, "at" to listOf(cx, cy)))
                eng.tool("doc_save", mapOf("doc" to copy))
                val ocrStart = System.nanoTime()
                val read = eng.tool("ocr_words", mapOf("doc" to copy, "pages" to listOf(1), "force_ocr" to true, "auto_rotate" to true))
                val ocrMs = (System.nanoTime() - ocrStart) / 1_000_000
                val words = io.github.artcraftandroid.pdfcraft.engine.Handwriting.words(read)
                val found = io.github.artcraftandroid.pdfcraft.engine.Handwriting.assign(inks, words)
                Diag.event(
                    DiagArea.Study, "handwriting", "doc" to doc.diagNumber, "outcome" to (if (found.isEmpty()) "nothing_read" else "read"),
                    "inks" to inks.size, "words" to words.size, "low_confidence" to words.count { (it.confidence ?: 100f) <= io.github.artcraftandroid.pdfcraft.engine.Handwriting.MIN_CONFIDENCE },
                    "inks_with_text" to found.size, "ocr_ms" to ocrMs,
                )
                if (found.isEmpty()) { vm.tell(text(R.string.handwriting_nothing_read)); return@launch }
                for ((index, words) in found) eng.tool("comment_edit", mapOf("doc" to doc.id, "page" to page + 1, "index" to index, "contents" to words))
                vm.refresh(doc)
                vm.tools.success = SuccessBanner(plural(R.plurals.handwriting_done, found.size, found.size), null)
            } catch (e: Exception) {
                Diag.failure(DiagArea.Study, "handwriting", e)
                vm.tell(e.userMessage)
            } finally {
                copy?.let { c -> runCatching { eng.tool("doc_close", mapOf("doc" to c, "discard_changes" to true)) } }
                withContext(Dispatchers.IO) { blank.delete() }
                vm.tools.busy = null
            }
        }
    }

    // ---- Lecture recording (#142) ----

    val lectures = io.github.artcraftandroid.pdfcraft.engine.Lectures(File(app.filesDir, "lectures"))

    /** A recording in progress beside one document (by URI, so it goes on across pages and reopening). */
    class LectureSession(
        val uri: String, val title: String, val id: String, val file: File, val started: Long,
        val recorder: android.media.MediaRecorder, val clock: io.github.artcraftandroid.pdfcraft.engine.RecordingClock,
    ) {
        var marks: List<io.github.artcraftandroid.pdfcraft.engine.LectureMark> = emptyList()
        val known = HashSet<String>()
    }

    var lectureRecording by mutableStateOf<LectureSession?>(null)
        internal set
    var lecturePaused by mutableStateOf(false)
        internal set
    internal var pendingLecture: OpenDocument? = null

    /** Start recording the lecture beside [doc]: comments written meanwhile remember when. */
    fun startLecture(doc: OpenDocument) {
        val app = app
        val uri = doc.source?.toString() ?: run { vm.tell(text(R.string.lecture_needs_saved)); return }
        if (lectureRecording != null) return
        if (app.filesDir.usableSpace < 100L * 1024 * 1024) { vm.tell(text(R.string.msg_voice_no_storage)); return }
        if (androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingLecture = doc
            vm.commenting.micRequests.trySend(Unit)
            return
        }
        vm.viewModelScope.launch {
            val now = System.currentTimeMillis()
            val (id, file) = withContext(Dispatchers.IO) { lectures.newFile(now) }
            @Suppress("DEPRECATION")
            val recorder = if (android.os.Build.VERSION.SDK_INT >= 31) android.media.MediaRecorder(app) else android.media.MediaRecorder()
            try {
                recorder.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                recorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                recorder.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioChannels(1)
                recorder.setAudioSamplingRate(44_100)
                recorder.setAudioEncodingBitRate(64_000)
                recorder.setOutputFile(file.absolutePath)
                recorder.prepare()
                recorder.start()
            } catch (e: Exception) {
                Diag.failure(DiagArea.Study, "lecture_start", e)
                recorder.release()
                file.delete()
                vm.tell(text(R.string.lecture_cannot_record))
                return@launch
            }
            val clock = io.github.artcraftandroid.pdfcraft.engine.RecordingClock { android.os.SystemClock.elapsedRealtime() }
            clock.start()
            val session = LectureSession(uri, doc.title, id, file, now, recorder, clock)
            // the comments already there are not marked as written during the lecture
            vm.comments(doc)?.mapNotNullTo(session.known) { it.name }
            Diag.event(DiagArea.Study, "lecture_start", "doc" to doc.diagNumber, "comments_before" to session.known.size, "free_mb" to app.filesDir.usableSpace / (1024 * 1024))
            lectureRecording = session
            lecturePaused = false
            withContext(Dispatchers.IO) { lectures.put(uri, io.github.artcraftandroid.pdfcraft.engine.Lecture(id, now, 0, emptyList())) }
            io.github.artcraftandroid.pdfcraft.LectureRecordService.onStop = { vm.viewModelScope.launch { stopLecture() } }
            io.github.artcraftandroid.pdfcraft.LectureRecordService.show(app, doc.title, now)
        }
    }

    fun pauseLecture() {
        val s = lectureRecording ?: return
        if (lecturePaused) return
        runCatching { s.recorder.pause() }.onSuccess { s.clock.pause(); lecturePaused = true }
            .also { Diag.event(DiagArea.Study, "lecture_pause", "ok" to it.isSuccess, "at_ms" to s.clock.elapsed()) }
    }

    fun resumeLecture() {
        val s = lectureRecording ?: return
        if (!lecturePaused) return
        runCatching { s.recorder.resume() }.also { Diag.event(DiagArea.Study, "lecture_resume", "ok" to it.isSuccess, "at_ms" to s.clock.elapsed()) }.onSuccess {
            s.clock.start()
            lecturePaused = false
            io.github.artcraftandroid.pdfcraft.LectureRecordService.show(app, s.title, System.currentTimeMillis() - s.clock.elapsed())
        }
    }

    /** Stop and keep the recording with the times its comments were written. */
    fun stopLecture() {
        val s = lectureRecording ?: return
        lectureRecording = null
        lecturePaused = false
        io.github.artcraftandroid.pdfcraft.LectureRecordService.onStop = null
        io.github.artcraftandroid.pdfcraft.LectureRecordService.hide(app)
        val duration = s.clock.elapsed().also { s.clock.pause() }
        // stop() fails when nothing was recorded: then nothing is kept
        val ok = runCatching { s.recorder.stop() }.isSuccess
        s.recorder.release()
        vm.viewModelScope.launch(Dispatchers.IO) {
            val bytes = s.file.length()
            Diag.event(
                DiagArea.Study, "lecture_stop", "ok" to ok, "duration_ms" to duration, "bytes" to bytes, "marks" to s.marks.size,
                "kbps" to (if (duration > 0) bytes * 8 / duration else 0L), "wall_ms" to System.currentTimeMillis() - s.started,
            )
            if (ok && s.file.length() > 0) {
                lectures.put(s.uri, io.github.artcraftandroid.pdfcraft.engine.Lecture(s.id, s.started, duration, s.marks))
                withContext(Dispatchers.Main) { vm.tell(text(R.string.lecture_saved, io.github.artcraftandroid.pdfcraft.ui.viewer.timerClock(duration))) }
            } else {
                lectures.delete(s.uri, s.id)
                withContext(Dispatchers.Main) { vm.tell(text(R.string.lecture_cannot_record)) }
            }
        }
    }

    /** A comment was added to [doc]: if a lecture is being recorded beside it, remember when. */
    internal fun lectureNoted(doc: OpenDocument) {
        val s = lectureRecording ?: return
        if (doc.source?.toString() != s.uri) return
        val at = s.clock.elapsed()
        vm.viewModelScope.launch {
            val added = vm.comments(doc)?.filter { c -> c.name?.let { it !in s.known } == true && c.replyTo == null }.orEmpty()
            if (added.isEmpty()) return@launch
            added.forEach { s.known += it.name!! }
            Diag.event(DiagArea.Study, "lecture_mark", "doc" to doc.diagNumber, "at_ms" to at, "added" to added.size, "marks" to s.marks.size + added.size)
            s.marks = s.marks + added.map { io.github.artcraftandroid.pdfcraft.engine.LectureMark(it.name!!, it.page.toInt(), at) }
            if (lectureRecording === s) withContext(Dispatchers.IO) {
                lectures.put(s.uri, io.github.artcraftandroid.pdfcraft.engine.Lecture(s.id, s.started, at, s.marks))
            }
        }
    }

    // Playback

    class LecturePlayback(val uri: String, val lecture: io.github.artcraftandroid.pdfcraft.engine.Lecture, val player: android.media.MediaPlayer)

    var lecturePlayback by mutableStateOf<LecturePlayback?>(null)
        internal set
    var lecturePlaying by mutableStateOf(false)
        internal set
    var lectureSpeed by mutableStateOf(1f)
        internal set

    fun lecturesOf(doc: OpenDocument): List<io.github.artcraftandroid.pdfcraft.engine.Lecture> =
        doc.source?.toString()?.let { lectures.list(it) }.orEmpty()

    /** Play [l] from [fromMs]. */
    fun playLecture(doc: OpenDocument, l: io.github.artcraftandroid.pdfcraft.engine.Lecture, fromMs: Long = 0) {
        val uri = doc.source?.toString() ?: return
        val current = lecturePlayback
        if (current != null && current.lecture.id == l.id) {
            seekLecture(fromMs)
            if (!lecturePlaying) toggleLecturePlay()
            return
        }
        closeLecturePlayback()
        val player = android.media.MediaPlayer()
        try {
            player.setDataSource(lectures.audio(l).absolutePath)
            player.prepare()
        } catch (e: Exception) {
            Diag.failure(DiagArea.Study, "lecture_play", e)
            player.release()
            vm.tell(text(R.string.lecture_cannot_play))
            return
        }
        Diag.event(DiagArea.Study, "lecture_play", "doc" to doc.diagNumber, "from_ms" to fromMs, "duration_ms" to player.duration, "marks" to l.marks.size)
        player.setOnCompletionListener { lecturePlaying = false }
        lecturePlayback = LecturePlayback(uri, l, player)
        applySpeed(player)
        player.seekTo(fromMs.toInt())
        player.start()
        lecturePlaying = true
    }

    fun toggleLecturePlay() {
        val p = lecturePlayback ?: return
        if (p.player.isPlaying) { p.player.pause(); lecturePlaying = false } else { p.player.start(); applySpeed(p.player); lecturePlaying = true }
    }

    fun seekLecture(ms: Long) {
        val p = lecturePlayback ?: return
        p.player.seekTo(ms.coerceIn(0, p.player.duration.toLong().coerceAtLeast(0)).toInt())
    }

    fun skipLecture(deltaMs: Long) {
        val p = lecturePlayback ?: return
        seekLecture(p.player.currentPosition + deltaMs)
    }

    fun lecturePosition(): Long = lecturePlayback?.let { runCatching { it.player.currentPosition.toLong() }.getOrDefault(0) } ?: 0

    fun cycleLectureSpeed() {
        val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
        lectureSpeed = speeds[(speeds.indexOf(lectureSpeed) + 1) % speeds.size]
        Diag.event(DiagArea.Study, "lecture_speed", "speed" to lectureSpeed)
        lecturePlayback?.player?.let(::applySpeed)
    }

    private fun applySpeed(player: android.media.MediaPlayer) {
        if (!player.isPlaying) return
        runCatching { player.playbackParams = player.playbackParams.setSpeed(lectureSpeed) }
    }

    fun closeLecturePlayback() {
        lecturePlayback?.player?.let { runCatching { it.stop() }; it.release() }
        lecturePlayback = null
        lecturePlaying = false
    }

    fun deleteLecture(doc: OpenDocument, l: io.github.artcraftandroid.pdfcraft.engine.Lecture) {
        val uri = doc.source?.toString() ?: return
        if (lecturePlayback?.lecture?.id == l.id) closeLecturePlayback()
        vm.viewModelScope.launch(Dispatchers.IO) { lectures.delete(uri, l.id) }
    }

    /** In playback, tapping a comment written during the lecture plays from just before it. */
    internal fun lectureSeekToComment(doc: OpenDocument, c: io.github.artcraftandroid.pdfcraft.core.CommentInfo) {
        val p = lecturePlayback ?: return
        if (doc.source?.toString() != p.uri) return
        val name = c.name ?: return
        val (_, at) = io.github.artcraftandroid.pdfcraft.engine.Lectures.seekFor(listOf(p.lecture), name)
            ?: run { Diag.event(DiagArea.Study, "lecture_seek_comment", "doc" to doc.diagNumber, "found" to false); return }
        Diag.event(DiagArea.Study, "lecture_seek_comment", "doc" to doc.diagNumber, "found" to true, "at_ms" to at)
        seekLecture(at)
        if (!lecturePlaying) toggleLecturePlay()
    }
}
