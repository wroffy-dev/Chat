package app.vaultchat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Colourful circles with a white initial, each person keeping one colour (owner 2026-11-22: Google Messages-like
// look). Every colour is dark enough for white text.
private val AvatarColors = listOf(
    Color(0xFF3F63D8), Color(0xFFC4561B), Color(0xFF1E7F52), Color(0xFFB3261E),
    Color(0xFF7A4CC2), Color(0xFF0E7490), Color(0xFFB0306E), Color(0xFF5B6B12),
)

/**
 * A person's initial in a coloured circle. The app has no profile photos (docs/01): the colour comes from the
 * username, so it stays the same everywhere. Decorative for screen readers.
 */
@Composable
fun InitialsAvatar(name: String, modifier: Modifier = Modifier, size: Dp = 44.dp, highlighted: Boolean = false) {
    val initial = name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
    // A ring in the accent colour marks something new from this person (a waiting snap), like a story ring.
    val color = AvatarColors[Math.floorMod(name.hashCode(), AvatarColors.size)]
    val ring = if (highlighted) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape).padding(3.dp) else Modifier
    Box(
        modifier = modifier.size(size).then(ring).clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(color, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(initial, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.38f).sp)
        }
    }
}
