package app.vaultchat.ui.call

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.vaultchat.domain.call.CallController
import app.vaultchat.domain.call.CallInfo
import app.vaultchat.domain.call.CallKind
import app.vaultchat.domain.call.CallRepository
import app.vaultchat.domain.realtime.RealtimeSignals
import app.vaultchat.platform.call.LiveKitMediaSession

/**
 * Holds one call. The media session lives as long as the ViewModel, so rotating the screen does not
 * drop the call; leaving the call screen for good ends it (docs/08-calling.md §7).
 */
class CallViewModel(application: Application, repo: CallRepository, signals: RealtimeSignals) : AndroidViewModel(application) {
    val media = LiveKitMediaSession(application)
    val controller = CallController(repo, media, viewModelScope, signals = signals)
    private var started = false

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

    override fun onCleared() {
        controller.dispose()
        super.onCleared()
    }
}
