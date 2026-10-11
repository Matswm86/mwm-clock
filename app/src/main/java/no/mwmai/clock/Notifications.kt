package no.mwmai.clock

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notifications {
    // The ring channel is silent on purpose: RingService plays the sound
    // itself, on the alarm stream, so it rises gently and obeys the alarm
    // volume instead of the notification volume.
    private const val CHANNEL_RING = "ring"
    private const val CHANNEL_RUNNING = "running"
    const val RING_ID = 1
    private const val MISSED_ID = 2
    private const val TIMER_BASE = 1000

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RING, "Ringing alarms and timers", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RUNNING, "Running timers", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
    }

    private fun canPost(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** An ongoing notification that counts the timer down on its own, so nothing has to keep running. */
    fun showTimer(context: Context, timer: Timer, remainingMs: Long) {
        if (!canPost(context)) return
        createChannels(context)
        val n = NotificationCompat.Builder(context, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_stat_clock)
            .setContentTitle(timer.name)
            .setContentText("Timer running")
            .setWhen(System.currentTimeMillis() + remainingMs)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setShowWhen(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openApp(context))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(TIMER_BASE + timer.id, n) }
    }

    fun cancelTimer(context: Context, id: Int) {
        NotificationManagerCompat.from(context).cancel(TIMER_BASE + id)
    }

    /**
     * The notification that keeps RingService in the foreground. Its full
     * screen intent opens the ring screen over the lock screen; when the phone
     * is in use, Android shows it as a heads-up banner with the buttons on it.
     */
    fun ring(context: Context, ring: Ring, title: String, text: String, canSnooze: Boolean, oneTapDismiss: Boolean): Notification {
        createChannels(context)
        val full = PendingIntent.getActivity(
            context, 10,
            Intent(context, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(context, CHANNEL_RING)
            .setSmallIcon(R.drawable.ic_stat_clock)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(full)
            .setFullScreenIntent(full, true)
        if (canSnooze) b.addAction(0, "Snooze", RingService.actionIntent(context, RingService.ACTION_SNOOZE, ring))
        if (ring.kind == Ring.Kind.TIMER) {
            b.addAction(0, "+1 min", RingService.actionIntent(context, RingService.ACTION_ADD_MINUTE, ring))
        }
        // With a math challenge, the only way off is the ring screen.
        if (oneTapDismiss) {
            val label = if (ring.kind == Ring.Kind.TIMER) "Stop" else "Dismiss"
            b.addAction(0, label, RingService.actionIntent(context, RingService.ACTION_DISMISS, ring))
        }
        return b.build()
    }

    fun ringWithoutService(context: Context, ring: Ring) {
        if (!canPost(context)) return
        val info = describe(context, ring)
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(RING_ID, ring(context, ring, info.title, info.text, canSnooze = false, oneTapDismiss = false))
        }
    }

    fun placeholder(context: Context): Notification {
        createChannels(context)
        return NotificationCompat.Builder(context, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_stat_clock)
            .setContentTitle("MWM Clock")
            .setSilent(true)
            .build()
    }

    fun missed(context: Context, title: String) {
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_stat_clock)
            .setContentTitle(title)
            .setContentText("Rang for 10 minutes with no answer and was silenced")
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(MISSED_ID, n) }
    }
}
