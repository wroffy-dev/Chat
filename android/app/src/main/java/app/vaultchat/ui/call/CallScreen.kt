package app.vaultchat.ui.call

import app.vaultchat.ui.theme.DarkPanel
import app.vaultchat.ui.theme.LocalPanel
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ripple
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import app.vaultchat.ui.components.pressScale
import app.vaultchat.ui.theme.panel
import app.vaultchat.ui.theme.screenBackground
import app.vaultchat.ui.theme.panelTrack
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import app.vaultchat.ui.components.SignalBars
import app.vaultchat.ui.theme.Sizes
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.graphicsLayer
import app.vaultchat.domain.call.CallController
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
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
import androidx.compose.material.icons.filled.Check
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
import app.vaultchat.ui.theme.VaultGreen
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
import androidx.compose.material3.AlertDialog
import app.vaultchat.platform.call.AudioOutputs
import app.vaultchat.platform.call.AudioOutput
import app.vaultchat.domain.connection.ConnResult
import app.vaultchat.VaultChatApplication
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
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.saveable.rememberSaveable

/**
 * The call screen: outgoing ringing, incoming ringing, connecting, active and ended states.
 * [onStart] begins the call once permissions are granted (outgoing) or immediately (incoming).
 */
@Composable
fun CallScreen(
    viewModel: CallSession,
    permissionPreferences: PermissionPreferences,
    kind: CallKind,
    incoming: Boolean,
    onStart: () -> Unit,
    onClose: () -> Unit,
    /** Starts a new call to the same person from the ended screen; null when that is not possible. */
    onCallAgain: (() -> Unit)? = null,
    /** Leaves the call screen while the call goes on in a small floating picture; null hides the button. */
    onMinimize: (() -> Unit)? = null,
) {
    val state by viewModel.controller.state.collectAsStateWithLifecycle()
    val peerMuted = state.remotePresent && !state.remoteMicOn && state.phase != CallPhase.ENDED
    val remoteVideo by viewModel.media.remoteVideo.collectAsStateWithLifecycle()
    val localVideo by viewModel.media.localVideo.collectAsStateWithLifecycle()
    val localFront by viewModel.media.localFront.collectAsStateWithLifecycle()
    val liveStats by viewModel.controller.liveStats.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Light in a video call (owner, 2026-11-21): on the back camera the phone's torch; on the front camera a
    // warm white glow around the screen at full brightness, which lights the face.
    var light by rememberSaveable { mutableStateOf(false) }
    // Night mode of the sent video (owner, 2026-11-21): dark parts of the picture are brightened.
    var nightVideo by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(nightVideo) { if (kind == CallKind.VIDEO) viewModel.controller.setNightVideo(nightVideo) }
    var lightNotice by remember { mutableStateOf(false) }
    LaunchedEffect(light, localFront, state.cameraOn, state.phase) {
        if (kind != CallKind.VIDEO) return@LaunchedEffect
        val wantTorch = light && !localFront && state.cameraOn && state.phase != CallPhase.ENDED
        val ok = viewModel.controller.setTorch(wantTorch)
        if (wantTorch && !ok) {
            light = false
            lightNotice = true
        }
    }
    val screenLight = kind == CallKind.VIDEO && light && localFront && state.cameraOn && state.phase != CallPhase.ENDED
    val callWindow = remember(context) { context.findActivity()?.window }
    DisposableEffect(screenLight, callWindow) {
        val w = callWindow
        if (screenLight && w != null) {
            val before = w.attributes.screenBrightness
            w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
            onDispose { w.attributes = w.attributes.apply { screenBrightness = before } }
        } else {
            onDispose { }
        }
    }

    val mic = rememberPermissionRequest(AppPermission.MICROPHONE, permissionPreferences)
    val camera = rememberPermissionRequest(AppPermission.CAMERA, permissionPreferences)
    // Bluetooth earbuds (owner 2026-11-26): Android 12+ shows them to the call only with this permission. Asked
    // once, after the microphone, so the two dialogs do not collide.
    val bluetooth = rememberPermissionRequest(AppPermission.BLUETOOTH_CONNECT, permissionPreferences) { status ->
        if (status == PermissionStatus.Granted) viewModel.media.bluetoothPermissionGranted()
    }
    LaunchedEffect(mic.status) {
        if (mic.status == PermissionStatus.Granted && bluetooth.status != PermissionStatus.Granted &&
            !permissionPreferences.wasRequested(AppPermission.BLUETOOTH_CONNECT)
        ) {
            bluetooth.request()
        }
    }
    val outputs by viewModel.media.audioOutputs.collectAsStateWithLifecycle()
    var pickingOutput by remember { mutableStateOf(false) }
    // With earbuds or a headset connected the speaker button chooses where the call is heard; else it toggles the
    // loudspeaker as before.
    val routes = outputs.available.any { it == AudioOutput.BLUETOOTH || it == AudioOutput.WIRED }
    val outputButton = if (routes) outputButtonFor(outputs) else null
    val onSpeakerTap: () -> Unit = { if (routes) pickingOutput = true else viewModel.controller.toggleSpeaker() }
    if (pickingOutput && routes) {
        AudioOutputDialog(
            outputs = outputs,
            onPick = { output ->
                pickingOutput = false
                if ((output == AudioOutput.SPEAKER) != state.speakerOn) viewModel.controller.toggleSpeaker()
                viewModel.media.selectAudioOutput(output)
            },
            onDismiss = { pickingOutput = false },
        )
    }
    val needsCamera = kind == CallKind.VIDEO
    val permissionsReady = mic.status == PermissionStatus.Granted && (!needsCamera || camera.status == PermissionStatus.Granted)

    LaunchedEffect(permissionsReady) { if (permissionsReady || incoming) onStart() }

    // An incoming call on a phone that has not allowed the microphone or camera yet: ask as soon as it
    // rings (once each), not only when Accept is tapped, so the 10 s auto-answer has them.
    var askedMic by remember { mutableStateOf(false) }
    var askedCamera by remember { mutableStateOf(false) }
    LaunchedEffect(incoming, mic.status, camera.status) {
        if (!incoming) return@LaunchedEffect
        if (mic.status != PermissionStatus.Granted && !askedMic) {
            askedMic = true
            mic.request()
        } else if (needsCamera && camera.status != PermissionStatus.Granted && !askedCamera && (mic.status == PermissionStatus.Granted || askedMic)) {
            askedCamera = true
            camera.request()
        }
    }

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

    // The push for this call can arrive after this screen opened; its notification goes once the call is
    // answered, declined or over.
    LaunchedEffect(state.phase) { if (state.phase != CallPhase.RINGING_IN) Push.cancelIncomingCall(context) }

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
    var zoomMenuOpen by remember { mutableStateOf(false) }
    val autoHide = kind == CallKind.VIDEO && state.phase == CallPhase.ACTIVE && !switchMenuOpen && !zoomMenuOpen &&
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
    // The same kind of notice for camera zoom, both ways (owner request 2026-11-03).
    var sentZooms by remember { mutableIntStateOf(0) }
    val sentZoomText = stringResource(R.string.call_zoomed_their_camera, peerName, zoomLabel(state.peerCameraZoom))
    val receivedZoomText = stringResource(R.string.call_peer_zoomed_your_camera, peerName, zoomLabel(state.cameraZoom))
    LaunchedEffect(sentZooms) { if (sentZooms > 0) { flipNotice = sentZoomText; delay(2_500); flipNotice = null } }
    LaunchedEffect(state.cameraZoomedByPeer) { if (state.cameraZoomedByPeer > 0) { flipNotice = receivedZoomText; delay(3_000); flipNotice = null } }

    var selfBig by remember { mutableStateOf(false) }
    var pipOffset by remember { mutableStateOf(Offset.Zero) }
    // How far the small picture may be dragged: the screen minus the picture and its margins.
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val maxPipX = with(density) { (config.screenWidthDp.dp - 112.dp - 32.dp).toPx() }.coerceAtLeast(0f)
    val maxPipY = with(density) { (config.screenHeightDp.dp - 160.dp - 96.dp).toPx() }.coerceAtLeast(0f)

    // A connected video call uses the owner's reference layout (2026-11-20, active_video_call.html): the
    // picture full screen under a dark gradient, a header with minimise, name and the live resolution, the
    // self view bottom-right and one bar of round controls. Voice calls, ringing and ended stay as before.
    val videoLayout = kind == CallKind.VIDEO && state.phase in setOf(CallPhase.CONNECTING, CallPhase.ACTIVE, CallPhase.RECONNECTING)
    // Calls stay dark, with dark panels (owner 2026-11-22: the rest of the app is light).
    CompositionLocalProvider(LocalPanel provides DarkPanel) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = if (videoLayout) RefSurface else VaultGreen.Deepest,
            contentColor = if (videoLayout) RefOnSurface else VaultGreen.Mint,
        ) {
            Box(
                // Voice calls, ringing and the end screen sit on a plain dark screen.
                modifier = Modifier.fillMaxSize().then(if (videoLayout) Modifier else Modifier.screenBackground()).pointerInput(Unit) {
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
                // WhatsApp-style: tap the small picture to swap which one fills the screen; drag it anywhere.
                val selfShown = kind == CallKind.VIDEO && localVideo != null && state.cameraOn && state.phase != CallPhase.ENDED
                val selfIsBig = selfBig && showVideo && selfShown
                val room = viewModel.media.room
                // Screen zoom (this phone only): pinch the big picture to enlarge it, drag to move, double-tap to
                // reset (owner request 2026-11-03). It starts again at 1x when the pictures are swapped.
                var viewZoom by remember(selfIsBig) { mutableFloatStateOf(1f) }
                var viewPan by remember(selfIsBig) { mutableStateOf(Offset.Zero) }
                val zoomedView = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = viewZoom
                    scaleY = viewZoom
                    translationX = viewPan.x
                    translationY = viewPan.y
                }
                if (selfIsBig) {
                    VideoView(room, localVideo, mirror = localFront, modifier = zoomedView)
                } else if (showVideo) {
                    VideoView(room, remoteVideo, mirror = false, modifier = zoomedView)
                }
                if (showVideo) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(selfIsBig) {
                                detectTransformGestures { _, pan, zoomChange, _ ->
                                    val z = (viewZoom * zoomChange).coerceIn(1f, MAX_SCREEN_ZOOM)
                                    // Keep the enlarged picture covering the screen: no empty edges.
                                    val maxX = size.width * (z - 1f) / 2f
                                    val maxY = size.height * (z - 1f) / 2f
                                    viewZoom = z
                                    viewPan = Offset((viewPan.x + pan.x).coerceIn(-maxX, maxX), (viewPan.y + pan.y).coerceIn(-maxY, maxY))
                                }
                            }
                            .pointerInput(selfIsBig) {
                                detectTapGestures(onDoubleTap = { viewZoom = 1f; viewPan = Offset.Zero })
                            },
                    )
                }
                val smallTrack = when {
                    selfIsBig -> remoteVideo
                    selfShown -> localVideo
                    else -> null
                }
                if (videoLayout) {
                    // Darker at the top and bottom so the header and controls read over any picture.
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                0f to RefSurface.copy(alpha = 0.7f),
                                0.5f to Color.Transparent,
                                1f to RefSurface.copy(alpha = 0.85f),
                            ),
                        ),
                    )
                    if (!showVideo) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CallAvatar(state.peerUsername.ifEmpty { "?" }, pulsing = state.phase != CallPhase.ACTIVE)
                        }
                    }
                    if (smallTrack != null) {
                        RefSelfView(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .safeDrawingPadding()
                                .padding(end = 16.dp, bottom = 112.dp)
                                .offset { IntOffset(pipOffset.x.roundToInt(), pipOffset.y.roundToInt()) },
                            content = { VideoView(room, smallTrack, mirror = !selfIsBig && localFront, modifier = Modifier.fillMaxSize()) },
                            onDrag = { drag ->
                                pipOffset = Offset(
                                    (pipOffset.x + drag.x).coerceIn(-maxPipX, 0f),
                                    (pipOffset.y + drag.y).coerceIn(-maxPipY, 0f),
                                )
                            },
                            canSwap = showVideo && selfShown,
                            onSwap = { selfBig = !selfBig },
                            onSwitchCamera = viewModel.controller::switchCamera,
                            switchEnabled = state.cameraOn,
                        )
                    }
                } else if (smallTrack != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .safeDrawingPadding()
                            .padding(16.dp)
                            .offset { IntOffset(pipOffset.x.roundToInt(), pipOffset.y.roundToInt()) }
                            .size(width = 112.dp, height = 160.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    ) {
                        VideoView(room, smallTrack, mirror = !selfIsBig && localFront, modifier = Modifier.fillMaxSize())
                        // A transparent layer on top takes the taps and drags (the video view itself does not).
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectDragGestures { change, drag ->
                                        change.consume()
                                        pipOffset = Offset(
                                            (pipOffset.x + drag.x).coerceIn(-maxPipX, 0f),
                                            (pipOffset.y + drag.y).coerceIn(0f, maxPipY),
                                        )
                                    }
                                }
                                .clickable(
                                    enabled = showVideo && selfShown,
                                    onClickLabel = stringResource(R.string.call_swap_video),
                                ) { selfBig = !selfBig },
                        )
                    }
                }

                // Front camera light: a warm white frame around the picture (the buttons stay on top of it).
                if (screenLight) {
                    Box(Modifier.fillMaxSize().border(36.dp, CallGlow))
                }
                if (lightNotice) {
                    Text(
                        stringResource(R.string.call_light_unavailable),
                        style = MaterialTheme.typography.labelLarge,
                        color = VaultGreen.Mint,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .safeDrawingPadding()
                            .padding(top = 96.dp, start = 24.dp, end = 24.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    LaunchedEffect(Unit) {
                        delay(3_000)
                        lightNotice = false
                    }
                }

                // Debug builds only: live call statistics for the two-phone tests (docs/09). They fade with
                // the controls after 4 s and come back on a touch.
                val live = liveStats
                if (BuildConfig.DEBUG && live != null && controlsVisible && state.phase in setOf(CallPhase.ACTIVE, CallPhase.RECONNECTING)) {
                    Text(
                        statsOverlayText(live),
                        style = MaterialTheme.typography.labelSmall,
                        color = VaultGreen.Mint,
                        // Lower left, above the buttons, so it does not cover the name and status at the top.
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .safeDrawingPadding()
                            .padding(start = 8.dp, bottom = 260.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(6.dp),
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
                // Minimise (owner request 2026-11-13): back to the chat with the call still running.
                if (!videoLayout && onMinimize != null && controlsVisible && state.phase in setOf(CallPhase.ACTIVE, CallPhase.RECONNECTING, CallPhase.CONNECTING)) {
                    FilledTonalButton(
                        onClick = onMinimize,
                        modifier = Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(12.dp),
                    ) { Text(stringResource(R.string.call_minimize)) }
                }
                if (videoLayout) {
                    AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut()) {
                        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                            val phase = phaseText(state.phase, state.endReason, incoming)
                            val quality = qualityLevel(liveStats)
                            RefHeader(
                                name = state.peerUsername.ifEmpty { "…" },
                                status = when {
                                    state.phase != CallPhase.ACTIVE -> phase
                                    elapsed != null && quality != null -> "$elapsed · ${stringResource(qualityLabel(quality))}"
                                    elapsed != null -> elapsed
                                    else -> phase
                                },
                                phase = phase,
                                onMinimize = onMinimize,
                                resolution = resolutionText(liveStats),
                                quality = quality,
                            )
                            if (peerMuted) {
                                MutedPill(stringResource(R.string.call_peer_muted, state.peerUsername), Modifier.align(Alignment.CenterHorizontally))
                            }
                            Spacer(Modifier.weight(1f))
                            RefControls(
                                micOn = state.micOn,
                                cameraOn = state.cameraOn,
                                speakerOn = state.speakerOn,
                                onMic = viewModel.controller::toggleMicrophone,
                                onCamera = viewModel.controller::toggleCamera,
                                onSpeaker = onSpeakerTap,
                                output = outputButton,
                                light = light,
                                onLight = { light = !light },
                                night = nightVideo,
                                onNight = { nightVideo = !nightVideo },
                                onEnd = viewModel.controller::hangup,
                                menu = { dismiss ->
                                    // Everything the old controls had stays one tap away under the switch button.
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.call_switch_my_camera)) },
                                        enabled = state.cameraOn,
                                        onClick = { dismiss(); viewModel.controller.switchCamera() },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.call_switch_their_camera)) },
                                        enabled = showVideo,
                                        onClick = { dismiss(); viewModel.controller.switchPeerCamera(); sentFlips++ },
                                    )
                                    HorizontalDivider()
                                    ZoomRow(stringResource(R.string.call_zoom_my_camera), state.cameraZoom, enabled = state.cameraOn) {
                                        dismiss(); viewModel.controller.setMyCameraZoom(it)
                                    }
                                    ZoomRow(stringResource(R.string.call_zoom_their_camera), state.peerCameraZoom, enabled = showVideo) {
                                        dismiss(); viewModel.controller.setPeerCameraZoom(it); sentZooms++
                                    }
                                    if (viewModel.controller.noiseReductionAvailable) {
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = { Text(stringResource(if (state.noiseReduction) R.string.call_noise_filter_on else R.string.call_noise_filter_off)) },
                                            onClick = { dismiss(); viewModel.controller.toggleNoiseReduction() },
                                        )
                                    }
                                },
                                onMenuOpen = { switchMenuOpen = it },
                            )
                        }
                    }
                } else AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut()) {
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
                        color = VaultGreen.Mint.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                        // Announces phase changes only, not every tick of the timer.
                        modifier = Modifier.clearAndSetSemantics {
                            contentDescription = phase
                            liveRegion = LiveRegionMode.Polite
                        },
                    )
                    // Connection quality from the call's real statistics (round-trip time and packet loss);
                    // nothing is shown until the first sample, and nothing is estimated.
                    val quality = if (state.phase == CallPhase.ACTIVE || state.phase == CallPhase.RECONNECTING) qualityLevel(liveStats) else null
                    if (quality != null) {
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SignalBars(quality, label = stringResource(qualityLabel(quality)), color = VaultGreen.Mint)
                            Text(stringResource(qualityLabel(quality)), style = MaterialTheme.typography.labelMedium, color = VaultGreen.Mint.copy(alpha = 0.75f))
                        }
                    }
                    if (peerMuted) {
                        Spacer(Modifier.height(8.dp))
                        MutedPill(stringResource(R.string.call_peer_muted, state.peerUsername))
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (!showVideo) {
                            // A soft ring breathes around the avatar while the call is ringing or connecting.
                            val waiting = state.phase in setOf(CallPhase.STARTING, CallPhase.RINGING_OUT, CallPhase.RINGING_IN, CallPhase.CONNECTING, CallPhase.RECONNECTING)
                            CallAvatar(state.peerUsername.ifEmpty { "?" }, pulsing = waiting)
                        }
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
                                breathing = true,
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
                                    colors = ButtonDefaults.buttonColors(containerColor = Tonal, contentColor = VaultGreen.Mint),
                                    modifier = Modifier.fillMaxWidth().height(52.dp),
                                ) { Text(stringResource(R.string.call_again), style = MaterialTheme.typography.labelLarge) }
                            }
                            Button(
                                onClick = onClose,
                                colors = ButtonDefaults.buttonColors(containerColor = VaultGreen.Mint, contentColor = VaultGreen.Deepest),
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                            ) { Text(stringResource(R.string.call_close), style = MaterialTheme.typography.labelLarge) }
                        }
                        else -> Controls(
                            kind = kind,
                            peerVideoPresent = showVideo,
                            onSwitchPeerCamera = { viewModel.controller.switchPeerCamera(); sentFlips++ },
                            onSwitchMenuOpen = { switchMenuOpen = it },
                            myZoom = state.cameraZoom,
                            peerZoom = state.peerCameraZoom,
                            onMyZoom = viewModel.controller::setMyCameraZoom,
                            onPeerZoom = { viewModel.controller.setPeerCameraZoom(it); sentZooms++ },
                            onZoomMenuOpen = { zoomMenuOpen = it },
                            micOn = state.micOn,
                            cameraOn = state.cameraOn,
                            speakerOn = state.speakerOn,
                            onMic = viewModel.controller::toggleMicrophone,
                            onCamera = viewModel.controller::toggleCamera,
                            onSwitch = viewModel.controller::switchCamera,
                            onSpeaker = onSpeakerTap,
                                output = outputButton,
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
}

