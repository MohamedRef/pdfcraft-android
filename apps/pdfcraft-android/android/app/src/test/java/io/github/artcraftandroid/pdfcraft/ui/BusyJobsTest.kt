package io.github.artcraftandroid.pdfcraft.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The busy dialog's jobs (#614): each counts, or is listed with the reason it cannot. */
class BusyJobsTest {
    /** The messages of every job in the code: a line that starts a job (job, docJob) or sets the busy message. */
    private fun jobMessages(): Set<String> =
        File("src/main/java").walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().asSequence()
                .filter { Regex("\\b(job|docJob)\\(|tools\\.busy = ").containsMatchIn(it) && !it.contains("fun job") && !it.contains("fun docJob") }
                .flatMap { line -> Regex("R\\.string\\.([a-z0-9_]+)").findAll(line).map { it.groupValues[1] } }
        }.toSet()

    @Test
    fun everyJobCountsOrSaysWhyNot() {
        val found = jobMessages()
        assertTrue("jobs found: $found", found.size > 20)
        val unlisted = found - BusyJobs.counted - BusyJobs.notCounted.keys
        assertEquals("jobs on neither list (#614)", emptySet<String>(), unlisted)
        assertEquals("on both lists", emptySet<String>(), BusyJobs.counted intersect BusyJobs.notCounted.keys)
        // nothing stale: every listed job still exists
        assertEquals("listed but gone", emptySet<String>(), (BusyJobs.counted + BusyJobs.notCounted.keys) - found)
    }

    @Test
    fun timeTakenReadsAsAClock() {
        assertEquals("0:00", busyElapsed(400))
        assertEquals("0:07", busyElapsed(7_900))
        assertEquals("12:30", busyElapsed(750_000))
        assertEquals("1:02:03", busyElapsed(3_723_000))
    }

    @Test
    fun aSmallFileHasNoNote() {
        assertEquals(null, busyLargeFileNote(BUSY_LARGE_FILE_BYTES - 1))
        assertEquals(null, busyLargeFileNote(0))
    }

    @Test
    fun stoppingIsAskedOnce() {
        val stop = JobStop()
        assertEquals(false, stop.asked)
        stop.asked = true
        assertEquals(true, stop.asked)
    }
}
