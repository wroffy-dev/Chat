package app.vaultchat.data.auth

import app.vaultchat.data.connection.ConnectionDto
import app.vaultchat.data.network.ApiResult
import app.vaultchat.data.network.HttpJson
import kotlinx.serialization.Serializable

@Serializable
data class DeviceDto(val model: String, val appVersionCode: Int)

@Serializable
data class LoginRequest(val username: String, val password: String, val device: DeviceDto)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)

@Serializable
class EmptyBody

@Serializable
data class UserDto(val id: String, val username: String, val mustChangePassword: Boolean)

@Serializable
data class TokenResponse(val accessToken: String, val accessExpiresAt: String, val refreshToken: String, val user: UserDto)

@Serializable
data class MeResponse(
    val id: String,
    val username: String,
    val mustChangePassword: Boolean,
    val connection: ConnectionDto? = null,
)

/** Typed calls for the auth endpoints and GET api/v1/me. Holds no state. */
class AuthApi(private val http: HttpJson) {
    suspend fun login(username: String, password: String, device: DeviceDto): ApiResult<TokenResponse> =
        http.post("api/v1/auth/login", LoginRequest(username, password, device), LoginRequest.serializer(), TokenResponse.serializer())

    suspend fun refresh(refreshToken: String): ApiResult<TokenResponse> =
        http.post("api/v1/auth/refresh", RefreshRequest(refreshToken), RefreshRequest.serializer(), TokenResponse.serializer())

    suspend fun logout(accessToken: String): ApiResult<Unit> =
        http.post("api/v1/auth/logout", EmptyBody(), EmptyBody.serializer(), null, accessToken)

    suspend fun changePassword(accessToken: String, current: String, new: String): ApiResult<TokenResponse> =
        http.post("api/v1/auth/change-password", ChangePasswordRequest(current, new), ChangePasswordRequest.serializer(), TokenResponse.serializer(), accessToken)

    suspend fun me(accessToken: String): ApiResult<MeResponse> =
        http.get("api/v1/me", MeResponse.serializer(), accessToken)
}