private const val CONTROLS_HIDE_MS = 4_000L

/** The front camera's light in a video call: warm white. */
private val CallGlow = Color(0xFFFFF6E8)

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

// The reference video-call layout (active_video_call.html, 2026-11-20) in the app's dark greens (owner,
// 2026-11-20: "core app ke green UI mein"); red stays for ending the call.
private val RefSurface = VaultGreen.Deepest
private val RefOnSurface = VaultGreen.Mint
private val RefOnSurfaceVariant = VaultGreen.Sage
private val RefContainer = VaultGreen.Deep
private val RefContainerHigh = VaultGreen.Forest
private val RefOutlineVariant = VaultGreen.Pine
private val RefError = Color(0xFFC81E1E)
private val RefSuccess = VaultGreen.Sage
private val RefWarning = Color(0xFFE0A33A)

/** "720p · 30 fps" of the video being received, from the call statistics; null until known. */
private fun resolutionText(s: StatsSample?): String? {
    val h = s?.recvHeight ?: return null
    val fps = s.recvFps
    return if (fps != null) "${h}p · ${fps.roundToInt()} fps" else "${h}p"
}

/** Header: minimise, the other person's name with the time and connection, and the live resolution. */
@Composable
private fun RefHeader(
    name: String,
    status: String,
    phase: String,
    onMinimize: (() -> Unit)?,
    resolution: String?,
    quality: Int?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f, fill = false)) {
            if (onMinimize != null) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(RefContainer.copy(alpha = 0.9f))
                        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.call_minimize), onClick = onMinimize),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.call_minimize), tint = RefOnSurface, modifier = Modifier.size(24.dp))
                }
            }
            Column {
                Text(name, color = RefOnSurface, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    status,
                    color = RefOnSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    letterSpacing = 0.3.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    // Announces phase changes only, not every tick of the timer.
                    modifier = Modifier.clearAndSetSemantics {
                        contentDescription = phase
                        liveRegion = LiveRegionMode.Polite
                    },
                )
            }
        }
        if (resolution != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(RefContainer.copy(alpha = 0.9f))
                    .border(1.dp, RefOutlineVariant, RoundedCornerShape(50))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                val dot = when (quality) {
                    1 -> RefError
                    2 -> RefWarning
                    else -> RefSuccess
                }
                Box(Modifier.size(8.dp).background(dot, CircleShape))
                Text(resolution, color = RefOnSurface, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

/**
 * The self view, 112 x 160 dp with rounded corners and a thin border, bottom-right; a small round button
 * in its corner flips this phone's camera. Tap the picture to swap the two views; drag to move it.
 */
@Composable
private fun RefSelfView(
    modifier: Modifier,
    content: @Composable () -> Unit,
    onDrag: (Offset) -> Unit,
    canSwap: Boolean,
    onSwap: () -> Unit,
    onSwitchCamera: () -> Unit,
    switchEnabled: Boolean,
) {
    Box(
        modifier = modifier
            .size(width = 112.dp, height = 160.dp)
            .shadow(8.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(RefContainer)
            .border(1.dp, RefOutlineVariant, RoundedCornerShape(16.dp)),
    ) {
        content()
        // A transparent layer on top takes the taps and drags (the video view itself does not).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        onDrag(drag)
                    }
                }
                .clickable(enabled = canSwap, onClickLabel = stringResource(R.string.call_swap_video), onClick = onSwap),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
                .size(32.dp)
                .clip(CircleShape)
                .background(RefSurface.copy(alpha = 0.8f))
                .clickable(enabled = switchEnabled, role = Role.Button, onClick = onSwitchCamera),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.FlipCamera, contentDescription = stringResource(R.string.call_switch_my_camera), tint = RefOnSurface, modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * One bar of round controls in a rounded panel: microphone, camera, switch (with the camera and zoom menu),
 * speaker, and the red end button. A button that is not in its usual state is filled light so it stands out.
 */
@Composable
private fun RefControls(
    micOn: Boolean,
    cameraOn: Boolean,
    speakerOn: Boolean,
    /** When earbuds or a headset are connected: where the call is heard, shown on the speaker button. */
    output: OutputButton? = null,
    onMic: () -> Unit,
    onCamera: () -> Unit,
    onSpeaker: () -> Unit,
    light: Boolean,
    onLight: () -> Unit,
    night: Boolean,
    onNight: () -> Unit,
    onEnd: () -> Unit,
    menu: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
    onMenuOpen: (Boolean) -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .panelTrack(RoundedCornerShape(30.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            RefRound(
                icon = if (micOn) AppIcons.MicOff else AppIcons.Mic,
                label = stringResource(if (micOn) R.string.call_mute else R.string.call_unmute),
                highlighted = !micOn,
                onClick = onMic,
            )
            RefRound(
                icon = if (cameraOn) AppIcons.Videocam else AppIcons.VideocamOff,
                label = stringResource(if (cameraOn) R.string.call_camera_off else R.string.call_camera_on),
                highlighted = !cameraOn,
                onClick = onCamera,
            )
            var expanded by remember { mutableStateOf(false) }
            LaunchedEffect(expanded) { onMenuOpen(expanded) }
            DisposableEffect(Unit) { onDispose { onMenuOpen(false) } }
            Box {
                RefRound(icon = AppIcons.FlipCamera, label = stringResource(R.string.call_switch), highlighted = false, onClick = { expanded = true })
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) { menu(this) { expanded = false } }
            }
            // Light and Night mode share one button (owner, 2026-11-21): a small menu with both.
            var lightMenu by remember { mutableStateOf(false) }
            LaunchedEffect(lightMenu) { onMenuOpen(lightMenu) }
            Box {
                RefRound(
                    icon = AppIcons.Flashlight,
                    label = stringResource(R.string.call_light),
                    highlighted = light || night,
                    onClick = { lightMenu = true },
                    state = stringResource(if (light || night) R.string.state_on else R.string.state_off),
                )
                DropdownMenu(expanded = lightMenu, onDismissRequest = { lightMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.call_light)) },
                        leadingIcon = { Icon(AppIcons.Flashlight, contentDescription = null) },
                        trailingIcon = { if (light) Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.state_on)) },
                        onClick = { lightMenu = false; onLight() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.call_night)) },
                        leadingIcon = { Icon(AppIcons.NightMode, contentDescription = null) },
                        trailingIcon = { if (night) Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.state_on)) },
                        onClick = { lightMenu = false; onNight() },
                    )
                }
            }
            RefRound(
                icon = output?.icon ?: AppIcons.VolumeUp,
                label = output?.label ?: stringResource(R.string.call_speaker),
                highlighted = if (output != null) false else !speakerOn,
                onClick = onSpeaker,
                state = output?.label ?: stringResource(if (speakerOn) R.string.state_on else R.string.state_off),
            )
            val endPress = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .size(width = 56.dp, height = 48.dp)
                    .pressScale(endPress, 0.88f)
                    .clip(RoundedCornerShape(50))
                    .background(RefError)
                    .clickable(interactionSource = endPress, indication = ripple(), role = Role.Button, onClick = onEnd),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.CallEnd, contentDescription = stringResource(R.string.call_end), tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun RefRound(icon: ImageVector, label: String, highlighted: Boolean, onClick: () -> Unit, state: String? = null) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(48.dp)
            .pressScale(interaction, 0.86f)
            .then(if (highlighted) Modifier.clip(CircleShape).background(RefOnSurface) else Modifier.panel(CircleShape))
            .clickable(interactionSource = interaction, indication = ripple(), role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { if (state != null) stateDescription = state },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (highlighted) RefSurface else RefOnSurface, modifier = Modifier.size(24.dp))
    }
}

