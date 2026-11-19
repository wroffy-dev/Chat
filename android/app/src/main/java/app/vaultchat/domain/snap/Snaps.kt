package app.vaultchat.domain.snap

/**
 * Snaps (owner decision D-27, 2026-11-19): a camera photo or a video of at most [MAX_VIDEO_SECONDS] seconds,
 * sent to one paired person. The server keeps it until it is opened (then deletes it at once) or for at
 * most 24 h. The app holds an opened snap only in memory, for one replay.
 */
enum class SnapKind { PHOTO, VIDEO }

data class WaitingSnap(
    val id: String,
    val connectionId: String,
    val fromUsername: String,
    val kind: SnapKind,
    val createdAtEpochMs: Long,
)

/** An opened snap: its bytes, held in memory only. */
class OpenedSnap(val kind: SnapKind, val bytes: ByteArray)

enum class SnapFailure { NOT_FOUND, TOO_LARGE, UNAVAILABLE, RATE_LIMITED, SESSION_ENDED, NETWORK, UNEXPECTED }

sealed interface SnapResult<out T> {
    data class Ok<T>(val value: T) : SnapResult<T>
    data class Failure(val reason: SnapFailure) : SnapResult<Nothing>
}

interface SnapRepository {
    /** Sends a snap; returns the streak with that person afterwards. */
    suspend fun send(connectionId: String, kind: SnapKind, bytes: ByteArray): SnapResult<Int>
    suspend fun inbox(): SnapResult<List<WaitingSnap>>
    /** Downloads the snap; the server deletes it at the same moment, so this works once. */
    suspend fun open(snapId: String): SnapResult<OpenedSnap>
}

object SnapRules {
    const val MAX_VIDEO_SECONDS = 7
    /** How long an opened photo stays on screen before it closes by itself. */
    const val PHOTO_SECONDS = 10
    /** An opened snap can be shown once more (owner: one replay). */
    const val REPLAYS = 1
}
