package io.github.artcraftandroid.pdfcraft.ui.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
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
    io.github.artcraftandroid.pdfcraft.ui.home.SettingsChoice(
        stringResource(R.string.scan_camera_setting),
        ScanCameraChoice.entries.map { it to stringResource(if (it == ScanCameraChoice.InApp) R.string.scan_camera_setting_in_app else R.string.scan_camera_setting_phone) },
        model.choice, { model.updateChoice(it) }, stringResource(R.string.scan_camera_setting_body),
    )
}

/** The live page outline in the camera (#295), a testing option: off, the camera is as it was before it. */
@Composable
internal fun ScanLiveSetting() {
    val model: ScanCameraModel = viewModel()
    io.github.artcraftandroid.pdfcraft.ui.SheetRow(null, stringResource(R.string.scan_live_setting), { model.updateLiveOutline(!model.liveOutline) }) {
        io.github.artcraftandroid.pdfcraft.ui.PdfSwitch(model.liveOutline, { model.updateLiveOutline(it) })
    }
}

/** Auto-capture (#296), a testing option and the camera screen's Auto switch: off, only the shutter takes photos. */
@Composable
internal fun ScanAutoSetting() {
    val model: ScanCameraModel = viewModel()
    io.github.artcraftandroid.pdfcraft.ui.SheetRow(null, stringResource(R.string.scan_auto_setting), { model.updateAutoCapture(!model.autoCapture) }) {
        io.github.artcraftandroid.pdfcraft.ui.PdfSwitch(model.autoCapture, { model.updateAutoCapture(it) })
    }
}

/** Low-light merge (#297): Auto, Always or Off; in the ordinary settings and among the testing options. */
@Composable
internal fun ScanMergeSettingRow() {
    val model: ScanCameraModel = viewModel()
    io.github.artcraftandroid.pdfcraft.ui.home.SettingsChoice(
        stringResource(R.string.scan_merge_setting),
        ScanMergeSetting.entries.map {
            it to stringResource(
                when (it) {
                    ScanMergeSetting.Auto -> R.string.scan_merge_auto
                    ScanMergeSetting.Always -> R.string.scan_merge_always
                    ScanMergeSetting.Off -> R.string.scan_merge_off
                },
            )
        },
        model.merge, { model.updateMerge(it) }, stringResource(R.string.scan_merge_body),
    )
}
