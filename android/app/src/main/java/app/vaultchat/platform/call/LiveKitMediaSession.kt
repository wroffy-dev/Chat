package app.vaultchat.platform.call

import android.content.Context
import app.vaultchat.domain.call.MediaEvent
import app.vaultchat.domain.call.MediaSession
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
    val room: Room = LiveKit.create(appContext, RoomOptions(adaptiveStream = true, dynacast = true))

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

    override fun disconnect() {
        if (released) return
        released = true
        _localVideo.value = null
        _remoteVideo.value = null
        room.disconnect()
        room.release()
        scope.cancel()
    }
}
