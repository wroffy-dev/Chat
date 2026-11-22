package app.vaultchat.ui.snap

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Spacer
import app.vaultchat.ui.components.InitialsAvatar
import androidx.compose.foundation.selection.toggleable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ripple
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import app.vaultchat.ui.components.pressScale
import app.vaultchat.ui.components.staggeredAppear
import app.vaultchat.ui.permissions.openAppSettings
import app.vaultchat.ui.theme.MintGradient
import app.vaultchat.ui.theme.glass
import app.vaultchat.ui.theme.glassBackdrop
import app.vaultchat.ui.theme.glow
import java.util.Locale
import app.vaultchat.ui.theme.glossy
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.view.WindowManager
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.vaultchat.R
import app.vaultchat.domain.connection.Connection
import app.vaultchat.domain.permissions.AppPermission
import app.vaultchat.domain.permissions.PermissionStatus
import app.vaultchat.domain.snap.SnapFailure
import app.vaultchat.domain.snap.SnapKind
import app.vaultchat.domain.snap.SnapRepository
import app.vaultchat.domain.snap.SnapResult
import app.vaultchat.domain.snap.SnapRules
import app.vaultchat.platform.snap.GalleryMedia
import app.vaultchat.platform.snap.SnapSaver
import app.vaultchat.ui.components.AppIcons
import app.vaultchat.ui.permissions.PermissionPreferences
import app.vaultchat.ui.permissions.rememberPermissionRequest
import app.vaultchat.ui.theme.VaultGreen
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What was just taken, before it is sent: a photo in memory, or a video in a temporary file. */
private sealed interface Taken {
    /** [fromGallery]: picked from the phone's gallery (owner, 2026-11-21), so it is already saved there. */
    val fromGallery: Boolean
    class Photo(val jpeg: ByteArray, val preview: Bitmap, override val fromGallery: Boolean = false) : Taken
    class Video(val file: File, override val fromGallery: Boolean = false) : Taken
}

/**
 * Taking a snap (owner decision D-27, 2026-11-19): tap for a photo, hold for a video of at most
 * [SnapRules.MAX_VIDEO_SECONDS] s, both straight from the camera (no gallery). The picture is kept in memory
 * and a video in the app's private cache only until it is sent or retaken; nothing is saved to the gallery.
 */
