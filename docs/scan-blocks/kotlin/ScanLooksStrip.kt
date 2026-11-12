package io.github.artcraftandroid.pdfcraft.ui.scan

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.pdfColors

/** Width of one look's tile in the strip, in dp (issue #301: 72 by 96). */
private val TILE_WIDTH: Dp = 72.dp

/** Height of one look's tile in the strip, in dp. */
private val TILE_HEIGHT: Dp = 96.dp

/** The name of [kind] as the strip and the controls show it: a string resource id. */
internal fun lookLabelRes(kind: ScanLookKind): Int = when (kind) {
    ScanLookKind.Original -> R.string.tools_filter_original
    ScanLookKind.Auto -> R.string.tools_filter_clean_scan
    ScanLookKind.Colour -> R.string.scan_look_color
    ScanLookKind.Greyscale -> R.string.tools_filter_grayscale
    ScanLookKind.BlackWhite -> R.string.scan_look_black_white
    ScanLookKind.Whiteboard -> R.string.tools_filter_whiteboard
}

/**
 * The row of looks under the preview: one live tile per look, each the current page in that look
 * (issue #301). A tile with no image yet is an empty card. The selected tile has an accent border
 * and is announced as selected.
 */
@Composable
internal fun ScanLooksStrip(
    tiles: Map<ScanLookKind, ImageBitmap?>,
    selected: ScanLookKind,
    onSelect: (ScanLookKind) -> Unit,
    modifier: Modifier,
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(ScanLookKind.entries) { kind ->
            ScanLookTile(
                kind = kind,
                image = tiles[kind],
                isSelected = kind == selected,
                onSelect = { onSelect(kind) },
            )
        }
    }
}

/** One look's tile: its preview over its name. */
@Composable
private fun ScanLookTile(kind: ScanLookKind, image: ImageBitmap?, isSelected: Boolean, onSelect: () -> Unit) {
    val c = pdfColors
    val name = stringResource(lookLabelRes(kind))
    val shape = RoundedCornerShape(4.dp)
    Column(
        Modifier
            .width(TILE_WIDTH)
            .semantics(mergeDescendants = true) {
                contentDescription = name
                selected = isSelected
            }
            .clickable(role = Role.RadioButton, onClick = onSelect),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(TILE_WIDTH, TILE_HEIGHT)
                .clip(shape)
                .background(c.surfaceRaised)
                .border(2.dp, if (isSelected) c.accent else Color.Transparent, shape),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            name,
            color = if (isSelected) c.accent else c.text,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(TILE_WIDTH),
        )
    }
}
