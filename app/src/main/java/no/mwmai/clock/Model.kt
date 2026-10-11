package no.mwmai.clock

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

data class Alarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val days: Int = 0,
    val label: String = "",
    val enabled: Boolean = true,
    // Ringtone uri; null means Android's default alarm sound.
    val sound: String? = null,
    val soundName: String? = null,
    val vibrate: Boolean = true,
    val rampSeconds: Int = 30,
    val snoozeMinutes: Int = 10,
    val maxSnoozes: Int = 3,
    val challenge: Challenge = Challenge.OFF,
    // One occurrence (epoch ms) the user chose to skip.
    val skip: Long? = null,
    val snoozedUntil: Long? = null,
    val snoozes: Int = 0,
) {
    val repeats: Boolean get() = days != 0

    /** When this alarm next rings after [after] (epoch ms), or null when it is off. */
    fun nextTrigger(after: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (!enabled) return null
        snoozedUntil?.takeIf { it > after }?.let { return it }
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(after), zone)
        return nextOccurrence(hour, minute, days, now, skip).toInstant().toEpochMilli()
    }

    /** The occurrence that SKIP NEXT would skip: the next one, ignoring any snooze. */
    fun nextOccurrenceAfter(after: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        nextOccurrence(hour, minute, days, ZonedDateTime.ofInstant(Instant.ofEpochMilli(after), zone), skip)
            .toInstant().toEpochMilli()
}

/**
 * A countdown. [endsAt] is in SystemClock.elapsedRealtime() milliseconds while
 * it runs, so changing the wall clock cannot stretch or shrink it. Once it
 * passes zero it keeps [endsAt] so the screen can count the overtime.
 */
data class Timer(
    val id: Int,
    val name: String,
    val duration: Long,
    val remaining: Long = duration,
    val endsAt: Long? = null,
    val ringing: Boolean = false,
) {
    val running: Boolean get() = endsAt != null
    fun remainingAt(nowElapsed: Long): Long = endsAt?.let { it - nowElapsed } ?: remaining
    val idle: Boolean get() = endsAt == null && remaining == duration
}

/** [startedAt] is in elapsedRealtime milliseconds; [laps] holds the total time at each LAP press. */
data class Stopwatch(
    val id: Int,
    val name: String,
    val accumulated: Long = 0,
    val startedAt: Long? = null,
    val laps: List<Long> = emptyList(),
) {
    val running: Boolean get() = startedAt != null
    fun elapsedAt(nowElapsed: Long): Long = accumulated + (startedAt?.let { nowElapsed - it } ?: 0L)
}

/** Something sounding right now. */
data class Ring(val kind: Kind, val id: Int, val since: Long) {
    enum class Kind { ALARM, TIMER }
    val key: String get() = "$kind:$id"
}