/** The large avatar of a voice call (or a video call without video), with a gentle pulse while waiting. */
@Composable
private fun CallAvatar(name: String, pulsing: Boolean) {
    // While waiting: two soft rings keep spreading out from the avatar, one after the other (owner 2026-11-22).
    val transition = rememberInfiniteTransition(label = "callPulse")
    val wave by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_400, easing = LinearEasing)),
        label = "ring",
    )
    val ring = VaultGreen.Sage
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.drawBehind {
            if (!pulsing) return@drawBehind
            val r = Sizes.avatarCall.toPx() / 2f
            for (phase in floatArrayOf(0f, 0.5f)) {
                val t = (wave + phase) % 1f
                drawCircle(ring.copy(alpha = 0.45f * (1f - t)), radius = r * (1f + 0.55f * t), style = Stroke(2.dp.toPx()))
            }
        },
    ) {
        InitialsAvatar(name, size = Sizes.avatarCall, avatar = rememberPeerAvatar(name))
    }
}

/**
 * The built-in avatar (D-32) of the person on the call, found among the user's connections by the name the call shows
 * (their nickname for an outgoing call, their username for an incoming one); null until known or when none.
 */
@Composable
private fun rememberPeerAvatar(name: String): String? {
    val container = (LocalContext.current.applicationContext as VaultChatApplication).container
    var avatar by remember(name) { mutableStateOf<String?>(null) }
    LaunchedEffect(name) {
        val people = (container.connectionRepository.connections() as? ConnResult.Ok)?.value.orEmpty()
        avatar = people.firstOrNull { it.peerUsername == name || it.username == name }?.peerAvatar
    }
    return avatar
}

