package app.vaultchat.domain

import app.vaultchat.domain.connection.QrPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrPayloadTest {
    private val token = "vcq_" + "aB3_-".repeat(8) + "xyz"

    @Test
    fun `accepts only the app's own connect format`() {
        assertEquals(43, token.length - 4)
        assertEquals(token, QrPayload.tokenFrom("vaultchat://connect?v=1&t=$token"))
        assertEquals(token, QrPayload.tokenFrom("  vaultchat://connect?v=1&t=$token\n"))
    }

    @Test
    fun `rejects links, other apps' codes and malformed tokens`() {
        assertNull(QrPayload.tokenFrom("https://example.com/?t=$token"))
        assertNull(QrPayload.tokenFrom("vaultchat://connect?v=2&t=$token"))
        assertNull(QrPayload.tokenFrom("vaultchat://connect?v=1&t=vcq_short"))
        assertNull(QrPayload.tokenFrom("vaultchat://connect?v=1&t=$token&extra=1"))
        assertNull(QrPayload.tokenFrom("WIFI:S:home;T:WPA;P:secret;;"))
    }
}
