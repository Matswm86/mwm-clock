package no.mwmai.clock.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import no.mwmai.clock.Clockwork
import no.mwmai.clock.KEYPAD_DIGITS
import no.mwmai.clock.Store
import no.mwmai.clock.Timer
import no.mwmai.clock.formatTimer
import no.mwmai.clock.keypadMillis
import no.mwmai.clock.keypadText

@Composable
fun TimersTab(onEditorOpen: (Boolean) -> Unit) {
    val context = LocalContext.current
    val timers by Store.timers.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Timer?>(null) }
    LaunchedEffect(adding, renaming) { onEditorOpen(adding || renaming != null) }

    if (adding) {
        TimerKeypad(
            onStart = { name, ms -> Clockwork.addTimer(context, name, ms, start = true); adding = false },
            onClose = { adding = false },
        )
        return
    }
    renaming?.let { t ->
        RenameScreen("RENAME TIMER", t.name, onSave = { Clockwork.renameTimer(context, t.id, it); renaming = null }) {
            renaming = null
        }
        return
    }

    val now = rememberElapsedNow(fast = false)
    val running = timers.filter { it.running && !it.ringing }
    Column(Modifier.fillMaxSize()) {
        Lcd {
            val soonest = running.minByOrNull { it.endsAt!! }
            Text(
                soonest?.let { formatTimer(it.remainingAt(now)) } ?: "0:00",
                fontFamily = Mono,
                fontSize = 64.sp,
                fontWeight = FontWeight.Medium,
                color = if (soonest != null) Deck.Phosphor else Deck.PhosphorDim,
            )
            val paused = timers.count { !it.running && !it.idle }
            StatusLine(
                when {
                    timers.any { it.ringing } -> "TIME'S UP"
                    soonest != null -> listOfNotNull(
                        "${running.size} RUNNING",
                        if (paused > 0) "$paused PAUSED" else null,
                        "NEXT: ${soonest.name.uppercase()}",
                    ).joinToString(" · ")
                    paused > 0 -> "$paused PAUSED"
                    else -> "NO TIMER RUNNING"
                },
                color = if (timers.any { it.ringing }) Deck.Amber else Deck.PhosphorDim,
            )
        }
        LazyColumn(
            Modifier
                .weight(1f)
                .padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(timers, key = { it.id }) { t ->
                TimerCard(t, now, onRename = { renaming = t })
            }
            if (timers.isEmpty()) {
                item { StatusLine("Tap + TIMER to add one. Several can run at once.", color = Deck.TextDim) }
            }
        }
        Spacer(Modifier.height(12.dp))
        DeckButton("+ TIMER", onClick = { adding = true }, modifier = Modifier.fillMaxWidth(), description = "Add timer")
    }
}

