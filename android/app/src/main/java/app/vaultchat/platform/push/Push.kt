package app.vaultchat.platform.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import app.vaultchat.BuildConfig
import app.vaultchat.MainActivity
import app.vaultchat.R
import app.vaultchat.VaultChatApplication
import app.vaultchat.data.push.PushTokenApi
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object Push {
    const val CHANNEL_CALLS = "calls"
    const val CHANNEL_ONGOING = "ongoing_call"
    const val CHANNEL_MESSAGES = "messages"
    const val EXTRA_INCOMING_CALL_ID = "app.vaultchat.INCOMING_CALL_ID"
    private const val INCOMING_NOTIFICATION_ID = 1001
    private const val MESSAGE_NOTIFICATION_ID = 1002

    /** True when the build carries Firebase identifiers (docs/10-notifications.md). */
    val configured: Boolean
        get() = BuildConfig.FCM_PROJECTID.isNotEmpty() && BuildConfig.FCM_APPID.isNotEmpty() &&
            BuildConfig.FCM_APIKEY.isNotEmpty() && BuildConfig.FCM_SENDERID.isNotEmpty()

    /** Called once from Application.onCreate. Without identifiers, push stays off. */
    fun init(context: Context) {
        createChannels(context)
        if (!configured || FirebaseApp.getApps(context).isNotEmpty()) return
        FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setProjectId(BuildConfig.FCM_PROJECTID)
                .setApplicationId(BuildConfig.FCM_APPID)
                .setApiKey(BuildConfig.FCM_APIKEY)
                .setGcmSenderId(BuildConfig.FCM_SENDERID)
                .build(),
        )
    }

    /** After sign-in: enable FCM (auto-init is off until then) and register the token. */
    suspend fun registerAfterLogin(api: PushTokenApi) {
        if (!configured) return
        val messaging = FirebaseMessaging.getInstance()
        messaging.isAutoInitEnabled = true
        val token = suspendCancellableCoroutine<String?> { cont ->
            messaging.token.addOnCompleteListener { task -> cont.resume(if (task.isSuccessful) task.result else null) }
        } ?: return
        api.register(token)
    }

    /** On logout: the server forgets the token with the session; the device deletes it too. */
    fun forgetOnDevice() {
        if (!configured) return
        val messaging = FirebaseMessaging.getInstance()
        messaging.isAutoInitEnabled = false
        messaging.deleteToken()
    }

    private fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CALLS, context.getString(R.string.channel_calls), NotificationManager.IMPORTANCE_HIGH),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, context.getString(R.string.channel_ongoing_call), NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, context.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /** "New message" only: no sender, no text, no preview (docs/07 §4, docs/10 §2). */
    fun showNewMessage(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val openPending = PendingIntent.getActivity(context, MESSAGE_NOTIFICATION_ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.chat_new_message))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(openPending)
            .build()
        NotificationManagerCompat.from(context).notify(MESSAGE_NOTIFICATION_ID, notification)
    }

    fun cancelMessageNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(MESSAGE_NOTIFICATION_ID)
    }

    /**
     * Incoming-call notification. Generic text only: the push carries no name or content, and
     * nothing sensitive appears on the lock screen.
     */
    fun showIncomingCall(context: Context, callId: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val open = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_INCOMING_CALL_ID, callId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val openPending = PendingIntent.getActivity(context, callId.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val decline = Intent(context, DeclineCallReceiver::class.java).putExtra(EXTRA_INCOMING_CALL_ID, callId)
        val declinePending = PendingIntent.getBroadcast(context, callId.hashCode(), decline, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val caller = Person.Builder().setName(context.getString(R.string.call_incoming)).setImportant(true).build()

        val notification = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.call_incoming))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, declinePending, openPending))
            .setFullScreenIntent(openPending, true)
            .setContentIntent(openPending)
            .build()
        NotificationManagerCompat.from(context).notify(INCOMING_NOTIFICATION_ID, notification)
    }

    fun cancelIncomingCall(context: Context) {
        NotificationManagerCompat.from(context).cancel(INCOMING_NOTIFICATION_ID)
    }
}

/** Receives FCM data messages: {t: "call" | "call_end", id} and {t: "msg"}. */
class VaultChatMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(message: RemoteMessage) {
        val id = message.data["id"]
        when (message.data["t"]) {
            "call" -> if (id != null) Push.showIncomingCall(applicationContext, id)
            "call_end" -> Push.cancelIncomingCall(applicationContext)
            "msg" -> Push.showNewMessage(applicationContext)
        }
    }

    override fun onNewToken(token: String) {
        val container = (application as VaultChatApplication).container
        // Only a signed-in session can own a token; otherwise it is registered at next login.
        if (container.authRepository.currentUser() != null) {
            scope.launch { container.pushTokenApi.register(token) }
        }
    }
}

/** "Decline" on the incoming-call notification, without opening the app. */
class DeclineCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(Push.EXTRA_INCOMING_CALL_ID) ?: return
        Push.cancelIncomingCall(context)
        val pending = goAsync()
        val container = (context.applicationContext as VaultChatApplication).container
        CoroutineScope(Dispatchers.IO).launch {
            try {
                container.callRepository.decline(id)
            } finally {
                pending.finish()
            }
        }
    }
}
