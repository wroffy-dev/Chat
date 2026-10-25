package app.vaultchat.platform.call

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.PowerManager
import android.util.Size
import app.vaultchat.domain.call.CaptureQuality
import app.vaultchat.domain.call.VideoQualityPolicy
import io.livekit.android.room.participant.VideoTrackPublishDefaults
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.VideoPreset169
import app.vaultchat.domain.call.MediaEvent
import app.vaultchat.domain.call.MediaSession
import app.vaultchat.domain.call.RawStat
import app.vaultchat.domain.call.StatsSample
import app.vaultchat.domain.call.WebRtcStats
import io.livekit.android.webrtc.RTCStatsGetter
import io.livekit.android.webrtc.getStats
import kotlinx.coroutines.withTimeoutOrNull
import livekit.org.webrtc.RTCStatsReport
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * LiveKit-backed media for one call. The server issues a room-scoped token; this class never sees
 * any LiveKit secret. Camera and microphone are only captured between [connect] and [disconnect].
 */
class LiveKitMediaSession(appContext: Context) : MediaSession {
    // Adaptive stream: receive only the resolution actually shown. Dynacast: stop encoding layers
    // nobody watches. Both are off by default in the SDK (docs/08-calling.md §8).
    val quality: CaptureQuality = chooseQuality(appContext)

    val room: Room = LiveKit.create(
        appContext,
        RoomOptions(
            adaptiveStream = true,
            dynacast = true,
            videoTrackCaptureDefaults = LocalVideoTrackOptions(captureParams = preset(quality).capture),
            // Simulcast lets the server drop to a lower layer instantly when the receiver's network weakens.
            videoTrackPublishDefaults = VideoTrackPublishDefaults(videoEncoding = preset(quality).encoding, simulcast = true),
        ),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _events = MutableSharedFlow<MediaEvent>(extraBufferCapacity = 32)
    override val events: SharedFlow<MediaEvent> = _events.asSharedFlow()

    private val _remoteVideo = MutableStateFlow<VideoTrack?>(null)
    val remoteVideo: StateFlow<VideoTrack?> = _remoteVideo.asStateFlow()
    private val _localVideo = MutableStateFlow<VideoTrack?>(null)
    val localVideo: StateFlow<VideoTrack?> = _localVideo.asStateFlow()

    private var released = false

    init {
        scope.launch {
            room.events.events.collect { e ->
                when (e) {
                    is RoomEvent.ParticipantConnected -> _events.emit(MediaEvent.RemoteJoined)
                    is RoomEvent.ParticipantDisconnected -> {
                        _remoteVideo.value = null
                        _events.emit(MediaEvent.RemoteLeft)
                    }
                    is RoomEvent.TrackSubscribed -> (e.track as? VideoTrack)?.let { _remoteVideo.value = it }
                    is RoomEvent.TrackUnsubscribed -> if (e.track == _remoteVideo.value) _remoteVideo.value = null
                    is RoomEvent.Reconnecting -> _events.emit(MediaEvent.Reconnecting)
                    is RoomEvent.Reconnected -> _events.emit(MediaEvent.Reconnected)
                    is RoomEvent.Disconnected -> if (!released) _events.emit(MediaEvent.Disconnected)
                    else -> Unit
                }
            }
        }
    }

    override suspend fun connect(url: String, token: String, video: Boolean) {
        room.connect(url, token)
        // The caller joins while it rings, so for the callee the peer is often already there.
        if (room.remoteParticipants.isNotEmpty()) _events.emit(MediaEvent.RemoteJoined)
        room.localParticipant.setMicrophoneEnabled(true)
        if (video) setCameraEnabled(true)
    }

    override suspend fun setMicrophoneEnabled(enabled: Boolean) {
        room.localParticipant.setMicrophoneEnabled(enabled)
    }

    override suspend fun setCameraEnabled(enabled: Boolean) {
        room.localParticipant.setCameraEnabled(enabled)
        _localVideo.value = if (enabled) room.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack else null
    }

    override fun switchCamera() {
        (room.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack)?.switchCamera()
    }

    override fun setSpeakerphone(enabled: Boolean) {
        val handler = room.audioHandler as? AudioSwitchHandler ?: return
        val wanted = handler.availableAudioDevices.firstOrNull {
            if (enabled) it is AudioDevice.Speakerphone else it is AudioDevice.Earpiece
        }
        // Headsets stay selected when present; this only flips between loudspeaker and earpiece.
        if (handler.availableAudioDevices.none { it is AudioDevice.BluetoothHeadset || it is AudioDevice.WiredHeadset }) {
            handler.selectDevice(wanted)
        }
    }

    private fun preset(q: CaptureQuality) = when (q) {
        CaptureQuality.P1080 -> VideoPreset169.H1080
        CaptureQuality.P720 -> VideoPreset169.H720
        CaptureQuality.P540 -> VideoPreset169.H540
    }

    override fun disconnect() {
        if (released) return
        released = true
        _localVideo.value = null
        _remoteVideo.value = null
        room.disconnect()
        room.release()
        scope.cancel()
    }

    /** Both peer connections' WebRTC stats, reduced to plain values (docs/08 §11). Null on any failure. */
    override suspend fun stats(): StatsSample? {
        if (released) return null
        return try {
            withTimeoutOrNull(1_500) {
                val publisher: RTCStatsGetter = room::getPublisherRTCStats
                val subscriber: RTCStatsGetter = room::getSubscriberRTCStats
                WebRtcStats.sample(publisher.getStats().toRaw(), subscriber.getStats().toRaw())
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun RTCStatsReport.toRaw(): List<RawStat> = statsMap.values.map { RawStat(it.id, it.type, it.members) }
}

/** Device capability check for 1080p capture (docs/08-calling.md §8). */
private fun chooseQuality(context: Context): CaptureQuality {
    val power = context.getSystemService(PowerManager::class.java)
    val supports1080p30 = try {
        val cameras = context.getSystemService(CameraManager::class.java)
        cameras.cameraIdList.any { id ->
            val chars = cameras.getCameraCharacteristics(id)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return@any false
            val fhd = Size(1920, 1080)
            val sizes = map.getOutputSizes(android.graphics.SurfaceTexture::class.java)?.toList().orEmpty()
            // Minimum frame duration in ns; ≤ 33.4 ms means at least 30 fps at that size.
            fhd in sizes && map.getOutputMinFrameDuration(android.graphics.SurfaceTexture::class.java, fhd) <= 33_400_000L
        }
    } catch (_: Exception) {
        false
    }
    return VideoQualityPolicy.choose(supports1080p30, power?.currentThermalStatus ?: 0, power?.isPowerSaveMode == true)
}
