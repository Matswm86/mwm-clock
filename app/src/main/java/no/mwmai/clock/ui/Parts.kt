package no.mwmai.clock.ui

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * SystemClock.elapsedRealtime(), refreshed every frame while [fast] (a
 * stopwatch shows hundredths) and every quarter second otherwise.
 */
@Composable
fun rememberElapsedNow(fast: Boolean): Long {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(fast) {
        while (true) {
            if (fast) withFrameMillis { } else delay(250)
            now = SystemClock.elapsedRealtime()
        }
    }
    return now
}

/** Wall-clock time in epoch ms, refreshed on each new second. */
@Composable
fun rememberWallNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000 - System.currentTimeMillis() % 1000 + 5)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/** The sage-on-black display panel at the top of every screen. */
@Composable
fun Lcd(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Deck.Lcd)
            .border(BorderStroke(1.dp, Deck.Edge), shape)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        content = content,
    )
}

/** A panel for one alarm, timer or stopwatch in a list. */
@Composable
fun Card(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Deck.Panel)
            .border(BorderStroke(1.dp, Deck.Edge), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        content = content,
    )
}

@Composable
fun DeckButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    accent: Color = Deck.Phosphor,
    height: Dp = 56.dp,
    fontSize: TextUnit = 14.sp,
    enabled: Boolean = true,
    description: String = label,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .height(height)
            .clip(shape)
            .background(if (active) accent else Deck.Raised)
            .border(BorderStroke(1.dp, if (active) accent else Deck.Edge), shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontFamily = Mono,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            color = when {
                !enabled -> Deck.Edge
                active -> Deck.Ink
                else -> Deck.Text
            },
        )
    }
}

/** A row of mutually exclusive choices, such as the snooze length. */
@Composable
fun <T> Choice(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(Modifier.padding(top = 14.dp)) {
        Label(title)
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, text) ->
                DeckButton(
                    label = text,
                    onClick = { onSelect(value) },
                    active = value == selected,
                    modifier = Modifier.weight(1f),
                    height = 42.dp,
                    fontSize = 12.sp,
                    description = "$title $text",
                )
            }
        }
    }
}

@Composable
fun Label(text: String, color: Color = Deck.TextDim) {
    Text(text, fontFamily = Mono, fontSize = 11.sp, letterSpacing = 2.sp, color = color)
}

@Composable
fun StatusLine(text: String, color: Color = Deck.PhosphorDim) {
    Text(
        text,
        fontFamily = Mono,
        fontSize = 13.sp,
        letterSpacing = 1.sp,
        color = color,
        modifier = Modifier.padding(top = 2.dp),
    )
}

/** A phone-style number pad. [extra] is the bottom-left key ("00" for the timer). */
@Composable
fun Keypad(onDigit: (String) -> Unit, onBack: () -> Unit, extra: String?, modifier: Modifier = Modifier) {
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(extra, "0", "⌫"))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { key ->
                    when (key) {
                        null -> Box(Modifier.weight(1f))
                        "⌫" -> DeckButton("⌫", onBack, Modifier.weight(1f), height = 52.dp, fontSize = 20.sp, description = "Delete digit")
                        else -> DeckButton(key, { onDigit(key) }, Modifier.weight(1f), height = 52.dp, fontSize = 20.sp)
                    }
                }
            }
        }
    }
}
