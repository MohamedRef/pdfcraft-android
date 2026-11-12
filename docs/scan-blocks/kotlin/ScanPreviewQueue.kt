package io.github.artcraftandroid.pdfcraft.ui.scan

/**
 * What a preview is of (#301, spec S15 section 2): everything that changes its pixels, which is
 * the photo, the corners, the page's look settings (turns included) and the longest side of the
 * picture. Two equal keys are the same picture.
 */
data class ScanPreviewKey(val photo: String, val corners: List<Float>, val look: ScanPageLook, val maxSide: Int)

/**
 * The "latest wins" rule for one preview target, the main preview or one tile of the strip
 * (#301, spec S15 section 2). A render in the native side cannot be cancelled, so a render that
 * is no longer wanted runs to its end and its picture is dropped.
 *
 * How the screen drives it, on one coroutine per target:
 * - [ask] with the key wanted. A ticket means: start a render of that key now. Null means a
 *   render is running or the key is already on screen, so nothing starts.
 * - when the render returns, [done] with the ticket. Show the picture only when `show` is true.
 *   When `next` is set, start a render of it with `nextTicket` and call [done] again when it returns.
 * - on a failed render call `done(ticket, ok = false)`: nothing is shown and the failed key is not
 *   rendered again by the queue; a different key that was asked for still starts.
 *
 * Pure: no threads, no clock, no coroutines, and every method is total.
 */
class ScanPreviewQueue {
    /**
     * What [ScanPreviewQueue.done] says. [show]: the finished picture is the one wanted, so show it.
     * [next]: the key to render now, with its ticket [nextTicket]; both are null when nothing waits.
     */
    data class Finished(val show: Boolean, val next: ScanPreviewKey?, val nextTicket: Int?)

    /** The last ticket handed out. Tickets start at 1; 0 is never a ticket. */
    private var lastTicket: Int = 0

    /** The key being rendered now, or null. */
    private var runningKey: ScanPreviewKey? = null

    /** The ticket of the render being run, or null when nothing runs. */
    private var runningTicket: Int? = null

    /** The key asked for most recently: the one that is wanted. */
    private var latest: ScanPreviewKey? = null

    /** The key whose picture is on screen, or null before the first render arrives. */
    private var shown: ScanPreviewKey? = null

    /** The key whose picture is on screen, or null before the first render arrives. */
    val shownKey: ScanPreviewKey?
        get() = shown

    /** Whether a render is running now. */
    val isRunning: Boolean
        get() = runningKey != null

    /**
     * The key that waits for the running render to finish, or null. Asking for a key again while
     * it runs, or for the key on screen, leaves nothing waiting.
     */
    val waiting: ScanPreviewKey?
        get() {
            val wanted = latest ?: return null
            val running = runningKey ?: return null
            return if (wanted != running && wanted != shown) wanted else null
        }

    /**
     * Asks for [key] (latest wins). Returns a ticket when a render of [key] should start now,
     * which is when nothing is running and [key] is not already on screen. Otherwise returns null:
     * a running render keeps going, and [key] replaces any key that was waiting.
     */
    fun ask(key: ScanPreviewKey): Int? {
        latest = key
        if (runningKey != null) return null
        if (key == shown) return null
        runningKey = key
        lastTicket += 1
        runningTicket = lastTicket
        return lastTicket
    }

    /**
     * Reports that the render with [ticket] has ended. A ticket that is not the running one changes
     * nothing and returns `show = false`. See the class comment for what the caller does with the
     * result. [ok] is false when the render failed.
     */
    fun done(ticket: Int, ok: Boolean = true): Finished {
        val rendered = runningKey
        if (rendered == null || ticket != runningTicket) return Finished(show = false, next = null, nextTicket = null)
        runningKey = null
        runningTicket = null
        val wanted = latest
        val show = ok && wanted == rendered
        if (show) shown = rendered
        // a waiting key starts now, unless it is on screen already or it is the key that just ran
        if (wanted == null || wanted == shown || wanted == rendered) return Finished(show = show, next = null, nextTicket = null)
        runningKey = wanted
        lastTicket += 1
        runningTicket = lastTicket
        return Finished(show = show, next = wanted, nextTicket = lastTicket)
    }
}
