package app.vaultchat.platform.chat

import android.content.Context
import app.vaultchat.domain.chat.PendingLeave
import app.vaultchat.domain.chat.PendingLeaveStore

/**
 * Stores at most one unsent chat exit: a conversation ID and a leave ID, never message content
 * (docs/07-chat-and-disappearing-messages.md §4–5). Message text is never written to device storage.
 */
class PrefsPendingLeaveStore(context: Context) : PendingLeaveStore {
    private val prefs = context.applicationContext.getSharedPreferences("chat_pending_leave", Context.MODE_PRIVATE)

    override fun save(leave: PendingLeave) {
        prefs.edit().putString(KEY_CONVERSATION, leave.conversationId).putString(KEY_LEAVE, leave.leaveId).commit()
    }

    override fun load(): PendingLeave? {
        val conversation = prefs.getString(KEY_CONVERSATION, null) ?: return null
        val leave = prefs.getString(KEY_LEAVE, null) ?: return null
        return PendingLeave(conversation, leave)
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_CONVERSATION = "conversation_id"
        const val KEY_LEAVE = "leave_id"
    }
}
