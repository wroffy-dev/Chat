package app.vaultchat.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vaultchat.ui.theme.Motion
import app.vaultchat.ui.theme.glassTrack
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A glass switch such as All / Unread (owner 2026-11-22: "read or unread mein switch krte hai toh apple type
 * slide ho glass effect ke sath"). The pressed-in glass slides to the chosen option with a spring, stretching
 * like liquid on its way; it can also be dragged with a finger and snaps to the nearest option, with a light
 * tick each time it crosses one.
 */
@Composable
fun GlassSegmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    val slider = rememberGlassSlider(selected, labels.size)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val select by rememberUpdatedState(onSelect)
    val current by rememberUpdatedState(selected)
    val colors = MaterialTheme.colorScheme
    val rowLeft = remember { floatArrayOf(0f) }
    Box(
        modifier = modifier
            .glassTrack(shape)
            .padding(5.dp)
            .onPlaced { rowLeft[0] = it.positionInRoot().x }
            .pointerInput(labels.size) {
                var lastNearest = -1
                detectHorizontalDragGestures(
                    onDragStart = { start ->
                        lastNearest = current
                        scope.launch { slider.position.snapTo(slider.indexAt(rowLeft[0] + start.x)) }
                    },
                    onDragEnd = {
                        val target = slider.position.value.roundToInt().coerceIn(0, labels.size - 1)
                        if (target != current) {
                            select(target)
                        } else {
                            scope.launch { slider.position.animateTo(target.toFloat(), GlassMotion.slide) }
                        }
                    },
                    onDragCancel = { scope.launch { slider.position.animateTo(current.toFloat(), GlassMotion.slide) } },
                ) { change, _ ->
                    change.consume()
                    val p = slider.indexAt(rowLeft[0] + change.position.x)
                    scope.launch { slider.position.snapTo(p) }
                    val nearest = p.roundToInt()
                    if (nearest != lastNearest) {
                        lastNearest = nearest
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }
            },
    ) {
        GlassSliderIndicator(slider, shape)
        Row {
            labels.forEachIndexed { i, label ->
                val chosen = i == selected
                val tint by animateColorAsState(if (chosen) colors.onSurface else colors.onSurfaceVariant, tween(Motion.MEDIUM), label = "segmentText")
                val interaction = remember { MutableInteractionSource() }
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                    color = tint,
                    modifier = Modifier
                        .glassSegment(slider, i)
                        .semantics { this.selected = chosen }
                        .pressScale(interaction, 0.92f)
                        .clip(shape)
                        .clickable(interactionSource = interaction, indication = null, role = Role.Tab) {
                            if (!chosen) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                select(i)
                            }
                        }
                        .padding(horizontal = 22.dp, vertical = 9.dp),
                )
            }
        }
    }
}
