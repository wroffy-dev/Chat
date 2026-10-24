package app.vaultchat.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.res.stringResource
import app.vaultchat.R
import app.vaultchat.ui.components.EmptyState

/**
 * Home shell. Only reachable after a successful login, which Phase 1 cannot produce.
 * Connection, chat and call entry points arrive in later phases (docs/15-android-ui-ux.md §3.3).
 */
@Composable
fun HomeScreen() {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        EmptyState(
            title = stringResource(R.string.home_not_connected_title),
            body = stringResource(R.string.home_not_connected_body),
            modifier = Modifier.safeDrawingPadding(),
        )
    }
}
