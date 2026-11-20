package app.vaultchat.platform.snap

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Saves the user's own snap to the phone's gallery (owner decision 2026-11-20, D-27 changed): only a photo
 * or video this user just took, before sending it; received snaps are never saved. Uses MediaStore, so no
 * storage permission is needed (Android 10+). Pictures go to Pictures/Vault, videos to Movies/Vault.
 */
object SnapSaver {
    fun savePhoto(context: Context, jpeg: ByteArray): Boolean = save(
        context,
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        "Vault_${System.currentTimeMillis()}.jpg",
        "image/jpeg",
        Environment.DIRECTORY_PICTURES + "/Vault",
    ) { it.write(jpeg) }

    fun saveVideo(context: Context, file: File): Boolean = save(
        context,
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        "Vault_${System.currentTimeMillis()}.mp4",
        "video/mp4",
        Environment.DIRECTORY_MOVIES + "/Vault",
    ) { out -> file.inputStream().use { it.copyTo(out) } }

    private fun save(
        context: Context,
        collection: android.net.Uri,
        name: String,
        mime: String,
        folder: String,
        write: (java.io.OutputStream) -> Unit,
    ): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return false
        return try {
            resolver.openOutputStream(uri)?.use(write) ?: error("no output stream")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (_: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }
}
