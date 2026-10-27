package app.vaultchat.domain.chat

/** A message as the server returns it. Lives only in memory: never written to device storage. */
data class ChatMessage(
    val id: String,
    val seq: Long,
    val senderId: String,
    val text: String,
    val createdAtEpochMs: Long,
)

data class EnterInfo(val leaseId: String, val heartbeatSeconds: Int, val epoch: Long)

data class HistoryPage(val epoch: Long, val cleared: Boolean, val items: List<ChatMessage>, val hasMore: Boolean)

data class SentMessage(val id: String, val seq: Long, val epoch: Long, val createdAtEpochMs: Long)

enum class ChatFailure {
    NOT_FOUND,
    /** The chat was cleared after this message was composed; it was not sent. */
    CLEARED,
    /** The presence lease lapsed or was replaced: the server already treated it as an exit. */
    LEASE_EXPIRED,
    TOO_LONG,
    INVALID,
    RATE_LIMITED,
    SESSION_ENDED,
    NETWORK,
    UNEXPECTED,
}

sealed interface ChatResult<out T> {
    data class Ok<T>(val value: T) : ChatResult<T>
    data class Failure(val reason: ChatFailure) : ChatResult<Nothing>
}

/** Chat API (docs/14-api-contracts.md §5). */
interface ChatRepository {
    suspend fun enter(conversationId: String): ChatResult<EnterInfo>
    suspend fun heartbeat(conversationId: String, leaseId: String): ChatResult<Unit>
    suspend fun leave(conversationId: String, leaveId: String): ChatResult<Unit>
    suspend fun history(conversationId: String, afterSeq: Long, epoch: Long?): ChatResult<HistoryPage>
    suspend fun send(conversationId: String, clientMessageId: String, epoch: Long, text: String): ChatResult<SentMessage>
    suspend fun markRead(conversationId: String, seq: Long): ChatResult<Unit>
}

/**
 * An exit that may not have reached the server yet (docs/07 §5). Holds IDs only, never message
 * content, so it can be kept in ordinary app storage and retried after a crash or restart.
 */
data class PendingLeave(val conversationId: String, val leaveId: String)

interface PendingLeaveStore {
    fun save(leave: PendingLeave)
    fun load(): PendingLeave?
    fun clear()
}

object ChatText {
    const val MAX_CODE_POINTS = 4000

    /** Trimmed text, or null when there is nothing to send. Length is checked separately. */
    fun normalize(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() }

    fun codePoints(text: String): Int = text.codePointCount(0, text.length)

    fun tooLong(text: String): Boolean = codePoints(text) > MAX_CODE_POINTS
}

/** Retries a stored exit. Run before entering any chat, and once after sign-in. */
suspend fun flushPendingLeave(repo: ChatRepository, store: PendingLeaveStore) {
    val pending = store.load() ?: return
    when (val r = repo.leave(pending.conversationId, pending.leaveId)) {
        is ChatResult.Ok -> store.clear()
        is ChatResult.Failure -> if (r.reason == ChatFailure.NOT_FOUND || r.reason == ChatFailure.SESSION_ENDED) store.clear()
    }
}
