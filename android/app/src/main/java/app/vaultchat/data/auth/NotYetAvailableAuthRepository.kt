package app.vaultchat.data.auth

import app.vaultchat.domain.auth.AuthRepository
import app.vaultchat.domain.auth.LoginFailure
import app.vaultchat.domain.auth.LoginResult

/**
 * Phase 1 implementation. The backend has no account endpoints yet, so sign-in always reports
 * that it is unavailable. It never pretends to succeed and never stores or sends the password.
 * Replaced by a real implementation in Phase 2 (docs/IMPLEMENTATION_PLAN.md).
 */
class NotYetAvailableAuthRepository : AuthRepository {
    override suspend fun login(username: String, password: String): LoginResult =
        LoginResult.Failure(LoginFailure.NOT_AVAILABLE)
}
