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
    private const val MAX_CHARS = 12_000

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
        if (file.exists()) file.readText().ifBlank { null }?.let { return it }
        return otherExit(context)
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
                // Reading the tombstone can fail (size, access); the crash is still reported without it.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching {
                    info.traceInputStream?.use { stream ->
                        val text = readableStrings(stream.readBytes())
                        // The crashing thread is the one that called abort(); show it first, because the log
                        // is cut at MAX_CHARS and the threads come in no useful order.
                        val at = text.indexOf("abort")
                        if (at > 0) {
                            appendLine("--- crashing thread (around abort) ---")
                            appendLine(text.substring((at - 1_500).coerceAtLeast(0), (at + 2_500).coerceAtMost(text.length)))
                            appendLine("--- all threads ---")
                        }
                        // Room left for the app log below.
                        append(text.take(5_000))
                    }
                }.onFailure { appendLine("(tombstone not readable: ${it.javaClass.simpleName})") }
                appendLine("--- app log (warnings and errors) ---")
                append(ownLog())
            }.take(MAX_CHARS)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * The app closed while on screen for another reason Android records: frozen (ANR, with its thread dump),
     * killed by the system (memory, a signal, too much work) or a crash the handler above could not write
     * (owner 2026-11-21: "app apne aap band ho rhi h" with no crash log). Exits in the background (Android
     * freeing memory) are ignored. The app's own recent warnings and errors from the system log are added.
     */
    private fun otherExit(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val am = context.getSystemService(ActivityManager::class.java) ?: return null
            val seen = prefs(context).getLong(SEEN, 0L)
            val info = am.getHistoricalProcessExitReasons(context.packageName, 0, 5).firstOrNull {
                it.timestamp > seen && it.reason !in NORMAL &&
                    (it.reason == ApplicationExitInfo.REASON_ANR || it.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE)
            } ?: return null
            buildString {
                appendLine(header(context, info.timestamp, "app closed: ${reasonName(info.reason)} · ${info.description ?: "–"} · importance ${info.importance}"))
                if (info.reason == ApplicationExitInfo.REASON_ANR) {
                    // For a freeze the trace is plain text: the main thread's stack shows where it was stuck.
                    info.traceInputStream?.use { stream ->
                        val text = stream.readBytes().toString(Charsets.UTF_8)
                        val main = text.indexOf("\"main\"")
                        appendLine("--- main thread ---")
                        appendLine(if (main >= 0) text.substring(main, (main + 4_000).coerceAtMost(text.length)) else text.take(4_000))
                    }
                }
                appendLine("--- app log (warnings and errors) ---")
                append(ownLog())
            }.take(MAX_CHARS)
        } catch (_: Throwable) {
            null
        }
    }

    /** This app's own recent warnings and errors (an app may read only its own lines of the system log). */
    private fun ownLog(): String = try {
        val process = ProcessBuilder("logcat", "-d", "-t", "400", "-v", "time", "*:W").redirectErrorStream(true).start()
        val lines = process.inputStream.bufferedReader().readLines()
        process.destroy()
        lines.takeLast(120).joinToString("\n")
    } catch (_: Throwable) {
        "(not readable)"
    }

    /** Exits that are normal or already covered (native crashes, above): everything else on screen is shown. */
    private val NORMAL = setOf(
        ApplicationExitInfo.REASON_EXIT_SELF,
        ApplicationExitInfo.REASON_USER_REQUESTED,
        ApplicationExitInfo.REASON_USER_STOPPED,
        ApplicationExitInfo.REASON_PERMISSION_CHANGE,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        15, // REASON_PACKAGE_STATE_CHANGE (Android 13)
        16, // REASON_PACKAGE_UPDATED (Android 14): the in-app update replaced the app
    )

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "froze (ANR)"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "too much work"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "start failed"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "a dependency died"
        ApplicationExitInfo.REASON_OTHER -> "closed by the system"
        else -> "unknown ($reason)"
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
