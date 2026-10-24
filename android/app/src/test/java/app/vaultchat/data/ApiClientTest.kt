package app.vaultchat.data

import app.vaultchat.data.auth.NotYetAvailableAuthRepository
import app.vaultchat.data.network.ApiClient
import app.vaultchat.data.network.ServerHealth
import app.vaultchat.domain.auth.LoginFailure
import app.vaultchat.domain.auth.LoginResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

class ApiClientTest {
    private val client = ApiClient("http://127.0.0.1:1/", ApiClient.defaultHttpClient())

    @Test
    fun `parses the standard error body`() {
        val body = """{"error":{"code":"NOT_FOUND","message":"Not found.","requestId":"abc"}}"""
        val parsed = client.parseError(body)!!
        assertEquals("NOT_FOUND", parsed.code)
        assertEquals("abc", parsed.requestId)
    }

    @Test
    fun `ignores bodies that are not standard errors`() {
        assertNull(client.parseError(null))
        assertNull(client.parseError(""))
        assertNull(client.parseError("<html>bad gateway</html>"))
        assertNull(client.parseError("""{"status":"ok"}"""))
    }

    @Test
    fun `unreachable server is reported, not thrown`() = runBlocking {
        assertEquals(ServerHealth.Unreachable, client.checkHealth())
    }

    /** Runs against a real backend when VAULTCHAT_TEST_API_URL is set (e.g. http://127.0.0.1:3000/). */
    @Test
    fun `real backend health check`() = runBlocking {
        val url = System.getenv("VAULTCHAT_TEST_API_URL")
        assumeTrue("VAULTCHAT_TEST_API_URL not set", !url.isNullOrBlank())
        assertEquals(ServerHealth.Reachable, ApiClient(url!!, ApiClient.defaultHttpClient()).checkHealth())
    }

    @Test
    fun `phase 1 sign-in never reports success`() = runBlocking {
        val result = NotYetAvailableAuthRepository().login("alice", "anything")
        assertEquals(LoginResult.Failure(LoginFailure.NOT_AVAILABLE), result)
    }
}
