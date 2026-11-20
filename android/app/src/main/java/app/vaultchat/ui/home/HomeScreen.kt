package app.vaultchat.ui.home

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.selected
import app.vaultchat.ui.components.SkeletonRow
import app.vaultchat.ui.components.UnreadBadge
import app.vaultchat.ui.components.VaultIconButton
import app.vaultchat.ui.theme.Motion
import app.vaultchat.ui.theme.Radii
import app.vaultchat.ui.theme.Sizes
import app.vaultchat.ui.theme.Spacing
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.width
import app.vaultchat.ui.components.AppIcons
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.border
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
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
import app.vaultchat.ui.about.LastCrashCard
import app.vaultchat.domain.permissions.PermissionStatus
import app.vaultchat.domain.permissions.AppPermission
import app.vaultchat.ui.permissions.rememberPermissionRequest
import app.vaultchat.platform.push.Push
import app.vaultchat.ui.about.UpdateBanner
import app.vaultchat.ui.components.BannerKind
import app.vaultchat.ui.components.EmptyState
import app.vaultchat.ui.components.InitialsAvatar
import app.vaultchat.ui.components.PrimaryButton
import app.vaultchat.ui.components.StatusBanner

/**
 * Home: the chat list (D-21: one row per connected person). Calls start from a chat's header.
 * There is deliberately no profile screen, no photos and no user search: the search box only
 * filters this user's own chats by name.
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onSignedOut: () -> Unit,
    onPasswordChangeRequired: () -> Unit,
    onOpenInbox: () -> Unit,
    onChat: (connection: Connection) -> Unit,
    /** Swipe right on the chat list opens the snap camera (D-27, as in Snapchat). */
    onOpenCamera: () -> Unit = {},
    /** The camera button on a chat row: a snap for that person. */
    onSnapTo: (Connection) -> Unit = {},
    /** The Map tab: connected people's latest locations (D-06 as widened 2026-11-19). */
    onOpenMap: () -> Unit = {},
    /** Settings (UI upgrade, 2026-11-20): version, notifications, password; no sign-out. */
    onOpenSettings: () -> Unit = {},
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

    // Swipe right anywhere on Home opens the snap camera (owner, 2026-11-19).
    val swipeOpenPx = with(LocalDensity.current) { 96.dp.toPx() }
    var swipe by remember { mutableFloatStateOf(0f) }
    Surface(
        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { swipe = 0f },
                onDragEnd = { if (swipe > swipeOpenPx) onOpenCamera() },
                onDragCancel = { swipe = 0f },
            ) { _, dx -> swipe += dx }
        },
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            var searching by rememberSaveable { mutableStateOf(false) }
            Header(
                searching = searching || state.query.isNotEmpty(),
                onSearch = {
                    if (searching || state.query.isNotEmpty()) {
                        viewModel.setQuery("")
                        searching = false
                    } else {
                        searching = true
                    }
                },
                onSettings = onOpenSettings,
                onConnect = onOpenInbox,
            )
            UpdateBanner(updater, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            LastCrashCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            AskBatteryExemptionOnce()
            AskNotificationPermissionOnce()
            AskFullScreenCallsOnce()
            AskBackgroundLocation()
            if (state.offline) {
                StatusBanner(
                    kind = BannerKind.WARNING,
                    title = stringResource(R.string.server_unreachable),
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = viewModel::refresh,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (state.loading && state.chats.isEmpty()) {
                // Placeholder rows while the first list loads, so the screen does not jump.
                Column(modifier = Modifier.weight(1f)) { repeat(4) { SkeletonRow() } }
            } else if (state.chats.isEmpty()) {
                EmptyState(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.home_not_connected_title),
                    body = stringResource(R.string.home_not_connected_body),
                    action = { PrimaryButton(stringResource(R.string.home_connect), onOpenInbox) },
                )
            } else {
                AnimatedVisibility(
                    visible = searching || state.query.isNotEmpty(),
                    enter = expandVertically(tween(Motion.MEDIUM)) + fadeIn(tween(Motion.MEDIUM)),
                    exit = shrinkVertically(tween(Motion.FAST)) + fadeOut(tween(Motion.FAST)),
                ) {
                    SearchField(state.query, viewModel::setQuery)
                }
                FilterRow(state.filter, unread = state.chats.count { it.unreadCount > 0 }, onSelect = viewModel::setFilter)
                ChatList(state.visibleChats, onChat, onSnap = onSnapTo, modifier = Modifier.weight(1f))
            }
            VaultBottomBar(selected = BottomTab.CHAT, onChat = {}, onCamera = onOpenCamera, onMap = onOpenMap)
        }
    }
}

