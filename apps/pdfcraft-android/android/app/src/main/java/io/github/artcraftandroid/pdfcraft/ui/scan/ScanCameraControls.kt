package io.github.artcraftandroid.pdfcraft.ui.scan

/*
 * Review pages, Undo and Done on the camera screen (#780), as pure functions so that they are
 * tested on the JVM: which of the three the screen shows, whether each can act now and why not,
 * the photos this camera handed to the scan since it opened (what Undo takes back and what Done
 * waits for), and the line that follows a photo. Behind the testing option "Review pages, Undo
 * and Done as separate camera buttons"; off is the one thumbnail that also was Done (#457).
 */

/** The three actions beside the shutter in Many pages and Book. */
internal enum class ScanCameraControl(val id: String) {
    /** The pages so far, opened on the newest without ending the scan. */
    Review("review"),

    /** The last photo this camera took comes out of the scan again. */
    Undo("undo"),

    /** The scan is finished: the document screen. */
    Done("done"),
}

/** Why a control cannot act now. The reason is written on the screen when the control is pressed, and TalkBack reads it with the control. */
internal enum class ScanCameraBlock(val id: String) {
    /** It can act. */
    None("none"),

    /** The scan has no page yet. */
    NoPages("no_pages"),

    /** A photo is being taken, or is on its way into the scan. */
    Busy("busy"),

    /** There are pages, but none that this camera took since it opened: an import or an earlier scan's page is not undone here. */
    NothingToUndo("nothing_to_undo"),
}

/** What the camera's bottom bar shows of the three controls, and whether each can act. */
internal data class ScanCameraControls(
    /** The controls on the bar; the places of the others stay empty, so the shutter never moves. */
    val shown: Set<ScanCameraControl> = emptySet(),
    /** The pages in the scan: the count on the Review pages button. Never the photos still on their way. */
    val pages: Int = 0,
    /** Photos taken that are not pages yet. */
    val pending: Int = 0,
    val review: ScanCameraBlock = ScanCameraBlock.None,
    val undo: ScanCameraBlock = ScanCameraBlock.None,
    val done: ScanCameraBlock = ScanCameraBlock.None,
) {
    fun block(control: ScanCameraControl): ScanCameraBlock = when (control) {
        ScanCameraControl.Review -> review
        ScanCameraControl.Undo -> undo
        ScanCameraControl.Done -> done
    }
}

/**
 * Which controls the bar shows. None with the testing option off ([on]) or for a retake (one photo
 * that replaces a page, then the camera closes). One page closes after its photo, so it has only
 * Review pages, and only once the scan has pages; Many pages and Book always show all three.
 */
internal fun scanCameraControlsShown(on: Boolean, mode: ScanCameraMode, retaking: Boolean, pages: Int): Set<ScanCameraControl> = when {
    !on || retaking -> emptySet()
    mode == ScanCameraMode.Single -> if (pages > 0) setOf(ScanCameraControl.Review) else emptySet()
    else -> ScanCameraControl.entries.toSet()
}

/**
 * The bar's controls and whether each can act:
 * - Review pages needs a page and no photo on its way ([busy]: being taken; [pending]: taken, not a page yet);
 * - Undo needs the same, and a page this camera took since it opened ([undoable]);
 * - Done needs a page, or a photo on its way, which it waits for.
 */
internal fun scanCameraControls(shown: Set<ScanCameraControl>, pages: Int, pending: Int, undoable: Boolean, busy: Boolean): ScanCameraControls {
    val count = pages.coerceAtLeast(0)
    val waiting = busy || pending > 0
    return ScanCameraControls(
        shown = shown, pages = count, pending = pending.coerceAtLeast(0),
        review = when {
            waiting -> ScanCameraBlock.Busy
            count == 0 -> ScanCameraBlock.NoPages
            else -> ScanCameraBlock.None
        },
        undo = when {
            waiting -> ScanCameraBlock.Busy
            count == 0 -> ScanCameraBlock.NoPages
            !undoable -> ScanCameraBlock.NothingToUndo
            else -> ScanCameraBlock.None
        },
        done = if (count == 0 && !waiting) ScanCameraBlock.NoPages else ScanCameraBlock.None,
    )
}

/**
 * The photos one camera handed to the scan since it opened, oldest first. A photo becomes a page a
 * little later (its thumbnail is read first), so each is waited for until it is seen among the
 * scan's pages. Kept while Review pages is open, so Undo still knows them after the way back;
 * emptied by Done, Close and Import.
 */
