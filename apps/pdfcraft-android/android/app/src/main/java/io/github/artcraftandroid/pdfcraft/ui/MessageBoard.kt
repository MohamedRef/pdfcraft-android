package io.github.artcraftandroid.pdfcraft.ui

/** What a message is, for its time on screen and what may replace it (#618). */
enum class MessageKind(val diag: String) {
    /** A routine notice ("Copied", "Saved"). */
    Notice("notice"),

    /** Undo or Redo naming the step it took: one message for a run of them, gone soon after the last tap. */
    Step("step"),

    /** Something failed or was refused: never hidden by a routine notice before it could be read. */
    Error("error"),

    /** A notice with a button ("3 pages deleted · Undo"). */
    WithButton("button"),
}

/** How a message left the screen (#618), for the diagnostics log. */
enum class MessageEnd(val diag: String) {
    Time("time"),
    Swipe("swipe"),
    Replaced("replaced"),
    Button("button"),

    /** The document or screen it was about closed. */
    Closed("closed"),

    /** Its button would no longer do what it names: the document changed again. */
    Stale("stale"),
}

/**
 * The one message at the bottom of the screen, and the rule for what replaces it (#618). No queue:
 * a message is shown when it is raised or not at all, and never later.
 *
 * - A new message replaces the one on screen at once.
 * - The same text raised again while it is shown stays where it is; its time starts again.
 * - Undo/Redo steps update one message in place; it goes [STEP_MS] after the last one.
 * - A routine message raised while an error younger than [ERROR_MIN_MS] is shown is dropped, so the
 *   error can be read. Errors in a row update one message in place, with how many happened.
 * - A message goes when its owner (the document it was about) leaves the front, errors excepted.
 * - A button whose action is no longer valid (the document changed again) closes the message
 *   instead of acting.
 *
 * No Compose in here, so JVM tests cover it: the UI calls [expire] when [Shown.until] has passed.
 * [now] is a monotonic clock in milliseconds; [timeout] lets the UI lengthen times for the
 * accessibility timeout setting; [log] gets one line per shown and per ended message.
 */
