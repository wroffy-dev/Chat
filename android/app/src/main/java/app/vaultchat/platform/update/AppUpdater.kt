package app.vaultchat.platform.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import app.vaultchat.BuildConfig
import app.vaultchat.VaultChatApplication
import app.vaultchat.data.app.ApkDownloader
import app.vaultchat.data.app.AppVersionApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

sealed interface UpdateState {
    data object None : UpdateState
    data class Available(val versionCode: Int) : UpdateState
    /** Android must first be told to allow installs from this app (a one-time system setting). */
    data class NeedsPermission(val versionCode: Int) : UpdateState
    data class Downloading(val versionCode: Int, val progress: Float) : UpdateState
    /** Waiting for the user to confirm Android's install dialog. */
    data class Installing(val versionCode: Int) : UpdateState
    data class Failed(val versionCode: Int) : UpdateState
}

/**
 * In-app update: when the server has a newer build for download, offers it; on request downloads
 * it, checks its SHA-256 and hands it to Android's package installer. Android replaces the app
 * only if the new APK is signed with the same key as the installed one.
 */
class AppUpdater(
    private val context: Context,
    private val versionApi: AppVersionApi,
    private val downloader: ApkDownloader,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.None)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    @Volatile private var latest: Pair<Int, String>? = null

    /** Asks the server for the latest build; keeps any download or install in progress. */
    fun check() {
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Installing) return
        scope.launch {
            val info = versionApi.fetch() ?: return@launch
            val sha = info.latestApkSha256
            if (info.downloadAvailable && sha != null && info.latestVersionCode > BuildConfig.VERSION_CODE) {
                latest = info.latestVersionCode to sha
                if (_state.value !is UpdateState.Failed) {
                    _state.value = if (canInstall()) UpdateState.Available(info.latestVersionCode) else UpdateState.NeedsPermission(info.latestVersionCode)
                }
            } else {
                latest = null
                _state.value = UpdateState.None
            }
        }
    }

    fun start() {
        val (versionCode, sha) = latest ?: return
        if (!canInstall()) {
            _state.value = UpdateState.NeedsPermission(versionCode)
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        _state.value = UpdateState.Downloading(versionCode, 0f)
        scope.launch {
            val file = File(context.cacheDir, "updates/vaultchat-update.apk").apply { parentFile?.mkdirs() }
            val result = downloader.download(file, sha) { _state.value = UpdateState.Downloading(versionCode, it) }
            if (result != ApkDownloader.Result.Done) {
                _state.value = UpdateState.Failed(versionCode)
                return@launch
            }
            _state.value = UpdateState.Installing(versionCode)
            try {
                withContext(Dispatchers.IO) { install(file) }
            } catch (_: IOException) {
                _state.value = UpdateState.Failed(versionCode)
            } catch (_: SecurityException) {
                _state.value = UpdateState.Failed(versionCode)
            } finally {
                file.delete()
            }
        }
    }

    /** From [UpdateInstallReceiver]: Android refused or the user cancelled. */
    fun installFailed() {
        _state.value = UpdateState.Failed(latest?.first ?: return)
    }

    private fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    private fun install(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("vaultchat.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            // The installer fills in the status extras, so the PendingIntent must be mutable.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val status = PendingIntent.getBroadcast(context, sessionId, Intent(context, UpdateInstallReceiver::class.java), flags)
            session.commit(status.intentSender)
        }
    }
}

/** Install progress from Android: shows its confirmation dialog, or reports a failure. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(context::startActivity)
            }
            // On success Android restarts the app into the new version.
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> (context.applicationContext as VaultChatApplication).container.appUpdater.installFailed()
        }
    }
}
