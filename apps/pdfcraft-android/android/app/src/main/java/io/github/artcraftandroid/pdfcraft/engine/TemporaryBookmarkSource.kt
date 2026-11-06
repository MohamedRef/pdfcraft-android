package io.github.artcraftandroid.pdfcraft.engine

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Keep ownership of a temporary native document even if its blocking open outlives cancellation. */
internal suspend fun <T> withTemporaryBookmarkSource(
    open: suspend () -> Long,
    close: suspend (Long) -> Unit,
    block: suspend (Long) -> T,
): T {
    var source: Long? = null
    try {
        // Store the ID before control returns to a potentially cancelled caller.
        withContext(NonCancellable) { source = open() }
        currentCoroutineContext().ensureActive()
        return block(checkNotNull(source))
    } finally {
        source?.let { id -> withContext(NonCancellable) { close(id) } }
    }
}