@Composable
private fun TimerCard(t: Timer, now: Long, onRename: () -> Unit) {
    val context = LocalContext.current
    val left = t.remainingAt(now)
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                t.name.uppercase(),
                fontFamily = Mono,
                fontSize = 13.sp,
                letterSpacing = 2.sp,
                color = Deck.TextDim,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                formatTimer(t.duration),
                fontFamily = Mono,
                fontSize = 12.sp,
                color = Deck.PhosphorDim,
                modifier = Modifier.padding(end = 10.dp),
            )
            DeckButton("✎", onRename, Modifier.width(40.dp), height = 32.dp, description = "Rename ${t.name}")
            Spacer(Modifier.width(6.dp))
            DeckButton("×", { Clockwork.deleteTimer(context, t.id) }, Modifier.width(40.dp), height = 32.dp, description = "Delete ${t.name}")
        }
        Text(
            formatTimer(left),
            fontFamily = Mono,
            fontSize = 44.sp,
            fontWeight = FontWeight.Medium,
            color = when {
                t.ringing || left < 0 -> Deck.Amber
                t.running -> Deck.Phosphor
                else -> Deck.PhosphorDim
            },
            modifier = Modifier.padding(top = 2.dp),
        )
        // How much of the timer is left, as a bar.
        val fraction = if (t.duration > 0) (left.toFloat() / t.duration).coerceIn(0f, 1f) else 0f
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Deck.Raised),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(if (t.ringing) Deck.Amber else Deck.Phosphor),
            )
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                t.ringing -> DeckButton("STOP", { Clockwork.resetTimer(context, t.id) }, Modifier.weight(1f), active = true, accent = Deck.Amber, height = 46.dp, description = "Stop ${t.name}")
                t.running -> DeckButton("PAUSE", { Clockwork.pauseTimer(context, t.id) }, Modifier.weight(1f), height = 46.dp, description = "Pause ${t.name}")
                else -> DeckButton(if (t.idle) "START" else "RESUME", { Clockwork.startTimer(context, t.id) }, Modifier.weight(1f), active = true, height = 46.dp, description = "Start ${t.name}")
            }
            DeckButton("RESET", { Clockwork.resetTimer(context, t.id) }, Modifier.weight(1f), height = 46.dp, enabled = !t.idle, description = "Reset ${t.name}")
            DeckButton("+1 MIN", { Clockwork.addMinute(context, t.id) }, Modifier.weight(1f), height = 46.dp, description = "Add a minute to ${t.name}")
        }
    }
}

private val PRESETS = listOf("100" to "1 M", "300" to "3 M", "500" to "5 M", "1000" to "10 M", "1500" to "15 M")

@Composable
private fun TimerKeypad(onStart: (String, Long) -> Unit, onClose: () -> Unit) {
    var digits by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    BackHandler(onBack = onClose)
    val ms = keypadMillis(digits)
    Column(Modifier.fillMaxSize()) {
        Label("NEW TIMER", color = Deck.Phosphor)
        Spacer(Modifier.height(8.dp))
        Lcd {
            Text(
                keypadText(digits),
                fontFamily = Mono,
                fontSize = 40.sp,
                fontWeight = FontWeight.Medium,
                color = if (digits.isEmpty()) Deck.PhosphorDim else Deck.Phosphor,
            )
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PRESETS.forEach { (d, text) ->
                DeckButton(text, { digits = d }, Modifier.weight(1f), height = 40.dp, fontSize = 12.sp, description = "Preset $text")
            }
        }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(30) },
            label = { Text("Name (optional)", fontFamily = Mono) },
            singleLine = true,
            textStyle = TextStyle(fontFamily = Mono, fontSize = 16.sp, color = Deck.Text),
            colors = fieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
        Spacer(Modifier.weight(1f))
        Keypad(
            onDigit = { key -> if (digits.isNotEmpty() || key.trim('0').isNotEmpty()) digits = (digits + key).take(KEYPAD_DIGITS) },
            onBack = { digits = digits.dropLast(1) },
            extra = "00",
            modifier = Modifier.padding(top = 12.dp),
        )
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DeckButton("CANCEL", onClose, Modifier.weight(1f))
            DeckButton("START", { onStart(name.trim(), ms) }, Modifier.weight(1f), active = ms > 0, enabled = ms > 0, description = "Start timer")
        }
    }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Deck.Phosphor,
    unfocusedBorderColor = Deck.Edge,
    focusedLabelColor = Deck.Phosphor,
    unfocusedLabelColor = Deck.TextDim,
    cursorColor = Deck.Phosphor,
)

@Composable
fun RenameScreen(title: String, current: String, onSave: (String) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize()) {
        Label(title, color = Deck.Phosphor)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(30) },
            label = { Text("Name", fontFamily = Mono) },
            singleLine = true,
            textStyle = TextStyle(fontFamily = Mono, fontSize = 16.sp, color = Deck.Text),
            colors = fieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DeckButton("CANCEL", onClose, Modifier.weight(1f))
            DeckButton("SAVE", { onSave(text.trim()) }, Modifier.weight(1f), active = true, description = "Save name")
        }
    }
}
