package no.mwmai.clock.ui

import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import no.mwmai.clock.Alarm
import no.mwmai.clock.Challenge
import no.mwmai.clock.Clockwork
import no.mwmai.clock.DAY_LETTERS
import no.mwmai.clock.Store
import no.mwmai.clock.amPm
import no.mwmai.clock.formatClock
import no.mwmai.clock.repeatText
import no.mwmai.clock.untilText
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun AlarmsTab(pickSound: SoundPicker, onEditorOpen: (Boolean) -> Unit) {
    val context = LocalContext.current
    val is24 = DateFormat.is24HourFormat(context)
    val alarms by Store.alarms.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Alarm?>(null) }
    LaunchedEffect(editing) { onEditorOpen(editing != null) }

    editing?.let { draft ->
        AlarmEditor(
            initial = draft,
            isNew = alarms.none { it.id == draft.id },
            is24 = is24,
            pickSound = pickSound,
            onSave = { saved ->
                Clockwork.saveAlarm(context, saved)
                editing = null
                saved.nextTrigger(System.currentTimeMillis())?.let { at ->
                    Toast.makeText(context, "Rings ${untilText(at - System.currentTimeMillis()).lowercase()}", Toast.LENGTH_SHORT).show()
                }
            },
            onDelete = { Clockwork.deleteAlarm(context, draft.id); editing = null },
            onClose = { editing = null },
        )
        return
    }

    val now = rememberWallNow()
    val sorted = alarms.sortedWith(compareBy({ it.hour }, { it.minute }))
    val next = alarms.mapNotNull { a -> a.nextTrigger(now)?.let { a to it } }.minByOrNull { it.second }

    Column(Modifier.fillMaxSize()) {
        Lcd {
            val cal = Calendar.getInstance().apply { timeInMillis = now }
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    formatClock(hour, cal.get(Calendar.MINUTE), is24),
                    fontFamily = Mono,
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Medium,
                    color = Deck.Phosphor,
                )
                Text(
                    ":%02d".format(cal.get(Calendar.SECOND)) + if (is24) "" else " " + amPm(hour),
                    fontFamily = Mono,
                    fontSize = 22.sp,
                    color = Deck.PhosphorDim,
                    modifier = Modifier.padding(start = 4.dp, bottom = 12.dp),
                )
            }
            if (next == null) {
                StatusLine("NO ALARM SET")
            } else {
                val (alarm, at) = next
                val day = SimpleDateFormat("EEE", Locale.getDefault()).format(Date(at)).uppercase()
                val time = formatClock(alarm.hour, alarm.minute, is24)
                val snoozed = alarm.snoozedUntil?.let { it == at } == true
                StatusLine(
                    (if (snoozed) "SNOOZED  " else "NEXT $day $time  ") + "· " + untilText(at - now),
                    color = Deck.Amber,
                )
            }
        }
        LazyColumn(
            Modifier
                .weight(1f)
                .padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(sorted, key = { it.id }) { alarm ->
                AlarmCard(alarm, is24, now, onClick = { editing = alarm }) { on ->
                    Clockwork.setAlarmEnabled(context, alarm.id, on)
                }
            }
            if (sorted.isEmpty()) {
                item {
                    StatusLine("Tap + ALARM to set one.", color = Deck.TextDim)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        DeckButton(
            "+ ALARM",
            onClick = { editing = newAlarm() },
            modifier = Modifier.fillMaxWidth(),
            description = "Add alarm",
        )
    }
}

/** A new alarm starts at 07:00, or at the time of the most recent alarm set. */
private fun newAlarm(): Alarm {
    val last = Store.alarms.value.maxByOrNull { it.id }
    return Alarm(id = Store.nextId(), hour = last?.hour ?: 7, minute = last?.minute ?: 0)
}

@Composable
private fun AlarmCard(alarm: Alarm, is24: Boolean, now: Long, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    val on = alarm.enabled
    Card(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        formatClock(alarm.hour, alarm.minute, is24),
                        fontFamily = Mono,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (on) Deck.Phosphor else Deck.PhosphorDim,
                    )
                    if (!is24) {
                        Text(
                            amPm(alarm.hour),
                            fontFamily = Mono,
                            fontSize = 14.sp,
                            color = Deck.PhosphorDim,
                            modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
                        )
                    }
                }
                Row {
                    DAY_LETTERS.forEachIndexed { i, letter ->
                        val set = (alarm.days and (1 shl i)) != 0
                        Text(
                            letter,
                            fontFamily = Mono,
                            fontSize = 13.sp,
                            fontWeight = if (set) FontWeight.Bold else FontWeight.Normal,
                            color = if (set && on) Deck.Amber else Deck.Edge,
                            modifier = Modifier.width(16.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        listOf(alarm.label.uppercase(), if (alarm.repeats) "" else "ONCE").filter { it.isNotBlank() }.joinToString(" · "),
                        fontFamily = Mono,
                        fontSize = 13.sp,
                        color = Deck.TextDim,
                        maxLines = 1,
                    )
                }
            }
            DeckButton(
                if (on) "ON" else "OFF",
                onClick = { onToggle(!on) },
                active = on,
                modifier = Modifier.width(76.dp),
                height = 48.dp,
                description = "Alarm ${formatClock(alarm.hour, alarm.minute, true)} ${if (on) "on" else "off"}",
            )
        }
        val notes = buildList {
            if (on && alarm.snoozedUntil != null && alarm.snoozedUntil > now) {
                add("SNOOZED ${untilText(alarm.snoozedUntil - now)}")
            }
            if (on && alarm.skip != null) {
                add("SKIPPING " + SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(alarm.skip)).uppercase())
            }
            if (alarm.challenge != Challenge.OFF) add("MATH ${alarm.challenge.label}")
        }
        if (notes.isNotEmpty()) StatusLine(notes.joinToString("  ·  "), color = Deck.Amber)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmEditor(
    initial: Alarm,
    isNew: Boolean,
    is24: Boolean,
    pickSound: SoundPicker,
    onSave: (Alarm) -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var draft by remember { mutableStateOf(initial) }
    val time = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = is24)
    BackHandler(onBack = onClose)

    Column(Modifier.fillMaxSize()) {
        Label(if (isNew) "NEW ALARM" else "EDIT ALARM", color = Deck.Phosphor)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                TimePicker(
                    state = time,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = Deck.Panel,
                        clockDialSelectedContentColor = Deck.Ink,
                        clockDialUnselectedContentColor = Deck.Text,
                        selectorColor = Deck.Phosphor,
                        containerColor = Deck.Ink,
                        periodSelectorBorderColor = Deck.Edge,
                        periodSelectorSelectedContainerColor = Deck.Phosphor,
                        periodSelectorUnselectedContainerColor = Deck.Raised,
                        periodSelectorSelectedContentColor = Deck.Ink,
                        periodSelectorUnselectedContentColor = Deck.Text,
                        timeSelectorSelectedContainerColor = Deck.Lcd,
                        timeSelectorUnselectedContainerColor = Deck.Raised,
                        timeSelectorSelectedContentColor = Deck.Phosphor,
                        timeSelectorUnselectedContentColor = Deck.Text,
                    ),
                )
            }

            Label("REPEAT  ·  ${repeatText(draft.days)}")
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val names = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
                DAY_LETTERS.forEachIndexed { i, letter ->
                    val bit = 1 shl i
                    DeckButton(
                        letter,
                        onClick = { draft = draft.copy(days = draft.days xor bit, skip = null) },
                        active = (draft.days and bit) != 0,
                        accent = Deck.Amber,
                        modifier = Modifier.weight(1f),
                        height = 44.dp,
                        description = names[i],
                    )
                }
            }

            OutlinedTextField(
                value = draft.label,
                onValueChange = { draft = draft.copy(label = it.take(40)) },
                label = { Text("Label", fontFamily = Mono) },
                singleLine = true,
                textStyle = TextStyle(fontFamily = Mono, fontSize = 16.sp, color = Deck.Text),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Deck.Phosphor,
                    unfocusedBorderColor = Deck.Edge,
                    focusedLabelColor = Deck.Phosphor,
                    unfocusedLabelColor = Deck.TextDim,
                    cursorColor = Deck.Phosphor,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
            )

            Column(Modifier.padding(top = 14.dp)) {
                Label("SOUND")
                DeckButton(
                    (draft.soundName ?: "Default alarm sound").uppercase(),
                    onClick = { pickSound(draft.sound) { uri, name -> draft = draft.copy(sound = uri, soundName = name) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    height = 48.dp,
                    fontSize = 12.sp,
                    description = "Choose sound",
                )
            }
            Choice("GETS LOUDER OVER", listOf(0 to "OFF", 30 to "30 S", 60 to "1 M", 180 to "3 M"), draft.rampSeconds) {
                draft = draft.copy(rampSeconds = it)
            }
            Choice("VIBRATE", listOf(true to "ON", false to "OFF"), draft.vibrate) { draft = draft.copy(vibrate = it) }
            Choice("SNOOZE LENGTH", listOf(5 to "5 M", 10 to "10 M", 15 to "15 M", 20 to "20 M"), draft.snoozeMinutes) {
                draft = draft.copy(snoozeMinutes = it)
            }
            Choice("SNOOZES ALLOWED", listOf(0 to "NONE", 1 to "1", 3 to "3", -1 to "NO LIMIT"), draft.maxSnoozes) {
                draft = draft.copy(maxSnoozes = it)
            }
            Choice("TO TURN IT OFF, SOLVE", Challenge.entries.map { it to it.label }, draft.challenge) {
                draft = draft.copy(challenge = it)
            }
            if (!isNew && initial.repeats && initial.enabled) {
                DeckButton(
                    if (initial.skip != null) "UNDO SKIP" else "SKIP NEXT",
                    onClick = { Clockwork.toggleSkip(context, initial.id); onClose() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp),
                    height = 48.dp,
                )
            }
            if (!isNew) {
                DeckButton(
                    "DELETE",
                    onClick = onDelete,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    height = 48.dp,
                    description = "Delete alarm",
                )
            }
            Spacer(Modifier.height(12.dp))
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DeckButton("CANCEL", onClick = onClose, modifier = Modifier.weight(1f))
            DeckButton(
                "SAVE",
                onClick = {
                    onSave(
                        draft.copy(
                            hour = time.hour,
                            minute = time.minute,
                            // A skip only makes sense for the occurrence it was set on.
                            skip = draft.skip.takeIf { time.hour == initial.hour && time.minute == initial.minute },
                            enabled = true,
                            snoozedUntil = null,
                            snoozes = 0,
                            label = draft.label.trim(),
                        ),
                    )
                },
                active = true,
                modifier = Modifier.weight(1f),
                description = "Save alarm",
            )
        }
    }
}
