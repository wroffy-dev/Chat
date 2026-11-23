package app.vaultchat.data.notes

import app.vaultchat.data.auth.AuthorizedCaller
import app.vaultchat.data.network.ApiResult
import app.vaultchat.data.network.HttpJson
import kotlinx.serialization.Serializable

/** A line added under a note, by its writer or a connected person (D-31 as changed 2026-11-23). */
@Serializable
data class NoteLineDto(
    val id: String,
    val body: String,
    val authorUsername: String,
    val mine: Boolean,
    val createdAt: String,
    /** The user's own name for the author (D-32), or null. */
    val authorNickname: String? = null,
) {
    /** The name shown for who added the line. */
    val authorName: String get() = authorNickname?.takeIf { it.isNotBlank() } ?: authorUsername
}

/**
 * A note as the server returns it (docs/14 §notes, D-31): the user's own ([mine]) or a connected person's, which
 * the user can only add lines to.
 */
@Serializable
data class NoteDto(
    val id: String,
    val title: String,
    val body: String,
    val createdAt: String,
    val updatedAt: String,
    val mine: Boolean = true,
    val authorUsername: String = "",
    val lines: List<NoteLineDto> = emptyList(),
    /** The user's own name for the writer and the writer's built-in avatar (D-32). */
    val authorNickname: String? = null,
    val authorAvatar: String? = null,
) {
    /** The name shown for the writer. */
    val authorName: String get() = authorNickname?.takeIf { it.isNotBlank() } ?: authorUsername
}

@Serializable
data class NotesDto(val items: List<NoteDto>)

@Serializable
private data class NoteBody(val title: String, val body: String)

@Serializable
private data class LineBody(val body: String)

/** Notes kept on the server: the user's own and those of the people they are connected with (D-31). */
class NotesApi(private val http: HttpJson, private val caller: AuthorizedCaller) {
    suspend fun list(): ApiResult<NotesDto> = caller.call { http.get("api/v1/notes", NotesDto.serializer(), it) }

    suspend fun create(title: String, body: String): ApiResult<NoteDto> =
        caller.call { http.post("api/v1/notes", NoteBody(title, body), NoteBody.serializer(), NoteDto.serializer(), it) }

    suspend fun update(id: String, title: String, body: String): ApiResult<NoteDto> =
        caller.call { http.patch("api/v1/notes/$id", NoteBody(title, body), NoteBody.serializer(), NoteDto.serializer(), it) }

    suspend fun delete(id: String): ApiResult<Unit> = caller.call { http.delete("api/v1/notes/$id", it) }

    suspend fun addLine(id: String, body: String): ApiResult<NoteDto> =
        caller.call { http.post("api/v1/notes/$id/lines", LineBody(body), LineBody.serializer(), NoteDto.serializer(), it) }

    suspend fun deleteLine(id: String, lineId: String): ApiResult<Unit> =
        caller.call { http.delete("api/v1/notes/$id/lines/$lineId", it) }

    companion object {
        /** Limits the server enforces (backend/src/notes/service.ts). */
        const val TITLE_MAX = 200
        const val BODY_MAX = 20_000
        const val LINE_MAX = 2_000
    }
}
