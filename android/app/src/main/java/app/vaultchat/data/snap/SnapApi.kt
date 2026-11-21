package app.vaultchat.data.snap

import app.vaultchat.data.auth.AuthorizedCaller
import app.vaultchat.data.network.ApiClient
import app.vaultchat.data.network.ApiErrorBody
import app.vaultchat.data.network.ApiResult
import app.vaultchat.domain.snap.OpenedSnap
import app.vaultchat.domain.snap.SnapFailure
import app.vaultchat.domain.snap.SnapKind
import app.vaultchat.domain.snap.SnapRepository
import app.vaultchat.domain.snap.SnapResult
import app.vaultchat.domain.snap.WaitingSnap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

@Serializable
private data class SnapFromDto(val id: String, val username: String)

@Serializable
private data class SnapDto(val id: String, val connectionId: String, val from: SnapFromDto, val kind: String, val createdAt: String, val viewOnce: Boolean = false)

@Serializable
private data class SnapListDto(val items: List<SnapDto>)

@Serializable
private data class SentSnapDto(val id: String, val streak: Int)

/** Snaps API (docs/14-api-contracts.md §5a) with the shared token-refreshing caller. Bytes stay in memory. */
class RemoteSnapRepository(baseUrl: String, http: OkHttpClient, private val caller: AuthorizedCaller) : SnapRepository {
    private val base: HttpUrl = baseUrl.toHttpUrl()
    // A 20 MB video on a slow network needs longer than the API client's 30 s.
    private val client = http.newBuilder().callTimeout(3, TimeUnit.MINUTES).readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).build()
    private val json = ApiClient.defaultJson

    override suspend fun send(connectionId: String, kind: SnapKind, bytes: ByteArray, viewOnce: Boolean): SnapResult<Int> = withContext(Dispatchers.IO) {
        val type = if (kind == SnapKind.PHOTO) "image/jpeg" else "video/mp4"
        val url = base.resolve("api/v1/connections/$connectionId/snaps?kind=${kind.name.lowercase()}" + if (viewOnce) "&once=true" else "")!!
        caller.call { token ->
            execute(Request.Builder().url(url).header("Authorization", "Bearer $token").post(bytes.toRequestBody(type.toMediaType()))) { r ->
                json.decodeFromString(SentSnapDto.serializer(), r.body!!.string()).streak
            }
        }.toSnap()
    }

    override suspend fun inbox(): SnapResult<List<WaitingSnap>> = withContext(Dispatchers.IO) {
        caller.call { token ->
            execute(Request.Builder().url(base.resolve("api/v1/snaps")!!).header("Authorization", "Bearer $token").get()) { r ->
                json.decodeFromString(SnapListDto.serializer(), r.body!!.string()).items.map {
                    WaitingSnap(it.id, it.connectionId, it.from.username, if (it.kind == "video") SnapKind.VIDEO else SnapKind.PHOTO, Instant.parse(it.createdAt).toEpochMilli(), it.viewOnce)
                }
            }
        }.toSnap()
    }

    override suspend fun open(snapId: String): SnapResult<OpenedSnap> = withContext(Dispatchers.IO) {
        caller.call { token ->
            val req = Request.Builder().url(base.resolve("api/v1/snaps/$snapId/open")!!).header("Authorization", "Bearer $token")
                .post("{}".toRequestBody("application/json".toMediaType()))
            execute(req) { r ->
                val video = r.header("Content-Type").orEmpty().startsWith("video/")
                OpenedSnap(if (video) SnapKind.VIDEO else SnapKind.PHOTO, r.body!!.bytes(), viewOnce = r.header("X-Snap-View-Once") == "true")
            }
        }.toSnap()
    }

    private fun <T> execute(builder: Request.Builder, read: (Response) -> T): ApiResult<T> = try {
        client.newCall(builder.build()).execute().use { response ->
            if (response.isSuccessful) {
                ApiResult.Ok(read(response))
            } else {
                // The error code matters: TOKEN_EXPIRED makes the caller refresh and retry.
                val code = runCatching { json.decodeFromString(ApiErrorBody.serializer(), response.body?.string().orEmpty()).error.code }.getOrNull()
                ApiResult.HttpError(response.code, code, null)
            }
        }
    } catch (_: IOException) {
        ApiResult.NetworkError
    }

    private fun <T> ApiResult<T>.toSnap(): SnapResult<T> = when (this) {
        is ApiResult.Ok -> SnapResult.Ok(value)
        ApiResult.NetworkError -> SnapResult.Failure(SnapFailure.NETWORK)
        is ApiResult.HttpError -> SnapResult.Failure(
            when {
                status == 404 -> SnapFailure.NOT_FOUND
                status == 413 -> SnapFailure.TOO_LARGE
                status == 503 -> SnapFailure.UNAVAILABLE
                status == 429 -> SnapFailure.RATE_LIMITED
                AuthorizedCaller.endsSession(this) -> SnapFailure.SESSION_ENDED
                else -> SnapFailure.UNEXPECTED
            },
        )
    }
}
