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

/** A consent text as the server keeps it (backend/consents/<id>.json, its only source). */
@Serializable
data class ConsentDto(val id: String, val version: Int, val title: String, val points: List<String>, val agree: String)

/** A point on the Map: the user's own (no name) or a connected person's latest. */
@Serializable
data class MapPointDto(val lat: Double, val lng: Double, val accuracyM: Double, val capturedAt: String, val avatar: String? = null)

@Serializable
data class MapPersonDto(
    val connectionId: String,
    val username: String,
    val lat: Double,
    val lng: Double,
    val accuracyM: Double,
    val capturedAt: String,
    /** Their built-in avatar and the user's own nickname for them (D-32); absent from older servers. */
    val avatar: String? = null,
    val nickname: String? = null,
)

/** GET /location-sharing/connections: connected people's latest points (D-06 as widened 2026-11-19). */
@Serializable
data class FriendsMapDto(val me: MapPointDto?, val people: List<MapPersonDto>)

@Serializable
private data class EnableBody(val enabled: Boolean, val consentVersion: Int, val precision: String)

@Serializable
private data class DisableBody(val enabled: Boolean)

@Serializable
private data class PointBody(val lat: Double, val lng: Double, val accuracyM: Double, val capturedAt: String, val precision: String)

/** Location sharing with the administrator (docs/14, docs/11; owner decision D-06). */
class LocationApi(private val http: HttpJson, private val caller: AuthorizedCaller) {
    suspend fun consent(): ApiResult<ConsentDto> =
        caller.call { http.get("api/v1/consents/location-sharing", ConsentDto.serializer(), it) }

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

    suspend fun connectionsMap(): ApiResult<FriendsMapDto> =
        caller.call { http.get("api/v1/location-sharing/connections", FriendsMapDto.serializer(), it) }

    private fun precision(precise: Boolean) = if (precise) "precise" else "approximate"
}
