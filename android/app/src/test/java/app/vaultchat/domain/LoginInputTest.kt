package app.vaultchat.domain

import app.vaultchat.domain.auth.LoginInput
import app.vaultchat.domain.auth.PasswordError
import app.vaultchat.domain.auth.UsernameError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginInputTest {
    @Test
    fun `valid input has no errors`() {
        val errors = LoginInput.validate("alice", "correct horse battery")
        assertTrue(errors.isValid)
    }

    @Test
    fun `username is trimmed and lowercased before validation`() {
        assertEquals("alice.b", LoginInput.normalizeUsername("  Alice.B "))
        assertNull(LoginInput.validate("  Alice.B ", "x").username)
    }

    @Test
    fun `empty fields are required`() {
        val errors = LoginInput.validate("   ", "")
        assertEquals(UsernameError.REQUIRED, errors.username)
        assertEquals(PasswordError.REQUIRED, errors.password)
    }

    @Test
    fun `username format matches the backend rules`() {
        assertEquals(UsernameError.INVALID_FORMAT, LoginInput.validate("ab", "x").username)
        assertEquals(UsernameError.INVALID_FORMAT, LoginInput.validate("a".repeat(33), "x").username)
        assertEquals(UsernameError.INVALID_FORMAT, LoginInput.validate("alice smith", "x").username)
        assertEquals(UsernameError.INVALID_FORMAT, LoginInput.validate("alice@home", "x").username)
        assertNull(LoginInput.validate("a_b-c.9", "x").username)
        assertNull(LoginInput.validate("a".repeat(32), "x").username)
    }

    @Test
    fun `password length counts code points, max 128`() {
        assertNull(LoginInput.validate("alice", "p".repeat(128)).password)
        assertEquals(PasswordError.TOO_LONG, LoginInput.validate("alice", "p".repeat(129)).password)
        // 128 emoji are 256 UTF-16 chars but only 128 code points.
        assertNull(LoginInput.validate("alice", "🔒".repeat(128)).password)
    }

    @Test
    fun `password is not trimmed`() {
        assertNull(LoginInput.validate("alice", " ").password)
    }
}
