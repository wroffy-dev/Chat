package app.vaultchat.ui.navigation

import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.vaultchat.AppContainer
import android.app.Application
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vaultchat.domain.call.CallResult
import app.vaultchat.platform.push.Push
import app.vaultchat.data.network.ApiResult
import app.vaultchat.domain.connection.Connection
import app.vaultchat.platform.call.TalkPlayer
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.currentBackStackEntryAsState
import app.vaultchat.domain.call.CallInfo
import app.vaultchat.domain.call.CallKind
import app.vaultchat.domain.call.CallPhase
import app.vaultchat.domain.call.CallStatus
import app.vaultchat.domain.chat.flushPendingLeave
import app.vaultchat.ui.about.UpgradeRequiredScreen
import app.vaultchat.ui.call.CallScreen
import app.vaultchat.ui.snap.SnapCameraScreen
import app.vaultchat.ui.map.MapScreen
import app.vaultchat.ui.snap.SnapViewerScreen
import app.vaultchat.domain.snap.SnapResult
import app.vaultchat.domain.connection.ConnResult
import app.vaultchat.domain.realtime.RealtimeSignal
import app.vaultchat.domain.realtime.pollWithSignals
import app.vaultchat.ui.call.CallEntryGuard
import app.vaultchat.ui.call.CallSession
import app.vaultchat.ui.call.MiniCall
import app.vaultchat.ui.call.TalkDialog
import app.vaultchat.ui.chat.ChatScreen
import app.vaultchat.ui.chat.ChatViewModel
import app.vaultchat.ui.call.IncomingCallWatcher
import app.vaultchat.ui.connect.InboxScreen
import app.vaultchat.ui.connect.InboxViewModel
import app.vaultchat.ui.connect.ScanQrScreen
import app.vaultchat.ui.connect.ScanQrViewModel
import app.vaultchat.ui.connect.ShowQrScreen
import app.vaultchat.ui.connect.ShowQrViewModel
import app.vaultchat.ui.home.HomeScreen
import app.vaultchat.ui.notes.NoteEditorScreen
import app.vaultchat.ui.notes.NoteEditorViewModel
import app.vaultchat.ui.notes.NotesScreen
import app.vaultchat.ui.notes.NotesViewModel
import app.vaultchat.ui.home.HomeViewModel
import app.vaultchat.ui.login.LoginOutcome
import app.vaultchat.ui.login.LoginScreen
import app.vaultchat.ui.login.LoginViewModel
import app.vaultchat.ui.password.ChangePasswordScreen
import app.vaultchat.ui.password.ChangePasswordViewModel
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import app.vaultchat.domain.location.LocationBlock
import app.vaultchat.ui.location.LocationRequiredScreen
import kotlinx.serialization.Serializable
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import app.vaultchat.ui.theme.Motion

@Serializable
data object LoginRoute

/** The chat list, with the Map and the camera behind it. Reached only by tapping the "Just Notes" title (D-31). */
@Serializable
data object HomeRoute

/** Just Notes: the screen the app opens on (D-31). */
@Serializable
data object NotesRoute

/** One note; [id] is null for a new one. */
@Serializable
data class NoteEditorRoute(val id: String? = null)

@Serializable
data class ChangePasswordRoute(val forced: Boolean)

@Serializable
data object InboxRoute


@Serializable
data object ShowQrRoute

@Serializable
data object ScanQrRoute

@Serializable
data class ChatRoute(
    val conversationId: String,
    /** The name shown: the user's nickname for the person, else their username (D-32). */
    val peerUsername: String,
    val connectionId: String,
    val username: String = "",
    val nickname: String? = null,
    val peerAvatar: String? = null,
)

private fun Connection.chatRoute() = ChatRoute(conversationId, peerUsername, id, username, nickname, peerAvatar)

/** Taking a snap (D-27); opened by swiping right on the chat list, the recipients are picked at Send. */
@Serializable
data class SnapCameraRoute(
    /** Ticked in the "Send to…" list when the camera was opened from that person's row or chat. */
    val preselect: String? = null,
)

/** The Map tab: connected people's latest locations (D-06 as widened by the owner, 2026-11-19). */
@Serializable
data object MapRoute

/** Viewing waiting snaps, oldest first; [ids] is comma-separated. */
@Serializable
data class SnapViewerRoute(val ids: String)

/** Outgoing: [connectionId] set, [callId] null. Incoming: [callId] set. */
@Serializable
data class CallRoute(
    val video: Boolean,
    val peerUsername: String,
    val connectionId: String? = null,
    val callId: String? = null,
)