/**
 * 1 (poor) to 3 (good) from the latest sample: round-trip time and the share of packets lost so far.
 * Null without a usable sample.
 */
private fun qualityLevel(s: StatsSample?): Int? {
    if (s == null) return null
    val rtt = s.rttMs
    val total = s.packetsLost + s.packetsReceived
    if (rtt == null && total == 0L) return null
    val loss = if (total > 0) s.packetsLost.toDouble() / total else 0.0
    val r = rtt ?: 0.0
    return when {
        r < 150 && loss < 0.02 -> 3
        r < 350 && loss < 0.06 -> 2
        else -> 1
    }
}

private fun qualityLabel(level: Int): Int = when (level) {
    3 -> R.string.call_quality_good
    2 -> R.string.call_quality_fair
    else -> R.string.call_quality_poor
}

/** Largest screen (view) zoom; the camera zoom has its own levels in [CallController]. */
private const val MAX_SCREEN_ZOOM = 5f

/** "1x", "1.5x", "2x". */
private fun zoomLabel(zoom: Float): String =
    if (zoom % 1f == 0f) "${zoom.toInt()}x" else "${"%.1f".format(java.util.Locale.ROOT, zoom)}x"

/**
 * Camera zoom (owner request 2026-11-03): one menu for this phone's camera and the other person's,
 * like Switch. It zooms the video that is sent, so both phones see it; the other person is told.
 */
