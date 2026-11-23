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

// Green circles with a white initial from the owner's palette (owner 2026-11-23), each person keeping one shade.
// Every shade is dark enough for white text.
private val AvatarColors = listOf(
    Color(0xFF235347), Color(0xFF163832), Color(0xFF2F6B57), Color(0xFF3E7D63),
    Color(0xFF1F4D45), Color(0xFF4A7A64), Color(0xFF0B2B26), Color(0xFF356A5A),
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
