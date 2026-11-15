package io.github.artcraftandroid.pdfcraft.ui.viewer

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush

/**
 * A pencil's paper grain (#569), exactly as the engine draws it in the saved comment
 * (`grain_on` in `pdfcraft_annot::appearance`): dots of [PT] points on a grid from the page's
 * origin, repeating every [TILE] dots. Every dot has a rank from a fixed integer hash; an ink
 * density shows the dots ranked below it, in [LEVELS] steps, so a denser grain holds every dot of
 * a lighter one and nothing is drawn twice where two densities meet.
 */
internal object PencilGrain {
    const val PT = 0.3f
    const val TILE = 32
    const val LEVELS = 8

    /** The engine's `grain_hash`: multiply, shift, xor, on 32-bit words. */
    fun hash(i: Int): Int {
        var x = i * 0x9E3779B1.toInt()
        x = x xor (x ushr 15)
        x *= 0x85EBCA77.toInt()
        x = x xor (x ushr 13)
        return x
    }

    private val ranks: IntArray by lazy {
        val n = TILE * TILE
        val order = (0 until n).sortedWith(compareBy<Int>({ hash(it).toLong() and 0xFFFFFFFFL }, { it }))
        IntArray(n).also { r -> order.forEachIndexed { rank, i -> r[i] = rank } }
    }

    /** The density as it is saved: two decimals, as `inkSaveCommands` sends it. */
    fun saved(density: Float): Double = Math.round(density.coerceIn(0f, 1f) * 100f) / 100.0

    /** The step (1…[LEVELS]) a saved density is drawn at. */
    fun level(saved: Double): Int = if (!saved.isFinite()) LEVELS else Math.round(saved * LEVELS).toInt().coerceIn(1, LEVELS)

    /** Whether the dot in column [gx], row [gy] (in dots from the page's bottom-left, up) is inked at [level]. */
    fun on(gx: Int, gy: Int, level: Int): Boolean {
        val i = Math.floorMod(gy, TILE) * TILE + Math.floorMod(gx, TILE)
        return ranks[i].toLong() * LEVELS < level.toLong() * TILE * TILE
    }

    private val tiles = object : LinkedHashMap<Long, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Bitmap>?) = size > 32
    }

    /**
     * One tile of the grain at [level] in colour [argb]. Its rows run down the screen while the
     * grid's run up the page, so bitmap row `py` holds grid row `TILE - 1 - py` ([brush] lines it up).
     */
    private fun tile(argb: Int, level: Int): Bitmap = synchronized(tiles) {
        tiles.getOrPut((argb.toLong() shl 8) or level.toLong()) {
            val px = IntArray(TILE * TILE) { i ->
                val x = i % TILE
                val y = i / TILE
                if (on(x, TILE - 1 - y, level)) argb or (0xFF shl 24) else 0
            }
            Bitmap.createBitmap(px, TILE, TILE, Bitmap.Config.ARGB_8888)
        }
    }

    /**
     * The brush that paints the grain at [level] in [argb], for drawing in page points (y down)
     * on a page [pageHeightPt] tall: the grid then meets the saved comment's, which counts from
     * the page's bottom edge.
     */
    fun brush(argb: Int, level: Int, pageHeightPt: Float): ShaderBrush {
        val shader = BitmapShader(tile(argb, level), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        val period = PT * TILE
        val h = pageHeightPt.takeIf { it.isFinite() } ?: 0f
        shader.setLocalMatrix(Matrix().apply { setScale(PT, PT); postTranslate(0f, ((h % period) + period) % period) })
        return ShaderBrush(shader)
    }
}
