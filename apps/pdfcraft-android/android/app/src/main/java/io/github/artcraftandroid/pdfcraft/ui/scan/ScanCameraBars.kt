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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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

/**
 * The bottom bar with Review pages, Undo and Done each a button of its own (#780): Import and
 * Review pages before the shutter, Undo and Done after it (mirrored in a right-to-left language).
 * The four places are equal shares of the width beside the shutter and are kept when a control is
 * not shown, so the shutter stays in the middle whatever the mode and before and after a photo.
 * A control that cannot act now stays on the bar, drawn fainter and in another shape; pressing it
 * says why ([onControl] is told either way), and TalkBack reads the reason with it.
 */
@Composable
internal fun ScanCameraActionBar(
    controls: ScanCameraControls,
    last: ImageBitmap?,
    finishing: Boolean,
    onImport: () -> Unit,
    onControl: (ScanCameraControl) -> Unit,
    shutter: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { ScanImportButton(onImport) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (ScanCameraControl.Review in controls.shown) ScanReviewButton(last, controls.pages, controls.pending, controls.review) { onControl(ScanCameraControl.Review) }
            }
        }
        shutter()
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (ScanCameraControl.Undo in controls.shown) {
                    ScanCameraWordButton(
                        LucideIcons.Undo2, stringResource(R.string.common_undo), stringResource(R.string.scan_camera_undo_photo),
                        controls.undo, filled = false, working = false,
                    ) { onControl(ScanCameraControl.Undo) }
                }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (ScanCameraControl.Done in controls.shown) {
                    ScanCameraWordButton(
                        LucideIcons.Check, stringResource(R.string.scan_camera_done_short), stringResource(R.string.scan_camera_done_short),
                        controls.done, filled = true, working = finishing,
                    ) { onControl(ScanCameraControl.Done) }
                }
            }
        }
    }
}

/** What the screen says when a control that cannot act is pressed; null when it can. */
internal fun scanCameraBlockText(block: ScanCameraBlock): Int? = when (block) {
    ScanCameraBlock.None -> null
    ScanCameraBlock.NoPages -> R.string.scan_camera_blocked_no_pages
    ScanCameraBlock.Busy -> R.string.scan_camera_blocked_busy
    ScanCameraBlock.NothingToUndo -> R.string.scan_camera_blocked_undo
}

/**
 * Review pages: the last page taken with the page count on a badge, and its word under it. Before
 * the first page an empty page outline holds the place. A ring turns on it while a photo is on its
 * way into the scan; the count is of the pages that are in it.
 */
@Composable
private fun ScanReviewButton(last: ImageBitmap?, count: Int, pending: Int, block: ScanCameraBlock, onClick: () -> Unit) {
    val label = pluralStringResource(R.plurals.scan_camera_review_pages, count, count)
    val reason = scanCameraBlockText(block)?.let { stringResource(it) }
    val faint = if (block == ScanCameraBlock.None || pending > 0) 1f else 0.5f
    Column(
        Modifier.sizeIn(minWidth = 56.dp, minHeight = 56.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onClick).clearAndSetSemantics {
            contentDescription = label
            role = Role.Button
            if (reason != null) stateDescription = reason
        }.padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            val page = Modifier.padding(top = 6.dp, start = 6.dp, end = 6.dp).size(36.dp, 46.dp).clip(RoundedCornerShape(4.dp))
            if (last != null) {
                Image(last, null, page.border(1.dp, Color.White, RoundedCornerShape(4.dp)), contentScale = ContentScale.Crop)
            } else {
                Box(page.border(1.5.dp, Color.White.copy(alpha = faint * 0.7f), RoundedCornerShape(4.dp)))
            }
            if (pending > 0) {
                // on a dark disc, so the ring shows on a white page too
                Box(Modifier.padding(top = 6.dp).size(28.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.5.dp)
                }
            }
            if (count > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd).sizeIn(minWidth = 24.dp, minHeight = 24.dp).clip(CircleShape).background(Color.White).padding(horizontal = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(scanCountBadge(count), color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
        Text(stringResource(R.string.scan_camera_review_short), color = Color.White.copy(alpha = faint), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Undo or Done: an icon in a circle with its word under it, at least 56 dp each way. [filled] is
 * the bar's main action (Done), a white disc; the other an outlined circle. One that cannot act
 * loses the disc or the outline and is fainter, so the difference is not one of colour alone.
 * [working] shows a ring in place of the icon while Done waits for a photo.
 */
@Composable
private fun ScanCameraWordButton(icon: ImageVector, word: String, label: String, block: ScanCameraBlock, filled: Boolean, working: Boolean, onClick: () -> Unit) {
    val can = block == ScanCameraBlock.None
    val reason = scanCameraBlockText(block)?.let { stringResource(it) }
    val faint = if (can) 1f else 0.5f
    Column(
        Modifier.sizeIn(minWidth = 56.dp, minHeight = 56.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onClick).clearAndSetSemantics {
            contentDescription = label
            role = Role.Button
            if (reason != null) stateDescription = reason
        }.padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val disc = filled && can
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(if (disc) Color.White else Color.Transparent)
                .border(1.5.dp, if (can) Color.White else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (working) {
                CircularProgressIndicator(Modifier.size(22.dp), color = if (disc) Color.Black else Color.White, strokeWidth = 2.5.dp)
            } else {
                Icon(icon, null, tint = if (disc) Color.Black else Color.White.copy(alpha = faint), modifier = Modifier.size(22.dp))
            }
        }
        Text(word, color = Color.White.copy(alpha = faint), fontSize = 12.sp, fontWeight = if (filled) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The line under the viewfinder after a photo or an Undo (#780): a tick and what happened, with the pages counted. */
@Composable
internal fun ScanCameraCueLine(cue: ScanCameraCue, pages: Int) {
    val text = when (cue) {
        ScanCameraCue.Taken -> stringResource(R.string.scan_camera_photo_taken)
        ScanCameraCue.Count -> pluralStringResource(R.plurals.scan_camera_pages_now, pages, pages)
        ScanCameraCue.Empty -> stringResource(R.string.scan_camera_pages_none)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (cue != ScanCameraCue.Empty) {
            Icon(LucideIcons.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(6.dp))
        }
        Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
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
