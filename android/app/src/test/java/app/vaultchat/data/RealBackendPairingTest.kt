package app.vaultchat.data

import app.vaultchat.data.auth.AuthApi
import app.vaultchat.data.auth.AuthorizedCaller
import app.vaultchat.data.auth.DeviceDto
import app.vaultchat.data.auth.RemoteAuthRepository
import app.vaultchat.data.connection.ConnectionApi
import app.vaultchat.data.connection.RemoteConnectionRepository
import app.vaultchat.data.network.ApiClient
import app.vaultchat.data.network.HttpJson
import app.vaultchat.domain.auth.LoginResult
import app.vaultchat.domain.auth.MeResult
import app.vaultchat.domain.connection.ConnResult
import app.vaultchat.domain.connection.QrPayload
import app.vaultchat.domain.connection.RequestDirection
import app.vaultchat.domain.connection.RequestStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Two app users pair through the real backend using the app's own data layer. Opt-in: needs
 * VAULTCHAT_TEST_API_URL and two unconnected users with changed passwords in
 * VAULTCHAT_TEST_PAIR (format "userA:passA:userB:passB").
 */
class RealBackendPairingTest {
    private class Client(url: String) {
        val store = InMemoryTokenStore()
        private val http = HttpJson(url, ApiClient.defaultHttpClient())
        private val caller = AuthorizedCaller(AuthApi(http), store)
        val auth = RemoteAuthRepository(AuthApi(http), store, DeviceDto("JVM test", 1), caller)
        val connections = RemoteConnectionRepository(ConnectionApi(http), caller)
    }

    @Test
    fun `two users pair by QR through the real backend`() = runBlocking {
        val url = System.getenv("VAULTCHAT_TEST_API_URL")
        val pair = System.getenv("VAULTCHAT_TEST_PAIR")?.split(":")
        assumeTrue("real backend pairing not configured", !url.isNullOrBlank() && pair?.size == 4)
        val (userA, passA, userB, passB) = pair!!

        val a = Client(url!!)
        val b = Client(url)
        assertEquals(LoginResult.Success(false), a.auth.login(userA, passA))
        assertEquals(LoginResult.Success(false), b.auth.login(userB, passB))

        val qr = (a.connections.generateQr() as ConnResult.Ok).value
        // B "scans" the payload exactly as the camera would deliver it.
        val token = QrPayload.tokenFrom(qr.payload)!!
        val request = (b.connections.redeem(token) as ConnResult.Ok).value
        assertEquals(userA.lowercase(), request.peerUsername)

        val incoming = (a.connections.requests(RequestDirection.INCOMING, RequestStatus.PENDING) as ConnResult.Ok).value
        assertEquals(request.id, incoming.single().id)
        val connection = (a.connections.accept(incoming.single().id) as ConnResult.Ok).value
        assertEquals(userB.lowercase(), connection.peerUsername)

        val outgoing = (b.connections.requests(RequestDirection.OUTGOING) as ConnResult.Ok).value
        assertEquals(RequestStatus.ACCEPTED, outgoing.first { it.id == request.id }.status)
        val me = b.auth.me()
        assertTrue(me is MeResult.Success)
        assertEquals(userA.lowercase(), (me as MeResult.Success).connection!!.peerUsername)
    }
}
