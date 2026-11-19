package app.vaultchat.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
 * chat list), and Map. Chat is what the app opens on.
 */
@Composable
fun VaultBottomBar(selected: BottomTab, onChat: () -> Unit, onCamera: () -> Unit, onMap: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    // Same light green as the screen, no white strip or shadow (owner, 2026-11-19).
    Surface(color = colors.background) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab(AppIcons.Chat, stringResource(R.string.tab_chat), selected == BottomTab.CHAT, onChat)
            Surface(
                shape = CircleShape,
                color = colors.primary,
                contentColor = colors.onPrimary,
                modifier = Modifier.size(60.dp).clickable(onClickLabel = stringResource(R.string.tab_camera), role = Role.Button, onClick = onCamera),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(AppIcons.PhotoCamera, contentDescription = stringResource(R.string.tab_camera), modifier = Modifier.size(28.dp))
                }
            }
            Tab(AppIcons.Map, stringResource(R.string.tab_map), selected == BottomTab.MAP, onMap)
        }
    }
}

@Composable
private fun Tab(icon: ImageVector, label: String, isSelected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tint = if (isSelected) colors.primary else colors.onSurfaceVariant
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(72.dp)
            .semantics { selected = isSelected }
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}
