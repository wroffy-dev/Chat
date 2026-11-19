package app.vaultchat.platform.location

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.vaultchat.VaultChatApplication
import java.util.concurrent.TimeUnit

/**
 * Location while Vault is closed, as on Snap Map (owner, 2026-11-19; D-06): one position about every 15
 * minutes (Android's shortest repeat; it may wait longer to save battery), sent like the one on opening.
 * Runs only with sharing on and "Allow all the time"; otherwise it is cancelled.
 */
object BackgroundLocation {
    private const val WORK = "vault-background-location"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<BackgroundLocationWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        runCatching { WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request) }
    }

    fun cancel(context: Context) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(WORK) }
    }
}

class BackgroundLocationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as VaultChatApplication).container
        // Best effort: a failed reading waits for the next run rather than retrying at once.
        runCatching { container.locationSharing.backgroundUpdate() }
        return Result.success()
    }
}
