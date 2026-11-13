package io.github.artcraftandroid.pdfcraft.ui.scan

import androidx.annotation.StringRes
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.core.ScanFindOptions
import io.github.artcraftandroid.pdfcraft.core.ScanFinder

/**
 * What a press of Auto detect on the crop screen does (#443). The same search on the same photo
 * gives the same answer, so a search that already found nothing (when the photo was imported, or
 * on this screen) is not run again: the next press tries the line detector instead, and once that
 * has found nothing too, the press only says again that nothing was found.
 */
internal sealed interface ScanCropDetectPlan {
    /** Search with [options]; [other] when they are not the testing options' own. */
    data class Search(val options: ScanFindOptions, val other: Boolean) : ScanCropDetectPlan

    /** Every search this screen can make has found nothing on this photo. */
    data object AlreadySearched : ScanCropDetectPlan
}

/**
 * The plan for the next press: [asked] are the testing options' search, [empty] the searches that
 * already found nothing on this photo. With [feedback] off (the testing option), every press runs
 * [asked] again, as before #443.
 *
 * Only the Auto detector has another to try: Auto is the network when its model file is there,
 * so the line detector is a different search. When the network's model file is missing, Auto
 * already is the line detector and the second search repeats it; the models ship in the app.
 */
internal fun scanCropDetectPlan(asked: ScanFindOptions, empty: Set<ScanFindOptions>, feedback: Boolean): ScanCropDetectPlan {
    if (!feedback) return ScanCropDetectPlan.Search(asked, other = false)
    val tries = buildList {
        add(asked)
        if (asked.finder == ScanFinder.AUTO) add(asked.copy(finder = ScanFinder.LINES))
    }
    val next = tries.firstOrNull { it !in empty } ?: return ScanCropDetectPlan.AlreadySearched
    return ScanCropDetectPlan.Search(next, other = next != asked)
}

/** The searches already known to find nothing when the crop screen opens: the import's, if it found nothing. */
internal fun scanCropDetectEmptyAtOpen(found: FloatArray?, importAsked: ScanFindOptions?): Set<ScanFindOptions> =
    if (found == null && importAsked != null) setOf(importAsked) else emptySet()

/** The line the crop screen shows after a press (#443), and its name in the log. */
internal enum class ScanCropDetectNote(val id: String, @StringRes val text: Int) {
    /** The search found nothing. */
    NoneFound("none", R.string.scan_crop_detect_none),

    /** The line detector, tried because the first search found nothing, found nothing either. */
    NoneFoundOther("none_other", R.string.scan_crop_detect_none_other),

    /** Not searched again: every search has found nothing on this photo. */
    AlreadySearched("already", R.string.scan_crop_detect_already),

    /** The line detector found the page that the first search did not. */
    FoundOther("found_other", R.string.scan_crop_detect_found_other),

    /** The native call failed. */
    Failed("failed", R.string.scan_crop_detect_failed),
}

/** The note for a search's outcome: [found] a page, or [failed] in the native call; null when the page was found the ordinary way. */
internal fun scanCropDetectNote(found: Boolean, failed: Boolean, other: Boolean): ScanCropDetectNote? = when {
    failed -> ScanCropDetectNote.Failed
    found -> if (other) ScanCropDetectNote.FoundOther else null
    other -> ScanCropDetectNote.NoneFoundOther
    else -> ScanCropDetectNote.NoneFound
}
