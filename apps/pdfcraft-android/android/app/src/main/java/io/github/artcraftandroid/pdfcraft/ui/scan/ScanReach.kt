package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel

/*
 * Every tool in reach (#516): where each scanner tool is, counted in taps from the document
 * screen. The bars and menus are built from these lists, so the desktop test that counts the taps
 * reads the same places the screens show. Behind the testing option "Tools in reach"; off, the
 * bars and menus are as before.
 */

/** What Enhance's bottom bar holds. */
internal enum class ScanEnhanceBarItem(val id: String) {
    Retake("retake"),
    Borders("borders"),
    Markup("markup"),
    Rotate("rotate"),
    Delete("delete"),
    More("more"),
}

/**
 * Enhance's bottom bar, in order: with tools in reach and Markup on, Borders, Markup, Rotate,
 * Retake, More (Delete goes to the page's top bar); else Retake, Borders, Rotate, Delete, More.
 */
internal fun scanEnhanceBarItems(inReach: Boolean, markup: Boolean): List<ScanEnhanceBarItem> =
    if (inReach && markup) {
        listOf(ScanEnhanceBarItem.Borders, ScanEnhanceBarItem.Markup, ScanEnhanceBarItem.Rotate, ScanEnhanceBarItem.Retake, ScanEnhanceBarItem.More)
    } else {
        listOf(ScanEnhanceBarItem.Retake, ScanEnhanceBarItem.Borders, ScanEnhanceBarItem.Rotate, ScanEnhanceBarItem.Delete, ScanEnhanceBarItem.More)
    }

/** Whether Delete is an icon on Enhance's top bar: when the bottom bar has no room for it. */
internal fun scanDeleteOnTopBar(inReach: Boolean, markup: Boolean): Boolean = ScanEnhanceBarItem.Delete !in scanEnhanceBarItems(inReach, markup)

/** What the document screen's ⋮ holds. */
internal enum class ScanDocMenuItem(val id: String) {
    Select("select"),
    Rename("rename"),
    Gallery("gallery"),
    /** The scan's words, read and handed out as text, before Save PDF. */
    ExtractText("extract_text"),
    /** The scan printed as it would be saved, before Save PDF. */
    Print("print"),
}

/** The document screen's ⋮, in order. Sign, Lock and To Word stay in the viewer (the owner's choice on #516). */
internal fun scanDocMenuItems(inReach: Boolean): List<ScanDocMenuItem> =
    if (inReach) ScanDocMenuItem.entries.toList() else listOf(ScanDocMenuItem.Select, ScanDocMenuItem.Rename, ScanDocMenuItem.Gallery)

/** Whether the document screen's top bar has Select, beside ⋮. */
internal fun scanDocSelectShown(inReach: Boolean): Boolean = inReach

/** What a scan made without saving it is for: Save PDF, Share, Print or Extract text (#516). */
internal enum class ScanOut(val id: String) {
    Save("save"),
    Share("share"),
    Print("print"),
    Text("text"),
}

/** The scanner's tools as the tap count knows them. [changesPage]: a tool that changes a page. */
internal enum class ScanReachTool(val id: String, val changesPage: Boolean) {
    Look("look", true),
    Borders("borders", true),
    Rotate("rotate", true),
    Retake("retake", true),
    Delete("delete", true),
    Markup("markup", true),
    /** A stroke with one of Markup's tools: Erase, Pen, Highlighter, Text. */
    MarkupTool("markup_tool", true),
    /** A control inside Adjust: strength, paper, document type, Flatten page, Remove fingers. */
    AdjustControl("adjust", true),
    ApplyAll("apply_all", true),
    ResetLook("reset_look", true),
    Compare("compare", false),
    Rename("rename", false),
    SelectPages("select", false),
    Gallery("gallery", false),
    ExtractText("extract_text", false),
    Print("print", false),
    /** A kept scan opened again, counted from Home. */
    RecentScan("recent", false),
}

/** Where a tool is: the taps it takes ([taps], null when it is not there before Save PDF) and the way ([from]). */
internal data class ScanReach(val taps: Int?, val from: String)

/**
 * Where [tool] is with the testing option [inReach] and Markup [markup], in taps from the document
 * screen (a recent scan from Home): opening a page is one tap, the tool on Enhance's bar another.
 */
