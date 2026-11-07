package io.github.artcraftandroid.pdfcraft.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal const val MAX_BATES_FILES = 20
internal const val MAX_BATES_BYTES = 512L * 1024L * 1024L

/** Numeric bounds avoid unsigned JSON precision loss on Android and leave ample page-count room. */
internal data class BatesSettings private constructor(val start: Long, val digits: Int, val prefix: String, val suffix: String) {
    val preview: String get() = prefix + start.toString().padStart(digits, '0') + suffix

    companion object {
        fun parse(start: String, digits: String, prefix: String, suffix: String): BatesSettings? {
            val n = start.toLongOrNull()?.takeIf { it in 1..999_999_999_999L } ?: return null
            val d = digits.toIntOrNull()?.takeIf { it in 1..12 } ?: return null
            fun valid(s: String): Boolean {
                var i = 0
                while (i < s.length) {
                    val c = s[i++]
                    if (c.isHighSurrogate()) {
                        if (i == s.length || !s[i++].isLowSurrogate()) return false
                    } else if (c.isLowSurrogate()) return false
                }
                return s.toByteArray(Charsets.UTF_8).size <= 128 && '#' !in s && ">>" !in s
            }
            return if (valid(prefix) && valid(suffix)) BatesSettings(n, d, prefix, suffix) else null
        }
    }
}

internal data class BatesInterval(val pages: Long, val first: Long, val last: Long)

/** Validate the entire native report before any output becomes public. Failed files reserve ranges. */
internal fun validateBatesIntervals(intervals: List<BatesInterval>, count: Int, start: Long) {
    require(count in 1..MAX_BATES_FILES && intervals.size == count)
    var next = start
    intervals.forEachIndexed { i, r ->
        require(r.pages > 0 && r.first == next && r.last >= r.first && r.last - r.first == r.pages - 1)
        if (i + 1 < count) {
            require(r.last < Long.MAX_VALUE)
            next = r.last + 1
        }
    }
}

/** Roll back only this newly reserved download, including cancellation before publishing it. */
internal fun <T> publishNewBatchCopy(
    reserve: () -> T,
    write: (T) -> Unit,
    commit: (T) -> Unit,
    rollback: (T) -> Unit,
    checkpoint: () -> Unit,
): T {
    checkpoint()
    val entry = reserve()
    try {
        checkpoint()
        write(entry)
        checkpoint()
        commit(entry)
        return entry
    } catch (failure: Throwable) {
        try { rollback(entry) } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
        throw failure
    }
}

/** Remember a committed row before dispatching back to a caller that may already be cancelled. */
internal suspend fun <T> rememberBatesPublication(publish: suspend () -> T, remember: (T) -> Unit) {
    withContext(NonCancellable + Dispatchers.IO) { remember(publish()) }
}
