package app.vaultchat.domain

import app.vaultchat.domain.auth.NewPasswordError
import app.vaultchat.domain.auth.NewPasswordInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NewPasswordInputTest {
    @Test
    fun `accepts a long new password that matches its confirmation`() {
        assertNull(NewPasswordInput.validate("temp", "a long passphrase", "a long passphrase"))
    }

    @Test
    fun `reports the first problem`() {
        assertEquals(NewPasswordError.CURRENT_REQUIRED, NewPasswordInput.validate("", "a long passphrase", "a long passphrase"))
        assertEquals(NewPasswordError.TOO_SHORT, NewPasswordInput.validate("temp", "short", "short"))
        assertEquals(NewPasswordError.TOO_LONG, NewPasswordInput.validate("temp", "x".repeat(129), "x".repeat(129)))
        assertEquals(NewPasswordError.SAME_AS_CURRENT, NewPasswordInput.validate("same passphrase", "same passphrase", "same passphrase"))
        assertEquals(NewPasswordError.MISMATCH, NewPasswordInput.validate("temp", "a long passphrase", "a long passphrasf"))
    }
}
