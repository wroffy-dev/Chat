package app.vaultchat.platform.call

import io.livekit.android.audio.AudioProcessorInterface
import java.nio.ByteBuffer

/**
 * Removes steady background noise (fan, traffic, TV) from the sent voice with RNNoise, on the phone,
 * after WebRTC's own echo cancellation and noise suppression (docs/08-calling.md §8). Nothing leaves
 * the device. LiveKit hands over each 10 ms frame as 480 float samples at 48 kHz, processed in place.
 * Other rates or frame sizes pass through unchanged, as does everything when the library is missing.
 */
class RnNoiseProcessor : AudioProcessorInterface {
    @Volatile var enabled = true

    private val lock = Any()
    private var handle = 0L
    private var rate = 0

    override fun isEnabled(): Boolean = enabled && available

    override fun getName(): String = "rnnoise"

    override fun initializeAudioProcessing(sampleRateHz: Int, numChannels: Int) {
        synchronized(lock) {
            rate = sampleRateHz
            if (available && handle == 0L) handle = nativeCreate()
        }
    }

    override fun resetAudioProcessing(newRate: Int) {
        synchronized(lock) { rate = newRate }
    }

    override fun processAudio(numBands: Int, numFrames: Int, buffer: ByteBuffer) {
        if (!enabled) return
        synchronized(lock) {
            if (handle != 0L && rate == SAMPLE_RATE && numFrames == FRAME) nativeProcess(handle, buffer)
        }
    }

    /** Frees the native state; called when the call ends. */
    fun release() {
        synchronized(lock) {
            if (handle != 0L) nativeDestroy(handle)
            handle = 0L
        }
    }

    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val FRAME = 480

        /** False if the native library could not load (e.g. an unsupported CPU); calls then go on without it. */
        val available: Boolean = try {
            System.loadLibrary("vaultchat_rnnoise")
            true
        } catch (_: Throwable) {
            false
        }
    }

    // Instance methods (not companion statics) so the JNI names are unambiguous; the C side ignores `this`.
    private external fun nativeCreate(): Long
    private external fun nativeProcess(handle: Long, buffer: ByteBuffer)
    private external fun nativeDestroy(handle: Long)
}
