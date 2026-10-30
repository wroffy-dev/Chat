package app.vaultchat.ui.call

import android.app.Activity
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.activity.compose.BackHandler
import app.vaultchat.platform.call.CallForegroundService
import app.vaultchat.platform.push.Push
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import app.vaultchat.ui.components.InitialsAvatar
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
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
    /** Starts a new call to the same person from the ended screen; null when that is not possible. */
    onCallAgain: (() -> Unit)? = null,
) {
    val state by viewModel.controller.state.collectAsStateWithLifecycle()
    val peerMuted = state.remotePresent && !state.remoteMicOn && state.phase != CallPhase.ENDED
    val remoteVideo by viewModel.media.remoteVideo.collectAsStateWithLifecycle()
    val localVideo by viewModel.media.localVideo.collectAsStateWithLifecycle()
    val localFront by viewModel.media.localFront.collectAsStateWithLifecycle()
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
    // The Switch menu is a separate popup window whose touches never reach this screen, so the
    // controls (and the menu with them) must not fade while it is open.
    var switchMenuOpen by remember { mutableStateOf(false) }
    val autoHide = kind == CallKind.VIDEO && state.phase == CallPhase.ACTIVE && !switchMenuOpen &&
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

    // Time since the call first connected, for "Connected · 3:12"; frozen when it ends.
    var connectedAt by remember { mutableStateOf<Long?>(null) }
    var clock by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.phase) {
        if (state.phase == CallPhase.ACTIVE && connectedAt == null) connectedAt = SystemClock.elapsedRealtime()
        clock = SystemClock.elapsedRealtime()
        while (state.phase == CallPhase.ACTIVE || state.phase == CallPhase.RECONNECTING) {
            delay(1_000)
            clock = SystemClock.elapsedRealtime()
        }
    }
    val elapsed = connectedAt?.let { formatDuration((clock - it) / 1000) }
    val showVideo = kind == CallKind.VIDEO && remoteVideo != null && state.phase in setOf(CallPhase.ACTIVE, CallPhase.RECONNECTING)

    // A brief, non-covert notice when either side flips the other's camera (owner request).
    val peerName = state.peerUsername.ifEmpty { "…" }
    val sentText = stringResource(R.string.call_switched_their_camera, peerName)
    val receivedText = stringResource(R.string.call_peer_switched_your_camera, peerName)
    var sentFlips by remember { mutableIntStateOf(0) }
    var flipNotice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sentFlips) { if (sentFlips > 0) { flipNotice = sentText; delay(2_500); flipNotice = null } }
    LaunchedEffect(state.cameraFlippedByPeer) { if (state.cameraFlippedByPeer > 0) { flipNotice = receivedText; delay(3_000); flipNotice = null } }

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
            if (showVideo) {
                VideoView(viewModel.media.room, remoteVideo, mirror = false, modifier = Modifier.fillMaxSize())
            }
            if (kind == CallKind.VIDEO && localVideo != null && state.cameraOn && state.phase != CallPhase.ENDED) {
                VideoView(
                    viewModel.media.room,
                    localVideo,
                    mirror = localFront,
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

            // A brief, non-covert notice when either side flips the other's camera.
            flipNotice?.let { text ->
                MutedPill(
                    text,
                    Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 72.dp),
                )
            }

            // The other person's mute stays visible with the controls hidden too.
            if (!controlsVisible && peerMuted) {
                MutedPill(
                    stringResource(R.string.call_peer_muted, state.peerUsername),
                    Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(24.dp),
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
                Spacer(Modifier.height(40.dp))
                Text(state.peerUsername.ifEmpty { "…" }, style = MaterialTheme.typography.headlineMedium)
                val phase = phaseText(state.phase, state.endReason, incoming)
                Text(
                    when {
                        elapsed == null -> phase
                        state.phase == CallPhase.ACTIVE || state.phase == CallPhase.ENDED -> "$phase · $elapsed"
                        else -> phase
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    // Announces phase changes only, not every tick of the timer.
                    modifier = Modifier.clearAndSetSemantics {
                        contentDescription = phase
                        liveRegion = LiveRegionMode.Polite
                    },
                )
                if (peerMuted) {
                    Spacer(Modifier.height(8.dp))
                    MutedPill(stringResource(R.string.call_peer_muted, state.peerUsername))
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (!showVideo) InitialsAvatar(state.peerUsername.ifEmpty { "?" }, size = 128.dp)
                }

                when {
                    !incoming && !permissionsReady && state.phase == CallPhase.STARTING -> PermissionPrompt(
                        micStatus = mic.status,
                        cameraStatus = if (needsCamera) camera.status else PermissionStatus.Granted,
                        onRequestMic = mic::request,
                        onRequestCamera = camera::request,
                        onOpenSettings = { openAppSettings(context) },
                        onCancel = onClose,
                    )
                    state.phase == CallPhase.RINGING_IN -> Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                    ) {
                        RoundAction(
                            label = stringResource(R.string.call_decline),
                            icon = AppIcons.CallEnd,
                            container = EndRed,
                            onClick = viewModel.controller::decline,
                        )
                        RoundAction(
                            label = stringResource(if (permissionsReady) R.string.call_accept else R.string.call_allow_and_accept),
                            icon = Icons.Filled.Call,
                            container = AcceptGreen,
                            onClick = {
                                if (permissionsReady) viewModel.controller.accept()
                                else if (mic.status != PermissionStatus.Granted) mic.request() else camera.request()
                            },
                        )
                    }
                    state.phase == CallPhase.ENDED -> Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (onCallAgain != null) {
                            Button(
                                onClick = onCallAgain,
                                colors = ButtonDefaults.buttonColors(containerColor = Tonal, contentColor = Color.White),
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                            ) { Text(stringResource(R.string.call_again), style = MaterialTheme.typography.labelLarge) }
                        }
                        Button(
                            onClick = onClose,
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF111111)),
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) { Text(stringResource(R.string.call_close), style = MaterialTheme.typography.labelLarge) }
                    }
                    else -> Controls(
                        kind = kind,
                        peerVideoPresent = showVideo,
                        onSwitchPeerCamera = { viewModel.controller.switchPeerCamera(); sentFlips++ },
                        onSwitchMenuOpen = { switchMenuOpen = it },
                        micOn = state.micOn,
                        cameraOn = state.cameraOn,
                        speakerOn = state.speakerOn,
                        onMic = viewModel.controller::toggleMicrophone,
                        onCamera = viewModel.controller::toggleCamera,
                        onSwitch = viewModel.controller::switchCamera,
                        onSpeaker = viewModel.controller::toggleSpeaker,
                        noiseFilter = if (viewModel.controller.noiseReductionAvailable) state.noiseReduction else null,
                        onNoiseFilter = viewModel.controller::toggleNoiseReduction,
                        endLabel = stringResource(
                            if (state.phase == CallPhase.STARTING || state.phase == CallPhase.RINGING_OUT) R.string.action_cancel else R.string.call_end,
                        ),
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
private fun MutedPill(text: String, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Icon(AppIcons.MicOff, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

private val EndRed = Color(0xFFB3261E)
private val AcceptGreen = Color(0xFF2E7D32)
private val Tonal = Color.White.copy(alpha = 0.14f)

/** "3:12", or "1:02:03" past an hour. */
private fun formatDuration(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** A round button with its label underneath. [on] fills it white, so an active toggle stands out. */
@Composable
private fun RoundToggle(
    label: String,
    icon: ImageVector,
    on: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    state: String? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .width(64.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { if (state != null) stateDescription = state },
    ) {
        Box(
            modifier = Modifier.size(64.dp).background(if (on) Color.White else Tonal, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (on) Color.Black else Color.White, modifier = Modifier.size(26.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center)
    }
}

/**
 * The "Switch" camera button. Tapping it opens a small menu with two choices: flip this phone's own
 * camera, or flip the other person's camera (owner request 2026-10-29). Only shown in video calls.
 */
@Composable
private fun SwitchCameraControl(
    myCameraOn: Boolean,
    peerVideoPresent: Boolean,
    onSwitchMine: () -> Unit,
    onSwitchTheirs: () -> Unit,
    onOpenChange: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) { onOpenChange(expanded) }
    DisposableEffect(Unit) { onDispose { onOpenChange(false) } }
    Box {
        RoundToggle(
            label = stringResource(R.string.call_switch),
            icon = AppIcons.FlipCamera,
            on = false,
            onClick = { expanded = true },
            // At least one of the two choices is usable.
            enabled = myCameraOn || peerVideoPresent,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.call_switch_my_camera)) },
                enabled = myCameraOn,
                onClick = { expanded = false; onSwitchMine() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.call_switch_their_camera)) },
                enabled = peerVideoPresent,
                onClick = { expanded = false; onSwitchTheirs() },
            )
        }
    }
}

