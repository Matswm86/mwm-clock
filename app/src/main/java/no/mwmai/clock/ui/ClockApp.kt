package no.mwmai.clock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.mwmai.clock.Store
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A problem that stops alarms from working properly, and how to fix it. */
data class Warning(val text: String, val fix: () -> Unit)

/** Asks Android's ringtone picker for a sound and hands back (uri, title); a null uri means the default. */
typealias SoundPicker = (current: String?, onPicked: (String?, String?) -> Unit) -> Unit

private val TABS = listOf("ALARM", "TIMER", "STOPWATCH")

@Composable
fun ClockApp(warnings: List<Warning>, pickSound: SoundPicker) {
    var tab by remember { mutableIntStateOf(Store.lastTab.coerceIn(0, TABS.lastIndex)) }
    // While a tab shows a full-screen editor, the tab bar is hidden.
    var editorOpen by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Deck.Ink)
            .systemBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Header()
        warnings.forEach { w ->
            Text(
                w.text,
                fontFamily = Mono,
                fontSize = 12.sp,
                letterSpacing = 1.sp,
                color = Deck.Amber,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = w.fix)
                    .padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> AlarmsTab(pickSound, onEditorOpen = { editorOpen = it })
                1 -> TimersTab(onEditorOpen = { editorOpen = it })
                else -> StopwatchTab(onEditorOpen = { editorOpen = it })
            }
        }
        if (!editorOpen) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TABS.forEachIndexed { i, name ->
                    DeckButton(
                        label = name,
                        onClick = { tab = i; Store.lastTab = i },
                        active = tab == i,
                        modifier = Modifier.weight(if (i == 2) 1.4f else 1f),
                        fontSize = 13.sp,
                        description = "${name.lowercase().replaceFirstChar { it.uppercase() }} tab",
                    )
                }
            }
        }
    }
}

@Composable
private fun Header() {
    val now = rememberWallNow()
    Row {
        Text(
            "MWM CLOCK",
            fontFamily = Mono,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 4.sp,
            color = Deck.TextDim,
        )
        Spacer(Modifier.weight(1f))
        Text(
            SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(now)).uppercase(),
            fontFamily = Mono,
            fontSize = 11.sp,
            letterSpacing = 2.sp,
            color = Deck.PhosphorDim,
        )
    }
}
