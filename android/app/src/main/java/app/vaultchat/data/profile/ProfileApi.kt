package app.vaultchat.data.profile

import app.vaultchat.data.auth.AuthorizedCaller
import app.vaultchat.data.network.ApiResult
import app.vaultchat.data.network.HttpJson
import kotlinx.serialization.Serializable

@Serializable
data class MyProfileDto(val id: String, val username: String, val avatar: String? = null)

@Serializable
data class AvatarDto(val avatar: String?)

@Serializable
data class NicknameDto(val nickname: String?)

/** The user's built-in avatar and their nicknames for the people they are connected with (D-32, docs/14 §profile). */
class ProfileApi(private val http: HttpJson, private val caller: AuthorizedCaller) {
    suspend fun me(): ApiResult<MyProfileDto> = caller.call { http.get("api/v1/me", MyProfileDto.serializer(), it) }

    /** [avatar]: a key of [app.vaultchat.ui.components.BuiltInAvatars], or null for the initial. */
    suspend fun setAvatar(avatar: String?): ApiResult<AvatarDto> =
        caller.call { http.put("api/v1/me/avatar", AvatarDto(avatar), AvatarDto.serializer(), AvatarDto.serializer(), it) }

    /** Null or blank removes the nickname; the chat gets a line saying the name changed. */
    suspend fun setNickname(connectionId: String, nickname: String?): ApiResult<NicknameDto> =
        caller.call {
            http.put("api/v1/connections/$connectionId/nickname", NicknameDto(nickname), NicknameDto.serializer(), NicknameDto.serializer(), it)
        }

    companion object {
        /** Longest nickname (backend/src/profile/service.ts NICKNAME_MAX). */
        const val NICKNAME_MAX = 40
    }
}
