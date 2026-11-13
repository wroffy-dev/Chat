package app.vaultchat.platform.call

import io.livekit.android.room.track.video.ChainVideoProcessor
import livekit.org.webrtc.VideoFrame

/**
 * Digital zoom of the sent camera video (owner request 2026-11-03): each frame's middle part is cut out
 * and enlarged back to the full size before it is encoded, so the other person (and the self-view) see
 * the zoomed picture. It works on every phone because it does not use the camera's own zoom, which
 * LiveKit does not expose; above 1x the picture is softer, as with any digital zoom.
 *
 * [level] is read for every frame on the camera thread; 1 = no zoom.
 */
class ZoomVideoProcessor(private val level: () -> Float) : ChainVideoProcessor() {
    override fun onFrameCaptured(frame: VideoFrame) {
        val zoom = level()
        if (zoom <= 1.01f) {
            continueChain(frame)
            return
        }
        val buffer = frame.buffer
        val width = buffer.width
        val height = buffer.height
        // Even sizes and offsets: the encoder's chroma planes are half size.
        val cropWidth = even((width / zoom).toInt()).coerceIn(2, width)
        val cropHeight = even((height / zoom).toInt()).coerceIn(2, height)
        val x = even((width - cropWidth) / 2)
        val y = even((height - cropHeight) / 2)
        val zoomed = VideoFrame(buffer.cropAndScale(x, y, cropWidth, cropHeight, width, height), frame.rotation, frame.timestampNs)
        continueChain(zoomed)
        zoomed.release()
    }

    private fun even(value: Int): Int = value and 1.inv()
}
