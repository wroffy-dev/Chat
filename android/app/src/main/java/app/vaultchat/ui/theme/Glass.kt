package app.vaultchat.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * The glass look of the app (owner 2026-11-22: the Liquid Glass mockup, "pure app mein"). A glass panel is a
 * see-through white fill with a shine on its top half, a faint reflection at the bottom and a bright rim, over
 * the plain screen and its warm glow ([glassBackdrop]).
 *
 * The blur of real frosted glass is not drawn: Compose has no blur of what lies behind a view, and blurring
 * the screen on every frame would cost battery and smoothness (owner 2026-11-21: no lag). The fills are
 * therefore more solid than in the mockup, which also keeps text readable.
 */
@Immutable
class GlassStyle(
    val fill: Color,
    val fillStrong: Color,
    val gloss: Color,
    val glossLow: Color,
    val rimTop: Color,
    val rimBottom: Color,
    /** Shadow under floating glass; transparent in dark mode, where it would not show. */
    val shadow: Color,
    /** The warm glow behind the screen. */
    val glow: Color,
)

val LightGlass = GlassStyle(
    fill = Color.White.copy(alpha = 0.72f),
    fillStrong = Color.White.copy(alpha = 0.86f),
    gloss = Color.White.copy(alpha = 0.95f),
    glossLow = Color.White.copy(alpha = 0.55f),
    rimTop = Color.White,
    rimBottom = Color(0x1A101820),
    shadow = Color(0x33202838),
    glow = Color(0xFFFFC9A8),
)

val DarkGlass = GlassStyle(
    fill = Color.White.copy(alpha = 0.07f),
    fillStrong = Color.White.copy(alpha = 0.11f),
    gloss = Color.White.copy(alpha = 0.20f),
    glossLow = Color.White.copy(alpha = 0.08f),
    rimTop = Color.White.copy(alpha = 0.34f),
    rimBottom = Color.White.copy(alpha = 0.10f),
    shadow = Color.Transparent,
    glow = Color(0xFF6A3A20),
)

val LocalGlass = staticCompositionLocalOf { LightGlass }

/**
 * Draws a glass panel behind the content, in [shape], and clips the content (and ripples) to it. [strong]
 * is for floating panels that sit over other content (the tab bar, popups). [elevation] adds a soft shadow
 * in light mode.
 */
@Composable
fun Modifier.glass(shape: Shape = CircleShape, strong: Boolean = false, elevation: Dp = 0.dp): Modifier {
    val g = LocalGlass.current
    val shadowed = if (elevation > 0.dp && g.shadow.alpha > 0f) {
        shadow(elevation, shape, clip = false, ambientColor = g.shadow, spotColor = g.shadow)
    } else {
        this
    }
    return shadowed
        .clip(shape)
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val sheen = Brush.verticalGradient(
                0f to g.gloss.copy(alpha = g.gloss.alpha * 0.55f),
                0.46f to Color.Transparent,
                0.72f to Color.Transparent,
                1f to g.glossLow.copy(alpha = g.glossLow.alpha * 0.6f),
            )
            val rim = Brush.verticalGradient(0f to g.rimTop, 0.5f to g.rimTop.copy(alpha = g.rimTop.alpha * 0.35f), 1f to g.rimBottom)
            // Twice the visible width: half of the stroke falls outside the clip.
            val stroke = Stroke(width = 2.dp.toPx())
            val fill = if (strong) g.fillStrong else g.fill
            onDrawBehind {
                drawOutline(outline, fill)
                drawOutline(outline, sheen)
                drawOutline(outline, rim, style = stroke)
            }
        }
}

/**
 * The shine of glossy glass on a solid coloured element (buttons, avatars, my bubbles): a bright top half,
 * a soft bottom reflection and a light rim. Put it after the element's background, so it draws over the
 * colour and under the content. With [over] it draws over the content instead, for components such as
 * Material buttons that paint their own colour inside.
 */
fun Modifier.glossy(shape: Shape = CircleShape, strength: Float = 1f, over: Boolean = false): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val sheen = Brush.verticalGradient(
        0f to Color.White.copy(alpha = 0.42f * strength),
        0.48f to Color.White.copy(alpha = 0.08f * strength),
        0.52f to Color.Transparent,
        1f to Color.White.copy(alpha = 0.16f * strength),
    )
    val rim = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.7f * strength), 0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.10f * strength))
    val stroke = Stroke(width = 1.5.dp.toPx())
    onDrawWithContent {
        if (!over) {
            drawOutline(outline, sheen)
            drawOutline(outline, rim, style = stroke)
        }
        drawContent()
        if (over) {
            drawOutline(outline, sheen)
            drawOutline(outline, rim, style = stroke)
        }
    }
}

/**
 * The screen behind the glass: the plain background with one soft warm glow low on the left, as in the
 * mockup (owner 2026-11-22: no green or blue gradient). Drawn once per size, not per frame.
 */
@Composable
fun Modifier.glassBackdrop(background: Color): Modifier {
    val glow = LocalGlass.current.glow
    return drawWithCache {
        val radius = max(size.width, size.height) * 0.42f
        val centre = Offset(size.width * 0.08f, size.height * 0.66f)
        val brush = Brush.radialGradient(
            0f to glow.copy(alpha = 0.55f),
            0.55f to glow.copy(alpha = 0.18f),
            1f to Color.Transparent,
            center = centre,
            radius = radius,
        )
        onDrawBehind {
            drawRect(background)
            drawCircle(brush, radius = radius, center = centre)
        }
    }
}
