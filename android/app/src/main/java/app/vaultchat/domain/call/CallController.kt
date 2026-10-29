package app.vaultchat.domain.call

import app.vaultchat.domain.realtime.RealtimeSignal
import app.vaultchat.domain.realtime.RealtimeSignals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class CallPhase { STARTING, RINGING_OUT, RINGING_IN, CONNECTING, ACTIVE, RECONNECTING, ENDED }

enum class CallEndReason {
    HANGUP,
    REMOTE_HANGUP,
    DECLINED,
    NO_ANSWER,
    CANCELLED,
    NETWORK_LOST,
    FAILED,
    MEDIA_UNAVAILABLE,
    BUSY,
    SESSION_ENDED,
}

data class CallState(
    val phase: CallPhase = CallPhase.STARTING,
    val callId: String? = null,
    val kind: CallKind = CallKind.VIDEO,
    val peerUsername: String = "",
    val micOn: Boolean = true,
    val cameraOn: Boolean = true,
    val speakerOn: Boolean = true,
    val remotePresent: Boolean = false,
    val endReason: CallEndReason? = null,
    val reconnects: Int = 0,
    /** The sent video size the user picked during the call (video calls). */
    val videoQuality: VideoQualityChoice = VideoQualityChoice.AUTO,
    /** What this call's measured connection supports; null until measured. */
    val recommendedQuality: CaptureQuality? = null,
)

/**
 * Call lifecycle on the device (docs/08-calling.md §4, §7, §9), independent of Android and
 * LiveKit so it can be tested on a plain JVM. The server is the source of truth for call status;
 * until live push exists it is polled while a call screen is open.
 */
