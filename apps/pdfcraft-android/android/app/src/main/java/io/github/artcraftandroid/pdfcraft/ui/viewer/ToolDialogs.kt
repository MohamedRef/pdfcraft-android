package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel

/** The editing tools' dialogs that can open over any screen (Recognize text's options, …). */
@Composable
fun ToolDialogs(vm: AppViewModel) {
    vm.ocr.optionsFor?.let { doc ->
        val context = LocalContext.current
        OcrOptionsDialog(
            initial = OcrOptions.load(context), languages = vm.ocr.languages, pageCount = doc.pageCount,
            onDismiss = { vm.ocr.optionsFor = null }, onRun = { vm.runOcr(doc, it) },
        )
    }
}
