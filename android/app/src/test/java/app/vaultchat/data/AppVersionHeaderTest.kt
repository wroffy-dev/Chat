package app.vaultchat.data

import app.vaultchat.data.network.ApiClient
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/** The app tells the server its version, and notices when the server says it is too old. */
class AppVersionHeaderTest {
    // A one-line HTTP server on a plain socket (Android unit tests have no JDK HTTP server).
    private val server = ServerSocket(0, 10, InetAddress.getLoopbackAddress())
    @Volatile private var seenVersion: String? = null
    @Volatile private var status = 200

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                socket.use { s ->
                    val reader = s.getInputStream().bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("X-App-Version:", ignoreCase = true)) seenVersion = line.substringAfter(":").trim()
                    }
                    s.getOutputStream().write("HTTP/1.1 $status X\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    s.getOutputStream().flush()
                }
            }
        }
    }

    @After
    fun stop() = server.close()

    private fun get(client: OkHttpClient) =
        client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/api/v1/me").build()).execute().close()

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
