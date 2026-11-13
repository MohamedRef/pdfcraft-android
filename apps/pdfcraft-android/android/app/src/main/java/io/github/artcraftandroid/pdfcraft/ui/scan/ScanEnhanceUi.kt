package io.github.artcraftandroid.pdfcraft.ui.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.pdfColors

/*
 * Enhance (#459, part of #455): the scan screen with the page taking all the room above one
 * strip of looks and one bar of five icons. The settings of a look are in a sheet that leaves
 * the page in view. Behind the testing option "Scan screens in steps".
 */

/** What More holds, in order. */
internal enum class ScanEnhanceMore(val id: String) {
    ApplyAll("apply_all"),
    ResetLook("reset_look"),
    Compare("compare"),
    AddPage("add_page"),
    Rename("rename"),
}

/** Which of More's items can be used now. */
internal fun scanEnhanceMoreEnabled(item: ScanEnhanceMore, pages: Int, applyNeeded: Boolean, lookIsDefault: Boolean, canCompare: Boolean, canRename: Boolean): Boolean = when (item) {
    ScanEnhanceMore.ApplyAll -> pages > 1 && applyNeeded
    ScanEnhanceMore.ResetLook -> pages > 0 && !lookIsDefault
    ScanEnhanceMore.Compare -> canCompare
    ScanEnhanceMore.AddPage -> true
    ScanEnhanceMore.Rename -> canRename
}

/** The page shown after a swipe or an arrow: one step, never past either end. */
internal fun scanEnhancePageAfter(current: Int, pages: Int, step: Int): Int = if (pages <= 0) 0 else (current + step).coerceIn(0, pages - 1)

/** A horizontal drag long enough to turn the page: a sixth of the page area's width. */
internal fun scanEnhanceSwipeStep(dragX: Float, width: Float, rtl: Boolean): Int {
    if (width <= 0f || kotlin.math.abs(dragX) < width / 6f) return 0
    val towardEnd = if (rtl) dragX > 0 else dragX < 0
    return if (towardEnd) 1 else -1
}

/** The bottom bar: Retake, Borders, Rotate, Delete, More. Five icons with words, so nothing scrolls sideways in any language. */
@Composable
internal fun ScanEnhanceBar(
    enabled: Boolean,
    moreEnabled: (ScanEnhanceMore) -> Boolean,
    onRetake: () -> Unit,
    onBorders: () -> Unit,
    onRotate: () -> Unit,
    onDelete: () -> Unit,
    onMore: (ScanEnhanceMore) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
        // five equal shares of the width: they fit a 360 dp phone in every language
        val share = Modifier.weight(1f)
        ScanBarButton(LucideIcons.Camera, stringResource(R.string.tools_retake), enabled, share, onRetake)
        ScanBarButton(LucideIcons.Crop, stringResource(R.string.scan_enhance_borders), enabled, share, onBorders)
        ScanBarButton(LucideIcons.RotateCw, stringResource(R.string.scan_rotate), enabled, share, onRotate)
        ScanBarButton(LucideIcons.Trash2, stringResource(R.string.common_delete), enabled, share, onDelete)
        Box(share) {
            ScanBarButton(LucideIcons.EllipsisVertical, stringResource(R.string.scan_enhance_more), modifier = Modifier.fillMaxWidth()) { menu = true }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                for (item in ScanEnhanceMore.entries) {
                    DropdownMenuItem(
                        text = { Text(stringResource(scanEnhanceMoreLabel(item))) },
                        enabled = moreEnabled(item),
                        onClick = {
                            menu = false
                            onMore(item)
                        },
                    )
                }
            }
        }
    }
}

private fun scanEnhanceMoreLabel(item: ScanEnhanceMore): Int = when (item) {
    ScanEnhanceMore.ApplyAll -> R.string.scan_enhance_apply_all
    ScanEnhanceMore.ResetLook -> R.string.scan_reset_look
    ScanEnhanceMore.Compare -> R.string.scan_enhance_compare
    ScanEnhanceMore.AddPage -> R.string.tools_add_page
    ScanEnhanceMore.Rename -> R.string.scan_enhance_rename
}

/** The sliders button at the end of the looks strip, for a look that has settings. */
@Composable
internal fun ScanAdjustButton(onClick: () -> Unit) {
    val c = pdfColors
    val label = stringResource(R.string.scan_enhance_adjust)
    Column(
        Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }.padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(c.surfaceRaised), contentAlignment = Alignment.Center) {
            Icon(LucideIcons.SlidersHorizontal, null, tint = c.icon, modifier = Modifier.size(24.dp))
        }
        Text(label, color = c.text, fontSize = 12.sp, maxLines = 1)
    }
}

/** The look's settings in a sheet that leaves the page in view above it: no dark veil over the page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScanAdjustSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val c = pdfColors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        scrimColor = Color.Transparent,
        containerColor = c.surface,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(stringResource(R.string.scan_enhance_adjust), color = c.text, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            content()
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** ‹ and › over the page's sides, for the page before and after; none past either end. */
@Composable
internal fun ScanPageArrows(current: Int, pages: Int, modifier: Modifier = Modifier, onStep: (Int) -> Unit) {
    if (pages < 2) return
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        val before = stringResource(R.string.scan_enhance_page_before)
        val after = stringResource(R.string.scan_enhance_page_after)
        if (current > 0) ScanArrow(LucideIcons.ChevronLeft, before) { onStep(-1) } else Spacer(Modifier.size(44.dp))
        Spacer(Modifier.weight(1f))
        if (current < pages - 1) ScanArrow(LucideIcons.ChevronRight, after) { onStep(1) } else Spacer(Modifier.size(44.dp))
    }
}

