package app.vaultchat.domain.auth

sealed interface LoginResult {
    data object Success : LoginResult
    data class Failure(val reason: LoginFailure) : LoginResult
}

enum class LoginFailure {
    /** This build has no working sign-in yet (backend accounts arrive in Phase 2). */
    NOT_AVAILABLE,
    INVALID_CREDENTIALS,
    RATE_LIMITED,
    NETWORK,
    UNEXPECTED,
}

interface AuthRepository {
    suspend fun login(username: String, password: String): LoginResult
}
