package app.vaultchat.ui.call

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.LifecycleStartEffect
import app.vaultchat.domain.call.CallInfo
import app.vaultchat.domain.call.CallRepository
import app.vaultchat.domain.call.CallResult
import app.vaultchat.domain.call.CallStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Checks for a ringing incoming call every few seconds **while the app is open**. Until push
 * notifications (FCM) are built, a call cannot ring on a phone whose app is closed or in the
 * background — see docs/10-notifications.md and docs/IMPLEMENTATION_PLAN.md.
 */
@Composable
fun IncomingCallWatcher(repo: CallRepository, enabled: Boolean, onIncoming: (CallInfo) -> Unit) {
    val latest = rememberUpdatedState(onIncoming)
    LifecycleStartEffect(repo, enabled) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        if (enabled) {
            scope.launch {
                var announced: String? = null
                while (isActive) {
                    val r = repo.current()
                    val call = (r as? CallResult.Ok)?.value
                    if (call != null && !call.isCaller && call.status == CallStatus.RINGING && call.id != announced) {
                        announced = call.id
                        latest.value(call)
                    }
                    delay(3_000)
                }
            }
        }
        onStopOrDispose { scope.cancel() }
    }
}
