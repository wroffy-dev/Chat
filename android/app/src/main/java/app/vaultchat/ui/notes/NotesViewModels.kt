package app.vaultchat.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vaultchat.data.network.ApiResult
import app.vaultchat.data.notes.NoteDto
import app.vaultchat.data.notes.NoteLineDto
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
    /** A pull-to-refresh is running. */
    val refreshing: Boolean = false,
    /** The session ended (401): back to the sign-in screen. */
    val signedOut: Boolean = false,
)

/** The list of the user's own notes, the screen the app opens on (D-31, owner 2026-11-23). */
class NotesViewModel(private val api: NotesApi) : ViewModel() {
    private val _state = MutableStateFlow(NotesState())
    val state: StateFlow<NotesState> = _state.asStateFlow()

    /** Reads the notes again; [pulled] shows the pull-to-refresh spinner meanwhile. */
    fun refresh(pulled: Boolean = false) {
        if (pulled) _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            when (val r = api.list()) {
                is ApiResult.Ok -> _state.update { it.copy(loading = false, refreshing = false, notes = r.value.items, offline = false) }
                is ApiResult.HttpError -> _state.update {
                    if (r.status == 401) it.copy(signedOut = true) else it.copy(loading = false, refreshing = false, offline = true)
                }
                ApiResult.NetworkError -> _state.update { it.copy(loading = false, refreshing = false, offline = true) }
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
    /** False for a connected person's note: it is read only, but lines can be added. */
    val mine: Boolean = true,
    val authorUsername: String = "",
    val authorAvatar: String? = null,
    val lines: List<NoteLineDto> = emptyList(),
    /** When the note last changed (ISO time), shown under the title. */
    val updatedAt: String? = null,
    val sendingLine: Boolean = false,
    /** Adding or removing a line failed. */
    val lineFailed: Boolean = false,
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
    /** A new note that starts as a checklist (owner 2026-11-25). */
    checklist: Boolean = false,
) : ViewModel() {
    private var id: String? = noteId
    private val _state = MutableStateFlow(
        EditorState(loading = noteId != null, exists = noteId != null, body = if (noteId == null && checklist) Checklist.OPEN else ""),
    )
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
                        _state.update { if (note == null) it.copy(loading = false, deleted = true) else it.copy(loading = false, title = note.title, body = note.body, mine = note.mine, authorUsername = note.authorName, authorAvatar = note.authorAvatar, lines = note.lines, updatedAt = note.updatedAt) }
                    }
                    is ApiResult.HttpError -> _state.update { if (r.status == 401) it.copy(signedOut = true) else it.copy(loading = false, save = SaveState.FAILED) }
                    ApiResult.NetworkError -> _state.update { it.copy(loading = false, save = SaveState.FAILED) }
                }
            }
        }
    }

    fun setTitle(value: String) = edit { it.copy(title = value.take(NotesApi.TITLE_MAX)) }

    fun setBody(value: String) = edit { it.copy(body = value.take(NotesApi.BODY_MAX)) }

    /** Saves the checklist's items (ticks and text) as the note's text. */
    fun setItems(items: List<CheckItem>) = setBody(Checklist.format(items))

    /** Switches the note between ordinary text and a checklist, keeping every line. */
    fun toggleChecklist() {
        val body = _state.value.body
        setBody(if (Checklist.isChecklist(body)) Checklist.toText(Checklist.parse(body)) else Checklist.format(Checklist.fromText(body)))
    }

    private fun edit(change: (EditorState) -> EditorState) {
        if (_state.value.loading || !_state.value.mine) return
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
        // A new note is created only once it has some text (an empty checklist counts as empty).
        if (existing == null && s.title.isBlank() && (s.body.isBlank() || Checklist.isEmpty(s.body))) return@withLock
        _state.update { it.copy(save = SaveState.SAVING) }
        val result = if (existing == null) api.create(s.title, s.body) else api.update(existing, s.title, s.body)
        when (result) {
            is ApiResult.Ok -> {
                id = result.value.id
                // Text typed while this request was on its way is saved by the next round.
                dirty = _state.value.title != s.title || _state.value.body != s.body
                _state.update { it.copy(save = SaveState.SAVED, exists = true, updatedAt = result.value.updatedAt) }
            }
            is ApiResult.HttpError -> _state.update { if (result.status == 401) it.copy(signedOut = true) else it.copy(save = SaveState.FAILED) }
            ApiResult.NetworkError -> _state.update { it.copy(save = SaveState.FAILED) }
        }
    }

    /** Adds [text] as a line under the note; true once the server has it. */
    fun addLine(text: String, done: (Boolean) -> Unit) {
        val existing = id ?: return
        val body = text.trim().take(NotesApi.LINE_MAX)
        if (body.isEmpty() || _state.value.sendingLine) return
        _state.update { it.copy(sendingLine = true, lineFailed = false) }
        viewModelScope.launch {
            when (val r = api.addLine(existing, body)) {
                is ApiResult.Ok -> {
                    _state.update { it.copy(sendingLine = false, lines = r.value.lines, updatedAt = r.value.updatedAt) }
                    done(true)
                }
                is ApiResult.HttpError -> {
                    _state.update {
                        when (r.status) {
                            401 -> it.copy(signedOut = true)
                            404 -> it.copy(deleted = true)
                            else -> it.copy(sendingLine = false, lineFailed = true)
                        }
                    }
                    done(false)
                }
                ApiResult.NetworkError -> {
                    _state.update { it.copy(sendingLine = false, lineFailed = true) }
                    done(false)
                }
            }
        }
    }

    /** Removes a line the user added, or any line under their own note. */
    fun deleteLine(lineId: String) {
        val existing = id ?: return
        viewModelScope.launch {
            when (val r = api.deleteLine(existing, lineId)) {
                is ApiResult.Ok -> _state.update { s -> s.copy(lines = s.lines.filterNot { it.id == lineId }, lineFailed = false) }
                is ApiResult.HttpError -> _state.update {
                    when (r.status) {
                        401 -> it.copy(signedOut = true)
                        404 -> it.copy(lines = it.lines.filterNot { l -> l.id == lineId })
                        else -> it.copy(lineFailed = true)
                    }
                }
                ApiResult.NetworkError -> _state.update { it.copy(lineFailed = true) }
            }
        }
    }

    fun delete() {
        if (!_state.value.mine) return
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
