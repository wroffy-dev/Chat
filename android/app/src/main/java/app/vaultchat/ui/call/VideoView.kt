package app.vaultchat.ui.call

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack

/** Renders a LiveKit video track. The renderer is released when it leaves the composition. */
@Composable
fun VideoView(room: Room, track: VideoTrack?, mirror: Boolean, modifier: Modifier = Modifier) {
    var renderer by remember { mutableStateOf<TextureViewRenderer?>(null) }
    AndroidView(
        factory = { ctx ->
            TextureViewRenderer(ctx).also {
                room.initVideoRenderer(it)
                it.setMirror(mirror)
                renderer = it
            }
        },
        onRelease = { it.release() },
        modifier = modifier,
    )
    DisposableEffect(track, renderer) {
        val r = renderer
        if (track != null && r != null) track.addRenderer(r)
        onDispose { if (track != null && r != null) track.removeRenderer(r) }
    }
}
