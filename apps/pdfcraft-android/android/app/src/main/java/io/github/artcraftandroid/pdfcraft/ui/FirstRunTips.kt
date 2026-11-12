package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R

/** The first-run tips (#202): one card at a time, Skip in one tap, replayed from Help ▸ Tips. */
internal val FIRST_RUN_TIPS = listOf(
    R.string.tips_draw_title to R.string.tips_draw_body,
    R.string.tips_pen_title to R.string.tips_pen_body,
    R.string.tips_find_title to R.string.tips_find_body,
)

@Composable
fun FirstRunTipsDialog(onClose: () -> Unit) {
    val c = pdfColors
    var at by remember { mutableIntStateOf(0) }
    val last = at == FIRST_RUN_TIPS.lastIndex
    val (title, body) = FIRST_RUN_TIPS[at]
    PdfDialog(
        onDismiss = onClose,
        title = stringResource(title),
        confirm = stringResource(if (last) R.string.common_done else R.string.tips_next),
        onConfirm = { if (last) onClose() else at++ },
        dismiss = if (last) null else stringResource(R.string.tips_skip),
    ) {
        Text(stringResource(body), color = c.text, fontSize = 14.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, androidx.compose.ui.Alignment.CenterHorizontally)) {
            FIRST_RUN_TIPS.indices.forEach { i ->
                Box(Modifier.size(6.dp).background(if (i == at) c.accent else c.divider, CircleShape))
            }
        }
    }
}
