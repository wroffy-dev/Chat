package app.vaultchat.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    /** The server answered with an error status; [code] is the API error code when present. */
    data class HttpError(val status: Int, val code: String?, val retryAfterSeconds: Int?) : ApiResult<Nothing>
    data object NetworkError : ApiResult<Nothing>
}

/** Small JSON-over-HTTP helper for the backend API (docs/14-api-contracts.md). */
class HttpJson(baseUrl: String, private val http: OkHttpClient, private val json: Json = ApiClient.defaultJson) {
    private val base: HttpUrl = baseUrl.toHttpUrl()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun <B, R> post(
        path: String,
        body: B,
        bodySerializer: KSerializer<B>,
        responseSerializer: KSerializer<R>?,
        accessToken: String? = null,
    ): ApiResult<R> = execute(
        Request.Builder().url(base.resolve(path)!!).post(json.encodeToString(bodySerializer, body).toRequestBody(jsonType)),
        responseSerializer,
        accessToken,
    )

    suspend fun <R> get(path: String, responseSerializer: KSerializer<R>, accessToken: String? = null): ApiResult<R> =
        execute(Request.Builder().url(base.resolve(path)!!).get(), responseSerializer, accessToken)

    @Suppress("UNCHECKED_CAST")
    private suspend fun <R> execute(builder: Request.Builder, serializer: KSerializer<R>?, accessToken: String?): ApiResult<R> =
        withContext(Dispatchers.IO) {
            if (accessToken != null) builder.header("Authorization", "Bearer $accessToken")
            try {
                http.newCall(builder.build()).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        if (serializer == null) ApiResult.Ok(Unit as R) else ApiResult.Ok(json.decodeFromString(serializer, text))
                    } else {
                        val code = try {
                            json.decodeFromString(ApiErrorBody.serializer(), text).error.code
                        } catch (_: SerializationException) {
                            null
                        } catch (_: IllegalArgumentException) {
                            null
                        }
                        ApiResult.HttpError(response.code, code, response.header("Retry-After")?.toIntOrNull())
                    }
                }
            } catch (_: IOException) {
                ApiResult.NetworkError
            } catch (_: SerializationException) {
                ApiResult.HttpError(-1, "UNREADABLE_RESPONSE", null)
            }
        }
}
