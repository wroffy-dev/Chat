package app.vaultchat

import android.content.Context
import android.os.Build
import app.vaultchat.data.auth.AuthApi
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

    val authRepository: AuthRepository = RemoteAuthRepository(
        api = AuthApi(HttpJson(BuildConfig.API_BASE_URL, http)),
        store = KeystoreTokenStore(appContext),
        device = DeviceDto(model = Build.MODEL.take(64), appVersionCode = BuildConfig.VERSION_CODE),
    )

    val permissionPreferences = PermissionPreferences(appContext)
}
