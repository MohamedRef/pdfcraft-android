package io.github.artcraftandroid.pdfcraft

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.artcraftandroid.pdfcraft.engine.FocusTimer
import org.json.JSONObject

/**
 * The focus timer outside the app: a notification counting down the current phase (the system
 * draws the countdown, so nothing runs while it shows) and an alarm at the phase's end that
 * moves to the next phase and says so. State lives in the app's preferences, wall-clock based,
 * so it survives rotation, backgrounding and the process being killed.
 */
object FocusTimerAlarm {
    private const val CHANNEL = "focus"
    private const val NOTIFICATION_ID = 7031
    private const val ACTION_PHASE = "io.github.artcraftandroid.pdfcraft.focus.PHASE"
    private const val PREFS = "app"
    private const val KEY = "focusTimer"

    fun load(context: Context): FocusTimer =
        FocusTimer.fromJson(runCatching { JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: "") }.getOrNull())

    /** Keep [timer], show its countdown and set the alarm for its phase's end (or clear both when stopped). */
    fun apply(context: Context, timer: FocusTimer, now: Long = System.currentTimeMillis(), alert: Boolean = false) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, timer.toJson().toString()).apply()
        val nm = context.getSystemService(NotificationManager::class.java)
        val am = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(context, 0, Intent(context, FocusTimerReceiver::class.java).setAction(ACTION_PHASE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (!timer.running) {
            am?.cancel(pending)
            if (!timer.active) nm?.cancel(NOTIFICATION_ID) else nm?.let { notify(context, it, timer, now, alert) }
            return
        }
        // inexact but allowed while idle: no exact-alarm permission; the countdown itself is exact
        runCatching { am?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timer.endsAt, pending) }
        nm?.let { notify(context, it, timer, now, alert) }
    }

    private fun notify(context: Context, nm: NotificationManager, timer: FocusTimer, now: Long, alert: Boolean) {
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.focus_timer), NotificationManager.IMPORTANCE_DEFAULT).apply { setShowBadge(false) }
        nm.createNotificationChannel(channel)
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val title = context.getString(if (timer.onBreak) R.string.focus_break else R.string.focus_work)
        val b = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_focus)
            .setContentTitle(title)
            .setContentIntent(open)
            .setOngoing(timer.running)
            .setOnlyAlertOnce(!alert)
            .setCategory(Notification.CATEGORY_STOPWATCH)
        if (timer.running) {
            b.setWhen(timer.endsAt).setUsesChronometer(true).setChronometerCountDown(true).setShowWhen(true)
        } else {
            val left = timer.left(now) / 1000
            b.setContentText(context.getString(R.string.focus_paused_left, left / 60, left % 60))
        }
        runCatching { nm.notify(NOTIFICATION_ID, b.build()) }
    }

    internal fun onPhaseAlarm(context: Context) {
        val now = System.currentTimeMillis()
        val t = load(context)
        if (!t.running) return
        apply(context, t.tick(now), now, alert = true)
    }
}

/** The alarm at a focus phase's end. */
class FocusTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        FocusTimerAlarm.onPhaseAlarm(context)
    }
}
