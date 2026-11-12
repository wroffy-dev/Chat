package app.vaultchat.platform.call

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.vaultchat.R
import app.vaultchat.VaultChatApplication
import app.vaultchat.domain.call.listenToTalk
import app.vaultchat.platform.push.Push
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Plays an incoming walkie-talkie message aloud (owner decision D-26, 2026-11-12): joins the talk call
 * without a microphone and plays it on the loudspeaker, also with the screen off or the app closed (then
 * woken by the push). Nothing is recorded or stored. It plays as call audio, which a silenced ringer does
 * not mute, and at a clearly audible volume (owner: "always play"); the volume is put back afterwards.
 */
object TalkPlayer {
    /** The message being played, so the push and the open app's own check do not both play it. */
    @Volatile
    private var playing: String? = null

    fun play(context: Context, callId: String) {
        val app = context.applicationContext
        synchronized(this) {
            if (playing != null) return
            playing = callId
        }
        // Keeps the process alive while it plays. Android may refuse a service started from the background;
        // an app that is open (screen off) still plays without it.
        runCatching { ContextCompat.startForegroundService(app, Intent(app, TalkPlaybackService::class.java)) }
        val container = (app as VaultChatApplication).container
        container.applicationScope.launch(Dispatchers.Main.immediate) {
            val audio = app.getSystemService(AudioManager::class.java)
            val before = audio?.let { runCatching { it.getStreamVolume(AudioManager.STREAM_VOICE_CALL) }.getOrNull() }
            var media: LiveKitMediaSession? = null
            try {
                if (audio != null && before != null) {
                    val wanted = (audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL) * LOUDNESS).toInt()
                    if (before < wanted) runCatching { audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, wanted, 0) }
                }
                media = LiveKitMediaSession(app)
                listenToTalk(container.callRepository, media, callId)
            } catch (_: Throwable) {
                // Nothing to play (camera/audio stack refused, no network): never take the app down for it.
            } finally {
                runCatching { media?.disconnect() }
                if (audio != null && before != null) runCatching { audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, before, 0) }
                playing = null
                runCatching { app.stopService(Intent(app, TalkPlaybackService::class.java)) }
            }
        }
    }

    /** At least this share of the full call volume while a message plays. */
    private const val LOUDNESS = 0.8f
}

/** Foreground service that only keeps the app alive while [TalkPlayer] plays; it shows a minimised notice. */
class TalkPlaybackService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        } catch (_: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification =
        NotificationCompat.Builder(this, Push.CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.talk_playing))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

    private companion object {
        const val NOTIFICATION_ID = 1003
    }
}