/**
 * Camera, snaps and calls open full screen: they grow into place with a soft spring and fade in, and shrink
 * away (owner 2026-11-22: premium motion).
 */
private fun riseIn(): EnterTransition =
    fadeIn(tween(Motion.MEDIUM)) + scaleIn(spring(dampingRatio = 0.86f, stiffness = 320f), initialScale = 0.9f)

private fun sinkOut(): ExitTransition =
    fadeOut(tween(Motion.MEDIUM)) + scaleOut(spring(dampingRatio = 1f, stiffness = 420f), targetScale = 0.92f)

/** The push of a new screen: a critically damped spring, quick to start and soft to land, as on iOS. */
private val PushSpring = spring(dampingRatio = 1f, stiffness = 340f, visibilityThreshold = IntOffset.VisibilityThreshold)

/** Clears the whole back stack, so Back never returns to a screen from the previous state. */
private fun NavHostController.replaceAll(route: Any) {
    navigate(route) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}

/** The chat list over the notes, so Back from the chats returns to the notes. */
private fun NavHostController.openChats() {
    navigate(HomeRoute) {
        popUpTo(NotesRoute)
        launchSingleTop = true
    }
}

@Composable
fun VaultChatNavHost(container: AppContainer) {
    // An outdated build can do nothing but tell the user to update (docs/05 §5, docs/16 §9).
    val upgradeRequired by container.upgradeRequired.collectAsStateWithLifecycle()
    if (upgradeRequired) {
        UpgradeRequiredScreen(container.appVersionApi)
        return
    }
    val navController = rememberNavController()
    // Decided once from the stored session; the Home screen then confirms it with the server.
    val start: Any = remember {
        val user = container.authRepository.currentUser()
        when {
            user == null -> LoginRoute
            user.mustChangePassword -> ChangePasswordRoute(forced = true)
            else -> NotesRoute
        }
    }

    val context = LocalContext.current
    val backStack by navController.currentBackStackEntryAsState()
    val onCallScreen = backStack?.destination?.route?.contains("CallRoute") == true
    // On the notes screens a new message is still announced as "New notes added" (Push.showNewMessage).
    val onNotes = backStack?.destination?.route?.let { it.contains("NotesRoute") || it.contains("NoteEditorRoute") } == true
    LaunchedEffect(onNotes) { Push.onNotesScreen = onNotes }
    // Screens of a signed-in user: calls ring and live updates run on all of them (the Map too).
    val signedIn = backStack?.destination?.route?.let { r -> listOf("NotesRoute", "NoteEditorRoute", "HomeRoute", "InboxRoute", "ShowQrRoute", "ScanQrRoute", "ChatRoute", "MapRoute").any { r.contains(it) } } == true
    // Live change signals while signed in. Not tied to the screen's lifecycle, so an incoming call is
    // still noticed with the screen off while the app is open (owner request 2026-11-03).
    DisposableEffect(signedIn) {
        val job = if (signedIn) container.applicationScope.launch { container.realtime.run() } else null
        onDispose { job?.cancel() }
    }
    // Location sharing (D-06): sent each time the app comes to the front; the app is blocked while it
    // is off. A call in progress is never covered.
    val location by container.locationSharing.state.collectAsStateWithLifecycle()
    LifecycleStartEffect(signedIn) {
        if (signedIn) container.locationSharing.onAppOpened()
        onStopOrDispose { }
    }
    val locationBlocked = signedIn && !onCallScreen && location.block != LocationBlock.NONE
    val scope = rememberCoroutineScope()

    IncomingCallWatcher(
        container.callRepository,
        container.realtime,
        container.applicationScope,
        enabled = signedIn && !onCallScreen && !locationBlocked,
    ) { call ->
        // A walkie-talkie message is played aloud at once; no call screen, no ringing (D-26).
        if (call.kind == CallKind.TALK) {
            TalkPlayer.play(context, call.id)
            return@IncomingCallWatcher
        }
        // Turn the screen on and show over the lock screen (MainActivity watches this), then open the
        // ringing call; its 10 s auto-answer then fires even if the screen was off.
        container.wakeForCall.value = true
        navController.navigate(CallRoute(video = call.kind == CallKind.VIDEO, peerUsername = call.peerUsername, callId = call.id))
    }
    // Stop keeping the screen awake once the call screen is gone. Not before it was shown: a call opened
    // from the notification sets the wake first and reaches the call screen a moment later.
    var wasOnCallScreen by remember { mutableStateOf(false) }
    LaunchedEffect(onCallScreen) {
        if (onCallScreen) {
            wasOnCallScreen = true
        } else if (wasOnCallScreen) {
            wasOnCallScreen = false
            container.wakeForCall.value = false
        }
    }

    // Register this device for call wake-ups once signed in (no-op when FCM is not configured), and
    // send a chat exit that a crash or lost network kept from reaching the server.
    LaunchedEffect(signedIn) {
        if (signedIn) {
            flushPendingLeave(container.chatRepository, container.pendingLeaves)
            Push.registerAfterLogin(container.pushTokenApi)
        }
    }

    // A tapped incoming-call notification: open the call screen if the call is still ringing.
    val pendingCallId by container.pendingIncomingCallId.collectAsStateWithLifecycle()
    LaunchedEffect(pendingCallId, signedIn) {
        val id = pendingCallId ?: return@LaunchedEffect
        if (!signedIn) return@LaunchedEffect
        container.pendingIncomingCallId.value = null
        Push.cancelIncomingCall(context)
        val call = (container.callRepository.get(id) as? CallResult.Ok)?.value
        if (call != null && call.status == CallStatus.RINGING && !call.isCaller && !onCallScreen) {
            navController.navigate(CallRoute(video = call.kind == CallKind.VIDEO, peerUsername = call.peerUsername, callId = call.id))
        } else if (!onCallScreen) {
            // The call is over (or could not be read): do not stay over the lock screen.
            container.wakeForCall.value = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    NavHost(
        navController = navController,
        startDestination = start,
        // Smooth, iPhone-like screen changes (owner 2026-11-21): a new screen slides in over the old one, which
        // drifts a little to the left and dims; Back plays it in reverse.
        enterTransition = { slideInHorizontally(PushSpring) { it } },
        exitTransition = {
            slideOutHorizontally(PushSpring) { -it / 4 } + fadeOut(tween(Motion.SLOW), targetAlpha = 0.5f)
        },
        popEnterTransition = {
            slideInHorizontally(PushSpring) { -it / 4 } + fadeIn(tween(Motion.SLOW), initialAlpha = 0.5f)
        },
        popExitTransition = { slideOutHorizontally(PushSpring) { it } },
    ) {
        composable<LoginRoute> {
            val viewModel: LoginViewModel = viewModel(
                factory = viewModelFactory { initializer { LoginViewModel(container.authRepository, container.apiClient) } },
            )
            LoginScreen(
                viewModel = viewModel,
                onLoggedIn = { outcome ->
                    navController.replaceAll(
                        if (outcome == LoginOutcome.CHANGE_PASSWORD) ChangePasswordRoute(forced = true) else NotesRoute,
                    )
                },
            )
        }
        composable<ChangePasswordRoute> { entry ->
            val route = entry.toRoute<ChangePasswordRoute>()
            val viewModel: ChangePasswordViewModel = viewModel(
                factory = viewModelFactory { initializer { ChangePasswordViewModel(container.authRepository) } },
            )
            ChangePasswordScreen(
                viewModel = viewModel,
                forced = route.forced,
                onDone = { navController.replaceAll(NotesRoute) },
                onSessionEnded = { navController.replaceAll(LoginRoute) },
                onBack = { navController.popBackStack() },
            )
        }
        composable<NotesRoute> {
            val viewModel: NotesViewModel = viewModel(
                factory = viewModelFactory { initializer { NotesViewModel(container.notesApi) } },
            )
            NotesScreen(
                viewModel = viewModel,
                onOpen = { id -> navController.navigate(NoteEditorRoute(id)) },
                onOpenChats = { navController.openChats() },
                onSessionEnded = { navController.replaceAll(LoginRoute) },
            )
        }
        composable<NoteEditorRoute> { entry ->
            val id = entry.toRoute<NoteEditorRoute>().id
            val viewModel: NoteEditorViewModel = viewModel(
                factory = viewModelFactory { initializer { NoteEditorViewModel(container.notesApi, id, container.applicationScope) } },
            )
            NoteEditorScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onSessionEnded = { navController.replaceAll(LoginRoute) },
            )
        }
        composable<HomeRoute> {
            val viewModel: HomeViewModel = viewModel(
                factory = viewModelFactory { initializer { HomeViewModel(container.authRepository, container.connectionRepository, container.realtime) } },
            )
            HomeScreen(
                viewModel = viewModel,
                onSignedOut = { navController.replaceAll(LoginRoute) },
                onPasswordChangeRequired = { navController.replaceAll(ChangePasswordRoute(forced = true)) },
                onOpenInbox = { navController.navigate(InboxRoute) },
                onChat = { c -> navController.navigate(c.chatRoute()) },
                onOpenCamera = { navController.navigate(SnapCameraRoute()) },
                onSnapTo = { c -> navController.navigate(SnapCameraRoute(c.id)) },
                onOpenMap = { navController.navigate(MapRoute) { launchSingleTop = true } },
            )
        }
        composable<ChatRoute> { entry ->
            val route = entry.toRoute<ChatRoute>()
            val me = container.authRepository.currentUser()
            if (me == null) {
                LaunchedEffect(Unit) { navController.replaceAll(LoginRoute) }
                return@composable
            }
            val viewModel: ChatViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        ChatViewModel(container.chatRepository, container.pendingLeaves, route.conversationId, me.id, container.applicationScope, container.realtime)
                    }
                },
            )
            // Walkie-talkie (D-26): a short dialog over the chat while the message is sent.
            var talking by remember { mutableStateOf(false) }
            if (talking) {
                TalkDialog(
                    repo = container.callRepository,
                    scope = container.applicationScope,
                    connectionId = route.connectionId,
                    peerUsername = route.peerUsername,
                    permissionPreferences = container.permissionPreferences,
                    onClose = { talking = false },
                )
            }
            // Snaps from this person waiting to be opened (D-27): re-read on a live signal, else every 15 s.
            var waitingSnaps by remember { mutableStateOf<List<String>>(emptyList()) }
            LaunchedEffect(route.connectionId) {
                pollWithSignals(container.realtime, { it is RealtimeSignal.SnapChanged }, pollMs = 15_000, signalledPollMs = 60_000) {
                    (container.snapRepository.inbox() as? SnapResult.Ok)?.value?.let { all ->
                        waitingSnaps = all.filter { it.connectionId == route.connectionId }.map { it.id }
                    }
                    true
                }
            }
            ChatScreen(
                onTalk = { talking = true },
                onSnap = { navController.navigate(SnapCameraRoute(route.connectionId)) },
                waitingSnaps = waitingSnaps.size,
                onOpenSnaps = { if (waitingSnaps.isNotEmpty()) navController.navigate(SnapViewerRoute(waitingSnaps.joinToString(","))) },
                viewModel = viewModel,
                myUserId = me.id,
                peerUsername = route.peerUsername,
                onBack = { navController.popBackStack() },
                onSessionEnded = { navController.replaceAll(LoginRoute) },
                onCall = { video -> navController.navigate(CallRoute(video = video, peerUsername = route.peerUsername, connectionId = route.connectionId)) },
                peerAvatar = route.peerAvatar,
                realUsername = route.username.ifEmpty { route.peerUsername },
                nickname = route.nickname,
                onSetNickname = { name -> container.profileApi.setNickname(route.connectionId, name) is ApiResult.Ok },
            )
        }
        composable<CallRoute>(
            enterTransition = { riseIn() },
            popExitTransition = { sinkOut() },
        ) { entry ->
            val route = entry.toRoute<CallRoute>()
            val kind = if (route.video) CallKind.VIDEO else CallKind.VOICE
            // The call lives in the app, not in this screen, so it can be minimised and come back.
            val callSession = remember {
                // A call that is still running (coming back from the small picture) is shown again; an ended
                // one is not reused ("Call again" opens this screen before the old one is cleared).
                container.activeCall.value?.takeIf { it.controller.state.value.phase != CallPhase.ENDED }
                    ?: CallSession(context.applicationContext as Application, container.callRepository, container.realtime, container.systemCalls)
                        .also { container.activeCall.value = it }
            }
            LaunchedEffect(callSession) { callSession.minimized = false }
            // When this screen is gone for good (Close, Back, sign-out) the call ends, unless it was minimised.
            val guard: CallEntryGuard = viewModel()
            guard.onGone = { if (!guard.keepCall) container.endCall(callSession) }
            CallScreen(
                onMinimize = {
                    guard.keepCall = true
                    callSession.minimized = true
                    navController.popBackStack()
                },
                viewModel = callSession,
                permissionPreferences = container.permissionPreferences,
                kind = kind,
                incoming = route.callId != null,
                onStart = {
                    val callId = route.callId
                    if (callId != null) {
                        callSession.showIncoming(CallInfo(callId, kind, CallStatus.RINGING, isCaller = false, peerUsername = route.peerUsername))
                    } else if (route.connectionId != null) {
                        callSession.startOutgoing(route.connectionId, kind, route.peerUsername)
                    }
                },
                onClose = { navController.popBackStack() },
                // Only outgoing calls know the connection to call again; incoming ones close instead.
                onCallAgain = route.connectionId?.let { connectionId ->
                    {
                        navController.popBackStack()
                        navController.navigate(CallRoute(video = route.video, peerUsername = route.peerUsername, connectionId = connectionId))
                    }
                },
            )
        }
        // The Map is a tab next to Chat: it fades in and out instead of sliding.
        composable<MapRoute>(
            enterTransition = { fadeIn(tween(Motion.MEDIUM)) },
            popExitTransition = { fadeOut(tween(Motion.MEDIUM)) },
        ) {
            MapScreen(
                api = container.locationApi,
                onChat = { navController.popBackStack() },
                onCamera = { navController.navigate(SnapCameraRoute()) },
                // A person's marker opens their chat or calls them (owner, 2026-11-21).
                onOpenChat = { connectionId ->
                    scope.launch {
                        val c = (container.connectionRepository.connections() as? ConnResult.Ok)?.value?.find { it.id == connectionId }
                        if (c != null) navController.navigate(c.chatRoute())
                    }
                },
                onCallPerson = { connectionId, username, video ->
                    navController.navigate(CallRoute(video = video, peerUsername = username, connectionId = connectionId))
                },
            )
        }
        composable<SnapCameraRoute>(
            enterTransition = { riseIn() },
            popExitTransition = { sinkOut() },
        ) { entry ->
            SnapCameraScreen(
                preselect = entry.toRoute<SnapCameraRoute>().preselect,
                repo = container.snapRepository,
                loadRecipients = { (container.connectionRepository.connections() as? ConnResult.Ok)?.value.orEmpty() },
                permissionPreferences = container.permissionPreferences,
                onClose = { navController.popBackStack() },
            )
        }
        composable<SnapViewerRoute>(
            enterTransition = { riseIn() },
            popExitTransition = { sinkOut() },
        ) { entry ->
            val route = entry.toRoute<SnapViewerRoute>()
            SnapViewerScreen(
                repo = container.snapRepository,
                snapIds = route.ids.split(",").filter { it.isNotBlank() },
                onClose = { navController.popBackStack() },
            )
        }
        composable<InboxRoute> {
            val viewModel: InboxViewModel = viewModel(
                factory = viewModelFactory { initializer { InboxViewModel(container.connectionRepository, container.realtime) } },
            )
            InboxScreen(
                viewModel = viewModel,
                onShowQr = { navController.navigate(ShowQrRoute) },
                onScanQr = { navController.navigate(ScanQrRoute) },
                onSessionEnded = { navController.replaceAll(LoginRoute) },
                onBack = { navController.popBackStack() },
            )
        }
        composable<ShowQrRoute> {
            val viewModel: ShowQrViewModel = viewModel(
                factory = viewModelFactory { initializer { ShowQrViewModel(container.connectionRepository, container.realtime) } },
            )
            ShowQrScreen(
                viewModel = viewModel,
                onConnected = { navController.openChats() },
                onSessionEnded = { navController.replaceAll(LoginRoute) },
                onBack = { navController.popBackStack() },
            )
        }
        composable<ScanQrRoute> {
            val viewModel: ScanQrViewModel = viewModel(
                factory = viewModelFactory { initializer { ScanQrViewModel(container.connectionRepository, container.realtime) } },
            )
            ScanQrScreen(
                viewModel = viewModel,
                permissionPreferences = container.permissionPreferences,
                onConnected = { navController.openChats() },
                onSessionEnded = { navController.replaceAll(LoginRoute) },
                onBack = { navController.popBackStack() },
            )
        }
    }
    // A minimised call: a small floating picture over whatever screen is shown; tap it to go back.
    val activeCall by container.activeCall.collectAsStateWithLifecycle()
    activeCall?.let { session ->
        if (session.minimized && !onCallScreen) {
            MiniCall(
                session = session,
                onOpen = { video, peer -> navController.navigate(CallRoute(video = video, peerUsername = peer)) },
                onEnded = { container.endCall(session) },
            )
        }
    }
    if (locationBlocked) {
        LocationRequiredScreen(
            ui = location,
            sharing = container.locationSharing,
            onLogout = {
                scope.launch {
                    Push.forgetOnDevice()
                    container.authRepository.logout()
                    navController.replaceAll(LoginRoute)
                }
            },
        )
    }
    }
}