@Composable
fun SnapCameraScreen(
    repo: SnapRepository,
    /** The people this user is paired with; Send asks which of them get the snap (owner, 2026-11-19). */
    loadRecipients: suspend () -> List<Connection>,
    permissionPreferences: PermissionPreferences,
    onClose: () -> Unit,
    /** Connection ticked in advance (camera opened from a person's chat or row). */
    preselect: String? = null,
) {
    val context = LocalContext.current
    val camera = rememberPermissionRequest(AppPermission.CAMERA, permissionPreferences)
    val mic = rememberPermissionRequest(AppPermission.MICROPHONE, permissionPreferences)
    LaunchedEffect(Unit) { if (camera.status != PermissionStatus.Granted) camera.request() }

    var taken by remember { mutableStateOf<Taken?>(null) }
    var sending by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var recipients by remember { mutableStateOf<List<Connection>>(emptyList()) }
    LaunchedEffect(Unit) { recipients = loadRecipients() }
    var picking by remember { mutableStateOf(false) }
    // Writing or drawing on the photo before sending (owner, 2026-11-21); null while not editing.
    var editing by remember { mutableStateOf<SnapEditMode?>(null) }
    // View once (owner, 2026-11-21): the person sees it a single time, with no replay.
    var viewOnce by remember { mutableStateOf(false) }
    // A gallery video being made smaller before it can be sent.
    var preparing by remember { mutableStateOf(false) }
    val tooLongText = stringResource(R.string.snap_video_too_long, SnapRules.MAX_GALLERY_VIDEO_SECONDS)
    val galleryFailedText = stringResource(R.string.snap_gallery_failed)
    // Photos and videos from the gallery (owner, 2026-11-21; changes D-27), through Android's own photo picker:
    // the app sees only the one item chosen and needs no storage permission. Sent as snaps, like camera ones.
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        preparing = true
        scope.launch {
            val video = context.contentResolver.getType(uri).orEmpty().startsWith("video/")
            if (video) {
                when (val v = GalleryMedia.video(context, uri, SnapRules.MAX_GALLERY_VIDEO_SECONDS)) {
                    is GalleryMedia.Video.Ready -> taken = Taken.Video(v.file, fromGallery = true)
                    is GalleryMedia.Video.TooLong -> notice = tooLongText
                    GalleryMedia.Video.Failed -> notice = galleryFailedText
                }
            } else {
                val bitmap = GalleryMedia.photo(context, uri)
                if (bitmap == null) {
                    notice = galleryFailedText
                } else {
                    taken = withContext(Dispatchers.Default) { encodePhoto(bitmap, fromGallery = true) }
                }
            }
            preparing = false
        }
    }

    fun discard() {
        editing = null
        viewOnce = false
        (taken as? Taken.Video)?.file?.delete()
        taken = null
    }

    fun failureText(reason: SnapFailure) = context.getString(
        when (reason) {
            SnapFailure.TOO_LARGE -> R.string.snap_too_large
            SnapFailure.UNAVAILABLE -> R.string.snap_unavailable
            SnapFailure.NETWORK -> R.string.snap_no_network
            else -> R.string.snap_failed
        },
    )

    fun sendTo(chosen: List<Connection>) {
        val item = taken ?: return
        if (chosen.isEmpty()) return
        picking = false
        sending = true
        scope.launch {
            val (kind, bytes) = when (item) {
                is Taken.Photo -> SnapKind.PHOTO to item.jpeg
                is Taken.Video -> SnapKind.VIDEO to withContext(Dispatchers.IO) { item.file.readBytes() }
            }
            var sent = 0
            var failure: SnapFailure? = null
            for (person in chosen) {
                when (val r = repo.send(person.id, kind, bytes, viewOnce)) {
                    is SnapResult.Ok -> sent++
                    is SnapResult.Failure -> failure = r.reason
                }
            }
            sending = false
            if (sent > 0) {
                notice = context.resources.getQuantityString(R.plurals.snap_sent_to, sent, sent)
                discard()
                delay(1_200)
                onClose()
            } else {
                notice = failureText(failure ?: SnapFailure.UNEXPECTED)
            }
        }
    }

    DisposableEffect(Unit) { onDispose { (taken as? Taken.Video)?.file?.delete() } }
    BackHandler { if (taken != null && !sending) discard() else onClose() }

    Surface(modifier = Modifier.fillMaxSize(), color = VaultGreen.Deepest, contentColor = VaultGreen.Mint) {
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                camera.status != PermissionStatus.Granted -> Box(
                    modifier = Modifier.fillMaxSize().glassBackdrop().safeDrawingPadding().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // A glass card; when Android will no longer ask, its button opens the app's settings instead.
                    val blocked = camera.status == PermissionStatus.PermanentlyDenied
                    Column(
                        modifier = Modifier.staggeredAppear(0).glass(RoundedCornerShape(28.dp)).padding(horizontal = 24.dp, vertical = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier.size(64.dp).background(MintGradient, CircleShape).glossy(CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Icon(AppIcons.PhotoCamera, contentDescription = null, tint = VaultGreen.Deepest, modifier = Modifier.size(30.dp)) }
                        Text(
                            stringResource(if (blocked) R.string.scan_qr_camera_denied else R.string.snap_needs_camera),
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                        )
                        CameraPillButton(
                            label = stringResource(if (blocked) R.string.action_open_settings else R.string.talk_allow_microphone),
                            primary = true,
                            onClick = { if (blocked) openAppSettings(context) else camera.request() },
                        )
                    }
                }
                taken == null -> CaptureView(
                    withAudio = mic.status == PermissionStatus.Granted,
                    onPhoto = { taken = it },
                    onVideo = { taken = it },
                    onWantAudio = { if (mic.status != PermissionStatus.Granted) mic.request() },
                    onGallery = {
                        if (!preparing) gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    },
                )
                else -> taken?.let { ReviewView(it) }
            }

            val photo = taken as? Taken.Photo
            if (photo != null && editing == null) {
                // Text and pen for a photo, top right (videos are sent as recorded).
                Row(
                    modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ReviewTool("Aa", stringResource(R.string.snap_add_text)) { editing = SnapEditMode.TEXT }
                    ReviewTool("✎", stringResource(R.string.snap_draw)) { editing = SnapEditMode.DRAW }
                }
            }
            if (taken != null && editing == null) {
                // View once: a pill at the top left that turns bright when on.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .safeDrawingPadding()
                        .padding(12.dp)
                        .cameraGlass(CircleShape, on = viewOnce)
                        .clickable(enabled = !sending, role = Role.Switch, onClickLabel = stringResource(R.string.snap_view_once)) { viewOnce = !viewOnce }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text("①", color = if (viewOnce) VaultGreen.Deepest else Color.White, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.snap_view_once), color = if (viewOnce) VaultGreen.Deepest else Color.White, fontWeight = FontWeight.SemiBold)
                }
                Row(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CameraPillButton(stringResource(R.string.snap_retake), enabled = !sending, onClick = ::discard)
                    // Save your own photo or video to the phone (owner, 2026-11-20). Received snaps have no Save, and
                    // one picked from the gallery is already there.
                    val savedText = stringResource(R.string.snap_saved)
                    val saveFailedText = stringResource(R.string.snap_save_failed)
                    if (taken?.fromGallery != true) CameraPillButton(
                        label = stringResource(R.string.snap_save),
                        icon = AppIcons.Download,
                        enabled = !sending && !saving,
                        onClick = {
                            val item = taken ?: return@CameraPillButton
                            saving = true
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    when (item) {
                                        is Taken.Photo -> SnapSaver.savePhoto(context, item.jpeg)
                                        is Taken.Video -> SnapSaver.saveVideo(context, item.file)
                                    }
                                }
                                saving = false
                                notice = if (ok) savedText else saveFailedText
                            }
                        },
                    )
                    CameraPillButton(
                        label = stringResource(R.string.snap_send_to),
                        icon = AppIcons.ArrowUpward,
                        primary = true,
                        busy = sending,
                        enabled = !sending,
                        onClick = { picking = true },
                    )
                }
            }
            val mode = editing
            if (photo != null && mode != null) {
                SnapEditor(
                    photo = photo.preview,
                    startMode = mode,
                    onDone = { painted ->
                        editing = null
                        if (painted != null) scope.launch { taken = withContext(Dispatchers.Default) { encodePhoto(painted) } }
                    },
                    onCancel = { editing = null },
                )
            }
            if (preparing) {
                Box(
                    modifier = Modifier.fillMaxSize().background(VaultGreen.Deepest.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = Modifier.staggeredAppear(0).glass(RoundedCornerShape(24.dp)).padding(horizontal = 28.dp, vertical = 22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(color = VaultGreen.Sage, strokeWidth = 3.dp)
                        Text(stringResource(R.string.snap_preparing), modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
            if (picking) {
                RecipientPicker(recipients, preselect, onSend = ::sendTo, onDismiss = { picking = false })
            }
            notice?.let {
                key(it) {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 24.dp)
                            .staggeredAppear(0)
                            .cameraGlass(CircleShape, on = false)
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                    )
                }
                LaunchedEffect(it) { delay(3_000); notice = null }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun CaptureView(
    withAudio: Boolean,
    onPhoto: (Taken.Photo) -> Unit,
    onVideo: (Taken.Video) -> Unit,
    onWantAudio: () -> Unit,
    onGallery: () -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var front by remember { mutableStateOf(false) }
    // Best photo the camera gives (owner, 2026-11-19: better snap quality): the full sensor resolution and the
    // camera's quality processing (a little slower to take than "minimise latency").
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            // The camera's own JPEG at full quality, so the one re-encode after rotation starts from the
            // cleanest picture (owner 2026-11-20: photos looked soft).
            .setJpegQuality(100)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build(),
            )
            .build()
    }
    val videoCapture = remember {
        // Front camera videos mirrored like the preview (owner, 2026-11-19).
        VideoCapture.Builder(
            Recorder.Builder()
                // 1080p where the phone can (720p, then 480p otherwise). 12 Mbps keeps a 7 s video near 11 MB,
                // under the server's 20 MB limit.
                .setQualitySelector(
                    QualitySelector.fromOrderedList(
                        listOf(Quality.FHD, Quality.HD, Quality.SD),
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                    ),
                )
                .setTargetVideoEncodingBitRate(VIDEO_BITRATE)
                .build(),
        ).setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY).build()
    }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var taking by remember { mutableStateOf(false) }
    // The bound camera, for pinch-to-zoom before taking the photo or video.
    var boundCamera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    var secondsLeft by remember { mutableIntStateOf(SnapRules.MAX_VIDEO_SECONDS) }
    val scope = rememberCoroutineScope()
    // Flash and sound (owner, 2026-11-20). Back camera: the phone's flash for a photo, its light for a video.
    // Front camera: the screen turns white at full brightness while the photo is taken, and a white glow
    // frames the picture while a video records. Sound off records the video without the microphone.
    var flash by rememberSaveable { mutableStateOf(false) }
    var muted by rememberSaveable { mutableStateOf(false) }
    var screenFlash by remember { mutableStateOf(false) }
    var night by rememberSaveable { mutableStateOf(false) }
    // True while the phone's own night mode is on, which takes photos only.
    var nightPhotoOnly by remember { mutableStateOf(false) }
    var focusAt by remember { mutableStateOf<Offset?>(null) }
    var focusTick by remember { mutableIntStateOf(0) }
    val haptics = LocalHapticFeedback.current
    // Premium camera motion (owner 2026-11-22). A black cover hides the moment the camera starts or switches, then
    // fades away; a white flash marks the shutter; the zoom level shows while it is not 1x.
    val cover = remember { Animatable(1f) }
    var binds by remember { mutableIntStateOf(0) }
    LaunchedEffect(front, night) {
        if (binds++ > 0) cover.snapTo(1f)
        delay(260)
        cover.animateTo(0f, tween(380, easing = FastOutSlowInEasing))
    }
    val shutterFlash = remember { Animatable(0f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(front) { zoom = 1f }
    var shutterDown by remember { mutableStateOf(false) }
    val glowing = screenFlash || (recording != null && flash && front)
    val window = remember(context) { context.findActivity()?.window }
    DisposableEffect(glowing, window) {
        val w = window
        if (glowing && w != null) {
            val before = w.attributes.screenBrightness
            w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
            onDispose { w.attributes = w.attributes.apply { screenBrightness = before } }
        } else {
            onDispose { }
        }
    }

    LaunchedEffect(Unit) { onWantAudio() }
    DisposableEffect(owner, front, night) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        val main = ContextCompat.getMainExecutor(context)
        future.addListener({
            val p = future.get()
            provider = p
            val base = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            fun bind(selector: CameraSelector, photoOnly: Boolean) {
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                p.unbindAll()
                boundCamera = runCatching {
                    // The phone's own night mode works for photos only (CameraX extensions cannot record video).
                    if (photoOnly) p.bindToLifecycle(owner, selector, preview, imageCapture)
                    else p.bindToLifecycle(owner, selector, preview, imageCapture, videoCapture)
                }.getOrNull()
                nightPhotoOnly = photoOnly && boundCamera != null
                // Without the phone's night mode, night is the brightest exposure the camera allows.
                if (night && !photoOnly) {
                    boundCamera?.let { cam ->
                        val exposure = cam.cameraInfo.exposureState
                        if (exposure.isExposureCompensationSupported) cam.cameraControl.setExposureCompensationIndex(exposure.exposureCompensationRange.upper)
                    }
                }
            }
            if (!night) {
                bind(base, photoOnly = false)
            } else {
                // Night mode (owner, 2026-11-21): the phone maker's night processing where it is offered.
                val extensions = ExtensionsManager.getInstanceAsync(context, p)
                extensions.addListener({
                    val manager = runCatching { extensions.get() }.getOrNull()
                    if (manager != null && runCatching { manager.isExtensionAvailable(base, ExtensionMode.NIGHT) }.getOrDefault(false)) {
                        bind(manager.getExtensionEnabledCameraSelector(base, ExtensionMode.NIGHT), photoOnly = true)
                    } else {
                        bind(base, photoOnly = false)
                    }
                }, main)
            }
        }, main)
        onDispose {
            recording?.stop()
            provider?.unbindAll()
        }
    }

    fun startVideo() {
        val file = File(context.cacheDir, "snap-${System.currentTimeMillis()}.mp4")
        val pending = videoCapture.output.prepareRecording(context, FileOutputOptions.Builder(file).build())
            .let { if (withAudio && !muted && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) it.withAudioEnabled() else it }
        recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            if (event is VideoRecordEvent.Finalize) {
                recording = null
                boundCamera?.cameraControl?.enableTorch(false)
                if (!event.hasError() || event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED) onVideo(Taken.Video(file)) else file.delete()
            }
        }
        if (flash && !front) boundCamera?.cameraControl?.enableTorch(true)
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        secondsLeft = SnapRules.MAX_VIDEO_SECONDS
        scope.launch {
            while (recording != null && secondsLeft > 0) {
                delay(1_000)
                secondsLeft--
            }
            recording?.stop()
        }
    }

    fun capturePhoto() {
        val mirror = front
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        scope.launch {
            shutterFlash.snapTo(0.85f)
            shutterFlash.animateTo(0f, tween(280, easing = FastOutSlowInEasing))
        }
        imageCapture.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                screenFlash = false
                // Decoding a full-resolution photo takes a moment: off the main thread, so
                // the camera screen never freezes (owner, 2026-11-19: smoother UI).
                scope.launch {
                    val photo = withContext(Dispatchers.Default) {
                        try {
                            toUprightJpeg(image.toBitmap(), image.imageInfo.rotationDegrees, mirror)
                        } finally {
                            image.close()
                        }
                    }
                    taking = false
                    onPhoto(photo)
                }
            }
            override fun onError(exception: ImageCaptureException) {
                screenFlash = false
                taking = false
            }
        })
    }

    fun takePhoto() {
        imageCapture.flashMode = if (flash && !front) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
        if (flash && front) {
            // White screen at full brightness first; the camera needs a moment to adjust to the new light.
            screenFlash = true
            scope.launch {
                delay(SCREEN_FLASH_MS)
                capturePhoto()
            }
        } else {
            capturePhoto()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Pinch on the picture to zoom the camera (owner request 2026-11-20), within what the lens allows.
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(boundCamera) {
                    detectTransformGestures { _, _, change, _ ->
                        val cam = boundCamera ?: return@detectTransformGestures
                        val zoomState = cam.cameraInfo.zoomState.value ?: return@detectTransformGestures
                        val next = (zoomState.zoomRatio * change).coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                        cam.cameraControl.setZoomRatio(next)
                        zoom = next
                    }
                }
                .pointerInput(boundCamera) {
                    detectTapGestures(
                        // Tap to focus and set the light there (owner, 2026-11-21); a ring shows where.
                        onTap = { at ->
                            val cam = boundCamera ?: return@detectTapGestures
                            val point = previewView.meteringPointFactory.createPoint(at.x, at.y)
                            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                                .setAutoCancelDuration(4, TimeUnit.SECONDS)
                                .build()
                            runCatching { cam.cameraControl.startFocusAndMetering(action) }
                            focusAt = at
                            focusTick++
                        },
                        // Double-tap to switch between the front and back camera, as in Snapchat.
                        onDoubleTap = { if (recording == null) front = !front },
                    )
                },
        )
        focusAt?.let { at ->
            LaunchedEffect(focusTick) {
                delay(900)
                focusAt = null
            }
            key(focusTick) {
                // The ring closes in on the point with a spring, like a lens finding focus.
                val ring = remember { Animatable(1.35f) }
                LaunchedEffect(Unit) { ring.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 420f)) }
                Box(
                    Modifier
                        .offset { IntOffset(at.x.roundToInt() - 36.dp.roundToPx(), at.y.roundToInt() - 36.dp.roundToPx()) }
                        .size(72.dp)
                        .graphicsLayer {
                            scaleX = ring.value
                            scaleY = ring.value
                        }
                        .border(1.5.dp, Color.White, CircleShape),
                )
            }
        }
        // Bottom controls (owner 2026-11-22: they overlapped): the zoom pill and a short-lived hint stack above one
        // row in which the gallery sits level with the shutter, so nothing can cover anything else.
        var hintShown by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) {
            delay(4_000)
            hintShown = false
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .safeDrawingPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
                .staggeredAppear(1),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The zoom level, while it is not 1x; tap it to go back to 1x.
            AnimatedVisibility(
                visible = zoom > 1.04f && recording == null,
                enter = fadeIn() + scaleIn(spring(dampingRatio = 0.6f, stiffness = 500f), initialScale = 0.7f),
                exit = fadeOut() + scaleOut(targetScale = 0.7f),
            ) {
                Text(
                    String.format(Locale.US, "%.1f×", zoom),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier
                        .cameraGlass(CircleShape, on = false)
                        .clickable(role = Role.Button) {
                            val cam = boundCamera ?: return@clickable
                            val min = cam.cameraInfo.zoomState.value?.minZoomRatio ?: 1f
                            cam.cameraControl.setZoomRatio(min)
                            zoom = min
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            // While recording: a breathing red dot and the time. Otherwise the hint, which fades after a few seconds,
            // and the night-mode note, which stays while it applies.
            AnimatedVisibility(
                visible = recording != null || nightPhotoOnly || hintShown,
                enter = fadeIn() + scaleIn(initialScale = 0.9f),
                exit = fadeOut() + scaleOut(targetScale = 0.9f),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .cameraGlass(RoundedCornerShape(20.dp), on = false)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    if (recording != null) {
                        RecordingDot()
                        Text(String.format(Locale.US, "0:%02d", SnapRules.MAX_VIDEO_SECONDS - secondsLeft), style = MaterialTheme.typography.labelLarge, color = Color.White)
                    } else {
                        Text(
                            if (nightPhotoOnly) stringResource(R.string.snap_night_photos_only) else stringResource(R.string.snap_hint, SnapRules.MAX_VIDEO_SECONDS),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // Gallery: a photo or a video (up to 30 s) (owner, 2026-11-21), level with the shutter, as in Snapchat.
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    // Fades out (and stops taking taps) while a video records.
                    val idle = recording == null
                    val shown by animateFloatAsState(if (idle) 1f else 0f, label = "galleryShown")
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .graphicsLayer { alpha = shown }
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = idle, role = Role.Button, onClickLabel = stringResource(R.string.snap_gallery), onClick = onGallery)
                            .padding(6.dp),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(48.dp).cameraGlass(RoundedCornerShape(14.dp), on = false),
                        ) {
                            Icon(AppIcons.Gallery, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                        }
                        Text(stringResource(R.string.snap_gallery), color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                // The shutter: a white ring around a white disc. The disc dips under the finger; while a video records it
                // shrinks into a red rounded square and a red arc runs round the ring for the time allowed.
                val recordingNow = recording != null
                val progress = remember { Animatable(0f) }
                LaunchedEffect(recordingNow) {
                    if (recordingNow) {
                        progress.snapTo(0f)
                        progress.animateTo(1f, tween(SnapRules.MAX_VIDEO_SECONDS * 1000, easing = LinearEasing))
                    } else {
                        progress.snapTo(0f)
                    }
                }
                val discScale by animateFloatAsState(
                    when {
                        recordingNow -> 0.56f
                        shutterDown -> 0.86f
                        else -> 1f
                    },
                    spring(dampingRatio = 0.55f, stiffness = 600f),
                    label = "shutterDisc",
                )
                val discColor by animateColorAsState(if (recordingNow) RecordRed else Color.White, label = "shutterColor")
                val corner by animateIntAsState(if (recordingNow) 28 else 50, label = "shutterCorner")
                val error = RecordRed
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .size(84.dp)
                        .drawBehind {
                            val stroke = 5.dp.toPx()
                            val inset = stroke / 2f
                            drawCircle(Color.White.copy(alpha = 0.92f), radius = size.minDimension / 2f - inset, style = Stroke(stroke))
                            val p = progress.value
                            if (p > 0f) {
                                drawArc(
                                    error,
                                    startAngle = -90f,
                                    sweepAngle = 360f * p,
                                    useCenter = false,
                                    topLeft = Offset(inset, inset),
                                    size = Size(size.width - stroke, size.height - stroke),
                                    style = Stroke(stroke, cap = StrokeCap.Round),
                                )
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    // One photo at a time; a second tap while it is being made does nothing.
                                    if (taking) return@detectTapGestures
                                    taking = true
                                    takePhoto()
                                },
                                onLongPress = { if (!nightPhotoOnly) startVideo() },
                                onPress = {
                                    shutterDown = true
                                    tryAwaitRelease()
                                    shutterDown = false
                                    recording?.stop()
                                },
                            )
                        }
                        .semantics { contentDescription = context.getString(R.string.snap_hint, SnapRules.MAX_VIDEO_SECONDS) },
                ) {
                    Box(
                        Modifier
                            .size(64.dp)
                            .graphicsLayer {
                                scaleX = discScale
                                scaleY = discScale
                            }
                            .clip(RoundedCornerShape(corner))
                            .background(discColor)
                            .glossy(RoundedCornerShape(corner), strength = 0.5f),
                    )
                }
                // Balances the gallery, so the shutter stays in the middle.
                Spacer(Modifier.weight(1f))
            }
        }
        // Front camera video with flash: a warm white glow around the picture lights the face.
        if (recording != null && flash && front) {
            Box(Modifier.fillMaxSize().border(40.dp, GlowWhite))
        }
        Column(
            modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(12.dp).staggeredAppear(0),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Flip (also a double-tap on the picture): the icon turns over as the camera switches.
            val turn by animateFloatAsState(if (front) 180f else 0f, spring(dampingRatio = 0.7f, stiffness = 300f), label = "flipTurn")
            CameraToggle(
                icon = AppIcons.FlipCamera,
                description = stringResource(R.string.snap_flip),
                on = false,
                enabled = recording == null,
                iconModifier = Modifier.graphicsLayer { rotationZ = turn },
                onClick = { front = !front },
            )
            CameraToggle(
                icon = AppIcons.NightMode,
                description = stringResource(if (night) R.string.snap_night_on else R.string.snap_night_off),
                on = night,
                enabled = recording == null,
                onClick = { night = !night },
            )
            CameraToggle(
                icon = if (flash) AppIcons.FlashOn else AppIcons.FlashOff,
                description = stringResource(if (flash) R.string.snap_flash_on else R.string.snap_flash_off),
                on = flash,
                onClick = { flash = !flash },
            )
            val soundOn = withAudio && !muted
            CameraToggle(
                icon = if (soundOn) AppIcons.Mic else AppIcons.MicOff,
                description = stringResource(if (soundOn) R.string.snap_sound_on else R.string.snap_sound_off),
                on = soundOn,
                enabled = recording == null,
                onClick = { if (!withAudio) onWantAudio() else muted = !muted },
            )
        }
        // Front camera photo with flash: the whole screen white until the photo is taken.
        if (screenFlash) {
            Box(Modifier.fillMaxSize().background(Color.White))
        }
        // The shutter's white blink, and the black cover while the camera starts or switches. Drawn only while
        // visible, read in the draw phase.
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val c = cover.value
                    if (c > 0f) drawRect(Color.Black, alpha = c)
                    val f = shutterFlash.value
                    if (f > 0f) drawRect(Color.White, alpha = f)
                },
        )
    }
}

