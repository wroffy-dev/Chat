package app.vaultchat.domain.realtime

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** "Something changed" signals from the server (docs/14 §10). IDs only: details are re-read over the API. */
sealed interface RealtimeSignal {
    data class ChatChanged(val conversationId: String) : RealtimeSignal
    data class CallChanged(val callId: String) : RealtimeSignal
    /** A pairing request was made, answered, withdrawn or superseded. */
    data class ConnectionChanged(val requestId: String) : RealtimeSignal
}

interface RealtimeSignals {
    val signals: Flow<RealtimeSignal>

    /** While true, screens can poll slowly: changes arrive as signals. */
    val connected: StateFlow<Boolean>
}

/**
 * Runs [check] now, then again at once on each signal that [matches], or after [pollMs] — or the
 * slower [signalledPollMs] while signals are connected, since polling is then only a fallback.
 * Stops when [check] returns false or the caller's coroutine is cancelled.
 */
suspend fun pollWithSignals(
    signals: RealtimeSignals?,
    matches: (RealtimeSignal) -> Boolean,
    pollMs: Long,
    signalledPollMs: Long,
    check: suspend () -> Boolean,
) = coroutineScope {
    val wake = Channel<Unit>(Channel.CONFLATED)
    val listener = signals?.let { live -> launch { live.signals.collect { if (matches(it)) wake.trySend(Unit) } } }
    while (check()) {
        val waitMs = if (signals?.connected?.value == true) signalledPollMs else pollMs
        withTimeoutOrNull(waitMs) { wake.receive() }
    }
    listener?.cancel()
}
