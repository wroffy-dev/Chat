package app.vaultchat.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.vaultchat.BuildConfig
import app.vaultchat.R
import app.vaultchat.data.app.AppVersionApi
import app.vaultchat.data.app.AppVersionInfo
import app.vaultchat.platform.ApkHash

/** This APK's SHA-256, computed once off the main thread; null while computing or if unreadable. */
@Composable
fun rememberApkHash(): String? {
    val context = LocalContext.current
    val hash by produceState<String?>(null) { value = ApkHash.sha256(context) }
    return hash
}

/** Installed version and APK hash, selectable so they can be compared with what the admin published. */
@Composable
fun AboutApp(modifier: Modifier = Modifier) {
    val hash = rememberApkHash()
    SelectionContainer(modifier = modifier) {
        Text(
            stringResource(R.string.about_version, BuildConfig.VERSION_CODE, hash ?: "…"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Shown instead of everything else once the server says this build is too old (426). */
@Composable
fun UpgradeRequiredScreen(api: AppVersionApi) {
    val info by produceState<AppVersionInfo?>(null) { value = api.fetch() }
    val hash = rememberApkHash()
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.upgrade_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.upgrade_body), style = MaterialTheme.typography.bodyLarge)
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.upgrade_installed, BuildConfig.VERSION_CODE, hash ?: "…"), style = MaterialTheme.typography.bodySmall)
                    info?.let {
                        Text(
                            stringResource(R.string.upgrade_latest, it.latestVersionCode, it.latestApkSha256 ?: stringResource(R.string.upgrade_no_hash)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