/** The red of a recording: brighter than the error colour, so it reads over any picture. */
private val RecordRed = Color(0xFFFF453A)

/** A red dot that breathes while a video records. */
@Composable
private fun RecordingDot() {
    val transition = rememberInfiniteTransition(label = "rec")
    val a by transition.animateFloat(1f, 0.35f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "recDot")
    Box(Modifier.size(9.dp).graphicsLayer { alpha = a }.background(RecordRed, CircleShape))
}

/**
 * Glass over the camera: a dark tint first, so white icons and text read over a bright picture, then the glass
 * rim and grain. [on] makes it a bright glossy white, for a control that is switched on.
 */
@Composable
private fun Modifier.cameraGlass(shape: androidx.compose.ui.graphics.Shape, on: Boolean): Modifier =
    if (on) {
        clip(shape).background(Color.White.copy(alpha = 0.94f)).glossy(shape, strength = 0.5f)
    } else {
        background(Color.Black.copy(alpha = 0.32f), shape).glass(shape)
    }

/** A pill button on the camera screens: glass, or mint with depth for the main action. */
@Composable
private fun CameraPillButton(
    label: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(50)
    val content = if (primary) VaultGreen.Deepest else Color.White
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .heightIn(min = 48.dp)
            .pressScale(interaction, 0.94f)
            .then(if (primary) Modifier.glow(VaultGreen.Sage, spread = 10.dp, strength = 0.3f).clip(shape).background(MintGradient).glossy(shape, strength = 0.7f) else Modifier.cameraGlass(shape, on = false))
            .clickable(interactionSource = interaction, indication = ripple(), enabled = enabled, role = Role.Button, onClick = onClick)
            .graphicsLayer { alpha = if (enabled || busy) 1f else 0.5f }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = content)
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        }
        Text(label, color = content, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** A round button over the camera: flash or sound, bright when on. */
@Composable
private fun CameraToggle(
    icon: ImageVector,
    description: String,
    on: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    iconModifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(46.dp)
            .pressScale(interaction, 0.86f)
            .cameraGlass(CircleShape, on = on)
            .clickable(interactionSource = interaction, indication = ripple(), enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .semantics { contentDescription = description },
    ) {
        Icon(icon, contentDescription = null, tint = if (on) VaultGreen.Deepest else Color.White, modifier = iconModifier.size(22.dp))
    }
}

/** Finds the activity behind a Compose context (for the screen brightness). */
private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

private val GlowWhite = Color(0xFFFFF6E8)

/** How long the white screen shows before a front camera photo with flash. */
private const val SCREEN_FLASH_MS = 600L

private const val VIDEO_BITRATE = 12_000_000

/** Longest side of a sent photo: about 8 megapixels, sharp even when zoomed in (was 2560, before that 1600). */
private const val PHOTO_LONG_SIDE = 3264

/** The server takes photos up to 6 MB; this leaves room. */
private const val PHOTO_MAX_BYTES = 5_500_000

/** Turns the camera frame upright and makes a JPEG of at most [PHOTO_LONG_SIDE] px on the long side. */
private fun toUprightJpeg(raw: Bitmap, rotation: Int, mirror: Boolean): Taken.Photo {
    val longSide = maxOf(raw.width, raw.height)
    val scale = if (longSide > PHOTO_LONG_SIDE) PHOTO_LONG_SIDE.toFloat() / longSide else 1f
    val matrix = Matrix().apply {
        postScale(scale, scale)
        postRotate(rotation.toFloat())
        // A front-camera photo comes out as the preview showed it (mirrored), as in Snapchat (owner, 2026-11-19).
        if (mirror) postScale(-1f, 1f)
    }
    return encodePhoto(Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true))
}