@Composable
private fun ZoomControl(
    myCameraOn: Boolean,
    peerVideoPresent: Boolean,
    myZoom: Float,
    peerZoom: Float,
    onMyZoom: (Float) -> Unit,
    onPeerZoom: (Float) -> Unit,
    onOpenChange: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) { onOpenChange(expanded) }
    DisposableEffect(Unit) { onDispose { onOpenChange(false) } }
    Box {
        FilledTonalButton(onClick = { expanded = true }, enabled = myCameraOn || peerVideoPresent) {
            Text(stringResource(R.string.call_zoom_button, zoomLabel(myZoom), zoomLabel(peerZoom)))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ZoomRow(stringResource(R.string.call_zoom_my_camera), myZoom, enabled = myCameraOn) { expanded = false; onMyZoom(it) }
            HorizontalDivider()
            ZoomRow(stringResource(R.string.call_zoom_their_camera), peerZoom, enabled = peerVideoPresent) { expanded = false; onPeerZoom(it) }
        }
    }
}

@Composable
private fun ZoomRow(title: String, current: Float, enabled: Boolean, onPick: (Float) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CallController.CAMERA_ZOOM_LEVELS.forEach { level ->
                val selected = kotlin.math.abs(level - current) < 0.01f
                TextButton(
                    onClick = { onPick(level) },
                    enabled = enabled && !selected,
                    modifier = Modifier.semantics { if (selected) stateDescription = "selected" },
                ) {
                    Text(
                        zoomLabel(level),
                        style = if (selected) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

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
private val AcceptGreen = VaultGreen.Pine
private val Tonal = VaultGreen.Forest

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
    val interaction = remember { MutableInteractionSource() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .width(64.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .pressScale(interaction, 0.9f)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { if (state != null) stateDescription = state },
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .then(if (on) Modifier.background(VaultGreen.Mint, CircleShape) else Modifier.panel(CircleShape)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (on) VaultGreen.Deepest else VaultGreen.Mint, modifier = Modifier.size(26.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = VaultGreen.Mint.copy(alpha = 0.85f), textAlign = TextAlign.Center)
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
private fun RoundAction(label: String, icon: ImageVector, container: Color, onClick: () -> Unit, breathing: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    // Accept gently breathes while the phone rings, inviting the tap.
    val breath = rememberInfiniteTransition(label = "accept")
    val swell by breath.animateFloat(1f, if (breathing) 1.07f else 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "acceptSwell")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .pressScale(interaction, 0.9f)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {},
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = swell
                    scaleY = swell
                }
                .size(72.dp)
                .background(container, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = VaultGreen.Mint, modifier = Modifier.size(30.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, color = VaultGreen.Mint)
    }
}

@Composable
private fun Controls(
    kind: CallKind,
    peerVideoPresent: Boolean,
    onSwitchPeerCamera: () -> Unit,
    onSwitchMenuOpen: (Boolean) -> Unit,
    myZoom: Float,
    peerZoom: Float,
    onMyZoom: (Float) -> Unit,
    onPeerZoom: (Float) -> Unit,
    onZoomMenuOpen: (Boolean) -> Unit,
    micOn: Boolean,
    cameraOn: Boolean,
    speakerOn: Boolean,
    /** When earbuds or a headset are connected: where the call is heard, shown on the speaker button. */
    output: OutputButton? = null,
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
        if (kind == CallKind.VIDEO) {
            ZoomControl(
                myCameraOn = cameraOn,
                peerVideoPresent = peerVideoPresent,
                myZoom = myZoom,
                peerZoom = peerZoom,
                onMyZoom = onMyZoom,
                onPeerZoom = onPeerZoom,
                onOpenChange = onZoomMenuOpen,
            )
        }
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
                label = output?.label ?: stringResource(R.string.call_speaker),
                icon = output?.icon ?: AppIcons.VolumeUp,
                on = if (output != null) true else speakerOn,
                onClick = onSpeaker,
                state = output?.label ?: stringResource(if (speakerOn) R.string.state_on else R.string.state_off),
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
            colors = ButtonDefaults.buttonColors(containerColor = EndRed, contentColor = VaultGreen.Mint),
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
            CallEndReason.PEER_BUSY -> R.string.call_ended_peer_busy
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

/** What the speaker button shows while earbuds or a headset are connected. */
data class OutputButton(val label: String, val icon: ImageVector)

@Composable
private fun outputButtonFor(outputs: AudioOutputs): OutputButton = when (outputs.selected) {
    AudioOutput.BLUETOOTH -> OutputButton(outputs.bluetoothName ?: stringResource(R.string.call_output_bluetooth), AppIcons.Bluetooth)
    AudioOutput.WIRED -> OutputButton(stringResource(R.string.call_output_wired), AppIcons.Headset)
    AudioOutput.SPEAKER -> OutputButton(stringResource(R.string.call_output_speaker), AppIcons.VolumeUp)
    AudioOutput.EARPIECE, null -> OutputButton(stringResource(R.string.call_output_phone), AppIcons.PhoneInHand)
}

/** Choose where the call is heard: the Bluetooth earbuds, a wired headset, the phone's earpiece or the loudspeaker. */
@Composable
private fun AudioOutputDialog(outputs: AudioOutputs, onPick: (AudioOutput) -> Unit, onDismiss: () -> Unit) {
    val order = listOf(AudioOutput.BLUETOOTH, AudioOutput.WIRED, AudioOutput.EARPIECE, AudioOutput.SPEAKER)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.call_output_title)) },
        text = {
            Column {
                for (output in order.filter { it in outputs.available }) {
                    val choice = outputButtonFor(outputs.copy(selected = output))
                    val selected = outputs.selected == output
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPick(output) }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                    ) {
                        Icon(choice.icon, contentDescription = null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(16.dp))
                        Text(choice.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        if (selected) Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.state_on), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