class CallController(
    private val repo: CallRepository,
    private val media: MediaSession,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Live "call changed" signals: the status is re-read at once and polled slowly while connected. */
    private val signals: RealtimeSignals? = null,
) {
    private val deviceLock = Mutex()

    companion object {
        const val STATUS_POLL_MS = 2_000L
        const val SIGNALLED_STATUS_POLL_MS = 10_000L
        /** Give up if media cannot reconnect within this time (docs/08 §9). */
        const val RECONNECT_GIVE_UP_MS = 30_000L
        /** After accepting, how long to wait for the other side's media before failing. */
        const val JOIN_GIVE_UP_MS = 30_000L
        /** How often call statistics are sampled while media flows (docs/08 §11). */
        const val STATS_INTERVAL_MS = 2_000L
    }

    private val _state = MutableStateFlow(CallState())
    val state: StateFlow<CallState> = _state.asStateFlow()

    private val _liveStats = MutableStateFlow<StatsSample?>(null)

    /** The latest statistics sample, for the debug overlay used in testing (docs/08 §11). */
    val liveStats: StateFlow<StatsSample?> = _liveStats.asStateFlow()

    private var poller: Job? = null
    private var mediaEvents: Job? = null
    private var watchdog: Job? = null
    private var statsJob: Job? = null
    private val stats = CallStatsAggregator()
    /** The last few samples, for the quality recommendation. */
    private val recent = ArrayDeque<StatsSample>()
    private var startedAtMs: Long? = null

    /** Caller side: create the call, join the room while it rings. */
    fun startOutgoing(connectionId: String, kind: CallKind, peerUsername: String) {
        _state.value = CallState(phase = CallPhase.STARTING, kind = kind, peerUsername = peerUsername, cameraOn = kind == CallKind.VIDEO, speakerOn = kind == CallKind.VIDEO)
        scope.launch {
            when (val r = repo.start(connectionId, kind)) {
                is CallResult.Ok -> {
                    _state.update { it.copy(callId = r.value.callId, phase = CallPhase.RINGING_OUT) }
                    listenToMedia()
                    if (!joinMedia(r.value, kind)) return@launch
                    pollStatus()
                }
                is CallResult.Failure -> finish(
                    when (r.reason) {
                        CallFailure.IN_PROGRESS -> CallEndReason.BUSY
                        CallFailure.MEDIA_UNAVAILABLE -> CallEndReason.MEDIA_UNAVAILABLE
                        CallFailure.SESSION_ENDED -> CallEndReason.SESSION_ENDED
                        else -> CallEndReason.FAILED
                    },
                    notifyServer = false,
                )
            }
        }
    }

    /** Callee side: show the ringing call. */
    fun showIncoming(info: CallInfo) {
        _state.value = CallState(
            phase = CallPhase.RINGING_IN,
            callId = info.id,
            kind = info.kind,
            peerUsername = info.peerUsername,
            cameraOn = info.kind == CallKind.VIDEO,
            speakerOn = info.kind == CallKind.VIDEO,
        )
        pollStatus()
    }

    fun accept() {
        val s = _state.value
        val id = s.callId ?: return
        if (s.phase != CallPhase.RINGING_IN) return
        _state.update { it.copy(phase = CallPhase.CONNECTING) }
        scope.launch {
            when (val r = repo.accept(id)) {
                is CallResult.Ok -> {
                    listenToMedia()
                    if (!joinMedia(r.value, s.kind)) return@launch
                    startWatchdog(JOIN_GIVE_UP_MS) { !_state.value.remotePresent }
                }
                is CallResult.Failure -> finish(
                    if (r.reason == CallFailure.NOT_ACTIVE) CallEndReason.CANCELLED else CallEndReason.FAILED,
                    notifyServer = false,
                )
            }
        }
    }

    fun decline() {
        val id = _state.value.callId ?: return
        if (_state.value.phase != CallPhase.RINGING_IN) return
        scope.launch {
            repo.decline(id)
            finish(CallEndReason.DECLINED, notifyServer = false)
        }
    }

    fun hangup() {
        val s = _state.value
        if (s.phase == CallPhase.ENDED) return
        if (s.phase == CallPhase.RINGING_IN) return decline()
        scope.launch {
            val id = s.callId
            if (id != null) {
                if (s.phase == CallPhase.RINGING_OUT || s.phase == CallPhase.STARTING) repo.cancel(id) else repo.end(id, "hangup")
            }
            finish(if (s.phase == CallPhase.RINGING_OUT) CallEndReason.CANCELLED else CallEndReason.HANGUP, notifyServer = false)
        }
    }

    fun toggleMicrophone() {
        _state.update { it.copy(micOn = !it.micOn) }
        applyDevice(microphone = true)
    }

    fun toggleCamera() {
        if (_state.value.kind != CallKind.VIDEO) return
        _state.update { it.copy(cameraOn = !it.cameraOn) }
        applyDevice(microphone = false)
    }

    /**
     * Brings the microphone and camera in line with the screen. One change at a time, always to the
     * latest wish: quick repeated taps cannot finish out of order and leave the mic in the wrong state.
     */
    /** Switches the sent video size mid-call; AUTO goes back to the size the phone chose itself. */
    fun setVideoQuality(choice: VideoQualityChoice) {
        if (_state.value.kind != CallKind.VIDEO || _state.value.videoQuality == choice) return
        _state.update { it.copy(videoQuality = choice) }
        scope.launch {
            deviceLock.withLock {
                val wanted = _state.value.videoQuality
                try {
                    media.setVideoQuality(wanted.capture ?: media.autoQuality)
                } catch (_: Exception) {
                    // The camera could not restart at that size; the call goes on as it was.
                }
            }
        }
    }

    private fun applyDevice(microphone: Boolean) {
        scope.launch {
            deviceLock.withLock {
                val s = _state.value
                try {
                    if (microphone) media.setMicrophoneEnabled(s.micOn) else media.setCameraEnabled(s.cameraOn)
                } catch (_: Exception) {
                    // Not connected yet, or the device refused: connect() applies the wish later.
                }
            }
        }
    }

    fun switchCamera() = media.switchCamera()

    fun toggleSpeaker() {
        val on = !_state.value.speakerOn
        _state.update { it.copy(speakerOn = on) }
        media.setSpeakerphone(on)
    }

    /** Called when the screen goes away for good (e.g. the ViewModel is cleared). */
    fun dispose() {
        if (_state.value.phase != CallPhase.ENDED) hangup()
        media.disconnect()
    }

    // ---------------------------------------------------------------------------------------------

    private suspend fun joinMedia(join: JoinInfo, kind: CallKind): Boolean = try {
        media.connect(join.livekitUrl, join.livekitToken, video = kind == CallKind.VIDEO, micOn = _state.value.micOn, cameraOn = _state.value.cameraOn)
        media.setSpeakerphone(_state.value.speakerOn)
        true
    } catch (_: Exception) {
        finish(CallEndReason.FAILED, notifyServer = true, serverReason = "error")
        false
    }

    private fun listenToMedia() {
        if (mediaEvents != null) return
        mediaEvents = scope.launch {
            media.events.collect { e ->
                when (e) {
                    MediaEvent.RemoteJoined -> {
                        watchdog?.cancel()
                        if (startedAtMs == null) startedAtMs = nowMs()
                        _state.update { it.copy(remotePresent = true, phase = CallPhase.ACTIVE) }
                        collectStats()
                    }
                    MediaEvent.RemoteLeft -> {
                        if (_state.value.phase == CallPhase.ACTIVE) finish(CallEndReason.REMOTE_HANGUP, notifyServer = true)
                    }
                    MediaEvent.Reconnecting -> {
                        _state.update { it.copy(phase = CallPhase.RECONNECTING, reconnects = it.reconnects + 1) }
                        startWatchdog(RECONNECT_GIVE_UP_MS) { _state.value.phase == CallPhase.RECONNECTING }
                    }
                    MediaEvent.Reconnected -> {
                        watchdog?.cancel()
                        _state.update { it.copy(phase = if (it.remotePresent) CallPhase.ACTIVE else CallPhase.CONNECTING) }
                    }
                    MediaEvent.Disconnected -> if (_state.value.phase != CallPhase.ENDED) {
                        finish(CallEndReason.NETWORK_LOST, notifyServer = true, serverReason = "network_lost")
                    }
                }
            }
        }
    }

    /** Follows the server's view: answered, declined, missed, cancelled or ended elsewhere. */
    private fun pollStatus() {
        poller?.cancel()
        poller = scope.launch {
            val wake = Channel<Unit>(Channel.CONFLATED)
            signals?.let { live ->
                launch { live.signals.collect { if (it is RealtimeSignal.CallChanged && it.callId == _state.value.callId) wake.trySend(Unit) } }
            }
            while (isActive) {
                val waitMs = if (signals?.connected?.value == true) SIGNALLED_STATUS_POLL_MS else STATUS_POLL_MS
                withTimeoutOrNull(waitMs) { wake.receive() }
                val id = _state.value.callId ?: continue
                val r = repo.get(id)
                if (r is CallResult.Failure && r.reason == CallFailure.SESSION_ENDED) {
                    finish(CallEndReason.SESSION_ENDED, notifyServer = false)
                    return@launch
                }
                val info = (r as? CallResult.Ok)?.value ?: continue
                val phase = _state.value.phase
                when (info.status) {
                    CallStatus.RINGING -> Unit
                    CallStatus.ACCEPTED, CallStatus.ACTIVE -> if (phase == CallPhase.RINGING_OUT) {
                        _state.update { it.copy(phase = if (it.remotePresent) CallPhase.ACTIVE else CallPhase.CONNECTING) }
                        startWatchdog(JOIN_GIVE_UP_MS) { !_state.value.remotePresent }
                    } else if (phase == CallPhase.RINGING_IN) {
                        // Answered on another device of this user: nothing left to do here.
                        finish(CallEndReason.CANCELLED, notifyServer = false)
                    }
                    CallStatus.DECLINED -> finish(CallEndReason.DECLINED, notifyServer = false)
                    CallStatus.MISSED -> finish(CallEndReason.NO_ANSWER, notifyServer = false)
                    CallStatus.CANCELLED -> finish(CallEndReason.CANCELLED, notifyServer = false)
                    CallStatus.ENDED -> finish(if (phase == CallPhase.RINGING_IN) CallEndReason.CANCELLED else CallEndReason.REMOTE_HANGUP, notifyServer = false)
                    CallStatus.FAILED -> finish(CallEndReason.FAILED, notifyServer = false)
                }
                if (_state.value.phase == CallPhase.ENDED) break
            }
            coroutineContext.cancelChildren()
        }
    }

    /** Samples statistics while the call is up; the summary goes out with the diagnostics at the end. */
    private fun collectStats() {
        if (statsJob != null) return
        statsJob = scope.launch {
            while (isActive) {
                media.stats()?.let {
                    val sample = it.copy(atMs = nowMs())
                    stats.add(sample)
                    _liveStats.value = sample
                    recent.addLast(sample)
                    if (recent.size > 5) recent.removeFirst()
                    if (_state.value.kind == CallKind.VIDEO) {
                        _state.update { s -> s.copy(recommendedQuality = QualityAdvisor.recommend(recent)) }
                    }
                }
                delay(STATS_INTERVAL_MS)
            }
        }
    }

    private fun startWatchdog(timeoutMs: Long, stillBad: () -> Boolean) {
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(timeoutMs)
            if (stillBad() && _state.value.phase != CallPhase.ENDED) {
                finish(CallEndReason.NETWORK_LOST, notifyServer = true, serverReason = "network_lost")
            }
        }
    }

    /**
     * One cleanup path for every way a call can end (docs/08 §7). Not suspending: it is often called
     * from inside the poller or media-event job that it cancels, so server notifications run in their
     * own coroutine and are not cancelled with them.
     */
    private fun finish(reason: CallEndReason, notifyServer: Boolean, serverReason: String = "hangup") {
        if (_state.value.phase == CallPhase.ENDED) return
        val id = _state.value.callId
        val durationSec = startedAtMs?.let { (nowMs() - it) / 1000 }
        _state.update { it.copy(phase = CallPhase.ENDED, endReason = reason) }
        val ended = _state.value
        statsJob?.cancel()
        val quality = stats.summary()
        media.disconnect()
        if (id != null) {
            scope.launch {
                if (notifyServer) repo.end(id, serverReason)
                // Best effort, numbers only: no media, IP addresses or location (docs/08 §11).
                repo.diagnostics(
                    id,
                    mapOf(
                        "kind" to ended.kind.name.lowercase(),
                        "endReason" to reason.name.lowercase(),
                        "durationSec" to durationSec,
                        "reconnects" to ended.reconnects,
                    ) + quality,
                )
            }
        }
        poller?.cancel()
        watchdog?.cancel()
        mediaEvents?.cancel()
    }
}
