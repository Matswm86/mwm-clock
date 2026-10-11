package no.mwmai.clock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

class ClockMathTest {
    private val oslo = ZoneId.of("Europe/Oslo")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi), oslo)

    // 2026-10-12 is a Monday.

    @Test
    fun onceRingsTodayWhenTheTimeIsStillAhead() {
        assertEquals(at(2026, 10, 12, 7, 30), nextOccurrence(7, 30, 0, at(2026, 10, 12, 6, 0)))
    }

    @Test
    fun onceRingsTomorrowWhenTheTimeHasPassed() {
        assertEquals(at(2026, 10, 13, 7, 30), nextOccurrence(7, 30, 0, at(2026, 10, 12, 8, 0)))
    }

    @Test
    fun exactlyNowIsNotNext() {
        assertEquals(at(2026, 10, 13, 7, 30), nextOccurrence(7, 30, 0, at(2026, 10, 12, 7, 30)))
    }

    @Test
    fun weekdaysSkipTheWeekend() {
        // Friday 2026-10-16 after 07:00 -> Monday 2026-10-19.
        assertEquals(at(2026, 10, 19, 7, 0), nextOccurrence(7, 0, WEEKDAYS, at(2026, 10, 16, 9, 0)))
    }

    @Test
    fun singleDayAlarmWaitsForItsDay() {
        val sunday = dayBit(DayOfWeek.SUNDAY)
        assertEquals(at(2026, 10, 18, 10, 0), nextOccurrence(10, 0, sunday, at(2026, 10, 12, 6, 0)))
    }

    @Test
    fun skipJumpsToTheFollowingOccurrence() {
        val monday = dayBit(DayOfWeek.MONDAY)
        val now = at(2026, 10, 11, 20, 0)
        val first = nextOccurrence(7, 0, monday, now)
        assertEquals(at(2026, 10, 12, 7, 0), first)
        val skipped = nextOccurrence(7, 0, monday, now, skip = first.toInstant().toEpochMilli())
        assertEquals(at(2026, 10, 19, 7, 0), skipped)
    }

    @Test
    fun springForwardGapRingsAtTheShiftedTime() {
        // Oslo skipped 02:00-03:00 on 2026-03-29; an alarm at 02:30 rings at 03:30 that night.
        val ring = nextOccurrence(2, 30, 0, at(2026, 3, 29, 1, 0))
        assertEquals(3, ring.hour)
        assertEquals(30, ring.minute)
        assertEquals(29, ring.dayOfMonth)
    }

    @Test
    fun fallBackStillRingsOnce() {
        // 2026-10-25 02:30 happens twice in Oslo; the alarm takes the first one.
        val ring = nextOccurrence(2, 30, 0, at(2026, 10, 25, 1, 0))
        assertEquals(at(2026, 10, 25, 2, 30).toInstant(), ring.toInstant())
    }

    @Test
    fun alarmModelHonoursSnoozeAndOff() {
        val now = at(2026, 10, 12, 6, 0).toInstant().toEpochMilli()
        val alarm = Alarm(id = 1, hour = 7, minute = 0)
        assertEquals(at(2026, 10, 12, 7, 0).toInstant().toEpochMilli(), alarm.nextTrigger(now, oslo))
        assertEquals(now + 600_000, alarm.copy(snoozedUntil = now + 600_000).nextTrigger(now, oslo))
        assertNull(alarm.copy(enabled = false).nextTrigger(now, oslo))
        // A snooze already in the past is ignored.
        assertEquals(alarm.nextTrigger(now, oslo), alarm.copy(snoozedUntil = now - 1).nextTrigger(now, oslo))
    }

    @Test
    fun repeatTextNamesCommonSets() {
        assertEquals("ONCE", repeatText(0))
        assertEquals("EVERY DAY", repeatText(EVERY_DAY))
        assertEquals("WEEKDAYS", repeatText(WEEKDAYS))
        assertEquals("WEEKENDS", repeatText(WEEKEND))
        assertEquals("MON WED", repeatText(dayBit(DayOfWeek.MONDAY) or dayBit(DayOfWeek.WEDNESDAY)))
    }

    @Test
    fun untilTextRoundsUp() {
        assertEquals("IN 1M", untilText(20_000))
        assertEquals("IN 2M", untilText(61_000))
        assertEquals("IN 7H 32M", untilText((7 * 60 + 32) * 60_000L))
        assertEquals("IN 2D 3H", untilText((2 * 1440 + 3 * 60) * 60_000L))
    }

    @Test
    fun stopwatchFormat() {
        assertEquals("00:00.00", formatStopwatch(0))
        assertEquals("01:05.43", formatStopwatch(65_437))
        assertEquals("1:00:00.00", formatStopwatch(3_600_000))
    }

    @Test
    fun timerFormatRoundsUpAndShowsOvertime() {
        assertEquals("0:01", formatTimer(1))
        assertEquals("1:30", formatTimer(90_000))
        assertEquals("1:00:00", formatTimer(3_600_000))
        assertEquals("0:00", formatTimer(0))
        assertEquals("-0:12", formatTimer(-12_400))
    }

    @Test
    fun clockFormat() {
        assertEquals("07:05", formatClock(7, 5, true))
        assertEquals("12:00", formatClock(0, 0, false))
        assertEquals("1:15", formatClock(13, 15, false))
        assertEquals("PM", amPm(12))
    }

    @Test
    fun keypadFillsFromTheRight() {
        assertEquals(90_000L, keypadMillis("130"))
        assertEquals(90_000L, keypadMillis("90"))
        assertEquals((3600 + 2 * 60 + 3) * 1000L, keypadMillis("10203"))
        assertEquals(0L, keypadMillis(""))
        assertEquals("00h 01m 30s", keypadText("130"))
    }

    @Test
    fun lapsAreDifferencesOfTotals() {
        val totals = listOf(10_000L, 25_000L, 33_000L)
        assertEquals(listOf(10_000L, 15_000L, 8_000L), lapTimes(totals))
        assertEquals(2 to 1, lapExtremes(totals))
        assertNull(lapExtremes(listOf(10_000L)))
        assertNull(lapExtremes(listOf(10_000L, 20_000L)))
    }

    @Test
    fun rampStartsQuietAndEndsFull() {
        assertEquals(1f, rampVolume(0, 0), 1e-6f)
        assertEquals(0.02f, rampVolume(0, 30_000), 1e-6f)
        assertEquals(0.25f, rampVolume(15_000, 30_000), 1e-6f)
        assertEquals(1f, rampVolume(60_000, 30_000), 1e-6f)
    }

    @Test
    fun snoozeLimit() {
        assertTrue(canSnooze(3, 2))
        assertFalse(canSnooze(3, 3))
        assertFalse(canSnooze(0, 0))
        assertTrue(canSnooze(-1, 99))
    }

    @Test
    fun mathProblemsCarryTheirAnswer() {
        val random = Random(42)
        repeat(200) {
            val easy = mathProblem(hard = false, random = random)
            val (a, b) = easy.text.split(" + ").map(String::toInt)
            assertEquals(a + b, easy.answer)
            val hard = mathProblem(hard = true, random = random)
            val m = Regex("""(\d+) × (\d+) \+ (\d+)""").matchEntire(hard.text)!!.groupValues
            assertEquals(m[1].toInt() * m[2].toInt() + m[3].toInt(), hard.answer)
        }
    }
}
