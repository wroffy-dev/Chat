package app.vaultchat.data.auth

import app.vaultchat.data.network.ApiResult
import app.vaultchat.domain.auth.AuthTokens
import app.vaultchat.domain.auth.SessionUser
import app.vaultchat.domain.auth.StoredSession
import app.vaultchat.domain.auth.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Runs authenticated API calls with the stored access token. On TOKEN_EXPIRED it refreshes once
 * (one refresh at a time across all callers) and retries; a refresh the server refuses ends the
 * local session. Shared by every repository that calls authenticated endpoints.
 */
class AuthorizedCaller(private val api: AuthApi, private val store: TokenStore) {
    private val refreshLock = Mutex()

    suspend fun <T> call(block: suspend (String) -> ApiResult<T>): ApiResult<T> {
        val session = load() ?: return SESSION_ENDED
        val first = block(session.tokens.accessToken)
        if (!(first is ApiResult.HttpError && first.status == 401 && first.code == "TOKEN_EXPIRED")) {
            if (first is ApiResult.HttpError && endsSession(first)) clear()
            return first
        }

        val token = refreshLock.withLock {
            val latest = load() ?: return SESSION_ENDED
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
        val second = block(token)
        if (second is ApiResult.HttpError && endsSession(second)) clear()
        return second
    }

    suspend fun load(): StoredSession? = withContext(Dispatchers.IO) { store.load() }

    suspend fun clear() = withContext(Dispatchers.IO) { store.clear() }

    suspend fun save(session: StoredSession) = withContext(Dispatchers.IO) { store.save(session) }

    suspend fun save(t: TokenResponse): StoredSession {
        val session = StoredSession(
            tokens = AuthTokens(t.accessToken, t.refreshToken, Instant.parse(t.accessExpiresAt).toEpochMilli()),
            user = SessionUser(t.user.id, t.user.username, t.user.mustChangePassword),
        )
        save(session)
        return session
    }

    companion object {
        val SESSION_ENDED = ApiResult.HttpError(401, "SESSION_REVOKED", null)

        /**
         * Whether a response means the session is over. A wrong *current password* on the
         * change-password endpoint is also a 401, but it must not log the user out.
         */
        fun endsSession(r: ApiResult.HttpError): Boolean =
            (r.status == 401 && r.code != "INVALID_CREDENTIALS" && r.code != "TOKEN_EXPIRED") ||
                (r.status == 403 && r.code == "ACCOUNT_DISABLED")
    }
}
