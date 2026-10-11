package no.mwmai.clock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * Turns what is in [Store] into armed alarms, and what fires back into rings.
 *
 * Only two AlarmManager entries ever exist: the soonest alarm and the soonest
 * timer. When one fires, every alarm or timer due by then rings, and the next
 * soonest is armed. Nothing stale can be left behind by a deleted alarm.
 */
object Clockwork {
    const val ACTION_ALARM = "no.mwmai.clock.ALARM"
    const val ACTION_TIMER = "no.mwmai.clock.TIMER"
    private const val TIMER_EARLY_MS = 300L

    // Alarms

    fun saveAlarm(context: Context, alarm: Alarm) {
        Store.updateAlarms { list ->
            if (list.any { it.id == alarm.id }) list.map { if (it.id == alarm.id) alarm else it } else list + alarm
        }
        sync(context)
    }

    fun deleteAlarm(context: Context, id: Int) {
        Store.updateAlarms { list -> list.filterNot { it.id == id } }
        sync(context)
    }

    fun setAlarmEnabled(context: Context, id: Int, on: Boolean) {
        Store.updateAlarm(id) { it.copy(enabled = on, skip = null, snoozedUntil = null, snoozes = 0) }
        sync(context)
    }

    /** Skips the next occurrence of a repeating alarm, or undoes the skip. */
    fun toggleSkip(context: Context, id: Int) {
        val now = System.currentTimeMillis()
        Store.updateAlarm(id) { a ->
            if (a.skip != null) a.copy(skip = null)
            else a.copy(skip = a.copy(skip = null).nextOccurrenceAfter(now), snoozedUntil = null, snoozes = 0)
        }
        sync(context)
    }

    // Timers

    fun addTimer(context: Context, name: String, duration: Long, start: Boolean) {
        val id = Store.nextId()
        val timer = Timer(id, name.ifBlank { "TIMER ${Store.timers.value.size + 1}" }, duration)
        Store.updateTimers { it + timer }
        if (start) startTimer(context, id) else sync(context)
    }

    fun startTimer(context: Context, id: Int) {
        val now = SystemClock.elapsedRealtime()
        Store.updateTimer(id) { t ->
            val left = if (t.remaining <= 0) t.duration else t.remaining
            t.copy(endsAt = now + left, remaining = left, ringing = false)
        }
        sync(context)
    }

    fun pauseTimer(context: Context, id: Int) {
        val now = SystemClock.elapsedRealtime()
        Store.updateTimer(id) { t -> t.copy(remaining = t.remainingAt(now).coerceAtLeast(0), endsAt = null) }
        sync(context)
    }

    fun resetTimer(context: Context, id: Int) {
        Store.updateTimer(id) { t -> t.copy(remaining = t.duration, endsAt = null, ringing = false) }
        Store.removeRing(Ring(Ring.Kind.TIMER, id, 0).key)
        sync(context)
    }

    /** One more minute: on a running timer, on a paused one, or on one that is ringing. */
    fun addMinute(context: Context, id: Int) {
        val now = SystemClock.elapsedRealtime()
        Store.updateTimer(id) { t ->
            when {
                t.ringing -> t.copy(endsAt = now + 60_000, remaining = 60_000, ringing = false)
                t.running -> t.copy(endsAt = t.endsAt!! + 60_000)
                else -> t.copy(remaining = t.remaining + 60_000)
            }
        }
        Store.removeRing(Ring(Ring.Kind.TIMER, id, 0).key)
        sync(context)
    }

    fun renameTimer(context: Context, id: Int, name: String) {
        Store.updateTimer(id) { it.copy(name = name.ifBlank { it.name }) }
        sync(context)
    }

    fun deleteTimer(context: Context, id: Int) {
        Store.updateTimers { list -> list.filterNot { it.id == id } }
        Store.removeRing(Ring(Ring.Kind.TIMER, id, 0).key)
        Notifications.cancelTimer(context, id)
        sync(context)
    }

    // Rings

    fun snooze(context: Context, ring: Ring) {
        if (ring.kind != Ring.Kind.ALARM) return
        Store.updateAlarm(ring.id) { a ->
            a.copy(snoozedUntil = System.currentTimeMillis() + a.snoozeMinutes * 60_000L, snoozes = a.snoozes + 1)
        }
        Store.removeRing(ring.key)
        sync(context)
    }

