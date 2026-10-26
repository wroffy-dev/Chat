package app.vaultchat.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import android.os.Build
import androidx.compose.ui.platform.LocalContext
import app.vaultchat.VaultChatApplication
import app.vaultchat.domain.permissions.AppPermission
import app.vaultchat.domain.permissions.PermissionStatus
import app.vaultchat.platform.push.Push
import app.vaultchat.ui.permissions.openAppSettings
import app.vaultchat.ui.permissions.rememberPermissionRequest
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.vaultchat.ui.about.AboutApp
import app.vaultchat.ui.components.PrimaryButton
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vaultchat.R
import app.vaultchat.ui.components.BannerKind
import app.vaultchat.ui.components.EmptyState
import app.vaultchat.ui.components.StatusBanner

/**
 * Home: connection, chat and call entry points (docs/15-android-ui-ux.md §3.3).
 * There is deliberately no profile screen.
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onChangePassword: () -> Unit,
    onSignedOut: () -> Unit,
    onPasswordChangeRequired: () -> Unit,
    onOpenInbox: () -> Unit,
    onCall: (connectionId: String, peerUsername: String, video: Boolean) -> Unit,
    onChat: (conversationId: String, peerUsername: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Re-check on return from pairing screens so a new connection shows immediately.
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(state.navigation) {
        when (state.navigation) {
            HomeNavigation.LOGIN -> onSignedOut()
            HomeNavigation.CHANGE_PASSWORD -> onPasswordChangeRequired()
            null -> Unit
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    state.username?.let {
                        Text(
                            stringResource(R.string.home_signed_in_as, it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = onChangePassword) { Text(stringResource(R.string.home_change_password)) }
                TextButton(onClick = viewModel::logout) { Text(stringResource(R.string.action_log_out)) }
            }
            // Incoming calls can only ring when the app is closed if notifications are allowed.
            val context = LocalContext.current
            if (Push.configured && Build.VERSION.SDK_INT >= 33) {
                val prefs = (context.applicationContext as VaultChatApplication).container.permissionPreferences
                val notifications = rememberPermissionRequest(AppPermission.NOTIFICATIONS, prefs)
                if (notifications.status != PermissionStatus.Granted) {
                    StatusBanner(
                        kind = BannerKind.WARNING,
                        title = stringResource(R.string.home_notifications_off),
                        actionLabel = stringResource(
                            if (notifications.status == PermissionStatus.PermanentlyDenied) R.string.action_open_settings else R.string.home_enable,
                        ),
                        onAction = {
                            if (notifications.status == PermissionStatus.PermanentlyDenied) openAppSettings(context) else notifications.request()
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            if (state.offline) {
                StatusBanner(
                    kind = BannerKind.WARNING,
                    title = stringResource(R.string.server_unreachable),
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = viewModel::refresh,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            val connection = state.connection
            if (connection != null) {
                EmptyState(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.home_connected_title, connection.peerUsername),
                    body = stringResource(R.string.home_connected_body),
                    action = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            PrimaryButton(stringResource(R.string.home_open_chat), { onChat(connection.conversationId, connection.peerUsername) })
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                PrimaryButton(
                                    stringResource(R.string.home_video_call),
                                    { onCall(connection.id, connection.peerUsername, true) },
                                    modifier = Modifier.weight(1f),
                                )
                                PrimaryButton(
                                    stringResource(R.string.home_voice_call),
                                    { onCall(connection.id, connection.peerUsername, false) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    },
                )
            } else {
                EmptyState(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.home_not_connected_title),
                    body = stringResource(R.string.home_not_connected_body),
                    action = { PrimaryButton(stringResource(R.string.home_connect), onOpenInbox) },
                )
            }
            AboutApp(modifier = Modifier.padding(16.dp))
        }
    }
}
