package app.vaultchat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.vaultchat.ui.navigation.VaultChatNavHost
import app.vaultchat.ui.theme.VaultChatTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as VaultChatApplication).container
        setContent {
            VaultChatTheme {
                VaultChatNavHost(container = container)
            }
        }
    }
}