@Composable
private fun ScanArrow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

/** A suggestion over the bottom of the page, with a way to dismiss it (#459). */
@Composable
internal fun ScanSuggestionOverlay(suggestion: ScanSuggestion, modifier: Modifier = Modifier, onTake: () -> Unit, onDismiss: () -> Unit) {
    val dismiss = stringResource(R.string.scan_enhance_dismiss)
    Row(modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.65f)).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.clickable(role = Role.Button, onClick = onTake).padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(stringResource(scanSuggestionLabel(suggestion)), color = Color.White, fontSize = 13.sp)
        }
        Box(Modifier.size(40.dp).clickable(role = Role.Button, onClick = onDismiss).semantics { contentDescription = dismiss }, contentAlignment = Alignment.Center) {
            Icon(LucideIcons.X, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

/** One `look` line's `from` and the `adjust_open` line (#459). */
internal fun logScanAdjustOpen(index: Int, look: ScanPageLook) {
    Diag.event(DiagArea.Scan, "adjust_open", "index" to index + 1, "look" to scanFilterId(look.filter))
}

/**
 * A sideways swipe over the page, watched without taking the touches from the page's own
 * gestures (hold to compare, double-tap and pinch to zoom): [onSwipe] gets the drag's width when
 * one finger went down and up again, moving more across than up or down, while [enabled].
 */
internal fun Modifier.scanSwipe(enabled: () -> Boolean, onSwipe: (Float) -> Unit): Modifier = this.then(
    Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var dx = 0f
            var dy = 0f
            var fingers = 1
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                fingers = maxOf(fingers, e.changes.count { it.pressed })
                val c = e.changes.firstOrNull { it.id == down.id } ?: break
                dx += c.position.x - c.previousPosition.x
                dy += c.position.y - c.previousPosition.y
                if (!c.pressed) break
            }
            if (fingers == 1 && enabled() && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f) onSwipe(dx)
        }
    },
)

internal fun scanSuggestionLabel(suggestion: ScanSuggestion): Int = when (suggestion) {
    ScanSuggestion.Flatten -> R.string.scan_suggest_flatten
    ScanSuggestion.Greyscale -> R.string.scan_suggest_greyscale
}

/**
 * One thing said about the page on Enhance (#493): what was turned or done automatically, each with
 * Undo, the document type's suggestion, and the hold-to-compare hint. They sit in one line between
 * the page and the looks strip, never over the page, so the page is clear while it is judged and
 * no two notes can overlap.
 */
internal sealed interface ScanEnhanceNote {
    /** "Turned upright · Undo" (#339). */
    data object Turned : ScanEnhanceNote
    /** What the automatic look did, with Undo (#466). */
    data class Auto(val chip: ScanAutoChip) : ScanEnhanceNote
    /** A suggestion to take or dismiss (#459). */
    data class Suggest(val suggestion: ScanSuggestion) : ScanEnhanceNote
    /** "Hold to see the original", or "Original" while it is shown (#301). */
    data class Compare(val comparing: Boolean) : ScanEnhanceNote
}

/** The notes for the page shown, in the order they are read: what was done, then what is offered, then the hint (#493). */
internal fun scanEnhanceNotes(turned: Boolean, chips: List<ScanAutoChip>, suggestion: ScanSuggestion?, compare: Boolean?): List<ScanEnhanceNote> = buildList {
    if (turned) add(ScanEnhanceNote.Turned)
    chips.forEach { add(ScanEnhanceNote.Auto(it)) }
    suggestion?.let { add(ScanEnhanceNote.Suggest(it)) }
    compare?.let { add(ScanEnhanceNote.Compare(it)) }
}

/** The height the notes line keeps on Enhance (#493), empty or not, so the page does not jump when a note comes or goes. */
internal const val SCAN_NOTES_HEIGHT_DP = 44

/**
 * The notes line under the page on Enhance (#493): one line that scrolls sideways when the notes are
 * wider than the screen; each note keeps its words on one line.
 */
@Composable
internal fun ScanEnhanceNotes(
    notes: List<ScanEnhanceNote>,
    modifier: Modifier = Modifier,
    onUndoTurn: () -> Unit = {},
    onUndoAuto: (ScanAutoChip) -> Unit = {},
    onTake: (ScanSuggestion) -> Unit = {},
    onDismiss: (ScanSuggestion) -> Unit = {},
) {
    val undoTurn = stringResource(R.string.scan_turn_undo_description)
    Row(
        modifier.fillMaxWidth().height(SCAN_NOTES_HEIGHT_DP.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (n in notes) when (n) {
            ScanEnhanceNote.Turned -> Text(
                stringResource(R.string.scan_turned_upright_undo), color = Color.White, fontSize = 13.sp, maxLines = 1, softWrap = false,
                modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.6f))
                    .clickable(role = Role.Button, onClickLabel = undoTurn) { onUndoTurn() }.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            is ScanEnhanceNote.Auto -> ScanAutoChipView(n.chip, { onUndoAuto(n.chip) })
            is ScanEnhanceNote.Suggest -> ScanSuggestionOverlay(n.suggestion, onTake = { onTake(n.suggestion) }, onDismiss = { onDismiss(n.suggestion) })
            is ScanEnhanceNote.Compare -> Text(
                stringResource(if (n.comparing) R.string.tools_filter_original else R.string.scan_compare_hint), color = Color.White, fontSize = 12.sp,
                maxLines = 1, softWrap = false,
                modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}
