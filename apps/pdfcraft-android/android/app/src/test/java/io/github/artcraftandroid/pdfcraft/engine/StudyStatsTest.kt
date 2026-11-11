package io.github.artcraftandroid.pdfcraft.engine

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyStatsTest {
    @Test
    fun idleTimeOverTwoMinutesIsNotCounted() {
        val clock = StudyClock()
        assertEquals(0, clock.activity(0))
        assertEquals(30_000, clock.activity(30_000))
        // ten minutes without a touch: only two of them count
        assertEquals(120_000, clock.activity(630_000))
        assertEquals(5_000, clock.pause(635_000))
        // after a pause the next touch starts afresh
        assertEquals(0, clock.activity(10_000_000))
        assertEquals(155_000, clock.total)
    }

    @Test
    fun recordsTimePagesAndDaysAndRoundTrips() {
        val dir = Files.createTempDirectory("study").toFile()
        try {
            val stats = StudyStats(File(dir, "study.json")) { ZoneOffset.UTC }
            val day1 = LocalDate.of(2026, 11, 9).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 3_600_000
            stats.record("content://a", "Bio.pdf", 10, setOf(0, 1, 2), lastPage = 2, ms = 60_000, at = day1)
            stats.record("content://a", "Bio.pdf", 10, setOf(2, 3, 99), lastPage = 3, ms = 30_000, at = day1 + 86_400_000, annotations = 2)
            stats.record("content://b", "Chem.pdf", 4, setOf(0), lastPage = 0, ms = 10_000, at = day1 + 1000)
            val a = stats.get("content://a")!!
            assertEquals(90_000, a.activeMs)
            assertEquals(setOf(0, 1, 2, 3), a.pagesSeen)
            assertEquals(0.4f, a.progress, 0.001f)
            assertEquals(3, a.lastPage)
            assertEquals(2, a.annotations)
            // most recently studied first
            assertEquals(listOf("content://a", "content://b"), stats.all().map { it.uri })
            val week = stats.week(LocalDate.of(2026, 11, 10))
            assertEquals(7, week.size)
            assertEquals(listOf(70_000L, 30_000L), week.takeLast(2))
            assertEquals(listOf(60_000L, 30_000L), stats.week(LocalDate.of(2026, 11, 10), setOf("content://a")).takeLast(2))
            stats.clear()
            assertNull(stats.get("content://a"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun focusTimerAlternatesAndSurvivesItsJson() {
        val t = FocusTimer(workMs = 25 * 60_000L, breakMs = 5 * 60_000L).start(0)
        assertTrue(t.running)
        assertEquals(25 * 60_000L, t.left(0))
        val onBreak = t.tick(25 * 60_000L + 1)
        assertTrue(onBreak.onBreak)
        assertEquals(30 * 60_000L, onBreak.endsAt)
        // backgrounded through several phases
        val later = t.tick(65 * 60_000L)
        assertFalse(later.onBreak)
        assertEquals(85 * 60_000L, later.endsAt)
        val paused = t.pause(10 * 60_000L)
        assertFalse(paused.running)
        assertEquals(15 * 60_000L, paused.left(99 * 60_000L))
        val resumed = paused.resume(100 * 60_000L)
        assertEquals(115 * 60_000L, resumed.endsAt)
        assertEquals(resumed, FocusTimer.fromJson(resumed.toJson()))
        assertFalse(resumed.stop().active)
    }

}