internal fun scanReach(tool: ScanReachTool, inReach: Boolean, markup: Boolean = true): ScanReach {
    val bar = scanEnhanceBarItems(inReach, markup)
    fun onPage(item: ScanEnhanceBarItem, more: Boolean): ScanReach = when {
        item in bar -> ScanReach(2, "bar")
        item == ScanEnhanceBarItem.Delete && scanDeleteOnTopBar(inReach, markup) -> ScanReach(2, "top")
        more -> ScanReach(3, "more")
        else -> ScanReach(null, "none")
    }
    val menu = scanDocMenuItems(inReach)
    fun inMenu(item: ScanDocMenuItem): ScanReach = if (item in menu) ScanReach(2, "menu") else ScanReach(null, "viewer")
    return when (tool) {
        // the looks strip is on the page itself
        ScanReachTool.Look -> ScanReach(2, "strip")
        ScanReachTool.Borders -> onPage(ScanEnhanceBarItem.Borders, false)
        ScanReachTool.Rotate -> onPage(ScanEnhanceBarItem.Rotate, false)
        ScanReachTool.Retake -> onPage(ScanEnhanceBarItem.Retake, false)
        ScanReachTool.Delete -> onPage(ScanEnhanceBarItem.Delete, false)
        ScanReachTool.Markup -> if (!markup) ScanReach(null, "none") else onPage(ScanEnhanceBarItem.Markup, true)
        ScanReachTool.MarkupTool -> scanReach(ScanReachTool.Markup, inReach, markup).let { r -> r.copy(taps = r.taps?.plus(1)) }
        // page, Adjust, the control
        ScanReachTool.AdjustControl -> ScanReach(3, "adjust")
        ScanReachTool.ApplyAll, ScanReachTool.ResetLook, ScanReachTool.Compare, ScanReachTool.Rename -> ScanReach(3, "more")
        // the visible Select, or ⋮ then Select pages
        ScanReachTool.SelectPages -> if (scanDocSelectShown(inReach)) ScanReach(1, "bar") else ScanReach(2, "menu")
        ScanReachTool.Gallery -> inMenu(ScanDocMenuItem.Gallery)
        ScanReachTool.ExtractText -> inMenu(ScanDocMenuItem.ExtractText)
        ScanReachTool.Print -> inMenu(ScanDocMenuItem.Print)
        // Home's row; else the Tools tab, Recent scans, the scan
        ScanReachTool.RecentScan -> if (inReach) ScanReach(1, "home") else ScanReach(3, "tools")
    }
}

/** The `tool_open` line's fields: the tool, the way it was reached, the taps from the document screen. */
internal fun scanToolOpenDiag(tool: ScanReachTool, from: String, inReach: Boolean, markup: Boolean): Array<Pair<String, Any?>> =
    arrayOf("tool" to tool.id, "from" to from, "taps" to (scanReach(tool, inReach, markup).taps ?: -1), "in_reach" to inReach)

/** A tool was opened by way of [from] (`bar`, `top`, `more`, `menu`, `selection`, `home`): one `tool_open` line (#516). */
internal fun AppViewModel.scanToolOpened(tool: ScanReachTool, from: String = scanReach(tool, scanLab.options.toolsInReach, scanLab.options.markup).from) {
    val lab = scanLab.options
    Diag.event(DiagArea.Scan, "tool_open", *scanToolOpenDiag(tool, from, lab.toolsInReach, lab.markup))
}

/** Markup opens on the tool last used (#516), when that tool is offered for the page; else the pen, as before. */
internal fun scanMarkupStartTool(last: ScanMarkupTool?, offered: List<ScanMarkupTool>, inReach: Boolean): ScanMarkupTool =
    last?.takeIf { inReach && it in offered } ?: ScanMarkupTool.Pen

/** The tool an item of Enhance's More is, for `tool_open`; null for Add page and Clear markup. */
internal fun scanMoreTool(item: ScanEnhanceMore): ScanReachTool? = when (item) {
    ScanEnhanceMore.ApplyAll -> ScanReachTool.ApplyAll
    ScanEnhanceMore.ResetLook -> ScanReachTool.ResetLook
    ScanEnhanceMore.Compare -> ScanReachTool.Compare
    ScanEnhanceMore.Rename -> ScanReachTool.Rename
    ScanEnhanceMore.Markup -> ScanReachTool.Markup
    ScanEnhanceMore.AddPage, ScanEnhanceMore.ClearMarkup -> null
}

/** The pages' words for Extract text: each page's text, the pages with none left out, a blank line between pages. */
internal fun scanTextJoin(pages: List<String>): String = pages.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")

/** The most characters handed to the share sheet at once: an intent much larger than this fails to start. */
internal const val SCAN_TEXT_MAX = 100_000

/** [text] cut to [SCAN_TEXT_MAX] characters, never inside a surrogate pair. */
internal fun scanTextForIntent(text: String): String {
    if (text.length <= SCAN_TEXT_MAX) return text
    var end = SCAN_TEXT_MAX
    if (Character.isHighSurrogate(text[end - 1])) end--
    return text.substring(0, end)
}
