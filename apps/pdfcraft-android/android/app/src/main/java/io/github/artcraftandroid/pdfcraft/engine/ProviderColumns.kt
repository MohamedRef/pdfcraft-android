package io.github.artcraftandroid.pdfcraft.engine

import android.database.Cursor

/** Read the requested named column, even if a provider returns extra/reordered columns. */
internal fun Cursor.namedString(column: String): String? {
    val index = getColumnIndex(column)
    return if (index >= 0 && !isNull(index)) getString(index) else null
}

internal fun Cursor.namedLong(column: String): Long? {
    val index = getColumnIndex(column)
    return if (index >= 0 && !isNull(index)) getLong(index) else null
}

internal fun Cursor.namedInt(column: String): Int? {
    val index = getColumnIndex(column)
    return if (index >= 0 && !isNull(index)) getInt(index) else null
}
