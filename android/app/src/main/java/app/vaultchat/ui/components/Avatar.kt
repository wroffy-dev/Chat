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
import app.vaultchat.ui.theme.VaultGreen

// Shades of the app's green palette (owner, 2026-11-19), light enough for the dark initial.
private val AvatarColors = listOf(
    VaultGreen.Mint, VaultGreen.Sage, Color(0xFFC3DCC9), Color(0xFFA9CBB3),
    Color(0xFFB8D8C0), Color(0xFFE8F5EA), Color(0xFF9FC2AA), Color(0xFFCFE6D4),
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
        Text(initial, color = VaultGreen.Deepest, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.4f).sp)
    }
}
