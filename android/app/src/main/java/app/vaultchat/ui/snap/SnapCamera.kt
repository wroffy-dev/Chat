package app.vaultchat.ui.snap

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
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.unit.dp
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
import app.vaultchat.platform.snap.SnapSaver
import app.vaultchat.ui.components.AppIcons
import app.vaultchat.ui.permissions.PermissionPreferences
import app.vaultchat.ui.permissions.rememberPermissionRequest
import app.vaultchat.ui.theme.VaultGreen
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What was just taken, before it is sent: a photo in memory, or a video in a temporary file. */
private sealed interface Taken {
    class Photo(val jpeg: ByteArray, val preview: Bitmap) : Taken
    class Video(val file: File) : Taken
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

    fun discard() {
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
                when (val r = repo.send(person.id, kind, bytes)) {
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
                camera.status != PermissionStatus.Granted -> Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.snap_needs_camera), style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = camera::request) { Text(stringResource(R.string.talk_allow_microphone)) }
                }
                taken == null -> CaptureView(
                    withAudio = mic.status == PermissionStatus.Granted,
                    onPhoto = { taken = it },
                    onVideo = { taken = it },
                    onWantAudio = { if (mic.status != PermissionStatus.Granted) mic.request() },
                )
                else -> ReviewView(taken!!)
            }

            if (taken != null) {
                Row(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = ::discard, enabled = !sending) { Text(stringResource(R.string.snap_retake), color = VaultGreen.Mint) }
                    // Save your own photo or video to the phone (owner, 2026-11-20). Received snaps have no Save.
                    val savedText = stringResource(R.string.snap_saved)
                    val saveFailedText = stringResource(R.string.snap_save_failed)
                    TextButton(
                        enabled = !sending && !saving,
                        onClick = {
                            val item = taken ?: return@TextButton
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
                    ) {
                        Icon(AppIcons.Download, contentDescription = null, tint = VaultGreen.Mint, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.snap_save), color = VaultGreen.Mint)
                    }
                    Button(
                        enabled = !sending,
                        colors = ButtonDefaults.buttonColors(containerColor = VaultGreen.Sage, contentColor = VaultGreen.Deepest),
                        onClick = { picking = true },
                    ) {
                        if (sending) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = VaultGreen.Deepest)
                        else Text(stringResource(R.string.snap_send_to))
                    }
                }
            }
            if (picking) {
                RecipientPicker(recipients, preselect, onSend = ::sendTo, onDismiss = { picking = false })
            }
            notice?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 24.dp)
                        .background(VaultGreen.Forest, CircleShape).padding(horizontal = 16.dp, vertical = 8.dp),
                )
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
    DisposableEffect(owner, front) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            val p = future.get()
            provider = p
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            p.unbindAll()
            boundCamera = runCatching {
                p.bindToLifecycle(owner, if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, videoCapture)
            }.getOrNull()
        }, ContextCompat.getMainExecutor(context))
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
            modifier = Modifier.fillMaxSize().pointerInput(boundCamera) {
                detectTransformGestures { _, _, change, _ ->
                    val cam = boundCamera ?: return@detectTransformGestures
                    val zoomState = cam.cameraInfo.zoomState.value ?: return@detectTransformGestures
                    val next = (zoomState.zoomRatio * change).coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                    cam.cameraControl.setZoomRatio(next)
                }
            },
        )
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (recording != null) "● $secondsLeft s" else stringResource(R.string.snap_hint, SnapRules.MAX_VIDEO_SECONDS),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.background(VaultGreen.Deepest.copy(alpha = 0.6f), CircleShape).padding(horizontal = 12.dp, vertical = 6.dp),
            )
            Box(
                modifier = Modifier
                    .padding(top = 16.dp)
                    .size(78.dp)
                    .border(5.dp, if (recording != null) MaterialTheme.colorScheme.error else VaultGreen.Mint, CircleShape)
                    .padding(8.dp)
                    .background(if (recording != null) MaterialTheme.colorScheme.error else VaultGreen.Sage, CircleShape)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                // One photo at a time; a second tap while it is being made does nothing.
                                if (taking) return@detectTapGestures
                                taking = true
                                takePhoto()
                            },
                            onLongPress = { startVideo() },
                            onPress = {
                                tryAwaitRelease()
                                recording?.stop()
                            },
                        )
                    },
            )
            TextButton(onClick = { front = !front }, enabled = recording == null) { Text(stringResource(R.string.snap_flip), color = VaultGreen.Mint) }
        }
        // Front camera video with flash: a warm white glow around the picture lights the face.
        if (recording != null && flash && front) {
            Box(Modifier.fillMaxSize().border(40.dp, GlowWhite))
        }
        Column(
            modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
    }
}

/** A round button over the camera: flash or sound, bright when on. */
@Composable
private fun CameraToggle(icon: ImageVector, description: String, on: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(if (on) VaultGreen.Mint else VaultGreen.Deepest.copy(alpha = 0.55f))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        Icon(icon, contentDescription = null, tint = if (on) VaultGreen.Deepest else Color.White, modifier = Modifier.size(24.dp))
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
    val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
    // High JPEG quality; lowered step by step only if a very detailed photo would pass the server's limit.
    var bytes = ByteArray(0)
    for (quality in intArrayOf(95, 90, 85, 75, 65)) {
        val out = ByteArrayOutputStream()
        upright.compress(Bitmap.CompressFormat.JPEG, quality, out)
        bytes = out.toByteArray()
        if (bytes.size <= PHOTO_MAX_BYTES) break
    }
    return Taken.Photo(bytes, upright)
}

@Composable
private fun ReviewView(taken: Taken) {
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
                    people.forEach { p ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { chosen = if (p.id in chosen) chosen - p.id else chosen + p.id }.padding(vertical = 6.dp),
                        ) {
                            Checkbox(checked = p.id in chosen, onCheckedChange = null)
                            Text(p.peerUsername, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp).weight(1f))
                            if (p.streak > 0) Text("\uD83D\uDD25 ${p.streak}", style = MaterialTheme.typography.labelLarge)
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
