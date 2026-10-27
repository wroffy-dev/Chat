package app.vaultchat.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Error body shared by all backend endpoints (docs/14-api-contracts.md §1). */
@Serializable
data class ApiErrorBody(val error: ApiErrorDetail)

@Serializable
data class ApiErrorDetail(val code: String, val message: String, val requestId: String? = null)

sealed interface ServerHealth {
    data object Reachable : ServerHealth
    /** The server answered but reported a problem (e.g. 503 while not ready). */
    data class Unhealthy(val httpStatus: Int) : ServerHealth
    data object Unreachable : ServerHealth
}

class ApiClient(
    baseUrl: String,
    private val http: OkHttpClient,
    private val json: Json = defaultJson,
) {
    private val base: HttpUrl = baseUrl.toHttpUrl()

    /** Liveness check against GET /healthz. Never throws for network problems. */
    suspend fun checkHealth(): ServerHealth = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(base.resolve("healthz")!!).get().build()
        try {
            http.newCall(request).execute().use { response ->
                if (response.isSuccessful) ServerHealth.Reachable else ServerHealth.Unhealthy(response.code)
            }
        } catch (_: IOException) {
            ServerHealth.Unreachable
        }
    }

    /** Parses the standard error body; returns null for anything that is not one. */
    fun parseError(body: String?): ApiErrorDetail? {
        if (body.isNullOrBlank()) return null
        return try {
            json.decodeFromString(ApiErrorBody.serializer(), body).error
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    companion object {
        val defaultJson = Json { ignoreUnknownKeys = true }

        /**
         * [appVersionCode] is sent as `X-App-Version` on every request; a `426` answer (this build is
         * older than the server's minimum, docs/05 §5) calls [onUpgradeRequired].
         */
        fun defaultHttpClient(appVersionCode: Int? = null, onUpgradeRequired: (() -> Unit)? = null): OkHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = if (appVersionCode != null) chain.request().newBuilder().header("X-App-Version", appVersionCode.toString()).build() else chain.request()
                chain.proceed(request).also { if (it.code == 426) onUpgradeRequired?.invoke() }
            }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            // Redirects could move credentials to another host; the API never redirects.
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
