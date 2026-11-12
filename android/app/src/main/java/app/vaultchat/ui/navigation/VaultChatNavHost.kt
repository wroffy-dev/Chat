package app.vaultchat.ui.navigation

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
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.currentBackStackEntryAsState
import app.vaultchat.domain.call.CallInfo
import app.vaultchat.domain.call.CallKind
import app.vaultchat.domain.call.CallStatus
import app.vaultchat.domain.chat.flushPendingLeave
import app.vaultchat.ui.about.UpgradeRequiredScreen
import app.vaultchat.ui.call.CallScreen
import app.vaultchat.ui.chat.ChatScreen
import app.vaultchat.ui.chat.ChatViewModel
import app.vaultchat.ui.call.CallViewModel
import app.vaultchat.ui.call.IncomingCallWatcher
import app.vaultchat.ui.connect.InboxScreen
import app.vaultchat.ui.connect.InboxViewModel
import app.vaultchat.ui.connect.ScanQrScreen
import app.vaultchat.ui.connect.ScanQrViewModel
import app.vaultchat.ui.connect.ShowQrScreen
import app.vaultchat.ui.connect.ShowQrViewModel
import app.vaultchat.ui.home.HomeScreen
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

@Serializable
data object LoginRoute

@Serializable
data object HomeRoute

@Serializable
data class ChangePasswordRoute(val forced: Boolean)

@Serializable
data object InboxRoute

@Serializable
data object ShowQrRoute

@Serializable
data object ScanQrRoute

@Serializable
data class ChatRoute(val conversationId: String, val peerUsername: String, val connectionId: String)

/** Outgoing: [connectionId] set, [callId] null. Incoming: [callId] set. */
@Serializable
data class CallRoute(
    val video: Boolean,
    val peerUsername: String,
    val connectionId: String? = null,
    val callId: String? = null,
)

/** Clears the whole back stack, so Back never returns to a screen from the previous state. */
private fun NavHostController.replaceAll(route: Any) {
    navigate(route) {
        popUpTo(graph.id) { inclusive = true }
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
            else -> HomeRoute
        }
    }

    val context = LocalContext.current
    val backStack by navController.currentBackStackEntryAsState()
    val onCallScreen = backStack?.destination?.route?.contains("CallRoute") == true
    val signedIn = backStack?.destination?.route?.let { r -> listOf("HomeRoute", "InboxRoute", "ShowQrRoute", "ScanQrRoute", "ChatRoute").any { r.contains(it) } } == true
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
    NavHost(navController = navController, startDestination = start) {
        composable<LoginRoute> {
            val viewModel: LoginViewModel = viewModel(
                factory = viewModelFactory { initializer { LoginViewModel(container.authRepository, container.apiClient) } },
            )
            LoginScreen(
                viewModel = viewModel,
                onLoggedIn = { outcome ->
                    navController.replaceAll(
                        if (outcome == LoginOutcome.CHANGE_PASSWORD) ChangePasswordRoute(forced = true) else HomeRoute,
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
                onDone = { navController.replaceAll(HomeRoute) },
                onSessionEnded = { navController.replaceAll(LoginRoute) },
                onBack = { navController.popBackStack() },
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
                onChat = { c -> navController.navigate(ChatRoute(c.conversationId, c.peerUsername, c.id)) },
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
            ChatScreen(
                viewModel = viewModel,
                myUserId = me.id,
                peerUsername = route.peerUsername,
                onBack = { navController.popBackStack() },
                onSessionEnded = { navController.replaceAll(LoginRoute) },
                onCall = { video -> navController.navigate(CallRoute(video = video, peerUsername = route.peerUsername, connectionId = route.connectionId)) },
            )
        }
        composable<CallRoute> { entry ->
            val route = entry.toRoute<CallRoute>()
            val kind = if (route.video) CallKind.VIDEO else CallKind.VOICE
            val viewModel: CallViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { CallViewModel(context.applicationContext as Application, container.callRepository, container.realtime, container.systemCalls) }
                },
            )
            CallScreen(
                viewModel = viewModel,
                permissionPreferences = container.permissionPreferences,
                kind = kind,
                incoming = route.callId != null,
                onStart = {
                    val callId = route.callId
                    if (callId != null) {
                        viewModel.showIncoming(CallInfo(callId, kind, CallStatus.RINGING, isCaller = false, peerUsername = route.peerUsername))
                    } else if (route.connectionId != null) {
                        viewModel.startOutgoing(route.connectionId, kind, route.peerUsername)
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
                onConnected = { navController.replaceAll(HomeRoute) },
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
                onConnected = { navController.replaceAll(HomeRoute) },
                onSessionEnded = { navController.replaceAll(LoginRoute) },
                onBack = { navController.popBackStack() },
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
