package app.vaultchat.data.push

import app.vaultchat.data.auth.AuthorizedCaller
import app.vaultchat.data.network.ApiResult
import app.vaultchat.data.network.HttpJson
import kotlinx.serialization.Serializable

@Serializable
data class PushTokenBody(val fcmToken: String)

/** Registers this device's FCM token on the current session (docs/10-notifications.md §3). */
class PushTokenApi(private val http: HttpJson, private val caller: AuthorizedCaller) {
    suspend fun register(fcmToken: String): Boolean =
        caller.call<Unit> { http.put<PushTokenBody, Unit>("api/v1/devices/push-token", PushTokenBody(fcmToken), PushTokenBody.serializer(), null, it) } is ApiResult.Ok

    suspend fun clear(): Boolean = caller.call<Unit> { http.delete("api/v1/devices/push-token", it) } is ApiResult.Ok
}
