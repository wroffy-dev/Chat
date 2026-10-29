package app.vaultchat.ui.connect

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vaultchat.R
import app.vaultchat.domain.permissions.AppPermission
import app.vaultchat.domain.permissions.PermissionStatus
import app.vaultchat.ui.components.AppIcons
import app.vaultchat.ui.components.BannerKind
import app.vaultchat.ui.components.FullScreenLoading
import app.vaultchat.ui.components.InitialsAvatar
import app.vaultchat.ui.components.PrimaryButton
import app.vaultchat.ui.components.StatusBanner
import app.vaultchat.ui.components.TopBar
import app.vaultchat.ui.permissions.PermissionPreferences
import app.vaultchat.ui.permissions.openAppSettings
import app.vaultchat.ui.permissions.rememberPermissionRequest

@Composable
private fun ScreenScaffold(
    title: String,
    onBack: () -> Unit,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                TopBar(title, onBack)
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
            overlay()
        }
    }
}

/** Pill-shaped outlined button in the design's outline colour. */
@Composable
private fun OutlinePill(text: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        modifier = modifier,
    ) { Text(text) }
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

        Text(
            stringResource(R.string.inbox_how_it_works),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionTile(stringResource(R.string.inbox_show_qr), AppIcons.QrCode, filled = true, onClick = onShowQr, modifier = Modifier.weight(1f))
            ActionTile(stringResource(R.string.inbox_scan_qr), AppIcons.PhotoCamera, filled = false, onClick = onScanQr, modifier = Modifier.weight(1f))
        }

        Text(
            stringResource(R.string.inbox_requests),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp).semantics { heading() },
        )
        if (state.incoming.isEmpty() && state.outgoing.isEmpty()) {
            Text(
                stringResource(R.string.inbox_no_requests),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            )
        }
        val idle = state.busyRequestId == null
        state.incoming.forEach { req ->
            RequestCard(
                peerUsername = req.peerUsername,
                title = stringResource(R.string.inbox_incoming, req.peerUsername),
                meta = stringResource(R.string.inbox_incoming_expires, minutesLeft(req.expiresAtEpochMs)),
                busy = state.busyRequestId == req.id,
            ) {
                OutlinePill(stringResource(R.string.action_reject), { viewModel.reject(req.id) }, enabled = idle)
                Button(onClick = { viewModel.accept(req.id) }, enabled = idle) { Text(stringResource(R.string.action_accept)) }
            }
        }
        state.outgoing.forEach { req ->
            RequestCard(
                peerUsername = req.peerUsername,
                title = stringResource(R.string.inbox_outgoing, req.peerUsername),
                meta = stringResource(R.string.inbox_outgoing_expires, minutesLeft(req.expiresAtEpochMs)),
                busy = state.busyRequestId == req.id,
            ) {
                OutlinePill(stringResource(R.string.action_cancel), { viewModel.cancel(req.id) }, enabled = idle)
            }
        }
    }
}

/** Whole minutes until [epochMs], rounded up, at least 1. */
private fun minutesLeft(epochMs: Long): Int =
    ((epochMs - System.currentTimeMillis() + 59_999) / 60_000).toInt().coerceAtLeast(1)

