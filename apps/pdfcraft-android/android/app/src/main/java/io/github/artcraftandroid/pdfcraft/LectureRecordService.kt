package io.github.artcraftandroid.pdfcraft

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder

/**
 * Keeps a lecture recording going while PdfCraft is in the background or the screen is off: a
 * foreground microphone service with a notification showing the time recorded and a Stop
 * button. The recorder itself stays in the app; this only holds the process in the foreground.
 */
class LectureRecordService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.lecture_title), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            onStop?.invoke()
            stopSelf()
            return START_NOT_STICKY
        }
        val base = intent?.getLongExtra(EXTRA_BASE, System.currentTimeMillis()) ?: System.currentTimeMillis()
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        val stop = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), getString(R.string.lecture_stop),
            PendingIntent.getService(this, 1, Intent(this, LectureRecordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE),
        ).build()
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_focus)
            .setContentTitle(getString(R.string.lecture_recording))
            .setContentText(title)
            .setWhen(base).setUsesChronometer(true).setShowWhen(true)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(stop)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIFICATION, n)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // swiped away from recents: the recording is closed so what was said so far is kept
        onStop?.invoke()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    companion object {
        private const val CHANNEL = "lecture"
        private const val NOTIFICATION = 9
        private const val ACTION_STOP = "io.github.artcraftandroid.pdfcraft.lecture.STOP"
        private const val EXTRA_BASE = "base"
        private const val EXTRA_TITLE = "title"

        /** What Stop does: set by the app while recording. */
        @Volatile
        var onStop: (() -> Unit)? = null

        /** Show the notification; [base] is the wall-clock time the count starts from (pauses left out). */
        fun show(context: Context, title: String, base: Long) {
            val i = Intent(context, LectureRecordService::class.java).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_BASE, base)
            runCatching { context.startForegroundService(i) }
        }

        fun hide(context: Context) {
            runCatching { context.stopService(Intent(context, LectureRecordService::class.java)) }
        }
    }
}
