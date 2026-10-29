package app.vaultchat.platform.call

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.PowerManager
import android.util.Size
import app.vaultchat.domain.call.CaptureQuality
import app.vaultchat.domain.call.VideoQualityPolicy
import io.livekit.android.room.participant.DataPublishReliability
import io.livekit.android.room.participant.VideoTrackPublishDefaults
import io.livekit.android.room.participant.VideoTrackPublishOptions
import livekit.org.webrtc.RtpParameters
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.VideoCaptureParameter
import io.livekit.android.room.track.VideoEncoding
import io.livekit.android.room.track.VideoPreset
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
import io.livekit.android.AudioOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.RoomOptions
import io.livekit.android.audio.AudioHandler
import io.livekit.android.audio.AudioProcessorOptions
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
import kotlinx.coroutines.delay
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
private const val TOPIC_SWITCH_CAMERA = "vaultchat.switch_camera"
private val SWITCH_CAMERA_PAYLOAD = ByteArray(0)

class LiveKitMediaSession(appContext: Context) : MediaSession {
    // Adaptive stream: receive only the resolution actually shown. Dynacast: stop encoding layers
    // nobody watches. Both are off by default in the SDK (docs/08-calling.md §8).
    val quality: CaptureQuality = chooseQuality(appContext)

    private val audio = HandoverAudioHandler(AudioSwitchHandler(appContext))
    private val denoiser = RnNoiseProcessor()

