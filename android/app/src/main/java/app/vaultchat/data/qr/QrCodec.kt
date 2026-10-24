package app.vaultchat.data.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** QR modules as a square grid: `true` = dark. One cell per module, including the quiet zone. */
class QrMatrix(val size: Int, private val cells: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = cells[y * size + x]
}

/** On-device QR encoding and decoding with ZXing. Nothing is sent to any scanning service. */
object QrCodec {
    fun encode(text: String, quietZoneModules: Int = 2): QrMatrix {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to quietZoneModules,
        )
        // Size 0 asks for the smallest matrix: one pixel per module.
        val bits = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val size = bits.width
        val cells = BooleanArray(size * size) { i -> bits.get(i % size, i / size) }
        return QrMatrix(size, cells)
    }

    /**
     * Decodes a QR code from a camera frame's luminance (Y) plane. Returns null when none is found.
     * [rowStride] may exceed [width] because camera buffers are often padded.
     */
    fun decodeLuminance(y: ByteArray, width: Int, height: Int, rowStride: Int = width): String? {
        val source = PlanarYUVLuminanceSource(y, rowStride, height, 0, 0, width, height, false)
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
        )
        return try {
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (_: NotFoundException) {
            null
        } catch (_: ReaderException) {
            null
        }
    }
}
