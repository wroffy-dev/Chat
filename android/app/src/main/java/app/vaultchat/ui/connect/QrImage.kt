package app.vaultchat.ui.connect

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.vaultchat.data.qr.QrCodec

/** Draws a QR code. Always black on white, even in dark theme, so cameras read it reliably. */
@Composable
fun QrImage(payload: String, description: String, size: Dp = 260.dp, modifier: Modifier = Modifier) {
    val matrix = remember(payload) { QrCodec.encode(payload) }
    Box(
        modifier = modifier
            .background(Color.White, RoundedCornerShape(12.dp))
            .padding(8.dp)
            .semantics { contentDescription = description },
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val cell = this.size.width / matrix.size
            for (y in 0 until matrix.size) {
                for (x in 0 until matrix.size) {
                    if (matrix[x, y]) {
                        // Slight overlap avoids hairline gaps between modules.
                        drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                    }
                }
            }
        }
    }
}