internal data class ScanCameraSession(
    val handed: List<Handed> = emptyList(),
    /** How many photos Undo took back. */
    val undone: Int = 0,
) {
    /** One photo: its file's path, when it was handed over, and whether it was seen as a page since. */
    data class Handed(val path: String, val at: Long, val committed: Boolean = false)

    /** Photos handed over that are not pages yet. */
    val pending: Int get() = handed.count { !it.committed }

    /** A photo was handed to the scan. */
    fun handed(path: String, at: Long): ScanCameraSession = if (handed.any { it.path == path }) this else copy(handed = handed + Handed(path, at))

    /** The photos that became pages since the last look at the scan's [pagePaths]. */
    fun newlySeen(pagePaths: Collection<String>): List<Handed> = handed.filter { !it.committed && it.path in pagePaths }

    /**
     * After a look at the scan's pages: a photo among them is a page now; one that was a page and
     * is gone (deleted or retaken on another screen) is forgotten, so Undo never reaches past it
     * to something the person did not just do.
     */
    fun seen(pagePaths: Collection<String>): ScanCameraSession {
        val next = handed.mapNotNull { h ->
            when {
                h.path in pagePaths -> if (h.committed) h else h.copy(committed = true)
                h.committed -> null
                else -> h
            }
        }
        return if (next == handed) this else copy(handed = next)
    }

    /** A photo that did not become a page within [PENDING_MS] is given up on, so nothing waits for it for ever. */
    fun expired(now: Long): ScanCameraSession {
        val next = handed.filter { it.committed || now - it.at < PENDING_MS }
        return if (next.size == handed.size) this else copy(handed = next)
    }

    /** When the oldest photo still waited for is given up on; null when none is. */
    fun nextExpiry(): Long? = handed.filter { !it.committed }.minOfOrNull { it.at + PENDING_MS }

    /**
     * The photo Undo takes back: the newest this camera handed over that is a page in [pagePaths].
     * Null while the newest is still on its way (Undo waits rather than take an older one), and
     * when there is none: before the first photo, and for pages that were imported or belong to
     * the scan from before this camera opened.
     */
    fun undoTarget(pagePaths: Collection<String>): String? {
        val newest = handed.lastOrNull { !it.committed || it.path in pagePaths } ?: return null
        return newest.path.takeIf { it in pagePaths }
    }

    /** [path] was taken back. */
    fun undone(path: String): ScanCameraSession = copy(handed = handed.filter { it.path != path }, undone = undone + 1)

    companion object {
        /** How long a photo is waited for to become a page, in milliseconds. */
        const val PENDING_MS = 6_000L
    }
}

/**
 * The pages Undo removes for the photo at [target], largest index first so each removal leaves the
 * others where they were. A book photo is two pages of one file (#303): both go, wherever the
 * pages were moved to.
 */
internal fun scanUndoIndexes(pagePaths: List<String>, target: String): List<Int> = pagePaths.indices.filter { pagePaths[it] == target }.sortedDescending()

/**
 * Whether closing the camera ends the scan altogether: with the testing option on, when no page is
 * left, none is on its way and Undo took at least one back. Without this the scan's name and
 * steps of the pages undone would stay for the next scan.
 */
internal fun scanCameraEndsEmpty(on: Boolean, pages: Int, pending: Int, undone: Int): Boolean = on && pages == 0 && pending == 0 && undone > 0

/** The line under the viewfinder after a photo or an Undo (#780): shown at once, for [MS]. */
internal enum class ScanCameraCue {
    /** The photo was taken and is on its way into the scan. */
    Taken,

    /** The scan's pages, counted. */
    Count,

    /** The scan has no page (after Undo took the only one back). */
    Empty,
    ;

    companion object {
        /** How long the line stays, in milliseconds. */
        const val MS = 1_500L

        /** How long the reason a control cannot act stays, in milliseconds. */
        const val BLOCKED_MS = 3_000L

        fun of(pages: Int, pending: Int): ScanCameraCue = when {
            pending > 0 -> Taken
            pages > 0 -> Count
            else -> Empty
        }
    }
}

/**
 * The log's `camera_action` line (#780): the [action] (`review`, `undo`, `done`, `close`), its
 * [result] (`ok`, `blocked`, `waits`), why it was blocked, and the counts. No names, no content.
 */
internal fun scanCameraActionDiag(action: String, result: String, why: ScanCameraBlock, pages: Int, pending: Int, removed: Int = 0): Array<Pair<String, Any?>> =
    arrayOf("action" to action, "result" to result, "why" to why.id, "pages" to pages, "pending" to pending, "removed" to removed)

/**
 * Where Review pages on the camera leads (#780): the scan's pages one under the other on the newest
 * page, with Back to camera, or the newest page in Enhance as at first, where the way back was
 * More, then Add page.
 */
internal enum class ScanReviewTarget(val id: String) {
    /** The document screen's continuous pages (#786), with Back to camera. */
    Pages("pages"),

    /** The newest page in Enhance: the testing option off, or no continuous pages to show. */
    Enhance("enhance"),
}

/**
 * Review pages opens the continuous pages when its testing option ([reviewReturn]) is on and they
 * exist: the scan's screens in steps ([steps]) and the continuous pages ([continuous]) are on.
 */
internal fun scanReviewTarget(reviewReturn: Boolean, steps: Boolean, continuous: Boolean): ScanReviewTarget =
    if (reviewReturn && steps && continuous) ScanReviewTarget.Pages else ScanReviewTarget.Enhance

/**
 * Whether a camera that closes keeps the photos Undo knows (#780): only the camera of a retake
 * asked for while the pages are under review, whether it took its photo or was closed without one.
 * The camera that Review pages left is still the one Back to camera returns to.
 */
internal fun scanReviewKeepsSession(reviewing: Boolean, retaking: Boolean): Boolean = reviewing && retaking

/** The log's `camera_review_end` line (#780): how the review ended ([by]: `button`, `back`, `add`, `import`), how long it showed, and the scan's pages. */
internal fun scanReviewEndDiag(by: String, ms: Long, pages: Int): Array<Pair<String, Any?>> =
    arrayOf("by" to by, "ms" to ms.coerceAtLeast(0L), "pages" to pages)