    fun dismiss(context: Context, ring: Ring) {
        when (ring.kind) {
            Ring.Kind.ALARM -> {
                Store.updateAlarm(ring.id) { it.copy(snoozedUntil = null, snoozes = 0) }
                Store.removeRing(ring.key)
                sync(context)
            }
            Ring.Kind.TIMER -> resetTimer(context, ring.id)
        }
    }

    /** Called by [AlarmReceiver] when one of the two armed entries fires. */
    fun fire(context: Context, action: String?) {
        val due = mutableListOf<Ring>()
        if (action == ACTION_ALARM) {
            val now = System.currentTimeMillis()
            // Measured from just before the armed moment, so a late delivery
            // still finds its alarm. An armed time that makes no sense (never
            // set, or hours old) falls back to the last minute.
            val armed = Store.armedAlarmAt
            val reference = (if (armed in (now - 3_600_000)..now) armed else now - 60_000) - 1
            val ringing = Store.alarms.value.filter { a -> a.nextTrigger(reference)?.let { it <= now + 1000 } == true }
            Store.updateAlarms { list ->
                list.map { a ->
                    if (ringing.none { it.id == a.id }) a
                    // A one-time alarm turns itself off; a repeating one moves on to its next day.
                    else a.copy(enabled = a.repeats, snoozedUntil = null, skip = a.skip?.takeIf { it > now })
                }
            }
            ringing.forEach { due += Ring(Ring.Kind.ALARM, it.id, now) }
        }
        val nowElapsed = SystemClock.elapsedRealtime()
        val finished = Store.timers.value.filter { t ->
            t.running && !t.ringing && t.endsAt!! <= nowElapsed + TIMER_EARLY_MS
        }
        if (finished.isNotEmpty()) {
            Store.updateTimers { list -> list.map { t -> if (finished.any { it.id == t.id }) t.copy(ringing = true) else t } }
            finished.forEach { due += Ring(Ring.Kind.TIMER, it.id, System.currentTimeMillis()) }
        }
        if (due.isNotEmpty()) {
            due.forEach(Store::addRing)
            // An exact alarm lets the app start a foreground service from the
            // background. The inexact fallback does not, so then the ring
            // notification goes up on its own and opens the ring screen.
            runCatching { ContextCompat.startForegroundService(context, Intent(context, RingService::class.java)) }
                .onFailure { Notifications.ringWithoutService(context, due.first()) }
        }
        sync(context)
    }

    /** Arms the soonest alarm and the soonest timer, and refreshes the running-timer notifications. */
    fun sync(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()

        val nextAlarm = Store.alarms.value.mapNotNull { it.nextTrigger(now) }.minOrNull()
        val alarmIntent = firePending(context, ACTION_ALARM)
        if (nextAlarm == null) {
            am.cancel(alarmIntent)
        } else {
            Store.armedAlarmAt = nextAlarm
            // setAlarmClock is the one call Doze always honours on time, and it
            // puts the alarm icon in the status bar.
            val show = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            if (canExact(am)) am.setAlarmClock(AlarmManager.AlarmClockInfo(nextAlarm, show), alarmIntent)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextAlarm, alarmIntent)
        }

        val nowElapsed = SystemClock.elapsedRealtime()
        val nextTimer = Store.timers.value.filter { it.running && !it.ringing }.mapNotNull { it.endsAt }.minOrNull()
        val timerIntent = firePending(context, ACTION_TIMER)
        if (nextTimer == null) {
            am.cancel(timerIntent)
        } else if (canExact(am)) {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, nextTimer, timerIntent)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, nextTimer, timerIntent)
        }

        Store.timers.value.forEach { t ->
            if (t.running && !t.ringing) Notifications.showTimer(context, t, t.remainingAt(nowElapsed))
            else Notifications.cancelTimer(context, t.id)
        }
    }

    fun canExact(am: AlarmManager): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()

    private fun firePending(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        if (action == ACTION_ALARM) 1 else 2,
        Intent(context, AlarmReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
