package app.vaultchat.data.call

import app.vaultchat.data.auth.AuthorizedCaller
import app.vaultchat.data.auth.EmptyBody
import app.vaultchat.data.network.ApiResult
import app.vaultchat.data.network.HttpJson
import app.vaultchat.domain.call.CallFailure
import app.vaultchat.domain.call.CallInfo
import app.vaultchat.domain.call.CallKind
import app.vaultchat.domain.call.CallRepository
import app.vaultchat.domain.call.CallResult
import app.vaultchat.domain.call.CallStatus
import app.vaultchat.domain.call.JoinInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class StartCallBody(val connectionId: String, val kind: String)

@Serializable
data class EndCallBody(val reason: String)

@Serializable
data class DiagnosticsBody(val summary: JsonObject)

@Serializable
data class JoinDto(val callId: String, val livekitUrl: String, val livekitToken: String, val maxVideoQuality: String, val ringTimeoutSeconds: Int)

@Serializable
data class CallPeerDto(val id: String, val username: String)

@Serializable
data class CallDto(
    val id: String,
    val kind: String,
    val status: String,
    val role: String,
    val peer: CallPeerDto,
    val createdAt: String,
    val acceptedAt: String? = null,
    val endedAt: String? = null,
    val endReason: String? = null,
)

@Serializable
data class CurrentCallDto(val call: CallDto? = null)

fun CallDto.toDomain() = CallInfo(
    id = id,
    kind = if (kind == "voice") CallKind.VOICE else CallKind.VIDEO,
    status = CallStatus.valueOf(status.uppercase()),
    isCaller = role == "caller",
    peerUsername = peer.username,
)

fun JoinDto.toDomain() = JoinInfo(callId, livekitUrl, livekitToken, ringTimeoutSeconds)

/** Calls API (docs/14-api-contracts.md §6) with the shared token-refreshing caller. */
class RemoteCallRepository(private val http: HttpJson, private val caller: AuthorizedCaller) : CallRepository {

    override suspend fun start(connectionId: String, kind: CallKind) = caller.call {
        http.post("api/v1/calls", StartCallBody(connectionId, kind.name.lowercase()), StartCallBody.serializer(), JoinDto.serializer(), it)
    }.map { it.toDomain() }

    override suspend fun current() = caller.call { http.get("api/v1/calls/current", CurrentCallDto.serializer(), it) }.map { it.call?.toDomain() }

    override suspend fun get(callId: String) = caller.call { http.get("api/v1/calls/$callId", CallDto.serializer(), it) }.map { it.toDomain() }

    override suspend fun accept(callId: String) = caller.call {
        http.post("api/v1/calls/$callId/accept", EmptyBody(), EmptyBody.serializer(), JoinDto.serializer(), it)
    }.map { it.toDomain() }

    override suspend fun decline(callId: String): CallResult<Unit> = empty("api/v1/calls/$callId/decline")

    override suspend fun cancel(callId: String): CallResult<Unit> = empty("api/v1/calls/$callId/cancel")

    override suspend fun end(callId: String, reason: String): CallResult<Unit> = caller.call<Unit> {
        http.post<EndCallBody, Unit>("api/v1/calls/$callId/end", EndCallBody(reason), EndCallBody.serializer(), null, it)
    }.map { }

    override suspend fun diagnostics(callId: String, summary: Map<String, Any?>): CallResult<Unit> {
        val json = JsonObject(summary.mapValues { (_, v) -> v.toJson() })
        return caller.call<Unit> {
            http.post<DiagnosticsBody, Unit>("api/v1/calls/$callId/diagnostics", DiagnosticsBody(json), DiagnosticsBody.serializer(), null, it)
        }.map { }
    }

    private suspend fun empty(path: String): CallResult<Unit> =
        caller.call<Unit> { http.post<EmptyBody, Unit>(path, EmptyBody(), EmptyBody.serializer(), null, it) }.map { }

    private fun Any?.toJson(): JsonElement = when (this) {
        null -> JsonNull
        is Number -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        else -> JsonPrimitive(toString().take(64))
    }

    private inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): CallResult<R> = when (this) {
        is ApiResult.Ok -> CallResult.Ok(transform(value))
        ApiResult.NetworkError -> CallResult.Failure(CallFailure.NETWORK)
        is ApiResult.HttpError -> CallResult.Failure(
            when {
                code == "CALL_IN_PROGRESS" -> CallFailure.IN_PROGRESS
                code == "CALL_NOT_ACTIVE" -> CallFailure.NOT_ACTIVE
                code == "MEDIA_UNAVAILABLE" -> CallFailure.MEDIA_UNAVAILABLE
                status == 404 -> CallFailure.NOT_FOUND
                status == 429 -> CallFailure.RATE_LIMITED
                AuthorizedCaller.endsSession(this) -> CallFailure.SESSION_ENDED
                else -> CallFailure.UNEXPECTED
            },
        )
    }
}