/** One of the two big Inbox buttons: an icon at the top, the label at the bottom. */
@Composable
private fun ActionTile(label: String, icon: ImageVector, filled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (filled) colors.primary else colors.surface,
        contentColor = if (filled) colors.onPrimary else colors.onSurface,
        modifier = modifier.height(128.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun RequestCard(peerUsername: String, title: String, meta: String, busy: Boolean, actions: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                InitialsAvatar(peerUsername, size = 40.dp)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
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

    ScreenScaffold(
        title = stringResource(R.string.show_qr_title),
        onBack = onBack,
        overlay = {
            val incoming = state.incoming
            if (incoming != null) {
                RequestSheet(
                    peerUsername = incoming.peerUsername,
                    deciding = state.deciding,
                    onReject = viewModel::reject,
                    onAccept = viewModel::accept,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        },
    ) {
        state.failure?.let { StatusBanner(BannerKind.ERROR, connectionFailureText(it)) }
        Spacer(Modifier.height(8.dp))
        val qr = state.qr
        if (qr == null) {
            FullScreenLoading(Modifier.padding(32.dp))
        } else {
            Surface(shape = RoundedCornerShape(24.dp), color = Color.White, shadowElevation = 1.dp) {
                QrImage(qr.payload, description = stringResource(R.string.show_qr_image_description), size = 248.dp, modifier = Modifier.padding(12.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CountdownRing(fraction = state.secondsLeft / QR_LIFETIME_S)
                Text(
                    stringResource(R.string.show_qr_countdown, state.secondsLeft),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        Text(
            stringResource(R.string.show_qr_instructions),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.widthIn(max = 300.dp),
        )
    }
}

private const val QR_LIFETIME_S = 60f

/** A small ring that empties as the code's time runs out. Decorative: the text gives the seconds. */
@Composable
private fun CountdownRing(fraction: Float) {
    val track = MaterialTheme.colorScheme.outline
    val ink = MaterialTheme.colorScheme.primary
    Canvas(modifier = Modifier.size(22.dp)) {
        val stroke = 2.5.dp.toPx()
        val inset = stroke / 2 + 1.dp.toPx()
        val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
        val topLeft = Offset(inset, inset)
        drawArc(track, 0f, 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
        drawArc(
            ink, -90f, 360f * fraction.coerceIn(0f, 1f), useCenter = false, topLeft = topLeft, size = arcSize,
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

/** "<name> wants to connect" as a bottom sheet over a scrim, on top of the QR code. */
@Composable
private fun RequestSheet(peerUsername: String, deciding: Boolean, onReject: () -> Unit, onAccept: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            // Swallows taps so the screen behind cannot be used while the request waits.
            .pointerInput(Unit) { detectTapGestures { } },
    )
    Surface(
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.navigationBarsPadding().padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(width = 32.dp, height = 4.dp)
                    .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)),
            )
            Row(
                modifier = Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                InitialsAvatar(peerUsername, size = 48.dp)
                Text(
                    stringResource(R.string.inbox_incoming, peerUsername),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(
                stringResource(R.string.show_qr_accept_explainer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinePill(stringResource(R.string.action_reject), onReject, enabled = !deciding, modifier = Modifier.weight(1f).height(52.dp))
                Button(onClick = onAccept, enabled = !deciding, modifier = Modifier.weight(1f).height(52.dp)) {
                    Text(stringResource(R.string.action_accept), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
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
                    Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp)).background(Color(0xFF16181C))) {
                        CameraQrScanner(onDecoded = viewModel::onDecoded, modifier = Modifier.fillMaxSize())
                        ViewfinderOverlay(Modifier.fillMaxSize())
                    }
                    Text(stringResource(R.string.scan_qr_instructions), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.scan_qr_camera_privacy),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.widthIn(max = 300.dp),
                    )
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
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(R.string.scan_qr_waiting, stage.request.peerUsername),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                OutlinePill(stringResource(R.string.action_cancel), viewModel::cancel)
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

/** Dimmed edges, four white corner brackets and a sweeping line over the camera preview. */
@Composable
private fun ViewfinderOverlay(modifier: Modifier = Modifier) {
    val sweep = rememberInfiniteTransition(label = "scan")
    val position by sweep.animateFloat(
        initialValue = 0.12f,
        targetValue = 0.84f,
        animationSpec = infiniteRepeatable(tween(1_300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "scanLine",
    )
    Canvas(modifier = modifier) {
        val inset = size.width * 0.18f
        val window = Rect(inset, inset, size.width - inset, size.height - inset)
        val radius = 12.dp.toPx()
        // Dim everything outside the window.
        val outside = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(Offset.Zero, size))
            addRoundRect(RoundRect(window, CornerRadius(radius)))
        }
        drawPath(outside, Color.Black.copy(alpha = 0.35f))
        // Corner brackets.
        val arm = 36.dp.toPx()
        val stroke = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
        fun bracket(x: Float, y: Float, dx: Float, dy: Float) {
            val p = Path().apply {
                moveTo(x, y + dy * arm)
                lineTo(x, y + dy * radius)
                quadraticTo(x, y, x + dx * radius, y)
                lineTo(x + dx * arm, y)
            }
            drawPath(p, Color.White, style = stroke)
        }
        bracket(window.left, window.top, 1f, 1f)
        bracket(window.right, window.top, -1f, 1f)
        bracket(window.left, window.bottom, 1f, -1f)
        bracket(window.right, window.bottom, -1f, -1f)
        // Sweeping line.
        val y = size.height * position
        drawLine(
            Color.White.copy(alpha = 0.8f),
            Offset(size.width * 0.2f, y),
            Offset(size.width * 0.8f, y),
            strokeWidth = 2.dp.toPx(),
        )
    }
}
