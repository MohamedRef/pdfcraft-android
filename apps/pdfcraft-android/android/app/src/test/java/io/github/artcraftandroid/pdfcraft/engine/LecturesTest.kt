package io.github.artcraftandroid.pdfcraft.engine

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LecturesTest {
    @Test
    fun recordingsAreKeptPerDocumentNewestFirst() {
        val dir = Files.createTempDirectory("lectures").toFile()
        val store = Lectures(dir)
        val (id1, f1) = store.newFile(1_000)
        f1.writeText("audio")
        store.put("content://a", Lecture(id1, 1_000, 60_000, listOf(LectureMark("c1", 0, 5_000))))
        store.put("content://a", Lecture("lecture-2000", 2_000, 30_000, emptyList()))
        store.put("content://b", Lecture("lecture-3000", 3_000, 10_000, emptyList()))
        assertEquals(listOf("lecture-2000", id1), store.list("content://a").map { it.id })
        // marks added later replace the entry
        store.put("content://a", Lecture(id1, 1_000, 61_000, listOf(LectureMark("c1", 0, 5_000), LectureMark("c2", 3, 40_000))))
        assertEquals(2, Lectures(dir).list("content://a").first { it.id == id1 }.marks.size)
        store.delete("content://a", id1)
        assertEquals(listOf("lecture-2000"), store.list("content://a").map { it.id })
        assertTrue(!f1.exists())
        assertEquals(1, store.list("content://b").size)
    }

    @Test
    fun idsCannotLeaveTheFolder() {
        assertTrue(Lectures.validId("lecture-123"))
        for (bad in listOf("../x", "lecture-1/../../a", "lecture-", "x")) assertTrue(!Lectures.validId(bad))
        assertNull(Lecture.from(org.json.JSONObject().put("id", "../../etc")))
    }

    @Test
    fun tappingACommentPlaysFromJustBeforeItWasWritten() {
        val old = Lecture("lecture-1", 1, 100_000, listOf(LectureMark("c1", 0, 50_000)))
        val new = Lecture("lecture-2", 2, 100_000, listOf(LectureMark("c2", 1, 1_000), LectureMark("c3", 1, 20_000)))
        assertEquals(old to 47_000L, Lectures.seekFor(listOf(new, old), "c1"))
        assertEquals("never before the start", new to 0L, Lectures.seekFor(listOf(old, new), "c2"))
        assertNull(Lectures.seekFor(listOf(old, new), "nope"))
        assertEquals(setOf("c2"), Lectures.writtenBy(new, 10_000))
    }

    @Test
    fun theClockLeavesPausesOut() {
        var t = 0L
        val clock = RecordingClock { t }
        clock.start(); t = 1_000; clock.pause(); t = 5_000
        assertEquals(1_000, clock.elapsed())
        clock.start(); t = 7_500
        assertEquals(3_500, clock.elapsed())
    }
}
