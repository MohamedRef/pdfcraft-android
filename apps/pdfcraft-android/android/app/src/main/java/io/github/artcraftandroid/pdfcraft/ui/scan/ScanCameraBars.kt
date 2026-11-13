package io.github.artcraftandroid.pdfcraft.ui.scan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.BarIcon
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons

/*
 * The camera's bars with the testing option "Camera modes and icon bar" on (#457): icons in the
 * top bar, each with an on/off shape and a spoken name; the mode row; Import, the shutter and the
 * last page always in the same places; zoom; the grid; and the page before the camera is allowed.
 */

/** The top bar: Close, then the light, auto-capture, the grid and ⋮ (the phone's camera app). Icons only, so it fits any language. */
@Composable
internal fun ScanCameraTopBar(
    model: ScanCameraModel,
    actions: ScanCameraActions,
    live: Boolean,
    torchAvailable: Boolean,
    torch: Boolean,
    onTorch: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        BarIcon(LucideIcons.X, stringResource(R.string.common_cancel), actions.onClose, tint = Color.White)
        Spacer(Modifier.weight(1f))
        if (torchAvailable) {
            ScanCameraToggle(if (torch) ScanCameraIcons.LightOn else ScanCameraIcons.LightOff, stringResource(R.string.scan_camera_light), torch, onTorch)
        }
        // auto-capture needs the live outline (#296)
        if (live) ScanCameraToggle(LucideIcons.Scan, stringResource(R.string.scan_auto_setting), model.autoCapture) { model.updateAutoCapture(it) }
        ScanCameraToggle(LucideIcons.Grid3x3, stringResource(R.string.scan_camera_grid), model.grid) { model.updateGrid(it) }
        var menu by remember { mutableStateOf(false) }
        Box {
            BarIcon(LucideIcons.EllipsisVertical, stringResource(R.string.scan_camera_more), { menu = true }, tint = Color.White)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.scan_camera_use_phone_app)) },
                    onClick = {
                        menu = false
                        actions.onPhoneApp()
                    },
                )
            }
        }
    }
}

/**
 * A switch drawn as an icon: an outlined circle when off, a filled white circle when on, so the
 * state is told by shape as well as colour. TalkBack reads its name and "on" or "off".
 */
@Composable
private fun ScanCameraToggle(icon: ImageVector, label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        Modifier.size(48.dp).toggleable(value = on, role = Role.Switch, onValueChange = onChange).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(38.dp).clip(CircleShape)
                .background(if (on) Color.White else Color.Transparent)
                .border(1.5.dp, if (on) Color.White else Color.White.copy(alpha = 0.55f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = if (on) Color.Black else Color.White, modifier = Modifier.size(22.dp))
        }
    }
}

