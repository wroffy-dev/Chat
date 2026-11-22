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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush

// The mockup's avatar (owner 2026-11-22, "must be same"): a dark grey-teal ball with a soft light on its upper
// left and a white initial, the same for everyone.
private val AvatarFill = Brush.verticalGradient(0f to Color(0xFF3B4A4C), 1f to Color(0xFF1C2526))

/**
 * A person's initial in a dark glossy ball. The app has no profile photos (docs/01). Decorative for screen
 * readers.
 */
@Composable
fun InitialsAvatar(name: String, modifier: Modifier = Modifier, size: Dp = 44.dp, highlighted: Boolean = false) {
    val initial = name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
    // A ring in the accent colour marks something new from this person (a waiting snap), like a story ring.
    val ring = if (highlighted) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape).padding(3.dp) else Modifier
    Box(
        modifier = modifier.size(size).then(ring).clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AvatarFill, CircleShape)
                .drawWithCache {
                    val light = Brush.radialGradient(
                        0f to Color.White.copy(alpha = 0.35f),
                        1f to Color.Transparent,
                        center = Offset(size.width * 0.32f, size.height * 0.26f),
                        radius = size.minDimension * 0.5f,
                    )
                    onDrawBehind { drawCircle(light) }
                }
                .border(1.dp, Brush.verticalGradient(0f to Color.White.copy(alpha = 0.35f), 0.5f to Color.Transparent), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(initial, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.38f).sp)
        }
    }
}
