package app.vaultchat.ui.components

import app.vaultchat.ui.theme.MintGradient
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vaultchat.ui.theme.Radii
import app.vaultchat.ui.theme.glass
import app.vaultchat.ui.theme.glossy
import app.vaultchat.ui.theme.Sizes
import app.vaultchat.ui.theme.Spacing

/**
 * Shared building blocks of the UI upgrade (docs/ui-upgrade): one look for icon buttons, badges, section
 * headings, settings rows, loading placeholders and small status animations.
 */

/**
 * Shrinks a pressed element and lets it spring back with a small bounce when let go, as on iOS (owner
 * 2026-11-22). The scale is read in the draw phase only.
 */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource, pressed: Float = 0.94f): Modifier {
    val isPressed by interaction.collectIsPressedAsState()
    val scale = remember { androidx.compose.animation.core.Animatable(1f) }
    androidx.compose.runtime.LaunchedEffect(isPressed) {
        scale.animateTo(if (isPressed) pressed else 1f, if (isPressed) GlassMotion.press else GlassMotion.release)
    }
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

/**
 * A round glass icon button, 44 dp with a 48 dp touch area, used in headers. With a [container] colour it
 * is a solid glossy button instead (Liquid Glass, owner 2026-11-22).
 */
@Composable
fun VaultIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = Color.Unspecified,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    size: androidx.compose.ui.unit.Dp = Sizes.headerAction,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier.size(Sizes.touch),
        contentAlignment = Alignment.Center,
    ) {
        val look = if (container == Color.Unspecified) {
            Modifier.glass(CircleShape)
        } else {
            Modifier.clip(CircleShape).background(container).glossy(CircleShape)
        }
        Box(
            modifier = Modifier
                .size(size)
                .pressScale(interaction)
                .then(look)
                .clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(bounded = true), role = Role.Button, onClick = onClick)
                .semantics { this.contentDescription = contentDescription },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(Sizes.iconSm + 2.dp))
        }
    }
}

/** Unread count as a small pill; "99+" past 99. */
@Composable
fun UnreadBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    val text = if (count > 99) "99+" else count.toString()
    Box(
        modifier = modifier
            .popOnChange(count)
            .heightIn(min = 22.dp)
            .widthIn(min = 22.dp)
            .background(MintGradient, CircleShape)
            .glossy(CircleShape, strength = 0.8f)
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/** Three dots that rise and fall in turn: "typing…" without words. Decorative for screen readers. */
@Composable
fun TypingDots(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(modifier = modifier.clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val a by transition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(450, delayMillis = i * 150), RepeatMode.Reverse),
                label = "dot$i",
            )
            Box(Modifier.size(5.dp).alpha(a).background(color, CircleShape))
        }
    }
}

/** A small uppercase section heading for settings and grouped lists. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = Spacing.xl, end = Spacing.xl, top = Spacing.xxl, bottom = Spacing.sm).semantics { heading() },
    )
}

/** A rounded group of settings rows, as one glass card. */
@Composable
fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.screen).glass(RoundedCornerShape(Radii.card)),
    ) { content() }
}

/**
 * One settings row: icon, title, optional description and a trailing element (value, chevron or switch).
 * Without [onClick] it is information only.
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    description: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier.size(34.dp).background(MaterialTheme.colorScheme.background, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(Sizes.iconSm + 2.dp)) }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
    }
}

/** A thin divider inside a settings group, inset past the icon. */
@Composable
fun SettingsDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 66.dp)
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
    )
}

/** A grey placeholder chat row while the list loads, gently pulsing. */
@Composable
fun SkeletonRow(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val a by transition.animateFloat(0.45f, 0.9f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "pulse")
    val shade = MaterialTheme.colorScheme.surfaceVariant
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = Spacing.md).alpha(a).clearAndSetSemantics { },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(Sizes.avatarRow).background(shade, CircleShape))
        Spacer(Modifier.width(Spacing.md))
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Box(Modifier.width(140.dp).height(14.dp).background(shade, RoundedCornerShape(7.dp)))
            Box(Modifier.width(90.dp).height(11.dp).background(shade, RoundedCornerShape(6.dp)))
        }
    }
}

/**
 * Connection quality as three bars, from real call statistics only. [level] is 1 (poor) to 3 (good);
 * null draws nothing (no sample yet: nothing is guessed).
 */
@Composable
fun SignalBars(level: Int?, label: String, modifier: Modifier = Modifier, color: Color = Color.White) {
    if (level == null) return
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        listOf(6.dp, 9.dp, 12.dp).forEachIndexed { i, h ->
            Box(Modifier.width(3.dp).height(h).background(color.copy(alpha = if (i < level) 1f else 0.3f), RoundedCornerShape(1.dp)))
        }
    }
}
