package app.vaultchat.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Flat Material 3 surfaces (owner 2026-11-22: "liquid glass remove krdo, just google msg jaisa look", light app).
 * Panels are plain tonal fills from the colour scheme, with no rims, shine or grain. The camera, snap viewer and
 * call screens stay dark around their pictures and provide [DarkPanel].
 */
@Immutable
class PanelStyle(
    val fill: Color,
    /** Floating panels over other content (menus, the ↓ button): a step stronger, with a shadow. */
    val fillStrong: Color,
    val track: Color,
    val selected: Color,
    val ground: Color,
)

/** Panels on the dark media screens (camera, snap viewer, calls). */
val DarkPanel = PanelStyle(
    fill = Color.White.copy(alpha = 0.12f),
    fillStrong = Color(0xFF163832),
    track = Color.White.copy(alpha = 0.10f),
    selected = Color.White.copy(alpha = 0.22f),
    ground = Color(0xFF051F20),
)

/** Null means "use the light colour scheme". */
val LocalPanel = staticCompositionLocalOf<PanelStyle?> { null }

@Composable
private fun panelStyle(): PanelStyle = LocalPanel.current ?: MaterialTheme.colorScheme.let {
    PanelStyle(
        fill = it.surfaceContainerHigh,
        fillStrong = it.surfaceContainerLowest,
        track = it.surfaceContainerHigh,
        selected = it.secondaryContainer,
        ground = it.background,
    )
}

/** A tonal panel in [shape] behind the content, clipping it (and ripples). [strong] floats over content. */
@Composable
fun Modifier.panel(shape: Shape = CircleShape, strong: Boolean = false, elevation: Dp = 0.dp): Modifier {
    val p = panelStyle()
    val lifted = strong || elevation > 0.dp
    val shadowed = if (lifted) shadow(if (elevation > 0.dp) elevation else 3.dp, shape, clip = false) else this
    return shadowed.clip(shape).background(if (strong) p.fillStrong else p.fill)
}

/** The track behind a segmented switch or the tab bar's choices. */
@Composable
fun Modifier.panelTrack(shape: Shape = CircleShape): Modifier = clip(shape).background(panelStyle().track)

/** The chosen segment or tab: Material's secondary container, as in a selected chip or navigation item. */
@Composable
fun Modifier.selectedPill(shape: Shape = CircleShape): Modifier = clip(shape).background(panelStyle().selected)

/** The plain screen behind everything. */
@Composable
fun Modifier.screenBackground(): Modifier = background(panelStyle().ground)
