package app.vaultchat.ui.call

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import app.vaultchat.domain.call.CallInfo
import app.vaultchat.domain.call.CallRepository
import app.vaultchat.domain.call.CallResult
import app.vaultchat.domain.call.CallStatus
import app.vaultchat.domain.realtime.RealtimeSignal
import app.vaultchat.domain.realtime.RealtimeSignals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Notices a ringing incoming call while the app is open, **including when the screen is off**: it runs
 * in the application scope, not the screen's lifecycle, so a locked or dark screen does not stop it
 * (owner request 2026-11-03). At once on a live "call changed" signal, otherwise polling (3 s, or 15 s
 * while signals are connected). Deep Doze can still delay it; the battery-optimisation exemption helps.
 * With the app fully closed, calls would need push notifications (docs/10), which are off (D-24).
 */
@Composable
fun IncomingCallWatcher(
    repo: CallRepository,
    signals: RealtimeSignals,
    scope: CoroutineScope,
    enabled: Boolean,
    onIncoming: (CallInfo) -> Unit,
) {
    val latest = rememberUpdatedState(onIncoming)
    DisposableEffect(enabled) {
        val jobs = if (!enabled) emptyList() else {
            val wake = Channel<Unit>(Channel.CONFLATED)
            listOf(
                scope.launch { signals.signals.collect { if (it is RealtimeSignal.CallChanged) wake.trySend(Unit) } },
                scope.launch {
                    var announced: String? = null
                    while (isActive) {
                        val call = (repo.current() as? CallResult.Ok)?.value
                        if (call != null && !call.isCaller && call.status == CallStatus.RINGING && call.id != announced) {
                            announced = call.id
                            latest.value(call)
                        }
                        withTimeoutOrNull(if (signals.connected.value) 15_000L else 3_000L) { wake.receive() }
                    }
                },
            )
        }
        onDispose { jobs.forEach { it.cancel() } }
    }
}
