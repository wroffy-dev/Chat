package app.vaultchat

import android.content.Context
import android.os.Build
import app.vaultchat.data.auth.AuthApi
import app.vaultchat.data.auth.AuthorizedCaller
import app.vaultchat.data.call.RemoteCallRepository
import app.vaultchat.data.chat.RemoteChatRepository
import app.vaultchat.data.realtime.RealtimeClient
import app.vaultchat.domain.chat.ChatRepository
import app.vaultchat.domain.chat.PendingLeaveStore
import app.vaultchat.platform.chat.PrefsPendingLeaveStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import app.vaultchat.data.push.PushTokenApi
import app.vaultchat.data.app.AppVersionApi
import kotlinx.coroutines.flow.MutableStateFlow
import app.vaultchat.data.connection.ConnectionApi
import app.vaultchat.domain.call.CallRepository
import app.vaultchat.data.connection.RemoteConnectionRepository
import app.vaultchat.domain.connection.ConnectionRepository
import app.vaultchat.data.auth.DeviceDto
import app.vaultchat.data.auth.RemoteAuthRepository
import app.vaultchat.data.network.ApiClient
import app.vaultchat.data.network.HttpJson
import app.vaultchat.domain.auth.AuthRepository
import app.vaultchat.platform.KeystoreTokenStore
import app.vaultchat.ui.permissions.PermissionPreferences

/** Manual dependency container. Small enough that a DI framework would add more than it saves. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    /** Set when the server says this build is too old (426); the app then shows only the update screen. */
    val upgradeRequired = MutableStateFlow(false)

    private val http = ApiClient.defaultHttpClient(BuildConfig.VERSION_CODE) { upgradeRequired.value = true }

    val apiClient = ApiClient(BuildConfig.API_BASE_URL, http)

    private val httpJson = HttpJson(BuildConfig.API_BASE_URL, http)
    private val authApi = AuthApi(httpJson)
    private val tokenStore = KeystoreTokenStore(appContext)

    /** One caller for all repositories, so token refreshes are serialised app-wide. */
    private val caller = AuthorizedCaller(authApi, tokenStore)

    val authRepository: AuthRepository = RemoteAuthRepository(
        api = authApi,
        store = tokenStore,
        device = DeviceDto(model = Build.MODEL.take(64), appVersionCode = BuildConfig.VERSION_CODE),
        caller = caller,
    )

    val connectionRepository: ConnectionRepository = RemoteConnectionRepository(ConnectionApi(httpJson), caller)

    val callRepository: CallRepository = RemoteCallRepository(httpJson, caller)

    val pushTokenApi = PushTokenApi(httpJson, caller)

    val appVersionApi = AppVersionApi(httpJson)

    val chatRepository: ChatRepository = RemoteChatRepository(httpJson, caller)

    /** Live change signals; run by the navigation host while signed in and in front. */
    val realtime = RealtimeClient(BuildConfig.API_BASE_URL, http, caller)

    val pendingLeaves: PendingLeaveStore = PrefsPendingLeaveStore(appContext)

    /** For work that must finish after a screen closes (e.g. the chat exit request). */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Set when the user taps an incoming-call notification; the navigation host consumes it. */
    val pendingIncomingCallId = MutableStateFlow<String?>(null)

    val permissionPreferences = PermissionPreferences(appContext)
}
