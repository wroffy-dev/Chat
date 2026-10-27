package app.vaultchat.platform.call

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.vaultchat.MainActivity
import app.vaultchat.R
import app.vaultchat.platform.push.Push

/**
 * Keeps an active call running when the app goes to the background (docs/08-calling.md §10).
 * Started only from the call screen while the app is in the foreground (required on Android 14+
 * for camera/microphone services) and stopped when the call ends. It captures nothing itself.
 */
class CallForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val video = intent?.getBooleanExtra(EXTRA_VIDEO, false) ?: false
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            (if (video) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0)
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), types)
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, Push.CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.call_ongoing))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1002
        private const val EXTRA_VIDEO = "video"

        fun start(context: Context, video: Boolean) {
            ContextCompat.startForegroundService(context, Intent(context, CallForegroundService::class.java).putExtra(EXTRA_VIDEO, video))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallForegroundService::class.java))
        }
    }
}
