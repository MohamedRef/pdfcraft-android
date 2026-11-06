package io.github.artcraftandroid.pdfcraft.ui.viewer

/** One annotation-toolbar session: touching the screen must not cancel an active pen stroke. */
internal class StylusRouting {
    var penSeen = false
        private set

    fun penStarted() { penSeen = true }
    fun fingersNavigate(penOnly: Boolean): Boolean = penOnly || penSeen
    fun cancelForExtraTouch(activePen: Boolean, touchCount: Int): Boolean = !activePen && touchCount > 1
}
