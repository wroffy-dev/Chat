package app.vaultchat.data

import app.vaultchat.data.auth.AuthApi
import app.vaultchat.data.auth.AuthorizedCaller
import app.vaultchat.data.connection.ConnectionApi
import app.vaultchat.data.connection.RemoteConnectionRepository
import app.vaultchat.data.network.HttpJson
import app.vaultchat.domain.auth.AuthTokens
import app.vaultchat.domain.auth.SessionUser
import app.vaultchat.domain.auth.StoredSession
import app.vaultchat.domain.connection.ConnResult
import app.vaultchat.domain.connection.ConnectionFailure
import app.vaultchat.domain.connection.RequestDirection
import app.vaultchat.domain.connection.RequestStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteConnectionRepositoryTest {
    private val backend = FakeBackend()
    private val store = InMemoryTokenStore().apply {
        session = StoredSession(AuthTokens("vca_1", "vcr_1", 0), SessionUser("u1", "alice", false))
    }
    private val http = HttpJson("http://test.invalid/", backend.client)
    private val repo = RemoteConnectionRepository(ConnectionApi(http), AuthorizedCaller(AuthApi(http), store))

    private fun error(code: String) = """{"error":{"code":"$code","message":"m","requestId":"r"}}"""

    @Test
    fun `generates a QR code with the access token`() = runBlocking {
        backend.on("/api/v1/connection-qr", 201, """{"token":"vcq_x","qrPayload":"vaultchat://connect?v=1&t=vcq_x","expiresAt":"2030-01-01T00:00:00Z"}""")
        val r = repo.generateQr()
        assertTrue(r is ConnResult.Ok)
        assertEquals("vaultchat://connect?v=1&t=vcq_x", (r as ConnResult.Ok).value.payload)
        assertEquals("Bearer vca_1", backend.seen.single().auth)
    }

    @Test
    fun `maps server error codes`() = runBlocking {
        backend.on("/api/v1/connection-requests", 410, error("QR_INVALID_OR_EXPIRED"))
        backend.on("/api/v1/connection-requests", 409, error("REQUEST_ALREADY_PENDING"))
        backend.on("/api/v1/connection-requests", 409, error("ALREADY_CONNECTED"))
        assertEquals(ConnResult.Failure(ConnectionFailure.QR_INVALID), repo.redeem("vcq_t"))
        assertEquals(ConnResult.Failure(ConnectionFailure.ALREADY_PENDING), repo.redeem("vcq_t"))
        assertEquals(ConnResult.Failure(ConnectionFailure.ALREADY_CONNECTED), repo.redeem("vcq_t"))
    }

    @Test
    fun `lists requests with query filters and parses them`() = runBlocking {
        backend.on(
            "/api/v1/connection-requests",
            200,
            """{"items":[{"id":"r1","direction":"incoming","peer":{"username":"bob"},"status":"pending",
               "createdAt":"2030-01-01T00:00:00Z","expiresAt":"2030-01-01T00:05:00Z"}]}""",
        )
        val r = repo.requests(RequestDirection.INCOMING, RequestStatus.PENDING) as ConnResult.Ok
        assertEquals("bob", r.value.single().peerUsername)
        assertEquals(RequestDirection.INCOMING, r.value.single().direction)
        assertEquals(RequestStatus.PENDING, r.value.single().status)
    }

    @Test
    fun `no connection yet is an empty result, not an error`() = runBlocking {
        backend.on("/api/v1/connections", 200, """{"items":[]}""")
        assertEquals(emptyList<Any>(), (repo.connections() as ConnResult.Ok).value)
    }

    @Test
    fun `the chat list carries every connection with its unread count`() = runBlocking {
        backend.on(
            "/api/v1/connections",
            200,
            """{"items":[
               {"id":"c1","peer":{"id":"u2","username":"bob"},"conversationId":"v1","createdAt":"2030-01-01T00:00:00Z",
                "unreadCount":0,"lastMessageAt":null},
               {"id":"c2","peer":{"id":"u3","username":"carol"},"conversationId":"v2","createdAt":"2030-01-02T00:00:00Z",
                "unreadCount":3,"lastMessageAt":"2030-01-03T00:00:00Z"}]}""",
        )
        val list = (repo.connections() as ConnResult.Ok).value
        assertEquals(listOf("bob", "carol"), list.map { it.peerUsername })
        assertEquals(3, list[1].unreadCount)
        assertNull(list[0].lastMessageAtEpochMs)
        assertEquals(java.time.Instant.parse("2030-01-03T00:00:00Z").toEpochMilli(), list[1].lastMessageAtEpochMs)
    }

    @Test
    fun `a revoked session ends the local session`() = runBlocking {
        backend.on("/api/v1/connections", 401, error("SESSION_REVOKED"))
        assertEquals(ConnResult.Failure(ConnectionFailure.SESSION_ENDED), repo.connections())
        assertNull(store.session)
    }
}
