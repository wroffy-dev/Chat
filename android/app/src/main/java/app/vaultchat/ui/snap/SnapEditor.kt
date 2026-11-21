package app.vaultchat.ui.snap

import android.graphics.Bitmap
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vaultchat.R
import kotlin.math.roundToInt

/** What the editor is doing: writing the caption or drawing with the pen. */
enum class SnapEditMode { TEXT, DRAW }

/** One pen line, in photo coordinates from 0 to 1 so it lands on the same spot at any size. */
private class PenLine(val color: Color, val points: List<Offset>)

private val PenColors = listOf(
    Color.White, Color(0xFFFFD60A), Color(0xFFFF453A), Color(0xFF30D158), Color(0xFF0A84FF), Color(0xFFBF5AF2), Color.Black,
)

// Sizes as parts of the photo's width, so the screen and the finished photo look the same.
private const val PEN_WIDTH = 0.012f
private const val CAPTION_TEXT = 0.05f
private const val CAPTION_PAD = 0.025f
private const val CAPTION_MAX_CHARS = 80

/**
 * Text and drawing on a snap photo before it is sent (owner, 2026-11-21, Snapchat style): a caption on a dark
 * band across the photo (drag it up or down) and a pen in a few colours with undo. Nothing new is sent: the
 * caption and lines are painted into the same photo. [onDone] gets the painted photo, or null when nothing
 * was added; [onCancel] drops the changes.
 */
@Composable
fun SnapEditor(photo: Bitmap, startMode: SnapEditMode, onDone: (Bitmap?) -> Unit, onCancel: () -> Unit) {
    BackHandler(onBack = onCancel)
    val density = LocalDensity.current
    val image = remember(photo) { photo.asImageBitmap() }
    var mode by remember { mutableStateOf(startMode) }
    var caption by remember { mutableStateOf("") }
    var captionY by remember { mutableStateOf(0.62f) }
    val lines = remember { mutableStateListOf<PenLine>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var penColor by remember { mutableStateOf(PenColors[0]) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    // Where the photo sits on screen (fitted, centred).
    val rect = remember(box, photo) {
        if (box.width == 0) {
            Rect.Zero
        } else {
            val s = minOf(box.width / photo.width.toFloat(), box.height / photo.height.toFloat())
            val w = photo.width * s
            val h = photo.height * s
            Rect((box.width - w) / 2, (box.height - h) / 2, (box.width + w) / 2, (box.height + h) / 2)
        }
    }
    fun toPhoto(p: Offset) = Offset(((p.x - rect.left) / rect.width).coerceIn(0f, 1f), ((p.y - rect.top) / rect.height).coerceIn(0f, 1f))
    fun toScreen(p: Offset) = Offset(rect.left + p.x * rect.width, rect.top + p.y * rect.height)

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Box(modifier = Modifier.fillMaxSize().onSizeChanged { box = it }) {
            Image(image, contentDescription = null, contentScale = ContentScale.Fit, filterQuality = FilterQuality.High, modifier = Modifier.fillMaxSize())
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(mode, penColor, rect) {
                        if (mode != SnapEditMode.DRAW) return@pointerInput
                        detectDragGestures(
                            onDragStart = { current = listOf(toPhoto(it)) },
                            onDragEnd = {
                                if (current.size > 1) lines.add(PenLine(penColor, current))
                                current = emptyList()
                            },
                            onDragCancel = { current = emptyList() },
                        ) { change, _ ->
                            change.consume()
                            current = current + toPhoto(change.position)
                        }
                    },
            ) {
                val stroke = Stroke(width = PEN_WIDTH * rect.width, cap = StrokeCap.Round, join = StrokeJoin.Round)
                fun draw(color: Color, points: List<Offset>) {
                    if (points.size < 2) return
                    val path = Path()
                    points.forEachIndexed { i, p -> toScreen(p).let { if (i == 0) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) } }
                    drawPath(path, color, style = stroke)
                }
                lines.forEach { draw(it.color, it.points) }
                draw(penColor, current)
            }
            // The caption band, across the photo's width; in text mode it takes the keyboard.
            if (rect.width > 0f && (caption.isNotEmpty() || mode == SnapEditMode.TEXT)) {
                val textSp = with(density) { (CAPTION_TEXT * rect.width).toSp() }
                val padDp = with(density) { (CAPTION_PAD * rect.width).toDp() }
                val widthDp = with(density) { rect.width.toDp() }
                val focus = remember { FocusRequester() }
                LaunchedEffect(mode) { if (mode == SnapEditMode.TEXT) runCatching { focus.requestFocus() } }
                Box(
                    modifier = Modifier
                        .offset { IntOffset(rect.left.roundToInt(), (rect.top + captionY * rect.height).roundToInt()) }
                        .width(widthDp)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .pointerInput(rect) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                captionY = (captionY + drag.y / rect.height).coerceIn(0f, 0.9f)
                            }
                        }
                        .padding(vertical = padDp, horizontal = padDp),
                    contentAlignment = Alignment.Center,
                ) {
                    val style = TextStyle(color = Color.White, fontSize = textSp, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
                    if (mode == SnapEditMode.TEXT) {
                        BasicTextField(
                            value = caption,
                            onValueChange = { caption = it.take(CAPTION_MAX_CHARS).replace('\n', ' ') },
                            textStyle = style,
                            cursorBrush = SolidColor(Color.White),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    } else {
                        Text(caption, style = style, maxLines = 3, modifier = Modifier.fillMaxWidth().clickable { mode = SnapEditMode.TEXT })
                    }
                }
            }
        }

        // Top bar: cancel on the left; text, pen, undo and done on the right.
        Row(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().safeDrawingPadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            EditorButton("✕", stringResource(R.string.action_cancel), active = false, onClick = onCancel)
            Box(Modifier.weight(1f))
            if (lines.isNotEmpty()) EditorButton("↶", stringResource(R.string.snap_undo), active = false, onClick = { lines.removeAt(lines.lastIndex) })
            EditorButton("Aa", stringResource(R.string.snap_add_text), active = mode == SnapEditMode.TEXT, onClick = { mode = SnapEditMode.TEXT })
            EditorButton("✎", stringResource(R.string.snap_draw), active = mode == SnapEditMode.DRAW, onClick = { mode = SnapEditMode.DRAW })
            EditorButton("✓", stringResource(R.string.snap_edit_done), active = true, onClick = {
                val text = caption.trim()
                onDone(if (text.isEmpty() && lines.isEmpty()) null else paint(photo, text, captionY, lines))
            })
        }
        // Pen colours down the right side while drawing.
        if (mode == SnapEditMode.DRAW) {
            Column(
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PenColors.forEach { c ->
                    Box(
                        Modifier
                            .size(if (c == penColor) 34.dp else 28.dp)
                            .clip(CircleShape)
                            .background(c)
                            .border(2.dp, if (c == Color.White) Color.Gray else Color.White, CircleShape)
                            .clickable(role = Role.Button) { penColor = c },
                    )
                }
            }
        }
    }
}

