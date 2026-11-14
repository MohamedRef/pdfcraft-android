package io.github.artcraftandroid.pdfcraft.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No two tools in the viewer's bars share an icon (#526): the tools you choose in the rows of
 * Highlight, Comment, Text, Draw, Measure and Fill & Sign, and the markups of the selection menu.
 * Squiggly was Lucide's spline, which is also Draw's connected lines. One tool shown in two places
 * (Highlight in its row and in the selection menu) keeps its icon: the same English name is the
 * same tool. Buttons that do something rather than choose a tool (Done, Undo point) are left out.
 */
class ViewerToolIconsTest {
    private val viewer = File("src/main/java/io/github/artcraftandroid/pdfcraft/ui/viewer")
    private val icon = """((?:io\.github\.artcraftandroid\.pdfcraft\.ui\.)?(?:LucideIcons|PdfIcons)\.\w+)"""

    /** The body of the top-level function [name] in [file]: from its `fun` to the next closing brace at column 0. */
    private fun body(file: String, name: String): String {
        val text = File(viewer, file).readText()
        val start = Regex("""\bfun (?:BoxScope\.)?$name\(""").find(text)?.range?.first ?: error("no $name in $file")
        val end = text.indexOf("\n}\n", start).takeIf { it > 0 } ?: text.length
        return text.substring(start, end)
    }

    private fun english(): Map<String, String> {
        val text = File("src/main/res/values/strings.xml").readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        return Regex("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    /** Each tool as (icon, string key). */
    private fun tools(): List<Pair<String, String>> {
        val found = mutableListOf<Pair<String, String>>()
        val subTool = Regex("""SubTool\(\s*$icon,\s*stringResource\(R\.string\.(\w+)\),\s*([^,]+),""")
        for ((file, fn) in listOf(
            "Tools.kt" to "MarkupSubTools", "Tools.kt" to "CommentSubTools", "Tools.kt" to "TextSubTools",
            "Measure.kt" to "MeasureToolbar", "FillSign.kt" to "FillSignToolbar",
        )) {
            subTool.findAll(body(file, fn)).filter { it.groupValues[3].trim() != "false" }.forEach { found += it.groupValues[1] to it.groupValues[2] }
        }
        val draw = File(viewer, "DrawToolbar.kt").readText()
        Regex("""DrawShape\(InkShapeMode\.\w+, $icon, R\.string\.(\w+)""").findAll(draw)
            .forEach { found += it.groupValues[1] to it.groupValues[2] }
        // a Draw button's label, then the icon drawn in its content, before the next button; a
        // button that opens a sheet of tools (Shapes, Guides, Pen settings) is not a tool itself
        for (chunk in draw.split("DrawToolbarButton(").drop(1)) {
            if ("ctl.picker = if (" in chunk.substringBefore(") {")) continue
            val label = Regex("""label = stringResource\(R\.string\.(\w+)\)""").find(chunk) ?: continue
            val drawn = Regex("""\bIcon\($icon,""").find(chunk) ?: continue
            found += drawn.groupValues[1] to label.groupValues[1]
        }
        body("ViewerOverlays.kt", "SelectionMenu").lines().filter { "markup(TextMarkup" in it }.forEach { line ->
            Regex("""MenuIcon\($icon, stringResource\(R\.string\.(\w+)\)""").find(line)?.let { found += it.groupValues[1] to it.groupValues[2] }
        }
        return found.map { (i, key) -> i.substringAfterLast("ui.") to key }
    }

    @Test
    fun noTwoToolsShareAnIcon() {
        val names = english()
        val tools = tools()
        // the reading found the rows (a changed call would otherwise pass by finding nothing)
        assertTrue("found ${tools.size}", tools.size >= 25)
        assertTrue(tools.any { it.second == "toolbar_squiggly" })
        assertTrue(tools.any { it.second == "measure_perimeter" })
        assertTrue(tools.any { it.second == "ink_ruler" })
        val shared = tools.groupBy({ it.first }, { names[it.second] ?: error("no English text for ${it.second}") })
            .mapValues { it.value.toSortedSet() }
            .filterValues { it.size > 1 }
        assertEquals(emptyMap<String, Set<String>>(), shared)
    }

    @Test
    fun squigglyHasItsOwnIcon() {
        val squiggly = tools().filter { it.second == "toolbar_squiggly" }.map { it.first }.toSet()
        assertEquals(setOf("PdfIcons.SquigglyUnderline"), squiggly)
    }
}
