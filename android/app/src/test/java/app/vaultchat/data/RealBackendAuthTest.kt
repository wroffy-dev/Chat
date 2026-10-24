package app.vaultchat.data

import app.vaultchat.data.auth.AuthApi
import app.vaultchat.data.auth.DeviceDto
import app.vaultchat.data.auth.RemoteAuthRepository
import app.vaultchat.data.network.ApiClient
import app.vaultchat.data.network.HttpJson
import app.vaultchat.domain.auth.ChangePasswordResult
import app.vaultchat.domain.auth.LoginResult
import app.vaultchat.domain.auth.MeResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * End-to-end against a real backend. Runs only when VAULTCHAT_TEST_API_URL, VAULTCHAT_TEST_USERNAME
 * and VAULTCHAT_TEST_TEMP_PASSWORD are set (a freshly created test user with a temporary password).
 */
class RealBackendAuthTest {
    @Test
    fun `full sign-in lifecycle against the real backend`() = runBlocking {
        val url = System.getenv("VAULTCHAT_TEST_API_URL")
        val username = System.getenv("VAULTCHAT_TEST_USERNAME")
        val temp = System.getenv("VAULTCHAT_TEST_TEMP_PASSWORD")
        assumeTrue("real backend not configured", !url.isNullOrBlank() && !username.isNullOrBlank() && !temp.isNullOrBlank())

        val store = InMemoryTokenStore()
        val repo = RemoteAuthRepository(AuthApi(HttpJson(url!!, ApiClient.defaultHttpClient())), store, DeviceDto("JVM test", 1))

        assertEquals(LoginResult.Success(mustChangePassword = true), repo.login(username!!, temp!!))
        assertEquals(MeResult.PasswordChangeRequired, repo.me())
        assertEquals(ChangePasswordResult.Success, repo.changePassword(temp, "jvm test passphrase 42"))
        val me = repo.me()
        assertTrue(me is MeResult.Success)
        assertEquals(username.lowercase(), (me as MeResult.Success).user.username)
        repo.logout()
        assertNull(store.session)
    }
}
