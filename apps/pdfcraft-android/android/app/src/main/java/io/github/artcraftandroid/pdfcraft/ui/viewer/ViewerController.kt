package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The tool the viewer's bottom bar has switched to (none: plain viewing). */
enum class ViewerTool {
    None,

    /** Comment: a note pinned where the page is tapped. */
    Comment,

    /** Highlight, underline or strikethrough over text dragged across ([TextMarkup]). */
    Markup,

    /** Freehand ink. */
    Draw,

    /** Free text typed onto the page. */
    Text,

    /** Fill & Sign: text, marks and signatures placed on a flat form. */
    FillSign,

    /** Edit PDF: the page's own text and images. */
    Edit,
}

/** The text-markup sub-tools, in the sub-toolbar's order. */
enum class TextMarkup { Highlight, Underline, Strikethrough, Squiggly }

/** The Comment tool's sub-tools: a typed note or a voice recording where the page is tapped. */
enum class CommentMode { Text, Voice, Stamp }

/** What the bottom sheet over the viewer shows. */
enum class ViewerSheet { None, More, FileDetails, Backdrops, TextStyles, ViewSettings, Attachments, Bookmarks, Comments, MoreTools, Signatures, ScanEnhance, OcrConfidence, SearchResults, Layers, EditContents, HiddenInfo, FocusTimer }

/**
 * The viewer's own state for one document, beside the page view ([ViewerState]): whether the
 * bars show, the active tool, the open sheet, search, Night Mode, and when the page indicator last
 * had reason to show.
 */
@Stable
class ViewerController {
    /** The top bar and bottom toolbar (a tap on the page toggles them). */
    var barsVisible by mutableStateOf(true)

    /** The layers' visibility when the Layers sheet first listed them: what Reset goes back to. */
    var initialLayers: List<Boolean>? = null

    var tool by mutableStateOf(ViewerTool.None)
    var markup by mutableStateOf(TextMarkup.Highlight)
    var commentMode by mutableStateOf(CommentMode.Text)
    /** The stamp the Comment tool's Stamp mode places ([STANDARD_STAMPS]). */
    var stamp by mutableStateOf(STANDARD_STAMPS.first())
    /** The Text tool places a callout (a text box with a leader to the tapped point). */
    var textCallout by mutableStateOf(false)
    /** Drawing sub-tool. Stylus eraser/button may temporarily override it per gesture. */
    var eraserMode by mutableStateOf(false)
    /** Select or move still-uncommitted Draw strokes without modifying saved PDF annotations. */
    var lassoMode by mutableStateOf(false)

    /** Draw: the ruler is shown (#150) and where it is, on screen; never saved to the PDF. */
    var rulerOn by mutableStateOf(false)
    internal var ruler by mutableStateOf<Ruler?>(null)

    /** Draw: the protractor is shown (#150) and where it is, on screen; never saved to the PDF. */
    /** The pen's last position in root coordinates, for the toolbars' palm guard (#156). */
    internal val penInRoot = PenTracker()

    var protractorOn by mutableStateOf(false)
    internal var protractor by mutableStateOf<Protractor?>(null)

    /** Draw: the zoom writing box (#130) and its frame on the page; viewer-only. */
    var zoomBoxOn by mutableStateOf(false)
    internal var zoomBox by mutableStateOf<ZoomBox?>(null)

    /** Draw: the next tap on the page picks the pen colour from the ink or comment there (#149). */
    var inkEyedropper by mutableStateOf(false)
    /** Explicit annotation shape, independent of freehand snap recognition. */
    internal var inkShapeMode by mutableStateOf(InkShapeMode.Freehand)

    var sheet by mutableStateOf(ViewerSheet.None)

    /** The active tool's open picker (colour, thickness, text size), and Fill & Sign's Sign panel. */
    var picker by mutableStateOf(Picker.None)
    var signPanel by mutableStateOf(false)

    /** Find is open in the top bar. */
    var searching by mutableStateOf(false)

