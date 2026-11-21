package app.vaultchat.ui.snap

import android.app.Activity
import android.graphics.BitmapFactory
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.vaultchat.R
import app.vaultchat.domain.snap.OpenedSnap
import app.vaultchat.domain.snap.SnapKind
import app.vaultchat.domain.snap.SnapRepository
import app.vaultchat.domain.snap.SnapResult
import app.vaultchat.domain.snap.SnapRules
import app.vaultchat.ui.theme.VaultGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

private enum class Stage { OPENING, SHOWING, AFTER, GONE }

/**
 * Viewing snaps (D-27): opens the waiting snaps from one person, oldest first. Opening downloads a snap and
 * the server deletes it at once; the bytes stay in memory (a video in a private cache file while it plays)
 * for one replay, and are dropped when the next snap opens or this screen closes. Screenshots and screen
 * recordings are blocked while it is shown (FLAG_SECURE; another camera cannot be stopped).
 */
@Composable
fun SnapViewerScreen(repo: SnapRepository, snapIds: List<String>, onClose: () -> Unit) {
    val context = LocalContext.current
    DisposableEffect(context) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    var index by remember { mutableIntStateOf(0) }
    var stage by remember { mutableStateOf(Stage.OPENING) }
    var snap by remember { mutableStateOf<OpenedSnap?>(null) }
    var replaysLeft by remember { mutableIntStateOf(SnapRules.REPLAYS) }
    var showing by remember { mutableIntStateOf(0) }
    var videoFile by remember { mutableStateOf<File?>(null) }

    fun dropCurrent() {
        videoFile?.delete()
        videoFile = null
        snap = null
    }
    DisposableEffect(Unit) { onDispose { dropCurrent() } }
    BackHandler(onBack = onClose)

    LaunchedEffect(index) {
        if (index >= snapIds.size) return@LaunchedEffect onClose()
        dropCurrent()
        stage = Stage.OPENING
        when (val r = repo.open(snapIds[index])) {
            is SnapResult.Ok -> {
                snap = r.value
                if (r.value.kind == SnapKind.VIDEO) {
                    videoFile = withContext(Dispatchers.IO) {
                        File.createTempFile("view-", ".mp4", context.cacheDir).apply { writeBytes(r.value.bytes) }
                    }
                }
                // View once (owner, 2026-11-21): shown a single time, no replay.
                replaysLeft = if (r.value.viewOnce) 0 else SnapRules.REPLAYS
                stage = Stage.SHOWING
                showing++
            }
            is SnapResult.Failure -> stage = Stage.GONE
        }
    }
    fun next() {
        if (index + 1 < snapIds.size) index++ else onClose()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = VaultGreen.Deepest, contentColor = VaultGreen.Mint) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (stage) {
                Stage.OPENING -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = VaultGreen.Sage)
                    Text(stringResource(R.string.snap_opening), modifier = Modifier.padding(top = 12.dp))
                }
                Stage.GONE -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.snap_gone), style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = ::next) { Text(stringResource(R.string.call_close), color = VaultGreen.Mint) }
                }
                Stage.SHOWING -> {
                    val current = snap
                    if (current != null) {
                        // Photos close by themselves after a few seconds or on a tap; videos when they end.
                        Box(modifier = Modifier.fillMaxSize().clickable { stage = Stage.AFTER }) {
                            if (current.kind == SnapKind.PHOTO) {
                                // Decoded off the main thread, so opening a snap does not stutter.
                                var bitmap by remember(showing) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
                                LaunchedEffect(showing) {
                                    bitmap = withContext(Dispatchers.Default) {
                                        BitmapFactory.decodeByteArray(current.bytes, 0, current.bytes.size)?.asImageBitmap()
                                    }
                                }
                                // Pinch or double-tap to zoom; a single tap still closes the photo.
                                bitmap?.let { ZoomableImage(it, onTap = { stage = Stage.AFTER }) }
                                var progress by remember(showing) { mutableFloatStateOf(1f) }
                                LaunchedEffect(showing) {
                                    val steps = SnapRules.PHOTO_SECONDS * 10
                                    for (i in steps downTo 0) {
                                        progress = i / steps.toFloat()
                                        delay(100)
                                    }
                                    stage = Stage.AFTER
                                }
                                LinearProgressIndicator(
                                    progress = { progress },
                                    color = VaultGreen.Sage,
                                    trackColor = VaultGreen.Forest,
                                    modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(12.dp),
                                )
                            } else {
                                videoFile?.let { file ->
                                    androidx.compose.runtime.key(showing) {
                                        LoopingVideo(file, Modifier.fillMaxSize(), loop = false, onDone = { stage = Stage.AFTER })
                                    }
                                }
                            }
                            if (current.viewOnce) {
                                Text(
                                    stringResource(R.string.snap_view_once_badge),
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .safeDrawingPadding()
                                        .padding(top = 28.dp, start = 16.dp)
                                        .background(VaultGreen.Deepest.copy(alpha = 0.6f), CircleShape)
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                            Text(
                                stringResource(R.string.snap_tap_to_close),
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(16.dp),
                            )
                        }
                    }
                }
                Stage.AFTER -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (replaysLeft > 0 && snap != null) {
                        Button(
                            onClick = {
                                replaysLeft--
                                showing++
                                stage = Stage.SHOWING
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = VaultGreen.Sage, contentColor = VaultGreen.Deepest),
                        ) { Text(stringResource(R.string.snap_replay)) }
                    }
                    TextButton(onClick = ::next) { Text(stringResource(R.string.call_close), color = VaultGreen.Mint) }
                }
            }
        }
    }
}