@Composable
private fun EditorButton(label: String, description: String, active: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (active) Color.White else Color.Black.copy(alpha = 0.5f))
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
    ) {
        Text(label, color = if (active) Color.Black else Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

/** Paints the caption band and the pen lines into a copy of [photo], at the photo's own size. */
private fun paint(photo: Bitmap, caption: String, captionY: Float, lines: List<PenLine>): Bitmap {
    val out = photo.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = android.graphics.Canvas(out)
    val w = out.width.toFloat()
    val h = out.height.toFloat()
    val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = PEN_WIDTH * w
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    lines.forEach { line ->
        pen.color = line.color.toArgb()
        val path = android.graphics.Path()
        line.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x * w, p.y * h) else path.lineTo(p.x * w, p.y * h) }
        canvas.drawPath(path, pen)
    }
    if (caption.isNotEmpty()) {
        val pad = CAPTION_PAD * w
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = CAPTION_TEXT * w
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        val layout = StaticLayout.Builder.obtain(caption, 0, caption.length, text, (w - 2 * pad).toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(3)
            .build()
        val top = captionY * h
        val band = Paint().apply { color = android.graphics.Color.argb(140, 0, 0, 0) }
        canvas.drawRect(0f, top, w, top + layout.height + 2 * pad, band)
        canvas.save()
        canvas.translate(pad, top + pad)
        layout.draw(canvas)
        canvas.restore()
    }
    return out
}
