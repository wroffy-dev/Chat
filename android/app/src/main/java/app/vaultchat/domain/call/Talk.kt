package app.vaultchat.domain.call

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Walkie-talkie (owner decision D-26, 2026-11-12): one person speaks for at most [Talk.MAX_SECONDS] and the
 * other phone plays it aloud at once. It is live, a short one-way call of kind [CallKind.TALK]: nothing is
 * recorded or stored, and the listening phone never sends its own microphone (the server's token forbids it).
 * Plain Kotlin, so it is tested on a JVM.
 */
object Talk {
    /** Longest message (D-26). */
    const val MAX_SECONDS = 10
    /** How long the sender waits for the other phone to wake up and join. */
    const val WAIT_FOR_PEER_MS = 22_000L
    /** After the other phone joined, its audio needs a moment before it really plays. */
    const val SETTLE_MS = 700L
    /** The listening phone leaves by itself after this long, whatever happens. */
    const val LISTEN_LIMIT_MS = 25_000L
}

enum class TalkPhase { CONNECTING, SPEAKING, DONE }

enum class TalkOutcome { SENT, NOT_REACHED, BUSY, FAILED }

data class TalkState(
    val phase: TalkPhase = TalkPhase.CONNECTING,
    /** Seconds left to speak, while [TalkPhase.SPEAKING]. */
    val secondsLeft: Int = Talk.MAX_SECONDS,
    val outcome: TalkOutcome? = null,
)

/**
 * The speaking side. [start] asks the server for a talk call and joins it with the microphone on; speaking
 * starts only once the other phone has joined (so nothing said is lost), and stops after
 * [Talk.MAX_SECONDS] or on [stop].
 */
class TalkSender(
    private val repo: CallRepository,
    private val media: MediaSession,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(TalkState())
    val state: StateFlow<TalkState> = _state.asStateFlow()
    private var job: Job? = null
    private var callId: String? = null

    fun start(connectionId: String) {
        if (job != null) return
        job = scope.launch {
            val join = when (val r = repo.start(connectionId, CallKind.TALK)) {
                is CallResult.Ok -> r.value
                is CallResult.Failure -> return@launch finish(if (r.reason == CallFailure.IN_PROGRESS) TalkOutcome.BUSY else TalkOutcome.FAILED)
            }
            callId = join.callId
            try {
                val peerJoined = scope.launch(start = CoroutineStart.UNDISPATCHED) { media.events.first { it is MediaEvent.RemoteJoined } }
                val peerGone = scope.launch(start = CoroutineStart.UNDISPATCHED) { media.events.first { it is MediaEvent.RemoteLeft || it is MediaEvent.Disconnected } }
                try {
                    media.connect(join.livekitUrl, join.livekitToken, video = false, micOn = true, cameraOn = false)
                    if (withTimeoutOrNull(Talk.WAIT_FOR_PEER_MS) { peerJoined.join() } == null) {
                        repo.cancel(join.callId)
                        return@launch finish(TalkOutcome.NOT_REACHED)
                    }
                    delay(Talk.SETTLE_MS)
                    _state.update { it.copy(phase = TalkPhase.SPEAKING, secondsLeft = Talk.MAX_SECONDS) }
                    for (left in Talk.MAX_SECONDS - 1 downTo 0) {
                        if (withTimeoutOrNull(1_000) { peerGone.join() } != null) break
                        _state.update { it.copy(secondsLeft = left) }
                    }
                    repo.end(join.callId, "hangup")
                    finish(TalkOutcome.SENT)
                } finally {
                    peerJoined.cancel()
                    peerGone.cancel()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                repo.end(join.callId, "error")
                finish(TalkOutcome.FAILED)
            }
        }
    }

    /** Stops early: before the other phone joined nothing is sent; while speaking the message ends here. */
    fun stop() {
        val running = job ?: return
        if (_state.value.phase == TalkPhase.DONE) return
        val speaking = _state.value.phase == TalkPhase.SPEAKING
        running.cancel()
        scope.launch {
            withContext(NonCancellable) {
                callId?.let { if (speaking) repo.end(it, "hangup") else repo.cancel(it) }
            }
            finish(if (speaking) TalkOutcome.SENT else TalkOutcome.NOT_REACHED)
        }
    }

    private fun finish(outcome: TalkOutcome) {
        if (_state.value.phase == TalkPhase.DONE) return
        _state.update { it.copy(phase = TalkPhase.DONE, outcome = outcome) }
        media.disconnect()
    }
}

/**
 * The listening side: accepts the talk call, joins without a microphone, plays it on the loudspeaker and
 * leaves when the speaker is done (or after [Talk.LISTEN_LIMIT_MS]). Returns whether anything was joined.
 */
suspend fun listenToTalk(repo: CallRepository, media: MediaSession, callId: String): Boolean {
    val join = (repo.accept(callId) as? CallResult.Ok)?.value ?: return false
    try {
        withTimeoutOrNull(Talk.LISTEN_LIMIT_MS) {
            kotlinx.coroutines.coroutineScope {
                // Subscribed before connecting, so an early "left" is not missed.
                val over = launch(start = CoroutineStart.UNDISPATCHED) { media.events.first { it is MediaEvent.RemoteLeft || it is MediaEvent.Disconnected } }
                media.connect(join.livekitUrl, join.livekitToken, video = false, micOn = false, cameraOn = false)
                media.setSpeakerphone(true)
                over.join()
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        // Could not connect: nothing to play.
    } finally {
        media.disconnect()
        withContext(NonCancellable) { repo.end(callId, "hangup") }
    }
    return true
}
