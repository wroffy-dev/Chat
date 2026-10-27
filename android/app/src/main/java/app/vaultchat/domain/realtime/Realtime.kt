package app.vaultchat.domain.realtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** "Something changed" signals from the server (docs/14 §10). IDs only: details are re-read over the API. */
sealed interface RealtimeSignal {
    data class ChatChanged(val conversationId: String) : RealtimeSignal
    data class CallChanged(val callId: String) : RealtimeSignal
}

interface RealtimeSignals {
    val signals: Flow<RealtimeSignal>

    /** While true, screens can poll slowly: changes arrive as signals. */
    val connected: StateFlow<Boolean>
}
