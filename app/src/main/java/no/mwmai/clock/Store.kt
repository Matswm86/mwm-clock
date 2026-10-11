package no.mwmai.clock

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the app knows, in memory as StateFlows and on disk as JSON in
 * SharedPreferences. The preferences live in device-protected storage so an
 * alarm set before a reboot still rings before the phone is unlocked.
 *
 * Every change goes through here and ends with [Clockwork.sync], which arms
 * AlarmManager and the notifications to match.
 */
object Store {
    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context

    private val _alarms = MutableStateFlow<List<Alarm>>(emptyList())
    private val _timers = MutableStateFlow<List<Timer>>(emptyList())
    private val _stopwatches = MutableStateFlow<List<Stopwatch>>(emptyList())
    private val _rings = MutableStateFlow<List<Ring>>(emptyList())
    val alarms: StateFlow<List<Alarm>> = _alarms.asStateFlow()
    val timers: StateFlow<List<Timer>> = _timers.asStateFlow()
    val stopwatches: StateFlow<List<Stopwatch>> = _stopwatches.asStateFlow()

    /** What is sounding now. Kept in memory only: the ring service owns it. */
    val rings: StateFlow<List<Ring>> = _rings.asStateFlow()

    @Synchronized
    fun load(context: Context) {
        if (::prefs.isInitialized) return
        appContext = context.applicationContext
        val storage = appContext.createDeviceProtectedStorageContext()
        prefs = storage.getSharedPreferences("clock", Context.MODE_PRIVATE)
        _alarms.value = readList("alarms", ::alarmFrom)
        _timers.value = readList("timers", ::timerFrom)
        _stopwatches.value = readList("stopwatches", ::stopwatchFrom)
        rebaseAfterReboot()
    }

    var armedAlarmAt: Long
        get() = prefs.getLong("armed_alarm_at", 0L)
        set(value) = prefs.edit { putLong("armed_alarm_at", value) }

    var lastTab: Int
        get() = prefs.getInt("tab", 0)
        set(value) = prefs.edit { putInt("tab", value) }

    var expandedStopwatch: Int
        get() = prefs.getInt("expanded_stopwatch", -1)
        set(value) = prefs.edit { putInt("expanded_stopwatch", value) }

    fun nextId(): Int {
        val id = prefs.getInt("next_id", 1)
        prefs.edit { putInt("next_id", id + 1) }
        return id
    }

    fun updateAlarms(change: (List<Alarm>) -> List<Alarm>) {
        _alarms.update(change)
        save()
    }

    fun updateTimers(change: (List<Timer>) -> List<Timer>) {
        _timers.update(change)
        save()
    }

    fun updateStopwatches(change: (List<Stopwatch>) -> List<Stopwatch>) {
        _stopwatches.update(change)
        save()
    }

    fun updateAlarm(id: Int, change: (Alarm) -> Alarm) = updateAlarms { list -> list.map { if (it.id == id) change(it) else it } }
    fun updateTimer(id: Int, change: (Timer) -> Timer) = updateTimers { list -> list.map { if (it.id == id) change(it) else it } }
    fun updateStopwatch(id: Int, change: (Stopwatch) -> Stopwatch) =
        updateStopwatches { list -> list.map { if (it.id == id) change(it) else it } }

    fun addRing(ring: Ring) = _rings.update { rings -> if (rings.any { it.key == ring.key }) rings else rings + ring }
    fun removeRing(key: String) = _rings.update { rings -> rings.filterNot { it.key == key } }

    private fun save() {
        prefs.edit {
            putString("alarms", JSONArray(_alarms.value.map(::alarmJson)).toString())
            putString("timers", JSONArray(_timers.value.map(::timerJson)).toString())
            putString("stopwatches", JSONArray(_stopwatches.value.map(::stopwatchJson)).toString())
            putInt("boot_count", bootCount())
            putLong("boot_wall", bootWall())
        }
    }

