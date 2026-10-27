package app.vaultchat.data

import app.vaultchat.data.qr.QrCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrCodecTest {
    private val payload = "vaultchat://connect?v=1&t=vcq_" + "Q".repeat(43)

    /** Renders the matrix like a camera would see it: 6 px per module, with row padding. */
    private fun frame(scale: Int, padding: Int): Triple<ByteArray, Int, Int> {
        val m = QrCodec.encode(payload, quietZoneModules = 4)
        val w = m.size * scale
        val stride = w + padding
        val y = ByteArray(stride * w) { 0xFF.toByte() }
        for (row in 0 until w) for (col in 0 until w) {
            if (m[col / scale, row / scale]) y[row * stride + col] = 0
        }
        return Triple(y, w, stride)
    }

    @Test
    fun `encoded code decodes back to the same text`() {
        val (y, w, stride) = frame(scale = 6, padding = 0)
        assertEquals(payload, QrCodec.decodeLuminance(y, w, w, stride))
    }

    @Test
    fun `decodes padded camera rows`() {
        val (y, w, stride) = frame(scale = 5, padding = 32)
        assertEquals(payload, QrCodec.decodeLuminance(y, w, w, stride))
    }

    @Test
    fun `returns null for a blank frame`() {
        assertNull(QrCodec.decodeLuminance(ByteArray(200 * 200) { 0x80.toByte() }, 200, 200))
    }
}
