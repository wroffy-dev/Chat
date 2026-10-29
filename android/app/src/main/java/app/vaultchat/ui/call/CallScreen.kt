package app.vaultchat.ui.call

import android.app.Activity
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.activity.compose.BackHandler
import app.vaultchat.platform.call.CallForegroundService
import app.vaultchat.platform.push.Push
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vaultchat.R
import app.vaultchat.domain.call.StatsSample
import app.vaultchat.BuildConfig
import app.vaultchat.domain.call.CallEndReason
import app.vaultchat.domain.call.CallKind
import app.vaultchat.domain.call.CallPhase
import app.vaultchat.domain.call.CaptureQuality
import app.vaultchat.domain.call.VideoQualityChoice
import app.vaultchat.domain.permissions.AppPermission
import app.vaultchat.domain.permissions.PermissionStatus
import app.vaultchat.ui.components.AppIcons
import app.vaultchat.ui.components.PrimaryButton
import app.vaultchat.ui.permissions.PermissionPreferences
import app.vaultchat.ui.permissions.openAppSettings
import app.vaultchat.ui.permissions.rememberPermissionRequest

/**
 * The call screen: outgoing ringing, incoming ringing, connecting, active and ended states.
 * [onStart] begins the call once permissions are granted (outgoing) or immediately (incoming).
 */
@Composable
fun CallScreen(
    viewModel: CallViewModel,
    permissionPreferences: PermissionPreferences,
    kind: CallKind,
    incoming: Boolean,
    onStart: () -> Unit,
    onClose: () -> Unit,
) {
    val state by viewModel.controller.state.collectAsStateWithLifecycle()
    val remoteVideo by viewModel.media.remoteVideo.collectAsStateWithLifecycle()
    val localVideo by viewModel.media.localVideo.collectAsStateWithLifecycle()
    val liveStats by viewModel.controller.liveStats.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val mic = rememberPermissionRequest(AppPermission.MICROPHONE, permissionPreferences)
    val camera = rememberPermissionRequest(AppPermission.CAMERA, permissionPreferences)
    val needsCamera = kind == CallKind.VIDEO
    val permissionsReady = mic.status == PermissionStatus.Granted && (!needsCamera || camera.status == PermissionStatus.Granted)

    LaunchedEffect(permissionsReady) { if (permissionsReady || incoming) onStart() }

    // Keep the screen on for the whole call.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    // While this screen is shown, the call may appear over the lock screen (incoming calls).
    DisposableEffect(context) {
        val activity = context as? Activity
        activity?.setShowWhenLocked(true)
        activity?.setTurnScreenOn(true)
        Push.cancelIncomingCall(context)
        onDispose {
            activity?.setShowWhenLocked(false)
            activity?.setTurnScreenOn(false)
        }
    }

    // Keep connected calls alive in the background: the foreground service is started here, while
    // the app is visible, as Android 14+ requires for camera and microphone services.
    val inCall = state.phase == CallPhase.CONNECTING || state.phase == CallPhase.ACTIVE || state.phase == CallPhase.RECONNECTING
    DisposableEffect(inCall) {
        if (inCall) CallForegroundService.start(context, video = kind == CallKind.VIDEO)
        onDispose { if (inCall) CallForegroundService.stop(context) }
    }

    // Back hangs up rather than leaving a call running behind the app.
    BackHandler(enabled = state.phase != CallPhase.ENDED) { viewModel.controller.hangup() }

    // In a connected video call the controls fade out after 4 s without a touch and come back on
    // any touch. Not with TalkBack on: its users must always be able to reach the buttons.
    val accessibility = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var qualityPicker by remember { mutableStateOf(false) }
    val autoHide = kind == CallKind.VIDEO && state.phase == CallPhase.ACTIVE && !qualityPicker &&
        accessibility?.isTouchExplorationEnabled != true
    var controlsVisible by remember { mutableStateOf(true) }
    var lastTouch by remember { mutableIntStateOf(0) }
    LaunchedEffect(autoHide, lastTouch) {
        controlsVisible = true
        if (autoHide) {
            delay(CONTROLS_HIDE_MS)
            controlsVisible = false
        }
    }

    if (qualityPicker) {
        QualityPicker(
            current = state.videoQuality,
            recommended = state.recommendedQuality,
            onPick = { viewModel.controller.setVideoQuality(it); qualityPicker = false },
            onDismiss = { qualityPicker = false },
        )
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0E1013), contentColor = Color.White) {
        Box(
            modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                // Sees every touch before the buttons do, without consuming it, so a button press
                // also restarts the timer.
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        lastTouch++
                    }
                }
            },
        ) {
            if (kind == CallKind.VIDEO && remoteVideo != null && state.phase in setOf(CallPhase.ACTIVE, CallPhase.RECONNECTING)) {
                VideoView(viewModel.media.room, remoteVideo, mirror = false, modifier = Modifier.fillMaxSize())
            }
            if (kind == CallKind.VIDEO && localVideo != null && state.cameraOn && state.phase != CallPhase.ENDED) {
                VideoView(
                    viewModel.media.room,
                    localVideo,
                    mirror = true,
                    modifier = Modifier
                        .safeDrawingPadding()
                        .padding(16.dp)
                        .size(width = 112.dp, height = 160.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .align(Alignment.TopEnd),
                )
            }

            // Debug builds only: live call statistics for the two-phone tests (docs/09).
            val live = liveStats
            if (BuildConfig.DEBUG && live != null && state.phase in setOf(CallPhase.ACTIVE, CallPhase.RECONNECTING)) {
                Text(
                    statsOverlayText(live),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .safeDrawingPadding()
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(6.dp)
                        .align(Alignment.TopStart),
                )
            }

            // With the controls hidden, a small reminder that the microphone is off.
            if (!controlsVisible && !state.micOn) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .safeDrawingPadding()
                        .padding(24.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Icon(AppIcons.MicOff, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.call_muted), style = MaterialTheme.typography.labelLarge)
                }
            }
            AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut()) {
            Column(
                modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(48.dp))
                Text(state.peerUsername.ifEmpty { "…" }, style = MaterialTheme.typography.headlineMedium)
                Text(
                    phaseText(state.phase, state.endReason, incoming),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )

                Spacer(Modifier.weight(1f))

                when {
                    !incoming && !permissionsReady && state.phase == CallPhase.STARTING -> PermissionPrompt(
                        micStatus = mic.status,
                        cameraStatus = if (needsCamera) camera.status else PermissionStatus.Granted,
                        onRequestMic = mic::request,
                        onRequestCamera = camera::request,
                        onOpenSettings = { openAppSettings(context) },
                        onCancel = onClose,
                    )
                    state.phase == CallPhase.RINGING_IN -> Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Button(
                            onClick = viewModel.controller::decline,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E)),
                        ) { Text(stringResource(R.string.call_decline)) }
                        Button(
                            onClick = {
                                if (permissionsReady) viewModel.controller.accept()
                                else if (mic.status != PermissionStatus.Granted) mic.request() else camera.request()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                        ) { Text(stringResource(if (permissionsReady) R.string.call_accept else R.string.call_allow_and_accept)) }
                    }
                    state.phase == CallPhase.ENDED -> PrimaryButton(stringResource(R.string.call_close), onClose)
                    else -> Controls(
                        kind = kind,
                        micOn = state.micOn,
                        cameraOn = state.cameraOn,
                        speakerOn = state.speakerOn,
                        onMic = viewModel.controller::toggleMicrophone,
                        onCamera = viewModel.controller::toggleCamera,
                        onSwitch = viewModel.controller::switchCamera,
                        quality = state.videoQuality,
                        recommended = state.recommendedQuality,
                        onQuality = { qualityPicker = true },
                        onSpeaker = viewModel.controller::toggleSpeaker,
                        onEnd = viewModel.controller::hangup,
                    )
                }
            }
            }
        }
    }
}