    /**
     * Timers and stopwatches count in elapsedRealtime, which starts again at
     * zero on every boot. After a reboot, shift them by how far the boot
     * moment moved on the wall clock, so a stopwatch keeps the time it had.
     */
    private fun rebaseAfterReboot() {
        if (!prefs.contains("boot_count") || prefs.getInt("boot_count", -1) == bootCount()) return
        val shift = prefs.getLong("boot_wall", bootWall()) - bootWall()
        _timers.update { list -> list.map { t -> t.copy(endsAt = t.endsAt?.plus(shift)) } }
        _stopwatches.update { list -> list.map { s -> s.copy(startedAt = s.startedAt?.plus(shift)) } }
        save()
    }

    private fun bootCount(): Int = Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, 0)
    private fun bootWall(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    private fun <T> readList(key: String, parse: (JSONObject) -> T): List<T> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { parse(array.getJSONObject(it)) }
        }.getOrElse { emptyList() }
    }
}

private fun JSONObject.optLongOrNull(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null
private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null

private fun alarmJson(a: Alarm) = JSONObject().apply {
    put("id", a.id); put("hour", a.hour); put("minute", a.minute); put("days", a.days)
    put("label", a.label); put("enabled", a.enabled); put("sound", a.sound); put("soundName", a.soundName)
    put("vibrate", a.vibrate); put("ramp", a.rampSeconds); put("snoozeMin", a.snoozeMinutes)
    put("maxSnoozes", a.maxSnoozes); put("challenge", a.challenge.name); put("skip", a.skip)
    put("snoozedUntil", a.snoozedUntil); put("snoozes", a.snoozes)
}

private fun alarmFrom(o: JSONObject) = Alarm(
    id = o.getInt("id"),
    hour = o.getInt("hour"),
    minute = o.getInt("minute"),
    days = o.optInt("days"),
    label = o.optString("label"),
    enabled = o.optBoolean("enabled", true),
    sound = o.optStringOrNull("sound"),
    soundName = o.optStringOrNull("soundName"),
    vibrate = o.optBoolean("vibrate", true),
    rampSeconds = o.optInt("ramp", 30),
    snoozeMinutes = o.optInt("snoozeMin", 10),
    maxSnoozes = o.optInt("maxSnoozes", 3),
    challenge = runCatching { Challenge.valueOf(o.optString("challenge")) }.getOrDefault(Challenge.OFF),
    skip = o.optLongOrNull("skip"),
    snoozedUntil = o.optLongOrNull("snoozedUntil"),
    snoozes = o.optInt("snoozes"),
)

private fun timerJson(t: Timer) = JSONObject().apply {
    put("id", t.id); put("name", t.name); put("duration", t.duration); put("remaining", t.remaining)
    put("endsAt", t.endsAt); put("ringing", t.ringing)
}

private fun timerFrom(o: JSONObject) = Timer(
    id = o.getInt("id"),
    name = o.optString("name"),
    duration = o.getLong("duration"),
    remaining = o.optLong("remaining", o.getLong("duration")),
    endsAt = o.optLongOrNull("endsAt"),
    // A ring does not survive the process, so a timer that was ringing comes back as finished-and-counting.
    ringing = o.optBoolean("ringing"),
)

private fun stopwatchJson(s: Stopwatch) = JSONObject().apply {
    put("id", s.id); put("name", s.name); put("accumulated", s.accumulated); put("startedAt", s.startedAt)
    put("laps", JSONArray(s.laps))
}

private fun stopwatchFrom(o: JSONObject) = Stopwatch(
    id = o.getInt("id"),
    name = o.optString("name"),
    accumulated = o.optLong("accumulated"),
    startedAt = o.optLongOrNull("startedAt"),
    laps = o.optJSONArray("laps")?.let { a -> List(a.length()) { a.getLong(it) } } ?: emptyList(),
)
