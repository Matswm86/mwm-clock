package no.mwmai.clock

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.format.DateFormat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Sounds whatever is in [Store.rings]: the alarm sound on the alarm stream,
 * rising from quiet when the alarm asks for it, plus vibration. It stays in the
 * foreground while anything rings and stops itself when the list is empty.
 */
class RingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null
    private var vibrator: Vibrator? = null
    private var playingKey: String? = null
    private var soundJob: Job? = null
    private var focus: AudioFocusRequest? = null
    private var restoreVolume: Int? = null
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Store.load(this)
        scope.launch { Store.rings.collect { show(it) } }
        // Nobody answered for ten minutes: stop the noise rather than drain the battery all day.
        scope.launch {
            while (isActive) {
                delay(15_000)
                val now = System.currentTimeMillis()
                Store.rings.value.filter { now - it.since >= AUTO_SILENCE_MS }.forEach(::silence)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ring = intent?.getStringExtra(EXTRA_KEY)?.let { key -> Store.rings.value.firstOrNull { it.key == key } }
        when (intent?.action) {
            ACTION_SNOOZE -> ring?.let { Clockwork.snooze(this, it) }
            ACTION_DISMISS -> ring?.let { Clockwork.dismiss(this, it) }
            ACTION_ADD_MINUTE -> ring?.let { Clockwork.addMinute(this, it.id) }
        }
        show(Store.rings.value)
        return START_NOT_STICKY
    }

    private fun show(rings: List<Ring>) {
        val top = rings.firstOrNull()
        if (top == null) {
            // Android kills an app whose foreground-started service never
            // called startForeground, even when it stops at once.
            if (!foreground) startInForeground(Notifications.placeholder(this))
            stopSound()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val info = describe(this, top)
        val notification = Notifications.ring(this, top, info.title, info.text, info.canSnooze, info.challenge == Challenge.OFF)
        startInForeground(notification)
        if (playingKey != top.key) startSound(top, info)
    }

    private fun startInForeground(notification: android.app.Notification) {
        ServiceCompat.startForeground(
            this, Notifications.RING_ID, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
        foreground = true
    }

    private fun silence(ring: Ring) {
        val info = describe(this, ring)
        if (info.canSnooze) Clockwork.snooze(this, ring) else Clockwork.dismiss(this, ring)
        Notifications.missed(this, info.title)
    }

    private fun startSound(ring: Ring, info: RingInfo) {
        stopSound()
        playingKey = ring.key
        val audio = getSystemService(AudioManager::class.java)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        // An alarm with the alarm volume at zero is no alarm at all. Lift it to
        // a third for the ring and put it back afterwards.
        if (audio != null && audio.getStreamVolume(AudioManager.STREAM_ALARM) == 0) {
            runCatching {
                audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM) / 3, 0)
                restoreVolume = 0
            }
        }
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs).build()
            .also { req -> audio?.requestAudioFocus(req) }

        player = listOfNotNull(
            info.sound?.let(Uri::parse),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
        ).firstNotNullOfOrNull { uri ->
            runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(attrs)
                    setDataSource(this@RingService, uri)
                    isLooping = true
                    prepare()
                }
            }.getOrNull()
        }
        val rampMs = info.rampSeconds * 1000L
        soundJob = scope.launch {
            val p = player
            if (p != null) {
                val v = rampVolume(0, rampMs)
                p.setVolume(v, v)
                p.start()
                val started = System.currentTimeMillis()
                while (isActive && System.currentTimeMillis() - started < rampMs + 500) {
                    delay(250)
                    val level = rampVolume(System.currentTimeMillis() - started, rampMs)
                    runCatching { p.setVolume(level, level) }
                }
            } else {
                // No playable ringtone (none installed, or storage still locked
                // after a reboot): beep on the alarm stream instead.
                tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 100) }.getOrNull()
                while (isActive) {
                    tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600)
                    delay(1200)
                }
            }
        }
        if (info.vibrate) {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Vibrator::class.java)
            }
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0), attrs)
        }
    }

    private fun stopSound() {
        playingKey = null
        soundJob?.cancel()
        soundJob = null
        player?.runCatching { stop(); release() }
        player = null
        tone?.release()
        tone = null
        vibrator?.cancel()
        vibrator = null
        val audio = getSystemService(AudioManager::class.java)
        focus?.let { audio?.abandonAudioFocusRequest(it) }
        focus = null
        restoreVolume?.let { v -> runCatching { audio?.setStreamVolume(AudioManager.STREAM_ALARM, v, 0) } }
        restoreVolume = null
    }

    override fun onDestroy() {
        stopSound()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_SNOOZE = "no.mwmai.clock.SNOOZE"
        const val ACTION_DISMISS = "no.mwmai.clock.DISMISS"
        const val ACTION_ADD_MINUTE = "no.mwmai.clock.ADD_MINUTE"
        private const val EXTRA_KEY = "ring"
        const val AUTO_SILENCE_MS = 10 * 60_000L

        fun actionIntent(context: Context, action: String, ring: Ring): PendingIntent = PendingIntent.getService(
            context,
            (action + ring.key).hashCode(),
            Intent(context, RingService::class.java).setAction(action).putExtra(EXTRA_KEY, ring.key),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun send(context: Context, action: String, ring: Ring) {
            context.startService(Intent(context, RingService::class.java).setAction(action).putExtra(EXTRA_KEY, ring.key))
        }
    }
}

/** What the ring screen and the notification say about a ring, and how it sounds. */
data class RingInfo(
    val title: String,
    val text: String,
    val time: String,
    val canSnooze: Boolean,
    val snoozeMinutes: Int,
    val challenge: Challenge,
    val sound: String?,
    val rampSeconds: Int,
    val vibrate: Boolean,
)

fun describe(context: Context, ring: Ring): RingInfo {
    val is24 = DateFormat.is24HourFormat(context)
    return when (ring.kind) {
        Ring.Kind.ALARM -> {
            val a = Store.alarms.value.firstOrNull { it.id == ring.id }
            val time = a?.let { formatClock(it.hour, it.minute, is24) + if (is24) "" else " " + amPm(it.hour) } ?: ""
            RingInfo(
                title = "Alarm $time",
                text = a?.label?.ifBlank { null } ?: "Alarm",
                time = time,
                canSnooze = a != null && canSnooze(a.maxSnoozes, a.snoozes),
                snoozeMinutes = a?.snoozeMinutes ?: 10,
                challenge = a?.challenge ?: Challenge.OFF,
                sound = a?.sound,
                rampSeconds = a?.rampSeconds ?: 0,
                vibrate = a?.vibrate ?: true,
            )
        }
        Ring.Kind.TIMER -> {
            val t = Store.timers.value.firstOrNull { it.id == ring.id }
            RingInfo(
                title = "Time's up",
                text = t?.name ?: "Timer",
                time = t?.let { formatTimer(it.duration) } ?: "",
                canSnooze = false,
                snoozeMinutes = 0,
                challenge = Challenge.OFF,
                sound = null,
                rampSeconds = 0,
                vibrate = true,
            )
        }
    }
}
