package app.vaultchat.data.connection

import app.vaultchat.data.auth.AuthorizedCaller
import app.vaultchat.data.network.ApiResult
import app.vaultchat.domain.connection.ConnResult
import app.vaultchat.domain.connection.Connection
import app.vaultchat.domain.connection.ConnectionFailure
import app.vaultchat.domain.connection.ConnectionRepository
import app.vaultchat.domain.connection.ConnectionRequest
import app.vaultchat.domain.connection.QrCode
import app.vaultchat.domain.connection.RequestDirection
import app.vaultchat.domain.connection.RequestStatus

class RemoteConnectionRepository(
    private val api: ConnectionApi,
    private val caller: AuthorizedCaller,
) : ConnectionRepository {

    override suspend fun connections(): ConnResult<List<Connection>> =
        caller.call { api.connections(it) }.map { list -> list.items.map { c -> c.toDomain() } }

    override suspend fun generateQr(): ConnResult<QrCode> = caller.call { api.generateQr(it) }.map { it.toDomain() }

    override suspend fun redeem(token: String): ConnResult<ConnectionRequest> =
        caller.call { api.redeem(it, token) }.map { it.toDomain() }

    override suspend fun requests(direction: RequestDirection?, status: RequestStatus?): ConnResult<List<ConnectionRequest>> =
        caller.call { api.requests(it, direction, status) }.map { list -> list.items.map { r -> r.toDomain() } }

    override suspend fun accept(requestId: String): ConnResult<Connection> =
        caller.call { api.accept(it, requestId) }.map { it.toDomain() }

    override suspend fun reject(requestId: String): ConnResult<Unit> = caller.call { api.reject(it, requestId) }.map { }

    override suspend fun cancel(requestId: String): ConnResult<Unit> = caller.call { api.cancel(it, requestId) }.map { }

    private inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ConnResult<R> = when (this) {
        is ApiResult.Ok -> ConnResult.Ok(transform(value))
        ApiResult.NetworkError -> ConnResult.Failure(ConnectionFailure.NETWORK)
        is ApiResult.HttpError -> ConnResult.Failure(
            when {
                code == "ALREADY_CONNECTED" -> ConnectionFailure.ALREADY_CONNECTED
                code == "QR_INVALID_OR_EXPIRED" -> ConnectionFailure.QR_INVALID
                code == "REQUEST_ALREADY_PENDING" -> ConnectionFailure.ALREADY_PENDING
                code == "REQUEST_NOT_PENDING" -> ConnectionFailure.NOT_PENDING
                status == 404 -> ConnectionFailure.NOT_FOUND
                status == 429 -> ConnectionFailure.RATE_LIMITED
                AuthorizedCaller.endsSession(this) -> ConnectionFailure.SESSION_ENDED
                else -> ConnectionFailure.UNEXPECTED
            },
        )
    }
}
