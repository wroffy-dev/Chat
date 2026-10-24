package app.vaultchat.data

import app.vaultchat.data.auth.AuthApi
import app.vaultchat.data.auth.DeviceDto
import app.vaultchat.data.auth.RemoteAuthRepository
import app.vaultchat.data.network.HttpJson
import app.vaultchat.domain.auth.ChangePasswordFailure
import app.vaultchat.domain.auth.ChangePasswordResult
import app.vaultchat.domain.auth.LoginFailure
import app.vaultchat.domain.auth.LoginResult
import app.vaultchat.domain.auth.MeResult
import app.vaultchat.domain.auth.StoredSession
import app.vaultchat.domain.auth.TokenStore
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryTokenStore : TokenStore {
    var session: StoredSession? = null
    override fun load() = session
    override fun save(session: StoredSession) { this.session = session }
    override fun clear() { session = null }
}

/** Scripted fake backend: each request is answered by the next handler for its path. */
class FakeBackend {
    data class Seen(val path: String, val auth: String?, val body: String)
    val seen = mutableListOf<Seen>()
    private val handlers = mutableMapOf<String, ArrayDeque<Pair<Int, String>>>()

    fun on(path: String, status: Int, body: String) {
        handlers.getOrPut(path) { ArrayDeque() }.addLast(status to body)
    }

    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val req = chain.request()
        val body = req.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() } ?: ""
        seen += Seen(req.url.encodedPath, req.header("Authorization"), body)
        val (status, json) = handlers[req.url.encodedPath]?.removeFirstOrNull() ?: (500 to "{}")
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(status).message("x")
            .body(json.toResponseBody("application/json".toMediaType())).build()
    }).build()
}

private fun tokens(access: String, refresh: String, mustChange: Boolean = false) =
    """{"accessToken":"$access","accessExpiresAt":"2030-01-01T00:00:00.000Z","refreshToken":"$refresh",
       "user":{"id":"u1","username":"alice","mustChangePassword":$mustChange}}"""

private fun error(code: String) = """{"error":{"code":"$code","message":"m","requestId":"r"}}"""

class RemoteAuthRepositoryTest {
    private val backend = FakeBackend()
    private val store = InMemoryTokenStore()
    private val repo = RemoteAuthRepository(
        AuthApi(HttpJson("http://test.invalid/", backend.client)),
        store,
        DeviceDto("Pixel Test", 1),
    )

    @Test
    fun `successful login stores the session`() = runBlocking {
        backend.on("/api/v1/auth/login", 200, tokens("vca_1", "vcr_1", mustChange = true))
        assertEquals(LoginResult.Success(mustChangePassword = true), repo.login("alice", "pw"))
        assertEquals("vca_1", store.session!!.tokens.accessToken)
        assertEquals("alice", repo.currentUser()!!.username)
        assertTrue(backend.seen.single().body.contains("\"model\":\"Pixel Test\""))
    }

    @Test
    fun `login failures map to user-facing reasons and store nothing`() = runBlocking {
        backend.on("/api/v1/auth/login", 401, error("INVALID_CREDENTIALS"))
        backend.on("/api/v1/auth/login", 403, error("ACCOUNT_DISABLED"))
        backend.on("/api/v1/auth/login", 429, error("RATE_LIMITED"))
        assertEquals(LoginResult.Failure(LoginFailure.INVALID_CREDENTIALS), repo.login("a", "b"))
        assertEquals(LoginResult.Failure(LoginFailure.ACCOUNT_DISABLED), repo.login("a", "b"))
        assertEquals(LoginResult.Failure(LoginFailure.RATE_LIMITED), repo.login("a", "b"))
        assertNull(store.session)
    }

    @Test
    fun `expired access token is refreshed once and the call retried`() = runBlocking {
        backend.on("/api/v1/auth/login", 200, tokens("vca_old", "vcr_old"))
        repo.login("alice", "pw")
        backend.on("/api/v1/me", 401, error("TOKEN_EXPIRED"))
        backend.on("/api/v1/auth/refresh", 200, tokens("vca_new", "vcr_new"))
        backend.on("/api/v1/me", 200, """{"id":"u1","username":"alice","mustChangePassword":false}""")

        assertTrue(repo.me() is MeResult.Success)
        val calls = backend.seen.map { it.path to it.auth }
        assertEquals("Bearer vca_old", calls[1].second)
        assertTrue(backend.seen[2].body.contains("vcr_old"))
        assertEquals("Bearer vca_new", calls[3].second)
        assertEquals("vcr_new", store.session!!.tokens.refreshToken)
    }

    @Test
    fun `failed refresh ends the local session`() = runBlocking {
        backend.on("/api/v1/auth/login", 200, tokens("vca_1", "vcr_1"))
        repo.login("alice", "pw")
        backend.on("/api/v1/me", 401, error("TOKEN_EXPIRED"))
        backend.on("/api/v1/auth/refresh", 401, error("SESSION_REVOKED"))
        assertEquals(MeResult.SessionEnded, repo.me())
        assertNull(store.session)
    }

    @Test
    fun `revoked session clears local state`() = runBlocking {
        backend.on("/api/v1/auth/login", 200, tokens("vca_1", "vcr_1"))
        repo.login("alice", "pw")
        backend.on("/api/v1/me", 401, error("SESSION_REVOKED"))
        assertEquals(MeResult.SessionEnded, repo.me())
        assertNull(repo.currentUser())
    }

    @Test
    fun `restricted session reports that a password change is required`() = runBlocking {
        backend.on("/api/v1/auth/login", 200, tokens("vca_1", "vcr_1", mustChange = true))
        repo.login("alice", "pw")
        backend.on("/api/v1/me", 403, error("PASSWORD_CHANGE_REQUIRED"))
        assertEquals(MeResult.PasswordChangeRequired, repo.me())
        assertTrue(store.session != null)
    }

    @Test
    fun `change password stores rotated tokens and maps failures`() = runBlocking {
        backend.on("/api/v1/auth/login", 200, tokens("vca_1", "vcr_1", mustChange = true))
        repo.login("alice", "temp")
        backend.on("/api/v1/auth/change-password", 401, error("INVALID_CREDENTIALS"))
        backend.on("/api/v1/auth/change-password", 422, error("WEAK_PASSWORD"))
        backend.on("/api/v1/auth/change-password", 200, tokens("vca_2", "vcr_2"))
        assertEquals(ChangePasswordResult.Failure(ChangePasswordFailure.WRONG_CURRENT_PASSWORD), repo.changePassword("x", "y"))
        assertTrue(store.session != null) // a wrong current password must not log the user out
        assertEquals(ChangePasswordResult.Failure(ChangePasswordFailure.WEAK_PASSWORD), repo.changePassword("temp", "short"))
        assertEquals(ChangePasswordResult.Success, repo.changePassword("temp", "a better passphrase"))
        assertEquals("vca_2", store.session!!.tokens.accessToken)
        assertEquals(false, repo.currentUser()!!.mustChangePassword)
    }

    @Test
    fun `logout clears locally even when the server is unreachable`() = runBlocking {
        backend.on("/api/v1/auth/login", 200, tokens("vca_1", "vcr_1"))
        repo.login("alice", "pw")
        backend.on("/api/v1/auth/logout", 503, "")
        repo.logout()
        assertNull(store.session)
        assertEquals("Bearer vca_1", backend.seen.last().auth)
    }
}
