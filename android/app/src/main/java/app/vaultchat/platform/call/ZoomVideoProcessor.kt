package app.vaultchat.platform.call

import io.livekit.android.room.track.video.ChainVideoProcessor
import livekit.org.webrtc.VideoFrame
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The sent camera video's own adjustments, applied to every frame before it is encoded, so the other person
 * (and the self-view) see the result. It works on every phone because it does not need the camera's own
 * controls, which LiveKit does not expose.
 *
 * - Digital zoom (owner request 2026-11-03): each frame's middle part is cut out and enlarged back to the
 *   full size; above 1x the picture is softer, as with any digital zoom.
 * - Night mode (owner request 2026-11-21): the brightness of each pixel is lifted with a gamma curve, which
 *   makes dark parts much brighter and leaves bright parts almost as they were. Only brightness (Y) is changed;
 *   the picture gets some noise in very dark rooms, as on any phone's night view.
 *
 * [level] and [night] are read for every frame on the camera thread; 1 = no zoom.
 */
class ZoomVideoProcessor(private val level: () -> Float, private val night: () -> Boolean = { false }) : ChainVideoProcessor() {
    private var row = ByteArray(0)

    override fun onFrameCaptured(frame: VideoFrame) {
        val zoom = level()
        val dark = night()
        if (zoom <= 1.01f && !dark) {
            continueChain(frame)
            return
        }
        var buffer = frame.buffer
        // Set when [buffer] is a new buffer this processor must release.
        var owned = false
        if (zoom > 1.01f) {
            val width = buffer.width
            val height = buffer.height
            // Even sizes and offsets: the encoder's chroma planes are half size.
            val cropWidth = even((width / zoom).toInt()).coerceIn(2, width)
            val cropHeight = even((height / zoom).toInt()).coerceIn(2, height)
            val x = even((width - cropWidth) / 2)
            val y = even((height - cropHeight) / 2)
            buffer = buffer.cropAndScale(x, y, cropWidth, cropHeight, width, height)
            owned = true
        }
        if (dark) {
            val i420 = buffer.toI420()
            if (owned) buffer.release()
            if (i420 == null) {
                continueChain(frame)
                return
            }
            brighten(i420)
            buffer = i420
            owned = true
        }
        val out = VideoFrame(buffer, frame.rotation, frame.timestampNs)
        continueChain(out)
        if (owned) out.release()
    }

    /** Applies [NIGHT_CURVE] to the brightness plane, a row at a time. */
    private fun brighten(i420: VideoFrame.I420Buffer) {
        val width = i420.width
        val height = i420.height
        val stride = i420.strideY
        val plane = i420.dataY
        // A frame that cannot be changed is sent as it is.
        if (plane.isReadOnly) return
        if (row.size < width) row = ByteArray(width)
        for (r in 0 until height) {
            val start = r * stride
            plane.position(start)
            plane.get(row, 0, width)
            for (c in 0 until width) row[c] = NIGHT_CURVE[row[c].toInt() and 0xFF]
            plane.position(start)
            plane.put(row, 0, width)
        }
        plane.position(0)
    }

    private fun even(value: Int): Int = value and 1.inv()

    private companion object {
        /** Brightness in → out: a gamma of 0.55 (dark 40 → 92, middle 128 → 175, bright 230 stays near 240). */
        val NIGHT_CURVE = ByteArray(256) { i -> (255.0 * (i / 255.0).pow(0.55)).roundToInt().coerceIn(0, 255).toByte() }
    }
}
