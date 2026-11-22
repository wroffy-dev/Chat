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
 * The glass look of the app, from the owner's dark glass mockup (2026-11-22, after reference pictures of glass
 * morphism and a glass "Inbox / Draft" switch). A glass panel is a see-through fill that is lighter at the top
 * left, a thin light border, a bright line along the top edge and a softer one along the bottom, over the dark
 * screen of [glassBackdrop].
 *
 * The blur of real frosted glass is not drawn: Compose has no blur of what lies behind a view, and blurring
 * the screen on every frame would cost battery and smoothness (owner 2026-11-21: no lag). The screen behind
 * the glass is plain, so there is little a blur would show.
 */
@Immutable
class GlassStyle(
    /** Fill at the top-left corner, in the middle and at the bottom-right corner. */
    val fillTop: Color,
    val fillMiddle: Color,
    val fillBottom: Color,
    /** Solid colour under floating glass that sits over other content (popups), so text stays readable. */
    val base: Color,
    val border: Color,
    val rimTop: Color,
    val rimBottom: Color,
    /** Shadow under floating glass; transparent where it would not show. */
    val shadow: Color,
    /** The two soft lights of the screen behind the glass. */
    val lightTop: Color,
    val lightBottom: Color,
    val ground: Color,
)

/** The app's glass: always dark (owner 2026-11-22). */
val DarkGlass = GlassStyle(
    fillTop = Color.White.copy(alpha = 0.12f),
    fillMiddle = Color.White.copy(alpha = 0.03f),
    fillBottom = Color.White.copy(alpha = 0.06f),
    base = Color(0xF0141719),
    border = Color.White.copy(alpha = 0.13f),
    rimTop = Color.White.copy(alpha = 0.30f),
    rimBottom = Color.White.copy(alpha = 0.16f),
    shadow = Color.Transparent,
    lightTop = Glass.TealLight,
    lightBottom = Glass.WarmLight,
    ground = Glass.Ground,
)

/** Light glass, for the map's buttons: the map itself stays light (owner 2026-11-02). */
val LightGlass = GlassStyle(
    fillTop = Color.White.copy(alpha = 0.92f),
    fillMiddle = Color.White.copy(alpha = 0.80f),
    fillBottom = Color.White.copy(alpha = 0.86f),
    base = Color.Transparent,
    border = Color(0x1A101820),
    rimTop = Color.White,
    rimBottom = Color(0x22101820),
    shadow = Color(0x33202838),
    lightTop = Color.Transparent,
    lightBottom = Color.Transparent,
    ground = Color.White,
)

val LocalGlass = staticCompositionLocalOf { DarkGlass }

/**
 * Draws a glass panel behind the content, in [shape], and clips the content (and ripples) to it. [strong]
 * is for floating panels that sit over other content (popups, the ↓ button). [elevation] adds a soft shadow
 * where the style has one.
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
            val fill = Brush.linearGradient(
                0f to g.fillTop,
                0.55f to g.fillMiddle,
                1f to g.fillBottom,
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            )
            // Border and edge lights in one stroke: bright along the top, soft along the bottom.
            val rim = Brush.verticalGradient(
                0f to g.rimTop,
                0.18f to g.border,
                0.82f to g.border,
                1f to g.rimBottom,
            )
            // Twice the visible width: half of the stroke falls outside the clip.
            val stroke = Stroke(width = 2.5.dp.toPx())
            onDrawBehind {
                if (strong && g.base.alpha > 0f) drawOutline(outline, g.base)
                drawOutline(outline, fill)
                drawOutline(outline, rim, style = stroke)
            }
        }
}

/**
 * A glass track holding segments, like the mockup's All / Unread switch and the tab bar: a glass capsule
 * with a stronger light line along its bottom edge.
 */
@Composable
fun Modifier.glassTrack(shape: Shape = CircleShape): Modifier {
    val g = LocalGlass.current
    return clip(shape).drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val fill = Brush.verticalGradient(0f to g.fillTop.copy(alpha = g.fillTop.alpha * 0.85f), 1f to g.fillMiddle)
        val rim = Brush.verticalGradient(
            0f to g.rimTop.copy(alpha = g.rimTop.alpha * 0.4f),
            0.3f to g.border.copy(alpha = g.border.alpha * 0.8f),
            0.75f to g.border,
            1f to g.rimTop.copy(alpha = g.rimTop.alpha * 1.1f),
        )
        val stroke = Stroke(width = 3.dp.toPx())
        onDrawBehind {
            drawOutline(outline, g.base.copy(alpha = 0.55f))
            drawOutline(outline, fill)
            drawOutline(outline, rim, style = stroke)
        }
    }
}

/**
 * The chosen segment inside a [glassTrack]: pressed into the glass, darker, with a shadow along its top
 * inside edge.
 */
fun Modifier.glassInset(shape: Shape = CircleShape): Modifier = clip(shape).drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val shade = Brush.verticalGradient(
        0f to Color.Black.copy(alpha = 0.55f),
        0.35f to Color.Black.copy(alpha = 0.28f),
        1f to Color.Black.copy(alpha = 0.24f),
    )
    val rim = Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.35f), 0.7f to Color.Transparent, 1f to Color.White.copy(alpha = 0.10f))
    val stroke = Stroke(width = 2.dp.toPx())
    onDrawBehind {
        drawOutline(outline, shade)
        drawOutline(outline, rim, style = stroke)
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
 * The screen behind the glass, as in the mockup: near-black, with a faint teal light from the top right and a
 * faint warm light from the bottom left (owner 2026-11-22: no bubbles in the background). Drawn once per size,
 * not per frame.
 */
@Composable
fun Modifier.glassBackdrop(): Modifier {
    val g = LocalGlass.current
    return drawWithCache {
        val topRadius = max(size.width, size.height) * 0.75f
        val topCentre = Offset(size.width * 0.8f, 0f)
        val top = Brush.radialGradient(0f to g.lightTop, 1f to g.lightTop.copy(alpha = 0f), center = topCentre, radius = topRadius)
        val bottomRadius = max(size.width, size.height) * 0.6f
        val bottomCentre = Offset(0f, size.height)
        val bottom = Brush.radialGradient(0f to g.lightBottom, 1f to g.lightBottom.copy(alpha = 0f), center = bottomCentre, radius = bottomRadius)
        onDrawBehind {
            drawRect(g.ground)
            drawRect(top)
            drawRect(bottom)
        }
    }
}
