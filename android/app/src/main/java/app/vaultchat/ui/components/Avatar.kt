package app.vaultchat.ui.components

import androidx.compose.foundation.background
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

private val AvatarColors = listOf(
    Color(0xFFE8DEF8), Color(0xFFD7E8F7), Color(0xFFDDF0E1), Color(0xFFFBE7C6),
    Color(0xFFF8D9D9), Color(0xFFE2E2E6), Color(0xFFD9F1F1), Color(0xFFF3DFF0),
)

/**
 * A person's initial in a soft coloured circle. The app has no profile photos (docs/01): the colour
 * comes from the username, so it stays the same everywhere. Decorative for screen readers.
 */
@Composable
fun InitialsAvatar(name: String, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val initial = name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
    val color = AvatarColors[Math.floorMod(name.hashCode(), AvatarColors.size)]
    Box(
        modifier = modifier.size(size).background(color, CircleShape).clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(initial, color = Color(0xFF1C1B1F), fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.4f).sp)
    }
}
