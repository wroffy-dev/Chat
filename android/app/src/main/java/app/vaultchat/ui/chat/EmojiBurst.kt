package app.vaultchat.ui.chat

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.sp
import app.vaultchat.domain.chat.EmojiEffects
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

private const val DURATION_MS = 2_800
private const val PIECES = 18

/** One emoji of the effect: where it starts, how big it is, when it leaves and how it sways. */
private class Piece(val emoji: String, val x: Float, val sizeSp: Float, val delay: Float, val sway: Float, val spin: Float, val speed: Float)

/**
 * Plays [effect] once over the whole chat (owner request 2026-11-20, like Apple Messages): emoji float up
 * from the bottom (hearts, fire…) or fall from the top (confetti, stars, flowers), sway a little and fade.
 * One animation drives every piece, read only while drawing, so nothing recomposes per frame. Skipped when
 * the phone's animations are turned off. Touches pass through; screen readers ignore it.
 */
@Composable
fun EmojiBurst(effect: EmojiEffects.Effect, key: Any, onDone: () -> Unit) {
    val context = LocalContext.current
    val animationsOff = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val progress = remember(key) { Animatable(0f) }
    val pieces = remember(key) {
        val random = Random(key.hashCode())
        List(PIECES) {
            Piece(
                emoji = effect.pieces[random.nextInt(effect.pieces.size)],
                x = random.nextFloat(),
                sizeSp = 22f + random.nextFloat() * 20f,
                delay = random.nextFloat() * 0.35f,
                sway = 12f + random.nextFloat() * 28f,
                spin = (random.nextFloat() - 0.5f) * 60f,
                speed = 0.8f + random.nextFloat() * 0.4f,
            )
        }
    }
    LaunchedEffect(key) {
        if (!animationsOff) progress.animateTo(1f, tween(DURATION_MS, easing = LinearEasing))
        onDone()
    }
    if (animationsOff) return
    BoxWithConstraints(modifier = Modifier.fillMaxSize().clearAndSetSemantics { }) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        pieces.forEach { p ->
            Box(
                modifier = Modifier.graphicsLayer {
                    // This piece's own 0..1, after its start delay.
                    val t = ((progress.value - p.delay) / (1f - p.delay) * p.speed).coerceIn(0f, 1f)
                    val travel = heightPx + 120f
                    translationX = p.x * (widthPx - 60f) + sin(t * PI.toFloat() * 3f) * p.sway
                    translationY = if (effect.motion == EmojiEffects.Motion.RISE) heightPx - t * travel else -80f + t * travel
                    rotationZ = p.spin * t
                    // Grow in quickly, fade out near the end. Never a near-zero scale: text drawn through an
                    // almost-degenerate matrix is a suspect in a native crash on Android 16 (realme, v158).
                    val scale = (0.4f + t * 4f).coerceAtMost(1f)
                    scaleX = scale
                    scaleY = scale
                    alpha = if (t <= 0f || t >= 1f) 0f else (1f - ((t - 0.75f) / 0.25f).coerceIn(0f, 1f))
                },
            ) {
                Text(p.emoji, fontSize = p.sizeSp.sp)
            }
        }
    }
}
