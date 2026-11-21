package io.github.artcraftandroid.pdfcraft.ui.scan

/*
 * The end of Save (#787): the scan on screen (its pages, photos, steps and name) stays until the
 * place the PDF goes to says the pages are there. Plain Kotlin, so the order is tested on the JVM.
 */

/** Where a saved scan's pages go; [id] is the log's word. */
internal enum class ScanDestination(val id: String) {
    /** A PDF of its own in Downloads. */
    Downloads("downloads"),
    /** Into the open document the scan was started from (Pages ▸ Insert). */
    Insert("insert"),
    /** Back into the PDF the scan was opened from in the viewer (#513). */
    Replace("replace"),
}

/** How putting a scan's pages back into the PDF it was opened from ended (#513, #787). */
internal enum class ScanPutBack(val id: String) {
    /** That PDF is no longer the one open: the caller saves a new file. */
    Closed("closed"),
    /** No page had changed: the PDF stays as it was. */
    Unchanged("unchanged"),
    /** The pages are in the PDF and its file was written. */
    Written("written"),
    /** The pages are in the open PDF, but its file refused the write: the viewer offers Save a copy (#491). */
    NotWritten("not_written"),
}

/** The open document the pages were for is closed: they can't go into it any more. */
internal class ScanDestinationGone : Exception()

/**
 * An edit that puts the pages back failed. [rolledBack]: the edits made before it were undone, so
 * the PDF is as it was and the same pages can be put back again; false leaves part of them in it.
 */
internal class ScanReplaceFailed(val rolledBack: Boolean, val error: Exception) : Exception(error.message, error)

/** What [scanApplyOrUndo] did: [applied] edits made, and the [error] that stopped it (null when all were made). */
internal class ScanApplied(val applied: Int, val error: Exception?, val rolledBack: Boolean)

/**
 * Make [count] edits in order with [apply] (the index from 0). When one fails, the edits already
 * made are taken back with [undo], one call each and the last first, so a document is never left
 * with half of a change. An [undo] that fails stops the taking back: the result says so.
 */
internal suspend fun scanApplyOrUndo(count: Int, apply: suspend (Int) -> Unit, undo: suspend () -> Unit): ScanApplied {
    var applied = 0
    try {
        while (applied < count) {
            apply(applied)
            applied++
        }
        return ScanApplied(applied, null, rolledBack = false)
    } catch (e: Exception) {
        var undone = 0
        try {
            while (undone < applied) {
                undo()
                undone++
            }
        } catch (u: Exception) {
            e.addSuppressed(u)
        }
        return ScanApplied(applied, e, rolledBack = undone == applied)
    }
}

/**
 * The last part of Save, in the order that keeps the scan safe (#787):
 *
 * 1. [write] puts the pages where they go and returns only when they are there. When it throws,
 *    nothing else here runs but [onFailed]: the scan, its photos and its name stay as they were,
 *    to be saved again. Null is returned.
 * 2. Once the pages are there, [onWritten] does what follows from it (the log's `saved` line,
 *    Recent scans, the photos). A failure in it goes to [onAuxFailed] and no further: the pages
 *    are already saved, so saving again would make them twice.
 * 3. [clear] ends the scan on screen, also when step 2 failed.
 */
internal suspend fun <T : Any> scanCommitSave(
    write: suspend () -> T,
    onFailed: suspend (Exception) -> Unit,
    onWritten: suspend (T) -> Unit,
    onAuxFailed: (Exception) -> Unit,
    clear: () -> Unit,
): T? {
    val done = try {
        write()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        onFailed(e)
        return null
    }
    try {
        onWritten(done)
    } catch (e: Exception) {
        onAuxFailed(e)
    } finally {
        clear()
    }
    return done
}

/** The fields of `save_failed` (#787): where the pages were going, why not, and whether the scan stayed. No path, no name. */
internal fun scanSaveFailedDiag(destination: ScanDestination, reason: String, pages: Int, draftKept: Boolean, ms: Long): Array<Pair<String, Any?>> =
    arrayOf("destination" to destination.id, "reason" to reason, "pages" to pages, "draft_kept" to draftKept, "ms" to ms)

/**
 * The log's word for why the pages did not arrive at [destination]: `closed` (the document they
 * were for is closed), `tool_rolled_back` and `tool_partial` (an edit failed, and whether the ones
 * before it were undone), `tool` (the insert was refused) or `write` (Downloads refused the file).
 */
internal fun scanSaveFailureReason(destination: ScanDestination, e: Exception): String = when {
    e is ScanDestinationGone -> "closed"
    e is ScanReplaceFailed -> if (e.rolledBack) "tool_rolled_back" else "tool_partial"
    destination == ScanDestination.Downloads -> "write"
    else -> "tool"
}
