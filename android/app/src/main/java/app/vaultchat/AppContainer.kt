package app.vaultchat

import android.content.Context
import android.os.Build
import app.vaultchat.data.auth.AuthApi
import app.vaultchat.data.auth.AuthorizedCaller
import app.vaultchat.data.call.RemoteCallRepository
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
    private val http = ApiClient.defaultHttpClient()

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

    val permissionPreferences = PermissionPreferences(appContext)
}
