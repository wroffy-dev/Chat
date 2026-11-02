package app.vaultchat.ui.location

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.vaultchat.R
import app.vaultchat.data.location.ConsentDto
import app.vaultchat.domain.location.LocationBlock
import app.vaultchat.platform.location.LocationSharing
import app.vaultchat.platform.location.LocationUi
import app.vaultchat.ui.components.PrimaryButton
import app.vaultchat.ui.permissions.openAppSettings
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Covers the app while location sharing is not on (owner decision D-06): the user reads what is shared
 * and with whom, then agrees, grants Android's permission and turns the phone's location on. Nothing
 * here is hidden: the same text is reachable later from the menu, where sharing can be turned off.
 */
@Composable
fun LocationRequiredScreen(ui: LocationUi, sharing: LocationSharing, onLogout: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var askedOnce by remember { mutableStateOf(false) }
    // The consent text comes only from the server (backend/consents/location-sharing.json).
    var consent by remember { mutableStateOf<ConsentDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        loading = true
        consent = sharing.consent()
        loading = false
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { sharing.recheck() }

    fun agree() {
        val agreed = consent ?: return
        working = true
        error = null
        scope.launch {
            if (!sharing.enable(agreed.version)) error = R.string.location_error_failed
            working = false
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        askedOnce = true
        sharing.recheck()
        if (sharing.hasPermission() && ui.block == LocationBlock.CONSENT) agree()
    }
    val ask = { permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.size(24.dp))
            Icon(Icons.Filled.LocationOn, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            val title = when (ui.block) {
                LocationBlock.DEVICE_OFF -> stringResource(R.string.location_device_off_title)
                LocationBlock.PERMISSION -> stringResource(R.string.location_permission_title)
                else -> consent?.title ?: stringResource(R.string.location_required_title)
            }
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            when {
                consent != null -> ConsentPoints(consent?.points.orEmpty())
                loading -> Text(stringResource(R.string.location_consent_loading), style = MaterialTheme.typography.bodyMedium)
                else -> {
                    Text(stringResource(R.string.location_error_offline), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { reload++ }) { Text(stringResource(R.string.action_retry)) }
                }
            }
            when (ui.block) {
                LocationBlock.DEVICE_OFF -> {
                    Text(stringResource(R.string.location_device_off_body), style = MaterialTheme.typography.bodyMedium)
                    PrimaryButton(stringResource(R.string.location_open_device_settings), onClick = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: ActivityNotFoundException) {
                            openAppSettings(context)
                        }
                    })
                }
                LocationBlock.PERMISSION -> {
                    Text(stringResource(R.string.location_permission_body), style = MaterialTheme.typography.bodyMedium)
                    // After a refusal Android may not show its dialog again; the app's settings page always works.
                    if (askedOnce) {
                        PrimaryButton(stringResource(R.string.action_open_settings), onClick = { openAppSettings(context) })
                    } else {
                        PrimaryButton(stringResource(R.string.location_allow), onClick = { ask() })
                    }
                }
                else -> PrimaryButton(
                    consent?.agree ?: stringResource(R.string.location_agree),
                    enabled = consent != null,
                    loading = working,
                    onClick = { if (sharing.hasPermission()) agree() else if (askedOnce) openAppSettings(context) else ask() },
                )
            }
            error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = onLogout) { Text(stringResource(R.string.action_log_out)) }
        }
    }
}

@Composable
private fun ConsentPoints(points: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        points.forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium) }
    }
}

/** From the menu: what is shared, when it was last sent and seen, and the switch to turn it off. */
@Composable
fun LocationSettingsDialog(ui: LocationUi, sharing: LocationSharing, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    // What the user agreed to: fetched from the server, or the last copy fetched while offline.
    var consent by remember { mutableStateOf(sharing.cachedConsent()) }
    LaunchedEffect(Unit) { sharing.consent()?.let { consent = it } }
    var confirmOff by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.location_settings_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                consent?.let { ConsentPoints(it.points) }
                Text(stringResource(R.string.location_last_sent, ui.lastSentAt?.let { ago(it) } ?: "—"), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.location_last_viewed, ui.lastViewedByAdminAt?.let { ago(it) } ?: stringResource(R.string.location_never)), style = MaterialTheme.typography.bodySmall)
                if (confirmOff) {
                    Text(stringResource(R.string.location_off_warning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                if (failed) Text(stringResource(R.string.location_error_failed), color = MaterialTheme.colorScheme.error)
                when {
                    ui.sending -> Text(stringResource(R.string.location_indicator_sending), style = MaterialTheme.typography.bodySmall)
                    ui.unavailable -> Text(stringResource(R.string.location_indicator_unavailable), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    ui.sendError != null -> Text(stringResource(R.string.location_indicator_send_failed, ui.sendError), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { sharing.sendNow() }, enabled = !ui.sending) { Text(stringResource(R.string.location_send_now)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (!confirmOff) {
                    confirmOff = true
                } else {
                    scope.launch {
                        val ok = sharing.disable()
                        failed = !ok
                        if (ok) onDismiss()
                    }
                }
            }) { Text(stringResource(if (confirmOff) R.string.location_turn_off_confirm else R.string.location_turn_off)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

private fun ago(at: Instant): String =
    DateUtils.getRelativeTimeSpanString(at.toEpochMilli(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
