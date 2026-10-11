package no.mwmai.clock

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import no.mwmai.clock.ui.MwmClockTheme
import no.mwmai.clock.ui.RingScreen

/** The ring screen: shown over the lock screen, it lights the display and closes once nothing rings. */
class RingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.load(this)
        val bar = SystemBarStyle.dark(0xFF14171C.toInt())
        enableEdgeToEdge(statusBarStyle = bar, navigationBarStyle = bar)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // When the ring came in through the plain notification (the service
        // could not start from the background), start the sound now that the
        // app is on screen. If the service already runs, this is a no-op.
        if (Store.rings.value.isNotEmpty()) {
            runCatching { ContextCompat.startForegroundService(this, Intent(this, RingService::class.java)) }
        }

        setContent {
            MwmClockTheme {
                val rings by Store.rings.collectAsStateWithLifecycle()
                // Also watch the alarms and timers, so the snooze count and names stay current.
                val alarms by Store.alarms.collectAsStateWithLifecycle()
                val timers by Store.timers.collectAsStateWithLifecycle()
                val top = rings.firstOrNull()
                LaunchedEffect(top == null) { if (top == null) finish() }
                if (top != null) {
                    val info = remember(top, alarms, timers) { describe(this@RingActivity, top) }
                    RingScreen(
                        ring = top,
                        info = info,
                        others = rings.size - 1,
                        onSnooze = { Clockwork.snooze(this@RingActivity, top) },
                        onDismiss = { Clockwork.dismiss(this@RingActivity, top) },
                        onAddMinute = { Clockwork.addMinute(this@RingActivity, top.id) },
                    )
                }
            }
        }
    }
}