/**
 * The app's title with three quiet actions: search (expands the search field), Settings and "+" to connect
 * with someone. No sign-out here (owner request 2026-11-02).
 */
@Composable
private fun Header(searching: Boolean, onSearch: () -> Unit, onSettings: () -> Unit, onConnect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = Spacing.xl, end = Spacing.sm, top = Spacing.md, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            maxLines = 1,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        VaultIconButton(
            icon = if (searching) Icons.Filled.Close else Icons.Filled.Search,
            contentDescription = stringResource(if (searching) R.string.home_search_close else R.string.home_search_hint),
            onClick = onSearch,
        )
        VaultIconButton(Icons.Filled.Settings, stringResource(R.string.settings_title), onSettings)
        VaultIconButton(
            Icons.Filled.Add,
            stringResource(R.string.home_connect_new),
            onConnect,
            container = MaterialTheme.colorScheme.primary,
            tint = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/** A white circle holding one icon, as in the reference design's header. */
@Composable
fun RoundIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, modifier = modifier.padding(4.dp).size(44.dp)) {
        IconButton(onClick = onClick) { content() }
    }
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

/**
 * Android 13+: the incoming-call notification needs the notification permission (D-24 as changed by the
 * owner on 2026-11-12: calls only). Asked once, and only in builds that can receive pushes.
 */
@Composable
private fun AskNotificationPermissionOnce() {
    if (!Push.CALL_NOTIFICATIONS || !Push.configured) return
    val context = LocalContext.current
    val prefs = (context.applicationContext as VaultChatApplication).container.permissionPreferences
    val request = rememberPermissionRequest(AppPermission.NOTIFICATIONS, prefs)
    LaunchedEffect(Unit) {
        if (request.status != PermissionStatus.Granted && !prefs.wasRequested(AppPermission.NOTIFICATIONS)) request.request()
    }
}

/**
 * Incoming calls full screen (owner request 2026-11-13). Explained once, then Android's own settings page
 * is opened: "Display over other apps" (the call opens full screen while the phone is in use) and, on
 * Android 14+ when it is off, "full-screen notifications" (locked or dark phone).
 */
@Composable
private fun AskFullScreenCallsOnce() {
    if (!Push.CALL_NOTIFICATIONS || !Push.configured) return
    val context = LocalContext.current
    val prefs = (context.applicationContext as VaultChatApplication).container.permissionPreferences
    var show by remember {
        mutableStateOf(!prefs.fullScreenCallsAsked() && (!Push.canOpenOverOtherApps(context) || !Push.canUseFullScreenCalls(context)))
    }
    if (!show) return
    fun open(action: String) {
        try {
            context.startActivity(Intent(action, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            // The phone has no such page; the setting stays reachable in Android's app settings.
        }
    }
    AlertDialog(
        onDismissRequest = { prefs.markFullScreenCallsAsked(); show = false },
        title = { Text(stringResource(R.string.full_screen_calls_title)) },
        text = { Text(stringResource(R.string.full_screen_calls_body)) },
        confirmButton = {
            TextButton(onClick = {
                prefs.markFullScreenCallsAsked()
                show = false
                if (!Push.canUseFullScreenCalls(context)) open("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT")
                if (!Push.canOpenOverOtherApps(context)) open(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            }) { Text(stringResource(R.string.full_screen_calls_open)) }
        },
        dismissButton = {
            TextButton(onClick = { prefs.markFullScreenCallsAsked(); show = false }) { Text(stringResource(R.string.full_screen_calls_later)) }
        },
    )
}

/**
 * Asks for "Allow all the time" so the Map stays current while Vault is closed (owner, 2026-11-19; D-06).
 * Not required to use the app; asked again at most once a day until allowed.
 */
@Composable
private fun AskBackgroundLocation() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    val context = LocalContext.current
    val sharing = (context.applicationContext as VaultChatApplication).container.locationSharing
    val prefs = remember { context.getSharedPreferences("location_sharing", android.content.Context.MODE_PRIVATE) }
    val dayMs = 24 * 60 * 60_000L
    var show by remember {
        mutableStateOf(
            sharing.hasPermission() && !sharing.hasBackgroundPermission() &&
                System.currentTimeMillis() - prefs.getLong("background_asked_at", 0L) > dayMs,
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { sharing.onAppOpened() }
    if (!show) return
    fun asked() {
        prefs.edit().putLong("background_asked_at", System.currentTimeMillis()).apply()
        show = false
    }
    AlertDialog(
        onDismissRequest = { asked() },
        title = { Text(stringResource(R.string.background_location_title)) },
        text = { Text(stringResource(R.string.background_location_body)) },
        confirmButton = {
            TextButton(onClick = {
                asked()
                // Android 11 and later open the app's location settings, where "Allow all the time" is chosen.
                launcher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }) { Text(stringResource(R.string.background_location_allow)) }
        },
        dismissButton = { TextButton(onClick = { asked() }) { Text(stringResource(R.string.full_screen_calls_later)) } },
    )
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.home_search_hint)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            { IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.home_search_clear)) } }
        } else {
            null
        },
        shape = RoundedCornerShape(Radii.input),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedBorderColor = MaterialTheme.colorScheme.surface,
            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = Spacing.xs),
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
    val container by animateColorAsState(if (selected) colors.primary else colors.surface, tween(Motion.MEDIUM), label = "pill")
    val content by animateColorAsState(if (selected) colors.onPrimary else colors.onSurface, tween(Motion.MEDIUM), label = "pillText")
    Surface(
        shape = RoundedCornerShape(50),
        color = container,
        contentColor = content,
        modifier = Modifier.semantics { this.selected = selected }.clip(RoundedCornerShape(50)).clickable(role = Role.Tab, onClick = onClick),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp))
    }
}