    /** Pages drawn as inverted greyscale (View settings ▸ Night Mode). */
    var nightMode by mutableStateOf(false)

    /** View settings ▸ Reading colours, as set in the engine for this document. */
    var readingColors by mutableStateOf(ReadingColors.Off)

    /** Uptime (ms) of the last scroll or zoom: the scrubber and page pill show for a while after it. */
    var lastMoved by mutableLongStateOf(0L)

    /** Presentation mode (#157): the page alone, taps turn pages, a drag is a laser pointer. */
    var presenting by mutableStateOf(false)

    /** The page mode to go back to after presenting. */
    internal var modeBeforePresenting: PageMode? = null

    /** The Pages screen (thumbnails, and organizing) covers the viewer. */
    var pagesOpen by mutableStateOf(false)

    /** The Go to page dialog. */
    var goToPage by mutableStateOf(false)

    /** Where jumps (link, bookmark, contents, Go to page) came from, for Back and the "‹ Page N" chip. */
    val jumps = JumpHistory()

    /** Bumped when [jumps] changes, so the chip recomposes. */
    var jumpsVersion by mutableLongStateOf(0L)
        private set

    /** Uptime (ms) until which the jump chip shows. */
    var jumpChipUntil by mutableLongStateOf(0L)
        private set

    /** Bars can't hide while a tool, search or sheet is up (as the reference's). */
    val barsLocked: Boolean get() = tool != ViewerTool.None || searching || sheet != ViewerSheet.None

    fun toggleBars() {
        if (!barsLocked && !presenting) barsVisible = !barsVisible
    }

    /** Switch to [t]; tapping the active tool again goes back to plain viewing. */
    fun switchTool(t: ViewerTool) {
        tool = if (tool == t) ViewerTool.None else t
        if (tool != ViewerTool.Draw) {
            eraserMode = false
            lassoMode = false
            inkShapeMode = InkShapeMode.Freehand
        }
        picker = Picker.None
        signPanel = false
        barsVisible = true
        sheet = ViewerSheet.None
    }

    /** A jump is about to leave where [viewer] is: remember it and show the way back for a while. */
    fun rememberJump(viewer: ViewerState) {
        jumps.push(viewer.spot())
        jumpsChanged()
    }

    /** Back to where the last jump came from; false when there is nowhere to go back to. */
    fun jumpBack(viewer: ViewerState): Boolean {
        val to = jumps.goBack(viewer.spot()) ?: return false
        viewer.goTo(to)
        jumpsChanged()
        return true
    }

    /** Forward again to where [jumpBack] came from. */
    fun jumpForward(viewer: ViewerState): Boolean {
        val to = jumps.goForward(viewer.spot()) ?: return false
        viewer.goTo(to)
        jumpsChanged()
        return true
    }

    /** Show the chip again for a while (the reader stopped scrolling). */
    fun showJumpChip() {
        val now = android.os.SystemClock.uptimeMillis()
        if ((jumps.canGoBack || jumps.canGoForward) && now - lastJumpAt < JUMP_RESHOW_MS) jumpChipUntil = android.os.SystemClock.uptimeMillis() + JUMP_CHIP_MS
    }

    fun hideJumpChip() {
        jumpChipUntil = 0L
    }

    private var lastJumpAt = 0L

    private fun jumpsChanged() {
        jumpsVersion++
        lastJumpAt = android.os.SystemClock.uptimeMillis()
        jumpChipUntil = android.os.SystemClock.uptimeMillis() + JUMP_CHIP_MS
    }

    companion object {
        /** How long the scrubber and page pill stay after the last scroll or zoom. */
        const val INDICATOR_MS = 2000L
        /** How long the "‹ Page N" chip shows after a jump. */
        const val JUMP_CHIP_MS = 10_000L

        /** Scrolling to a stop shows the chip again for this long after the last jump. */
        const val JUMP_RESHOW_MS = 2L * 60 * 1000
    }
}
