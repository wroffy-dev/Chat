package app.vaultchat.ui.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.vaultchat.R
import app.vaultchat.VaultChatApplication
import app.vaultchat.data.network.ApiResult
import app.vaultchat.data.profile.ProfileApi
import app.vaultchat.ui.components.BuiltInAvatars
import app.vaultchat.ui.components.InitialsAvatar
import app.vaultchat.ui.components.pressScale
import kotlinx.coroutines.launch

/**
 * The user's own avatar as a round button (D-32, owner 2026-11-23); tapping it opens the avatar picker. Shows the
 * initial until the server has answered.
 */
@Composable
fun MyAvatarButton(modifier: Modifier = Modifier, size: Int = 36) {
    val api = (LocalContext.current.applicationContext as VaultChatApplication).container.profileApi
    var username by remember { mutableStateOf("") }
    var avatar by remember { mutableStateOf<String?>(null) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        (api.me() as? ApiResult.Ok)?.value?.let {
            username = it.username
            avatar = it.avatar
        }
    }
    val interaction = remember { MutableInteractionSource() }
    val label = stringResource(R.string.avatar_change)
    Box(
        modifier
            .size((size + 8).dp)
            .pressScale(interaction)
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), role = Role.Button) { picking = true }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        InitialsAvatar(username.ifEmpty { "?" }, size = size.dp, avatar = avatar)
    }
    if (picking) {
        AvatarPicker(
            current = avatar,
            name = username,
            saving = saving,
            onPick = { key ->
                saving = true
                scope.launch {
                    if (api.setAvatar(key) is ApiResult.Ok) avatar = key
                    saving = false
                    picking = false
                }
            },
            onDismiss = { picking = false },
        )
    }
}

/** A sheet of the built-in avatars (no photos), with the initial as the first choice. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvatarPicker(current: String?, name: String, saving: Boolean, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text(stringResource(R.string.avatar_title), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.avatar_body), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            val choices: List<String?> = listOf<String?>(null) + BuiltInAvatars.map { it.key }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(64.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(choices) { key ->
                    val selected = key == current
                    val interaction = remember { MutableInteractionSource() }
                    val label = if (key == null) stringResource(R.string.avatar_initial) else key
                    Surface(
                        shape = CircleShape,
                        color = colors.surface,
                        border = if (selected) BorderStroke(3.dp, colors.primary) else BorderStroke(1.dp, colors.outlineVariant),
                        modifier = Modifier
                            .aspectRatio(1f)
                            .pressScale(interaction)
                            .clip(CircleShape)
                            .clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), enabled = !saving) { onPick(key) }
                            .semantics {
                                contentDescription = label
                                this.selected = selected
                            },
                    ) {
                        Box(Modifier.padding(5.dp), contentAlignment = Alignment.Center) {
                            InitialsAvatar(name.ifEmpty { "?" }, size = 52.dp, avatar = key)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Gives a connected person a nickname (D-32): only this user sees it, and the chat shows both people a line that
 * the name was changed. Blank removes it.
 */
@Composable
fun NicknameDialog(username: String, current: String?, saving: Boolean, onSave: (String?) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(current.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.nickname_title)) },
        text = {
            Column {
                Text(stringResource(R.string.nickname_body, username), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(ProfileApi.NICKNAME_MAX) },
                    singleLine = true,
                    placeholder = { Text(username) },
                    label = { Text(stringResource(R.string.nickname_label)) },
                    supportingText = { Text("${text.length} / ${ProfileApi.NICKNAME_MAX}") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(text.trim().ifEmpty { null }) }, enabled = !saving) { Text(stringResource(R.string.nickname_save)) }
        },
        dismissButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (!current.isNullOrBlank()) {
                    TextButton(onClick = { onSave(null) }, enabled = !saving) { Text(stringResource(R.string.nickname_remove)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.nickname_cancel)) }
            }
        },
    )
}
