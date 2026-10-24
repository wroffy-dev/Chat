package app.vaultchat

import android.content.Context
import app.vaultchat.data.auth.NotYetAvailableAuthRepository
import app.vaultchat.data.network.ApiClient
import app.vaultchat.domain.auth.AuthRepository
import app.vaultchat.ui.permissions.PermissionPreferences

/** Manual dependency container. Small enough that a DI framework would add more than it saves. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val apiClient = ApiClient(BuildConfig.API_BASE_URL, ApiClient.defaultHttpClient())

    // Phase 1: the backend has no account endpoints yet. See NotYetAvailableAuthRepository.
    val authRepository: AuthRepository = NotYetAvailableAuthRepository()

    val permissionPreferences = PermissionPreferences(appContext)
}