/** A JPEG of [upright] for sending, with the bitmap kept for the preview. */
private fun encodePhoto(upright: Bitmap, fromGallery: Boolean = false): Taken.Photo {
    // High JPEG quality; lowered step by step only if a very detailed photo would pass the server's limit.
    var bytes = ByteArray(0)
    for (quality in intArrayOf(95, 90, 85, 75, 65)) {
        val out = ByteArrayOutputStream()
        upright.compress(Bitmap.CompressFormat.JPEG, quality, out)
        bytes = out.toByteArray()
        if (bytes.size <= PHOTO_MAX_BYTES) break
    }
    return Taken.Photo(bytes, upright, fromGallery)
}

/** A round button over the photo before sending: "Aa" for text, the pen for drawing. */
@Composable
private fun ReviewTool(label: String, description: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(46.dp)
            .cameraGlass(CircleShape, on = false)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
    ) {
        Text(label, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ReviewView(taken: Taken) {
    // The picture settles into place: from a touch larger and faded to full, like a photo developing.
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 320f)) }
    Box(
        Modifier.fillMaxSize().graphicsLayer {
            val v = shown.value
            alpha = v.coerceIn(0f, 1f)
            val scale = 1.06f - 0.06f * v
            scaleX = scale
            scaleY = scale
        },
    ) { ReviewContent(taken) }
}

