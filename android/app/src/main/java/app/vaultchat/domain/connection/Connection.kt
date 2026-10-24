package app.vaultchat.domain.connection

data class Connection(val id: String, val peerId: String, val peerUsername: String, val sinceEpochMs: Long)

enum class RequestDirection { INCOMING, OUTGOING }

enum class RequestStatus { PENDING, ACCEPTED, REJECTED, CANCELLED, EXPIRED }

data class ConnectionRequest(
    val id: String,
    val direction: RequestDirection,
    val peerUsername: String,
    val status: RequestStatus,
    val expiresAtEpochMs: Long,
)

data class QrCode(val token: String, val payload: String, val expiresAtEpochMs: Long)

enum class ConnectionFailure {
    ALREADY_CONNECTED,
    /** Unknown, expired, already used, replaced, or the user's own code — the server does not say which. */
    QR_INVALID,
    ALREADY_PENDING,
    NOT_PENDING,
    NOT_FOUND,
    RATE_LIMITED,
    SESSION_ENDED,
    NETWORK,
    UNEXPECTED,
}

sealed interface ConnResult<out T> {
    data class Ok<T>(val value: T) : ConnResult<T>
    data class Failure(val reason: ConnectionFailure) : ConnResult<Nothing>
}

interface ConnectionRepository {
    suspend fun connection(): ConnResult<Connection?>
    suspend fun generateQr(): ConnResult<QrCode>
    suspend fun redeem(token: String): ConnResult<ConnectionRequest>
    suspend fun requests(direction: RequestDirection? = null, status: RequestStatus? = null): ConnResult<List<ConnectionRequest>>
    suspend fun accept(requestId: String): ConnResult<Connection>
    suspend fun reject(requestId: String): ConnResult<Unit>
    suspend fun cancel(requestId: String): ConnResult<Unit>
}

/** Parses scanned QR text. Only this app's own format is accepted; anything else is ignored, never opened. */
object QrPayload {
    private val pattern = Regex("^vaultchat://connect\\?v=1&t=(vcq_[A-Za-z0-9_-]{43})$")

    fun tokenFrom(raw: String): String? = pattern.matchEntire(raw.trim())?.groupValues?.get(1)
}
