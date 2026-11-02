package app.vaultchat.platform

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import app.vaultchat.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Keeps the reason for the app's last crash on the phone, so a tester can read it in About and send it
 * along. Nothing is uploaded. Kotlin/Java crashes are written by an uncaught-exception handler; native
 * crashes (WebRTC, camera) are read from Android's own exit records on Android 11+, with the readable
 * strings of the tombstone (signal, abort message, library and function names) on Android 12+.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"
    private const val SEEN = "last_crash_seen_ms"
    private const val MAX_CHARS = 6_000

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val text = buildString {
                    appendLine(header(app, System.currentTimeMillis(), "crash on thread ${thread.name}"))
                    append(error.stackTraceToString())
                }
                File(app.filesDir, FILE).writeText(text.take(MAX_CHARS))
            } catch (_: Throwable) {
                // Never let the crash log hide the real crash.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The last crash, or null if the app has not crashed since the log was cleared. */
    fun last(context: Context): String? {
        nativeCrash(context)?.let { return it }
        val file = File(context.filesDir, FILE)
        return if (file.exists()) file.readText().ifBlank { null } else null
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
        prefs(context).edit().putLong(SEEN, System.currentTimeMillis()).apply()
    }

    /** A native crash newer than the last clear, from Android's exit records (Android 11+). */
    private fun nativeCrash(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val am = context.getSystemService(ActivityManager::class.java) ?: return null
            val seen = prefs(context).getLong(SEEN, 0L)
            val info = am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
                .firstOrNull { it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE && it.timestamp > seen }
                ?: return null
            buildString {
                appendLine(header(context, info.timestamp, "native crash: ${info.description ?: "–"}"))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    info.traceInputStream?.use { append(readableStrings(it.readBytes())) }
                }
            }.take(MAX_CHARS)
        } catch (_: Throwable) {
            null
        }
    }

    /** Runs of printable characters from the binary tombstone, like the `strings` tool. */
    private fun readableStrings(bytes: ByteArray): String {
        val out = StringBuilder()
        val run = StringBuilder()
        fun flush() {
            if (run.length >= 6) out.appendLine(run)
            run.setLength(0)
        }
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E) run.append(c.toChar()) else flush()
            if (out.length > MAX_CHARS) break
        }
        flush()
        return out.toString()
    }

    private fun header(context: Context, atMs: Long, what: String): String {
        val utc = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return "v${BuildConfig.VERSION_CODE} · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL} · " +
            "${utc.format(Date(atMs))}\n$what"
    }

    private fun prefs(context: Context) = context.getSharedPreferences("crash_log", Context.MODE_PRIVATE)
}