private const val CONTROLS_HIDE_MS = 4_000L

@Composable
private fun qualityLabel(choice: VideoQualityChoice): String = when (choice) {
    VideoQualityChoice.AUTO -> stringResource(R.string.call_quality_auto)
    else -> sizeLabel(choice.capture!!)
}

private fun sizeLabel(q: CaptureQuality): String = when (q) {
    CaptureQuality.P1080 -> "1080p"
    CaptureQuality.P720 -> "720p"
    CaptureQuality.P540 -> "540p"
    CaptureQuality.P360 -> "360p"
}

/**
 * Sent video size, changeable during the call. The recommendation comes from this call's measured
 * connection (QualityAdvisor); picking a size restarts the camera for a moment.
 */
@Composable
private fun QualityPicker(
    current: VideoQualityChoice,
    recommended: CaptureQuality?,
    onPick: (VideoQualityChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.call_quality_title)) },
        text = {
            Column {
                Text(
                    if (recommended != null) stringResource(R.string.call_quality_measured, sizeLabel(recommended))
                    else stringResource(R.string.call_quality_measuring),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                VideoQualityChoice.entries.forEach { choice ->
                    val isRecommended = choice.capture != null && choice.capture == recommended
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = choice == current, role = Role.RadioButton, onClick = { onPick(choice) })
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(selected = choice == current, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when (choice) {
                                VideoQualityChoice.AUTO -> stringResource(R.string.call_quality_auto_long)
                                VideoQualityChoice.P360 -> stringResource(R.string.call_quality_saver, sizeLabel(choice.capture!!))
                                else -> sizeLabel(choice.capture!!)
                            } + if (isRecommended) "  · " + stringResource(R.string.call_quality_recommended) else "",
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.call_quality_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun Controls(
    kind: CallKind,
    micOn: Boolean,
    cameraOn: Boolean,
    speakerOn: Boolean,
    onMic: () -> Unit,
    onCamera: () -> Unit,
    onSwitch: () -> Unit,
    quality: VideoQualityChoice,
    recommended: CaptureQuality?,
    onQuality: () -> Unit,
    onSpeaker: () -> Unit,
    onEnd: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Muted is shown loudly (white button, crossed-out mic) so it is never mistaken for live.
            FilledTonalButton(
                onClick = onMic,
                colors = if (micOn) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.filledTonalButtonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black,
                ),
            ) {
                Icon(if (micOn) AppIcons.Mic else AppIcons.MicOff, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(if (micOn) R.string.call_mute else R.string.call_unmute))
            }
            FilledTonalButton(onClick = onSpeaker) { Text(stringResource(if (speakerOn) R.string.call_earpiece else R.string.call_speaker)) }
        }
        if (kind == CallKind.VIDEO) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onCamera) { Text(stringResource(if (cameraOn) R.string.call_camera_off else R.string.call_camera_on)) }
                FilledTonalButton(onClick = onSwitch, enabled = cameraOn) { Text(stringResource(R.string.call_switch_camera)) }
            }
            // A dot on the button when the network suggests a different size than the one in use.
            val hint = recommended != null && quality.capture != null && quality.capture != recommended
            FilledTonalButton(onClick = onQuality) {
                Text(stringResource(R.string.call_quality_button, qualityLabel(quality)) + if (hint) "  •" else "")
            }
        }
        // End call is set apart and coloured so it is hard to hit by accident (docs/15 §4).
        Button(
            onClick = onEnd,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E)),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text(stringResource(R.string.call_end)) }
    }
}

