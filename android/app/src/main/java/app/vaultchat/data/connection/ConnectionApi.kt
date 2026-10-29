package app.vaultchat.data.connection

import app.vaultchat.data.auth.EmptyBody
import app.vaultchat.data.network.ApiResult
import app.vaultchat.data.network.HttpJson
import app.vaultchat.domain.connection.Connection
import app.vaultchat.domain.connection.ConnectionRequest
import app.vaultchat.domain.connection.QrCode
import app.vaultchat.domain.connection.RequestDirection
import app.vaultchat.domain.connection.RequestStatus
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class PeerDto(val id: String, val username: String)

@Serializable
data class ConnectionDto(
    val id: String,
    val peer: PeerDto,
    val conversationId: String,
    val createdAt: String,
    // Only in GET /connections (the chat list).
    val unreadCount: Int = 0,
    val lastMessageAt: String? = null,
)

@Serializable
data class RequestPeerDto(val username: String)

@Serializable
data class RequestDto(
    val id: String,
    val direction: String,
    val peer: RequestPeerDto,
    val status: String,
    val createdAt: String,
    val expiresAt: String,
)

@Serializable
data class RequestListDto(val items: List<RequestDto>)

@Serializable
data class ConnectionListDto(val items: List<ConnectionDto>)

@Serializable
data class QrDto(val token: String, val qrPayload: String, val expiresAt: String)

@Serializable
data class RedeemBody(val token: String)

fun ConnectionDto.toDomain() = Connection(
    id = id,
    peerId = peer.id,
    peerUsername = peer.username,
    conversationId = conversationId,
    sinceEpochMs = Instant.parse(createdAt).toEpochMilli(),
    unreadCount = unreadCount,
    lastMessageAtEpochMs = lastMessageAt?.let { Instant.parse(it).toEpochMilli() },
)

fun RequestDto.toDomain() = ConnectionRequest(
    id = id,
    direction = if (direction == "incoming") RequestDirection.INCOMING else RequestDirection.OUTGOING,
    peerUsername = peer.username,
    status = RequestStatus.valueOf(status.uppercase()),
    expiresAtEpochMs = Instant.parse(expiresAt).toEpochMilli(),
)

fun QrDto.toDomain() = QrCode(token, qrPayload, Instant.parse(expiresAt).toEpochMilli())

/** Typed calls for the QR-connection endpoints (docs/14-api-contracts.md §4). */
class ConnectionApi(private val http: HttpJson) {
    suspend fun connections(token: String): ApiResult<ConnectionListDto> =
        http.get("api/v1/connections", ConnectionListDto.serializer(), token)

    suspend fun generateQr(token: String): ApiResult<QrDto> =
        http.post("api/v1/connection-qr", EmptyBody(), EmptyBody.serializer(), QrDto.serializer(), token)

    suspend fun redeem(token: String, qrToken: String): ApiResult<RequestDto> =
        http.post("api/v1/connection-requests", RedeemBody(qrToken), RedeemBody.serializer(), RequestDto.serializer(), token)

    suspend fun requests(token: String, direction: RequestDirection?, status: RequestStatus?): ApiResult<RequestListDto> {
        val query = listOfNotNull(
            direction?.let { "direction=${it.name.lowercase()}" },
            status?.let { "status=${it.name.lowercase()}" },
        ).joinToString("&")
        return http.get("api/v1/connection-requests" + if (query.isEmpty()) "" else "?$query", RequestListDto.serializer(), token)
    }

    suspend fun accept(token: String, id: String): ApiResult<ConnectionDto> =
        http.post("api/v1/connection-requests/$id/accept", EmptyBody(), EmptyBody.serializer(), ConnectionDto.serializer(), token)

    suspend fun reject(token: String, id: String): ApiResult<Unit> =
        http.post("api/v1/connection-requests/$id/reject", EmptyBody(), EmptyBody.serializer(), null, token)

    suspend fun cancel(token: String, id: String): ApiResult<Unit> =
        http.post("api/v1/connection-requests/$id/cancel", EmptyBody(), EmptyBody.serializer(), null, token)
}
