package io.github.artcraftandroid.pdfcraft.engine

import android.database.Cursor
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Real Cursor interface with deterministic provider rows; no Android method stubs execute. */
class ProviderColumnsTest {
    private fun row(vararg cells: Pair<String, Any?>): Cursor {
        val columns = cells.map { it.first }
        val values = cells.map { it.second }
        return Proxy.newProxyInstance(Cursor::class.java.classLoader, arrayOf(Cursor::class.java)) { _, method, args ->
            when (method.name) {
                "getColumnIndex" -> columns.indexOf(args!![0] as String)
                "isNull" -> values[args!![0] as Int] == null
                "getString" -> values[args!![0] as Int] as String?
                "getLong" -> (values[args!![0] as Int] as Number).toLong()
                "getInt" -> (values[args!![0] as Int] as Number).toInt()
                else -> error("Unexpected Cursor call: ${method.name}")
            }
        } as Cursor
    }

    @Test fun reorderedProviderNameDoesNotBecomeNumericRowId() {
        val cursor = row("_id" to "2", "flags" to 0, "_display_name" to "Study notes.pdf")
        assertEquals("Study notes.pdf", cursor.namedString("_display_name"))
    }

    @Test fun sizeDateAndReadOnlyFlagsUseTheirOwnColumns() {
        val cursor = row("_id" to 2L, "_size" to 352_078_544L, "last_modified" to 1_700_000_000_000L, "flags" to 0)
        assertEquals(352_078_544L, cursor.namedLong("_size"))
        assertEquals(1_700_000_000_000L, cursor.namedLong("last_modified"))
        assertEquals(0, cursor.namedInt("flags"))
    }

    @Test fun absentOrNullMetadataDoesNotReadAnUnrelatedColumn() {
        val cursor = row("_id" to "2", "_display_name" to null, "_size" to null, "flags" to null)
        assertNull(cursor.namedString("_display_name"))
        assertNull(cursor.namedString("unknown"))
        assertNull(cursor.namedLong("_size"))
        assertNull(cursor.namedLong("last_modified"))
        assertNull(cursor.namedInt("flags"))
    }

    @Test fun ordinarySingleColumnProviderStillWorks() {
        assertEquals("Book.pdf", row("_display_name" to "Book.pdf").namedString("_display_name"))
        assertEquals(17L, row("_size" to 17L).namedLong("_size"))
    }
}
