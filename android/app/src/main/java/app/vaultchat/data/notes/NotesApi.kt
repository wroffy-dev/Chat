package app.vaultchat.data.notes

import app.vaultchat.data.auth.AuthorizedCaller
import app.vaultchat.data.network.ApiResult
import app.vaultchat.data.network.HttpJson
import kotlinx.serialization.Serializable

/** One of the user's own notes as the server returns it (docs/14 §notes, D-31). */
@Serializable
data class NoteDto(val id: String, val title: String, val body: String, val createdAt: String, val updatedAt: String)

@Serializable
data class NotesDto(val items: List<NoteDto>)

@Serializable
private data class NoteBody(val title: String, val body: String)

/** The signed-in user's notes, kept on the server and visible only to them (owner decision D-31, 2026-11-23). */
class NotesApi(private val http: HttpJson, private val caller: AuthorizedCaller) {
    suspend fun list(): ApiResult<NotesDto> = caller.call { http.get("api/v1/notes", NotesDto.serializer(), it) }

    suspend fun create(title: String, body: String): ApiResult<NoteDto> =
        caller.call { http.post("api/v1/notes", NoteBody(title, body), NoteBody.serializer(), NoteDto.serializer(), it) }

    suspend fun update(id: String, title: String, body: String): ApiResult<NoteDto> =
        caller.call { http.patch("api/v1/notes/$id", NoteBody(title, body), NoteBody.serializer(), NoteDto.serializer(), it) }

    suspend fun delete(id: String): ApiResult<Unit> = caller.call { http.delete("api/v1/notes/$id", it) }

    companion object {
        /** Limits the server enforces (backend/src/notes/service.ts). */
        const val TITLE_MAX = 200
        const val BODY_MAX = 20_000
    }
}
