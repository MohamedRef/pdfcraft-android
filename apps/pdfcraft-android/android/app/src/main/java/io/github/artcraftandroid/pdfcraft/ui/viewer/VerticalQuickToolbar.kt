package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.pdfColors

/**
 * Docked shortcuts for phones/tablets with stylus annotation workflows.
 *
 * Reuses the existing quick bar's saved order instead of maintaining a second
 * mapping of tools or a new native annotation handler. This is a touch overlay:
 * it never modifies PDF page geometry or the provider-backed source file.
 */
@Composable
fun VerticalQuickToolbar(
    tools: List<QuickTool>,
    onTool: (QuickTool) -> Unit,
    onMoreTools: () -> Unit,
    moreToolsOpen: Boolean,
    onHandleMove: (Float, Float) -> Unit,
    onHandleRelease: () -> Unit,
    onHandleCancel: () -> Unit,
) {
    val colors = pdfColors
    val haptics = LocalHapticFeedback.current
    val maxRailHeight = (LocalConfiguration.current.screenHeightDp - 150).coerceAtLeast(200).dp
    Column(
        Modifier
            .heightIn(max = maxRailHeight)
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surface)
            .border(1.dp, colors.divider, RoundedCornerShape(10.dp))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        val dragTitle = stringResource(R.string.toolbar_drag_handle)
        Box(
            Modifier.size(width = 48.dp, height = 34.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                        onDrag = { change, delta ->
                            change.consume()
                            onHandleMove(delta.x, delta.y)
                        },
                        onDragEnd = {
                            onHandleRelease()
                            // One tick at snap, never vibrate continuously while dragging.
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        },
                        onDragCancel = { onHandleCancel() },
                    )
                }
                .semantics { contentDescription = dragTitle },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(width = 26.dp, height = 4.dp).background(colors.divider, RoundedCornerShape(3.dp)))
        }
        for (tool in tools.distinct().take(8)) {
            RailTool(
                icon = tool.icon,
                label = stringResource(tool.title),
                selected = false,
                onClick = { onTool(tool) },
            )
        }
        RailTool(
            icon = LucideIcons.LayoutGrid,
            label = stringResource(R.string.toolbar_vertical_more),
            selected = moreToolsOpen,
            onClick = onMoreTools,
        )
    }
}

@Composable
private fun RailTool(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = pdfColors
    Box(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) colors.pressed else colors.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = colors.icon, modifier = Modifier.size(25.dp))
    }
}
