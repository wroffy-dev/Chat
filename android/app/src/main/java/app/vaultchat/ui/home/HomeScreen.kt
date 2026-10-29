package app.vaultchat.ui.home

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vaultchat.R
import app.vaultchat.VaultChatApplication
import app.vaultchat.domain.connection.Connection
import app.vaultchat.domain.permissions.AppPermission
import app.vaultchat.domain.permissions.PermissionStatus
import app.vaultchat.platform.push.Push
import app.vaultchat.ui.about.AboutApp
import app.vaultchat.ui.about.UpdateBanner
import app.vaultchat.ui.components.BannerKind
import app.vaultchat.ui.components.EmptyState
import app.vaultchat.ui.components.InitialsAvatar
import app.vaultchat.ui.components.PrimaryButton
import app.vaultchat.ui.components.StatusBanner
import app.vaultchat.ui.permissions.openAppSettings
import app.vaultchat.ui.permissions.rememberPermissionRequest

/**
 * Home: the chat list (D-21: one row per connected person). Calls start from a chat's header.
 * There is deliberately no profile screen, no photos and no user search: the search box only
 * filters this user's own chats by name.
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onChangePassword: () -> Unit,
    onSignedOut: () -> Unit,
    onPasswordChangeRequired: () -> Unit,
    onOpenInbox: () -> Unit,
    onChat: (connection: Connection) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val updater = (context.applicationContext as VaultChatApplication).container.appUpdater

    LifecycleResumeEffect(viewModel) {
        viewModel.start()
        // Also on return from Android's "install unknown apps" setting.
        updater.check()
        onPauseOrDispose { viewModel.stop() }
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
            Header(
                username = state.username,
                onConnect = onOpenInbox,
                onChangePassword = onChangePassword,
                onLogout = viewModel::logout,
            )
            UpdateBanner(updater, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            NotificationsBanner()
            AskBatteryExemptionOnce()
            if (state.offline) {
                StatusBanner(
                    kind = BannerKind.WARNING,
                    title = stringResource(R.string.server_unreachable),
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = viewModel::refresh,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (!state.loading && state.chats.isEmpty()) {
                EmptyState(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.home_not_connected_title),
                    body = stringResource(R.string.home_not_connected_body),
                    action = { PrimaryButton(stringResource(R.string.home_connect), onOpenInbox) },
                )
            } else {
                SearchField(state.query, viewModel::setQuery)
                FilterRow(state.filter, unread = state.chats.count { it.unreadCount > 0 }, onSelect = viewModel::setFilter)
                ChatList(state.visibleChats, onChat, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Header(username: String?, onConnect: () -> Unit, onChangePassword: () -> Unit, onLogout: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    if (about) {
        // Version and APK hash live here rather than on the main screen.
        AlertDialog(
            onDismissRequest = { about = false },
            title = { Text(stringResource(R.string.home_about)) },
            text = { AboutApp() },
            confirmButton = { TextButton(onClick = { about = false }) { Text(stringResource(R.string.action_close)) } },
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.home_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        RoundIconButton(onClick = onConnect) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.home_connect_new))
        }
        Box {
            RoundIconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.home_menu))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                username?.let {
                    DropdownMenuItem(text = { Text(stringResource(R.string.home_signed_in_as, it)) }, onClick = {}, enabled = false)
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.home_change_password)) }, onClick = { menu = false; onChangePassword() })
                DropdownMenuItem(text = { Text(stringResource(R.string.home_about)) }, onClick = { menu = false; about = true })
                DropdownMenuItem(text = { Text(stringResource(R.string.action_log_out)) }, onClick = { menu = false; onLogout() })
            }
        }
    }
}

/** A white circle holding one icon, as in the reference design's header. */
@Composable
fun RoundIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, modifier = modifier.padding(4.dp).size(44.dp)) {
        IconButton(onClick = onClick) { content() }
    }
}

