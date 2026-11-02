package app.vaultchat.data.location

import app.vaultchat.data.auth.AuthorizedCaller
import app.vaultchat.data.network.ApiResult
import app.vaultchat.data.network.HttpJson
import kotlinx.serialization.Serializable

@Serializable
data class LocationStateDto(
    val enabled: Boolean,
    val consentVersion: Int?,
    val currentConsentVersion: Int,
    val precision: String?,
    val lastSentAt: String?,
    val lastViewedByAdminAt: String?,
)

@Serializable
private data class EnableBody(val enabled: Boolean, val consentVersion: Int, val precision: String)

@Serializable
private data class DisableBody(val enabled: Boolean)

@Serializable
private data class PointBody(val lat: Double, val lng: Double, val accuracyM: Double, val capturedAt: String, val precision: String)

/** Location sharing with the administrator (docs/14, docs/11; owner decision D-06). */
class LocationApi(private val http: HttpJson, private val caller: AuthorizedCaller) {
    suspend fun state(): ApiResult<LocationStateDto> =
        caller.call { http.get("api/v1/location-sharing", LocationStateDto.serializer(), it) }

    suspend fun enable(consentVersion: Int, precise: Boolean): ApiResult<LocationStateDto> = caller.call {
        http.put("api/v1/location-sharing", EnableBody(true, consentVersion, precision(precise)), EnableBody.serializer(), LocationStateDto.serializer(), it)
    }

    suspend fun disable(): ApiResult<LocationStateDto> = caller.call {
        http.put("api/v1/location-sharing", DisableBody(false), DisableBody.serializer(), LocationStateDto.serializer(), it)
    }

    suspend fun report(lat: Double, lng: Double, accuracyM: Double, capturedAtIso: String, precise: Boolean): ApiResult<LocationStateDto> = caller.call {
        http.put(
            "api/v1/location-sharing/current",
            PointBody(lat, lng, accuracyM, capturedAtIso, precision(precise)),
            PointBody.serializer(),
            LocationStateDto.serializer(),
            it,
        )
    }

    private fun precision(precise: Boolean) = if (precise) "precise" else "approximate"
}
