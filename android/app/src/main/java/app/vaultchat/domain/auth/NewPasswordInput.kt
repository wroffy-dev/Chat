package app.vaultchat.domain.auth

enum class NewPasswordError { CURRENT_REQUIRED, TOO_SHORT, TOO_LONG, SAME_AS_CURRENT, MISMATCH }

/** Client-side mirror of the backend rule (min 12, max 128 code points). The server stays authoritative. */
object NewPasswordInput {
    const val MIN = 12
    const val MAX = 128

    fun validate(current: String, new: String, confirm: String): NewPasswordError? {
        val length = new.codePointCount(0, new.length)
        return when {
            current.isEmpty() -> NewPasswordError.CURRENT_REQUIRED
            length < MIN -> NewPasswordError.TOO_SHORT
            length > MAX -> NewPasswordError.TOO_LONG
            new == current -> NewPasswordError.SAME_AS_CURRENT
            new != confirm -> NewPasswordError.MISMATCH
            else -> null
        }
    }
}
