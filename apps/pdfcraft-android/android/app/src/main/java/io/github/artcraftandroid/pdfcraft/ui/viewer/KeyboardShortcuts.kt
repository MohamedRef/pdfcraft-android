package io.github.artcraftandroid.pdfcraft.ui.viewer

import android.view.KeyEvent

/** What a key does in the viewer (#153). */
enum class ViewerShortcut {
    Pen, Highlighter, Eraser, Lasso, Text, Note, Shapes,
    Leave, Undo, Redo, Find, Bookmark, Delete, Thinner, Thicker,
    NextPage, PreviousPage, ScrollDown, ScrollUp, ScrollRight, ScrollLeft,
    ZoomIn, ZoomOut, Print, Help,
}

/**
 * The viewer's shortcut for a key, or null. Ctrl+Shift+Z and Ctrl+Y redo; Ctrl+/ lists the
 * shortcuts. Only keys no focused text field took reach here, so typing never triggers these.
 */
fun viewerShortcut(keyCode: Int, ctrl: Boolean, shift: Boolean): ViewerShortcut? {
    if (ctrl) {
        return when (keyCode) {
            KeyEvent.KEYCODE_Z -> if (shift) ViewerShortcut.Redo else ViewerShortcut.Undo
            KeyEvent.KEYCODE_Y -> ViewerShortcut.Redo
            KeyEvent.KEYCODE_F -> ViewerShortcut.Find
            KeyEvent.KEYCODE_B -> ViewerShortcut.Bookmark
            KeyEvent.KEYCODE_P -> ViewerShortcut.Print
            KeyEvent.KEYCODE_SLASH -> ViewerShortcut.Help
            KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_NUMPAD_ADD -> ViewerShortcut.ZoomIn
            KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> ViewerShortcut.ZoomOut
            else -> null
        }
    }
    return when (keyCode) {
        KeyEvent.KEYCODE_P -> ViewerShortcut.Pen
        KeyEvent.KEYCODE_H -> ViewerShortcut.Highlighter
        KeyEvent.KEYCODE_E -> ViewerShortcut.Eraser
        KeyEvent.KEYCODE_L -> ViewerShortcut.Lasso
        KeyEvent.KEYCODE_T -> ViewerShortcut.Text
        KeyEvent.KEYCODE_N -> ViewerShortcut.Note
        KeyEvent.KEYCODE_S -> ViewerShortcut.Shapes
        KeyEvent.KEYCODE_ESCAPE -> ViewerShortcut.Leave
        KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_DEL -> ViewerShortcut.Delete
        KeyEvent.KEYCODE_LEFT_BRACKET -> ViewerShortcut.Thinner
        KeyEvent.KEYCODE_RIGHT_BRACKET -> ViewerShortcut.Thicker
        KeyEvent.KEYCODE_PAGE_DOWN -> ViewerShortcut.NextPage
        KeyEvent.KEYCODE_PAGE_UP -> ViewerShortcut.PreviousPage
        KeyEvent.KEYCODE_SPACE -> if (shift) ViewerShortcut.PreviousPage else ViewerShortcut.NextPage
        KeyEvent.KEYCODE_DPAD_DOWN -> ViewerShortcut.ScrollDown
        KeyEvent.KEYCODE_DPAD_UP -> ViewerShortcut.ScrollUp
        KeyEvent.KEYCODE_DPAD_RIGHT -> ViewerShortcut.ScrollRight
        KeyEvent.KEYCODE_DPAD_LEFT -> ViewerShortcut.ScrollLeft
        else -> null
    }
}

/** The pen preset a number key picks (1 to 9: presets 0 to 8), or null. */
fun presetForKey(keyCode: Int): Int? =
    if (keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) keyCode - KeyEvent.KEYCODE_1 else null

/** A pen width one step thinner or thicker ([dir]), within the width slider's range. */
fun steppedInkWidth(width: Float, dir: Int): Float = ((width * 2f).toInt() + dir).coerceIn(2, 20) / 2f

/** One line of the shortcuts list: what it does, and its key (with Ctrl or Shift). */
data class ShortcutEntry(val label: Int, val keyCode: Int, val ctrl: Boolean = false, val shift: Boolean = false)

