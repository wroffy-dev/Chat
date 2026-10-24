package app.vaultchat.domain.auth

import app.vaultchat.domain.connection.Connection

/** The signed-in user as last confirmed by the server. */
data class SessionUser(val id: String, val username: String, val mustChangePassword: Boolean)

data class AuthTokens(val accessToken: String, val refreshToken: String, val accessExpiresAtEpochMs: Long)

data class StoredSession(val tokens: AuthTokens, val user: SessionUser)

/** Persists the session. The Android implementation encrypts it with a Keystore key. */
interface TokenStore {
    fun load(): StoredSession?
    fun save(session: StoredSession)
    fun clear()
}

sealed interface LoginResult {
    data class Success(val mustChangePassword: Boolean) : LoginResult
    data class Failure(val reason: LoginFailure) : LoginResult
}

enum class LoginFailure { INVALID_CREDENTIALS, ACCOUNT_DISABLED, RATE_LIMITED, NETWORK, UNEXPECTED }

sealed interface ChangePasswordResult {
    data object Success : ChangePasswordResult
    data class Failure(val reason: ChangePasswordFailure) : ChangePasswordResult
}

enum class ChangePasswordFailure { WRONG_CURRENT_PASSWORD, WEAK_PASSWORD, RATE_LIMITED, SESSION_ENDED, NETWORK, UNEXPECTED }

sealed interface MeResult {
    /** [connection] is null until the two users have paired. */
    data class Success(val user: SessionUser, val connection: Connection?) : MeResult
    data object PasswordChangeRequired : MeResult
    /** Logged out, revoked, expired or disabled: the stored session has been cleared. */
    data object SessionEnded : MeResult
    data object Network : MeResult
    data object Unexpected : MeResult
}

interface AuthRepository {
    /** The stored session's user, without a network call. Null when signed out. */
    fun currentUser(): SessionUser?
    suspend fun login(username: String, password: String): LoginResult
    suspend fun changePassword(currentPassword: String, newPassword: String): ChangePasswordResult
    suspend fun me(): MeResult
    /** Ends the session on the server when reachable; always clears it locally. */
    suspend fun logout()
}
