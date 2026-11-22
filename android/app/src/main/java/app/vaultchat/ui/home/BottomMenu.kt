package app.vaultchat.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ripple
import androidx.compose.runtime.remember
import app.vaultchat.ui.components.pressScale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clip
import app.vaultchat.ui.components.SlidingIndicator
import app.vaultchat.ui.components.slideSegment
import app.vaultchat.ui.components.rememberSlideState
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.vaultchat.R
import app.vaultchat.ui.components.AppIcons

/** The tabs of the bottom menu (owner, 2026-11-19): Chat (the default), Camera, Map. */
enum class BottomTab { CHAT, CAMERA, MAP }

/**
 * The tab the user last left by tapping the other tab, so the next screen's bar can open with its indicator sliding
 * over from there.
 */
private var lastTabIndex = 0

/**
 * The bottom menu as a Material 3 navigation bar (owner 2026-11-22: Google Messages-like look): Chat, a filled
 * camera button in the middle (also reached by swiping right on the chat list), and Map. The chosen tab's icon sits
 * in a pill that slides between the tabs.
 */
@Composable
fun VaultBottomBar(selected: BottomTab, onChat: () -> Unit, onCamera: () -> Unit, onMap: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val index = if (selected == BottomTab.MAP) 1 else 0
    val slider = rememberSlideState(index, count = 2, from = lastTabIndex)
    val haptics = LocalHapticFeedback.current
    fun go(to: Int, action: () -> Unit) {
        if (to == index) return
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        lastTabIndex = index
        action()
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceContainer)
            .heightIn(min = 80.dp)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        SlidingIndicator(slider, RoundedCornerShape(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab(AppIcons.Chat, stringResource(R.string.tab_chat), selected == BottomTab.CHAT, Modifier.slideSegment(slider, 0)) { go(0, onChat) }
            val interaction = remember { MutableInteractionSource() }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(56.dp)
                    .pressScale(interaction, 0.9f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.primaryContainer)
                    .clickable(
                        interactionSource = interaction,
                        indication = ripple(bounded = true),
                        onClickLabel = stringResource(R.string.tab_camera),
                        role = Role.Button,
                        onClick = onCamera,
                    ),
            ) {
                Icon(AppIcons.PhotoCamera, contentDescription = stringResource(R.string.tab_camera), tint = colors.onPrimaryContainer, modifier = Modifier.size(26.dp))
            }
            Tab(AppIcons.Map, stringResource(R.string.tab_map), selected == BottomTab.MAP, Modifier.slideSegment(slider, 1)) { go(1, onMap) }
        }
    }
}

/** A navigation item: the icon in its pill (the pill itself slides), and the label under it. */
@Composable
private fun Tab(icon: ImageVector, label: String, isSelected: Boolean, pillModifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val iconTint by animateColorAsState(if (isSelected) colors.onSecondaryContainer else colors.onSurfaceVariant, label = "tabIcon")
    val labelTint by animateColorAsState(if (isSelected) colors.onSurface else colors.onSurfaceVariant, label = "tabLabel")
    val interaction = remember { MutableInteractionSource() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .semantics { selected = isSelected }
            .clickable(interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Box(modifier = pillModifier.size(width = 64.dp, height = 32.dp).pressScale(interaction, 0.9f), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(24.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = labelTint, modifier = Modifier.padding(top = 4.dp))
    }
}