class MessageBoard(
    private val now: () -> Long,
    private val log: (MessageLog) -> Unit = {},
) {
    /** One message on screen. [id] changes only when a new message slides in; in-place updates keep it. */
    class Shown internal constructor(
        val id: Long,
        val text: String,
        val kind: MessageKind,
        /** How many errors (or steps) this one message stands for; shown as a count for errors. */
        val count: Int,
        val actionLabel: String?,
        internal val action: (() -> Unit)?,
        internal val owner: Any?,
        internal val valid: (() -> Boolean)?,
        internal val shownAt: Long,
        val until: Long,
        internal val updates: Int,
    )

    /** Lengthens a time on screen: (ms, has a button) → ms. The accessibility timeout setting, in the app. */
    var timeout: (Long, Boolean) -> Long = { ms, _ -> ms }

    /** Called with the new message (or null) whenever it changes. */
    var onChange: (Shown?) -> Unit = {}

    var current: Shown? = null
        private set(value) {
            field = value
            onChange(value)
        }

    private var nextId = 1L

    /**
     * Raise [text]. [owner] is the document it is about (null: the app as a whole); [valid] is asked
     * before the button acts and whenever the owner changes. Returns whether it is on screen.
     */
    fun raise(
        text: String,
        kind: MessageKind = MessageKind.Notice,
        actionLabel: String? = null,
        action: (() -> Unit)? = null,
        owner: Any? = null,
        valid: (() -> Boolean)? = null,
    ): Boolean {
        if (text.isBlank()) return false
        val t = now()
        val k = if (actionLabel != null && kind == MessageKind.Notice) MessageKind.WithButton else kind
        val cur = current
        if (cur != null) {
            val same = cur.text == text && cur.kind == k && cur.owner === owner && cur.actionLabel == actionLabel
            when {
                // the same message again: it stays, its time starts again
                same -> {
                    current = cur.updated(text, k, t, actionLabel, action, valid, count = if (k == MessageKind.Error) cur.count + 1 else cur.count)
                    return true
                }
                // an error is read before anything routine replaces it
                cur.kind == MessageKind.Error && k != MessageKind.Error && t - cur.shownAt < ERROR_MIN_MS -> {
                    log(MessageLog.Dropped(k, cur.kind))
                    return false
                }
                // a run of Undo/Redo, or of errors: one message, updated in place
                (cur.kind == MessageKind.Step || cur.kind == MessageKind.Error) && k == cur.kind && cur.owner === owner -> {
                    current = cur.updated(text, k, t, actionLabel, action, valid, count = cur.count + 1)
                    return true
                }
                else -> end(cur, MessageEnd.Replaced, t)
            }
        }
        val shown = Shown(
            id = nextId++, text = text, kind = k, count = 1, actionLabel = actionLabel, action = action,
            owner = owner, valid = valid, shownAt = t, until = t + duration(k), updates = 0,
        )
        current = shown
        log(MessageLog.Started(k))
        return true
    }

    private fun Shown.updated(text: String, kind: MessageKind, t: Long, actionLabel: String?, action: (() -> Unit)?, valid: (() -> Boolean)?, count: Int) =
        Shown(id, text, kind, count, actionLabel, action, owner, valid, shownAt, t + duration(kind), updates + 1)

    /** How long a message of [kind] stays, before [timeout]. */
    private fun duration(kind: MessageKind): Long = timeout(
        when (kind) {
            MessageKind.Notice -> NOTICE_MS
            MessageKind.Step -> STEP_MS
            MessageKind.Error -> ERROR_MS
            MessageKind.WithButton -> BUTTON_MS
        },
        kind == MessageKind.WithButton,
    )

    /** The message's time is up (the UI calls this at [Shown.until]); early calls do nothing. */
    fun expire() {
        val cur = current ?: return
        val t = now()
        if (t >= cur.until) end(cur, MessageEnd.Time, t, clear = true)
    }

    /** Swiped away (or its ✕): nothing comes up behind it. */
    fun dismiss() {
        val cur = current ?: return
        end(cur, MessageEnd.Swipe, now(), clear = true)
    }

    /** Its button was tapped: acts only if the button still does what the message names. */
    fun press(): Boolean {
        val cur = current ?: return false
        val t = now()
        if (cur.valid?.invoke() == false) {
            end(cur, MessageEnd.Stale, t, clear = true)
            return false
        }
        end(cur, MessageEnd.Button, t, clear = true)
        cur.action?.invoke()
        return cur.action != null
    }

    /** [front] is now the document (or null: Home) in front: a message about another one goes. */
    fun frontChanged(front: Any?) {
        val cur = current ?: return
        if (cur.owner != null && cur.owner !== front && cur.kind != MessageKind.Error) end(cur, MessageEnd.Closed, now(), clear = true)
    }

    /** [owner] changed (an edit, an undo): a button that would no longer do what it names goes. */
    fun ownerChanged(owner: Any?) {
        val cur = current ?: return
        if (cur.owner === owner && cur.valid?.invoke() == false) end(cur, MessageEnd.Stale, now(), clear = true)
    }

    private fun end(cur: Shown, how: MessageEnd, t: Long, clear: Boolean = false) {
        log(MessageLog.Ended(cur.kind, how, t - cur.shownAt, cur.updates))
        if (clear) current = null
    }

    companion object {
        /** A routine notice, as Material's short snackbar. */
        const val NOTICE_MS = 4_000L

        /** Undo/Redo: gone about two seconds after the last tap. */
        const val STEP_MS = 2_000L

        /** An error stays longer, and is not replaced by a notice for [ERROR_MIN_MS]. */
        const val ERROR_MS = 6_000L
        const val ERROR_MIN_MS = 3_000L

        /** A message with a button, shorter than Material's ten-second long snackbar. */
        const val BUTTON_MS = 8_000L
    }
}

/** What the diagnostics log is told about a message: never its text, which can hold file names. */
sealed class MessageLog {
    data class Started(val kind: MessageKind) : MessageLog()
    data class Ended(val kind: MessageKind, val how: MessageEnd, val ms: Long, val updates: Int) : MessageLog()
    data class Dropped(val kind: MessageKind, val behind: MessageKind) : MessageLog()
}
