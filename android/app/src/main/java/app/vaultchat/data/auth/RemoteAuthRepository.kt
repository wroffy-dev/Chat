package app.vaultchat.data.auth

import app.vaultchat.data.connection.toDomain
import app.vaultchat.data.network.ApiResult
import app.vaultchat.domain.auth.AuthRepository
import app.vaultchat.domain.auth.ChangePasswordFailure
import app.vaultchat.domain.auth.ChangePasswordResult
import app.vaultchat.domain.auth.LoginFailure
import app.vaultchat.domain.auth.LoginResult
import app.vaultchat.domain.auth.MeResult
import app.vaultchat.domain.auth.SessionUser
import app.vaultchat.domain.auth.TokenStore

/** Talks to the backend auth endpoints and keeps the session in the [TokenStore]. */
class RemoteAuthRepository(
    private val api: AuthApi,
    private val store: TokenStore,
    private val device: DeviceDto,
    private val caller: AuthorizedCaller = AuthorizedCaller(api, store),
) : AuthRepository {

    override fun currentUser(): SessionUser? = store.load()?.user

    override suspend fun login(username: String, password: String): LoginResult =
        when (val r = api.login(username, password, device)) {
            is ApiResult.Ok -> {
                caller.save(r.value)
                LoginResult.Success(r.value.user.mustChangePassword)
            }
            is ApiResult.HttpError -> LoginResult.Failure(
                when {
                    r.status == 401 -> LoginFailure.INVALID_CREDENTIALS
                    r.status == 403 && r.code == "ACCOUNT_DISABLED" -> LoginFailure.ACCOUNT_DISABLED
                    r.status == 429 -> LoginFailure.RATE_LIMITED
                    else -> LoginFailure.UNEXPECTED
                },
            )
            ApiResult.NetworkError -> LoginResult.Failure(LoginFailure.NETWORK)
        }

    override suspend fun changePassword(currentPassword: String, newPassword: String): ChangePasswordResult =
        when (val r = caller.call { token -> api.changePassword(token, currentPassword, newPassword) }) {
            is ApiResult.Ok -> {
                caller.save(r.value)
                ChangePasswordResult.Success
            }
            is ApiResult.HttpError -> ChangePasswordResult.Failure(
                when {
                    r.status == 401 && r.code == "INVALID_CREDENTIALS" -> ChangePasswordFailure.WRONG_CURRENT_PASSWORD
                    r.status == 422 -> ChangePasswordFailure.WEAK_PASSWORD
                    r.status == 429 -> ChangePasswordFailure.RATE_LIMITED
                    AuthorizedCaller.endsSession(r) -> ChangePasswordFailure.SESSION_ENDED
                    else -> ChangePasswordFailure.UNEXPECTED
                },
            )
            ApiResult.NetworkError -> ChangePasswordResult.Failure(ChangePasswordFailure.NETWORK)
        }

    override suspend fun me(): MeResult {
        val session = caller.load() ?: return MeResult.SessionEnded
        return when (val r = caller.call { token -> api.me(token) }) {
            is ApiResult.Ok -> {
                val user = SessionUser(r.value.id, r.value.username, r.value.mustChangePassword)
                (caller.load() ?: session).let { caller.save(it.copy(user = user)) }
                MeResult.Success(user, r.value.connection?.toDomain())
            }
            is ApiResult.HttpError -> when {
                r.status == 403 && r.code == "PASSWORD_CHANGE_REQUIRED" -> MeResult.PasswordChangeRequired
                AuthorizedCaller.endsSession(r) -> MeResult.SessionEnded
                else -> MeResult.Unexpected
            }
            ApiResult.NetworkError -> MeResult.Network
        }
    }

    override suspend fun logout() {
        val session = caller.load() ?: return
        // Best effort: the local session is cleared even if the server cannot be reached.
        api.logout(session.tokens.accessToken)
        caller.clear()
    }
}
