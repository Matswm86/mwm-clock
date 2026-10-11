package no.mwmai.clock

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import no.mwmai.clock.ui.ClockApp
import no.mwmai.clock.ui.MwmClockTheme
import no.mwmai.clock.ui.Warning

class MainActivity : ComponentActivity() {
    private val warnings = mutableStateOf<List<Warning>>(emptyList())
    private var onSoundPicked: ((String?, String?) -> Unit)? = null

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshWarnings()
        Clockwork.sync(this)
    }

    private val soundPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data ?: return@registerForActivityResult
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val uri = IntentCompat.getParcelableExtra(data, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        // The "Default" entry comes back as the settings alias; store it as null so it follows the system choice.
        val chosen = uri?.takeUnless { it == Settings.System.DEFAULT_ALARM_ALERT_URI }
        val title = chosen?.let { runCatching { RingtoneManager.getRingtone(this, it)?.getTitle(this) }.getOrNull() }
        onSoundPicked?.invoke(chosen?.toString(), title)
        onSoundPicked = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bar = SystemBarStyle.dark(0xFF14171C.toInt())
        enableEdgeToEdge(statusBarStyle = bar, navigationBarStyle = bar)
        Store.load(this)
        Notifications.createChannels(this)
        if (Store.stopwatches.value.isEmpty() && Store.alarms.value.isEmpty() && Store.timers.value.isEmpty()) {
            Store.updateStopwatches { listOf(Stopwatch(Store.nextId(), "Stopwatch 1")) }
        }
        Clockwork.sync(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsAllowed()) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Something started ringing while the app is open: show the ring screen.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                Store.rings.collect { rings ->
                    if (rings.isNotEmpty()) startActivity(Intent(this@MainActivity, RingActivity::class.java))
                }
            }
        }

        setContent {
            MwmClockTheme {
                ClockApp(warnings = warnings.value, pickSound = { current, onPicked -> pickSound(current, onPicked) })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshWarnings()
    }

    private fun pickSound(current: String?, onPicked: (String?, String?) -> Unit) {
        onSoundPicked = onPicked
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, Settings.System.DEFAULT_ALARM_ALERT_URI)
            .putExtra(
                RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                current?.let(Uri::parse) ?: Settings.System.DEFAULT_ALARM_ALERT_URI,
            )
        runCatching { soundPicker.launch(intent) }.onFailure { onSoundPicked = null }
    }

    private fun notificationsAllowed(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** The three settings that can quietly stop an alarm from working, each with a tap to fix it. */
    private fun refreshWarnings() {
        val list = mutableListOf<Warning>()
        if (!notificationsAllowed()) {
            list += Warning("NOTIFICATIONS ARE OFF: ALARMS SOUND BUT SHOW NO SCREEN. TAP TO FIX.") { openAppNotificationSettings() }
        }
        val am = getSystemService(AlarmManager::class.java)
        if (am != null && !Clockwork.canExact(am) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += Warning("EXACT ALARMS ARE OFF: ALARMS MAY RING LATE. TAP TO FIX.") {
                startSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        }
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && nm != null && !nm.canUseFullScreenIntent()) {
            list += Warning("FULL-SCREEN ALARMS ARE OFF: A LOCKED PHONE SHOWS ONLY A BANNER. TAP TO FIX.") {
                startSettings(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            }
        }
        warnings.value = list
    }

    private fun openAppNotificationSettings() {
        startSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    private fun startSettings(intent: Intent) {
        runCatching { startActivity(intent) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } }
        Clockwork.sync(this)
    }
}
