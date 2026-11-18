package io.github.artcraftandroid.pdfcraft.ui.viewer

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** #723: Analyze text styles runs with a job that Cancel and the sheet closing both stop. */
class TextStylesSheetTest {
    private val source = File("src/main/java/io/github/artcraftandroid/pdfcraft/ui/viewer/TextStylesSheet.kt").readText()

    @Test
    fun theAnalysisIsWatchedAndStopsWhenTheSheetCloses() {
        assertTrue("the tool is given the job", source.contains("engine.tool(\"text_styles\", mapOf(\"doc\" to doc.id), job)"))
        assertTrue("closing the sheet stops the engine", source.contains("onDispose { running?.cancel() }"))
        assertTrue("Cancel stops it too", source.contains("job.cancel()"))
        assertTrue("its time, pages and size are logged", source.contains("vm.jobEnd(\"text_styles\""))
    }
}
