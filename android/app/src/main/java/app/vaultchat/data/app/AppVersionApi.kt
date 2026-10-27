package app.vaultchat.data.app

import app.vaultchat.data.network.ApiResult
import app.vaultchat.data.network.HttpJson
import kotlinx.serialization.Serializable

@Serializable
data class AppVersionInfo(val minSupportedVersionCode: Int, val latestVersionCode: Int, val latestApkSha256: String? = null)

/** GET /api/v1/app/version: public, so even an outdated app can learn what to install. */
class AppVersionApi(private val http: HttpJson) {
    suspend fun fetch(): AppVersionInfo? = (http.get("api/v1/app/version", AppVersionInfo.serializer()) as? ApiResult.Ok)?.value
}
