package io.github.artcraftandroid.pdfcraft.ui.scan

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea

/**
 * Book mode (#303): whether each photo taken is cut into the two pages of a book spread, and the
 * order the two go in. Both are remembered. The order starts from the app's layout direction
 * ([rtl]: Arabic gives right to left).
 */
internal class ScanBookController(private val prefs: SharedPreferences, private val rtl: () -> Boolean) {
    var on by mutableStateOf(prefs.getBoolean(PREF_ON, false))
        private set

    var order by mutableStateOf(ScanBookOrder.of(prefs.getString(PREF_ORDER, null)) ?: ScanBookOrder.default(rtl()))
        private set

    fun turn(value: Boolean) {
        if (value == on) return
        on = value
        prefs.edit().putBoolean(PREF_ON, value).apply()
        Diag.event(DiagArea.Scan, "book_mode", "on" to value, "book_order" to order.id)
    }

    fun flipOrder() {
        order = if (order == ScanBookOrder.RightToLeft) ScanBookOrder.LeftToRight else ScanBookOrder.RightToLeft
        prefs.edit().putString(PREF_ORDER, order.id).apply()
        Diag.event(DiagArea.Scan, "book_mode", "on" to on, "book_order" to order.id)
    }

    private companion object {
        const val PREF_ON = "scanBook"
        const val PREF_ORDER = "scanBookOrder"
    }
}
