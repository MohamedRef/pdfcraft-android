package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription

/**
 * The fraction (0..1, from the start of the playback) for a tap at [x] on a bar [width] pixels wide.
 * Pointer positions are always measured from the left, but a bar laid out from the start runs right
 * to left in an RTL layout (Arabic), so there the fraction is mirrored.
 */
fun seekFraction(x: Float, width: Int, rtl: Boolean): Float {
    if (width <= 0) return 0f
    val f = (x / width).coerceIn(0f, 1f)
    return if (rtl) 1f - f else f
}

/**
 * Lets TalkBack, Switch Access and other accessibility services read and change a custom slider
 * or seek bar: its value in [range], a spoken [state], and a SetProgress action that calls [onChange]
 * with the requested value kept inside the range. [steps] is the number of values between the ends.
 */
fun Modifier.rangeSemantics(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    state: String,
    onChange: (Float) -> Unit,
): Modifier = semantics {
    progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(range), range, steps)
    stateDescription = state
    setProgress { target ->
        onChange(target.coerceIn(range))
        true
    }
}
