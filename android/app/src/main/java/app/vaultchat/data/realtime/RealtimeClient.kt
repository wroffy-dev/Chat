package app.vaultchat.data.realtime

import app.vaultchat.data.auth.AuthorizedCaller
import app.vaultchat.data.network.ApiClient
import app.vaultchat.data.network.ApiErrorBody
import app.vaultchat.data.network.ApiResult
import app.vaultchat.domain.realtime.RealtimeSignal
import app.vaultchat.domain.realtime.RealtimeSignals
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

@Serializable
data class SignalFrame(val type: String, val data: JsonObject = JsonObject(emptyMap()))

/**
 * The app's live connection to GET /api/v1/ws. The token goes in the Authorization header, never
 * the URL. Reconnects with backoff (1, 2, 4 … 30 s); an expired token is refreshed through the shared
 * [AuthorizedCaller]; a revoked session stops it. Run only while the app is in front: in the
 * background, calls arrive by push instead.
 */
class RealtimeClient(
    baseUrl: String,
    http: OkHttpClient,
    private val caller: AuthorizedCaller,
    private val json: Json = ApiClient.defaultJson,
) : RealtimeSignals {
    private val url = baseUrl.toHttpUrl().resolve("api/v1/ws")!!
    private val client = http.newBuilder().readTimeout(0, TimeUnit.SECONDS).pingInterval(30, TimeUnit.SECONDS).build()

    private val _signals = MutableSharedFlow<RealtimeSignal>(extraBufferCapacity = 64)
    override val signals: SharedFlow<RealtimeSignal> = _signals.asSharedFlow()
    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** Runs until cancelled or the session ends. */
    suspend fun run() {
        var backoffMs = 1_000L
        while (currentCoroutineContext().isActive) {
            val r = caller.call { token -> session(token) }
            if (r is ApiResult.HttpError && AuthorizedCaller.endsSession(r)) return
            // A connection that worked and then dropped comes back quickly; repeated failures slow down.
            backoffMs = if (r is ApiResult.Ok) 1_000L else minOf(backoffMs * 2, 30_000L)
            delay(if (r is ApiResult.Ok) 1_000L else backoffMs)
        }
    }

    /** One connection, until it closes. Ok = it was open and then ended; errors map close codes. */
    private suspend fun session(token: String): ApiResult<Unit> = suspendCancellableCoroutine { cont ->
        val done = AtomicBoolean(false)
        val opened = AtomicBoolean(false)
        fun finish(result: ApiResult<Unit>) {
            _connected.value = false
            if (done.compareAndSet(false, true)) cont.resume(result)
        }
        val socket = client.newWebSocket(
            Request.Builder().url(url).header("Authorization", "Bearer $token").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened.set(true)
                    _connected.value = true
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    parse(text)?.let { _signals.tryEmit(it) }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finish(closeResult(code, opened.get()))

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    finish(if (!opened.get() && response != null) handshakeError(response) else if (opened.get()) ApiResult.Ok(Unit) else ApiResult.NetworkError)
                }
            },
        )
        cont.invokeOnCancellation {
            socket.cancel()
            _connected.value = false
        }
    }

    private fun parse(text: String): RealtimeSignal? = try {
        val frame = json.decodeFromString(SignalFrame.serializer(), text)
        fun field(name: String) = frame.data[name]?.jsonPrimitive?.contentOrNull
        when (frame.type) {
            "chat.changed" -> field("conversationId")?.let { RealtimeSignal.ChatChanged(it) }
            "call.changed" -> field("callId")?.let { RealtimeSignal.CallChanged(it) }
            "connection.changed" -> field("requestId")?.let { RealtimeSignal.ConnectionChanged(it) }
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    private fun handshakeError(response: Response): ApiResult.HttpError {
        val code = try {
            json.decodeFromString(ApiErrorBody.serializer(), response.body?.string().orEmpty()).error.code
        } catch (_: Exception) {
            null
        }
        return ApiResult.HttpError(response.code, code, null)
    }

    companion object {
        /** Server close codes (docs/14 §10) mapped to the API errors the shared caller understands. */
        fun closeResult(code: Int, opened: Boolean): ApiResult<Unit> = when (code) {
            4401 -> ApiResult.HttpError(401, "TOKEN_EXPIRED", null)
            4403 -> ApiResult.HttpError(401, "SESSION_REVOKED", null)
            else -> if (opened) ApiResult.Ok(Unit) else ApiResult.NetworkError
        }
    }
}
