package app.vaultchat.domain.auth

/** Field-level problems with the login form. Mapped to user-facing text by the UI layer. */
enum class UsernameError { REQUIRED, INVALID_FORMAT }
enum class PasswordError { REQUIRED, TOO_LONG }

data class LoginInputErrors(
    val username: UsernameError? = null,
    val password: PasswordError? = null,
) {
    val isValid: Boolean get() = username == null && password == null
}

/**
 * Client-side checks only, to give fast feedback. The backend remains the authority
 * (docs/04-accounts-and-authentication.md §3).
 */
object LoginInput {
    const val USERNAME_MIN = 3
    const val USERNAME_MAX = 32
    const val PASSWORD_MAX = 128

    private val usernamePattern = Regex("^[a-z0-9._-]{$USERNAME_MIN,$USERNAME_MAX}$")

    /** Usernames are case-insensitive and stored lowercase. */
    fun normalizeUsername(raw: String): String = raw.trim().lowercase()

    fun validate(rawUsername: String, password: String): LoginInputErrors {
        val username = normalizeUsername(rawUsername)
        val usernameError = when {
            username.isEmpty() -> UsernameError.REQUIRED
            !usernamePattern.matches(username) -> UsernameError.INVALID_FORMAT
            else -> null
        }
        val passwordError = when {
            password.isEmpty() -> PasswordError.REQUIRED
            password.codePointCount(0, password.length) > PASSWORD_MAX -> PasswordError.TOO_LONG
            else -> null
        }
        return LoginInputErrors(usernameError, passwordError)
    }
}
