package app.vaultchat.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vaultchat.R
import app.vaultchat.domain.permissions.AppPermission
import app.vaultchat.domain.permissions.PermissionStatus
import app.vaultchat.ui.components.BannerKind
import app.vaultchat.ui.components.FullScreenLoading
import app.vaultchat.ui.components.PrimaryButton
import app.vaultchat.ui.components.StatusBanner
import app.vaultchat.ui.permissions.PermissionPreferences
import app.vaultchat.ui.permissions.openAppSettings
import app.vaultchat.ui.permissions.rememberPermissionRequest

@Composable
private fun ScreenScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            }
            Box(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { content() }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------

@Composable
fun InboxScreen(
    viewModel: InboxViewModel,
    onShowQr: () -> Unit,
    onScanQr: () -> Unit,
    onSessionEnded: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleStartEffect(viewModel) {
        viewModel.startPolling()
        onStopOrDispose { viewModel.stopPolling() }
    }
    LaunchedEffect(state.sessionEnded) { if (state.sessionEnded) onSessionEnded() }

    ScreenScaffold(stringResource(R.string.inbox_title), onBack) {
        if (state.loading) {
            FullScreenLoading(Modifier.padding(32.dp))
            return@ScreenScaffold
        }
        state.failure?.let { StatusBanner(BannerKind.ERROR, connectionFailureText(it)) }

        val connection = state.connection
        if (connection != null) {
            StatusBanner(BannerKind.SUCCESS, stringResource(R.string.inbox_connected_with, connection.peerUsername))
            return@ScreenScaffold
        }

        Text(stringResource(R.string.inbox_how_it_works), style = MaterialTheme.typography.bodyMedium)
        PrimaryButton(stringResource(R.string.inbox_show_qr), onShowQr)
        OutlinedButton(onClick = onScanQr, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.inbox_scan_qr)) }

        Text(stringResource(R.string.inbox_requests), style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
        if (state.incoming.isEmpty() && state.outgoing.isEmpty()) {
            Text(
                stringResource(R.string.inbox_no_requests),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        state.incoming.forEach { req ->
            RequestCard(stringResource(R.string.inbox_incoming, req.peerUsername), busy = state.busyRequestId == req.id) {
                OutlinedButton(onClick = { viewModel.reject(req.id) }, enabled = state.busyRequestId == null) { Text(stringResource(R.string.action_reject)) }
                Button(onClick = { viewModel.accept(req.id) }, enabled = state.busyRequestId == null) { Text(stringResource(R.string.action_accept)) }
            }
        }
        state.outgoing.forEach { req ->
            RequestCard(stringResource(R.string.inbox_outgoing, req.peerUsername), busy = state.busyRequestId == req.id) {
                OutlinedButton(onClick = { viewModel.cancel(req.id) }, enabled = state.busyRequestId == null) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    }
}

@Composable
private fun RequestCard(title: String, busy: Boolean, actions: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                if (busy) Text(stringResource(R.string.loading), color = MaterialTheme.colorScheme.onSurfaceVariant) else actions()
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------

@Composable
fun ShowQrScreen(viewModel: ShowQrViewModel, onConnected: () -> Unit, onSessionEnded: () -> Unit, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleStartEffect(viewModel) {
        viewModel.start()
        onStopOrDispose { viewModel.stop() }
    }
    LaunchedEffect(state.connected) { if (state.connected) onConnected() }
    LaunchedEffect(state.sessionEnded) { if (state.sessionEnded) onSessionEnded() }

    ScreenScaffold(stringResource(R.string.show_qr_title), onBack) {
        val incoming = state.incoming
        if (incoming != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.inbox_incoming, incoming.peerUsername), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.show_qr_accept_explainer), style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = viewModel::reject, enabled = !state.deciding) { Text(stringResource(R.string.action_reject)) }
                        Button(onClick = viewModel::accept, enabled = !state.deciding) { Text(stringResource(R.string.action_accept)) }
                    }
                }
            }
            return@ScreenScaffold
        }

        state.failure?.let { StatusBanner(BannerKind.ERROR, connectionFailureText(it)) }
        val qr = state.qr
        if (qr == null) {
            FullScreenLoading(Modifier.padding(32.dp))
        } else {
            QrImage(qr.payload, description = stringResource(R.string.show_qr_image_description))
            Text(
                stringResource(R.string.show_qr_countdown, state.secondsLeft),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(stringResource(R.string.show_qr_instructions), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
    }
}

// ------------------------------------------------------------------------------------------------

@Composable
fun ScanQrScreen(
    viewModel: ScanQrViewModel,
    permissionPreferences: PermissionPreferences,
    onConnected: () -> Unit,
    onSessionEnded: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val camera = rememberPermissionRequest(AppPermission.CAMERA, permissionPreferences)
    LifecycleStartEffect(viewModel) {
        viewModel.startPolling()
        onStopOrDispose { viewModel.stopPolling() }
    }
    LaunchedEffect(state.stage) { if (state.stage == ScanStage.Connected) onConnected() }
    LaunchedEffect(state.sessionEnded) { if (state.sessionEnded) onSessionEnded() }

    ScreenScaffold(stringResource(R.string.scan_qr_title), onBack) {
        when (val stage = state.stage) {
            ScanStage.Scanning -> when (val status = camera.status) {
                PermissionStatus.Granted -> {
                    CameraQrScanner(
                        onDecoded = viewModel::onDecoded,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)),
                    )
                    Text(stringResource(R.string.scan_qr_instructions), textAlign = TextAlign.Center)
                }
                is PermissionStatus.Requestable -> {
                    Text(stringResource(R.string.scan_qr_camera_rationale), textAlign = TextAlign.Center)
                    PrimaryButton(stringResource(R.string.scan_qr_allow_camera), camera::request)
                    if (status.showRationale) {
                        Text(stringResource(R.string.scan_qr_camera_privacy), style = MaterialTheme.typography.bodySmall)
                    }
                }
                PermissionStatus.PermanentlyDenied -> {
                    StatusBanner(BannerKind.WARNING, stringResource(R.string.scan_qr_camera_denied))
                    PrimaryButton(stringResource(R.string.action_open_settings), { openAppSettings(context) })
                }
            }
            ScanStage.Submitting -> FullScreenLoading(Modifier.padding(32.dp))
            is ScanStage.Waiting -> {
                StatusBanner(BannerKind.INFO, stringResource(R.string.scan_qr_waiting, stage.request.peerUsername))
                OutlinedButton(onClick = viewModel::cancel) { Text(stringResource(R.string.action_cancel)) }
            }
            ScanStage.Connected -> FullScreenLoading(Modifier.padding(32.dp))
            is ScanStage.Failed -> {
                val text = when (stage.reason) {
                    ScanFailure.NOT_OUR_CODE -> stringResource(R.string.scan_qr_not_our_code)
                    ScanFailure.REJECTED -> stringResource(R.string.scan_qr_rejected)
                    ScanFailure.EXPIRED -> stringResource(R.string.scan_qr_expired)
                    ScanFailure.CANCELLED -> stringResource(R.string.scan_qr_cancelled)
                    ScanFailure.SERVER -> state.serverFailure?.let { connectionFailureText(it) } ?: stringResource(R.string.login_error_unexpected)
                }
                StatusBanner(BannerKind.WARNING, text)
                PrimaryButton(stringResource(R.string.scan_qr_again), viewModel::scanAgain)
            }
        }
    }
}
