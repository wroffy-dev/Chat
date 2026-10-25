package app.vaultchat.ui.call

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.LifecycleStartEffect
import app.vaultchat.domain.call.CallInfo
import app.vaultchat.domain.call.CallRepository
import app.vaultchat.domain.call.CallResult
import app.vaultchat.domain.call.CallStatus
import app.vaultchat.domain.realtime.RealtimeSignal
import app.vaultchat.domain.realtime.RealtimeSignals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Notices a ringing incoming call **while the app is open**: at once when a live "call changed"
 * signal arrives, otherwise by polling (every 3 s, or 15 s while signals are connected). With the
 * app closed, calls ring through push notifications (docs/10-notifications.md).
 */
@Composable
fun IncomingCallWatcher(repo: CallRepository, signals: RealtimeSignals, enabled: Boolean, onIncoming: (CallInfo) -> Unit) {
    val latest = rememberUpdatedState(onIncoming)
    LifecycleStartEffect(repo, enabled) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        if (enabled) {
            val wake = Channel<Unit>(Channel.CONFLATED)
            scope.launch { signals.signals.collect { if (it is RealtimeSignal.CallChanged) wake.trySend(Unit) } }
            scope.launch {
                var announced: String? = null
                while (isActive) {
                    val r = repo.current()
                    val call = (r as? CallResult.Ok)?.value
                    if (call != null && !call.isCaller && call.status == CallStatus.RINGING && call.id != announced) {
                        announced = call.id
                        latest.value(call)
                    }
                    withTimeoutOrNull(if (signals.connected.value) 15_000L else 3_000L) { wake.receive() }
                }
            }
        }
        onStopOrDispose { scope.cancel() }
    }
}
