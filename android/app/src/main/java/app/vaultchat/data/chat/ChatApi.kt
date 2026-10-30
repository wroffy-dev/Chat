package app.vaultchat.data.chat

import app.vaultchat.data.auth.AuthorizedCaller
import app.vaultchat.data.auth.EmptyBody
import app.vaultchat.data.network.ApiResult
import app.vaultchat.data.network.HttpJson
import app.vaultchat.domain.chat.ChatFailure
import app.vaultchat.domain.chat.ChatMessage
import app.vaultchat.domain.chat.ChatRepository
import app.vaultchat.domain.chat.ChatResult
import app.vaultchat.domain.chat.EnterInfo
import app.vaultchat.domain.chat.HistoryPage
import app.vaultchat.domain.chat.Reaction
import app.vaultchat.domain.chat.SentMessage
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class EnterDto(val leaseId: String, val leaseTtlSeconds: Int, val heartbeatSeconds: Int, val epoch: Long)

@Serializable
data class HeartbeatBody(val leaseId: String)

@Serializable
data class LeaveBody(val leaveId: String)

@Serializable
data class LeaveDto(val epoch: Long)

@Serializable
data class MessageDto(val id: String, val seq: Long, val senderId: String, val text: String, val createdAt: String)

@Serializable
data class ReactionDto(val messageId: String, val userId: String, val emoji: String)

@Serializable
data class HistoryDto(
    val epoch: Long,
    val cleared: Boolean,
    val items: List<MessageDto>,
    val hasMore: Boolean,
    // Absent from servers older than D-25.
    val reactions: List<ReactionDto> = emptyList(),
)

@Serializable
data class ReactionBody(val emoji: String)

@Serializable
data class SendBody(val clientMessageId: String, val epoch: Long, val text: String)

@Serializable
data class SentDto(val id: String, val seq: Long, val epoch: Long, val createdAt: String)

@Serializable
data class ReadBody(val seq: Long)

fun MessageDto.toDomain() = ChatMessage(id, seq, senderId, text, Instant.parse(createdAt).toEpochMilli())

/** Chat API (docs/14-api-contracts.md §5) with the shared token-refreshing caller. */
class RemoteChatRepository(private val http: HttpJson, private val caller: AuthorizedCaller) : ChatRepository {
    private fun base(id: String) = "api/v1/conversations/$id"

    override suspend fun enter(conversationId: String) = caller.call {
        http.post("${base(conversationId)}/enter", EmptyBody(), EmptyBody.serializer(), EnterDto.serializer(), it)
    }.map { EnterInfo(it.leaseId, it.heartbeatSeconds, it.epoch) }

    override suspend fun heartbeat(conversationId: String, leaseId: String) = caller.call<Unit> {
        http.post<HeartbeatBody, Unit>("${base(conversationId)}/heartbeat", HeartbeatBody(leaseId), HeartbeatBody.serializer(), null, it)
    }.map { }

    override suspend fun leave(conversationId: String, leaveId: String) = caller.call {
        http.post("${base(conversationId)}/leave", LeaveBody(leaveId), LeaveBody.serializer(), LeaveDto.serializer(), it)
    }.map { }

    override suspend fun history(conversationId: String, afterSeq: Long, epoch: Long?) = caller.call {
        val query = "afterSeq=$afterSeq" + (epoch?.let { e -> "&epoch=$e" } ?: "")
        http.get("${base(conversationId)}/messages?$query", HistoryDto.serializer(), it)
    }.map { h ->
        HistoryPage(h.epoch, h.cleared, h.items.map { m -> m.toDomain() }, h.hasMore, h.reactions.map { Reaction(it.messageId, it.userId, it.emoji) })
    }

    override suspend fun send(conversationId: String, clientMessageId: String, epoch: Long, text: String) = caller.call {
        http.post("${base(conversationId)}/messages", SendBody(clientMessageId, epoch, text), SendBody.serializer(), SentDto.serializer(), it)
    }.map { SentMessage(it.id, it.seq, it.epoch, Instant.parse(it.createdAt).toEpochMilli()) }

    override suspend fun markRead(conversationId: String, seq: Long) = caller.call<Unit> {
        http.post<ReadBody, Unit>("${base(conversationId)}/read", ReadBody(seq), ReadBody.serializer(), null, it)
    }.map { }

    override suspend fun react(conversationId: String, messageId: String, emoji: String?) = caller.call<Unit> {
        val path = "${base(conversationId)}/messages/$messageId/reaction"
        if (emoji == null) http.delete(path, it) else http.put<ReactionBody, Unit>(path, ReactionBody(emoji), ReactionBody.serializer(), null, it)
    }.map { }

    private inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ChatResult<R> = when (this) {
        is ApiResult.Ok -> ChatResult.Ok(transform(value))
        ApiResult.NetworkError -> ChatResult.Failure(ChatFailure.NETWORK)
        is ApiResult.HttpError -> ChatResult.Failure(
            when {
                code == "CONVERSATION_CLEARED" -> ChatFailure.CLEARED
                code == "LEASE_EXPIRED" -> ChatFailure.LEASE_EXPIRED
                code == "MESSAGE_TOO_LONG" || status == 413 -> ChatFailure.TOO_LONG
                status == 404 -> ChatFailure.NOT_FOUND
                status == 429 -> ChatFailure.RATE_LIMITED
                AuthorizedCaller.endsSession(this) -> ChatFailure.SESSION_ENDED
                status == 400 -> ChatFailure.INVALID
                else -> ChatFailure.UNEXPECTED
            },
        )
    }
}
