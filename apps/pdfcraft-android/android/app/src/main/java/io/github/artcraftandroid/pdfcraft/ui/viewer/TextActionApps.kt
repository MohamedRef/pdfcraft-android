package io.github.artcraftandroid.pdfcraft.ui.viewer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/** Another app's text action (Android's "process text": translate, define, search…), offered behind More (#535). */
class TextActionApp(val label: String, val icon: ImageBitmap?, val component: ComponentName)

/**
 * The installed activities that take selected text (ACTION_PROCESS_TEXT, text/plain), in the
 * order the system lists them; this app's own are left out. The manifest's `<queries>` makes them
 * visible on Android 11 and later. Nothing is bundled: an empty list when none is installed.
 */
fun textActionApps(context: Context): List<TextActionApp> {
    val pm = context.packageManager
    val probe = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
    @Suppress("DEPRECATION") // the flags overload taking ResolveInfoFlags needs API 33
    val found = runCatching { pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY) }.getOrDefault(emptyList())
    val px = (24 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    return found.mapNotNull { r ->
        val a = r.activityInfo ?: return@mapNotNull null
        if (a.packageName == context.packageName || !a.exported) return@mapNotNull null
        TextActionApp(
            label = r.loadLabel(pm).toString(),
            icon = runCatching { r.loadIcon(pm).toBitmap(px, px).asImageBitmap() }.getOrNull(),
            component = ComponentName(a.packageName, a.name),
        )
    }
}

/** Start [app] on [text], read only: the PDF's text is never replaced by what it returns. */
fun processTextIntent(app: TextActionApp, text: String): Intent =
    Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").setComponent(app.component)
        .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
        .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
