package io.github.artcraftandroid.pdfcraft.ui.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.pdfColors

/**
 * Scan with: PdfCraft's camera or the phone's camera app (#294). In the ordinary settings, so the
 * phone's camera app is always within reach if the new screen misbehaves, and among the scanner's
 * testing options, where it is the option that puts back what the new screen replaced.
 */
@Composable
internal fun ScanCameraSetting() {
    val model: ScanCameraModel = viewModel()
    val c = pdfColors
    Text(stringResource(R.string.scan_camera_setting), color = c.text, fontSize = 16.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (choice in ScanCameraChoice.entries) {
            val on = choice == model.choice
            val name = stringResource(if (choice == ScanCameraChoice.InApp) R.string.scan_camera_setting_in_app else R.string.scan_camera_setting_phone)
            Text(
                name, color = if (on) Color.White else c.text, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) c.accent else c.surface)
                    .clickable(role = Role.RadioButton) { model.updateChoice(choice) }.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
    Text(stringResource(R.string.scan_camera_setting_body), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
}

/** The live page outline in the camera (#295), a testing option: off, the camera is as it was before it. */
@Composable
internal fun ScanLiveSetting() {
    val model: ScanCameraModel = viewModel()
    io.github.artcraftandroid.pdfcraft.ui.SheetRow(null, stringResource(R.string.scan_live_setting), { model.updateLiveOutline(!model.liveOutline) }) {
        io.github.artcraftandroid.pdfcraft.ui.PdfSwitch(model.liveOutline, { model.updateLiveOutline(it) })
    }
}
