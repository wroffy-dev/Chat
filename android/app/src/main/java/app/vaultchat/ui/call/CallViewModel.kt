package app.vaultchat.ui.call

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import app.vaultchat.domain.call.CallController
import app.vaultchat.domain.call.CallInfo
import app.vaultchat.domain.call.CallKind
import app.vaultchat.domain.call.CallRepository
import app.vaultchat.domain.call.SystemCallSync
import app.vaultchat.domain.call.SystemCalls
import app.vaultchat.domain.realtime.RealtimeSignals
import app.vaultchat.platform.call.LiveKitMediaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Holds one call. It lives in the app (AppContainer.activeCall), not in the call screen, so the call
 * screen can be minimised: the person goes back to the chat and the call goes on in a small floating
 * picture (owner request 2026-11-13). Rotating the screen does not drop it either. It ends when the
 * call screen is closed for good, or when the call ends while minimised (docs/08-calling.md §7).
 */
class CallSession(
    application: Application,
    repo: CallRepository,
    signals: RealtimeSignals,
    systemCalls: SystemCalls?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val media = LiveKitMediaSession(application)
    val controller = CallController(repo, media, scope, signals = signals)

    /** The call screen was left with "Minimise": the call goes on and a small picture floats over the app. */
    var minimized by mutableStateOf(false)

    init {
        // Headset, car and watch buttons through Android's call framework, when the build enables it.
        if (systemCalls != null) SystemCallSync(controller, systemCalls, scope, media::handAudioToSystem).start()
    }
    private var started = false
    private var disposed = false

    fun startOutgoing(connectionId: String, kind: CallKind, peerUsername: String) {
        if (started) return
        started = true
        controller.startOutgoing(connectionId, kind, peerUsername)
    }

    fun showIncoming(info: CallInfo) {
        if (started) return
        started = true
        controller.showIncoming(info)
    }

    /** Ends the call if it is still running and releases camera, microphone and the connection. */
    fun dispose() {
        if (disposed) return
        disposed = true
        controller.dispose()
        // A moment for the hang-up request to reach the server, then everything of this call stops.
        scope.launch {
            delay(5_000)
            scope.cancel()
        }
    }
}

/**
 * Lives with the call screen's navigation entry (so it survives a rotation) only to say when that entry is
 * gone for good; the app then ends the call unless it was minimised.
 */
class CallEntryGuard : ViewModel() {
    var onGone: (() -> Unit)? = null

    /** Set when this entry was left with "Minimise": its going away must not end the call. */
    var keepCall = false

    override fun onCleared() {
        onGone?.invoke()
    }
}
