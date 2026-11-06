package io.github.artcraftandroid.pdfcraft.engine

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TemporaryBookmarkSourceTest {
    @Test fun cancellationDuringOpenClosesSourceWithoutEditingTarget() = runBlocking {
        val opening = CompletableDeferred<Unit>()
        val finishOpen = CompletableDeferred<Unit>()
        var copied = false
        val closed = mutableListOf<Long>()
        val job = launch {
            withTemporaryBookmarkSource(
                open = { opening.complete(Unit); finishOpen.await(); 7L },
                close = { delay(1); closed.add(it); Unit },
            ) { copied = true }
        }
        opening.await()
        job.cancel()
        finishOpen.complete(Unit)
        job.join()
        assertFalse(copied)
        assertEquals(listOf(7L), closed)
    }

    @Test fun failureInCopyStillClosesSource() {
        val closed = mutableListOf<Long>()
        assertThrows(IOException::class.java) {
            runBlocking {
                withTemporaryBookmarkSource<Unit>(open = { 8L }, close = { closed.add(it); Unit }) {
                    throw IOException("invalid bookmark source")
                }
            }
        }
        assertEquals(listOf(8L), closed)
    }

    @Test fun successfulCopyReturnsResultAndClosesExactlyOnce() = runBlocking {
        val closed = mutableListOf<Long>()
        val result = withTemporaryBookmarkSource(open = { 9L }, close = { closed.add(it); Unit }) { it + 1 }
        assertEquals(10L, result)
        assertEquals(listOf(9L), closed)
    }
}
