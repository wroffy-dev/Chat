package app.vaultchat.platform.call

import android.Manifest
import android.app.Notification
import android.content.pm.PackageManager
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
        // Android 14+ refuses a camera or microphone service type whose permission is not granted, and that
        // closed the app when a call was auto-answered on a phone that had not allowed the camera yet
        // (owner report 2026-11-13, v142, vivo V2130). Only the granted types are asked for; with neither,
        // or if Android still refuses, the call goes on without the service instead of crashing.
        fun granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        val types = (if (granted(Manifest.permission.RECORD_AUDIO)) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0) or
            (if (video && granted(Manifest.permission.CAMERA)) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0)
        try {
            if (types == 0) throw SecurityException("no camera or microphone permission")
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), types)
        } catch (_: Exception) {
            stopSelf()
        }
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
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.call_ongoing))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1002
        private const val EXTRA_VIDEO = "video"

        fun start(context: Context, video: Boolean) {
            // Without the microphone or camera permission there is no service type Android would accept,
            // so the service is not started at all (the call still runs while the app is on screen).
            fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            if (!granted(Manifest.permission.RECORD_AUDIO) && !(video && granted(Manifest.permission.CAMERA))) return
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, CallForegroundService::class.java).putExtra(EXTRA_VIDEO, video))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallForegroundService::class.java))
        }
    }
}
