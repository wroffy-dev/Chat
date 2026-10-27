package app.vaultchat

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.vaultchat.ui.navigation.VaultChatNavHost
import app.vaultchat.platform.push.Push
import app.vaultchat.ui.theme.VaultChatTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as VaultChatApplication).container
        handleIncomingCallIntent(intent)
        setContent {
            VaultChatTheme {
                VaultChatNavHost(container = container)
            }
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
