package app.vaultchat.ui.call

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vaultchat.R
import app.vaultchat.domain.call.CallKind
import app.vaultchat.domain.call.CallPhase
import app.vaultchat.platform.call.CallForegroundService
import kotlin.math.roundToInt

/**
 * A minimised call (owner request 2026-11-13): the call goes on while the person uses the rest of the app,
 * for example the chat. A small picture of the other person (or, without video, a "call in progress" pill)
 * floats over the screen; it can be dragged, and a tap goes back to the call screen. When the call ends
 * it disappears.
 */
@Composable
fun MiniCall(session: CallSession, onOpen: (video: Boolean, peerUsername: String) -> Unit, onEnded: () -> Unit) {
    val state by session.controller.state.collectAsStateWithLifecycle()
    val remoteVideo by session.media.remoteVideo.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(state.phase) { if (state.phase == CallPhase.ENDED) onEnded() }
    if (state.phase == CallPhase.ENDED) return

    // The call screen is gone, so this keeps the call's foreground service running.
    val video = state.kind == CallKind.VIDEO
    DisposableEffect(video) {
        CallForegroundService.start(context, video = video)
        onDispose { CallForegroundService.stop(context) }
    }

    var drag by remember { mutableStateOf(Offset.Zero) }
    val open = { onOpen(video, state.peerUsername) }
    Box(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
        val place = Modifier
            .align(Alignment.TopEnd)
            .offset { IntOffset(drag.x.roundToInt(), drag.y.roundToInt()) }
            .pointerInput(Unit) {
                detectDragGestures { change, amount ->
                    change.consume()
                    drag += amount
                }
            }
        if (video && remoteVideo != null) {
            Box(modifier = place.padding(top = 64.dp).size(width = 108.dp, height = 156.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black)) {
                VideoView(session.media.room, remoteVideo, mirror = false, modifier = Modifier.fillMaxSize())
                // The video view takes no touches itself; this layer makes the picture tappable.
                Box(modifier = Modifier.fillMaxSize().clickable(onClickLabel = stringResource(R.string.call_return), onClick = open))
            }
        } else {
            Surface(
                shape = RoundedCornerShape(50),
                color = Color(0xFF1E8E3E),
                contentColor = Color.White,
                shadowElevation = 6.dp,
                modifier = place.padding(top = 64.dp).clip(RoundedCornerShape(50)).clickable(onClickLabel = stringResource(R.string.call_return), onClick = open),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Icon(Icons.Filled.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.call_return_to, state.peerUsername.ifEmpty { "…" }), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
