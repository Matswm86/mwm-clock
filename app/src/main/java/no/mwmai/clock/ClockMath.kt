package no.mwmai.clock

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.math.ceil
import kotlin.random.Random

// Pure functions only: no Android types, so the unit tests run on a plain JVM.

/** An alarm's repeat days are a bit mask: Monday is bit 0, Sunday is bit 6. */
fun dayBit(day: DayOfWeek): Int = 1 shl (day.value - 1)

const val EVERY_DAY = 0x7F
const val WEEKDAYS = 0x1F
const val WEEKEND = 0x60

/**
 * The next time an alarm set for [hour]:[minute] goes off, strictly after [now].
 * [days] == 0 means once: the next time the clock shows that time.
 * [skip] (epoch ms) is one occurrence the user chose to skip.
 * On a spring-forward day a time that does not exist rings at the shifted
 * time, because ZonedDateTime.of moves it forward by the gap.
 */
fun nextOccurrence(hour: Int, minute: Int, days: Int, now: ZonedDateTime, skip: Long? = null): ZonedDateTime {
    var date = now.toLocalDate()
    // Fifteen days covers a weekly alarm whose next occurrence is skipped.
    repeat(15) {
        val candidate = ZonedDateTime.of(date, LocalTime.of(hour, minute), now.zone)
        val dayMatches = days == 0 || (days and dayBit(date.dayOfWeek)) != 0
        if (dayMatches && candidate.isAfter(now) && candidate.toInstant().toEpochMilli() != skip) {
            return candidate
        }
        date = date.plusDays(1)
    }
    error("no occurrence of $hour:$minute on days $days within 15 days")
}

/** Short day names for the repeat line, Monday first. */
val DAY_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")

fun repeatText(days: Int): String = when (days) {
    0 -> "ONCE"
    EVERY_DAY -> "EVERY DAY"
    WEEKDAYS -> "WEEKDAYS"
    WEEKEND -> "WEEKENDS"
    else -> listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
        .filterIndexed { i, _ -> (days and (1 shl i)) != 0 }
        .joinToString(" ")
}

/** "IN 7H 32M". Rounds up, so an alarm 20 seconds away reads "IN 1M". */
fun untilText(ms: Long): String {
    val minutes = ceil(ms.coerceAtLeast(0) / 60_000.0).toLong().coerceAtLeast(1)
    val d = minutes / 1440
    val h = minutes % 1440 / 60
    val m = minutes % 60
    return when {
        d > 0 -> "IN ${d}D ${h}H"
        h > 0 -> "IN ${h}H ${m}M"
        else -> "IN ${m}M"
    }
}

/** Stopwatch time, "MM:SS.cc", or "H:MM:SS.cc" past an hour. */
fun formatStopwatch(ms: Long): String {
    val t = ms.coerceAtLeast(0)
    val cs = t / 10 % 100
    val s = t / 1000 % 60
    val m = t / 60_000 % 60
    val h = t / 3_600_000
    return if (h > 0) "%d:%02d:%02d.%02d".format(h, m, s, cs) else "%02d:%02d.%02d".format(m, s, cs)
}

/**
 * Timer time, "M:SS" or "H:MM:SS". A running timer shows whole seconds rounded
 * up, so it reads 0:01 until it is really done. Past zero it counts the
 * overtime with a minus sign.
 */
fun formatTimer(ms: Long): String {
    val over = ms < 0
    val total = if (over) -ms / 1000 else ceil(ms / 1000.0).toLong()
    val s = total % 60
    val m = total / 60 % 60
    val h = total / 3600
    val body = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    return if (over) "-$body" else body
}

/** Wall-clock time of day for display. */
fun formatClock(hour: Int, minute: Int, is24Hour: Boolean): String {
    if (is24Hour) return "%02d:%02d".format(hour, minute)
    val h12 = if (hour % 12 == 0) 12 else hour % 12
    return "%d:%02d".format(h12, minute)
}

fun amPm(hour: Int): String = if (hour < 12) "AM" else "PM"

/**
 * Timer keypad entry. Digits fill from the right as H H M M S S, like a
 * microwave: typing 1, 3, 0 means 1 minute 30 seconds. Entries such as
 * "90" seconds are allowed and simply mean 90 seconds.
 */
const val KEYPAD_DIGITS = 6

fun keypadMillis(digits: String): Long {
    val padded = digits.takeLast(KEYPAD_DIGITS).padStart(KEYPAD_DIGITS, '0')
    val h = padded.substring(0, 2).toLong()
    val m = padded.substring(2, 4).toLong()
    val s = padded.substring(4, 6).toLong()
    return (h * 3600 + m * 60 + s) * 1000
}

fun keypadText(digits: String): String {
    val p = digits.takeLast(KEYPAD_DIGITS).padStart(KEYPAD_DIGITS, '0')
    return "${p.substring(0, 2)}h ${p.substring(2, 4)}m ${p.substring(4, 6)}s"
}

/** Lap times from the cumulative totals recorded at each LAP press. */
fun lapTimes(totals: List<Long>): List<Long> = totals.mapIndexed { i, t -> t - (totals.getOrNull(i - 1) ?: 0L) }

/** Indexes of the fastest and slowest lap; null below two laps, or when every lap is the same. */
fun lapExtremes(totals: List<Long>): Pair<Int, Int>? {
    val laps = lapTimes(totals)
    if (laps.size < 2) return null
    val fastest = laps.indices.minBy { laps[it] }
    val slowest = laps.indices.maxBy { laps[it] }
    return if (laps[fastest] == laps[slowest]) null else fastest to slowest
}

/**
 * Alarm volume while it rises: amplitude goes up with the square of time so
 * the start is really quiet and the loudness feels like an even climb.
 */
fun rampVolume(elapsedMs: Long, rampMs: Long): Float {
    if (rampMs <= 0) return 1f
    val f = (elapsedMs.toFloat() / rampMs).coerceIn(0f, 1f)
    return (f * f).coerceAtLeast(0.02f)
}

/** [maxSnoozes] below zero means no limit. */
fun canSnooze(maxSnoozes: Int, snoozesSoFar: Int): Boolean = maxSnoozes < 0 || snoozesSoFar < maxSnoozes

/** What it takes to turn an alarm off. Only alarms have a challenge; timers stop with one tap. */
enum class Challenge(val label: String, val problems: Int, val hard: Boolean) {
    OFF("OFF", 0, false),
    EASY("1 SUM", 1, false),
    HARD("3 HARD", 3, true),
}

data class Problem(val text: String, val answer: Int)

/** A sum that needs a moment of real thought, so you are awake when you finish it. */
fun mathProblem(hard: Boolean, random: Random = Random.Default): Problem = if (hard) {
    val a = random.nextInt(12, 20)
    val b = random.nextInt(3, 10)
    val c = random.nextInt(11, 100)
    Problem("$a × $b + $c", a * b + c)
} else {
    val a = random.nextInt(12, 50)
    val b = random.nextInt(12, 50)
    Problem("$a + $b", a + b)
}
