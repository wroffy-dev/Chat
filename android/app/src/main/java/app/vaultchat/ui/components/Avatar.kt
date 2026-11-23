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

/** One built-in avatar (D-32): a drawing (an emoji, drawn by the phone's own font) on a soft circle. */
data class BuiltInAvatar(val key: String, val glyph: String, val background: Color)

/**
 * The avatars a user can pick (owner, 2026-11-23, D-32). The keys are the only thing stored on the server and must
 * match its list (backend/src/profile/service.ts AVATARS); there are no uploaded pictures.
 */
val BuiltInAvatars: List<BuiltInAvatar> = listOf(
    BuiltInAvatar("fox", "\uD83E\uDD8A", Color(0xFFFFE3D3)),
    BuiltInAvatar("panda", "\uD83D\uDC3C", Color(0xFFE6ECEA)),
    BuiltInAvatar("koala", "\uD83D\uDC28", Color(0xFFE5E8EF)),
    BuiltInAvatar("lion", "\uD83E\uDD81", Color(0xFFFFEFC9)),
    BuiltInAvatar("tiger", "\uD83D\uDC2F", Color(0xFFFFE6C7)),
    BuiltInAvatar("bear", "\uD83D\uDC3B", Color(0xFFF1E2D6)),
    BuiltInAvatar("bunny", "\uD83D\uDC30", Color(0xFFFCE4EC)),
    BuiltInAvatar("cat", "\uD83D\uDC31", Color(0xFFFFF1D6)),
    BuiltInAvatar("dog", "\uD83D\uDC36", Color(0xFFF3E5D8)),
    BuiltInAvatar("frog", "\uD83D\uDC38", Color(0xFFDFF5DF)),
    BuiltInAvatar("monkey", "\uD83D\uDC35", Color(0xFFF2E3D5)),
    BuiltInAvatar("penguin", "\uD83D\uDC27", Color(0xFFE1ECF7)),
    BuiltInAvatar("owl", "\uD83E\uDD89", Color(0xFFEEE3D6)),
    BuiltInAvatar("unicorn", "\uD83E\uDD84", Color(0xFFF3E5F5)),
    BuiltInAvatar("octopus", "\uD83D\uDC19", Color(0xFFFFDDE4)),
    BuiltInAvatar("whale", "\uD83D\uDC33", Color(0xFFDDEFFB)),
    BuiltInAvatar("turtle", "\uD83D\uDC22", Color(0xFFE3F2DF)),
    BuiltInAvatar("bee", "\uD83D\uDC1D", Color(0xFFFFF5C2)),
    BuiltInAvatar("butterfly", "\uD83E\uDD8B", Color(0xFFE3F0FF)),
    BuiltInAvatar("flower", "\uD83C\uDF38", Color(0xFFFDE2EC)),
    BuiltInAvatar("sun", "\u2600\uFE0F", Color(0xFFFFF3C4)),
    BuiltInAvatar("moon", "\uD83C\uDF19", Color(0xFFE8E6F8)),
    BuiltInAvatar("star", "\u2B50", Color(0xFFFFF4CC)),
    BuiltInAvatar("heart", "\uD83D\uDC9A", Color(0xFFDAF1DE)),
)

private val AvatarsByKey = BuiltInAvatars.associateBy { it.key }

/** The built-in avatar for [key], or null for none or one this app version does not know. */
fun builtInAvatar(key: String?): BuiltInAvatar? = key?.let { AvatarsByKey[it] }

/**
 * A person's avatar: their built-in avatar (D-32) when they picked one, else their initial in a coloured circle (the
 * colour comes from the username, so it stays the same everywhere). There are no photos. Decorative for screen readers.
 */
@Composable
fun InitialsAvatar(name: String, modifier: Modifier = Modifier, size: Dp = 44.dp, highlighted: Boolean = false, avatar: String? = null) {
    val picked = builtInAvatar(avatar)
    val initial = name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
    // A ring in the accent colour marks something new from this person (a waiting snap), like a story ring.
    val color = picked?.background ?: AvatarColors[Math.floorMod(name.hashCode(), AvatarColors.size)]
    val ring = if (highlighted) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape).padding(3.dp) else Modifier
    Box(
        modifier = modifier.size(size).then(ring).clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(color, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (picked != null) {
                Text(picked.glyph, fontSize = (size.value * 0.5f).sp)
            } else {
                Text(initial, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.38f).sp)
            }
        }
    }
}