/** Incoming calls can only ring when the app is closed if notifications are allowed. */
@Composable
private fun NotificationsBanner() {
    val context = LocalContext.current
    if (!Push.configured || Build.VERSION.SDK_INT < 33) return
    val prefs = (context.applicationContext as VaultChatApplication).container.permissionPreferences
    val notifications = rememberPermissionRequest(AppPermission.NOTIFICATIONS, prefs)
    if (notifications.status == PermissionStatus.Granted) return
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

/**
 * Battery optimisation (Doze, App Standby) can hold back call wake-ups and stop the app in the
 * background. Android's own "let this app run in the background" dialog is shown once, the first
 * time Home opens while the app is optimised; the app itself shows no banner for it (owner choice).
 */
// BatteryLife: Play's policy on this dialog does not apply; the app is sideloaded (docs/10 §6).
@SuppressLint("BatteryLife")
@Composable
private fun AskBatteryExemptionOnce() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        val prefs = (context.applicationContext as VaultChatApplication).container.permissionPreferences
        val power = context.getSystemService(PowerManager::class.java) ?: return@LaunchedEffect
        if (prefs.batteryExemptionAsked() || power.isIgnoringBatteryOptimizations(context.packageName)) return@LaunchedEffect
        prefs.markBatteryExemptionAsked()
        val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        // Phones without the dialog are left alone: the setting stays reachable in Android's battery settings.
        try {
            context.startActivity(ask)
        } catch (_: ActivityNotFoundException) {
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.home_search_hint)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        shape = RoundedCornerShape(28.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedBorderColor = MaterialTheme.colorScheme.surface,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun FilterRow(selected: ChatFilter, unread: Int, onSelect: (ChatFilter) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Pill(stringResource(R.string.home_filter_all), selected == ChatFilter.ALL) { onSelect(ChatFilter.ALL) }
        Pill(
            if (unread > 0) stringResource(R.string.home_filter_unread_count, unread) else stringResource(R.string.home_filter_unread),
            selected == ChatFilter.UNREAD,
        ) { onSelect(ChatFilter.UNREAD) }
    }
}

@Composable
private fun Pill(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) colors.primary else colors.surface,
        contentColor = if (selected) colors.onPrimary else colors.onSurface,
        modifier = Modifier.clickable(role = Role.Tab, onClick = onClick),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp), modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
    }
}

@Composable
private fun ChatList(chats: List<Connection>, onChat: (Connection) -> Unit, modifier: Modifier = Modifier) {
    if (chats.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.TopCenter) {
            Text(stringResource(R.string.home_no_matches), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(chats, key = { it.id }) { chat -> ChatRow(chat) { onChat(chat) } }
    }
}

@Composable
private fun ChatRow(chat: Connection, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        InitialsAvatar(chat.peerUsername)
        Column(modifier = Modifier.weight(1f)) {
            Text(chat.peerUsername, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (chat.unreadCount > 0) {
                    pluralStringResource(R.plurals.home_new_messages, chat.unreadCount, chat.unreadCount)
                } else {
                    stringResource(R.string.home_tap_to_chat)
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                fontWeight = if (chat.unreadCount > 0) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            chat.lastMessageAtEpochMs?.let {
                Text(relativeTime(it), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            if (chat.unreadCount > 0) {
                Box(
                    modifier = Modifier.defaultMinSize(minWidth = 20.dp, minHeight = 20.dp).background(colors.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (chat.unreadCount > 99) "99+" else chat.unreadCount.toString(),
                        color = colors.onPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 5.dp),
                    )
                }
            } else {
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

/** "10:42", "Yesterday", or a short date, in the phone's locale. */
@Composable
private fun relativeTime(epochMs: Long): String {
    val context = LocalContext.current
    return when {
        DateUtils.isToday(epochMs) -> DateUtils.formatDateTime(context, epochMs, DateUtils.FORMAT_SHOW_TIME)
        DateUtils.isToday(epochMs + DateUtils.DAY_IN_MILLIS) -> stringResource(R.string.home_yesterday)
        else -> DateUtils.formatDateTime(context, epochMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR)
    }
}
