package app.vaultchat.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.vaultchat.ui.theme.Ref
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The chat's background (owner 2026-11-22, in the spirit of a doodle wallpaper): the screen colour with a very
 * light pattern of small line drawings about chatting and love — hearts, chat bubbles, stars, a moon,
 * flowers, smileys, sparkles, a letter, music notes, a cup, a camera, a paper plane, a balloon — with mini
 * doodles in between (owner 2026-11-22: denser, smaller gaps), in the app's green. The pattern is drawn once into
 * one tile and repeated, so it costs nothing while scrolling. It is the app's own drawing, not a copy of
 * another app's wallpaper.
 */
@Composable
fun Modifier.chatWallpaper(): Modifier {
    val base = Ref.Surface
    val dark = base.luminance() < 0.4f
    val ink = Ref.Primary.copy(alpha = if (dark) 0.12f else 0.10f)
    val density = LocalDensity.current
    val brush = remember(ink, density) {
        ShaderBrush(ImageShader(doodleTile(ink, density), TileMode.Repeated, TileMode.Repeated))
    }
    return drawBehind {
        drawRect(base)
        drawRect(brush)
    }
}

private enum class Doodle {
    HEART, BUBBLE, STAR, MOON, FLOWER, SMILEY, SPARKLE, LETTER, NOTE, CUP, TWO_HEARTS, CHAT_DOTS, CAMERA, PLANE, BALLOON, SQUIGGLE,
}

/** Tiny shapes that fill the gaps between the doodles (owner 2026-11-22: fewer empty spaces). */
private enum class Mini { HEART, PLUS, SPARKLE, RING, DOT, WAVE }

/**
 * One tile of the pattern: a grid of cells, each with one doodle near its middle (jittered and turned a little)
 * and one or two mini doodles in its corners. Everything stays inside its own cell, so tiles meet without seams.
 */