@Composable
private fun ReviewContent(taken: Taken) {
    when (taken) {
        // Pinch or double-tap to zoom the photo before sending it (owner request 2026-11-20).
        is Taken.Photo -> {
            val image = remember(taken) { taken.preview.asImageBitmap() }
            ZoomableImage(image)
        }
        is Taken.Video -> LoopingVideo(taken.file, Modifier.fillMaxSize())
    }
}

/** Plays a local video file again and again (the preview before sending). */
@Composable
internal fun LoopingVideo(file: File, modifier: Modifier = Modifier, loop: Boolean = true, onDone: () -> Unit = {}) {
    AndroidView(
        factory = { ctx: Context ->
            VideoView(ctx).apply {
                setVideoURI(Uri.fromFile(file))
                setOnPreparedListener { it.isLooping = loop; start() }
                setOnCompletionListener { if (!loop) onDone() }
            }
        },
        onRelease = { it.stopPlayback() },
        modifier = modifier,
    )
}


/** "Send to…": the people this user is paired with, any number of them ticked (as in Snapchat). */
@Composable
private fun RecipientPicker(people: List<Connection>, preselect: String?, onSend: (List<Connection>) -> Unit, onDismiss: () -> Unit) {
    var chosen by remember { mutableStateOf(setOfNotNull(preselect)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.snap_send_to)) },
        text = {
            if (people.isEmpty()) {
                Text(stringResource(R.string.snap_no_people))
            } else {
                Column(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    // Each person as a row with their avatar; a chosen row lights up softly (owner 2026-11-22: premium).
                    people.forEach { p ->
                        val picked = p.id in chosen
                        val tint by animateColorAsState(if (picked) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent, label = "pickRow")
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(tint)
                                .toggleable(value = picked, role = Role.Checkbox) { chosen = if (picked) chosen - p.id else chosen + p.id }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                        ) {
                            InitialsAvatar(p.peerUsername, size = 36.dp)
                            Text(p.peerUsername, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp).weight(1f))
                            if (p.streak > 0) Text("\uD83D\uDD25 ${p.streak}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 4.dp))
                            Checkbox(checked = picked, onCheckedChange = null)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = chosen.isNotEmpty(), onClick = { onSend(people.filter { it.id in chosen }) }) {
                Text(stringResource(R.string.snap_send_count, chosen.size))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
