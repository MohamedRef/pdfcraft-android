package io.github.artcraftandroid.pdfcraft.engine

/**
 * A new file in Downloads, in three steps that each have to succeed (#787): [reserve] a pending
 * entry, [write] the bytes into it, [publish] it (the number of rows the update changed: exactly
 * one, or the file would stay hidden as pending and the caller would think it saved, so that is
 * [notPublished]). When a step after the first fails, [remove] takes the pending entry away and
 * the failure is thrown; a failing [remove] is added to it and never hides it. Returns the entry.
 */
internal fun <T> publishDownload(
    reserve: () -> T,
    write: (T) -> Unit,
    publish: (T) -> Int,
    remove: (T) -> Unit,
    notPublished: () -> Exception,
): T {
    val entry = reserve()
    try {
        write(entry)
        if (publish(entry) != 1) throw notPublished()
        return entry
    } catch (failure: Exception) {
        try {
            remove(entry)
        } catch (cleanup: Exception) {
            if (cleanup !== failure) failure.addSuppressed(cleanup)
        }
        throw failure
    }
}
