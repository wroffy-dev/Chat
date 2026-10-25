package app.vaultchat.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vaultchat.domain.chat.ChatController
import app.vaultchat.domain.chat.ChatRepository
import app.vaultchat.domain.chat.PendingLeaveStore
import app.vaultchat.domain.realtime.RealtimeSignals
import kotlinx.coroutines.CoroutineScope

/**
 * Holds one open chat. Survives rotation; when the chat screen leaves the back stack for good
 * (Back, the toolbar arrow, sign-out) the ViewModel is cleared and that is the exit (docs/07 §4.1).
 */
class ChatViewModel(
    repo: ChatRepository,
    pendingLeaves: PendingLeaveStore,
    conversationId: String,
    myUserId: String,
    applicationScope: CoroutineScope,
    signals: RealtimeSignals,
) : ViewModel() {
    val controller = ChatController(repo, pendingLeaves, conversationId, myUserId, viewModelScope, applicationScope, signals = signals)

    init {
        controller.start()
    }

    override fun onCleared() {
        controller.leave()
        super.onCleared()
    }
}
