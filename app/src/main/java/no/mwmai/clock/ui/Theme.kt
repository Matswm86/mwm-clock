package no.mwmai.clock.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import no.mwmai.clock.R

// The MWM faceplate: charcoal metal, a phosphor display in soft sage, and
// one warm amber for whatever needs your attention: a ring, a lap record.
object Deck {
    val Ink = Color(0xFF14171C)
    val Panel = Color(0xFF1C2027)
    val Raised = Color(0xFF262B34)
    val Edge = Color(0xFF343A45)
    val Lcd = Color(0xFF0D1512)
    val Phosphor = Color(0xFF7FD6A0)
    val PhosphorDim = Color(0xFF3F6B52)
    val Amber = Color(0xFFE8B86D)
    val Text = Color(0xFFD9DEE5)
    val TextDim = Color(0xFF8A93A0)
    val Heart = Color(0xFFE5737F)
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun mono(weight: Int) = Font(
    R.font.jetbrains_mono_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Mono = FontFamily(mono(400), mono(500), mono(700))

private val colors = darkColorScheme(
    primary = Deck.Phosphor,
    onPrimary = Deck.Ink,
    secondary = Deck.Amber,
    onSecondary = Deck.Ink,
    background = Deck.Ink,
    onBackground = Deck.Text,
    surface = Deck.Panel,
    onSurface = Deck.Text,
    surfaceVariant = Deck.Raised,
    onSurfaceVariant = Deck.TextDim,
    surfaceContainer = Deck.Panel,
    surfaceContainerHigh = Deck.Raised,
    surfaceContainerHighest = Deck.Raised,
    outline = Deck.Edge,
    outlineVariant = Deck.Edge,
    error = Deck.Heart,
)

@Composable
fun MwmClockTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
