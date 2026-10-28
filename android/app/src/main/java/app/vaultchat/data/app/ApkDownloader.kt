package app.vaultchat.data.app

import app.vaultchat.data.auth.AuthorizedCaller
import app.vaultchat.data.network.ApiClient
import app.vaultchat.data.network.ApiErrorBody
import app.vaultchat.data.network.ApiResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Downloads the latest APK (GET /api/v1/app/apk, signed-in users only) to [dest] and checks it
 * against the SHA-256 the server announced, so a cut-off or altered download is never installed.
 */
class ApkDownloader(baseUrl: String, http: OkHttpClient, private val caller: AuthorizedCaller) {
    private val url = baseUrl.toHttpUrl().resolve("api/v1/app/apk")!!
    // The API client's 30 s call limit is too short for a ~40 MB file on a slow network.
    private val client = http.newBuilder().callTimeout(15, TimeUnit.MINUTES).readTimeout(60, TimeUnit.SECONDS).build()

    sealed interface Result {
        data object Done : Result
        data object Failed : Result
        /** The file did not match the announced hash; it has been deleted. */
        data object Corrupt : Result
    }

    suspend fun download(dest: File, expectedSha256: String, onProgress: (Float) -> Unit): Result = withContext(Dispatchers.IO) {
        dest.delete()
        val r = caller.call { token -> fetch(token, dest, onProgress) }
        when {
            r !is ApiResult.Ok -> {
                dest.delete()
                Result.Failed
            }
            !r.value.equals(expectedSha256, ignoreCase = true) -> {
                dest.delete()
                Result.Corrupt
            }
            else -> Result.Done
        }
    }

    /** Streams the body into [dest]; returns its SHA-256 (hex). */
    private suspend fun fetch(token: String, dest: File, onProgress: (Float) -> Unit): ApiResult<String> {
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // The error code matters: TOKEN_EXPIRED makes the caller refresh and retry.
                    val code = runCatching { ApiClient.defaultJson.decodeFromString(ApiErrorBody.serializer(), response.body?.string().orEmpty()).error.code }.getOrNull()
                    return ApiResult.HttpError(response.code, code, null)
                }
                val body = response.body ?: return ApiResult.NetworkError
                val total = body.contentLength().takeIf { it > 0 }
                val digest = MessageDigest.getInstance("SHA-256")
                var done = 0L
                body.byteStream().use { input ->
                    dest.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            done += n
                            if (total != null) onProgress(done.toFloat() / total)
                        }
                    }
                }
                ApiResult.Ok(digest.digest().joinToString("") { "%02x".format(it) })
            }
        } catch (_: IOException) {
            ApiResult.NetworkError
        }
    }
}
