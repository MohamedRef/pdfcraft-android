package io.github.artcraftandroid.pdfcraft.ui

/**
 * The one request waiting for a system picker's result (Save a copy). A new request displaces the
 * old one, which the caller must resolve, so a request whose picker never returned can't take a
 * later result for itself (#104). Main-thread only, like the picker callbacks.
 */
internal class PendingRequest<T : Any> {
    private var current: T? = null

    /** Wait for a result for [request]; returns the request it displaced, if any. */
    fun start(request: T): T? {
        val old = current
        current = request
        return old
    }

    /** The waiting request, which no longer waits; null when nothing does. */
    fun take(): T? {
        val r = current
        current = null
        return r
    }
}
