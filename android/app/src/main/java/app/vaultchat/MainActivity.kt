package app.vaultchat

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import app.vaultchat.ui.navigation.VaultChatNavHost
import app.vaultchat.platform.push.Push
import app.vaultchat.ui.theme.VaultChatTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as VaultChatApplication).container
        handleIncomingCallIntent(intent)
        // A call ringing while the screen is off (app open) turns the screen on and shows over the lock
        // screen, so the 10 s auto-answer happens in view, not hidden (owner request 2026-11-03).
        lifecycleScope.launch {
            // Best effort: if a phone refuses, the call still opens rather than the app closing.
            container.wakeForCall.collect { wake -> runCatching { showOverLockScreen(wake) } }
        }
        setContent {
            VaultChatTheme {
                VaultChatNavHost(container = container)
            }
        }
    }

    /** Held while a call rings with the screen off, so the screen comes on (released when the call screen goes). */
    private var wakeLock: PowerManager.WakeLock? = null

    private fun showOverLockScreen(on: Boolean) {
        // With the screen off the activity is stopped: setTurnScreenOn only acts when it resumes, and nothing
        // composes (no ringing, no auto-answer). A wake-up wake lock turns the screen on; Vault was the app in
        // front, so it comes back over the lock screen (setShowWhenLocked) and the 10 s auto-answer runs.
        // No notification is used (D-24). Best effort: a phone may ignore it.
        if (on) {
            if (wakeLock == null) {
                runCatching {
                    @Suppress("DEPRECATION")
                    wakeLock = getSystemService(PowerManager::class.java)
                        ?.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "vault:incoming-call")
                        ?.apply { acquire(60_000L) }
                }
            }
        } else {
            wakeLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
            wakeLock = null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(on)
            setTurnScreenOn(on)
            if (on) getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            if (on) window.addFlags(flags) else window.clearFlags(flags)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingCallIntent(intent)
    }

    /** An incoming-call notification was tapped (or its full-screen intent fired). */
    private fun handleIncomingCallIntent(intent: Intent?) {
        val callId = intent?.getStringExtra(Push.EXTRA_INCOMING_CALL_ID) ?: return
        intent.removeExtra(Push.EXTRA_INCOMING_CALL_ID)
        (application as VaultChatApplication).container.pendingIncomingCallId.value = callId
    }
}
