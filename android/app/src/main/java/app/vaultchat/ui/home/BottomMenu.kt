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
import app.vaultchat.ui.theme.glass
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

/** The tabs of the bottom menu (owner, 2026-11-19): Chat (the default), Camera, Map. */
enum class BottomTab { CHAT, CAMERA, MAP }

/**
 * The bottom menu: Chat, the round Camera button in the middle (also reached by swiping right on the
 * chat list), and Map. Chat is what the app opens on. A floating glass capsule with a glossy camera
 * button (Liquid Glass, owner 2026-11-22).
 */
@Composable
fun VaultBottomBar(selected: BottomTab, onChat: () -> Unit, onCamera: () -> Unit, onMap: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp)
            .glass(RoundedCornerShape(36.dp), strong = true, elevation = 10.dp)
            .height(72.dp)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tab(AppIcons.Chat, stringResource(R.string.tab_chat), selected == BottomTab.CHAT, onChat)
        // The round camera shrinks a little under the finger (UI upgrade): quiet, tactile feedback.
        val interaction = remember { MutableInteractionSource() }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(60.dp)
                .pressScale(interaction)
                .clip(CircleShape)
                .background(colors.primary)
                .glossy(CircleShape)
                .clickable(
                    interactionSource = interaction,
                    indication = ripple(bounded = true),
                    onClickLabel = stringResource(R.string.tab_camera),
                    role = Role.Button,
                    onClick = onCamera,
                ),
        ) {
            Icon(AppIcons.PhotoCamera, contentDescription = stringResource(R.string.tab_camera), tint = colors.onPrimary, modifier = Modifier.size(28.dp))
        }
        Tab(AppIcons.Map, stringResource(R.string.tab_map), selected == BottomTab.MAP, onMap)
    }
}

@Composable
private fun Tab(icon: ImageVector, label: String, isSelected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tint = if (isSelected) colors.primary else colors.onSurfaceVariant
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .width(96.dp)
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(if (isSelected) colors.primary.copy(alpha = 0.13f) else Color.Transparent)
            .semantics { selected = isSelected }
            .clickable(role = Role.Tab, onClick = onClick),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}