private fun doodleTile(ink: Color, density: Density): ImageBitmap {
    val cell = with(density) { 48.dp.toPx() }
    val columns = 6
    val rows = 7
    val tile = ImageBitmap((cell * columns).toInt(), (cell * rows).toInt())
    val stroke = Stroke(width = with(density) { 1.5.dp.toPx() }, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val thin = Stroke(width = with(density) { 1.2.dp.toPx() }, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val random = Random(20261122)
    val kinds = Doodle.entries
    val minis = Mini.entries
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(tile), Size(tile.width.toFloat(), tile.height.toFloat())) {
        var k = 0
        for (r in 0 until rows) {
            for (c in 0 until columns) {
                val left = c * cell
                val top = r * cell
                val size = cell * (0.50f + random.nextFloat() * 0.10f)
                val room = (cell - size) * 0.35f
                val x = left + cell / 2 + (random.nextFloat() - 0.5f) * room
                val y = top + cell / 2 + (random.nextFloat() - 0.5f) * room
                val angle = (random.nextFloat() - 0.5f) * 50f
                // Every kind in turn, starting at a different one on each row, so neighbours differ.
                val kind = kinds[(k + r * 7) % kinds.size]
                k++
                translate(x, y) {
                    rotate(angle, pivot = Offset.Zero) { drawDoodle(kind, size, ink, stroke) }
                }
                // Minis in the cell's corners: always the top left, often the bottom right.
                val mini = cell * 0.15f
                val corners = buildList {
                    add(Offset(left + cell * (0.10f + random.nextFloat() * 0.06f), top + cell * (0.10f + random.nextFloat() * 0.06f)))
                    if (random.nextFloat() < 0.7f) add(Offset(left + cell * (0.84f + random.nextFloat() * 0.06f), top + cell * (0.84f + random.nextFloat() * 0.06f)))
                }
                corners.forEach { at ->
                    val m = minis[random.nextInt(minis.size)]
                    translate(at.x, at.y) {
                        rotate((random.nextFloat() - 0.5f) * 60f, pivot = Offset.Zero) { drawMini(m, mini, ink, thin) }
                    }
                }
            }
        }
    }
    return tile
}

/** Draws one mini doodle about [s] pixels across, centred on (0, 0). */
private fun DrawScope.drawMini(kind: Mini, s: Float, ink: Color, stroke: Stroke) {
    val h = s / 2
    when (kind) {
        Mini.HEART -> drawPath(heart(h), ink)
        Mini.PLUS -> {
            drawLine(ink, Offset(-h, 0f), Offset(h, 0f), stroke.width, StrokeCap.Round)
            drawLine(ink, Offset(0f, -h), Offset(0f, h), stroke.width, StrokeCap.Round)
        }
        Mini.SPARKLE -> drawPath(star(h, h * 0.3f, 4), ink)
        Mini.RING -> drawCircle(ink, radius = h * 0.75f, center = Offset.Zero, style = stroke)
        Mini.DOT -> drawCircle(ink, radius = h * 0.45f, center = Offset.Zero)
        Mini.WAVE -> {
            val path = Path().apply {
                moveTo(-h * 1.2f, 0f)
                quadraticTo(-h * 0.6f, -h * 0.8f, 0f, 0f)
                quadraticTo(h * 0.6f, h * 0.8f, h * 1.2f, 0f)
            }
            drawPath(path, ink, style = stroke)
        }
    }
}

/** Draws one doodle about [s] pixels across, centred on (0, 0). */
private fun DrawScope.drawDoodle(kind: Doodle, s: Float, ink: Color, stroke: Stroke) {
    val h = s / 2
    when (kind) {
        Doodle.HEART -> drawPath(heart(h), ink, style = stroke)
        Doodle.TWO_HEARTS -> {
            translate(-h * 0.3f, h * 0.1f) { drawPath(heart(h * 0.7f), ink, style = stroke) }
            translate(h * 0.45f, -h * 0.35f) { drawPath(heart(h * 0.45f), ink, style = stroke) }
        }
        Doodle.BUBBLE -> {
            val path = Path().apply {
                addRoundRect(androidx.compose.ui.geometry.RoundRect(-h, -h * 0.7f, h, h * 0.45f, CornerRadius(h * 0.4f)))
                moveTo(-h * 0.45f, h * 0.45f)
                lineTo(-h * 0.6f, h * 0.95f)
                lineTo(-h * 0.05f, h * 0.45f)
            }
            drawPath(path, ink, style = stroke)
            drawLine(ink, Offset(-h * 0.55f, -h * 0.25f), Offset(h * 0.55f, -h * 0.25f), stroke.width, StrokeCap.Round)
            drawLine(ink, Offset(-h * 0.55f, h * 0.05f), Offset(h * 0.2f, h * 0.05f), stroke.width, StrokeCap.Round)
        }
        Doodle.CHAT_DOTS -> {
            drawRoundRect(ink, Offset(-h, -h * 0.55f), Size(s, h * 1.1f), CornerRadius(h * 0.55f), style = stroke)
            for (i in -1..1) drawCircle(ink, radius = stroke.width * 1.1f, center = Offset(i * h * 0.45f, 0f))
        }
        Doodle.STAR -> drawPath(star(h, h * 0.45f, 5), ink, style = stroke)
        Doodle.SPARKLE -> drawPath(star(h, h * 0.22f, 4), ink, style = stroke)
        Doodle.MOON -> {
            // A crescent: an outer arc and a smaller inner one, joined at the tips.
            val path = Path().apply {
                arcTo(androidx.compose.ui.geometry.Rect(-h, -h, h, h), 60f, 240f, true)
                arcTo(androidx.compose.ui.geometry.Rect(-h * 0.35f, -h * 0.85f, h * 1.05f, h * 0.85f), 240f, -120f, false)
                close()
            }
            drawPath(path, ink, style = stroke)
        }
        Doodle.FLOWER -> {
            for (i in 0 until 5) {
                val a = (i * 72 - 90) * PI.toFloat() / 180f
                drawCircle(ink, radius = h * 0.33f, center = Offset(cos(a) * h * 0.55f, sin(a) * h * 0.55f), style = stroke)
            }
            drawCircle(ink, radius = h * 0.18f, center = Offset.Zero)
        }
        Doodle.SMILEY -> {
            drawCircle(ink, radius = h, center = Offset.Zero, style = stroke)
            drawCircle(ink, radius = stroke.width, center = Offset(-h * 0.35f, -h * 0.25f))
            drawCircle(ink, radius = stroke.width, center = Offset(h * 0.35f, -h * 0.25f))
            drawArc(ink, 20f, 140f, false, Offset(-h * 0.55f, -h * 0.4f), Size(h * 1.1f, h * 1.0f), style = stroke)
        }
        Doodle.LETTER -> {
            drawRoundRect(ink, Offset(-h, -h * 0.65f), Size(s, h * 1.3f), CornerRadius(h * 0.12f), style = stroke)
            val v = Path().apply {
                moveTo(-h, -h * 0.6f)
                lineTo(0f, h * 0.1f)
                lineTo(h, -h * 0.6f)
            }
            drawPath(v, ink, style = stroke)
        }
        Doodle.NOTE -> {
            drawCircle(ink, radius = h * 0.28f, center = Offset(-h * 0.35f, h * 0.55f), style = stroke)
            drawCircle(ink, radius = h * 0.28f, center = Offset(h * 0.6f, h * 0.35f), style = stroke)
            val beam = Path().apply {
                moveTo(-h * 0.07f, h * 0.55f)
                lineTo(-h * 0.07f, -h * 0.75f)
                lineTo(h * 0.88f, -h * 0.95f)
                lineTo(h * 0.88f, h * 0.35f)
            }
            drawPath(beam, ink, style = stroke)
        }
        Doodle.CUP -> {
            drawRoundRect(ink, Offset(-h * 0.7f, -h * 0.15f), Size(h * 1.15f, h * 1.0f), CornerRadius(h * 0.18f), style = stroke)
            drawArc(ink, -90f, 180f, false, Offset(h * 0.2f, h * 0.05f), Size(h * 0.6f, h * 0.55f), style = stroke)
            for (dx in listOf(-h * 0.35f, 0f)) {
                val steam = Path().apply {
                    moveTo(dx, -h * 0.35f)
                    quadraticTo(dx - h * 0.18f, -h * 0.6f, dx, -h * 0.8f)
                    quadraticTo(dx + h * 0.18f, -h * 1.0f, dx, -h * 1.1f)
                }
                drawPath(steam, ink, style = stroke)
            }
        }
        Doodle.CAMERA -> {
            drawRoundRect(ink, Offset(-h, -h * 0.45f), Size(s, h * 1.2f), CornerRadius(h * 0.22f), style = stroke)
            drawRoundRect(ink, Offset(-h * 0.35f, -h * 0.72f), Size(h * 0.7f, h * 0.27f), CornerRadius(h * 0.08f), style = stroke)
            drawCircle(ink, radius = h * 0.36f, center = Offset(0f, h * 0.15f), style = stroke)
        }
        Doodle.PLANE -> {
            // A paper plane, the "send" sign.
            val plane = Path().apply {
                moveTo(-h, h * 0.05f)
                lineTo(h, -h * 0.75f)
                lineTo(h * 0.25f, h * 0.75f)
                lineTo(-h * 0.05f, h * 0.15f)
                close()
            }
            drawPath(plane, ink, style = stroke)
            drawLine(ink, Offset(-h * 0.05f, h * 0.15f), Offset(h, -h * 0.75f), stroke.width, StrokeCap.Round)
        }
        Doodle.BALLOON -> {
            drawOval(ink, Offset(-h * 0.6f, -h), Size(h * 1.2f, h * 1.45f), style = stroke)
            val string = Path().apply {
                moveTo(0f, h * 0.45f)
                quadraticTo(-h * 0.25f, h * 0.7f, 0f, h * 0.85f)
                quadraticTo(h * 0.25f, h * 1.0f, 0f, h * 1.1f)
            }
            drawPath(string, ink, style = stroke)
        }
        Doodle.SQUIGGLE -> {
            val path = Path().apply {
                moveTo(-h, h * 0.2f)
                quadraticTo(-h * 0.5f, -h * 0.6f, 0f, 0f)
                quadraticTo(h * 0.5f, h * 0.6f, h, -h * 0.2f)
            }
            drawPath(path, ink, style = stroke)
            drawCircle(ink, radius = stroke.width * 1.2f, center = Offset(h * 0.6f, -h * 0.6f))
        }
    }
}

/** A heart about 2·[h] across, centred. */
private fun heart(h: Float): Path = Path().apply {
    moveTo(0f, h * 0.85f)
    cubicTo(-h * 1.25f, h * 0.05f, -h * 0.75f, -h * 1.05f, 0f, -h * 0.4f)
    cubicTo(h * 0.75f, -h * 1.05f, h * 1.25f, h * 0.05f, 0f, h * 0.85f)
    close()
}

/** A star with [points] points between radius [outer] and [inner], centred. */
private fun star(outer: Float, inner: Float, points: Int): Path = Path().apply {
    for (i in 0 until points * 2) {
        val radius = if (i % 2 == 0) outer else inner
        val a = (i * 180f / points - 90f) * PI.toFloat() / 180f
        val x = cos(a) * radius
        val y = sin(a) * radius
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}
