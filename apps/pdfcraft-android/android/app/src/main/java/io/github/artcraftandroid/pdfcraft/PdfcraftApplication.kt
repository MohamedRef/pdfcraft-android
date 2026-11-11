package io.github.artcraftandroid.pdfcraft

import android.app.Application
import android.content.ComponentCallbacks2
import io.github.artcraftandroid.pdfcraft.engine.Engine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** What a memory trim at a given level drops: page images not on screen, and native caches. */
internal data class Trim(val images: Boolean, val native: Boolean)

@Suppress("DEPRECATION") // RUNNING_* levels still arrive before Android 14
internal fun trimFor(level: Int): Trim {
    val pressure = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
        level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
    return Trim(images = pressure || level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, native = pressure)
}

class PdfcraftApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The engine, loaded once per process on a background thread and shared by the activities. */
    val engine: Deferred<Result<Engine>> by lazy { scope.async { runCatching { Engine.open(this@PdfcraftApplication) } } }

    override fun onCreate() {
        super.onCreate()
        initText(this)
    }

    /**
     * #92: going to the background drops the page images that are not on screen, but keeps the
     * parsed documents, so coming back does not parse a large PDF again. Only real memory
     * pressure drops the native renderers and text layers too, and never on the main thread
     * (that call waits for any extraction in progress).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val trim = trimFor(level)
        if (trim.images) io.github.artcraftandroid.pdfcraft.ui.viewer.PageImages.trimAll()
        if (trim.native && engine.isCompleted) {
            val eng = engine.getCompleted().getOrNull() ?: return
            scope.launch { eng.trimMemory() }
        }
    }
}
