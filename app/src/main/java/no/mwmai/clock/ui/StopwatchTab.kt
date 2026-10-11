package no.mwmai.clock.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import no.mwmai.clock.Stopwatch
import no.mwmai.clock.Store
import no.mwmai.clock.formatStopwatch
import no.mwmai.clock.lapExtremes
import no.mwmai.clock.lapTimes

@Composable
fun StopwatchTab(onEditorOpen: (Boolean) -> Unit) {
    val watches by Store.stopwatches.collectAsStateWithLifecycle()
    var expanded by remember { mutableIntStateOf(Store.expandedStopwatch) }
    var renaming by remember { mutableStateOf<Stopwatch?>(null) }
    LaunchedEffect(renaming) { onEditorOpen(renaming != null) }

    renaming?.let { s ->
        RenameScreen("RENAME STOPWATCH", s.name, onSave = { name ->
            Store.updateStopwatch(s.id) { it.copy(name = name.ifBlank { it.name }) }
            renaming = null
        }) { renaming = null }
        return
    }

    val anyRunning = watches.any { it.running }
    // Timing a race is read off the screen; keep it awake while a stopwatch runs.
    val view = LocalView.current
    DisposableEffect(anyRunning) {
        view.keepScreenOn = anyRunning
        onDispose { view.keepScreenOn = false }
    }

    val now = rememberElapsedNow(fast = anyRunning)
    val focus = watches.firstOrNull { it.id == expanded } ?: watches.firstOrNull()
    Column(Modifier.fillMaxSize()) {
        Lcd {
            Text(
                formatStopwatch(focus?.elapsedAt(now) ?: 0),
                fontFamily = Mono,
                fontSize = 56.sp,
                fontWeight = FontWeight.Medium,
                color = if (focus?.running == true) Deck.Phosphor else Deck.PhosphorDim,
                maxLines = 1,
            )
            StatusLine(
                if (focus == null) "NO STOPWATCH" else buildString {
                    append(focus.name.uppercase())
                    if (focus.laps.isNotEmpty()) {
                        append("  ·  LAP ${focus.laps.size + 1} ")
                        append(formatStopwatch(focus.elapsedAt(now) - focus.laps.last()))
                    }
                    if (watches.size > 1) append("  ·  ${watches.count { it.running }}/${watches.size} RUNNING")
                },
            )
        }
        LazyColumn(
            Modifier
                .weight(1f)
                .padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(watches, key = { it.id }) { s ->
                StopwatchCard(
                    s,
                    now,
                    open = s.id == focus?.id,
                    onOpen = {
                        expanded = s.id
                        Store.expandedStopwatch = s.id
                    },
                    onRename = { renaming = s },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DeckButton(
                "+ STOPWATCH",
                onClick = {
                    val id = Store.nextId()
                    Store.updateStopwatches { it + Stopwatch(id, "Stopwatch ${it.size + 1}") }
                    expanded = id
                    Store.expandedStopwatch = id
                },
                modifier = Modifier.weight(1f),
                description = "Add stopwatch",
            )
            if (watches.size > 1) {
                DeckButton(
                    if (anyRunning) "STOP ALL" else "START ALL",
                    onClick = {
                        val t = SystemClock.elapsedRealtime()
                        Store.updateStopwatches { list -> list.map { if (anyRunning) stop(it, t) else start(it, t) } }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun start(s: Stopwatch, now: Long) = if (s.running) s else s.copy(startedAt = now)
private fun stop(s: Stopwatch, now: Long) = if (!s.running) s else s.copy(accumulated = s.elapsedAt(now), startedAt = null)

@Composable
private fun StopwatchCard(s: Stopwatch, now: Long, open: Boolean, onOpen: () -> Unit, onRename: () -> Unit) {
    val elapsed = s.elapsedAt(now)
    Card(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                s.name.uppercase(),
                fontFamily = Mono,
                fontSize = 13.sp,
                letterSpacing = 2.sp,
                color = if (open) Deck.Phosphor else Deck.TextDim,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            DeckButton("✎", onRename, Modifier.width(40.dp), height = 32.dp, description = "Rename ${s.name}")
            Spacer(Modifier.width(6.dp))
            DeckButton(
                "×",
                { Store.updateStopwatches { list -> list.filterNot { it.id == s.id } } },
                Modifier.width(40.dp),
                height = 32.dp,
                description = "Delete ${s.name}",
            )
        }
        Text(
            formatStopwatch(elapsed),
            fontFamily = Mono,
            fontSize = 36.sp,
            fontWeight = FontWeight.Medium,
            color = if (s.running) Deck.Phosphor else Deck.PhosphorDim,
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DeckButton(
                if (s.running) "STOP" else if (elapsed > 0) "RESUME" else "START",
                onClick = {
                    val t = SystemClock.elapsedRealtime()
                    Store.updateStopwatch(s.id) { if (it.running) stop(it, t) else start(it, t) }
                },
                modifier = Modifier.weight(1f),
                active = !s.running,
                height = 46.dp,
                description = "${if (s.running) "Stop" else "Start"} ${s.name}",
            )
            if (s.running) {
                DeckButton(
                    "LAP",
                    onClick = {
                        val t = SystemClock.elapsedRealtime()
                        Store.updateStopwatch(s.id) { it.copy(laps = it.laps + it.elapsedAt(t)) }
                        onOpen()
                    },
                    modifier = Modifier.weight(1f),
                    height = 46.dp,
                    description = "Lap ${s.name}",
                )
            } else {
                DeckButton(
                    "RESET",
                    onClick = { Store.updateStopwatch(s.id) { it.copy(accumulated = 0, startedAt = null, laps = emptyList()) } },
                    modifier = Modifier.weight(1f),
                    height = 46.dp,
                    enabled = elapsed > 0,
                    description = "Reset ${s.name}",
                )
            }
        }
        if (open && s.laps.isNotEmpty()) Laps(s.laps)
    }
}

/** Newest lap first; the fastest lap in sage, the slowest in amber. */
@Composable
private fun Laps(totals: List<Long>) {
    val laps = lapTimes(totals)
    val extremes = lapExtremes(totals)
    Column(Modifier.padding(top = 10.dp)) {
        Row {
            Label("LAP", Deck.TextDim)
            Spacer(Modifier.weight(1f))
            Label("LAP TIME", Deck.TextDim)
            Spacer(Modifier.width(24.dp))
            Label("TOTAL", Deck.TextDim)
        }
        laps.indices.reversed().forEach { i ->
            val color: Color = when {
                i == extremes?.first -> Deck.Phosphor
                i == extremes?.second -> Deck.Amber
                else -> Deck.Text
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
                Text("%02d".format(i + 1), fontFamily = Mono, fontSize = 15.sp, color = color)
                Spacer(Modifier.weight(1f))
                Text(formatStopwatch(laps[i]), fontFamily = Mono, fontSize = 15.sp, color = color)
                Spacer(Modifier.width(24.dp))
                Text(formatStopwatch(totals[i]), fontFamily = Mono, fontSize = 15.sp, color = Deck.TextDim)
            }
        }
    }
}
