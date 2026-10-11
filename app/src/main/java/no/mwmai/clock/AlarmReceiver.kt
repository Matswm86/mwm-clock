package no.mwmai.clock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives the two armed AlarmManager entries, and the system events after
 * which they must be armed again: a reboot (AlarmManager forgets everything),
 * an app update, and a change of time or time zone.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Store.load(context)
        when (intent.action) {
            Clockwork.ACTION_ALARM, Clockwork.ACTION_TIMER -> Clockwork.fire(context, intent.action)
            else -> Clockwork.sync(context)
        }
    }
}
