package app.vaultchat

import android.app.Application
import app.vaultchat.platform.CrashLog
import app.vaultchat.platform.push.Push

class VaultChatApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        container = AppContainer(this)
        Push.init(this)
    }
}
