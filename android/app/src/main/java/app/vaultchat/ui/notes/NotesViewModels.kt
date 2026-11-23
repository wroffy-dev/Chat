package app.vaultchat.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vaultchat.data.network.ApiResult
import app.vaultchat.data.notes.NoteDto
import app.vaultchat.data.notes.NotesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class NotesState(
    val loading: Boolean = true,
    val notes: List<NoteDto> = emptyList(),
    val offline: Boolean = false,
    /** The session ended (401): back to the sign-in screen. */
    val signedOut: Boolean = false,
)

/** The list of the user's own notes, the screen the app opens on (D-31, owner 2026-11-23). */
class NotesViewModel(private val api: NotesApi) : ViewModel() {
    private val _state = MutableStateFlow(NotesState())
    val state: StateFlow<NotesState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            when (val r = api.list()) {
                is ApiResult.Ok -> _state.update { it.copy(loading = false, notes = r.value.items, offline = false) }
                is ApiResult.HttpError -> _state.update { if (r.status == 401) it.copy(signedOut = true) else it.copy(loading = false, offline = true) }
                ApiResult.NetworkError -> _state.update { it.copy(loading = false, offline = true) }
            }
        }
    }
}

enum class SaveState { IDLE, SAVING, SAVED, FAILED }

data class EditorState(
    val loading: Boolean,
    val title: String = "",
    val body: String = "",
    val save: SaveState = SaveState.IDLE,
    val exists: Boolean = false,
    val deleted: Boolean = false,
    val signedOut: Boolean = false,
)

/**
 * One note being read or written. Changes are saved on their own shortly after typing stops, and once more when
 * leaving; a new note is created on the server only when it has some text.
 */
class NoteEditorViewModel(
    private val api: NotesApi,
    noteId: String?,
    /** Outlives this screen, so the last save still finishes after Back. */
    private val outlive: CoroutineScope,
) : ViewModel() {
    private var id: String? = noteId
    private val _state = MutableStateFlow(EditorState(loading = noteId != null, exists = noteId != null))
    val state: StateFlow<EditorState> = _state.asStateFlow()
    private val saving = Mutex()
    private var pending: Job? = null
    private var dirty = false

    init {
        if (noteId != null) {
            viewModelScope.launch {
                // There is no single-note read: the note comes from the user's own list.
                when (val r = api.list()) {
                    is ApiResult.Ok -> {
                        val note = r.value.items.firstOrNull { it.id == noteId }
                        _state.update { if (note == null) it.copy(loading = false, deleted = true) else it.copy(loading = false, title = note.title, body = note.body) }
                    }
                    is ApiResult.HttpError -> _state.update { if (r.status == 401) it.copy(signedOut = true) else it.copy(loading = false, save = SaveState.FAILED) }
                    ApiResult.NetworkError -> _state.update { it.copy(loading = false, save = SaveState.FAILED) }
                }
            }
        }
    }

    fun setTitle(value: String) = edit { it.copy(title = value.take(NotesApi.TITLE_MAX)) }

    fun setBody(value: String) = edit { it.copy(body = value.take(NotesApi.BODY_MAX)) }

    private fun edit(change: (EditorState) -> EditorState) {
        if (_state.value.loading) return
        _state.update(change)
        dirty = true
        pending?.cancel()
        pending = viewModelScope.launch {
            delay(SAVE_AFTER_MS)
            save()
        }
    }

    /** Saves what is not saved yet; called on leaving the note. The returned job ends when it is saved. */
    fun leave(): Job {
        pending?.cancel()
        return outlive.launch { if (dirty) save() }
    }

    private suspend fun save() = saving.withLock {
        if (!dirty) return@withLock
        val s = _state.value
        val existing = id
        if (existing == null && s.title.isBlank() && s.body.isBlank()) return@withLock
        _state.update { it.copy(save = SaveState.SAVING) }
        val result = if (existing == null) api.create(s.title, s.body) else api.update(existing, s.title, s.body)
        when (result) {
            is ApiResult.Ok -> {
                id = result.value.id
                // Text typed while this request was on its way is saved by the next round.
                dirty = _state.value.title != s.title || _state.value.body != s.body
                _state.update { it.copy(save = SaveState.SAVED, exists = true) }
            }
            is ApiResult.HttpError -> _state.update { if (result.status == 401) it.copy(signedOut = true) else it.copy(save = SaveState.FAILED) }
            ApiResult.NetworkError -> _state.update { it.copy(save = SaveState.FAILED) }
        }
    }

    fun delete() {
        pending?.cancel()
        viewModelScope.launch { saving.withLock { deleteLocked() } }
    }

    private suspend fun deleteLocked() {
        run {
            val existing = id
            if (existing == null) {
                dirty = false
                _state.update { it.copy(deleted = true) }
                return
            }
            when (val r = api.delete(existing)) {
                is ApiResult.Ok -> {
                    dirty = false
                    _state.update { it.copy(deleted = true) }
                }
                is ApiResult.HttpError -> _state.update {
                    when (r.status) {
                        401 -> it.copy(signedOut = true)
                        404 -> it.copy(deleted = true)
                        else -> it.copy(save = SaveState.FAILED)
                    }
                }
                ApiResult.NetworkError -> _state.update { it.copy(save = SaveState.FAILED) }
            }
        }
    }

    private companion object {
        const val SAVE_AFTER_MS = 800L
    }
}
