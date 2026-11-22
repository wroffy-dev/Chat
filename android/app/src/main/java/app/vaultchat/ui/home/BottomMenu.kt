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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import app.vaultchat.ui.theme.Glass
import app.vaultchat.ui.components.GlassSliderIndicator
import app.vaultchat.ui.components.glassSegment
import app.vaultchat.ui.components.rememberGlassSlider
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import app.vaultchat.ui.theme.glassTrack
import androidx.compose.ui.graphics.Brush
import app.vaultchat.ui.theme.glossy
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

/** The camera button's brushed-silver face, as in the mockup (owner 2026-11-22). */
private val SilverButton = Brush.verticalGradient(0f to Color(0xFFFFFFFF), 0.55f to Color(0xFFD9DEE0), 1f to Color(0xFFBFC6C9))

/** The tabs of the bottom menu (owner, 2026-11-19): Chat (the default), Camera, Map. */
enum class BottomTab { CHAT, CAMERA, MAP }

/**
 * The bottom menu: Chat, the round Camera button in the middle (also reached by swiping right on the
 * chat list), and Map. Chat is what the app opens on. A glass track with the chosen tab pressed in and a
 * silver camera button (dark glass mockup, owner 2026-11-22).
 */
/**
 * The tab the user last left by tapping the other tab, so the next screen's bar can open with its glass sliding
 * over from there.
 */
private var lastTabIndex = 0

@Composable
fun VaultBottomBar(selected: BottomTab, onChat: () -> Unit, onCamera: () -> Unit, onMap: () -> Unit) {
    val index = if (selected == BottomTab.MAP) 1 else 0
    // The pressed-in glass slides over from the tab the user came from (owner 2026-11-22: iOS-like motion).
    val slider = rememberGlassSlider(index, count = 2, from = lastTabIndex)
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
            .padding(horizontal = 18.dp, vertical = 10.dp)
            .glassTrack(RoundedCornerShape(36.dp))
            .height(72.dp)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        GlassSliderIndicator(slider, RoundedCornerShape(28.dp))
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab(AppIcons.Chat, stringResource(R.string.tab_chat), selected == BottomTab.CHAT, Modifier.glassSegment(slider, 0)) { go(0, onChat) }
            // The silver camera shrinks under the finger and springs back.
            val interaction = remember { MutableInteractionSource() }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(60.dp)
                    .pressScale(interaction, 0.88f)
                    .clip(CircleShape)
                    .background(SilverButton)
                    .glossy(CircleShape, strength = 0.6f)
                    .clickable(
                        interactionSource = interaction,
                        indication = ripple(bounded = true),
                        onClickLabel = stringResource(R.string.tab_camera),
                        role = Role.Button,
                        onClick = onCamera,
                    ),
            ) {
                Icon(AppIcons.PhotoCamera, contentDescription = stringResource(R.string.tab_camera), tint = Glass.Ground, modifier = Modifier.size(28.dp))
            }
            Tab(AppIcons.Map, stringResource(R.string.tab_map), selected == BottomTab.MAP, Modifier.glassSegment(slider, 1)) { go(1, onMap) }
        }
    }
}

@Composable
private fun Tab(icon: ImageVector, label: String, isSelected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tint by animateColorAsState(if (isSelected) colors.onSurface else colors.onSurfaceVariant, label = "tabTint")
    val interaction = remember { MutableInteractionSource() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .width(104.dp)
            .height(56.dp)
            .pressScale(interaction, 0.9f)
            .clip(RoundedCornerShape(28.dp))
            .semantics { selected = isSelected }
            .clickable(interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}
