package app.vaultchat.domain.snap

/**
 * Snaps (owner decision D-27, 2026-11-19): a camera photo or a video of at most [MAX_VIDEO_SECONDS] seconds,
 * or since 2026-11-21 a photo or a video of at most [MAX_GALLERY_VIDEO_SECONDS] seconds from the phone's gallery,
 * sent to one paired person; the sender may mark it "view once" (no replay). The server keeps it until it is opened (then deletes it at once) or for at
 * most 24 h. The app holds an opened snap only in memory, for one replay.
 */
enum class SnapKind { PHOTO, VIDEO }

data class WaitingSnap(
    val id: String,
    val connectionId: String,
    val fromUsername: String,
    val kind: SnapKind,
    val createdAtEpochMs: Long,
    /** Shown a single time, no replay (owner, 2026-11-21). */
    val viewOnce: Boolean = false,
)

/** An opened snap: its bytes, held in memory only. */
class OpenedSnap(val kind: SnapKind, val bytes: ByteArray, val viewOnce: Boolean = false)

enum class SnapFailure { NOT_FOUND, TOO_LARGE, UNAVAILABLE, RATE_LIMITED, SESSION_ENDED, NETWORK, UNEXPECTED }

sealed interface SnapResult<out T> {
    data class Ok<T>(val value: T) : SnapResult<T>
    data class Failure(val reason: SnapFailure) : SnapResult<Nothing>
}

interface SnapRepository {
    /** Sends a snap; returns the streak with that person afterwards. [viewOnce]: no replay for them. */
    suspend fun send(connectionId: String, kind: SnapKind, bytes: ByteArray, viewOnce: Boolean = false): SnapResult<Int>
    suspend fun inbox(): SnapResult<List<WaitingSnap>>
    /** Downloads the snap; the server deletes it at the same moment, so this works once. */
    suspend fun open(snapId: String): SnapResult<OpenedSnap>
}

object SnapRules {
    const val MAX_VIDEO_SECONDS = 7
    /** A video picked from the phone's gallery (owner, 2026-11-21); it is made smaller before sending. */
    const val MAX_GALLERY_VIDEO_SECONDS = 30
    /** How long an opened photo stays on screen before it closes by itself. */
    const val PHOTO_SECONDS = 10
    /** An opened snap can be shown once more (owner: one replay). */
    const val REPLAYS = 1
}