@Composable
private fun ChatList(chats: List<Connection>, onChat: (Connection) -> Unit, onSnap: (Connection) -> Unit, modifier: Modifier = Modifier) {
    if (chats.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.TopCenter) {
            Text(stringResource(R.string.home_no_matches), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = Spacing.xs)) {
        items(chats, key = { it.id }) { chat ->
            // Rows slide to their new place when the order changes (a new message moves a chat up).
            Column(modifier = Modifier.animateItem()) {
                ChatRow(chat, onClick = { onChat(chat) }, onSnap = { onSnap(chat) })
                HorizontalDivider(
                    modifier = Modifier.padding(start = Spacing.screen + Sizes.avatarRow + Spacing.md),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                )
            }
        }
    }
}

/**
 * A chat-list row (UI upgrade 2026-11-20, keeping the owner's Snapchat elements): avatar, name with the time
 * on the right, a status line ("New snap", "N new messages" or "Tap to chat") with its coloured square, the
 * 🔥 streak, the unread badge and a camera button for a snap. The list has no message text to preview (the
 * chat list API carries counts and times only).
 */
@Composable
private fun ChatRow(chat: Connection, onClick: () -> Unit, onSnap: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val snap = chat.pendingSnaps > 0
    val unread = chat.unreadCount > 0
    val fresh = snap || unread
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(onClick = onClick)
            .padding(start = Spacing.screen, end = Spacing.xs, top = Spacing.sm, bottom = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        InitialsAvatar(chat.peerUsername, size = Sizes.avatarRow, highlighted = snap)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    chat.peerUsername,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (fresh) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // Snap streak (D-27): days in a row on which both sent a snap.
                if (chat.streak > 0) {
                    Text(
                        " ${chat.streak}\uD83D\uDD25",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.weight(1f))
                chat.lastMessageAtEpochMs?.let {
                    Text(
                        relativeTime(it),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (unread) colors.primary else colors.onSurfaceVariant,
                        fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        modifier = Modifier.padding(start = Spacing.sm),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Snapchat's status square: filled for something new, an outline otherwise.
                val mark = if (snap) colors.primary else colors.secondary
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .then(if (fresh) Modifier.background(mark, RoundedCornerShape(2.dp)) else Modifier.border(1.5.dp, mark, RoundedCornerShape(2.dp))),
                )
                Spacer(Modifier.width(6.dp))
                val status = when {
                    snap -> pluralStringResource(R.plurals.home_new_snaps, chat.pendingSnaps, chat.pendingSnaps)
                    unread -> pluralStringResource(R.plurals.home_new_messages, chat.unreadCount, chat.unreadCount)
                    else -> stringResource(R.string.home_tap_to_chat)
                }
                Text(
                    status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (fresh) colors.onSurface else colors.onSurfaceVariant,
                    fontWeight = if (fresh) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                UnreadBadge(chat.unreadCount, modifier = Modifier.padding(start = Spacing.sm))
            }
        }
        IconButton(onClick = onSnap) {
            Icon(AppIcons.PhotoCamera, contentDescription = stringResource(R.string.snap_button), tint = colors.onSurfaceVariant, modifier = Modifier.size(22.dp))
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
