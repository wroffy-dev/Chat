package app.vaultchat.platform.snap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

/**
 * A photo or video chosen from the phone's gallery for a snap (owner, 2026-11-21; changes D-27). The picker is
 * Android's own photo picker, so the app gets only the one item chosen and needs no storage permission. Like a
 * camera snap, nothing is kept: the photo is decoded in memory and a video is re-encoded into the app's private
 * cache, both until sent or retaken.
 */
object GalleryMedia {
    /** Longest side of a gallery photo, as for camera photos. */
    private const val PHOTO_LONG_SIDE = 3264

    /** A gallery video is re-encoded to at most this short side (720p) and bit rate: 30 s is about 13 MB. */
    private const val VIDEO_SHORT_SIDE = 720
    private const val VIDEO_BITRATE = 3_500_000

    sealed interface Video {
        class Ready(val file: File) : Video
        class TooLong(val seconds: Int) : Video
        data object Failed : Video
    }

    /** The photo upright (EXIF orientation applied by the decoder), at most [PHOTO_LONG_SIDE] px long. */
    suspend fun photo(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val longSide = max(info.size.width, info.size.height)
                if (longSide > PHOTO_LONG_SIDE) {
                    val scale = PHOTO_LONG_SIDE.toFloat() / longSide
                    decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                }
                // A software bitmap, so it can be copied and drawn on (text and pen) and compressed.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull()
    }

    /** Checks the length, then re-encodes the video smaller (H.264 720p, AAC) into the app's cache. */
    suspend fun video(context: Context, uri: Uri, maxSeconds: Int): Video {
        val info = withContext(Dispatchers.IO) { runCatching { probe(context, uri) }.getOrNull() } ?: return Video.Failed
        val seconds = ((info.durationMs + 999) / 1000).toInt()
        if (info.durationMs > maxSeconds * 1000L + 500) return Video.TooLong(seconds)
        val out = File(context.cacheDir, "gallery-${System.currentTimeMillis()}.mp4")
        val ok = withContext(Dispatchers.Main) { transcode(context, uri, info, out) }
        if (!ok) {
            out.delete()
            return Video.Failed
        }
        return Video.Ready(out)
    }

    private class Probe(val durationMs: Long, val width: Int, val height: Int)

    private fun probe(context: Context, uri: Uri): Probe {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            // The size as shown: a portrait phone video is stored landscape with a 90° rotation.
            return if (rotation % 180 == 0) Probe(duration, w, h) else Probe(duration, h, w)
        } finally {
            r.release()
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun transcode(context: Context, uri: Uri, info: Probe, out: File): Boolean = suspendCancellableCoroutine { cont ->
        val shortSide = min(info.width, info.height)
        val effects = if (shortSide > VIDEO_SHORT_SIDE && info.width > 0 && info.height > 0) {
            val scale = VIDEO_SHORT_SIDE.toFloat() / shortSide
            // Even sizes, as video encoders require.
            val w = ((info.width * scale).toInt() / 2) * 2
            val h = ((info.height * scale).toInt() / 2) * 2
            Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT)))
        } else {
            Effects.EMPTY
        }
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(
                DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(VIDEO_BITRATE).build())
                    .build(),
            )
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    if (cont.isActive) cont.resume(false)
                }
            })
            .build()
        val item = EditedMediaItem.Builder(MediaItem.fromUri(uri)).setEffects(effects).build()
        transformer.start(item, out.absolutePath)
        // The transformer must be stopped on the thread that started it (the main thread).
        cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { transformer.cancel() } }
    }
}
