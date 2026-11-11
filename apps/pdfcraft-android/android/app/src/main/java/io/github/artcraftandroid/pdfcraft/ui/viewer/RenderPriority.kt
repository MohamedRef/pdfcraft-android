package io.github.artcraftandroid.pdfcraft.ui.viewer

/** Visible requests always precede speculative work, even when the caller order changes. */
internal fun nextRenderRequest(requests: List<ImageRequest>, cached: Set<ImageKey>, inFlight: Set<ImageKey>, failed: Set<ImageKey>, allowPrefetch: Boolean): ImageRequest? {
    fun eligible(r: ImageRequest) = r.key !in cached && r.key !in inFlight && r.key !in failed
    return requests.firstOrNull { !it.prefetch && eligible(it) }
        ?: if (allowPrefetch) requests.firstOrNull { eligible(it) } else null
}

/** Renders of one image tried at most this many times per generation before it is left blank. */
internal const val RENDER_ATTEMPTS = 3

/** The wait before the first retry; it doubles for each further failure. */
internal const val RENDER_RETRY_MS = 400L

/**
 * Failed renders, per image (#99). A failure is often transient (a bitmap allocation under memory
 * pressure, a race with reopening the document), so a key is retried after a back-off, up to
 * [RENDER_ATTEMPTS] tries; only then is it left out until the next edit. Not thread-safe: the
 * caller holds its lock. [now] is a monotonic clock in milliseconds.
 */
internal class RenderFailures {
    private class Entry(var count: Int, var retryAt: Long)
    private val entries = HashMap<ImageKey, Entry>()
    private var newestGen = Long.MIN_VALUE

    /** Records a failed render of [key]; returns the wait before it may be tried again, or null for never. */
    fun record(key: ImageKey, now: Long): Long? {
        val e = entries.getOrPut(key) { Entry(0, now) }
        e.count++
        if (e.count >= RENDER_ATTEMPTS) {
            e.retryAt = Long.MAX_VALUE
            return null
        }
        val wait = RENDER_RETRY_MS shl (e.count - 1)
        e.retryAt = now + wait
        return wait
    }

    /** A render of [key] succeeded: it starts afresh. */
    fun succeeded(key: ImageKey) {
        entries.remove(key)
    }

    /** The keys that may not be rendered at [now]. */
    fun blocked(now: Long): Set<ImageKey> = entries.filterValues { now < it.retryAt }.keys

    /** The view asked for generation [gen]: failures of older generations are forgotten. */
    fun generationWanted(gen: Long) {
        if (gen <= newestGen) return
        newestGen = gen
        entries.keys.removeAll { it.gen < gen }
    }

    fun clear() {
        entries.clear()
        newestGen = Long.MIN_VALUE
    }

    val size: Int get() = entries.size
}
