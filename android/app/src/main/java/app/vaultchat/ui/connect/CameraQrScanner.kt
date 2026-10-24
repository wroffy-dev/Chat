package app.vaultchat.ui.connect

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.vaultchat.data.qr.QrCodec
import java.util.concurrent.Executors

/**
 * Camera preview that decodes QR codes on-device. Frames are analysed in memory and discarded;
 * nothing is saved or uploaded. The camera is released when this leaves the composition.
 */
@Composable
fun CameraQrScanner(onDecoded: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnDecoded = rememberUpdatedState(onDecoded)
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()
            provider = cameraProvider
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor) { image ->
                try {
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    // The last row may be shorter than rowStride; pad so every row is addressable.
                    val data = ByteArray(plane.rowStride * image.height)
                    buffer.get(data, 0, minOf(buffer.remaining(), data.size))
                    QrCodec.decodeLuminance(data, image.width, image.height, plane.rowStride)?.let { text ->
                        ContextCompat.getMainExecutor(context).execute { latestOnDecoded.value(text) }
                    }
                } finally {
                    image.close()
                }
            }
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            provider?.unbindAll()
            analysisExecutor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}