@Composable
private fun PermissionPrompt(
    micStatus: PermissionStatus,
    cameraStatus: PermissionStatus,
    onRequestMic: () -> Unit,
    onRequestCamera: () -> Unit,
    onOpenSettings: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val blocked = micStatus == PermissionStatus.PermanentlyDenied || cameraStatus == PermissionStatus.PermanentlyDenied
        Text(
            stringResource(if (blocked) R.string.call_permission_blocked else R.string.call_permission_needed),
            textAlign = TextAlign.Center,
        )
        when {
            blocked -> PrimaryButton(stringResource(R.string.action_open_settings), onOpenSettings)
            micStatus != PermissionStatus.Granted -> PrimaryButton(stringResource(R.string.call_allow_microphone), onRequestMic)
            else -> PrimaryButton(stringResource(R.string.call_allow_camera), onRequestCamera)
        }
        FilledTonalButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
    }
}

@Composable
private fun phaseText(phase: CallPhase, reason: CallEndReason?, incoming: Boolean): String = stringResource(
    when (phase) {
        CallPhase.STARTING -> if (incoming) R.string.call_incoming else R.string.call_starting
        CallPhase.RINGING_OUT -> R.string.call_ringing
        CallPhase.RINGING_IN -> R.string.call_incoming
        CallPhase.CONNECTING -> R.string.call_connecting
        CallPhase.ACTIVE -> R.string.call_connected
        CallPhase.RECONNECTING -> R.string.call_reconnecting
        CallPhase.ENDED -> when (reason) {
            CallEndReason.DECLINED -> R.string.call_ended_declined
            CallEndReason.NO_ANSWER -> R.string.call_ended_no_answer
            CallEndReason.NETWORK_LOST -> R.string.call_ended_network
            CallEndReason.MEDIA_UNAVAILABLE -> R.string.call_ended_unavailable
            CallEndReason.BUSY -> R.string.call_ended_busy
            CallEndReason.FAILED -> R.string.call_ended_failed
            CallEndReason.CANCELLED -> R.string.call_ended_cancelled
            CallEndReason.SESSION_ENDED -> R.string.session_ended
            else -> R.string.call_ended
        }
    },
)

/** Compact live values for testers; numbers only. */
private fun statsOverlayText(s: StatsSample): String = buildString {
    fun n(v: Double?) = v?.let { "%.0f".format(it) } ?: "–"
    appendLine("RTT ${n(s.rttMs)} ms · jitter ${n(s.jitterMs)} ms")
    appendLine("lost ${s.packetsLost} / recv ${s.packetsReceived}")
    appendLine("send ${s.sendWidth ?: "–"}x${s.sendHeight ?: "–"} @${n(s.sendFps)} · ${s.qualityLimitation ?: "–"}")
    appendLine("recv ${s.recvWidth ?: "–"}x${s.recvHeight ?: "–"} @${n(s.recvFps)} · freezes ${s.freezeCount}")
    append("path ${s.candidateType ?: "–"}/${s.protocol ?: "–"}")
}
