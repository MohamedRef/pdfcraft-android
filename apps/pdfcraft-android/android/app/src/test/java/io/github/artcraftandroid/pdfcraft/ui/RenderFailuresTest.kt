package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.ImageKey
import io.github.artcraftandroid.pdfcraft.ui.viewer.ImageRequest
import io.github.artcraftandroid.pdfcraft.ui.viewer.RENDER_ATTEMPTS
import io.github.artcraftandroid.pdfcraft.ui.viewer.RENDER_RETRY_MS
import io.github.artcraftandroid.pdfcraft.ui.viewer.RenderFailures
import io.github.artcraftandroid.pdfcraft.ui.viewer.nextRenderRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderFailuresTest {
    private val page = ImageKey(page = 3, gen = 1, level = 0)

    @Test fun aTransientFailureIsRetriedAfterABackOff() {
        val f = RenderFailures()
        assertEquals(RENDER_RETRY_MS, f.record(page, now = 1000))
        val request = ImageRequest(page, 1f, null)
        assertNull("waits out the back-off", nextRenderRequest(listOf(request), emptySet(), emptySet(), f.blocked(1000 + RENDER_RETRY_MS - 1), true))
        assertEquals(request, nextRenderRequest(listOf(request), emptySet(), emptySet(), f.blocked(1000 + RENDER_RETRY_MS), true))
        f.succeeded(page)
        assertEquals(0, f.size)
    }

    @Test fun backOffDoublesAndGivesUpAfterTheLastAttempt() {
        val f = RenderFailures()
        var now = 0L
        for (attempt in 1 until RENDER_ATTEMPTS) {
            val wait = f.record(page, now)!!
            assertEquals(RENDER_RETRY_MS shl (attempt - 1), wait)
            now += wait
        }
        assertNull(f.record(page, now))
        assertTrue(page in f.blocked(Long.MAX_VALUE - 1))
    }

    @Test fun aNewGenerationForgetsOlderFailures() {
        val f = RenderFailures()
        repeat(RENDER_ATTEMPTS) { f.record(page, 0) }
        val newer = page.copy(gen = 2)
        f.record(newer, 0)
        f.generationWanted(2)
        assertEquals(setOf(newer), f.blocked(0))
        assertEquals(1, f.size)
    }
}