/** Incoming call's Decline and Accept: large coloured circles with a label. */
@Composable
private fun RoundAction(label: String, icon: ImageVector, container: Color, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick).semantics(mergeDescendants = true) {},
    ) {
        Box(modifier = Modifier.size(72.dp).background(container, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Color.White)
    }
}

@Composable
private fun Controls(
    kind: CallKind,
    peerVideoPresent: Boolean,
    onSwitchPeerCamera: () -> Unit,
    onSwitchMenuOpen: (Boolean) -> Unit,
    micOn: Boolean,
    cameraOn: Boolean,
    speakerOn: Boolean,
    onMic: () -> Unit,
    onCamera: () -> Unit,
    onSwitch: () -> Unit,
    onSpeaker: () -> Unit,
    /** Null when the phone cannot run the noise filter. */
    noiseFilter: Boolean?,
    onNoiseFilter: () -> Unit,
    endLabel: String,
    onEnd: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        // Four buttons in a video call spread evenly, so they still fit a narrow (360 dp) phone.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (kind == CallKind.VIDEO) Arrangement.SpaceEvenly else Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
        ) {
            // Muted is shown loudly (white button, crossed-out mic) so it is never mistaken for live.
            RoundToggle(
                label = stringResource(if (micOn) R.string.call_mute else R.string.call_unmute),
                icon = if (micOn) AppIcons.Mic else AppIcons.MicOff,
                on = !micOn,
                onClick = onMic,
            )
            if (kind == CallKind.VIDEO) {
                RoundToggle(
                    label = stringResource(if (cameraOn) R.string.call_camera_off else R.string.call_camera_on),
                    icon = if (cameraOn) AppIcons.Videocam else AppIcons.VideocamOff,
                    on = !cameraOn,
                    onClick = onCamera,
                )
                SwitchCameraControl(
                    myCameraOn = cameraOn,
                    peerVideoPresent = peerVideoPresent,
                    onSwitchMine = onSwitch,
                    onSwitchTheirs = onSwitchPeerCamera,
                    onOpenChange = onSwitchMenuOpen,
                )
            }
            RoundToggle(
                label = stringResource(R.string.call_speaker),
                icon = AppIcons.VolumeUp,
                on = speakerOn,
                onClick = onSpeaker,
                state = stringResource(if (speakerOn) R.string.state_on else R.string.state_off),
            )
        }
        if (noiseFilter != null) {
            FilledTonalButton(onClick = onNoiseFilter) {
                Text(stringResource(if (noiseFilter) R.string.call_noise_filter_on else R.string.call_noise_filter_off))
            }
        }
        // End call is set apart and coloured so it is hard to hit by accident (docs/15 §4).
        Button(
            onClick = onEnd,
            colors = ButtonDefaults.buttonColors(containerColor = EndRed, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Icon(AppIcons.CallEnd, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Text(endLabel, style = MaterialTheme.typography.labelLarge)
        }
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