/** The mode row above the shutter: One page, Many pages, Book. The chosen one is a filled pill in bold. */
@Composable
internal fun ScanCameraModeRow(mode: ScanCameraMode, bookAvailable: Boolean, onChoose: (ScanCameraMode) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (m in ScanCameraMode.entries) {
            if (m == ScanCameraMode.Book && !bookAvailable) continue
            val chosen = m == mode
            Text(
                stringResource(scanCameraModeName(m)),
                color = if (chosen) Color.Black else Color.White,
                fontSize = 14.sp,
                fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 140.dp).clip(CircleShape)
                    .background(if (chosen) Color.White else Color.Transparent)
                    .selectable(selected = chosen, role = Role.RadioButton) { onChoose(m) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

internal fun scanCameraModeName(mode: ScanCameraMode): Int = when (mode) {
    ScanCameraMode.Single -> R.string.scan_mode_single
    ScanCameraMode.Batch -> R.string.scan_mode_batch
    ScanCameraMode.Book -> R.string.scan_mode_book
}

/** Import, at the bottom left before and after the first page: an icon with its word. */
@Composable
internal fun ScanImportButton(onClick: () -> Unit) {
    Column(
        Modifier.sizeIn(minWidth = 56.dp, minHeight = 56.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(LucideIcons.Images, null, tint = Color.White, modifier = Modifier.size(26.dp))
        Text(stringResource(R.string.scan_camera_import_short), color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The last page taken, bottom right, with the page count on a badge; a tap opens the scan. In
 * Many pages and Book, "Done" is written under it.
 */
@Composable
internal fun ScanPagesButton(last: ImageBitmap, count: Int, showDone: Boolean, onClick: () -> Unit) {
    val label = stringResource(R.string.scan_camera_open_pages, count)
    Column(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onClick).clearAndSetSemantics {
            contentDescription = label
            role = Role.Button
        }.padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Image(
                last, null, Modifier.padding(top = 6.dp, end = 6.dp).size(44.dp, 58.dp).clip(RoundedCornerShape(4.dp)).border(1.dp, Color.White, RoundedCornerShape(4.dp)),
                contentScale = ContentScale.Crop,
            )
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = 2.dp).sizeIn(minWidth = 22.dp, minHeight = 22.dp).clip(CircleShape).background(Color.White).padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(scanCountBadge(count), color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (showDone) Text(stringResource(R.string.scan_camera_done_short), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** The 1× / 2× button over the bottom of the viewfinder. */
@Composable
internal fun ScanZoomButton(zoom: Float, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val text = ScanZoom.label(zoom)
    val label = stringResource(R.string.scan_camera_zoom, text)
    Box(
        modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).border(1.dp, Color.White.copy(alpha = 0.7f), CircleShape)
            .clickable(role = Role.Button, onClick = onClick).clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        // "2×" reads the same way in Arabic
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr))
    }
}

/** Lines of thirds over the viewfinder. */
@Composable
internal fun ScanCameraGrid(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val c = Color.White.copy(alpha = 0.45f)
        val w = 1.dp.toPx()
        for (i in 1..2) {
            val x = size.width * i / 3f
            val y = size.height * i / 3f
            drawLine(c, Offset(x, 0f), Offset(x, size.height), w)
            drawLine(c, Offset(0f, y), Offset(size.width, y), w)
        }
    }
}

/**
 * The page before the camera is allowed, when it was refused, or on a phone without a camera:
 * one filled button for the way forward (Continue, Open settings, or Import photos), Import
 * photos as the second choice, Cancel as plain text.
 */
@Composable
internal fun ScanCameraPermissionView(route: ScanCaptureRoute, actions: ScanCameraActions) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        val message = when (route) {
            ScanCaptureRoute.AskPermission -> R.string.scan_camera_rationale
            ScanCaptureRoute.ImportOnly -> R.string.scan_camera_no_camera
            else -> R.string.scan_camera_denied
        }
        Icon(LucideIcons.Camera, null, tint = Color.White, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        Text(stringResource(message), color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        val filled = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
        val wide = Modifier.fillMaxWidth().widthIn(max = 320.dp).sizeIn(minHeight = 48.dp)
        when (route) {
            ScanCaptureRoute.AskPermission -> Button(actions.onAskPermission, wide, colors = filled) { Text(stringResource(R.string.scan_camera_continue), fontSize = 16.sp) }
            ScanCaptureRoute.ImportOnly -> Button(actions.onImport, wide, colors = filled) { Text(stringResource(R.string.scan_camera_import), fontSize = 16.sp) }
            else -> Button(actions.onOpenSettings, wide, colors = filled) { Text(stringResource(R.string.scan_camera_open_settings), fontSize = 16.sp) }
        }
        if (route != ScanCaptureRoute.ImportOnly) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(actions.onImport, wide, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White), border = BorderStroke(1.dp, Color.White)) {
                Text(stringResource(R.string.scan_camera_import), fontSize = 16.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = actions.onClose) { Text(stringResource(R.string.common_cancel), color = Color.White, fontSize = 16.sp) }
    }
}
