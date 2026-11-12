package io.github.artcraftandroid.pdfcraft.ui.scan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.pdfColors

/**
 * Book mode's two chips beside the looks (#303): Book, a switch for the photos taken from now
 * on, and when it is on, the order the two pages of a spread go in (tapping flips it).
 */
@Composable
internal fun ScanBookChips(book: ScanBookController) {
    val c = pdfColors
    val on = book.on
    Text(
        stringResource(R.string.tools_scan_book), color = if (on) c.accent else c.text, fontSize = 14.sp,
        modifier = Modifier.clip(CircleShape).background(if (on) c.pressed else Color.Transparent).border(1.dp, if (on) c.accent else c.textSecondary, CircleShape)
            .toggleable(value = on, role = Role.Switch) { book.turn(it) }.padding(horizontal = 14.dp, vertical = 8.dp),
    )
    if (on) {
        val label = if (book.order == ScanBookOrder.RightToLeft) R.string.tools_scan_book_rtl else R.string.tools_scan_book_ltr
        Text(
            stringResource(label), color = c.text, fontSize = 14.sp,
            modifier = Modifier.clip(CircleShape).border(1.dp, c.textSecondary, CircleShape)
                .clickable(role = Role.Button) { book.flipOrder() }.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/** A note on a page that is half of a spread: which half (#303). */
@Composable
internal fun ScanBookLabel(half: ScanBookHalf, modifier: Modifier = Modifier) {
    Text(
        stringResource(if (half.left) R.string.tools_scan_book_left_page else R.string.tools_scan_book_right_page), color = Color.White, fontSize = 13.sp,
        modifier = modifier.padding(12.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** Book mode's switch in the camera's top bar (#303), white on the dark camera screen. */
@Composable
internal fun ScanBookCameraToggle(book: ScanBookController) {
    val on = book.on
    Text(
        stringResource(R.string.tools_scan_book), color = if (on) Color.Black else Color.White, fontSize = 14.sp,
        modifier = Modifier.clip(CircleShape).background(if (on) Color.White else Color.Transparent).border(1.dp, Color.White, CircleShape)
            .toggleable(value = on, role = Role.Switch) { book.turn(it, from = "camera") }.padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * The spine guide over the viewfinder in book mode (#303): a vertical line at the frame's centre
 * and the hint to line the book's spine up with it. The gutter search looks in the middle 30 % of
 * the spread, so a spine near the line is found.
 */
@Composable
internal fun BoxScope.ScanBookSpineGuide() {
    Canvas(Modifier.matchParentSize()) {
        val x = size.width / 2f
        drawLine(Color.Black.copy(alpha = 0.5f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 4.dp.toPx())
        drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12.dp.toPx(), 8.dp.toPx())))
    }
    Text(
        stringResource(R.string.scan_camera_book_hint), color = Color.White, fontSize = 13.sp,
        modifier = Modifier.align(Alignment.TopCenter).padding(12.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
