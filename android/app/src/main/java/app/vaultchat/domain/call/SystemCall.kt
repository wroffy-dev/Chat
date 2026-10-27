package app.vaultchat.domain.call

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch

/** What Android's call framework is told about a call (docs/08-calling.md §10). */
data class SystemCallInfo(val callId: String, val incoming: Boolean, val video: Boolean, val peerUsername: String)

/** From the app to the system call. */
sealed interface SystemCallCommand {
    /** The user answered in the app (incoming calls). */
    data object Answer : SystemCallCommand
    /** The other side answered (outgoing calls). */
    data object Active : SystemCallCommand
    data class Speaker(val on: Boolean) : SystemCallCommand
    data class Disconnect(val reason: CallEndReason) : SystemCallCommand
}

/** What the user did through the system: a headset or car button, a watch, or another call arriving. */
interface SystemCallActions {
    fun answer()
    fun hangup()
    /** The system holds the call (e.g. a phone call was answered) or resumes it. */
    fun hold(held: Boolean)
    /** The system call is up and now owns audio routing. */
    fun registered()
}

/** Android's telecom framework on a phone; a fake in tests. */
interface SystemCalls {
    /**
     * Adds the call to the system and suspends until the system has removed it. Throws when the
     * system refuses the call; the call then continues in the app without system integration.
     */
    suspend fun run(info: SystemCallInfo, commands: ReceiveChannel<SystemCallCommand>, actions: SystemCallActions)
}

/**
 * Keeps the system's view of one call in step with [CallController], and turns system actions into
 * controller actions. The app's own screens and notifications stay as they are: with self-managed
 * calls Android draws no call UI, but headsets, cars and watches can answer and end the call, and
 * the system routes the audio. Off unless the build enables it (docs/08 §10).
 */
class SystemCallSync(
    private val controller: CallController,
    private val system: SystemCalls,
    private val scope: CoroutineScope,
    /** Called once the system owns audio routing, so the media layer stops routing audio itself. */
    private val onSystemAudio: () -> Unit = {},
) {
    private val commands = Channel<SystemCallCommand>(Channel.UNLIMITED)
    private var job: Job? = null
    private var observer: Job? = null
    private var isIncoming = false
    private var answeredBySystem = false
    private var connected = false
    private var mutedForHold = false

    fun start() {
        if (observer != null) return
        observer = scope.launch {
            var lastSpeaker: Boolean? = null
            controller.state.collect { s ->
                val id = s.callId
                if (job == null && id != null && (s.phase == CallPhase.RINGING_OUT || s.phase == CallPhase.RINGING_IN)) {
                    register(SystemCallInfo(id, s.phase == CallPhase.RINGING_IN, s.kind == CallKind.VIDEO, s.peerUsername))
                }
                if (job == null) {
                    if (s.phase == CallPhase.ENDED) observer?.cancel()
                    return@collect
                }
                if (!connected && s.phase in CONNECTED_PHASES) {
                    connected = true
                    if (!answeredBySystem) {
                        commands.trySend(if (isIncoming) SystemCallCommand.Answer else SystemCallCommand.Active)
                    }
                }
                // Sent once at the start too: the system picks its own default route.
                if (s.speakerOn != lastSpeaker && s.phase != CallPhase.ENDED) {
                    lastSpeaker = s.speakerOn
                    commands.trySend(SystemCallCommand.Speaker(s.speakerOn))
                }
                if (s.phase == CallPhase.ENDED) {
                    commands.trySend(SystemCallCommand.Disconnect(s.endReason ?: CallEndReason.HANGUP))
                    commands.close()
                    observer?.cancel()
                }
            }
        }
    }

    private fun register(info: SystemCallInfo) {
        isIncoming = info.incoming
        job = scope.launch {
            try {
                system.run(info, commands, actions)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Refused by the system (e.g. another call app is busy): the call goes on in the app.
            }
        }
    }

    private val actions = object : SystemCallActions {
        override fun answer() {
            if (controller.state.value.phase != CallPhase.RINGING_IN) return
            answeredBySystem = true
            controller.accept()
        }

        override fun hangup() {
            if (controller.state.value.phase != CallPhase.ENDED) controller.hangup()
        }

        override fun hold(held: Boolean) {
            val micOn = controller.state.value.micOn
            if (held && micOn) {
                mutedForHold = true
                controller.toggleMicrophone()
            } else if (!held && mutedForHold) {
                mutedForHold = false
                if (!micOn) controller.toggleMicrophone()
            }
        }

        override fun registered() = onSystemAudio()
    }

    private companion object {
        val CONNECTED_PHASES = setOf(CallPhase.CONNECTING, CallPhase.ACTIVE, CallPhase.RECONNECTING)
    }
}
