package no.mwmai.clock.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.mwmai.clock.Challenge
import no.mwmai.clock.Ring
import no.mwmai.clock.RingInfo
import no.mwmai.clock.Store
import no.mwmai.clock.amPm
import no.mwmai.clock.formatClock
import no.mwmai.clock.formatTimer
import no.mwmai.clock.mathProblem
import java.util.Calendar

@Composable
fun RingScreen(ring: Ring, info: RingInfo, others: Int, onSnooze: () -> Unit, onDismiss: () -> Unit, onAddMinute: () -> Unit) {
    val context = LocalContext.current
    val is24 = DateFormat.is24HourFormat(context)
    // The challenge restarts for each new ring, never carries over.
    var solving by remember(ring.key) { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Deck.Ink)
            .systemBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            "MWM CLOCK",
            fontFamily = Mono,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 4.sp,
            color = Deck.TextDim,
        )
        Spacer(Modifier.height(12.dp))
        val wall = rememberWallNow()
        Lcd {
            val cal = Calendar.getInstance().apply { timeInMillis = wall }
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            Text(
                formatClock(hour, cal.get(Calendar.MINUTE), is24) + if (is24) "" else " " + amPm(hour),
                fontFamily = Mono,
                fontSize = 64.sp,
                fontWeight = FontWeight.Medium,
                color = Deck.Phosphor,
            )
            Text(
                info.title.uppercase(),
                fontFamily = Mono,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Deck.Amber,
            )
            StatusLine(info.text.uppercase(), color = Deck.Text)
            if (ring.kind == Ring.Kind.TIMER) {
                val t = Store.timers.value.firstOrNull { it.id == ring.id }
                val now = rememberElapsedNow(fast = false)
                if (t != null) StatusLine("OVER BY ${formatTimer(-t.remainingAt(now)).removePrefix("-")}")
            }
            if (others > 0) StatusLine("+ $others MORE RINGING", color = Deck.Amber)
        }

        if (solving) {
            SumChallenge(info.challenge, onSolved = onDismiss, modifier = Modifier.weight(1f))
            return@Column
        }

        Spacer(Modifier.weight(1f))
        if (info.canSnooze) {
            DeckButton(
                "SNOOZE ${info.snoozeMinutes} MIN",
                onClick = onSnooze,
                active = true,
                modifier = Modifier.fillMaxWidth(),
                height = 120.dp,
                fontSize = 22.sp,
                description = "Snooze",
            )
            Spacer(Modifier.height(12.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (ring.kind == Ring.Kind.TIMER) {
                DeckButton("+1 MIN", onAddMinute, Modifier.weight(1f), height = 72.dp, fontSize = 18.sp, description = "Add a minute")
            }
            val label = when {
                ring.kind == Ring.Kind.TIMER -> "STOP"
                info.challenge != Challenge.OFF -> "SOLVE TO DISMISS"
                else -> "DISMISS"
            }
            DeckButton(
                label,
                onClick = { if (info.challenge == Challenge.OFF) onDismiss() else solving = true },
                active = !info.canSnooze,
                accent = Deck.Amber,
                modifier = Modifier.weight(1f),
                height = 72.dp,
                fontSize = 18.sp,
                description = if (ring.kind == Ring.Kind.TIMER) "Stop" else "Dismiss",
            )
        }
    }
}

/** A run of sums to solve before the alarm goes quiet. A wrong answer brings a fresh sum. */
@Composable
private fun SumChallenge(challenge: Challenge, onSolved: () -> Unit, modifier: Modifier = Modifier) {
    var solved by remember { mutableIntStateOf(0) }
    var problem by remember { mutableStateOf(mathProblem(challenge.hard)) }
    var entry by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    Column(modifier.padding(top = 16.dp)) {
        Label("SUM ${solved + 1} OF ${challenge.problems}", color = Deck.Phosphor)
        Text(
            "${problem.text} = ${entry.ifEmpty { "?" }}",
            fontFamily = Mono,
            fontSize = 34.sp,
            fontWeight = FontWeight.Medium,
            color = Deck.Text,
            modifier = Modifier.padding(top = 8.dp),
        )
        StatusLine(if (wrong) "NOT QUITE. HERE IS A NEW ONE." else " ", color = Deck.Amber)
        Spacer(Modifier.weight(1f))
        Keypad(
            onDigit = { if (entry.length < 5) entry += it },
            onBack = { entry = entry.dropLast(1) },
            extra = null,
        )
        Spacer(Modifier.height(12.dp))
        DeckButton(
            "OK",
            onClick = {
                if (entry.toIntOrNull() == problem.answer) {
                    wrong = false
                    solved += 1
                    if (solved >= challenge.problems) onSolved() else problem = mathProblem(challenge.hard)
                } else {
                    wrong = true
                    problem = mathProblem(challenge.hard)
                }
                entry = ""
            },
            active = entry.isNotEmpty(),
            enabled = entry.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            height = 64.dp,
            description = "Check answer",
        )
    }
}
