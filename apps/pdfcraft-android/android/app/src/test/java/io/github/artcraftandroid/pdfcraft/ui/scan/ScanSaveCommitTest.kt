package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.engine.publishDownload
import io.github.artcraftandroid.pdfcraft.ui.ScanFilter
import io.github.artcraftandroid.pdfcraft.ui.ScanSession
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Save keeps the scan until the place its PDF goes to has it (#787). The failures here are
 * injected: the end of Save runs as `saveScanNow` composes it ([scanCommitSave] with the same
 * steps in the same order), over photos in a temporary folder, a Downloads that can refuse, and
 * the real Recent scans store. The view model itself needs a phone and is not run here.
 */
class ScanSaveCommitTest {
    @get:Rule val tmp = TemporaryFolder()

    /** The scan on screen: its pages (photo, borders, turn, look), its name, and what the log got. */
    private inner class Draft(val keepRecent: Boolean) {
        val cam: File = tmp.newFolder()
        val recent = ScanRecentStore(File(tmp.newFolder(), "recent"))
        // three numbered pages, borders pulled by hand, the second turned, a look chosen
        val pages = (1..3).map { n ->
            val photo = File(cam, "page-$n.jpg").apply { writeBytes(ByteArray(100 * n) { n.toByte() }) }
            ScanSession.Page(photo.absolutePath, floatArrayOf(0.1f * n, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f), null, turns = if (n == 2) 1 else null, look = ScanPageLook(ScanFilter.Grayscale, 60))
        }.toMutableList()
        var name = "Invoice March"
        val log = mutableListOf<String>()
        val told = mutableListOf<String>()
        var auxFailures = 0

        fun asSaved(): String = ScanSession(null, null, false, name, pages.toList()).encode()
        fun photos(): List<File> = pages.map { File(it.path) }

        /** The end of Save: [write] is the destination; [after] can fail once the pages are there. */
        fun save(destination: ScanDestination, after: () -> Unit = {}, write: suspend () -> String): String? = runBlocking {
            val saving = pages.toList()
            val chosen = name
            scanCommitSave(
                write = write,
                onFailed = { e ->
                    log += "save_failed " + scanSaveFailedDiag(destination, scanSaveFailureReason(destination, e), saving.size, draftKept = true, ms = 0).joinToString(" ") { "${it.first}=${it.second}" }
                    told += "kept"
                },
                onWritten = {
                    log += "saved destination=${destination.id} pages=${saving.size}"
                    after()
                    if (keepRecent && destination != ScanDestination.Insert) {
                        recent.keep(null, ScanSession(null, null, false, chosen, saving), now = 1_000L)
                    } else {
                        saving.forEach { File(it.path).delete() }
                    }
                },
                onAuxFailed = { auxFailures++ },
                clear = {
                    pages.clear()
                    name = ""
                },
            )
        }
    }

    /** Downloads: what was published, and a write that can be made to fail. */
    private class Downloads {
        val published = mutableListOf<String>()
        val pending = mutableListOf<String>()
        var failAt: String? = null
        private var next = 0

        fun save(name: String): String = publishDownload(
            reserve = {
                if (failAt == "reserve") throw IOException("no entry")
                "row-${next++}".also { pending += it }
            },
            write = { if (failAt == "write") throw IOException("no space") },
            publish = { row ->
                if (failAt == "publish") 0 else {
                    pending -= row
                    published += name
                    1
                }
            },
            remove = { pending -= it },
            notPublished = { IOException("not published") },
        )
    }

    @Test
    fun aRefusedWriteKeepsTheScanAndSavingAgainMakesOnePdf() {
        for (keep in listOf(false, true)) for (failAt in listOf("reserve", "write", "publish")) {
            val draft = Draft(keepRecent = keep)
            val downloads = Downloads()
            val before = draft.asSaved()
            val sizes = draft.photos().map { it.length() }

            downloads.failAt = failAt
            assertNull("$failAt, keep $keep", draft.save(ScanDestination.Downloads) { downloads.save(draft.name) })
            // order, name, borders, turn, look and the photos are as they were
            assertEquals(before, draft.asSaved())
            assertEquals("Invoice March", draft.name)
            assertEquals(sizes, draft.photos().map { it.length() })
            assertTrue(draft.photos().all { it.isFile })
            // nothing says saved, nothing was kept in Recent scans, nothing is left half-written
            assertEquals(listOf("save_failed destination=downloads reason=write pages=3 draft_kept=true ms=0"), draft.log)
            assertEquals(listOf("kept"), draft.told)
            assertEquals(emptyList<ScanRecentEntry>(), draft.recent.list())
            assertEquals(emptyList<String>(), downloads.published)
            assertEquals(emptyList<String>(), downloads.pending)

            // the same scan saved again, now that Downloads takes it
            downloads.failAt = null
            val photos = draft.photos()
            assertNotNull(draft.save(ScanDestination.Downloads) { downloads.save(draft.name) })
            assertEquals(listOf("Invoice March"), downloads.published)
            assertEquals("saved destination=downloads pages=3", draft.log.last())
            assertEquals(1, draft.log.count { it.startsWith("saved") })
            // ended as a saved scan always was: the screen is empty, the photos kept or gone by the setting
            assertTrue(draft.pages.isEmpty())
            assertEquals("", draft.name)
            assertTrue(photos.none { it.exists() })
            assertEquals(if (keep) 1 else 0, draft.recent.list().size)
            if (keep) assertEquals(3, draft.recent.list()[0].pages)
        }
    }

    @Test
    fun aFailureAfterThePdfIsPublishedDoesNotMakeItTwice() {
        val draft = Draft(keepRecent = true)
        val downloads = Downloads()
        // Recent scans fails once the file is in Downloads
        val done = draft.save(ScanDestination.Downloads, after = { throw IOException("recent scans full") }) { downloads.save(draft.name) }
        assertNotNull(done)
        assertEquals(1, draft.auxFailures)
        assertEquals(listOf("Invoice March"), downloads.published)
        // the scan ended, so no second Save can publish the same pages again; nothing told a failure
        assertTrue(draft.pages.isEmpty())
        assertEquals(emptyList<String>(), draft.told)
        assertEquals(listOf("saved destination=downloads pages=3"), draft.log)
    }

    @Test
    fun pagesNotInsertedStayInTheScan() {
        for ((error, reason) in listOf<Pair<Exception, String>>(IllegalStateException("refused") to "tool", ScanDestinationGone() to "closed")) {
            val draft = Draft(keepRecent = false)
            val before = draft.asSaved()
            val inserted = mutableListOf<Int>()
            assertNull(draft.save(ScanDestination.Insert) { throw error })
            assertEquals(before, draft.asSaved())
            assertTrue(draft.photos().all { it.isFile })
            assertEquals(listOf("save_failed destination=insert reason=$reason pages=3 draft_kept=true ms=0"), draft.log)
            // the insert waited for: saved only once the document took the pages
            assertNotNull(draft.save(ScanDestination.Insert) { inserted += 3; "inserted" })
            assertEquals(listOf(3), inserted)
            assertEquals("saved destination=insert pages=3", draft.log.last())
            assertTrue(draft.pages.isEmpty())
        }
    }

    @Test
    fun aReplaceThatFailsHalfWayIsUndoneAndTheScanStays() {
        val draft = Draft(keepRecent = false)
        val before = draft.asSaved()
        // the PDF as a list of pages; each edit is one step of its Undo
        val pdf = mutableListOf("a", "b", "c")
        val undo = ArrayDeque<List<String>>()
        fun edit(i: Int, failAt: Int?) {
            if (i == failAt) throw IllegalStateException("page refused")
            undo.addLast(pdf.toList())
            pdf[i] = pdf[i].uppercase()
        }
        suspend fun replace(failAt: Int?): String {
            val applied = scanApplyOrUndo(3, apply = { edit(it, failAt) }, undo = { val was = undo.removeLast(); pdf.clear(); pdf += was })
            applied.error?.let { throw ScanReplaceFailed(applied.rolledBack, it) }
            return ScanPutBack.Written.id
        }
        assertNull(draft.save(ScanDestination.Replace) { replace(failAt = 2) })
        // the PDF is as it was, the scan too, and the log says the edits were undone
        assertEquals(listOf("a", "b", "c"), pdf)
        assertEquals(before, draft.asSaved())
        assertTrue(draft.photos().all { it.isFile })
        assertEquals(listOf("save_failed destination=replace reason=tool_rolled_back pages=3 draft_kept=true ms=0"), draft.log)
        // Done again makes the change once
        assertEquals("written", draft.save(ScanDestination.Replace) { replace(failAt = null) })
        assertEquals(listOf("A", "B", "C"), pdf)
        assertEquals("saved destination=replace pages=3", draft.log.last())
        assertTrue(draft.pages.isEmpty())
    }

    @Test
    fun editsAreUndoneLastFirstAndAFailedUndoIsSaid() = runBlocking {
        val calls = mutableListOf<String>()
        val all = scanApplyOrUndo(2, apply = { calls += "apply$it" }, undo = { calls += "undo" })
        assertEquals(2, all.applied)
        assertNull(all.error)
        assertEquals(listOf("apply0", "apply1"), calls)

        calls.clear()
        val refused = IllegalStateException("refused")
        val third = scanApplyOrUndo(4, apply = {
            if (it == 2) throw refused
            calls += "apply$it"
        }, undo = { calls += "undo" })
        assertEquals(2, third.applied)
        assertSame(refused, third.error)
        assertTrue(third.rolledBack)
        assertEquals(listOf("apply0", "apply1", "undo", "undo"), calls)

        // the first edit refused: nothing to take back, the PDF is as it was
        val first = scanApplyOrUndo(3, apply = { throw refused }, undo = { calls += "never" })
        assertEquals(0, first.applied)
        assertTrue(first.rolledBack)
        assertFalse("never" in calls)

        // an undo that fails leaves part of the change in the PDF: said, so the edits are not made over it
        val stuck = scanApplyOrUndo(3, apply = { if (it == 2) throw IllegalStateException("refused") }, undo = { throw IOException("undo refused") })
        assertFalse(stuck.rolledBack)
        assertEquals("undo refused", stuck.error!!.suppressed.single().message)
        assertEquals("tool_partial", scanSaveFailureReason(ScanDestination.Replace, ScanReplaceFailed(false, stuck.error!!)))
    }

    @Test
    fun theFailedLineHasNoPathOrName() {
        val line = scanSaveFailedDiag(ScanDestination.Replace, "not_written", pages = 2, draftKept = false, ms = 40).toMap()
        assertEquals(setOf("destination", "reason", "pages", "draft_kept", "ms"), line.keys)
        assertEquals("replace", line["destination"])
        assertEquals(false, line["draft_kept"])
        assertEquals(listOf("downloads", "insert", "replace"), ScanDestination.values().map { it.id })
        assertEquals(listOf("closed", "unchanged", "written", "not_written"), ScanPutBack.values().map { it.id })
    }
}
