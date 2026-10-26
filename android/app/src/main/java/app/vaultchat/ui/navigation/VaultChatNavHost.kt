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
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
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
data class ChatRoute(val conversationId: String, val peerUsername: String)

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
    // Rings only while the app is open (no push notifications yet).
    // Live change signals while signed in and the app is in front (calls arrive by push otherwise).
    LifecycleStartEffect(signedIn) {
        val job = if (signedIn) container.applicationScope.launch { container.realtime.run() } else null
        onStopOrDispose { job?.cancel() }
    }
    IncomingCallWatcher(container.callRepository, container.realtime, enabled = signedIn && !onCallScreen) { call ->
        navController.navigate(CallRoute(video = call.kind == CallKind.VIDEO, peerUsername = call.peerUsername, callId = call.id))
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
        val call = (container.callRepository.get(id) as? CallResult.Ok)?.value ?: return@LaunchedEffect
        if (call.status == CallStatus.RINGING && !call.isCaller && !onCallScreen) {
            navController.navigate(CallRoute(video = call.kind == CallKind.VIDEO, peerUsername = call.peerUsername, callId = call.id))
        }
    }

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
                factory = viewModelFactory { initializer { HomeViewModel(container.authRepository) } },
            )
            HomeScreen(
                viewModel = viewModel,
                onChangePassword = { navController.navigate(ChangePasswordRoute(forced = false)) },
                onSignedOut = { navController.replaceAll(LoginRoute) },
                onPasswordChangeRequired = { navController.replaceAll(ChangePasswordRoute(forced = true)) },
                onOpenInbox = { navController.navigate(InboxRoute) },
                onCall = { connectionId, peer, video ->
                    navController.navigate(CallRoute(video = video, peerUsername = peer, connectionId = connectionId))
                },
                onChat = { conversationId, peer -> navController.navigate(ChatRoute(conversationId, peer)) },
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
            )
        }
        composable<InboxRoute> {
            val viewModel: InboxViewModel = viewModel(
                factory = viewModelFactory { initializer { InboxViewModel(container.connectionRepository) } },
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
                factory = viewModelFactory { initializer { ShowQrViewModel(container.connectionRepository) } },
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
                factory = viewModelFactory { initializer { ScanQrViewModel(container.connectionRepository) } },
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
}
