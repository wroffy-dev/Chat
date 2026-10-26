package app.vaultchat.data

import app.vaultchat.data.network.ApiClient
import com.sun.net.httpserver.HttpServer
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

/** The app tells the server its version, and notices when the server says it is too old. */
class AppVersionHeaderTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private var seenVersion: String? = null
    private var status = 200

    init {
        server.createContext("/") { exchange ->
            seenVersion = exchange.requestHeaders.getFirst("X-App-Version")
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun get(client: okhttp3.OkHttpClient) =
        client.newCall(Request.Builder().url("http://127.0.0.1:${server.address.port}/api/v1/me").build()).execute().close()

    @Test
    fun `every request carries the version code`() {
        var upgrade = false
        get(ApiClient.defaultHttpClient(42) { upgrade = true })
        assertEquals("42", seenVersion)
        assertFalse(upgrade)
    }

    @Test
    fun `a 426 answer reports that an update is required`() {
        var upgrade = false
        status = 426
        get(ApiClient.defaultHttpClient(1) { upgrade = true })
        assertTrue(upgrade)
    }
}