    val room: Room = LiveKit.create(
        appContext,
        RoomOptions(
            adaptiveStream = true,
            dynacast = true,
            videoTrackCaptureDefaults = LocalVideoTrackOptions(captureParams = preset(quality).capture),
            // Simulcast lets the server drop to a lower layer instantly when the receiver's network weakens.
            videoTrackPublishDefaults = VideoTrackPublishDefaults(
                videoEncoding = preset(quality).encoding,
                simulcast = true,
                simulcastLayers = layersBelow(quality),
            ),
        ),
        LiveKitOverrides(
            audioOptions = AudioOptions(
                audioHandler = audio,
                audioProcessorOptions = if (NOISE_FILTER) AudioProcessorOptions(capturePostProcessor = denoiser) else null,
            ),
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
                    is RoomEvent.ParticipantConnected -> {
                        _events.emit(MediaEvent.RemoteJoined)
                        // Their microphone is published a moment after they join; a peer who joined
                        // muted never publishes it, so look once that moment has passed.
                        launch {
                            delay(3_000)
                            emitRemoteMicrophone()
                        }
                    }
                    // The server relays the other phone's mute; "muted" here means it really sends nothing.
                    is RoomEvent.TrackMuted, is RoomEvent.TrackUnmuted, is RoomEvent.TrackPublished,
                    is RoomEvent.TrackUnpublished -> emitRemoteMicrophone()
                    is RoomEvent.ParticipantDisconnected -> {
                        _remoteVideo.value = null
                        _events.emit(MediaEvent.RemoteLeft)
                    }
                    is RoomEvent.TrackSubscribed -> (e.track as? VideoTrack)?.let { _remoteVideo.value = it }
                    is RoomEvent.TrackUnsubscribed -> if (e.track == _remoteVideo.value) _remoteVideo.value = null
                    is RoomEvent.DataReceived -> if (e.topic == TOPIC_SWITCH_CAMERA) {
                        // The other person asked to flip our camera; flip the already-on camera only.
                        val camera = room.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack
                        if (camera != null) {
                            camera.switchCamera()
                            _events.emit(MediaEvent.CameraSwitchedByPeer)
                        }
                    }
                    is RoomEvent.Reconnecting -> _events.emit(MediaEvent.Reconnecting)
                    is RoomEvent.Reconnected -> _events.emit(MediaEvent.Reconnected)
                    is RoomEvent.Disconnected -> if (!released) _events.emit(MediaEvent.Disconnected)
                    else -> Unit
                }
            }
        }
    }

    override suspend fun connect(url: String, token: String, video: Boolean, micOn: Boolean, cameraOn: Boolean) {
        room.connect(url, token)
        joined = true
        // The caller joins while it rings, so for the callee the peer is often already there.
        if (room.remoteParticipants.isNotEmpty()) {
            _events.emit(MediaEvent.RemoteJoined)
            emitRemoteMicrophone()
        }
        room.localParticipant.setMicrophoneEnabled(micOn)
        if (video) setCameraEnabled(cameraOn)
    }

    /**
     * Set once connect() has joined the room. Not Room.state: that only turns CONNECTED when the
     * media transport is up, a moment after joining, and LiveKit accepts these calls before then
     * (it calls them itself while connecting). Gating on it dropped the camera and mute changes.
     */
    @Volatile private var joined = false

    override suspend fun setMicrophoneEnabled(enabled: Boolean) {
        // Before joining there is nothing to change; connect() applies the wish.
        if (!joined) return
        room.localParticipant.setMicrophoneEnabled(enabled)
    }

    override suspend fun setCameraEnabled(enabled: Boolean) {
        if (!joined) return
        room.localParticipant.setCameraEnabled(enabled)
        val wanted = pending
        if (enabled && wanted != null) {
            pending = null
            (room.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack)
                ?.let { republishCamera(it, wanted.first, wanted.second) }
        }
        _localVideo.value = if (enabled) room.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack else null
    }

    override fun switchCamera() {
        (room.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack)?.switchCamera()
    }

    override suspend fun requestPeerSwitchCamera() {
        if (!joined) return
        // Tiny reliable message on our own topic; the payload is unused, the topic carries the meaning.
        room.localParticipant.publishData(SWITCH_CAMERA_PAYLOAD, DataPublishReliability.RELIABLE, topic = TOPIC_SWITCH_CAMERA)
    }

    /** The other person's microphone: on only while it is published and not muted. */
    private suspend fun emitRemoteMicrophone() {
        val peer = room.remoteParticipants.values.firstOrNull() ?: return
        val mic = peer.getTrackPublication(Track.Source.MICROPHONE)
        _events.emit(MediaEvent.RemoteMicrophone(on = mic != null && !mic.muted))
    }

    /** The system call (core-telecom) routes audio from now on; see [HandoverAudioHandler]. */
    fun handAudioToSystem() = audio.handOver()

    override fun setSpeakerphone(enabled: Boolean) {
        // With a system call the speaker goes through telecom instead (SystemCallSync).
        if (audio.handedOver) return
        val handler = audio.own
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
        CaptureQuality.P360 -> VideoPreset169.H360
    }

    override val autoQuality: CaptureQuality get() = quality

    /** Lower simulcast layers at 30 fps (LiveKit's defaults include 180p at 15 fps). */
    private fun layersBelow(top: CaptureQuality) = when (top) {
        CaptureQuality.P1080, CaptureQuality.P720 -> listOf(VideoPreset169.H360, VideoPreset169.H540)
        CaptureQuality.P540 -> listOf(VideoPreset169.H360)
        CaptureQuality.P360 -> listOf(P180at30)
    }

    /** 180p at 30 fps: LiveKit's own 180p and 216p presets run at 15 fps. */
    private object P180at30 : VideoPreset {
        override val capture = VideoCaptureParameter(320, 180, 30)
        override val encoding = VideoEncoding(200_000, 30)
    }

    /** A size picked while the camera was off; applied when it is turned back on. */
    private var pending: Pair<CaptureQuality, Boolean>? = null

    /**
     * Republishes the camera at the new size. Forced: one layer (no simulcast, so neither the server
     * nor the other phone can pick a smaller one), 30 fps, and no WebRTC degradation, so size and frame
     * rate stay; on a weak network the picture gets blockier instead. Auto: simulcast and LiveKit's
     * default degradation (keep the frame rate, lower the size), as at the start of the call.
     * A fresh track is created: enabling again after an unpublish would reuse LiveKit's cached,
     * stopped default track. The facing camera is kept.
     */
    override suspend fun setVideoQuality(quality: CaptureQuality, forced: Boolean) {
        val publication = room.localParticipant.getTrackPublication(Track.Source.CAMERA)
        val old = publication?.track as? LocalVideoTrack
        if (!joined || old == null) return
        // Republishing a turned-off camera would start sending again; wait until it is turned on.
        if (publication?.muted == true) {
            pending = quality to forced
            return
        }
        republishCamera(old, quality, forced)
    }

    private suspend fun republishCamera(old: LocalVideoTrack, quality: CaptureQuality, forced: Boolean) {
        val p = preset(quality)
        val participant = room.localParticipant
        val fresh = participant.createVideoTrack(options = old.options.copy(captureParams = p.capture))
        participant.unpublishTrack(old)
        _localVideo.value = null
        fresh.startCapture()
        val published = participant.publishVideoTrack(
            fresh,
            VideoTrackPublishOptions(
                videoEncoding = p.encoding,
                simulcast = !forced,
                simulcastLayers = if (forced) null else layersBelow(quality),
                source = Track.Source.CAMERA,
                degradationPreference = if (forced) RtpParameters.DegradationPreference.DISABLED else null,
            ),
        )
        if (published) {
            _localVideo.value = fresh
        } else {
            fresh.stopCapture()
            fresh.dispose()
        }
    }

    override val noiseReductionAvailable: Boolean get() = NOISE_FILTER && RnNoiseProcessor.available

    override fun setNoiseReduction(enabled: Boolean) {
        denoiser.enabled = enabled
    }

    override fun disconnect() {
        if (released) return
        released = true
        _localVideo.value = null
        _remoteVideo.value = null
        room.disconnect()
        room.release()
        denoiser.release()
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

/**
 * Noise filter off while calls that stopped connecting after it was added (v114, 2026-10-29) are
 * investigated; the button is hidden and audio goes straight through, as before.
 */
private const val NOISE_FILTER = false

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

/**
 * LiveKit's usual audio routing until a system call takes over (docs/08-calling.md §10). Two owners
 * of audio mode, focus and routing would fight, so once Android's telecom framework has the call
 * this stops LiveKit's handler. Without a system call it behaves exactly like [AudioSwitchHandler].
 */
private class HandoverAudioHandler(val own: AudioSwitchHandler) : AudioHandler {
    private var started = false

    @Volatile
    var handedOver = false
        private set

    @Synchronized
    override fun start() {
        started = true
        if (!handedOver) own.start()
    }

    @Synchronized
    override fun stop() {
        if (started && !handedOver) own.stop()
        started = false
    }

    @Synchronized
    fun handOver() {
        if (handedOver) return
        handedOver = true
        if (started) own.stop()
    }

}
