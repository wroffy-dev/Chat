package app.vaultchat.data.auth

import app.vaultchat.data.network.ApiResult
import app.vaultchat.domain.auth.AuthRepository
import app.vaultchat.domain.auth.AuthTokens
import app.vaultchat.domain.auth.ChangePasswordFailure
import app.vaultchat.domain.auth.ChangePasswordResult
import app.vaultchat.domain.auth.LoginFailure
import app.vaultchat.domain.auth.LoginResult
import app.vaultchat.domain.auth.MeResult
import app.vaultchat.domain.auth.SessionUser
import app.vaultchat.domain.auth.StoredSession
import app.vaultchat.domain.auth.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Talks to the backend auth endpoints and keeps the session in [store]. Expired access tokens are
 * refreshed once, transparently; a refresh that fails ends the local session.
 */
class RemoteAuthRepository(
    private val api: AuthApi,
    private val store: TokenStore,
    private val device: DeviceDto,
) : AuthRepository {
    private val refreshLock = Mutex()

    override fun currentUser(): SessionUser? = store.load()?.user

    override suspend fun login(username: String, password: String): LoginResult =
        when (val r = api.login(username, password, device)) {
            is ApiResult.Ok -> {
                save(r.value)
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
        when (val r = authorized { token -> api.changePassword(token, currentPassword, newPassword) }) {
            is ApiResult.Ok -> {
                save(r.value)
                ChangePasswordResult.Success
            }
            is ApiResult.HttpError -> ChangePasswordResult.Failure(
                when {
                    r.status == 401 && r.code == "INVALID_CREDENTIALS" -> ChangePasswordFailure.WRONG_CURRENT_PASSWORD
                    r.status == 422 -> ChangePasswordFailure.WEAK_PASSWORD
                    r.status == 429 -> ChangePasswordFailure.RATE_LIMITED
                    endsSession(r) -> ChangePasswordFailure.SESSION_ENDED.also { clear() }
                    else -> ChangePasswordFailure.UNEXPECTED
                },
            )
            ApiResult.NetworkError -> ChangePasswordResult.Failure(ChangePasswordFailure.NETWORK)
        }

    override suspend fun me(): MeResult {
        val session = load() ?: return MeResult.SessionEnded
        return when (val r = authorized { token -> api.me(token) }) {
            is ApiResult.Ok -> {
                val user = SessionUser(r.value.id, r.value.username, r.value.mustChangePassword)
                (load() ?: session).let { save(it.copy(user = user)) }
                MeResult.Success(user)
            }
            is ApiResult.HttpError -> when {
                r.status == 403 && r.code == "PASSWORD_CHANGE_REQUIRED" -> MeResult.PasswordChangeRequired
                endsSession(r) -> MeResult.SessionEnded.also { clear() }
                else -> MeResult.Unexpected
            }
            ApiResult.NetworkError -> MeResult.Network
        }
    }

    override suspend fun logout() {
        val session = load() ?: return
        // Best effort: the local session is cleared even if the server cannot be reached.
        api.logout(session.tokens.accessToken)
        clear()
    }

    /**
     * Runs [call] with the current access token. On TOKEN_EXPIRED, refreshes (one refresh at a time)
     * and retries once.
     */
    private suspend fun <T> authorized(call: suspend (String) -> ApiResult<T>): ApiResult<T> {
        val session = load() ?: return ApiResult.HttpError(401, "SESSION_REVOKED", null)
        val first = call(session.tokens.accessToken)
        if (!(first is ApiResult.HttpError && first.status == 401 && first.code == "TOKEN_EXPIRED")) return first

        val token = refreshLock.withLock {
            val latest = load() ?: return ApiResult.HttpError(401, "SESSION_REVOKED", null)
            // Another caller may have refreshed while we waited for the lock.
            if (latest.tokens.accessToken != session.tokens.accessToken) {
                latest.tokens.accessToken
            } else {
                when (val r = api.refresh(latest.tokens.refreshToken)) {
                    is ApiResult.Ok -> save(r.value).tokens.accessToken
                    is ApiResult.HttpError -> {
                        if (endsSession(r)) clear()
                        return r
                    }
                    ApiResult.NetworkError -> return ApiResult.NetworkError
                }
            }
        }
        return call(token)
    }

    private fun endsSession(r: ApiResult.HttpError) =
        r.status == 401 || (r.status == 403 && r.code == "ACCOUNT_DISABLED")

    private suspend fun load(): StoredSession? = withContext(Dispatchers.IO) { store.load() }

    private suspend fun clear() = withContext(Dispatchers.IO) { store.clear() }

    private suspend fun save(t: TokenResponse): StoredSession {
        val session = StoredSession(
            tokens = AuthTokens(t.accessToken, t.refreshToken, Instant.parse(t.accessExpiresAt).toEpochMilli()),
            user = SessionUser(t.user.id, t.user.username, t.user.mustChangePassword),
        )
        save(session)
        return session
    }

    private suspend fun save(session: StoredSession) = withContext(Dispatchers.IO) { store.save(session) }
}
