package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import io.github.artcraftandroid.pdfcraft.R
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The one message at the bottom of the screen (#618): [AppViewModel.messageBoard] decides what it
 * shows. A new message slides in; an update in place (a run of Undo, the same text again, errors in
 * a row) does not. A sideways swipe or the ✕ closes it, and nothing comes up behind it.
 */
@Composable
fun MessageHost(vm: AppViewModel, modifier: Modifier = Modifier) {
    val board = vm.messageBoard
    val a11y = LocalAccessibilityManager.current
    // Android's accessibility timeout setting can ask for longer messages, as Material's snackbar does
    SideEffect {
        board.timeout = { ms, button -> a11y?.calculateRecommendedTimeoutMillis(ms, containsIcons = true, containsText = true, containsControls = button) ?: ms }
    }
    val shown = vm.message
    LaunchedEffect(shown?.id, shown?.until) {
        val s = shown ?: return@LaunchedEffect
        delay((s.until - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L))
        board.expire()
    }
    AnimatedContent(
        targetState = shown,
        contentKey = { it?.id },
        transitionSpec = { (fadeIn() + slideInVertically { it / 2 }) togetherWith fadeOut() },
        modifier = modifier,
        label = "message",
    ) { s ->
        if (s != null) MessageBar(s, onClose = board::dismiss, onPress = { board.press() })
    }
}

@Composable
private fun MessageBar(s: MessageBoard.Shown, onClose: () -> Unit, onPress: () -> Unit) {
    val scope = rememberCoroutineScope()
    val dx = remember(s.id) { Animatable(0f) }
    val width = remember(s.id) { intArrayOf(1) }
    val closeLabel = stringResource(R.string.viewer_close)
    Box(
        Modifier.fillMaxWidth()
            .onSizeChanged { width[0] = it.width.coerceAtLeast(1) }
            .offset { IntOffset(dx.value.roundToInt(), 0) }
            .graphicsLayer { alpha = 1f - (abs(dx.value) / width[0]).coerceIn(0f, 1f) }
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { d -> scope.launch { dx.snapTo(dx.value + d) } },
                onDragStopped = { velocity ->
                    if (abs(dx.value) > width[0] * 0.3f || abs(velocity) > 1_500f) {
                        val out = if (dx.value + velocity / 10f < 0f) -width[0].toFloat() else width[0].toFloat()
                        dx.animateTo(out)
                        onClose()
                    } else {
                        dx.animateTo(0f)
                    }
                },
            )
            .semantics {
                liveRegion = LiveRegionMode.Polite
                dismiss(closeLabel) { onClose(); true }
            },
    ) {
        Snackbar(
            action = s.actionLabel?.let { label -> { TextButton(onClick = onPress) { Text(label) } } },
            dismissAction = {
                IconButton(onClick = onClose) { Icon(LucideIcons.X, contentDescription = closeLabel) }
            },
        ) {
            // errors in a row are one message with how many happened
            val count = if (s.kind == MessageKind.Error && s.count > 1) "  ×" + java.text.NumberFormat.getInstance().format(s.count) else ""
            Text(s.text + count)
        }
    }
}
