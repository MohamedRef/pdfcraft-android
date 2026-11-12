package io.github.artcraftandroid.pdfcraft.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import java.io.File

/**
 * The Android side of the diagnostics log ([Diag]): turning it on and off from Settings, the
 * memory snapshot lines, and the crash line written before the process ends.
 */
internal object Diagnostics {
    private const val PREFS = "app"
    const val PREF_KEY = "recordDiagnostics"

    /** Before the full log, the switch only recorded open timings, under this key. */
    private const val OLD_PREF_KEY = "recordOpenTimings"

    /** One sink per process: its writer thread is shared by recording, sharing and clearing. */
    private var files: DiagSink? = null

    @Synchronized
    private fun files(context: Context): DiagSink =
        files ?: DiagSink(File(context.applicationContext.filesDir, "diagnostics")).also { files = it }

    fun isOn(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_KEY, prefs.getBoolean(OLD_PREF_KEY, false))
    }

    /** At process start: resumes recording when it was on, and installs the crash line. */
    fun start(context: Context) {
        if (isOn(context)) {
            Diag.sink = files(context)
            Diag.event(DiagArea.App, "process_start", "api" to android.os.Build.VERSION.SDK_INT)
            memory("process_start")
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { Diag.sink?.addNow(crashLine(Diag.now(), thread.name == "main", error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Settings → Diagnostics. Turning it off deletes what was recorded. Blocking: call off the main thread. */
    fun setOn(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_KEY, on).remove(OLD_PREF_KEY).apply()
        if (on) {
            Diag.sink = files(context)
            Diag.event(DiagArea.App, "recording_on")
            memory("recording_on")
        } else {
            Diag.sink = null
            files(context).clear()
        }
    }

    /** Starts a fresh log. Blocking: call off the main thread. */
    fun clear(context: Context) {
        files(context).clear()
        Diag.event(DiagArea.App, "log_cleared")
    }

    /** Heap use at [stage]: the Java heap and the native heap (where the engine's documents live). */
    fun memory(stage: String) {
        if (!Diag.enabled()) return
        val rt = Runtime.getRuntime()
        Diag.event(
            DiagArea.Memory, "heap",
            "stage" to stage,
            "java_used_mb" to (rt.totalMemory() - rt.freeMemory()) / MB,
            "java_max_mb" to rt.maxMemory() / MB,
            "native_mb" to Debug.getNativeHeapAllocatedSize() / MB,
        )
    }

    /** Facts about the device for the report's header; no account or user data. */
    fun facts(context: Context, appVersion: String, versionCode: Long, engineVersion: String): List<Pair<String, String>> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        return listOf(
            "app" to "$appVersion ($versionCode)",
            "engine" to engineVersion,
            "device" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            "android" to "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
            "memory_class_mb" to "${am?.memoryClass ?: 0} (large ${am?.largeMemoryClass ?: 0})",
            "device_ram_mb" to "${mem.totalMem / MB}",
            "available_ram_mb" to "${mem.availMem / MB} (low ${mem.lowMemory})",
            "max_heap_mb" to "${Runtime.getRuntime().maxMemory() / MB}",
        )
    }

    /** Blocking: call off the main thread. */
    fun lines(context: Context): List<String> = files(context).lines()

    private const val MB = 1024L * 1024
}

private val FRAME = Regex("[A-Za-z0-9_.$<>:-]{1,160}")

/**
 * A crash (#114): the exception classes and up to 12 code locations (class, method, line) of the
 * app's own code and the libraries'. Never the message, which can hold a path or document text.
 */
internal fun crashLine(at: java.time.LocalDateTime, mainThread: Boolean, error: Throwable): String {
    val classes = generateSequence(error) { it.cause }.take(4).joinToString(">") { it.javaClass.simpleName.ifEmpty { "anonymous" } }
    val frames = error.stackTrace.take(12).joinToString("|") { f ->
        "${f.className.substringAfterLast('.')}.${f.methodName}:${f.lineNumber}".takeIf { FRAME.matches(it) } ?: "?"
    }
    return diagPrefix(at, DiagArea.Error) + "crash main_thread=$mainThread error_class=${classes.takeIf { FRAME.matches(it) } ?: "?"} frames=$frames"
}
