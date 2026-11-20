package app.vaultchat.ui.snap

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale

/**
 * A snap photo that can be zoomed (owner request 2026-11-20): pinch to zoom up to 5x and drag to look
 * around; double-tap zooms in to 2.5x or back out. Drawn with high-quality filtering, so a large photo
 * scaled to the screen stays sharp instead of jagged. [onTap] receives single taps (the viewer closes on one).
 */
@Composable
fun ZoomableImage(bitmap: ImageBitmap, modifier: Modifier = Modifier, onTap: (() -> Unit)? = null) {
    var zoom by remember(bitmap) { mutableFloatStateOf(1f) }
    var pan by remember(bitmap) { mutableStateOf(Offset.Zero) }
    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.High,
        modifier = modifier
            .fillMaxSize()
            .pointerInput(bitmap) {
                detectTransformGestures { _, move, change, _ ->
                    val z = (zoom * change).coerceIn(1f, MAX_ZOOM)
                    // Never past the edges of the enlarged photo.
                    val maxX = size.width * (z - 1f) / 2f
                    val maxY = size.height * (z - 1f) / 2f
                    zoom = z
                    pan = if (z == 1f) Offset.Zero else Offset((pan.x + move.x).coerceIn(-maxX, maxX), (pan.y + move.y).coerceIn(-maxY, maxY))
                }
            }
            .pointerInput(bitmap, onTap) {
                detectTapGestures(
                    onDoubleTap = {
                        if (zoom > 1f) {
                            zoom = 1f
                            pan = Offset.Zero
                        } else {
                            zoom = DOUBLE_TAP_ZOOM
                        }
                    },
                    onTap = { onTap?.invoke() },
                )
            }
            .graphicsLayer {
                scaleX = zoom
                scaleY = zoom
                translationX = pan.x
                translationY = pan.y
            },
    )
}

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f