/** The shortcuts the list shows (Ctrl+/ and the system's Meta+/), grouped as tools then the rest. */
val SHORTCUTS: List<ShortcutEntry> = listOf(
    ShortcutEntry(io.github.artcraftandroid.pdfcraft.R.string.shortcut_pen, KeyEvent.KEYCODE_P),
    ShortcutEntry(io.github.artcraftandroid.pdfcraft.R.string.shortcut_highlight, KeyEvent.KEYCODE_H),
    ShortcutEntry(io.github.artcraftandroid.pdfcraft.R.string.shortcut_eraser, KeyEvent.KEYCODE_E),
    ShortcutEntry(io.github.artcraftandroid.pdfcraft.R.string.shortcut_lasso, KeyEvent.KEYCODE_L),
    ShortcutEntry(io.github.artcraftandroid.pdfcraft.R.string.shortcut_text, KeyEvent.KEYCODE_T),
    ShortcutEntry(io.github.artcraftandroid.pdfcraft.R.string.shortcut_note, KeyEvent.KEYCODE_N),
    ShortcutEntry(io.github.artcraftandroid.pdfcraft.R.string.shortcut_shapes, KeyEvent.KEYCODE_S),
    ShortcutEntry(io.github.artcraftandroid.pdfcraft.R.string.shortcut_pen_preset, KeyEvent.KEYCODE_1),
    ShortcutEntry(io.github.artcraftandroid.pdfcraft.R.string.shortcut_thinner, KeyEvent.KEYCODE_LEFT_BRACKET),
    ShortcutEntry(io.github.artcraftandroid.pdfcraft.R.string.shortcut_thicker, KeyEvent.KEYCODE_RIGHT_BRACKET),
    ShortcutEntry(io.github.artcraftandroid.pdfcraft.R.string.shortcut_leave, KeyEvent.KEYCODE_ESCAPE),
    ShortcutEntry(io.github.artcraftandroid.pdfcraft.R.string.shortcut_delete, KeyEvent.KEYCODE_FORWARD_DEL),
    ShortcutEntry(io.github.artcraftandroid.pdfcraft.R.string.shortcut_undo, KeyEvent.KEYCODE_Z, ctrl = true),
    ShortcutEntry(io.github.artcraftandroid.pdfcraft.R.string.shortcut_redo, KeyEvent.KEYCODE_Z, ctrl = true, shift = true),
    ShortcutEntry(io.github.artcraftandroid.pdfcraft.R.string.shortcut_find, KeyEvent.KEYCODE_F, ctrl = true),
    ShortcutEntry(io.github.artcraftandroid.pdfcraft.R.string.shortcut_bookmark, KeyEvent.KEYCODE_B, ctrl = true),
    ShortcutEntry(io.github.artcraftandroid.pdfcraft.R.string.shortcut_next_page, KeyEvent.KEYCODE_PAGE_DOWN),
    ShortcutEntry(io.github.artcraftandroid.pdfcraft.R.string.shortcut_previous_page, KeyEvent.KEYCODE_PAGE_UP),
    ShortcutEntry(io.github.artcraftandroid.pdfcraft.R.string.shortcut_zoom_in, KeyEvent.KEYCODE_EQUALS, ctrl = true),
    ShortcutEntry(io.github.artcraftandroid.pdfcraft.R.string.shortcut_zoom_out, KeyEvent.KEYCODE_MINUS, ctrl = true),
    ShortcutEntry(io.github.artcraftandroid.pdfcraft.R.string.shortcut_print, KeyEvent.KEYCODE_P, ctrl = true),
    ShortcutEntry(io.github.artcraftandroid.pdfcraft.R.string.shortcut_help, KeyEvent.KEYCODE_SLASH, ctrl = true),
)

/** How a key is written in the list: a letter or sign as itself, named keys from strings. */
fun keyLabel(context: android.content.Context, e: ShortcutEntry): String {
    val key = when (e.keyCode) {
        KeyEvent.KEYCODE_ESCAPE -> context.getString(io.github.artcraftandroid.pdfcraft.R.string.key_esc)
        KeyEvent.KEYCODE_FORWARD_DEL -> context.getString(io.github.artcraftandroid.pdfcraft.R.string.key_delete)
        KeyEvent.KEYCODE_PAGE_DOWN -> context.getString(io.github.artcraftandroid.pdfcraft.R.string.key_page_down)
        KeyEvent.KEYCODE_PAGE_UP -> context.getString(io.github.artcraftandroid.pdfcraft.R.string.key_page_up)
        KeyEvent.KEYCODE_1 -> "1–9"
        else -> KeyEvent(KeyEvent.ACTION_DOWN, e.keyCode).displayLabel.toString()
    }
    val mods = buildString {
        if (e.ctrl) append(context.getString(io.github.artcraftandroid.pdfcraft.R.string.key_ctrl)).append('+')
        if (e.shift) append(context.getString(io.github.artcraftandroid.pdfcraft.R.string.key_shift)).append('+')
    }
    return mods + key
}

/** The shortcuts as the system's Meta+/ helper lists them. */
fun shortcutGroup(context: android.content.Context): android.view.KeyboardShortcutGroup {
    val items = SHORTCUTS.filter { it.keyCode != KeyEvent.KEYCODE_1 }.map { e ->
        val meta = (if (e.ctrl) KeyEvent.META_CTRL_ON else 0) or (if (e.shift) KeyEvent.META_SHIFT_ON else 0)
        val char = KeyEvent(KeyEvent.ACTION_DOWN, e.keyCode).displayLabel
        if (char.isLetterOrDigit() || char in "[]/=-") android.view.KeyboardShortcutInfo(context.getString(e.label), char.uppercaseChar(), meta)
        else android.view.KeyboardShortcutInfo(context.getString(e.label), e.keyCode, meta)
    }
    return android.view.KeyboardShortcutGroup(context.getString(io.github.artcraftandroid.pdfcraft.R.string.shortcut_group), items)
}
