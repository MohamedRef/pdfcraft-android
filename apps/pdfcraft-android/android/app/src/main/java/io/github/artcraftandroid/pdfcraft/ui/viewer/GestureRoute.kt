package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.ui.geometry.Offset

/** The kind of pointer a tool gesture starts with or is taken over by. */
internal enum class ToolPointer { Touch, Stylus, Eraser }

/** What a one-pointer tool gesture does. */
internal enum class GestureRole { Draw, Erase, Navigate, Palm, Overlay }

/**
 * The routing decisions of [toolGestures], kept out of the pointer loop so they can be tested
 * on the JVM (#81): which role a gesture takes at contact, how an arriving pen takes over from a
 * finger, when a second finger turns a finger stroke into navigation, and whether a gesture
 * moved beyond touch slop (a tap, for a pen as for a finger, #74).
 */
internal class GestureRoute(private val slop: Float, private val routing: StylusRouting = StylusRouting()) {
    var role: GestureRole = GestureRole.Navigate
        private set
    var mode: PenDownMode = PenDownMode.Ink
        private set
    /** A pen or eraser tip owns the gesture: touches never interrupt it. */
    var activePen = false
        private set
    /** Where the active pointer went down. */
    var origin: Offset = Offset.Zero
        private set
    /** The active pointer has left touch slop from [origin]: the gesture is not a tap. */
    var moved = false
        private set

    /**
     * The first contact: [buttonHeld] is the pen's side button at contact (read only then,
     * #82, #144); [overlay] and [palm] are a finger on the ruler or one the writing-hand guard
     * rejects (#150, #156).
     */
    fun start(
        pointer: ToolPointer,
        at: Offset,
        buttonHeld: Boolean = false,
        hold: PenButtonHold = PenButtonHold.Eraser,
        penOnly: Boolean = false,
        overlay: Boolean = false,
        palm: Boolean = false,
    ): GestureRole {
        origin = at
        moved = false
        activePen = pointer != ToolPointer.Touch
        mode = penDownMode(pointer == ToolPointer.Eraser, pointer == ToolPointer.Stylus && buttonHeld, hold)
        if (activePen) routing.penStarted()
        val touch = pointer == ToolPointer.Touch
        role = when {
            touch && overlay -> GestureRole.Overlay
            touch && palm -> GestureRole.Palm
            touch && routing.fingersNavigate(penOnly) -> GestureRole.Navigate
            mode == PenDownMode.Erase -> GestureRole.Erase
            else -> GestureRole.Draw
        }
        return role
    }

    /**
     * A pen touches down while a finger (or palm) holds the gesture: the pen takes it over from
     * where it touched. Returns null when a pen already owns the gesture.
     */
    fun penArrives(pointer: ToolPointer, at: Offset, buttonHeld: Boolean, hold: PenButtonHold = PenButtonHold.Eraser): GestureRole? {
        if (activePen || pointer == ToolPointer.Touch) return null
        activePen = true
        routing.penStarted()
        mode = penDownMode(pointer == ToolPointer.Eraser, buttonHeld, hold)
        role = if (mode == PenDownMode.Erase) GestureRole.Erase else GestureRole.Draw
        origin = at
        moved = false
        return role
    }

    /**
     * [count] fingers are down on a finger gesture: with more than one, this frame pans and zooms,
     * and a finger stroke in progress is cancelled for good. Returns whether this frame navigates.
     */
    fun touches(count: Int): Boolean {
        if (activePen) return false
        val extra = routing.cancelForExtraTouch(false, count)
        if (extra && role == GestureRole.Draw) role = GestureRole.Navigate
        return extra
    }

    /** The active pointer is at [at]. */
    fun motion(at: Offset) {
        if ((at - origin).getDistance() > slop) moved = true
    }
}
