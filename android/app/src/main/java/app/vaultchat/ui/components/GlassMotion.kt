package app.vaultchat.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import app.vaultchat.ui.theme.glassInset
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Motion of the glass UI (owner 2026-11-22: "ultra level ke animation … jaise apple mein … slide ho glass effect
 * ke sath"). Every animation here is a spring, so it can be interrupted and feels physical, and it runs in the
 * layout or draw phase only: nothing recomposes per frame, which keeps it smooth (owner 2026-11-21: no lag).
 */
object GlassMotion {
    /** The sliding glass of a switch or tab bar: quick, with a small settle. */
    val slide = spring<Float>(dampingRatio = 0.7f, stiffness = 420f)

    /** Letting go of a pressed element: a soft bounce back. */
    val release = spring<Float>(dampingRatio = 0.45f, stiffness = 520f)

    /** Pressing down: fast and without bounce. */
    val press = spring<Float>(dampingRatio = 1f, stiffness = Spring.StiffnessHigh)

    /** Things appearing on screen. */
    val appear = spring<Float>(dampingRatio = 0.78f, stiffness = 260f)
}

/**
 * The state of a sliding glass indicator over a row of segments: its position is a fractional segment index, so
 * between two segments it sits (and stretches) in between, and a finger can drag it.
 */
@Stable
class GlassSlider internal constructor(initial: Int, count: Int) {
    val position = Animatable(initial.toFloat())
    internal val bounds = mutableStateListOf<Rect>().apply { repeat(count) { add(Rect.Zero) } }
    internal var origin by mutableStateOf(Offset.Zero)

    /** The fractional segment index under [rootX], from the segments' centres. */
    fun indexAt(rootX: Float): Float {
        val centres = bounds.map { it.center.x }
        if (centres.isEmpty()) return 0f
        if (rootX <= centres.first()) return 0f
        if (rootX >= centres.last()) return (centres.size - 1).toFloat()
        for (i in 0 until centres.size - 1) {
            val a = centres[i]
            val b = centres[i + 1]
            if (rootX in a..b) return i + if (b > a) (rootX - a) / (b - a) else 0f
        }
        return 0f
    }

    /** Where the indicator is now, relative to itself, stretched along its way by [stretch] pixels. */
    internal fun frame(stretch: Float): Rect {
        if (bounds.isEmpty()) return Rect.Zero
        val p = position.value.coerceIn(0f, (bounds.size - 1).toFloat())
        val i = floor(p).toInt()
        val j = min(i + 1, bounds.size - 1)
        val f = p - i
        val a = bounds[i]
        val b = bounds[j]
        val width = a.width + (b.width - a.width) * f + stretch
        val height = a.height + (b.height - a.height) * f
        val left = a.left + (b.left - a.left) * f - origin.x - stretch / 2f
        val top = a.top + (b.top - a.top) * f - origin.y
        return Rect(left, top, left + width, top + height)
    }
}

/**
 * A [GlassSlider] that glides to [selected] whenever it changes. [from] is where it starts, so a screen can open
 * with the indicator sliding over from the tab the user came from.
 */
@Composable
fun rememberGlassSlider(selected: Int, count: Int, from: Int = selected): GlassSlider {
    val slider = remember(count) { GlassSlider(from.coerceIn(0, count - 1), count) }
    LaunchedEffect(slider, selected) { slider.position.animateTo(selected.toFloat(), GlassMotion.slide) }
    return slider
}

/** Marks segment [index] of [slider], so the indicator knows where it is. */
fun Modifier.glassSegment(slider: GlassSlider, index: Int): Modifier = onPlaced { coordinates ->
    val rect = Rect(coordinates.positionInRoot(), coordinates.size.toSize())
    if (index in slider.bounds.indices && slider.bounds[index] != rect) slider.bounds[index] = rect
}

/**
 * The pressed-in glass that slides between the segments of [slider]. Put it first inside the box that holds the
 * segments. On its way it stretches with its speed and flattens a little, like a drop of liquid glass, then
 * settles with a small bounce.
 */
@Composable
fun GlassSliderIndicator(slider: GlassSlider, shape: Shape, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val maxStretch = with(density) { 26.dp.toPx() }
    val perSpeed = with(density) { 10.dp.toPx() }
    Box(
        modifier
            .onPlaced { slider.origin = it.positionInRoot() }
            .layout { measurable, _ ->
                val speed = abs(slider.position.velocity)
                val frame = slider.frame(min(speed * perSpeed, maxStretch))
                val width = frame.width.roundToInt().coerceAtLeast(0)
                val height = frame.height.roundToInt().coerceAtLeast(0)
                val placeable = measurable.measure(Constraints.fixed(width, height))
                layout(0, 0) { placeable.place(frame.left.roundToInt(), frame.top.roundToInt()) }
            }
            .graphicsLayer {
                // Reading the position keeps this layer in step with the slide; the speed itself is not state.
                slider.position.value
                val speed = abs(slider.position.velocity)
                scaleY = 1f - min(speed * 0.02f, 0.08f)
                transformOrigin = TransformOrigin.Center
            }
            .glassInset(shape),
    )
}

/**
 * Fades, lifts and grows an element into place when it first appears, [index] steps after the first, so a list
 * cascades in like on iOS.
 */
@Composable
fun Modifier.staggeredAppear(index: Int): Modifier {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay((index * 45L).coerceAtMost(360L))
        progress.animateTo(1f, GlassMotion.appear)
    }
    return graphicsLayer {
        val v = progress.value
        alpha = v.coerceIn(0f, 1f)
        translationY = (1f - v) * 22.dp.toPx()
        val scale = 0.95f + 0.05f * v
        scaleX = scale
        scaleY = scale
    }
}

/** A short spring "pop" each time [key] changes after the first time, for counts and badges. */
@Composable
fun Modifier.popOnChange(key: Any?): Modifier {
    val scale = remember { Animatable(1f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(key) {
        if (first) {
            first = false
        } else {
            scale.snapTo(1.28f)
            scale.animateTo(1f, GlassMotion.release)
        }
    }
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}
