package app.vaultchat.domain.call

import kotlinx.coroutines.flow.Flow

enum class CallKind { VIDEO, VOICE }

enum class CallStatus { RINGING, ACCEPTED, ACTIVE, ENDED, DECLINED, CANCELLED, MISSED, FAILED }

data class CallInfo(
    val id: String,
    val kind: CallKind,
    val status: CallStatus,
    val isCaller: Boolean,
    val peerUsername: String,
)

data class JoinInfo(val callId: String, val livekitUrl: String, val livekitToken: String, val ringTimeoutSeconds: Int)

enum class CallFailure { NOT_FOUND, IN_PROGRESS, NOT_ACTIVE, MEDIA_UNAVAILABLE, RATE_LIMITED, SESSION_ENDED, NETWORK, UNEXPECTED }

sealed interface CallResult<out T> {
    data class Ok<T>(val value: T) : CallResult<T>
    data class Failure(val reason: CallFailure) : CallResult<Nothing>
}

interface CallRepository {
    suspend fun start(connectionId: String, kind: CallKind): CallResult<JoinInfo>
    suspend fun current(): CallResult<CallInfo?>
    suspend fun get(callId: String): CallResult<CallInfo>
    suspend fun accept(callId: String): CallResult<JoinInfo>
    suspend fun decline(callId: String): CallResult<Unit>
    suspend fun cancel(callId: String): CallResult<Unit>
    suspend fun end(callId: String, reason: String): CallResult<Unit>
    suspend fun diagnostics(callId: String, summary: Map<String, Any?>): CallResult<Unit>
}

/** What the call logic needs from the media layer (LiveKit on Android, a fake in tests). */
sealed interface MediaEvent {
    data object RemoteJoined : MediaEvent
    data object RemoteLeft : MediaEvent
    data object Reconnecting : MediaEvent
    data object Reconnected : MediaEvent
    /** The other person's microphone, as the server reports it: published and not muted. */
    data class RemoteMicrophone(val on: Boolean) : MediaEvent
    /** The other person asked, during the call, to flip this phone's camera (front/back). */
    data object CameraSwitchedByPeer : MediaEvent
    /** The media connection is gone for good (server closed the room, or could not reconnect). */
    data object Disconnected : MediaEvent
}

interface MediaSession {
    val events: Flow<MediaEvent>
    /**
     * Connects and publishes the microphone if [micOn] (and, for video calls, the camera if [cameraOn]),
     * so a mute chosen while it rang holds. Throws on failure.
     */
    suspend fun connect(url: String, token: String, video: Boolean, micOn: Boolean = true, cameraOn: Boolean = true)
    suspend fun setMicrophoneEnabled(enabled: Boolean)
    suspend fun setCameraEnabled(enabled: Boolean)
    /** The size the phone picked by itself (used for "Auto"). */
    val autoQuality: CaptureQuality get() = CaptureQuality.P720
    /**
     * Changes the sent video size mid-call; the camera restarts, so video pauses for a moment.
     * [forced]: send exactly this size at 30 fps (one layer, no automatic lowering); otherwise the
     * size may adapt to the network as in Auto.
     */
    suspend fun setVideoQuality(quality: CaptureQuality, forced: Boolean) = Unit
    /** True when the on-device noise filter can run on this phone. */
    val noiseReductionAvailable: Boolean get() = false
    /** Turns the noise filter for the sent voice on or off; takes effect on the next 10 ms frame. */
    fun setNoiseReduction(enabled: Boolean) = Unit
    fun switchCamera()
    /**
     * Asks the other person's phone to flip its own camera (front/back) during the call. Their app
     * switches its already-shared camera and shows that it happened; it cannot turn a camera on.
     * A no-op where the media layer cannot send it (e.g. the test fake).
     */
    suspend fun requestPeerSwitchCamera() = Unit
    fun setSpeakerphone(enabled: Boolean)
    /** Releases camera, microphone and the connection. Safe to call more than once. */
    fun disconnect()

    /** Current WebRTC statistics, or null when unavailable (not connected, or the SDK failed). */
    suspend fun stats(): StatsSample? = null
}
